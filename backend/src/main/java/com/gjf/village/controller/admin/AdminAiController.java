package com.gjf.village.controller.admin;

import com.gjf.village.annotation.Log;
import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.AiChatQueryDTO;
import com.gjf.village.service.AiChatService;
import com.gjf.village.vo.AiChatRecordVO;
import com.gjf.village.vo.AiDegradeStatVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台 —— AI 会话记录与降级统计。
 *
 * <p>权限码来自 {@code sys_menu}:菜单 801「AI会话记录」下挂了
 * {@code ai:record:list}(看)和 {@code ai:transfer}(标记需人工跟进)。
 * 两个都授给了村级管理员和超管;数据级权限由 {@code DataScopeHelper} 决定
 * —— 村管只看本村,超管看全部。
 */
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
@Tag(name = "后台-AI 会话管理", description = "会话记录、降级率与原因分布")
public class AdminAiController {

    private final AiChatService aiChatService;

    @GetMapping("/list")
    @RequiresPerm("ai:record:list")
    @Operation(summary = "会话记录列表",
            description = "村级管理员只看本村;可按状态筛(1成功 2降级 3失败),"
                    + "传 onlyProblem=true 一次捞出降级和失败")
    public Result<PageResult<AiChatRecordVO>> list(AiChatQueryDTO query) {
        return Result.success(aiChatService.pageAdmin(query));
    }

    /**
     * 降级率与原因分布。
     *
     * <p>这是验收项 AI-07 要的那个页面背后的接口。它回答的问题不是
     * "AI 好不好用",而是<b>"AI 不好的时候,是哪里不好"</b> ——
     * 降级率只说明有问题,原因分布才说明该去做什么:
     * 全是「超时」该查网络或调超时;全是「鉴权失败401」是密钥的事;
     * 全是「余额不足402」该充值;而「提问包含违规词」根本不是故障,
     * 是内容安全在正常工作。
     */
    @GetMapping("/stat")
    @RequiresPerm("ai:record:list")
    @Operation(summary = "降级率与原因分布",
            description = "降级率的分母是全部记录;失败(内容拦截)单独计数,不计入降级率")
    public Result<AiDegradeStatVO> stat(AiChatQueryDTO query) {
        return Result.success(aiChatService.degradeStat(query));
    }

    @PutMapping("/{id}/follow-up")
    @RequiresPerm("ai:transfer")
    @Log(module = "AI 管理", operation = "标记人工跟进")
    @Operation(summary = "标记为需要人工跟进",
            description = "注意:系统里没有在线的人工客服,这个动作不转接、不通知任何人,"
                    + "只是给管理员一个待办标记,后台可按它筛出「AI 没答好、需要有人跟一下」的记录")
    public Result<Void> followUp(@PathVariable Long id) {
        aiChatService.markFollowUp(id);
        return Result.success("已标记为需要人工跟进", null);
    }
}
