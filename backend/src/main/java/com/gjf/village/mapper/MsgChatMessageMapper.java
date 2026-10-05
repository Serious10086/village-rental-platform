package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.dto.ChatQueryDTO;
import com.gjf.village.entity.MsgChatMessage;
import com.gjf.village.vo.ChatMessageVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 聊天消息 Mapper。
 */
@Mapper
public interface MsgChatMessageMapper extends BaseMapper<MsgChatMessage> {

    /**
     * 取最近 N 条消息(倒序返回,调用方翻正)。
     *
     * <p>聊天界面要的是"最新的几条",不是"第一页"。用
     * {@code ORDER BY id DESC LIMIT n} 再在 Java 里翻过来,
     * 保证任何消息量下打开会话看到的都是最新的对话 ——
     * 如果写成正序 + LIMIT,消息超过 n 条后打开会话看到的就是很久以前的开场白。
     */
    @Select("SELECT * FROM msg_chat_message WHERE conversation_id = #{conversationId} "
            + "ORDER BY id DESC LIMIT #{limit}")
    List<MsgChatMessage> selectRecent(@Param("conversationId") Long conversationId,
                                      @Param("limit") int limit);

    /**
     * 把发给某人的消息全部标记为已读。
     *
     * <p>{@code AND is_read = 0} 是幂等保护:重复调用不会反复写库,
     * 也避免了无意义的行锁争用。
     */
    @Update("UPDATE msg_chat_message SET is_read = 1 "
            + "WHERE conversation_id = #{conversationId} AND to_user_id = #{toUserId} AND is_read = 0")
    int markRead(@Param("conversationId") Long conversationId,
                 @Param("toUserId") Long toUserId);

    /**
     * 聊天记录分页 —— 两个用途。
     *
     * <p>一是聊天窗口里往前翻历史消息;二是<b>后台处理纠纷时调取
     * 这笔订单的全部聊天记录</b>,对应需求原文"调取订单和聊天记录作为依据"。
     * 后一个用途是它存在的关键理由:纠纷判断的是"当时双方到底怎么说的",
     * 只给管理员看最近的几条是不够的,他需要能一直往前翻。
     *
     * <p>SQL 见 {@code MsgChatMessageMapper.xml},那里还说明了
     * 为什么按订单取消息的路径是"订单 → 会话 → 消息"。
     */
    IPage<ChatMessageVO> selectMessagePage(IPage<ChatMessageVO> page,
                                           @Param("q") ChatQueryDTO query);
}
