package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gjf.village.entity.MsgConversation;
import com.gjf.village.vo.ConversationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 站内信会话 Mapper。
 *
 * <p>未读数的加减都在这里手写 SQL,理由和 {@code SysUserMapper.increaseBalance}
 * 完全一样:<b>加法必须在数据库端做</b>。写成"查出来 +1 再写回",
 * 同一会话连发两条消息时两条都读到同一个旧值,未读数只会加 1。
 */
@Mapper
public interface MsgConversationMapper extends BaseMapper<MsgConversation> {

    /**
     * 我的会话列表(含对方姓名、关联资源/订单)。
     *
     * <p>会话的双方是 user_a / user_b 两列,而"对方"是谁取决于我是哪一个 ——
     * 所以这里的 JOIN 条件必须写成 {@code CASE WHEN},不能在 Java 里
     * 先查出来再逐条补。逐条补就是 N+1:十个会话十一次查询。
     */
    List<ConversationVO> selectMyConversations(@Param("userId") Long userId);

    /**
     * 我的总未读数(导航红点用)。
     *
     * <p>每条会话的未读存在 a_unread 或 b_unread 里,取决于我是甲方还是乙方,
     * 所以求和要带条件。写成 {@code SUM(a_unread) + SUM(b_unread)} 是错的 ——
     * 那会把对方没看我的消息也算进我的未读。
     */
    @Select("SELECT IFNULL(SUM(CASE WHEN user_a_id = #{userId} THEN a_unread ELSE b_unread END), 0) "
            + "FROM msg_conversation WHERE user_a_id = #{userId} OR user_b_id = #{userId}")
    int sumMyUnread(@Param("userId") Long userId);

    /**
     * 未读数 +1。
     *
     * <p>甲/乙两列各写一个方法,而不是传一个"列名"参数 ——
     * 列名只能靠 {@code ${}} 拼接,那就等于把 SQL 片段交给调用方,
     * 是注入风险的入口。两个方法看着笨,但字段名是写死的,没有注入面。
     */
    @Update("UPDATE msg_conversation SET a_unread = a_unread + 1 WHERE id = #{id}")
    int increaseAUnread(@Param("id") Long id);

    @Update("UPDATE msg_conversation SET b_unread = b_unread + 1 WHERE id = #{id}")
    int increaseBUnread(@Param("id") Long id);

    /** 清零未读。同上,两列各一个方法。 */
    @Update("UPDATE msg_conversation SET a_unread = 0 WHERE id = #{id}")
    int clearAUnread(@Param("id") Long id);

    @Update("UPDATE msg_conversation SET b_unread = 0 WHERE id = #{id}")
    int clearBUnread(@Param("id") Long id);

    /**
     * 查找两个人之间已存在的会话 —— "先查后插"里的那个"查"。
     *
     * <p>{@code resourceId} 为空时匹配 {@code resource_id IS NULL} 的会话,
     * 这一点不能用 MyBatis-Plus 的条件构造器偷懒:
     * {@code .eq(MsgConversation::getResourceId, null)} 生成的是
     * {@code resource_id = NULL},在 SQL 里恒为 UNKNOWN,永远查不到 ——
     * 而"不针对具体资源的纯私聊"正好是 resource_id 为空的那一类。
     * 写成 LambdaQueryWrapper 的话,这个 bug 只会在
     * "从用户主页发起私聊,重复点了两次"这种路径下才暴露出来。
     *
     * <p><b>先查后插仍有并发窗口</b>(两个请求同时查到"还没有会话",
     * 然后各插一条)。{@code uk_conv} 唯一约束能挡住 resource_id 非空的情况,
     * 但 MySQL 的唯一索引不约束 NULL,所以纯私聊会话理论上可能重复。
     * 这是本项目已知并保留的一处取舍,见论文「设计权衡」一节。
     */
    MsgConversation selectByUsersAndResource(@Param("a") Long userAId,
                                             @Param("b") Long userBId,
                                             @Param("resourceId") Long resourceId);

    /**
     * 某笔订单下的所有会话ID —— 后台调取聊天记录用。
     *
     * <p>返回 ID 而不是直接返回消息:先把"这笔订单涉及几次沟通"拿全,
     * 再按会话取消息,管理员能看到"第一次谈价""第二次谈交付"这样的分段。
     */
    @Select("SELECT id FROM msg_conversation WHERE order_id = #{orderId} ORDER BY id")
    List<Long> selectIdsByOrder(@Param("orderId") Long orderId);
}
