package com.gjf.village.controller;

import com.gjf.village.ai.AiCallException;
import com.gjf.village.ai.DeepSeekClient;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.Result;
import com.gjf.village.common.result.ResultCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康检查与连通性自检接口。
 *
 * <p>批次 0 的验收就靠它:前端调用本接口并把返回值显示出来,
 * 一次性验证「前端 → 后端 → 数据库」整条链路通了。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "系统自检", description = "健康检查与连通性验证")
public class PingController {

    private final DataSource dataSource;
    private final DeepSeekClient deepSeekClient;

    @Value("${spring.application.name:village-rental-platform}")
    private String appName;

    @Value("${app.version:1.0.0}")
    private String appVersion;

    /**
     * 基础连通性检查(不查数据库)。
     */
    @GetMapping("/ping")
    @Operation(summary = "基础健康检查")
    public Result<Map<String, Object>> ping() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", appName);
        data.put("version", appVersion);
        data.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        data.put("message", "后端服务运行正常");
        return Result.success(data);
    }

    /**
     * 数据库连通性检查。
     *
     * <p>会真实建立一次连接并读取元数据,所以它能证明的不只是"接口活着",
     * 而是"接口 → 连接池 → MySQL"整条链路都通。
     *
     * <p>返回内容只包含数据库名和版本,不含账号、密码、连接串等敏感信息。
     */
    @GetMapping("/ping/db")
    @Operation(summary = "数据库连通性检查")
    public Result<Map<String, Object>> pingDb() {
        Map<String, Object> data = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            data.put("database", conn.getCatalog());
            data.put("product", meta.getDatabaseProductName());
            data.put("dbVersion", meta.getDatabaseProductVersion());
            data.put("driver", meta.getDriverName());
            data.put("message", "数据库连接正常");
            return Result.success(data);
        } catch (Exception e) {
            // 这里必须打堆栈 —— 连不上库的排查全靠它
            log.error("数据库连通性检查失败", e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "数据库连接失败,请检查 MySQL 服务与配置");
        }
    }

    /**
     * 异常处理自检 —— 用于验证全局异常处理器是否生效。
     *
     * <p>不传参数返回成功;传了 {@code type=business} 抛业务异常,
     * 传 {@code type=system} 抛系统异常。前端可据此确认
     * 「业务异常返回 400 系 code、系统异常不泄露堆栈」这两条规则确实生效。
     */
    @GetMapping("/ping/error")
    @Operation(summary = "异常处理自检")
    public Result<String> pingError(@RequestParam(required = false) String type) {
        if ("business".equals(type)) {
            throw BusinessException.badRequest("这是一个业务异常示例(参数不合法)");
        }
        if ("system".equals(type)) {
            // 故意制造一个空指针,验证系统异常不会把堆栈返回给前端
            throw new IllegalStateException("这是一个系统异常示例(不应出现在前端响应里)");
        }
        return Result.success("未触发异常,请传 type=business 或 type=system");
    }

    /**
     * AI 配置自检。
     *
     * <p>回答三个问题:密钥配没配、连不连得上、<b>配置里的模型名服务端认不认</b>。
     *
     * <p><b>第三个是这里最值钱的。</b>实测模型名写错是发现不了的 ——
     * 对话接口会返回 200,并在响应体里把 model 悄悄换成默认模型。
     * 所以"我的模型配错了没有"只能靠把 {@code /models} 的清单和配置值对一下
     * 来回答,报错日志里永远看不到。
     *
     * <p><b>它不消耗 token。</b>走的是免费的 {@code /models},而不是
     * "发一句真话去试试"。这个接口在免登录白名单里,后者等于把烧额度的
     * 开关摆给所有人。
     *
     * <p><b>失败也不抛异常。</b>诊断接口的职责是把原因说清楚 ——
     * 被诊断的东西坏了的时候,它自己变成 500 就什么都没说明白。
     * 所以失败信息写在返回体里,HTTP 依然是 200。
     */
    @GetMapping("/ping/ai")
    @Operation(summary = "AI 配置自检",
            description = "不消耗 token;失败时把原因写在返回体里而不是抛异常")
    public Result<Map<String, Object>> pingAi() {
        Map<String, Object> data = new LinkedHashMap<>();
        DeepSeekClient.Configuration config = deepSeekClient.configuration();
        data.put("configured", config.configured());
        data.put("model", config.model());
        data.put("maskedKey", config.maskedKey());
        data.put("baseUrl", config.baseUrl());
        data.put("thinkingDisabled", config.thinkingDisabled());
        data.put("readTimeoutSeconds", config.readTimeoutSeconds());

        if (!config.configured()) {
            data.put("reachable", false);
            data.put("message", "没有配置密钥,问答会直接走兜底话术。"
                    + "填 app.ai.deepseek.api-key 或设置环境变量 DEEPSEEK_API_KEY");
            return Result.success(data);
        }

        long start = System.currentTimeMillis();
        try {
            List<DeepSeekClient.ModelInfo> models = deepSeekClient.listModels();
            List<String> ids = models.stream().map(DeepSeekClient.ModelInfo::id).toList();
            boolean modelValid = ids.contains(config.model());
            data.put("reachable", true);
            data.put("costMs", System.currentTimeMillis() - start);
            data.put("modelValid", modelValid);
            data.put("availableModels", models);
            data.put("message", modelValid
                    ? "AI 配置正常,可用模型:" + ids
                    : "能连上,但配置的模型 " + config.model() + " 不在可用列表里:"
                      + ids + " —— 注意对话接口不会因此报错,它会静默换成默认模型");
            return Result.success(data);
        } catch (AiCallException e) {
            data.put("reachable", false);
            data.put("costMs", System.currentTimeMillis() - start);
            data.put("failure", e.failure().reason());
            data.put("message", e.getMessage());
            return Result.success(data);
        }
    }
}
