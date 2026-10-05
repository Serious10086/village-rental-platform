package com.gjf.village.controller.system;

import com.gjf.village.ai.AiFailure;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.failpoint.AiFailPoint;
import com.gjf.village.common.failpoint.TransactionFailPoint;
import com.gjf.village.common.result.Result;
import com.gjf.village.common.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 系统调试接口 —— 目前只有事务故障注入。
 *
 * <p><b>为什么这个接口是必要的,而不是"测试代码混进了生产"。</b>
 *
 * <p>验收要求里的"事务"这一项,证据不能只是代码里有 {@code @Transactional}
 * 注解 —— 那只能证明写了注解,证明不了失败时真的会回滚。
 * 要证明回滚,必须<b>让事务失败一次</b>,然后逐表查库确认没有残留。
 *
 * <p>而事务失败这件事,正常途径制造不出来:代码正确的时候它不会失败,
 * 想让它失败只能去改代码。改完再改回来,测的就不是要发布的那份代码了 ——
 * 这种"测过了"是假的。
 *
 * <p>所以留一个受控的失败点开关:平时它什么都不做,需要验证时由超管
 * 打开,下一个请求就会在指定位置抛异常。这是软件测试里标准的
 * <b>故障注入(fault injection)</b> 手法,和"打个断点看变量"是同一类东西 ——
 * 目的都是把难以复现的路径变成可重复执行的动作。
 *
 * <p><b>权限控制:</b>整个接口显式要求超管身份。普通管理员
 * (包括村级管理员)调用会直接 403 —— 否则任何人都能把系统
 * 切到"一用就报错"的状态。
 */
@RestController
@RequestMapping("/api/system/debug")
@RequiredArgsConstructor
@Tag(name = "系统调试", description = "事务故障注入开关,仅超级管理员可调用")
public class SysDebugController {

    private final TransactionFailPoint failPoint;
    private final AiFailPoint aiFailPoint;

    @PostMapping("/fail-point")
    @Operation(summary = "装载事务失败点",
            description = "装载后,下一个经过该点的请求会抛异常并回滚事务。"
                    + "只影响一个请求,触发后自动解除")
    public Result<String> arm(@RequestParam String point) {
        requireSuperAdmin();
        if (!TransactionFailPoint.CREATE_ORDER.equals(point)) {
            throw BusinessException.badRequest("未知的失败点:" + point);
        }
        failPoint.arm(point);
        return Result.success("已装载失败点 " + point + ",下次经过该点的事务将回滚", point);
    }

    @GetMapping("/fail-point")
    @Operation(summary = "查询当前装载的失败点")
    public Result<String> query() {
        requireSuperAdmin();
        // 未装载时返回空串而不是 null:接口要能被脚本稳定地解析,
        // 返回 null 会让 data 字段在 JSON 里整个消失,调用方得写两层判空
        String point = failPoint.armedPoint();
        return Result.success(point == null ? "未装载" : point, point == null ? "" : point);
    }

    @DeleteMapping("/fail-point")
    @Operation(summary = "解除装载")
    public Result<Void> clear() {
        requireSuperAdmin();
        failPoint.clear();
        return Result.success("已解除", null);
    }

    // ── AI 调用故障注入(批次 7) ────────────────────────────────
    //
    // 理由和上面那个事务失败点完全一样:验收项 AI-02(超时降级)与
    // AI-03(密钥失效降级)要断言的是"AI 调不通时走兜底话术、记下降级原因、
    // 而不是 500",而代码正确的时候 AI 不会调不通 —— 想让它调不通,
    // 传统做法是去改配置(超时改 1 毫秒、密钥改错)再重启。
    // 那样测的是"改过配置的那一份",而且每条都要重启一次,进不了回归。
    //
    // 参数用枚举名(AUTH / TIMEOUT / ...)而不是中文原因,是为了让脚本
    // 不必处理 URL 编码;返回体里把 reason 带出来,断言就能直接用。

    @PostMapping("/ai-fail-point")
    @Operation(summary = "装载 AI 调用失败点",
            description = "装载后,下一个提问会被强制判定为这种失败并走降级路径。"
                    + "只影响一次提问,触发后自动解除。failure 取 AiFailure 的枚举名,"
                    + "例如 AUTH(鉴权失败401)、TIMEOUT(超时)、RATE_LIMIT(限流429)")
    public Result<Map<String, Object>> armAi(@RequestParam String failure) {
        requireSuperAdmin();
        AiFailure target = parseFailure(failure);
        aiFailPoint.arm(target);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("armed", target.name());
        data.put("reason", target.reason());
        data.put("message", "已装载 AI 失败点 " + target.name()
                + ",下一个提问会降级,原因记为「" + target.reason() + "」");
        return Result.success(data);
    }

    @GetMapping("/ai-fail-point")
    @Operation(summary = "查询当前装载的 AI 失败点")
    public Result<Map<String, Object>> queryAi() {
        requireSuperAdmin();
        AiFailure armed = aiFailPoint.armedFailure();
        Map<String, Object> data = new LinkedHashMap<>();
        // 未装载时给空串而不是 null:接口要能被脚本稳定解析,
        // 返回 null 会让 data 里那个字段整个消失,调用方得写两层判空
        data.put("armed", armed == null ? "" : armed.name());
        data.put("reason", armed == null ? "" : armed.reason());
        // 把可选项一并列出来,脚本不用去读代码猜枚举名
        data.put("available", Arrays.stream(AiFailure.values())
                .map(f -> f.name() + "(" + f.reason() + ")")
                .toList());
        return Result.success(data);
    }

    @DeleteMapping("/ai-fail-point")
    @Operation(summary = "解除 AI 失败点装载")
    public Result<Void> clearAi() {
        requireSuperAdmin();
        aiFailPoint.clear();
        return Result.success("已解除", null);
    }

    /**
     * 把枚举名解析成失败类型。
     *
     * <p>非法值直接 400 并列出合法值 —— 故障注入是个手工/脚本触发的动作,
     * 拼错枚举名却静默成功(比如退化成"抛个通用异常"),会让测试
     * 以为自己在验 A、实际验的是 B。
     */
    private AiFailure parseFailure(String name) {
        try {
            return AiFailure.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("未知的失败类型:" + name
                    + ",可选:" + Arrays.stream(AiFailure.values()).map(Enum::name).toList());
        }
    }

    /**
     * 显式校验超管身份。
     *
     * <p>没有用 {@code @RequiresPerm}:那条路子要挂一个权限码,
     * 而权限码是给角色配的、可以在后台被改。这个开关的风险等级
     * 不该受角色配置影响,所以直接判死。
     */
    private void requireSuperAdmin() {
        if (!UserContext.get().isSuperAdmin()) {
            throw BusinessException.forbidden("该接口仅超级管理员可调用");
        }
    }
}
