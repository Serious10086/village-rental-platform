package com.gjf.village.controller.portal;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.BoardPostDTO;
import com.gjf.village.dto.BoardQueryDTO;
import com.gjf.village.dto.BoardReplyDTO;
import com.gjf.village.service.BizBoardService;
import com.gjf.village.vo.BoardItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 前台留言板接口。
 *
 * <p><b>读接口公开,写接口要登录。</b>这个分界不是随手划的:
 * 平台做的是跨村交易,买家得能先看看邻村的人在聊什么,
 * 所以列表和回复不需要登录(配在 {@code app.security.whitelist} 里);
 * 而发帖、回复、点赞、删除都是"留下痕迹"的操作,必须知道是谁做的 ——
 * 实名才敢说话,是村级留言板能保持秩序的前提。
 *
 * <p>服务的"写"比"读"更严:不止要登录,还必须和留言属于同一个村组。
 * 你可以在别村的留言板看,但不能在别村的留言板上说话。
 */
@RestController
@RequestMapping("/api/board")
@RequiredArgsConstructor
@Tag(name = "留言板", description = "发帖、回复、点赞;公开浏览")
public class BoardController {

    private final BizBoardService boardService;

    @GetMapping("/list")
    @Operation(summary = "主帖列表",
            description = "公开接口。`villageId` 不传时取当前登录用户的村组;"
                    + "未登录时必传。登录后每条留言会带上 `liked` 标记")
    public Result<PageResult<BoardItemVO>> list(BoardQueryDTO query) {
        return Result.success(boardService.pageRoot(query));
    }

    @GetMapping("/replies")
    @Operation(summary = "某条主帖下的回复",
            description = "公开接口。按时间正序,单页最多 50 条")
    public Result<PageResult<BoardItemVO>> replies(@RequestParam Long parentId,
                                                   @RequestParam(defaultValue = "1") long pageNum,
                                                   @RequestParam(defaultValue = "20") long pageSize) {
        return Result.success(boardService.pageReplies(parentId, pageNum, pageSize));
    }

    @PostMapping("/post")
    @Operation(summary = "发布留言",
            description = "留言所属村组由服务端从登录态取,不接受前端传入")
    public Result<Long> post(@Valid @RequestBody BoardPostDTO dto) {
        return Result.success("发布成功", boardService.post(dto));
    }

    @PostMapping("/reply")
    @Operation(summary = "回复留言",
            description = "只能回复主帖,不支持对回复再回复 —— 留言板层级固定为两级")
    public Result<Long> reply(@Valid @RequestBody BoardReplyDTO dto) {
        return Result.success("回复成功", boardService.reply(dto));
    }

    @PostMapping("/{id}/like")
    @Operation(summary = "点赞 / 取消点赞",
            description = "同一个接口来回切。返回 `true` 表示现在处于已点赞状态。"
                    + "防重复点赞由数据库唯一约束 `uk_board_user` 保证")
    public Result<Boolean> like(@PathVariable Long id) {
        return Result.success(boardService.toggleLike(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除自己的留言",
            description = "逻辑删除。删主帖会连带删掉它下面的回复 —— "
                    + "否则那些回复会变成列表里既显示不出也删不掉的孤儿数据")
    public Result<Void> delete(@PathVariable Long id) {
        boardService.delete(id);
        return Result.success("已删除", null);
    }
}
