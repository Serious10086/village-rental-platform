package com.gjf.village.service;

import com.gjf.village.vo.RecommendItemVO;
import com.gjf.village.vo.RecommendStatVO;

import java.util.List;

/**
 * 智能推荐服务。
 *
 * <h3>两路召回 + 一级兜底</h3>
 *
 * <pre>
 *   ① 内容推荐 —— 用户近 30 天偏好最强的几个分类下,他没看过的好东西
 *   ② 村组热度 —— 本村最热的几个(本村能当面交易,比邻村的更值得推)
 *   ③ 全局兜底 —— 前两路加起来不够时,用全平台热门补满
 * </pre>
 *
 * <p><b>为什么要三路而不是一路。</b>只用内容推荐,冷启动用户(从没产生过
 * 行为)会看到一个空推荐位 —— 那是最劝退的一种"个性化";
 * 只用热度,推荐就退化成了"排行榜",和"猜你喜欢"这四个字没关系。
 * 三路按顺序补位,任何情况下都不会空。
 *
 * <h3>推荐理由是真的,不是编的</h3>
 *
 * <p>每条结果都带一个 {@code reason},而且它<b>严格对应该条是怎么被选出来的</b>:
 * 走内容推荐的写"你看过「农机具」",走村组热度的写"本村热门"。
 * 不给冷启动用户编一个假的"根据你的兴趣"—— 他没有任何行为,
 * 系统对他一无所知,那句"根据你的浏览记录"是撒谎。
 */
public interface RecommendService {

    /**
     * 首页「猜你喜欢」。
     *
     * <p>登录用户走三路召回;游客没有行为也没有村组,直接给全局热门
     * (理由也如实写成"大家都在看")。
     *
     * @param limit 最多几条,内部会夹到合理区间
     */
    List<RecommendItemVO> recommendForMe(int limit);

    /**
     * 详情页「相似资源」。
     *
     * <p>语义是"同分类的其他资源",所以<b>不排除用户看过的</b> ——
     * 那是"探索同类",不是个性化推荐。排除的话,一个把某分类看遍的用户
     * 会看到空白的推荐区。
     *
     * <p>游客也能用:详情页本来就公开,相似资源也不依赖登录态。
     */
    List<RecommendItemVO> similarTo(Long resourceId, int limit);

    /**
     * 推荐位点击率(后台)。
     *
     * @param days 统计最近多少天
     */
    RecommendStatVO statistics(int days);
}
