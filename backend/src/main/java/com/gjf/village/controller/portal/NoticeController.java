package com.gjf.village.controller.portal;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.NoticeQueryDTO;
import com.gjf.village.service.MsgNoticeService;
import com.gjf.village.vo.NoticeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 通知接口。
 *
 * <p>全部需要登录 —— 通知是私人的,不存在"公开通知列表"。
 * 所有查询和操作都强制限定在当前用户名下,不接受前端传 userId。
 */
@RestController
@RequestMapping("/api/notice")
@RequiredArgsConstructor
@Tag(name = "消息通知", description = "通知列表、未读数、已读标记")
public class NoticeController {

    private final MsgNoticeService noticeService;

    @GetMapping("/list")
    @Operation(summary = "我的通知列表")
    public Result<PageResult<NoticeVO>> list(NoticeQueryDTO query) {
        return Result.success(noticeService.pageMyNotices(query));
    }

    @GetMapping("/unread")
    @Operation(summary = "未读数量", description = "前端用它渲染顶栏红点,轮询调用,所以只返回一个数字")
    public Result<Long> unread() {
        return Result.success(noticeService.countUnread());
    }

    @PutMapping("/{id}/read")
    @Operation(summary = "标记单条已读")
    public Result<Void> markRead(@PathVariable Long id) {
        noticeService.markRead(id);
        return Result.success();
    }

    @PutMapping("/read-all")
    @Operation(summary = "全部标记已读")
    public Result<Void> markAllRead() {
        noticeService.markAllRead();
        return Result.success("已全部标记为已读", null);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除通知")
    public Result<Void> delete(@PathVariable Long id) {
        noticeService.delete(id);
        return Result.success("已删除", null);
    }
}
