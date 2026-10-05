package com.gjf.village.controller.portal;

import com.gjf.village.common.result.Result;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.ChangePasswordDTO;
import com.gjf.village.dto.LoginDTO;
import com.gjf.village.dto.RegisterDTO;
import com.gjf.village.service.SysMenuService;
import com.gjf.village.service.SysUserService;
import com.gjf.village.vo.LoginVO;
import com.gjf.village.vo.RouterVO;
import com.gjf.village.vo.UserInfoVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证接口。
 *
 * <p>login 和 register 在白名单里,不需要 token;
 * getInfo 和 getRouters 需要登录。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "认证", description = "注册、登录、获取用户信息与菜单路由")
public class AuthController {

    private final SysUserService userService;
    private final SysMenuService menuService;

    @PostMapping("/register")
    @Operation(summary = "注册", description = "手机号 + 密码 + 姓名 + 村组。注册后即可登录,但需完成实名认证才能发布资源和下单")
    public Result<Map<String, Object>> register(@Valid @RequestBody RegisterDTO dto) {
        Long userId = userService.register(dto);
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("message", "注册成功,请登录");
        return Result.success("注册成功", data);
    }

    @PostMapping("/login")
    @Operation(summary = "登录", description = "返回 token 和用户信息(含角色、权限码)")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success("登录成功", userService.login(dto));
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录", description = "无状态 JWT,服务端不维护会话,这里只是给前端一个语义化的接口")
    public Result<Void> logout() {
        // JWT 是无状态的,服务端没有会话可销毁,真正的退出是前端删掉本地 token。
        // 之所以保留这个接口:① 前端调用它比什么都不做更符合直觉;
        // ② 将来如果要做"服务端强制下线"(把 token 加入黑名单),在这里扩展即可。
        return Result.success("已退出登录", null);
    }

    @PostMapping("/changePassword")
    @Operation(summary = "修改密码",
            description = "只能改当前登录用户自己的密码(改谁由 token 决定,请求体里没有 userId),需验证原密码。"
                    + "改完旧 token 在过期前仍然有效 —— 无状态 JWT 没有会话可作废")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        userService.changePassword(dto);
        return Result.success("密码已修改,请用新密码重新登录", null);
    }

    @GetMapping("/getInfo")
    @Operation(summary = "获取当前用户信息", description = "含角色编码和权限码,前端据此控制菜单与按钮显隐")
    public Result<UserInfoVO> getInfo() {
        return Result.success(userService.getUserInfo(UserContext.getUserId()));
    }

    @GetMapping("/getRouters")
    @Operation(summary = "获取用户菜单路由树",
            description = "前端据此动态注册后台路由。村民/买家没有后台菜单,返回空数组")
    public Result<List<RouterVO>> getRouters() {
        return Result.success(menuService.buildRouters(UserContext.getUserId()));
    }
}
