package com.gjf.village.controller.portal;

import com.gjf.village.common.result.Result;
import com.gjf.village.dto.AiChatDTO;
import com.gjf.village.service.AiChatService;
import com.gjf.village.vo.AiAnswerVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台 —— AI 智能客服。
 *
 * <p><b>这两个接口都要登录</b>(不在白名单里),和不设门槛的公告、留言板不一样。
 * 三个理由:对话记录要挂在具体的人身上(否则 {@code ai_chat_record.user_id}
 * 那一列的外键就没法填)、提示词里会带上用户所在村组、以及这是一个按量计费的
 * 外部服务 —— 开放给游客等于把账单交给所有人。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI 智能客服", description = "业务问答、多轮上下文")
public class AiChatController {

    private final AiChatService aiChatService;

    /**
     * 问一句。
     *
     * <p><b>这个接口不会因为 AI 挂了而返回错误。</b>答不上来是一种业务结果,
     * 表达在返回体的 {@code status} / {@code degradeReason} 里:
     * <ul>
     *   <li>{@code status = 1 成功} —— AI 的回答</li>
     *   <li>{@code status = 2 降级} —— 本地兜底话术,原因见 degradeReason</li>
     *   <li>{@code status = 3 失败} —— 提问被内容安全拦下</li>
     * </ul>
     * {@code answer} 在这三种情况下都非空。前端要做的只是:显示它,
     * 并在 status ≠ 1 时挂一个"本次为兜底答复"的标记。
     */
    @PostMapping("/chat")
    @Operation(summary = "向智能客服提问",
            description = "同一 sessionId 的最近 5 轮会作为上下文一起发给模型。"
                    + "AI 不可用时返回本地兜底话术而不是报错,详见 status 字段")
    public Result<AiAnswerVO> chat(@Valid @RequestBody AiChatDTO dto) {
        return Result.success(aiChatService.chat(dto.getSessionId(), dto.getQuestion()));
    }

    /**
     * 标记某轮回答「已解决」。
     *
     * <p>只影响那一行记录上的一个标记,不改变问答过程 ——
     * 它的用处是让后台能看出"降级那几条里,哪些其实也帮上了忙"。
     * 全兜底但用户都点已解决,说明本地问答库够用,不必急着调 AI。
     */
    @PutMapping("/{recordId}/resolved")
    @Operation(summary = "标记回答已解决")
    public Result<Void> markResolved(@PathVariable Long recordId) {
        aiChatService.markResolved(recordId);
        return Result.success("已记录", null);
    }
}
