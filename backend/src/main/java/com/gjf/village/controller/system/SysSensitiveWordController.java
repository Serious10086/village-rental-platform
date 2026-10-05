package com.gjf.village.controller.system;

import com.gjf.village.annotation.Log;
import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.common.util.SensitiveWordUtil;
import com.gjf.village.dto.SensitiveWordDTO;
import com.gjf.village.dto.SensitiveWordQueryDTO;
import com.gjf.village.entity.SysSensitiveWord;
import com.gjf.village.service.SysSensitiveWordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 系统管理 —— 敏感词。
 *
 * <p>仅超级管理员可访问:词库是全平台共用的,
 * 让村级管理员各改各的会导致不同村的标准不一致。
 *
 * <p>增删改后服务会自动重载内存词库,改完立刻生效。
 */
@RestController
@RequestMapping("/api/system/sensitive")
@RequiredArgsConstructor
@Tag(name = "系统管理-敏感词", description = "敏感词库维护,改动后立即生效")
public class SysSensitiveWordController {

    private final SysSensitiveWordService sensitiveWordService;
    private final SensitiveWordUtil sensitiveWordUtil;

    @GetMapping("/list")
    @RequiresPerm("sensitive:list")
    @Operation(summary = "敏感词列表")
    public Result<PageResult<SysSensitiveWord>> list(SensitiveWordQueryDTO query) {
        return Result.success(sensitiveWordService.page(query));
    }

    @PostMapping
    @RequiresPerm("sensitive:edit")
    @Log(module = "敏感词管理", operation = "新增敏感词")
    @Operation(summary = "新增敏感词")
    public Result<Void> add(@Valid @RequestBody SensitiveWordDTO dto) {
        sensitiveWordService.add(dto);
        return Result.success("添加成功,已立即生效", null);
    }

    @PutMapping
    @RequiresPerm("sensitive:edit")
    @Log(module = "敏感词管理", operation = "修改敏感词")
    @Operation(summary = "修改敏感词")
    public Result<Void> update(@Valid @RequestBody SensitiveWordDTO dto) {
        sensitiveWordService.update(dto);
        return Result.success("保存成功,已立即生效", null);
    }

    @DeleteMapping("/{id}")
    @RequiresPerm("sensitive:edit")
    @Log(module = "敏感词管理", operation = "删除敏感词")
    @Operation(summary = "删除敏感词")
    public Result<Void> delete(@PathVariable Long id) {
        sensitiveWordService.delete(id);
        return Result.success("已删除", null);
    }

    @PostMapping("/batch")
    @RequiresPerm("sensitive:edit")
    @Log(module = "敏感词管理", operation = "批量导入敏感词")
    @Operation(summary = "批量导入",
            description = "用换行或逗号分隔。已存在的词会自动跳过,不会因为个别重复导致整批失败")
    public Result<Map<String, Object>> batchImport(@RequestBody Map<String, Object> body) {
        String text = String.valueOf(body.getOrDefault("text", ""));
        String category = body.get("category") == null ? null : String.valueOf(body.get("category"));
        Integer action = body.get("action") == null
                ? null : Integer.valueOf(String.valueOf(body.get("action")));

        int inserted = sensitiveWordService.batchImport(text, category, action);

        Map<String, Object> data = new HashMap<>();
        data.put("inserted", inserted);
        data.put("total", sensitiveWordService.size());
        return Result.success("成功导入 " + inserted + " 个敏感词", data);
    }

    @GetMapping("/size")
    @RequiresPerm("sensitive:list")
    @Operation(summary = "当前词库大小", description = "内存中已加载的敏感词数量")
    public Result<Integer> size() {
        return Result.success(sensitiveWordService.size());
    }

    /**
     * 试一试 —— 输入一段文字,看看会被怎么处理。
     *
     * <p>加这个接口是因为"词库加了词"和"加了词真的生效"是两件事。
     * 管理员改完词库后可以用它立刻验证,不用等到有人发内容才发现没生效。
     */
    @GetMapping("/test")
    @RequiresPerm("sensitive:list")
    @Operation(summary = "试过滤效果",
            description = "返回:是否被拦截、命中了哪些词、打码后的文本")
    public Result<Map<String, Object>> test(@RequestParam String text) {
        Set<String> hits = sensitiveWordUtil.findAll(text);

        Map<String, Object> data = new HashMap<>();
        data.put("blocked", sensitiveWordUtil.shouldBlock(text));
        data.put("hits", hits);
        data.put("filtered", sensitiveWordUtil.filter(text));
        return Result.success(data);
    }
}
