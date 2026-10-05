package com.gjf.village.security;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 登录用户上下文对象。
 *
 * <p>JWT 里只存 userId(避免 token 过大、也避免改了权限要等 token 过期),
 * 其余信息在每次请求时从数据库装配到本对象,放进 {@link com.gjf.village.common.util.UserContext}。
 *
 * <p>它是三层权限的公共数据源:
 * <ul>
 *   <li><b>菜单级</b> —— 用 roles 反查 sys_role_menu</li>
 *   <li><b>按钮级</b> —— 用 perms 集合与 @RequiresPerm 比对</li>
 *   <li><b>数据级</b> —— 用 villageId + dataScope 给查询注入过滤条件</li>
 * </ul>
 */
@Data
public class LoginUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;

    private String phone;

    private String realName;

    /** 用户类型:1村民 2租户/买家 3管理员 */
    private Integer userType;

    /**
     * 所属村组ID —— 数据级权限的锚点。
     * 村级管理员的所有查询最终都会带上 {@code village_id = this.villageId}。
     */
    private Long villageId;

    /** 数据范围:1全部 2本镇 3本村 4仅本人(多角色取最宽松者) */
    private Integer dataScope;

    /** 是否已通过实名认证(2=已认证)。未实名不能发布资源和下单 */
    private Integer realnameStatus;

    /** 角色编码集合,如 [VILLAGE_ADMIN, VILLAGER] */
    private Set<String> roleCodes = new HashSet<>();

    /** 权限标识集合,如 [resource:audit, credit:adjust] */
    private Set<String> perms = new HashSet<>();

    /** 角色ID列表(用于反查菜单) */
    private List<Long> roleIds;

    // ── 便捷判断 ────────────────────────────────────────────

    /** 是否超级管理员 */
    public boolean isSuperAdmin() {
        return roleCodes.contains("SUPER_ADMIN");
    }

    /** 是否拥有某个权限标识 */
    public boolean hasPerm(String perm) {
        return perms.contains(perm);
    }

    /** 是否已通过实名认证 */
    public boolean isRealnameVerified() {
        return Integer.valueOf(2).equals(realnameStatus);
    }
}
