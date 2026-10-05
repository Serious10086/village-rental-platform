package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户表。
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 手机号(登录账号) */
    private String phone;

    /**
     * 密码(BCrypt 密文)。
     *
     * <p>两层保护:
     * <ul>
     *   <li>{@code @TableField(select = false)} —— MyBatis-Plus 生成的查询不带上该列,
     *       日常查询根本取不到密码。只有登录时用 Mapper 里手写的 SQL 专门查。</li>
     *   <li>{@code @JsonIgnore} —— 兜底,万一某处把 SysUser 直接返回给前端,
     *       密码字段也不会被序列化出去。</li>
     * </ul>
     */
    @JsonIgnore
    @TableField(select = false)
    private String password;

    /** 用户类型:1村民 2租户/买家 3管理员 */
    private Integer userType;

    private String realName;
    private String idCard;
    private String idCardFront;
    private String idCardBack;

    /** 所属村组ID —— 数据级权限的锚点 */
    private Long villageId;

    private String address;
    private String avatar;

    /** 信用分 */
    private Integer creditScore;

    /** 实名状态:0未提交 1待审核 2已认证 3已驳回 */
    private Integer realnameStatus;

    /** 账号状态:0禁用 1正常 2封禁 */
    private Integer accountStatus;

    /** 账户余额 */
    private BigDecimal balance;

    /** 是否外来用户 */
    private Integer isOutside;

    private LocalDateTime lastLoginTime;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
