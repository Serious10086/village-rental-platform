package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.ResultCode;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.NoticeQueryDTO;
import com.gjf.village.entity.MsgNotice;
import com.gjf.village.mapper.MsgNoticeMapper;
import com.gjf.village.service.MsgNoticeService;
import com.gjf.village.vo.NoticeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 系统通知服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MsgNoticeServiceImpl implements MsgNoticeService {

    private final MsgNoticeMapper noticeMapper;

    /** 类型编码 → 中文名 */
    private static final Map<Integer, String> TYPE_NAMES = Map.ofEntries(
            Map.entry(MsgNotice.TYPE_REALNAME, "实名认证"),
            Map.entry(MsgNotice.TYPE_RESOURCE, "资源审核"),
            Map.entry(MsgNotice.TYPE_ORDER, "订单状态"),
            Map.entry(MsgNotice.TYPE_RENT_EXPIRE, "到期提醒"),
            Map.entry(MsgNotice.TYPE_DEPOSIT, "押金退还"),
            Map.entry(MsgNotice.TYPE_ANNOUNCEMENT, "系统公告"),
            Map.entry(MsgNotice.TYPE_DISPUTE, "纠纷处理"),
            Map.entry(MsgNotice.TYPE_CREDIT, "信用变动"),
            Map.entry(MsgNotice.TYPE_WITHDRAW, "提现通知"),
            Map.entry(MsgNotice.TYPE_BOARD, "留言互动"),
            // 第 11 种类型 —— Map.of 只支持到 10 对键值,所以上面换成了 Map.ofEntries
            Map.entry(MsgNotice.TYPE_OUTSIDE, "外来用户备案")
    );

    @Override
    public void send(Long userId, Integer noticeType, String title, String content,
                     Integer bizType, Long bizId) {
        if (userId == null) {
            log.warn("通知接收人为空,已跳过: title={}", title);
            return;
        }
        MsgNotice notice = new MsgNotice();
        notice.setUserId(userId);
        notice.setNoticeType(noticeType);
        notice.setTitle(title);
        notice.setContent(content);
        notice.setBizType(bizType);
        notice.setBizId(bizId);
        notice.setIsRead(0);
        noticeMapper.insert(notice);
    }

    /**
     * 批量发送。
     *
     * <p>逐条 insert 而不是拼一条多值 INSERT:数量级不大(最多几百条),
     * 而逐条的写法能直接复用 MyBatis-Plus 的自动填充,
     * 也便于将来某条失败时单独重试。
     */
    @Override
    public void sendBatch(Collection<Long> userIds, Integer noticeType, String title,
                          String content, Integer bizType, Long bizId) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        userIds.forEach(uid -> send(uid, noticeType, title, content, bizType, bizId));
    }

    @Override
    public PageResult<NoticeVO> pageMyNotices(NoticeQueryDTO query) {
        Long userId = UserContext.getUserId();

        LambdaQueryWrapper<MsgNotice> wrapper = new LambdaQueryWrapper<MsgNotice>()
                // 只查自己的 —— 这个条件是硬性的,不接受前端传入
                .eq(MsgNotice::getUserId, userId)
                .eq(query.getNoticeType() != null, MsgNotice::getNoticeType, query.getNoticeType())
                .eq(query.getIsRead() != null, MsgNotice::getIsRead, query.getIsRead())
                .orderByDesc(MsgNotice::getCreateTime);

        Page<MsgNotice> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<MsgNotice> result = noticeMapper.selectPage(page, wrapper);

        List<NoticeVO> voList = result.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(result, voList);
    }

    @Override
    public long countUnread() {
        // 用 count 而不是查列表再 size:未读数接口前端会轮询,数据量越小越好
        return noticeMapper.selectCount(new LambdaQueryWrapper<MsgNotice>()
                .eq(MsgNotice::getUserId, UserContext.getUserId())
                .eq(MsgNotice::getIsRead, 0));
    }

    @Override
    public void markRead(Long noticeId) {
        // 先查出来校验归属,再更新。
        //
        // 不校验会怎样:通知ID是自增的,任何人改一下URL里的数字
        // 就能把别人的通知标成已读 —— 虽然后果不严重,但属于越权。
        MsgNotice notice = noticeMapper.selectById(noticeId);
        if (notice == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "通知不存在");
        }
        if (!Objects.equals(notice.getUserId(), UserContext.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能操作自己的通知");
        }
        if (Integer.valueOf(1).equals(notice.getIsRead())) {
            return;   // 已读的不用重复更新
        }

        MsgNotice update = new MsgNotice();
        update.setId(noticeId);
        update.setIsRead(1);
        update.setReadTime(LocalDateTime.now());
        noticeMapper.updateById(update);
    }

    @Override
    public void markAllRead() {
        noticeMapper.update(null, new LambdaUpdateWrapper<MsgNotice>()
                .eq(MsgNotice::getUserId, UserContext.getUserId())
                .eq(MsgNotice::getIsRead, 0)
                .set(MsgNotice::getIsRead, 1)
                .set(MsgNotice::getReadTime, LocalDateTime.now()));
    }

    @Override
    public void delete(Long noticeId) {
        MsgNotice notice = noticeMapper.selectById(noticeId);
        if (notice == null) {
            return;
        }
        if (!Objects.equals(notice.getUserId(), UserContext.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能删除自己的通知");
        }
        noticeMapper.deleteById(noticeId);
    }

    // ── 内部方法 ────────────────────────────────────────────────

    private NoticeVO toVO(MsgNotice notice) {
        NoticeVO vo = new NoticeVO();
        vo.setId(notice.getId());
        vo.setNoticeType(notice.getNoticeType());
        vo.setNoticeTypeName(TYPE_NAMES.getOrDefault(notice.getNoticeType(), "系统消息"));
        vo.setTitle(notice.getTitle());
        vo.setContent(notice.getContent());
        vo.setBizType(notice.getBizType());
        vo.setBizId(notice.getBizId());
        vo.setIsRead(notice.getIsRead());
        vo.setReadTime(notice.getReadTime());
        vo.setCreateTime(notice.getCreateTime());
        vo.setLinkPath(buildLinkPath(notice));
        return vo;
    }

    /**
     * 由通知类型和业务ID算出跳转路径。
     *
     * <p>放在后端而不是前端:跳转规则和业务强相关,
     * 集中在这里改一处就生效,前端不用为每种类型写一段 if-else。
     */
    private String buildLinkPath(MsgNotice notice) {
        // 外来用户备案按 noticeType 判,不走下面的 bizType —— 它在 bizType
        // 那套编号里没有可用值,原因见 switch 上方的说明。
        // 落点是个人中心:那里的「村组」字段在备案通过前显示"未绑定村组",
        // 通过后变成目标村名,本人能在这个页面上看到结果
        if (Objects.equals(notice.getNoticeType(), MsgNotice.TYPE_OUTSIDE)) {
            return "/user/profile";
        }

        Integer bizType = notice.getBizType();
        Long bizId = notice.getBizId();
        if (bizType == null || bizId == null) {
            return null;
        }
        return switch (bizType) {
            // ⚠ 这套 bizType 是 MsgNotice 自己的编号,与 sys_audit_record.biz_type
            // <b>只在 1/2/4 上碰巧相同</b>:3 在那边是"外来用户备案",在这边是"订单"。
            // 照抄另一套的值会把通知链到不相干的页面 —— 备案改走上面的 noticeType 分支,
            // 正是因为它在这套编号里没有对应值
            case 1 -> "/user/profile";          // 实名认证
            case 2 -> "/resource/" + bizId;      // 资源
            case 3 -> "/my/order";               // 订单
            case 4 -> "/user/wallet";            // 提现
            // 5/6 是批次 6c 加的。「回复了我的留言」直接带着 postId 跳,
            // 前端据此把那条留言的回复展开 —— 否则用户点进来还要自己
            // 在列表里翻找被回复的是哪一条
            case 5 -> "/board?postId=" + bizId;        // 留言
            case 6 -> "/announcement/" + bizId;        // 公告
            default -> null;
        };
    }
}
