package com.gjf.village.controller.portal;

import com.gjf.village.common.result.Result;
import com.gjf.village.service.RecommendService;
import com.gjf.village.vo.RecommendItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 前台 —— 智能推荐。
 *
 * <p><b>两个接口都免登录。</b>首页和资源详情页本来就是公开页面,
 * 推荐位空着或者要登录才显示都会很别扭。游客拿到的是全局热门
 * (没有行为可供个性化,这是事实而不是降级),理由也如实写"大家都在看"。
 *
 * <p>免登录还带来一个副作用,是好的:<b>游客的访问不会计入点击率</b> ——
 * 曝光和点击都只在登录时记录,所以后台那个点击率天然是"登录用户的点击率"。
 * 这一点在后台页面上写明了,免得有人把它当成全站数据。
 */
@RestController
@RequestMapping("/api/recommend")
@RequiredArgsConstructor
@Tag(name = "智能推荐", description = "首页猜你喜欢、详情页相似资源")
public class RecommendController {

    private final RecommendService recommendService;

    /**
     * 首页「猜你喜欢」。
     *
     * <p>结果带 {@code reason} 和 {@code strategy}:前者给用户看
     * ("你看过「农机具」"),后者给出问题的排查者看(这条是内容推的还是热度推的)。
     * 两者都不是装饰 —— 推荐能解释自己,是它值不值得信的前提。
     */
    @GetMapping("/me")
    @Operation(summary = "猜你喜欢",
            description = "登录用户走「内容偏好 + 村组热度 + 全局兜底」三路召回;"
                    + "游客返回全平台热门。结果自带推荐理由")
    public Result<List<RecommendItemVO>> forMe(@RequestParam(required = false) Integer limit) {
        return Result.success(recommendService.recommendForMe(limit == null ? 0 : limit));
    }

    /**
     * 详情页「相似资源」。
     *
     * <p>不排除用户看过的 —— 它是"去看看同类的其他东西",不是个性化推荐。
     * 排除的话,一个把某分类看遍的用户会看到空白的推荐区。
     */
    @GetMapping("/similar/{resourceId}")
    @Operation(summary = "相似资源",
            description = "同分类的其他已上架资源,按热度排序,不含当前这一条")
    public Result<List<RecommendItemVO>> similar(@PathVariable Long resourceId,
                                                 @RequestParam(required = false) Integer limit) {
        return Result.success(recommendService.similarTo(resourceId, limit == null ? 0 : limit));
    }
}
