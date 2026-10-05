package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.entity.BizResourceFavorite;
import com.gjf.village.vo.FavoriteItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 资源收藏 Mapper。
 *
 * <p>收藏/取消收藏、判断"我收藏了没有"走 {@code BaseMapper} 那套就够;
 * 需要手写 SQL 的只有「我的收藏」列表 —— 它要联出资源卡片要用的字段。
 */
@Mapper
public interface BizResourceFavoriteMapper extends BaseMapper<BizResourceFavorite> {

    /**
     * 我的收藏(一张资源卡片的字段 + 收藏时间)。
     *
     * <p><b>这里查的是 {@code biz_resource} 而不是视图 {@code v_resource_list}</b>:
     * 视图写死了"只看已上架",而收藏列表必须把已下架的资源也显示出来
     * (标上「已下架」),否则用户会以为收藏被系统弄丢了。
     *
     * <p>排序按收藏时间倒序 —— 收藏列表是"我最近想要什么",不是"什么最新发布"。
     */
    IPage<FavoriteItemVO> selectMyFavorites(IPage<FavoriteItemVO> page,
                                            @Param("userId") Long userId,
                                            @Param("keyword") String keyword);
}
