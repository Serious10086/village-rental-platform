package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 登录成功返回。
 *
 * <p>把 token 和用户信息一起返回,前端一次请求就能完成登录并渲染界面,
 * 不用再调一次 getInfo。
 */
@Data
public class LoginVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** JWT token,前端存 localStorage,后续请求放在 Authorization 头里 */
    private String token;

    /** token 有效期(分钟),前端可据此做"登录即将过期"提示 */
    private Long expiresIn;

    /** 用户信息(含角色和权限码) */
    private UserInfoVO userInfo;
}
