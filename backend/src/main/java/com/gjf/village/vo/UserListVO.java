package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 后台用户列表项。
 *
 * <p>与 {@link UserInfoVO} 的区别:这是"管理别人"的视角,
 * 所以带上村组、注册时间、角色等管理需要的信息,
 * 但同样<b>不含密码和身份证号</b> —— 身份证号只在实名审核详情页单独返回。
 */
@Data
public class UserListVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String phone;
    private String realName;
    private String avatar;
    private Integer userType;
    private Long villageId;
    private String villageName;
    private String address;
    private Integer creditScore;
    private Integer realnameStatus;
    private Integer accountStatus;
    private BigDecimal balance;
    private Integer isOutside;
    private Set<String> roleNames;
    private LocalDateTime createTime;
    private LocalDateTime lastLoginTime;
}
