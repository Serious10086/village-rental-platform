package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.entity.AiUserBehavior;
import com.gjf.village.entity.BizResource;
import com.gjf.village.mapper.AiUserBehaviorMapper;
import com.gjf.village.mapper.BizResourceMapper;
import com.gjf.village.recommend.RecommendCache;
import com.gjf.village.security.LoginUser;
import com.gjf.village.service.AiBehaviorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户行为采集实现。
 *
 * <p>整个类的实现就一个私有 {@link #save} —— 五个体面方法只是它的不同填法。
 * 这样写是因为采集这件事的<b>难点不在于记什么,而在于怎么失败</b>:
 * 异常要吞、游客要跳、字段要截断、缓存要失效,这四件事每个入口都得做一遍,
 * 散开写迟早漏一处。收在一个方法里,只有一处需要看。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiBehaviorServiceImpl implements AiBehaviorService {

    /** 关键词列的宽度是 VARCHAR(100),超了要截断,不然整个 insert 会失败 */
    private static final int KEYWORD_MAX = 100;

    /**
     * 停留时长的上限,2 小时。
     *
     * <p>这个值来自前端上报,也就是**不可信的输入**。它的用途只有一个 ——
     * 判断"这次浏览算不算认真看"(≥30 秒加一分),所以一个离谱的大数字
     * 并不会让它多加分(加分封顶)。但界面上会显示"最近看了 3 小时",
     * 那明显是错的。夹住它,是为了让存下来的数据在展示时也说得通。
     */
    private static final int STAY_SECONDS_MAX = 7200;

    private final AiUserBehaviorMapper behaviorMapper;
    private final BizResourceMapper resourceMapper;
    private final RecommendCache recommendCache;

    @Override
    public void viewed(Long resourceId, Integer staySeconds, int source) {
        LoginUser me = UserContext.getOrNull();
        if (me == null) {
            return;
        }
        save(me, AiUserBehavior.TYPE_VIEW, resourceId, categoryOf(resourceId), null, staySeconds, source);
    }

    @Override
    public void searched(String keyword) {
        LoginUser me = UserContext.getOrNull();
        if (me == null) {
            return;
        }
        // 搜索没有资源 id,也没有分类 —— 分类要等用户点了搜索结果才拿得到,
        // 所以搜索行为只贡献"关键词"这一个信号
        save(me, AiUserBehavior.TYPE_SEARCH, null, null, keyword, null, AiUserBehavior.SOURCE_SEARCH);
    }

    @Override
    public void favorited(Long resourceId) {
        LoginUser me = UserContext.getOrNull();
        if (me == null) {
            return;
        }
        save(me, AiUserBehavior.TYPE_FAVORITE, resourceId, categoryOf(resourceId), null, null,
                AiUserBehavior.SOURCE_DETAIL);
    }

    @Override
    public void ordered(Long resourceId) {
        LoginUser me = UserContext.getOrNull();
        if (me == null) {
            return;
        }
        save(me, AiUserBehavior.TYPE_ORDER, resourceId, categoryOf(resourceId), null, null,
                AiUserBehavior.SOURCE_DETAIL);
    }

    @Override
    public void evaluated(Long resourceId) {
        LoginUser me = UserContext.getOrNull();
        if (me == null) {
            return;
        }
        save(me, AiUserBehavior.TYPE_EVALUATE, resourceId, categoryOf(resourceId), null, null,
                AiUserBehavior.SOURCE_DETAIL);
    }

    @Override
    public void impression() {
        LoginUser me = UserContext.getOrNull();
        if (me == null) {
            return;
        }
        // 曝光没有目标资源(它是整个推荐位被看了一次),所以 resource_id 为空
        save(me, AiUserBehavior.TYPE_IMPRESSION, null, null, null, null,
                AiUserBehavior.SOURCE_RECOMMEND);
    }

    /**
     * 按资源 id 取它的分类,取不到返回 null。
     *
     * <p><b>分类由服务端查,不接受调用方(更不接受前端)传。</b>
     * 两个理由:
     * <ul>
     *   <li>推荐算法的输入不能是客户端说了算的 —— 伪造一个分类,
     *       就能把"猜你喜欢"往别的方向带;</li>
     *   <li>分类会被改(改分类是发布者的正常操作),而行为记录
     *       应当反映"当时那个资源属于哪一类"。取当前值虽然不是历史真值,
     *       但至少是权威的、一致的。</li>
     * </ul>
     *
     * <p>只 select 一列而不是 {@code selectById} 取整行:行为采集发生在
     * 浏览、下单、评价每一条路径上,取整行会白白多传十几个字段。
     * 查不到(资源被删)就返回 null —— 那一列允许为空,
     * 这条行为仍然值得记(用户确实看过/收藏过它)。
     */
    private Long categoryOf(Long resourceId) {
        if (resourceId == null) {
            return null;
        }
        try {
            BizResource probe = resourceMapper.selectOne(
                    new LambdaQueryWrapper<BizResource>()
                            .select(BizResource::getCategoryId)
                            .eq(BizResource::getId, resourceId));
            return probe == null ? null : probe.getCategoryId();
        } catch (Exception e) {
            // 取分类失败不该让"记一条行为"整体失败 —— 分类只是推荐的输入之一,
            // 缺了它这条行为仍然能贡献"浏览过"这个事实
            log.warn("取资源分类失败,行为仍会记录: resourceId={}", resourceId, e);
            return null;
        }
    }

    /**
     * 落一条行为,并且让这个用户的推荐缓存失效。
     *
     * <p><b>异常全部吞掉。</b>采集是旁路:用户来看资源、下单、评价是主流程,
     * 而"记一笔他看过什么"是附加动作。一次 insert 失败(表锁、字段异常、
     * 将来某次改动的副作用)绝不该让主流程失败 ——
     * 用户因为"行为记录写不进去"而下不了单,是荒谬的。
     *
     * <p>这不是"静默吞异常"那种坏味道:日志里留了痕,而且就算采集全挂,
     * 系统也只是推荐变笨,功能本身不受影响。旁路就该有旁路的失败姿态。
     *
     * <p><b>缓存失效要在写成功之后</b>,但即使写失败也建议失效一次 ——
     * 万一缓存里是旧的推荐、而用户又刚做了操作,失效比不失效好。
     * 这里放在 finally 里也是这个考虑。
     */
    private void save(LoginUser me, int type, Long resourceId, Long categoryId,
                      String keyword, Integer staySeconds, int source) {
        try {
            AiUserBehavior behavior = new AiUserBehavior();
            behavior.setUserId(me.getUserId());
            behavior.setVillageId(me.getVillageId());
            behavior.setBehaviorType(type);
            behavior.setResourceId(resourceId);
            behavior.setCategoryId(categoryId);
            behavior.setKeyword(truncate(keyword));
            behavior.setStaySeconds(clampStay(staySeconds));
            behavior.setSource(source);
            behaviorMapper.insert(behavior);
        } catch (Exception e) {
            log.warn("行为记录失败(不影响主流程): userId={}, type={}, resourceId={}",
                    me.getUserId(), type, resourceId, e);
        } finally {
            recommendCache.invalidate(me.getUserId());
        }
    }

    private static String truncate(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= KEYWORD_MAX ? trimmed : trimmed.substring(0, KEYWORD_MAX);
    }

    private static int clampStay(Integer staySeconds) {
        if (staySeconds == null || staySeconds < 0) {
            return 0;
        }
        return Math.min(staySeconds, STAY_SECONDS_MAX);
    }
}
