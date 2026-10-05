package com.gjf.village.recommend;

import com.gjf.village.vo.RecommendItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 推荐结果的进程内缓存。
 *
 * <h3>为什么缓存,以及为什么只缓存这么点时间</h3>
 *
 * <p>算一次推荐要跑三条查询(取偏好分类、取看过的资源、按分类拉候选),
 * 而首页每次刷新都会请求一次 —— 同一个用户反复刷首页不该反复算。
 *
 * <p>但 TTL 压得很短(5 分钟),因为<b>推荐的最佳状态是"刚刚好反映他最近在干什么"</b>。
 * 缓存一小时的话,用户上午看了一堆农机、下午打开首页还是推荐农机,
 * 他会觉得"这系统记着我上午的事"—— 那是另一种意义上的不准确。
 * 慢一点可以忍,推荐得不对不能忍,所以 TTL 取得短。
 *
 * <h3>失效比 TTL 更要紧</h3>
 *
 * <p><b>光有 TTL 是不够的,必须有人在写行为时主动失效。</b>
 * 否则会出现一个很扎眼的场景:用户点开一台拖拉机、看完回到首页,
 * 首页推荐的还是刚才那一批 —— 因为它还在缓存里。
 * "我明明刚看过,它却像没看见"是最容易被察觉的算法故障,
 * 比"推荐得不那么准"严重得多。
 *
 * <p>所以 {@code AiBehaviorServiceImpl} 每写一条行为就调一次
 * {@link #invalidate(Long)}。这也是为什么缓存单独做成一个组件,
 * 而不是藏在推荐服务内部的私有字段 —— 采集那一侧需要够得着它,
 * 又没必要为此依赖整个推荐服务。
 *
 * <h3>为什么可以没有分布式缓存</h3>
 *
 * <p>单机部署(毕设的部署形态)下,进程内的 Map 就是全部实例的缓存。
 * 换成多实例的话这里必须换成 Redis,否则同一份推荐在两个实例上
 * 算两次、失效也只失效一个 —— 但那要等真的多实例了再说。
 */
@Slf4j
@Component
public class RecommendCache {

    /** 缓存有效期。短,因为推荐的价值在于"反映最近" */
    private static final long TTL_MILLIS = 5 * 60 * 1000L;

    /** 缓存条目上限。超过就整体清空 —— 毕设的量级下不会到,到了说明该换 Redis 了 */
    private static final int MAX_ENTRIES = 5000;

    private record Entry(List<RecommendItemVO> items, long expireAt) {
    }

    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    /** 取缓存。未命中或已过期返回 null */
    public List<RecommendItemVO> get(Long userId) {
        Entry entry = cache.get(userId);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expireAt()) {
            cache.remove(userId);
            return null;
        }
        return entry.items();
    }

    public void put(Long userId, List<RecommendItemVO> items) {
        if (cache.size() >= MAX_ENTRIES) {
            log.info("推荐缓存条目达到上限 {},整体清空", MAX_ENTRIES);
            cache.clear();
        }
        cache.put(userId, new Entry(items, System.currentTimeMillis() + TTL_MILLIS));
    }

    /**
     * 让某个用户的缓存失效。
     *
     * <p>每写一条行为都会调它 —— 失效本身很便宜(一次 Map 移除),
     * 而算一次推荐很贵,所以宁可多失效几次。
     */
    public void invalidate(Long userId) {
        if (userId != null) {
            cache.remove(userId);
        }
    }

    /** 当前缓存了多少个用户(自检与测试用) */
    public int size() {
        return cache.size();
    }
}
