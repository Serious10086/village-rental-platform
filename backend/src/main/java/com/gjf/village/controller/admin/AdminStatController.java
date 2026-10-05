package com.gjf.village.controller.admin;

import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.Result;
import com.gjf.village.service.StatService;
import com.gjf.village.vo.StatOverviewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台 —— 数据统计。
 *
 * <p>三个接口对应三个页面,共用同一个返回形状({@code StatOverviewVO}),
 * 差别只在范围和要不要各村明细 —— 详见那个 VO 的注释。
 *
 * <p>三个接口都是只读的,所以**都没有 {@code @Log}**:操作日志记的是
 * "谁改变了什么",看统计不改变任何东西。把查询也记进去的话,
 * 日志表会被"某某看了工作台"这种记录淹掉,真正要查的那几条反而找不到。
 */
@RestController
@RequestMapping("/api/admin/stat")
@RequiredArgsConstructor
@Tag(name = "后台-数据统计", description = "工作台、村级统计、全局统计")
public class AdminStatController {

    private final StatService statService;

    /**
     * 工作台。
     *
     * <p>权限码用的是 {@code dashboard:view}(菜单「工作台」上挂的那个),
     * 而不是新造一个 —— 这个接口填的就是工作台页面,
     * 能进那个页面的人本来就该拿到这些数字。
     */
    @GetMapping("/dashboard")
    @RequiresPerm("dashboard:view")
    @Operation(summary = "工作台统计",
            description = "关键数字 + 资源分类分布 + 订单状态分布 + 近 30 天订单趋势。"
                    + "范围由登录态决定:村管看本村,超管看全平台")
    public Result<StatOverviewVO> dashboard() {
        return Result.success(statService.dashboard());
    }

    @GetMapping("/village")
    @RequiresPerm("stat:village")
    @Operation(summary = "村级统计",
            description = "村级管理员**只能看本村**(传 villageId 也会被忽略);"
                    + "超级管理员可以指定 villageId,不传则给一个资源最多的村")
    public Result<StatOverviewVO> village(@RequestParam(required = false) Long villageId) {
        return Result.success(statService.village(villageId));
    }

    @GetMapping("/global")
    @RequiresPerm("stat:global")
    @Operation(summary = "全局统计",
            description = "全平台数字 + 各村横向对比 + 资源分类分布。仅超级管理员")
    public Result<StatOverviewVO> global() {
        return Result.success(statService.global());
    }
}
