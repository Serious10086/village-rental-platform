package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.NoticeQueryDTO;
import com.gjf.village.vo.NoticeVO;

import java.util.Collection;

/**
 * 系统通知服务。
 */
public interface MsgNoticeService {

    /**
     * 发一条通知。
     *
     * @param userId     接收人
     * @param noticeType 通知类型,见 {@link com.gjf.village.entity.MsgNotice}
     * @param bizType    业务类型(用于点击跳转),可为 null
     * @param bizId      业务ID(用于点击跳转),可为 null
     */
    void send(Long userId, Integer noticeType, String title, String content,
              Integer bizType, Long bizId);

    /**
     * 批量发通知(如"到期提醒"要发给所有即将到期的租客)。
     */
    void sendBatch(Collection<Long> userIds, Integer noticeType, String title,
                   String content, Integer bizType, Long bizId);

    /**
     * 当前用户的通知列表。
     */
    PageResult<NoticeVO> pageMyNotices(NoticeQueryDTO query);

    /**
     * 当前用户的未读数量(前端红点用)。
     */
    long countUnread();

    /**
     * 标记一条通知为已读。
     *
     * <p>只能标记自己的通知 —— 不做归属校验的话,
     * 任何人都能把别人的通知标成已读。
     */
    void markRead(Long noticeId);

    /**
     * 全部标记为已读。
     */
    void markAllRead();

    /**
     * 删除一条通知(只能删自己的)。
     */
    void delete(Long noticeId);
}
