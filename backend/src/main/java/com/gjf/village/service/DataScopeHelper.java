package com.gjf.village.service;

import com.gjf.village.common.enums.DataScopeEnum;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.BaseQueryDTO;
import com.gjf.village.entity.SysVillage;
import com.gjf.village.mapper.SysVillageMapper;
import com.gjf.village.security.LoginUser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据级权限注入器。
 *
 * <p>把"当前用户能看到哪些数据"翻译成查询条件,写进 {@link BaseQueryDTO}。
 * Service 层的每个列表查询在拼装条件之前调一次即可。
 *
 * <p><b>四档数据范围的处理:</b>
 * <table border="1">
 *   <tr><th>范围</th><th>注入的条件</th></tr>
 *   <tr><td>1 全部</td><td>不加任何条件(超管)</td></tr>
 *   <tr><td>2 本镇</td><td>villageIds = 本镇节点 + 其下所有村</td></tr>
 *   <tr><td>3 本村</td><td>villageIds = [本村]</td></tr>
 *   <tr><td>4 仅本人</td><td>publisherId = 当前用户ID</td></tr>
 * </table>
 *
 * <p><b>关键设计:</b>villageIds 用"允许访问的集合"而不是"要排除的集合"。
 * 前者是白名单语义 —— 拿不到集合时最坏结果是看不到数据(安全);
 * 后者是黑名单语义 —— 拿不到集合时最坏结果是看到全部(危险)。
 * 权限设计永远选白名单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataScopeHelper {

    /**
     * "什么都看不到"的哨兵值。
     *
     * <p>用一个不可能存在的 id(-1)而不是空集合:空集合拼进
     * {@code IN ()} 是语法错误,而 {@code IN (-1)} 永远匹配不上任何行 ——
     * 同样安全,但不会让 SQL 挂掉。这个技巧在 {@link #apply} 里也用了。
     */
    private static final List<Long> NONE = List.of(-1L);

    private final SysVillageMapper villageMapper;

    /**
     * 把数据范围注入查询条件。
     *
     * <p>无论调用方之前 set 过什么,这里都会<b>覆盖</b> —— 这是防越权的关键。
     *
     * @param query 继承了 BaseQueryDTO 的查询对象(会被就地修改)
     */
    public void apply(BaseQueryDTO query) {
        LoginUser user = UserContext.get();

        // 先无条件清空,防止调用方(或恶意前端)预先塞值绕过检查
        query.setVillageIds(null);
        query.setPublisherId(null);

        DataScopeEnum scope = DataScopeEnum.of(user.getDataScope());

        switch (scope) {
            case ALL -> {
                // 超管:不加任何限制
            }
            case TOWN -> {
                List<Long> ids = resolveTownVillageIds(user.getVillageId());
                if (ids.isEmpty()) {
                    // 拿不到村列表时返回一个不可能匹配的条件,
                    // 让他什么都看不到,而不是看到全部
                    query.setVillageIds(List.of(-1L));
                } else {
                    query.setVillageIds(ids);
                }
            }
            case VILLAGE -> {
                if (user.getVillageId() == null) {
                    // 没绑定村组的村级管理员:同样按"看不到"处理,而不是"看全部"
                    query.setVillageIds(List.of(-1L));
                } else {
                    query.setVillageIds(List.of(user.getVillageId()));
                }
            }
            case SELF -> query.setPublisherId(user.getUserId());
        }

        log.debug("数据权限注入: userId={}, scope={}, villageIds={}, publisherId={}",
                user.getUserId(), scope, query.getVillageIds(), query.getPublisherId());
    }

    /**
     * 解析"本镇"范围:当前节点 + 其下所有子节点。
     *
     * <p>sys_village 是两级结构(乡镇 → 村)。如果用户的 village_id 指向乡镇节点,
     * 他要能管理下辖所有村;如果指向村节点,就只有它自己。
     * 两种情况用同一段逻辑处理,不用判断节点层级。
     */
    private List<Long> resolveTownVillageIds(Long villageId) {
        List<Long> ids = new ArrayList<>();
        if (villageId == null) {
            return ids;
        }
        ids.add(villageId);

        List<SysVillage> children = villageMapper.selectList(
                new LambdaQueryWrapper<SysVillage>()
                        .eq(SysVillage::getParentId, villageId)
                        .select(SysVillage::getId));
        children.forEach(child -> ids.add(child.getId()));
        return ids;
    }

    /**
     * 取当前用户的村组白名单,给<b>不走 {@code BaseQueryDTO} 的查询</b>用。
     *
     * <p>大多数列表查询走 {@link #apply(BaseQueryDTO)},把条件注进 DTO 就完事。
     * 但统计类的查询(比如推荐位点击率)是几条聚合 SQL,没有 DTO 可注 ——
     * 那些地方用这个方法直接把白名单取出来。
     *
     * <p><b>为什么不干脆复用 apply():</b>它对「仅本人」范围设的是
     * {@code publisherId} 而不是 {@code villageIds},拿回来的 villageIds 是
     * <b>null</b> —— 而在这个方法的语义里 null 表示"不加限制"。
     * 于是"仅本人"的用户会看到全部村组的数据。后台统计接口有
     * {@code @RequiresPerm} 挡在前面、正常走不到这里,但那种"靠上游拦住"
     * 的正确性太脆:将来权限码配错一次就成了越权,而且不会报错。
     * 所以这里显式按"拿不准就看不到"处理 —— 白名单语义下,
     * 拿不到集合时最坏结果必须是"看不到数据",不能是"看到全部"。
     *
     * @return 允许访问的村组;null 表示不加限制(仅"全部"范围)
     */
    public List<Long> villageScopeOrNull() {
        LoginUser user = UserContext.get();
        return switch (DataScopeEnum.of(user.getDataScope())) {
            case ALL -> null;
            case TOWN -> {
                List<Long> ids = resolveTownVillageIds(user.getVillageId());
                yield ids.isEmpty() ? NONE : ids;
            }
            case VILLAGE -> user.getVillageId() == null
                    ? NONE
                    : List.of(user.getVillageId());
            case SELF -> NONE;
        };
    }

    /**
     * 判断当前用户是否有权访问指定村组的数据。
     *
     * <p>用于"按ID查详情"这类查询 —— 这类接口走不到列表的过滤条件,
     * 必须在查到数据后单独校验一次归属,否则改一下URL里的ID就能看别村的数据。
     *
     * @return true 表示有权访问
     */
    public boolean canAccessVillage(Long targetVillageId) {
        LoginUser user = UserContext.get();
        DataScopeEnum scope = DataScopeEnum.of(user.getDataScope());

        return switch (scope) {
            case ALL -> true;
            case SELF -> true;   // "仅本人"不按村组限制,由调用方另行校验数据归属
            case TOWN -> targetVillageId != null
                    && resolveTownVillageIds(user.getVillageId()).contains(targetVillageId);
            case VILLAGE -> targetVillageId != null && targetVillageId.equals(user.getVillageId());
        };
    }

    /**
     * 判断当前用户是否有<b>管理视角</b>——即有没有资格查看他人的数据。
     *
     * <p><b>这个方法与 {@link #canAccessVillage} 的区别,以及为什么需要它:</b>
     * {@code canAccessVillage} 对「仅本人」范围返回 true,注释里写的是
     * "由调用方另行校验数据归属"。这个约定在<b>后台接口</b>上是成立的 ——
     * 那些接口有 {@code @RequiresPerm} 拦在前面,村民根本进不来,
     * 所以"仅本人返回 true"从来不会真的放谁过去。
     *
     * <p>但前台接口没有这层兜底。而 VILLAGER 和 BUYER 两个角色的
     * {@code data_scope} 都是 4(仅本人),于是把 {@code canAccessVillage}
     * 单独用在任何<b>前台也能访问到</b>的接口上,等于没做校验:
     * 任何一个登录用户改一下 URL 里的 ID,就能看到别人的数据。
     *
     * <p>这个方法把"数据范围本身超出仅本人"和"管不管得到这个村"合成一个
     * 结论,专门给前台可达的路径用。<b>判断依据是数据范围而不是角色编码</b>:
     * 将来若新增"镇管理员"(范围 2 本镇),这里不用改 ——
     * 而写成 {@code roleCodes.contains("VILLAGE_ADMIN")} 就得再改一遍,
     * 漏改的表现是"新角色什么都管不了",不报错,只是功能不通。
     *
     * @return true 表示当前用户是管理员且有权管辖该村
     */
    public boolean canManageVillage(Long targetVillageId) {
        LoginUser user = UserContext.getOrNull();
        if (user == null) {
            return false;
        }
        if (user.isSuperAdmin()) {
            return true;
        }
        if (DataScopeEnum.of(user.getDataScope()) == DataScopeEnum.SELF) {
            return false;
        }
        return canAccessVillage(targetVillageId);
    }
}
