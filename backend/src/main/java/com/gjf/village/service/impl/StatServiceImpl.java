package com.gjf.village.service.impl;

import com.gjf.village.common.enums.OrderStatusEnum;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.mapper.StatMapper;
import com.gjf.village.security.LoginUser;
import com.gjf.village.service.DataScopeHelper;
import com.gjf.village.service.StatService;
import com.gjf.village.vo.OrderTrendVO;
import com.gjf.village.vo.StatItemVO;
import com.gjf.village.vo.StatOverviewVO;
import com.gjf.village.vo.VillageStatVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * 数据统计服务实现。
 *
 * <p>整个类没有写操作,也没有事务 —— 统计是只读的,而且允许读到
 * "上一秒的"数据(统计本来就不追求强一致)。不加事务反而更好:
 * 一次统计要跑四条查询,包在一个事务里会把它们绑成同一个快照,
 * 而快照读对统计没有额外价值,却要占着一个数据库连接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatServiceImpl implements StatService {

    /** 趋势图看多少天。再长的话,村级规模下大部分点是 0,折线成了一根趴在底部的线 */
    private static final int TREND_DAYS = 30;

    /** 订单状态:使用中 / 已完成 —— 计算利用率时算作"这个资源被用上了" */
    private static final int STATUS_USING = 3;
    private static final int STATUS_FINISHED = 4;

    private final StatMapper statMapper;
    private final DataScopeHelper dataScopeHelper;

    @Override
    public StatOverviewVO dashboard() {
        // 村管 → [本村];超管 → null(不加限制)
        return overview(dataScopeHelper.villageScopeOrNull(), true, false);
    }

    /**
     * 村级统计。
     *
     * <p><b>村管传的 villageId 一律忽略。</b>这是这一层最重要的一个判断:
     * 前端传什么都往后端塞的做法在查询接口上就等于没有权限 ——
     * 改一下 URL 里的村组 id 就能看别村的数字。所以范围只由登录态决定,
     * 参数只对超管有意义。
     *
     * <p>超管不传 villageId 时,挑一个**资源最多的村**作为默认。
     * 不这么做的话,超管(他的 village_id 指向镇节点)打开这个页面会看到
     * 一整屏的 0 —— 因为资源挂在村上,镇节点自己一条都没有。
     * 给一个"有内容的"默认值比给一个空页面有用。
     */
    @Override
    public StatOverviewVO village(Long villageId) {
        LoginUser me = UserContext.get();
        Long target;
        if (me.isSuperAdmin()) {
            target = villageId != null ? villageId : pickRichestVillage();
        } else {
            if (me.getVillageId() == null) {
                throw BusinessException.badRequest("你的账号还没有绑定村组,看不到村级统计");
            }
            target = me.getVillageId();
        }
        // 村级统计给的是"一个范围",所以按单村过滤。
        // withVillages 传 true:那一行里带着**村名**,前端要把"这些数字是哪个村的"
        // 显示出来 —— 超管切村之后如果页面上没有村名,他分不清自己看的是哪个村。
        return overview(List.of(target), true, true);
    }

    /**
     * 全局统计。
     *
     * <p>这里再判一次超管,而接口上已经有 {@code @RequiresPerm("stat:global")}。
     * 冗余的理由和 {@code PermissionAspect} 里那句"超管直接放行"类似:
     * 权限码是**配出来的**,可以在后台改;而这个接口的语义是"全平台" ——
     * 一旦哪天有人手滑把 {@code stat:global} 授给了村管,
     * 他看到的就不是"本村",而是**所有村的横向对比**。
     * 这种"改配置就泄露"的路径,值得多用一行代码钉死。
     */
    @Override
    public StatOverviewVO global() {
        if (!UserContext.get().isSuperAdmin()) {
            throw BusinessException.forbidden("全局统计仅平台管理员可见");
        }
        // 超管的 scope 是 null,也就是"不加村组限制" —— 这正是全局统计要的
        return overview(dataScopeHelper.villageScopeOrNull(), true, true);
    }

    // ═══════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════

    /**
     * 组装统计总览。
     *
     * @param villageIds   村组白名单;null 表示全部
     * @param withTrend    是否要近 30 天趋势(只有工作台和全局统计要)
     * @param withVillages 是否要各村明细(只有全局统计要)
     */
    private StatOverviewVO overview(List<Long> villageIds, boolean withTrend, boolean withVillages) {
        List<VillageStatVO> rows = statMapper.selectVillageStats(villageIds);

        StatOverviewVO vo = new StatOverviewVO();
        long resourceTotal = 0, online = 0, rent = 0, sell = 0, highRisk = 0;
        long users = 0, orders = 0, finished = 0, using = 0;
        for (VillageStatVO r : rows) {
            resourceTotal += nz(r.getResourceTotal());
            online        += nz(r.getOnlineCount());
            rent          += nz(r.getRentCount());
            sell          += nz(r.getSellCount());
            highRisk      += nz(r.getHighRiskCount());
            users         += nz(r.getUserTotal());
            orders        += nz(r.getOrderTotal());
            finished      += nz(r.getOrderFinished());
            using         += nz(r.getOrderUsing());
        }
        vo.setResourceTotal(resourceTotal);
        vo.setOnlineCount(online);
        vo.setRentCount(rent);
        vo.setSellCount(sell);
        vo.setHighRiskCount(highRisk);
        vo.setUserTotal(users);
        vo.setOrderTotal(orders);
        vo.setOrderFinished(finished);
        vo.setOrderUsing(using);

        // 利用率用**汇总后的两个数**重新算,不是各村比率的平均 ——
        // 一个只有 1 个资源、恰好成过 1 单的村会有 100% 的利用率,
        // 把那个数平均进去会显著拉高整体,而它代表不了整个平台
        vo.setUsageRate(resourceTotal == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(using + finished)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(resourceTotal), 2, RoundingMode.HALF_UP));

        vo.setCategoryDist(statMapper.selectCategoryDist(villageIds));
        vo.setOrderStatusDist(translateOrderStatus(statMapper.selectOrderStatusDist(villageIds)));
        if (withTrend) {
            vo.setOrderTrend(statMapper.selectOrderTrend(villageIds, TREND_DAYS));
        }
        if (withVillages) {
            vo.setVillages(rows);
        }
        return vo;
    }

    /**
     * 把订单状态码翻译成中文名。
     *
     * <p>SQL 里只取回状态码,名字在这里补 —— 状态机的唯一定义处是
     * {@code OrderStatusEnum},在 SQL 里再写一遍 CASE WHEN 等于把它复制了一份,
     * 而两份迟早会漂开(新增一个状态时最容易漏改 SQL 那一份)。
     */
    private List<StatItemVO> translateOrderStatus(List<StatItemVO> raw) {
        for (StatItemVO item : raw) {
            String code = item.getName();
            try {
                item.setName(OrderStatusEnum.of(Integer.valueOf(code)).getDescription());
            } catch (Exception e) {
                // 状态码不在枚举里(脏数据或将来新增了状态没同步枚举):
                // 保留原始码而不是丢掉这条 —— 图上显示 "99" 总比少一根柱子强,
                // 而且那个数字本身就是排查的线索
                log.warn("订单状态码不在枚举内: {}", code);
            }
        }
        return raw;
    }

    /** 超管没指定村时挑一个资源最多的村,让页面有内容可看 */
    private Long pickRichestVillage() {
        return statMapper.selectVillageStats(null).stream()
                .filter(v -> nz(v.getResourceTotal()) > 0)
                .max(Comparator.comparingLong(v -> nz(v.getResourceTotal())))
                .map(VillageStatVO::getVillageId)
                .orElseThrow(() -> BusinessException.notFound("还没有任何村组有资源"));
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
