package com.gjf.village.service.impl;

import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.entity.AiUserBehavior;
import com.gjf.village.entity.BizResource;
import com.gjf.village.mapper.AiUserBehaviorMapper;
import com.gjf.village.mapper.BizResourceMapper;
import com.gjf.village.mapper.RecommendMapper;
import com.gjf.village.recommend.RecommendCache;
import com.gjf.village.security.LoginUser;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.service.AiBehaviorService;
import com.gjf.village.service.DataScopeHelper;
import com.gjf.village.service.RecommendService;
import com.gjf.village.vo.BehaviorCountVO;
import com.gjf.village.vo.CategoryPreferenceVO;
import com.gjf.village.vo.RecommendItemVO;
import com.gjf.village.vo.RecommendStatVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智能推荐服务实现。
 *
 * <p>整个类里没有一个写操作 —— 推荐是只读的:它读行为、读资源,
 * 算出一个列表返回。写行为的责任在 {@code AiBehaviorService},
 * 两者分开之后,这个类可以随便加缓存、换算法,都不用担心动了数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendServiceImpl implements RecommendService {

    /** 偏好统计的时间窗。太长会把"上个月感兴趣、现在早不看了"算进来 */
    private static final int PREFERENCE_DAYS = 30;

    /** 取偏好最强的几个分类。3 个够了 —— 再多就开始"什么都推一点",等于没有偏好 */
    private static final int PREFERENCE_CATEGORY_LIMIT = 3;

    /** 默认条数 */
    private static final int DEFAULT_LIMIT = 8;

    /** 条数上限。前端传大了也只是浪费自己和数据库的时间,夹住 */
    private static final int MAX_LIMIT = 20;

    private static final int STAT_DEFAULT_DAYS = 7;
    private static final int STAT_MAX_DAYS = 90;

    private final AiUserBehaviorMapper behaviorMapper;
    private final RecommendMapper recommendMapper;
    private final BizResourceMapper resourceMapper;
    private final RecommendCache cache;
    private final DataScopeHelper dataScopeHelper;

    /**
     * 用来记推荐位曝光。
     *
     * <p>方向是"推荐 → 行为"(推荐要记曝光),而不是反过来 ——
     * 行为采集让缓存失效时用的是 {@link RecommendCache} 本身,
     * 不需要认识推荐服务。这样两个类之间只有一条依赖边,
     * 不会形成 Spring 启动时的那种循环依赖。
     */
    private final AiBehaviorService behaviorService;

    // ═══════════════════════════════════════════════════════════
    //  首页「猜你喜欢」
    // ═══════════════════════════════════════════════════════════

    @Override
    public List<RecommendItemVO> recommendForMe(int limit) {
        int size = clampLimit(limit);
        LoginUser me = UserContext.getOrNull();

        // 游客:没有行为也没有村组,只能给全局热门。
        // 理由如实写"大家都在看" —— 不能对一个我们一无所知的人说"根据你的兴趣"
        if (me == null) {
            List<RecommendItemVO> items = recommendMapper.selectHot(null, null, false, size);
            items.forEach(i -> {
                i.setStrategy(RecommendItemVO.STRATEGY_FALLBACK);
                i.setReason("大家都在看");
            });
            // 游客不记曝光 —— 点击率统计的分母因此只含登录用户,
            // 后台页面上的说明里写了这一点
            return items;
        }

        List<RecommendItemVO> cached = cache.get(me.getUserId());
        if (cached != null) {
            return cached;
        }

        List<RecommendItemVO> items = build(me.getUserId(), me.getVillageId(), size);
        cache.put(me.getUserId(), items);

        // 曝光在"算完并返回"之后记。放在缓存判断之后是有意的:
        // 命中缓存的那次也算一次曝光(推荐位确实又展示了一遍),
        // 但缓存未命中和命中在这一点上没有区别,所以这里挪到 put 之后统一记。
        // 换句话说:每次真正返回给前端的推荐结果,都记一次曝光。
        behaviorService.impression();
        return items;
    }

    /**
     * 三路召回。
     *
     * <p>顺序就是优先级:内容推荐 > 村组热度 > 全局兜底。
     * 用 {@code picked} 去重,而不是让 SQL 去排重 —— 三路是三次独立查询,
     * 同一条资源完全可能同时被"你偏好的分类"和"本村热门"命中,
     * 在 Java 里用一个 Set 拦掉最直接。
     */
    private List<RecommendItemVO> build(Long userId, Long villageId, int size) {
        List<RecommendItemVO> result = new ArrayList<>(size);
        Set<Long> picked = new HashSet<>();

        // ── ① 内容推荐 ──────────────────────────────────────────
        List<CategoryPreferenceVO> prefs =
                behaviorMapper.selectTopCategories(userId, PREFERENCE_DAYS, PREFERENCE_CATEGORY_LIMIT);
        if (!prefs.isEmpty()) {
            Map<Long, String> nameOf = new HashMap<>();
            List<Long> categoryIds = new ArrayList<>(prefs.size());
            for (CategoryPreferenceVO pref : prefs) {
                categoryIds.add(pref.getCategoryId());
                nameOf.put(pref.getCategoryId(), pref.getCategoryName());
            }
            for (RecommendItemVO item : recommendMapper.selectByCategories(
                    categoryIds, userId, true, villageId, size)) {
                item.setStrategy(RecommendItemVO.STRATEGY_CONTENT);
                item.setReason(reasonFor(nameOf.get(item.getCategoryId())));
                if (picked.add(item.getId())) {
                    result.add(item);
                }
            }
        }

        // ── ② 村组热度 ──────────────────────────────────────────
        if (result.size() < size && villageId != null) {
            for (RecommendItemVO item : recommendMapper.selectHot(villageId, userId, true, size)) {
                if (picked.add(item.getId())) {
                    item.setStrategy(RecommendItemVO.STRATEGY_VILLAGE_HOT);
                    item.setReason("本村热门");
                    result.add(item);
                }
            }
        }

        // ── ③ 全局兜底 ──────────────────────────────────────────
        if (result.size() < size) {
            for (RecommendItemVO item : recommendMapper.selectHot(null, userId, true, size)) {
                if (picked.add(item.getId())) {
                    item.setStrategy(RecommendItemVO.STRATEGY_FALLBACK);
                    item.setReason("大家都在看");
                    result.add(item);
                }
            }
        }

        return result.size() > size ? new ArrayList<>(result.subList(0, size)) : result;
    }

    /**
     * 推荐理由。
     *
     * <p><b>措辞是"关注过"而不是"看过"。</b>一条分类偏好可以来自五种行为里的
     * 任何一种:浏览、搜索、收藏、下单、评价。只下过单、从没点开过详情的
     * 用户被告知"你看过「电动车」",那是编的 —— 而推荐理由的全部价值
     * 就在于它说的是真的。第一版写的就是"你看过",写测试时才发现
     * 一个下单驱动的偏好会被它描述错。
     *
     * <p>分类名取不到时退到"你可能感兴趣",而不是拼出"你关注过「null」" ——
     * 分类被删掉的历史行为会走到这条分支,而一个显示 null 的理由
     * 比没有理由更糟。
     */
    private String reasonFor(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return "你可能感兴趣";
        }
        return "你关注过「" + categoryName + "」";
    }

    // ═══════════════════════════════════════════════════════════
    //  详情页「相似资源」
    // ═══════════════════════════════════════════════════════════

    @Override
    public List<RecommendItemVO> similarTo(Long resourceId, int limit) {
        int size = clampLimit(limit);
        BizResource resource = resourceMapper.selectById(resourceId);
        if (resource == null || resource.getCategoryId() == null) {
            throw BusinessException.notFound("资源不存在或已删除");
        }

        LoginUser me = UserContext.getOrNull();
        Long meId = me == null ? null : me.getUserId();
        Long villageId = me == null ? null : me.getVillageId();

        // 多要一条:下面要把"当前这个资源"自己剔掉,多要一条才不会少给一条
        List<RecommendItemVO> candidates = recommendMapper.selectByCategories(
                List.of(resource.getCategoryId()), meId,
                // 不排除看过的 —— 这是"探索同类",不是个性化推荐
                false, villageId, size + 1);

        List<RecommendItemVO> result = new ArrayList<>(size);
        for (RecommendItemVO item : candidates) {
            if (item.getId().equals(resourceId)) {
                continue;
            }
            item.setStrategy(RecommendItemVO.STRATEGY_CONTENT);
            item.setReason("同属「" + item.getCategoryName() + "」");
            result.add(item);
            if (result.size() >= size) {
                break;
            }
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    //  后台:推荐位点击率
    // ═══════════════════════════════════════════════════════════


    @Override
    public RecommendStatVO statistics(int days) {
        int window = days <= 0 ? STAT_DEFAULT_DAYS : Math.min(days, STAT_MAX_DAYS);
        // 村组白名单来自数据级权限:村管看本村用户产生的曝光与点击,超管看全部
        List<Long> villageIds = dataScopeHelper.villageScopeOrNull();
        RecommendStatVO stat = behaviorMapper.selectRecommendStat(villageIds, window);
        if (stat == null) {
            stat = new RecommendStatVO();
        }
        stat.setDaily(behaviorMapper.selectRecommendDaily(villageIds, window));

        // 各行为的采集量,顺手把中文名补上 —— 和 AI 对话记录那边一样,
        // 中文名在服务端给,不让前端再写一遍"1=浏览"的映射
        List<BehaviorCountVO> behaviors = behaviorMapper.selectBehaviorCounts(villageIds, window);
        behaviors.forEach(b -> b.setName(behaviorName(b.getType())));
        stat.setBehaviors(behaviors);
        return stat;
    }

    /** 行为类型的中文名。和 {@code AiUserBehavior.TYPE_*} 一一对应 */
    private static String behaviorName(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case AiUserBehavior.TYPE_VIEW -> "浏览";
            case AiUserBehavior.TYPE_SEARCH -> "搜索";
            case AiUserBehavior.TYPE_FAVORITE -> "收藏";
            case AiUserBehavior.TYPE_ORDER -> "下单";
            case AiUserBehavior.TYPE_EVALUATE -> "评价";
            case AiUserBehavior.TYPE_IMPRESSION -> "推荐位曝光";
            default -> "未知";
        };
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
