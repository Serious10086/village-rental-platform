package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内信会话表。
 *
 * <p><b>本表有一条必须遵守的写入约定:</b>{@code user_a_id} 取两人中较小的 ID,
 * {@code user_b_id} 取较大的。否则 (1,2) 和 (2,1) 会被当成两个不同的会话,
 * 唯一约束 {@code uk_conv} 形同虚设 —— 甲乙各开一个会话,消息分散在两处,
 * 谁都看不到完整的对话。这个约定由 Service 层统一保证(见 {@code ChatServiceImpl}),
 * 调用方不需要关心。
 *
 * <p><b>已知设计权衡:</b>MySQL 唯一索引不约束 NULL,所以当
 * {@code resource_id IS NULL}(不针对具体资源的私聊)时,{@code uk_conv}
 * 无法阻止重复会话,只能靠应用层"先查后插"保证。"resource_id 用 0 占位"
 * 的方案行不通 —— 0 不存在于 {@code biz_resource},会违反外键约束。
 *
 * <p><b>未读数为什么冗余存两列而不是每次 count:</b>会话列表要为每个会话
 * 显示未读数,若实时统计就是对每条会话各跑一次 COUNT,十个会话十次查询。
 * 冗余存的代价是要保证它与消息表一致,而这个一致性通过
 * {@code UPDATE ... SET a_unread = a_unread + 1} 在数据库端自增来保证 ——
 * 不是"读出来加一再写回",那种写法在并发下发两条消息会只加一次。
 */
@Data
@TableName("msg_conversation")
public class MsgConversation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户A —— 约定为两人中 id 较小的那个 */
    private Long userAId;

    /** 用户B —— 约定为两人中 id 较大的那个 */
    private Long userBId;

    /** 关联资源ID,可为空(不是就资源发起的私聊) */
    private Long resourceId;

    /** 关联订单ID,可为空 */
    private Long orderId;

    /** 最后一条消息(冗余,列表展示用 —— 否则列表要为每条会话再查一次消息表) */
    private String lastMessage;

    /** 最后消息时间,会话列表按它倒序 */
    private LocalDateTime lastTime;

    /** 用户A的未读数 */
    private Integer aUnread;

    /** 用户B的未读数 */
    private Integer bUnread;

    private LocalDateTime createTime;

    // ── 辅助方法 ────────────────────────────────────────────────

    /** 判断某人是否属于这个会话 */
    public boolean contains(Long userId) {
        return userId != null && (userId.equals(userAId) || userId.equals(userBId));
    }

    /** 取会话里的另一方 */
    public Long peerOf(Long userId) {
        return userId.equals(userAId) ? userBId : userAId;
    }
}
