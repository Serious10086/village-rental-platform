package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.FavoriteQueryDTO;
import com.gjf.village.vo.FavoriteItemVO;

/**
 * 资源收藏服务。
 *
 * <p>收藏是批次 8 顺带做掉的一个功能 —— 它原本不在计划里,但
 * {@code biz_resource.favorite_count} 那一列从建库起就摆在那儿(恒为 0),
 * 而行为采集又需要一个"明确意图"的信号(浏览可能是误点,收藏不是)。
 * 于是把它补上:一个表、一个开关接口、一个列表页。
 */
public interface BizResourceFavoriteService {

    /**
     * 收藏 / 取消收藏,返回<b>操作之后的状态</b>(true 表示已收藏)。
     *
     * <p>用一个接口做双向切换而不是 {@code favorite()} + {@code unfavorite()}
     * 两个接口:前端那一个按钮的语义就是"切换",拆成两个接口之后
     * 前端得自己判断该调哪个 —— 而它判断依据是"我以为的当前状态",
     * 这个状态可能已经过期(另一个标签页里取消过)。
     * 让服务端用 DELETE 的影响行数来判定,前端只管点。
     */
    boolean toggle(Long resourceId);

    /** 我的收藏(按收藏时间倒序) */
    PageResult<FavoriteItemVO> pageMine(FavoriteQueryDTO query);
}
