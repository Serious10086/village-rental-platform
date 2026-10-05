package com.gjf.village.controller.portal;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.ChatQueryDTO;
import com.gjf.village.dto.ChatSendDTO;
import com.gjf.village.dto.ChatStartDTO;
import com.gjf.village.service.ChatService;
import com.gjf.village.vo.ChatMessageVO;
import com.gjf.village.vo.ConversationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 站内信接口(前台)。
 *
 * <p>{@code /start} 是幂等的:同一对用户、同一个资源反复调用只会
 * 得到同一个会话ID,所以前端"联系卖家"按钮不需要判断"是新建还是打开"。
 *
 * <p>会话ID是自增主键、可以被猜到,因此每一个用到会话ID的方法
 * 都在 Service 层校验"我是不是这个会话的参与方"——
 * 漏掉任何一处,换个ID就能读到别人的私聊。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "站内信", description = "会话发起、消息收发、未读数")
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/start")
    @Operation(summary = "发起会话(幂等)",
            description = "传 targetUserId,可选 resourceId / orderId 作为话题标签。"
                    + "返回会话ID,已存在则直接返回已有的那个")
    public Result<Long> start(@Valid @RequestBody ChatStartDTO dto) {
        return Result.success(chatService.startConversation(dto));
    }

    @PostMapping("/send")
    @Operation(summary = "发送消息",
            description = "命中拦截类敏感词整条拒绝;命中替换类打码放行,"
                    + "但库里存的是原文并标记 isFiltered,前台读取时统一打码")
    public Result<Void> send(@Valid @RequestBody ChatSendDTO dto) {
        chatService.send(dto);
        return Result.success("已发送", null);
    }

    @GetMapping("/conversations")
    @Operation(summary = "我的会话列表", description = "按最后消息时间倒序,带我的未读数")
    public Result<List<ConversationVO>> conversations() {
        return Result.success(chatService.listMyConversations());
    }

    @GetMapping("/messages")
    @Operation(summary = "会话最近的消息",
            description = "取最新的 N 条(默认 50,上限 200)并按时间正序返回,"
                    + "打开聊天窗口看到的是刚聊完的这几句,不是一个月前的开场白")
    public Result<List<ChatMessageVO>> messages(@RequestParam Long conversationId,
                                                @RequestParam(required = false) Integer limit) {
        return Result.success(chatService.listRecentMessages(conversationId, limit));
    }

    @PutMapping("/{conversationId}/read")
    @Operation(summary = "把这个会话标为已读",
            description = "同时改消息表和会话表:只改一张的话,"
                    + "要么列表上的红点消不掉,要么下次进来又冒出来")
    public Result<Void> markRead(@PathVariable Long conversationId) {
        chatService.markRead(conversationId);
        return Result.success();
    }

    @GetMapping("/unread")
    @Operation(summary = "聊天未读总数", description = "导航栏红点用,只返回一个数字")
    public Result<Integer> unread() {
        return Result.success(chatService.countMyUnread());
    }

    /**
     * 按订单调取聊天记录 —— 纠纷抽屉里用。
     *
     * <p>放在这里而不是后台控制器,是因为它和 {@code /messages} 是
     * 同一份数据的两种读法(按会话 / 按订单),放在一起才知道要同步改。
     * 权限判断在 Service 层:必须是超管或有该订单所属村管辖权的管理员。
     */
    @GetMapping("/order-messages")
    @Operation(summary = "按订单调取聊天记录(后台纠纷处理用)",
            description = "返回原文不打码,并带 isFiltered 标记,"
                    + "让管理员知道哪些内容用户当时看到的和这里不一样")
    public Result<PageResult<ChatMessageVO>> orderMessages(ChatQueryDTO query) {
        return Result.success(chatService.pageOrderMessages(query));
    }
}
