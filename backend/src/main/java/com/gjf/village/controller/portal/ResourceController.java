package com.gjf.village.controller.portal;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.ResourcePublishDTO;
import com.gjf.village.dto.ResourceQueryDTO;
import com.gjf.village.entity.ResourceListView;
import com.gjf.village.service.BizResourceService;
import com.gjf.village.vo.MyResourceVO;
import com.gjf.village.vo.ResourceDetailVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 资源接口(前台)。
 *
 * <p>其中 {@code /list} 和 {@code /detail/{id}} 是公开的(在白名单里) ——
 * 未登录的访客可以浏览资源,这是平台的获客前提。发布和管理类接口需要登录。
 */
@RestController
@RequestMapping("/api/resource")
@RequiredArgsConstructor
@Tag(name = "资源(前台)", description = "浏览、搜索、发布、管理闲置资源")
public class ResourceController {

    private final BizResourceService resourceService;

    // ── 公开接口 ────────────────────────────────────────────

    @GetMapping("/list")
    @Operation(summary = "资源搜索",
            description = "支持关键词(全文检索)、分类、村组、价格区间、交易方式、可用日期筛选")
    public Result<PageResult<ResourceListView>> list(ResourceQueryDTO query) {
        return Result.success(resourceService.pageResources(query));
    }

    @GetMapping("/detail/{id}")
    @Operation(summary = "资源详情",
            description = "未上架的资源只有发布者本人和本村管理员可见")
    public Result<ResourceDetailVO> detail(@PathVariable Long id) {
        return Result.success(resourceService.getDetail(id));
    }

    // ── 需要登录 ────────────────────────────────────────────

    @PostMapping("/publish")
    @Operation(summary = "发布资源",
            description = "出租与出售共用本接口,靠 tradeType 区分。发布后进入待审核状态")
    public Result<Map<String, Object>> publish(@Valid @RequestBody ResourcePublishDTO dto) {
        Long id = resourceService.publish(dto);
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("message", "发布成功,等待村级管理员审核");
        return Result.success("发布成功,等待审核", data);
    }

    @PutMapping("/update")
    @Operation(summary = "编辑资源",
            description = "已上架的资源编辑后会重新进入待审核状态")
    public Result<Void> update(@Valid @RequestBody ResourcePublishDTO dto) {
        resourceService.update(dto);
        return Result.success("保存成功", null);
    }

    @GetMapping("/mine")
    @Operation(summary = "我的发布", description = "返回当前用户全部状态的资源")
    public Result<PageResult<MyResourceVO>> mine(ResourceQueryDTO query) {
        return Result.success(resourceService.pageMyResources(query));
    }

    @PutMapping("/{id}/offline")
    @Operation(summary = "下架资源")
    public Result<Void> offline(@PathVariable Long id) {
        resourceService.offline(id);
        return Result.success("已下架", null);
    }

    @PutMapping("/{id}/online")
    @Operation(summary = "重新上架", description = "仅临时下架的资源可以")
    public Result<Void> online(@PathVariable Long id) {
        resourceService.online(id);
        return Result.success("已重新上架", null);
    }

    @PutMapping("/{id}/price")
    @Operation(summary = "调整价格")
    public Result<Void> adjustPrice(@PathVariable Long id, @RequestParam BigDecimal price) {
        resourceService.adjustPrice(id, price);
        return Result.success("调价成功", null);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除资源", description = "逻辑删除,交易中的资源不可删除")
    public Result<Void> delete(@PathVariable Long id) {
        resourceService.delete(id);
        return Result.success("已删除", null);
    }
}
