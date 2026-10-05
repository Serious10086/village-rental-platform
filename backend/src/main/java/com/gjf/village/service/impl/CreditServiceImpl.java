package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.CreditAdjustDTO;
import com.gjf.village.dto.CreditQueryDTO;
import com.gjf.village.entity.BizCreditRecord;
import com.gjf.village.entity.SysUser;
import com.gjf.village.entity.UserCreditSummary;
import com.gjf.village.mapper.BizCreditRecordMapper;
import com.gjf.village.mapper.SysUserMapper;
import com.gjf.village.mapper.UserCreditSummaryMapper;
import com.gjf.village.security.LoginUser;
import com.gjf.village.service.CreditService;
import com.gjf.village.service.DataScopeHelper;
import com.gjf.village.vo.CreditRecordVO;
import com.gjf.village.vo.CreditSummaryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 信用服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditServiceImpl implements CreditService {

    private final SysUserMapper userMapper;
    private final BizCreditRecordMapper creditRecordMapper;
    private final UserCreditSummaryMapper creditSummaryMapper;
    private final DataScopeHelper dataScopeHelper;

    /**
     * 信用等级门槛。
     *
     * <p>最低那档刻意跟下单门槛(60 分)对齐 —— 用户看到的"受限"
     * 必须正好是"下不了单",两套阈值各写各的迟早会对不上,
     * 出现"页面显示信用良好、下单却被拒"这种最招人烦的割裂。
     */
    private static final int LEVEL_EXCELLENT = 120;
    private static final int LEVEL_GOOD = 100;
    private static final int LEVEL_FAIR = 80;
    private static final int LEVEL_LIMITED = 60;

    /** 业务类型 → 中文名 */
    private static final Map<Integer, String> BIZ_TYPE_NAMES = Map.of(
            BizCreditRecord.BIZ_ORDER, "订单履约",
            BizCreditRecord.BIZ_RESOURCE_INTACT, "资源完好",
            BizCreditRecord.BIZ_EVALUATION, "用户评价",
            BizCreditRecord.BIZ_MANUAL, "管理员调整",
            BizCreditRecord.BIZ_VIOLATION, "违规处罚"
    );

    // ═══════════════════════════════════════════════════════════
    //  记账(全系统唯一入口)
    // ═══════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * <p><b>这里存在一个极小的竞态窗口</b>:先读旧分、再数据库端自增,
     * 读到的旧分可能不是最新的。之所以可以接受:
     * 分数本身是累计值,任何时刻的最终结果都正确;
     * 而流水里真正要保证的是 {@code afterScore - beforeScore == changeScore}
     * 这条恒等式,它在任何并发交错下都成立。
     *
     * <p>要做到绝对精确得给用户行加锁,代价大于收益 ——
     * 信用分不是余额,差一两分的瞬时误差不影响任何业务判断。
     */
    @Override
    public void adjust(Long userId, int delta, String reason, int bizType,
                       Long bizId, Long villageId) {
        if (userId == null || delta == 0) {
            return;
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            log.warn("信用分调整跳过:用户不存在 userId={}", userId);
            return;
        }

        int before = user.getCreditScore() == null ? 100 : user.getCreditScore();
        userMapper.increaseCreditScore(userId, delta);

        BizCreditRecord record = new BizCreditRecord();
        record.setUserId(userId);
        record.setChangeScore(delta);
        record.setBeforeScore(before);
        // afterScore 用 before + delta 算,而不是重新查一次库 ——
        // 重查可能读到别的并发变更,流水就不自洽了
        record.setAfterScore(Math.max(before + delta, 0));
        record.setReason(reason);
        record.setBizType(bizType);
        record.setBizId(bizId);
        record.setVillageId(villageId);
        creditRecordMapper.insert(record);

        log.info("信用分变更: userId={}, {}{} ({} → {}), 原因={}",
                userId, delta > 0 ? "+" : "", delta, before, before + delta, reason);
    }

    // ═══════════════════════════════════════════════════════════
    //  查询
    // ═══════════════════════════════════════════════════════════

    @Override
    public CreditSummaryVO getMyCredit() {
        return getCreditSummary(UserContext.getUserId());
    }

    @Override
    public CreditSummaryVO getCreditSummary(Long userId) {
        UserCreditSummary summary = creditSummaryMapper.selectByUserId(userId);
        if (summary == null) {
            throw BusinessException.notFound("用户不存在");
        }
        return toVO(summary);
    }

    /**
     * 我的信用流水。
     *
     * <p>强制写入当前用户ID —— 否则改一下参数就能翻别人被扣分的原因。
     * 扣分原因是相当敏感的信息(可能写着"恶意损坏设备"),
     * 不能靠"猜不到用户ID"来保护。
     */
    @Override
    public PageResult<CreditRecordVO> pageMyRecords(CreditQueryDTO query) {
        query.setUserId(UserContext.getUserId());
        query.setVillageIds(null);   // 看自己的流水,不受村组限制
        return pageRecords(query);
    }

    // ── 后台 ────────────────────────────────────────────────────

    /**
     * 后台信用列表。
     *
     * <p>注入数据范围:村级管理员只看得到本村用户。
     * 这里<b>不能</b>像前台那样清空 villageIds —— 那正是后台与前台的分界。
     */
    @Override
    public PageResult<CreditSummaryVO> pageCreditList(CreditQueryDTO query) {
        dataScopeHelper.apply(query);

        Page<UserCreditSummary> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<UserCreditSummary> result = creditSummaryMapper.selectCreditPage(page, query);

        List<CreditSummaryVO> voList = result.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(result, voList);
    }

    /**
     * 后台查看指定用户的信用流水。
     *
     * <p>这里要校验一次归属:村级管理员不能翻别村用户的扣分记录。
     * 列表接口有 villageIds 兜着,但"按 userId 查流水"这条路径绕过了列表,
     * 必须在查到数据之后单独校验。
     */
    @Override
    public PageResult<CreditRecordVO> pageUserRecords(CreditQueryDTO query) {
        if (query.getUserId() == null) {
            throw BusinessException.badRequest("请指定要查看的用户");
        }
        UserCreditSummary target = creditSummaryMapper.selectByUserId(query.getUserId());
        if (target == null) {
            throw BusinessException.notFound("用户不存在");
        }
        LoginUser me = UserContext.get();
        if (!me.isSuperAdmin() && !dataScopeHelper.canAccessVillage(target.getVillageId())) {
            throw BusinessException.notFound("用户不存在");
        }
        query.setVillageIds(null);
        return pageRecords(query);
    }

    private PageResult<CreditRecordVO> pageRecords(CreditQueryDTO query) {
        Page<CreditRecordVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<CreditRecordVO> result = creditRecordMapper.selectRecordPage(page, query);
        result.getRecords().forEach(r ->
                r.setBizTypeName(BIZ_TYPE_NAMES.getOrDefault(r.getBizType(), "其他")));
        return PageResult.of(result);
    }

    // ═══════════════════════════════════════════════════════════
    //  管理员手动调整
    // ═══════════════════════════════════════════════════════════

    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void adjustByAdmin(CreditAdjustDTO dto) {
        if (dto.getDelta() == null || dto.getDelta() == 0) {
            throw BusinessException.badRequest("调整分值不能为 0");
        }
        if (!StringUtils.hasText(dto.getReason())) {
            throw BusinessException.badRequest("请填写调整原因 —— 用户会看到这条说明");
        }

        UserCreditSummary target = creditSummaryMapper.selectByUserId(dto.getUserId());
        if (target == null) {
            throw BusinessException.notFound("用户不存在");
        }

        // 与"查看流水"同样的越权防线:村管只能调本村用户的分
        LoginUser me = UserContext.get();
        if (!me.isSuperAdmin() && !dataScopeHelper.canAccessVillage(target.getVillageId())) {
            throw BusinessException.forbidden("只能调整本村用户的信用分");
        }

        adjust(dto.getUserId(), dto.getDelta(), dto.getReason(),
                BizCreditRecord.BIZ_MANUAL, null, target.getVillageId());
    }

    // ═══════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════

    /** 视图对象 → 展示对象,补上信用等级 */
    private CreditSummaryVO toVO(UserCreditSummary summary) {
        CreditSummaryVO vo = new CreditSummaryVO();
        BeanUtils.copyProperties(summary, vo);

        int score = summary.getCreditScore() == null ? 100 : summary.getCreditScore();
        applyLevel(vo, score);
        return vo;
    }

    /**
     * 按分数贴等级标签。
     *
     * <p>文案写成"你该做什么"而不是"你是什么等级":
     * 用户看到"信用较差"只会觉得被骂了,看到"接近下单门槛,再低将无法下单"
     * 才知道该去把没结的订单处理掉。
     */
    private void applyLevel(CreditSummaryVO vo, int score) {
        if (score >= LEVEL_EXCELLENT) {
            vo.setLevel("信用优秀");
            vo.setLevelType("success");
            vo.setLevelDesc("履约记录良好,平台将优先展示你的资源");
            vo.setToNextLevel(0);
        } else if (score >= LEVEL_GOOD) {
            vo.setLevel("信用良好");
            vo.setLevelType("primary");
            vo.setLevelDesc("一切正常,继续保持按时履约");
            vo.setToNextLevel(LEVEL_EXCELLENT - score);
        } else if (score >= LEVEL_FAIR) {
            vo.setLevel("信用一般");
            vo.setLevelType("warning");
            vo.setLevelDesc("有过取消或纠纷记录,注意按时履约可以逐步恢复");
            vo.setToNextLevel(LEVEL_GOOD - score);
        } else if (score >= LEVEL_LIMITED) {
            vo.setLevel("信用较差");
            vo.setLevelType("warning");
            vo.setLevelDesc("已接近下单门槛,低于 " + LEVEL_LIMITED + " 分将无法下单");
            vo.setToNextLevel(LEVEL_FAIR - score);
        } else {
            vo.setLevel("信用受限");
            vo.setLevelType("danger");
            vo.setLevelDesc("已低于下单门槛(" + LEVEL_LIMITED + " 分),暂时无法下单。"
                    + "如有异议可联系村级管理员核实");
            vo.setToNextLevel(LEVEL_LIMITED - score);
        }
    }
}
