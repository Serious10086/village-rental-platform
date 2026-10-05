package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.common.enums.ResourceStatusEnum;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.FavoriteQueryDTO;
import com.gjf.village.entity.BizResource;
import com.gjf.village.entity.BizResourceFavorite;
import com.gjf.village.mapper.BizResourceFavoriteMapper;
import com.gjf.village.mapper.BizResourceMapper;
import com.gjf.village.security.LoginUser;
import com.gjf.village.service.AiBehaviorService;
import com.gjf.village.service.BizResourceFavoriteService;
import com.gjf.village.vo.FavoriteItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资源收藏服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizResourceFavoriteServiceImpl implements BizResourceFavoriteService {

    private final BizResourceFavoriteMapper favoriteMapper;
    private final BizResourceMapper resourceMapper;
    private final AiBehaviorService behaviorService;

    /**
     * 收藏 / 取消收藏。
     *
     * <h3>事务开头必须先把资源行锁成排他锁,理由和留言板点赞完全一样</h3>
     *
     * <p>往 {@code biz_resource_favorite} 插一行时,外键检查会在被引用的
     * {@code biz_resource} 那一行上加一把<b>共享锁</b>;紧接着的
     * {@code favorite_count + 1} 要的是<b>排他锁</b>。两个用户同时收藏
     * 同一个资源时,双方都攥着共享锁、都在等对方松手升级 —— 死锁。
     *
     * <p>这是批次 6c 在留言点赞上实测踩到的坑(InnoDB 日志里两边一字不差:
     * {@code HOLDS ... S locks rec but not gap} + {@code WAITING lock_mode X}),
     * 这里换成收藏是<b>同一个形状</b>:子行插入 + 父行计数自增。
     * 解法也一样:事务一开始就用 {@code selectByIdForUpdate} 把父行锁住,
     * 同一个资源上的收藏就排起队了,那个环根本形不成。
     *
     * <p>代价是同一个资源上的收藏操作串行 —— 毕设的量级下可以忽略。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public boolean toggle(Long resourceId) {
        LoginUser me = UserContext.get();

        BizResource resource = resourceMapper.selectByIdForUpdate(resourceId);
        if (resource == null) {
            throw BusinessException.notFound("资源不存在或已删除");
        }

        // ── 先试取消 ────────────────────────────────────────────
        // 【顺序不能反】取消收藏必须先判定,因为"已下架的资源也要能取消收藏":
        // 用户收藏了一件东西、后来它下架了,那条收藏仍然挂在他的列表里,
        // 他得能把它清掉。把"资源必须已上架"挡在前面的话,
        // 这些收藏就永远删不掉了 —— 界面上是一个点不动的取消按钮。
        int removed = favoriteMapper.delete(new LambdaQueryWrapper<BizResourceFavorite>()
                .eq(BizResourceFavorite::getUserId, me.getUserId())
                .eq(BizResourceFavorite::getResourceId, resourceId));
        if (removed > 0) {
            resourceMapper.decreaseFavoriteCount(resourceId);
            return false;
        }

        // ── 收藏:只有已上架的能收藏 ────────────────────────────
        ResourceStatusEnum status = ResourceStatusEnum.of(resource.getResourceStatus());
        if (status != ResourceStatusEnum.ONLINE) {
            throw BusinessException.badRequest("该资源当前" + status.getDescription() + ",无法收藏");
        }

        try {
            BizResourceFavorite favorite = new BizResourceFavorite();
            favorite.setUserId(me.getUserId());
            favorite.setResourceId(resourceId);
            favoriteMapper.insert(favorite);
        } catch (DuplicateKeyException e) {
            // 并发下另一个请求已经收藏了(DELETE 和 INSERT 之间的窗口)。
            // 对他来说结果一样是"已收藏",所以不报错,也**不再自增一次计数** ——
            // 计数必须和明细条数对得上,这是留言板点赞那条不变式的同一个要求
            log.debug("并发重复收藏,已忽略: resourceId={}, userId={}", resourceId, me.getUserId());
            return true;
        }

        resourceMapper.increaseFavoriteCount(resourceId);
        // 收藏是一个"明确意图"信号,权重比浏览高得多,推荐那边等着用它
        behaviorService.favorited(resourceId);
        return true;
    }

    @Override
    public PageResult<FavoriteItemVO> pageMine(FavoriteQueryDTO query) {
        LoginUser me = UserContext.get();
        Page<FavoriteItemVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<FavoriteItemVO> result =
                favoriteMapper.selectMyFavorites(page, me.getUserId(), query.getKeyword());
        return PageResult.of(result);
    }
}
