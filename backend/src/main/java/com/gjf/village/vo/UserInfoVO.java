package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Set;

/**
 * 当前登录用户信息。
 *
 * <p>前端拿到后存进 Pinia,用它做三件事:
 * <ul>
 *   <li>渲染顶栏的用户名、信用分、余额</li>
 *   <li>用 {@code perms} 控制按钮显隐(v-hasPerm 指令)</li>
 *   <li>用 {@code roles} 判断是否显示"后台管理"入口</li>
 * </ul>
 *
 * <p><b>注意这里不含密码</b>,也不含身份证号等敏感字段。
 */
@Data
public class UserInfoVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;

    private String phone;

    private String realName;

    /**
     * 身份证号(<b>已脱敏</b>)。
     *
     * <p>格式如 {@code 510124********1234}。即便是用户看自己的资料,
     * 也没必要返回完整号码 —— 这个响应会被存进 localStorage、
     * 可能被截图、可能被旁人看到。要完整号码的场景(管理员核对身份)
     * 走专门的审核详情接口。
     */
    private String idCard;

    private String avatar;

    /** 用户类型:1村民 2租户/买家 3管理员 */
    private Integer userType;

    private Long villageId;

    /** 村组名称,前端直接显示,省一次请求 */
    private String villageName;

    private String townName;

    private String address;

    private Integer creditScore;

    /** 实名状态:0未提交 1待审核 2已认证 3已驳回 */
    private Integer realnameStatus;

    private Integer accountStatus;

    private BigDecimal balance;

    /** 数据范围:1全部 2本镇 3本村 4仅本人 —— 前端可据此在标题上标注"本村" */
    private Integer dataScope;

    /** 角色编码集合,如 [VILLAGE_ADMIN, VILLAGER] */
    private Set<String> roles;

    /** 权限标识集合,如 [resource:audit, credit:adjust] —— 按钮级权限的数据源 */
    private Set<String> perms;

    /** 是否超级管理员 —— 前端据此决定是否显示"系统管理"入口 */
    private Boolean superAdmin;

    /** 是否已通过实名认证 —— 前端据此决定是否显示"发布资源"按钮 */
    private Boolean realnameVerified;
}
