package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.ChangePasswordDTO;
import com.gjf.village.dto.LoginDTO;
import com.gjf.village.dto.OutsideUserCreateDTO;
import com.gjf.village.dto.RegisterDTO;
import com.gjf.village.dto.UserQueryDTO;
import com.gjf.village.security.LoginUser;
import com.gjf.village.vo.LoginVO;
import com.gjf.village.vo.UserInfoVO;
import com.gjf.village.vo.UserListVO;

/**
 * 用户与认证服务。
 */
public interface SysUserService {

    /**
     * 登录。
     *
     * @return token 与用户信息
     */
    LoginVO login(LoginDTO dto);

    /**
     * 注册(手机号 + 密码 + 村组)。
     */
    Long register(RegisterDTO dto);

    /**
     * 管理员代建外来用户账号。
     *
     * <p>只建账号,<b>不</b>写归属村组 —— 此时这个人还没有村,
     * 目标村组只存在于审核单上,等备案通过才落到 {@code sys_user.village_id}。
     * 调用方(备案服务)负责校验目标村组在不在当前管理员的可管范围内。
     *
     * @return 新用户ID
     */
    Long createOutsideUser(OutsideUserCreateDTO dto);

    /**
     * 修改当前登录用户自己的密码。
     *
     * <p>改谁由登录态决定,不从参数取。原密码必须验对。
     *
     * <p><b>已知边界:</b>本项目是纯 JWT 无状态鉴权,服务端不存会话、也没有
     * token 黑名单,所以改完密码后<b>旧 token 在过期前依然有效</b>。
     * 前端改完会清掉本地登录态并跳回登录页,但那只防"本人换设备前忘记退出",
     * 挡不住已经泄露出去的 token。要真正作废,得引入 token 版本号或黑名单。
     */
    void changePassword(ChangePasswordDTO dto);

    /**
     * 装配登录用户上下文。
     *
     * <p>JWT 里只有 userId,每次请求都要靠这个方法把角色、权限码、数据范围
     * 重新装配出来。这样超管调整了某个角色的权限,该用户下一次请求就立即生效,
     * 不需要等 token 过期。
     *
     * @return 用户不存在或被禁用时返回 null
     */
    LoginUser buildLoginUser(Long userId);

    /**
     * 查询用户信息(含角色与权限码)。
     */
    UserInfoVO getUserInfo(Long userId);

    /**
     * 后台:分页查询用户列表。
     *
     * <p><b>这是数据级权限的示范接口。</b>方法内部会调用
     * {@code dataScopeHelper.apply(query)},把当前管理员的可见范围注入查询条件:
     * 村级管理员只能查到本村用户,超级管理员能查到全部。
     * 前端即使把 villageId 传成别的村,也会被 Service 层覆盖掉。
     */
    PageResult<UserListVO> pageUsers(UserQueryDTO query);
}
