package com.gjf.village.controller.system;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.OperationLogQueryDTO;
import com.gjf.village.entity.SysOperationLog;
import com.gjf.village.mapper.SysOperationLogMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统 —— 操作日志。
 *
 * <p><b>只读,而且故意只读。</b>审计日志没有"编辑"和"删除":能改的日志
 * 就不是审计。这个控制器里只有两个 GET,以后也不该加第三个。
 *
 * <p><b>它是超管专属的</b> —— 种子数据里「系统管理」整个目录只授给了超管
 * (村级管理员的那份授权清单里明确不含它)。这一点很关键:
 * {@code sys_operation_log} 表里没有村组列,做不了数据级过滤,
 * 所以"村管看不到别村的日志"这件事**完全靠菜单授权挡住**。
 * 谁要是把 {@code log:list} 授给村管,他就看到了全平台的审计记录 ——
 * 这一点在 DTO 的注释里也写了一遍。
 */
@RestController
@RequestMapping("/api/system/log")
@RequiredArgsConstructor
@Tag(name = "系统-操作日志", description = "审计日志查询(只读)")
public class SysOperationLogController {

    private final SysOperationLogMapper logMapper;

    @GetMapping("/list")
    @RequiresPerm("log:list")
    @Operation(summary = "操作日志列表",
            description = "按时间倒序。可按操作人、模块、状态、日期区间筛。"
                    + "注意:没有数据级权限过滤(表里没有村组列),仅靠菜单授权限制为超管可见")
    public Result<PageResult<SysOperationLog>> list(OperationLogQueryDTO query) {
        Page<SysOperationLog> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<SysOperationLog> result = logMapper.selectLogPage(page, query);
        return Result.success(PageResult.of(result));
    }

    /**
     * 日志里出现过的模块名。
     *
     * <p>给前端的"模块"下拉用。从数据里取而不是前端写死一份清单 ——
     * 后者在新增一个 {@code @Log(module = "…")} 时会漏改,而漏改的表现
     * 只是"筛选项里少一个",不报错,拖到有人发现为止。
     */
    @GetMapping("/modules")
    @RequiresPerm("log:list")
    @Operation(summary = "日志模块清单", description = "用于前端下拉筛选")
    public Result<List<String>> modules() {
        return Result.success(logMapper.selectDistinctModules());
    }
}
