package com.gjf.village.controller.admin;

import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.Result;
import com.gjf.village.service.RecommendService;
import com.gjf.village.vo.RecommendStatVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台 —— 推荐效果。
 *
 * <p>权限码 {@code recommend:stat} 挂在 AI 管理(菜单 80)下面。
 * 数据级权限照旧:村管看本村用户产生的曝光与点击,超管看全部。
 */
@RestController
@RequestMapping("/api/admin/recommend")
@RequiredArgsConstructor
@Tag(name = "后台-推荐效果", description = "推荐位点击率与趋势")
public class AdminRecommendController {

    private final RecommendService recommendService;

    /**
     * 推荐位点击率。
     *
     * <p>返回 <b>曝光 / 点击 / 点击率</b> 三个数,而不是只给一个点击量 ——
     * 没有分母的那个数字说明不了任何事(一天 50 次点击是多还是少?)。
     * 另外带上按天的明细:推荐这种指标看趋势比看单点有用得多。
     *
     * <p><b>口径要说明白:分母只含登录用户。</b>游客的进出不计入
     * (曝光和点击都只在登录时记录)。所以它是"登录用户的推荐位点击率",
     * 不是全站的 —— 后台页面上写了这一句。
     */
    @GetMapping("/stat")
    @RequiresPerm("recommend:stat")
    @Operation(summary = "推荐位点击率",
            description = "曝光 / 点击 / 点击率 + 按天明细。分母只含登录用户;"
                    + "另外分不出「哪个策略贡献的点击」—— 那需要给行为表加一列")
    public Result<RecommendStatVO> stat(@RequestParam(required = false) Integer days) {
        return Result.success(recommendService.statistics(days == null ? 0 : days));
    }
}
