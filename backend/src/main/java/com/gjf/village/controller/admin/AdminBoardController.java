package com.gjf.village.controller.admin;

import com.gjf.village.annotation.Log;
import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.AdminBoardQueryDTO;
import com.gjf.village.service.BizBoardService;
import com.gjf.village.vo.BoardItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 后台 —— 留言管理。
 *
 * <p>与前台列表的三点差别:能看到<b>已隐藏的</b>留言(否则隐藏了就再也找不回来)、
 * 能看到<b>回复</b>(只巡查主帖会漏掉一半违规内容)、
 * 以及受<b>数据级权限</b>约束(村管只看得到本村)。
 */
@RestController
@RequestMapping("/api/admin/board")
@RequiredArgsConstructor
@Tag(name = "后台-留言管理", description = "本村留言巡查与删除")
public class AdminBoardController {

    private final BizBoardService boardService;

    @GetMapping("/list")
    @RequiresPerm("board:list")
    @Operation(summary = "留言列表",
            description = "村级管理员只能看到本村留言。"
                    + "`onlyRoot=true` 只看主帖,`onlyFiltered=true` 只看命中过敏感词的"
                    + "(这两个都是为了把巡查范围缩小到真正需要看的那几条)")
    public Result<PageResult<BoardItemVO>> list(AdminBoardQueryDTO query) {
        return Result.success(boardService.pageAdmin(query));
    }

    @DeleteMapping("/{id}")
    @RequiresPerm("board:delete")
    @Log(module = "留言管理", operation = "删除留言")
    @Operation(summary = "删除留言",
            description = "逻辑删除,不是物理删除 —— 留言可能是纠纷处理时的证据,"
                    + "数据库里仍可追溯,前台不再显示")
    public Result<Void> delete(@PathVariable Long id) {
        boardService.adminDelete(id);
        return Result.success("已删除", null);
    }
}
