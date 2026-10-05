package com.gjf.village.controller.portal;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.FavoriteQueryDTO;
import com.gjf.village.service.BizResourceFavoriteService;
import com.gjf.village.vo.FavoriteItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台 —— 资源收藏。
 *
 * <p>两个接口都要登录:收藏是"我的东西",{{@code biz_resource_favorite.user_id}}
 * 是外键,存不下匿名收藏。而且这也符合直觉 —— 收藏了却找不回来,
 * 那就不该让他收藏。
 */
@RestController
@RequestMapping("/api/favorite")
@RequiredArgsConstructor
@Tag(name = "资源收藏", description = "收藏 / 取消收藏、我的收藏")
public class FavoriteController {

    private final BizResourceFavoriteService favoriteService;

    /**
     * 收藏或取消收藏(同一个接口,双向切换)。
     *
     * <p>返回 {@code data} 是操作<b>之后</b>的状态:true = 已收藏,
     * false = 已取消。前端不用自己猜该显示实心还是空心星星 ——
     * 猜的依据是"我以为的当前状态",而那个状态可能已经过期
     * (比如在另一个标签页里取消过)。
     */
    @PutMapping("/{resourceId}")
    @Operation(summary = "收藏 / 取消收藏",
            description = "双向切换,返回操作后的状态。已下架的资源不能收藏,但**可以取消收藏**")
    public Result<Boolean> toggle(@PathVariable Long resourceId) {
        boolean favorited = favoriteService.toggle(resourceId);
        return Result.success(favorited ? "已收藏" : "已取消收藏", favorited);
    }

    /**
     * 我的收藏。
     *
     * <p>列表里会带上资源的<b>当前状态</b>,所以已下架的收藏不会消失,
     * 而是显示成「已下架」—— 让用户知道东西还在、只是暂时不能租了。
     * 直接从收藏列表里藏掉它,比显示一个灰色的卡片更让人困惑。
     */
    @GetMapping("/mine")
    @Operation(summary = "我的收藏", description = "按收藏时间倒序,含已下架的资源")
    public Result<PageResult<FavoriteItemVO>> mine(FavoriteQueryDTO query) {
        return Result.success(favoriteService.pageMine(query));
    }
}
