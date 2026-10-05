package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.common.enums.OrderStatusEnum;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.ResultCode;
import com.gjf.village.common.util.SensitiveWordUtil;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.EvaluationCreateDTO;
import com.gjf.village.dto.EvaluationQueryDTO;
import com.gjf.village.dto.EvaluationReplyDTO;
import com.gjf.village.entity.BizCreditRecord;
import com.gjf.village.entity.BizEvaluation;
import com.gjf.village.entity.BizOrder;
import com.gjf.village.entity.MsgNotice;
import com.gjf.village.mapper.BizEvaluationMapper;
import com.gjf.village.mapper.BizOrderMapper;
import com.gjf.village.security.LoginUser;
import com.gjf.village.service.BizEvaluationService;
import com.gjf.village.service.AiBehaviorService;
import com.gjf.village.service.CreditService;
import com.gjf.village.service.DataScopeHelper;
import com.gjf.village.service.MsgNoticeService;
import com.gjf.village.vo.EvaluationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评价服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizEvaluationServiceImpl implements BizEvaluationService {

    private final BizEvaluationMapper evaluationMapper;
    private final BizOrderMapper orderMapper;
    private final CreditService creditService;
    private final MsgNoticeService noticeService;
    private final DataScopeHelper dataScopeHelper;
    private final SensitiveWordUtil sensitiveWordUtil;
    private final AiBehaviorService behaviorService;

    // ── 评价对信用分的影响 ──────────────────────────────────────
    //
    // 这三个数字就是"基于用户评价的信用分"这条需求的全部实现。
    // 阈值这样定:
    //   · 好评(4-5 分)加 1 分 —— 一单只加 1 分,刷好评的收益很低,
    //     而正常做生意的人慢慢就能攒起来
    //   · 中评(3 分)不加不减 —— 没出问题也没特别满意,不构成信用信号
    //   · 差评(1-2 分)扣 2 分 —— 扣分要比加分狠,这样"信用受损"
    //     才会被当成一件要认真对待的事
    //
    // 注意这与"订单完成 +2"是两笔独立的账:前者是履约,后者是被认可,
    // 一单走完最多 +3。两条流水分开记,用户能看到分是从哪来的。
    private static final int SCORE_GOOD = 4;
    private static final int SCORE_NEUTRAL = 3;
    private static final int CREDIT_ON_GOOD = 1;
    private static final int CREDIT_ON_BAD = -2;

    /** 订单类型:租赁 / 购买(用于文案) */
    private static final int TYPE_RENT = 1;

    private static final String ANONYMOUS_NAME = "匿名用户";

    // ═══════════════════════════════════════════════════════════
    //  提交评价
    // ═══════════════════════════════════════════════════════════

    /**
     * 提交评价。
     *
     * <p><b>被评价人是谁由服务端从订单里推出来,不接受前端传入。</b>
     * 否则任何人都能伪造一条"评价某人"的记录去刷分,而且
     * {@code uk_order_from} 唯一约束只在"同一人对同一单"这一维度上生效,
     * 它挡不住"对着任意订单评价任意人"。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public Long create(EvaluationCreateDTO dto) {
        LoginUser me = UserContext.get();
        BizOrder order = orderMapper.selectById(dto.getOrderId());
        if (order == null) {
            throw BusinessException.notFound("订单不存在");
        }

        // ── ① 只有已完成的订单才能评价 ──────────────────────────
        // 订单还在进行中就评价,评价的是"还没发生的事"。而且未完成的订单
        // 可能被取消,那样这条评价就成了无源之水。
        OrderStatusEnum status = OrderStatusEnum.of(order.getOrderStatus());
        if (status != OrderStatusEnum.FINISHED) {
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "订单当前状态为「" + status.getDescription() + "」,完成之后才能评价");
        }

        // ── ② 只有交易双方能评价,且被评价人是对方 ──────────────
        boolean isBuyer = Objects.equals(order.getBuyerId(), me.getUserId());
        boolean isSeller = Objects.equals(order.getSellerId(), me.getUserId());
        if (!isBuyer && !isSeller) {
            throw BusinessException.forbidden("只有这笔订单的交易双方可以评价");
        }
        Long toUserId = isBuyer ? order.getSellerId() : order.getBuyerId();

        // ── ③ 一单一评 ──────────────────────────────────────────
        // 这里先查一次是为了给出友好提示;真正拦住重复提交的是
        // 数据库的 uk_order_from 唯一约束 —— Service 层的"先查后插"
        // 在并发下有两个请求同时查到"还没评过"的窗口。
        BizEvaluation exists = evaluationMapper.selectByOrderAndFrom(
                order.getId(), me.getUserId());
        if (exists != null) {
            throw BusinessException.badRequest("你已经评价过这笔订单了");
        }

        // ── ④ 敏感词 ────────────────────────────────────────────
        checkSensitive(dto);

        // ── ⑤ 写评价 ────────────────────────────────────────────
        BizEvaluation evaluation = new BizEvaluation();
        evaluation.setOrderId(order.getId());
        evaluation.setResourceId(order.getResourceId());
        evaluation.setFromUserId(me.getUserId());
        evaluation.setToUserId(toUserId);
        evaluation.setScore(dto.getScore());
        evaluation.setResourceScore(dto.getResourceScore());
        evaluation.setServiceScore(dto.getServiceScore());
        evaluation.setContent(dto.getContent());
        evaluation.setImages(join(dto.getImages()));
        evaluation.setIsAnonymous(Boolean.TRUE.equals(dto.getAnonymous()) ? 1 : 0);
        evaluation.setVillageId(order.getVillageId());
        evaluation.setStatus(BizEvaluation.STATUS_NORMAL);
        evaluationMapper.insert(evaluation);

        // ── ⑥ 按评分调整被评价人的信用分 ────────────────────────
        int delta = creditDeltaOf(dto.getScore());
        if (delta != 0) {
            creditService.adjust(toUserId, delta, buildCreditReason(dto.getScore(), order.getOrderNo()),
                    BizCreditRecord.BIZ_EVALUATION, evaluation.getId(), order.getVillageId());
        }

        // ── ⑦ 通知被评价人 ──────────────────────────────────────
        noticeService.send(toUserId, MsgNotice.TYPE_ORDER,
                "你收到一条新评价",
                "订单 " + order.getOrderNo() + " 对方给了你 " + dto.getScore() + " 星评价"
                        + (delta > 0 ? ",信用分 +" + delta : delta < 0 ? ",信用分 " + delta : "")
                        + (dto.getContent() != null && !dto.getContent().isBlank()
                            ? "。评价内容:" + abbreviate(dto.getContent()) : "。"),
                3, order.getId());

        // ── ⑧ 记一条评价行为(推荐用) ────────────────────────────
        // 评价意味着这笔交易真的完成了,是"这个方向他确实感兴趣"的
        // 最强证据之一(权重仅次于下单)。放在事务里,和评价本身同生共死。
        behaviorService.evaluated(order.getResourceId());

        log.info("评价提交: evaluationId={}, orderNo={}, from={}, to={}, score={}, 信用{}",
                evaluation.getId(), order.getOrderNo(), me.getUserId(), toUserId, dto.getScore(), delta);
        return evaluation.getId();
    }

    /**
     * 被评价人回复评价。
     *
     * <p>只能回复一次、且只能由被评价人本人回复。允许无限次回复的话,
     * 差评下面会变成对骂现场,后来的租户反而看不清事实。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void reply(Long id, EvaluationReplyDTO dto) {
        BizEvaluation evaluation = evaluationMapper.selectById(id);
        if (evaluation == null) {
            throw BusinessException.notFound("评价不存在");
        }
        if (!Objects.equals(evaluation.getToUserId(), UserContext.getUserId())) {
            throw BusinessException.forbidden("只有被评价人可以回复这条评价");
        }
        if (evaluation.getReplyContent() != null && !evaluation.getReplyContent().isBlank()) {
            throw BusinessException.badRequest("这条评价你已经回复过了");
        }
        if (sensitiveWordUtil.shouldBlock(dto.getReplyContent())) {
            Set<String> hits = sensitiveWordUtil.findAll(dto.getReplyContent());
            throw new BusinessException(ResultCode.SENSITIVE_CONTENT,
                    "回复包含违规词,请修改后重试:" + String.join("、", hits));
        }

        BizEvaluation update = new BizEvaluation();
        update.setId(id);
        update.setReplyContent(sensitiveWordUtil.filter(dto.getReplyContent()));
        update.setReplyTime(LocalDateTime.now());
        evaluationMapper.updateById(update);

        noticeService.send(evaluation.getFromUserId(), MsgNotice.TYPE_ORDER,
                "你的评价收到了回复",
                "你给订单的评价收到了对方的回复:" + abbreviate(dto.getReplyContent()),
                3, evaluation.getOrderId());

        log.info("评价回复: evaluationId={}, by={}", id, UserContext.getUserId());
    }

    // ═══════════════════════════════════════════════════════════
    //  查询
    // ═══════════════════════════════════════════════════════════

    /** 我收到的评价 */
    @Override
    public PageResult<EvaluationVO> pageReceived(EvaluationQueryDTO query) {
        query.setToUserId(UserContext.getUserId());
        query.setFromUserId(null);
        query.setVillageIds(null);
        query.setStatus(BizEvaluation.STATUS_NORMAL);
        return pageEvaluations(query);
    }

    /** 我发出的评价 */
    @Override
    public PageResult<EvaluationVO> pageSent(EvaluationQueryDTO query) {
        query.setFromUserId(UserContext.getUserId());
        query.setToUserId(null);
        query.setVillageIds(null);
        // 自己写过的评价,即使后来被管理员隐藏了也应当能看到 ——
        // 否则用户会以为自己的评价凭空消失了
        query.setStatus(null);
        return pageEvaluations(query);
    }

    /**
     * 某个资源下的评价(前台公开)。
     *
     * <p>强制 status = 1:被管理员隐藏的违规评价不能再露出来。
     * 这个条件不能让前端传 —— 否则把 status 设成 2 就能把别人的
     * 违规评价翻出来看。
     */
    @Override
    public PageResult<EvaluationVO> pageByResource(EvaluationQueryDTO query) {
        if (query.getResourceId() == null) {
            throw BusinessException.badRequest("请指定资源");
        }
        query.setFromUserId(null);
        query.setToUserId(null);
        query.setVillageIds(null);
        query.setStatus(BizEvaluation.STATUS_NORMAL);
        return pageEvaluations(query);
    }

    /** 某笔订单的评价 —— 订单详情页据此显示"待评价/已评价" */
    @Override
    public PageResult<EvaluationVO> listByOrder(Long orderId) {
        EvaluationQueryDTO query = new EvaluationQueryDTO();
        query.setOrderId(orderId);
        query.setPageSize(10L);
        return pageEvaluations(query);
    }

    // ── 后台 ────────────────────────────────────────────────────

    /**
     * 后台评价管理。
     *
     * <p>与前台的三点差别:注入数据范围(只看本村)、
     * 不过滤 status(被隐藏的也要看得到,否则隐藏了就没法恢复)、
     * 支持 onlyBad 只看差评。
     */
    @Override
    public PageResult<EvaluationVO> pageAdmin(EvaluationQueryDTO query) {
        dataScopeHelper.apply(query);
        query.setFromUserId(null);
        query.setToUserId(null);
        return pageEvaluations(query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void hide(Long id) {
        BizEvaluation evaluation = requireManageable(id);
        BizEvaluation update = new BizEvaluation();
        update.setId(id);
        update.setStatus(BizEvaluation.STATUS_HIDDEN);
        evaluationMapper.updateById(update);
        log.info("评价已隐藏: id={}, 操作人={}", id, UserContext.getUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void show(Long id) {
        requireManageable(id);
        BizEvaluation update = new BizEvaluation();
        update.setId(id);
        update.setStatus(BizEvaluation.STATUS_NORMAL);
        evaluationMapper.updateById(update);
        log.info("评价已恢复: id={}, 操作人={}", id, UserContext.getUserId());
    }

    /**
     * 取出评价并校验管理权限。
     *
     * <p>隐藏/恢复是写操作,同样要过数据级权限 ——
     * 只在列表查询里过滤、写接口不校验的话,村管改一下请求里的 ID
     * 就能操作别村的评价。
     */
    private BizEvaluation requireManageable(Long id) {
        BizEvaluation evaluation = evaluationMapper.selectById(id);
        if (evaluation == null) {
            throw BusinessException.notFound("评价不存在");
        }
        LoginUser me = UserContext.get();
        if (!me.isSuperAdmin() && !dataScopeHelper.canAccessVillage(evaluation.getVillageId())) {
            throw BusinessException.forbidden("只能管理本村的评价");
        }
        return evaluation;
    }

    // ═══════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════

    private PageResult<EvaluationVO> pageEvaluations(EvaluationQueryDTO query) {
        Page<EvaluationVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<EvaluationVO> result = evaluationMapper.selectEvaluationPage(page, query);
        result.getRecords().forEach(this::maskAnonymous);
        return PageResult.of(result);
    }

    /**
     * 匿名脱敏。
     *
     * <p>数据库里始终存着真实的 from_user_id —— 纠纷处理时平台需要能追溯到人;
     * 这里只处理<b>展示</b>:把姓名换成"匿名用户"、头像清空。
     *
     * <p>反过来做(匿名就不查用户信息)更省事,但一旦将来要做
     * "争议评价核实",数据链就断了。<b>保留事实、控制展示</b> 是更稳的分工。
     */
    private void maskAnonymous(EvaluationVO vo) {
        LoginUser me = UserContext.getOrNull();
        boolean isSelf = me != null && Objects.equals(me.getUserId(), vo.getFromUserId());

        if (Integer.valueOf(1).equals(vo.getIsAnonymous()) && !isSelf) {
            vo.setFromUserName(ANONYMOUS_NAME);
            vo.setFromUserAvatar(null);
            // 匿名的同时把 ID 也抹掉 —— 只改名字的话,
            // 前端一比对 fromUserId 就能知道是谁写的
            vo.setFromUserId(null);
        }

        boolean canReply = me != null
                && Objects.equals(me.getUserId(), vo.getToUserId())
                && (vo.getReplyContent() == null || vo.getReplyContent().isBlank());
        vo.setCanReply(canReply);
    }

    /**
     * 评分 → 信用分变更量。
     *
     * <p>抽成方法而不是散在 create 里,是为了将来调阈值时只有一处要改,
     * 也方便测试直接对着这张映射表写用例。
     */
    private int creditDeltaOf(Integer score) {
        if (score == null) {
            return 0;
        }
        if (score >= SCORE_GOOD) {
            return CREDIT_ON_GOOD;
        }
        if (score == SCORE_NEUTRAL) {
            return 0;
        }
        return CREDIT_ON_BAD;
    }

    private String buildCreditReason(Integer score, String orderNo) {
        String label = score >= SCORE_GOOD ? "收到好评" : "收到差评";
        return "订单 " + orderNo + " " + label + "(" + score + " 星)";
    }

    /**
     * 评价内容的敏感词检查。
     *
     * <p>与资源发布、聊天用的是同一套 DFA 词库和同一套两级处理:
     * 拦截类直接拒绝,替换类打码放行。评价是公开内容,
     * 一条带辱骂词汇的差评挂在资源下面,影响的是整个平台的观感。
     */
    private void checkSensitive(EvaluationCreateDTO dto) {
        if (dto.getContent() == null || dto.getContent().isBlank()) {
            return;
        }
        if (sensitiveWordUtil.shouldBlock(dto.getContent())) {
            Set<String> hits = sensitiveWordUtil.findAll(dto.getContent());
            throw new BusinessException(ResultCode.SENSITIVE_CONTENT,
                    "评价内容包含违规词,请修改后重试:" + String.join("、", hits));
        }
        dto.setContent(sensitiveWordUtil.filter(dto.getContent()));
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    /** List → 逗号分隔字符串 */
    private String join(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
