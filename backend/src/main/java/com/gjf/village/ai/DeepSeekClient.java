package com.gjf.village.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek 客户端 —— 一次问答就是一次 {@link #chat} 调用。
 *
 * <p>这一层只做四件事:<b>拼请求、发请求、认失败、解析回答</b>。
 * 业务规则(提示词)、降级策略、落库都在上层 —— 因为"什么算业务上可接受的回答"
 * 是业务的事,而"这次调用是超时还是欠费"是通信的事,混在一起两边都不好改。
 *
 * <h3>几处刻意的选择</h3>
 *
 * <p><b>用 JDK 自带的 HttpClient,不引额外依赖。</b>
 * Spring 的 RestTemplate 要额外的 bean 配置且已进入维护模式,WebClient 要 WebFlux。
 * JDK 17 自带的 {@code java.net.http.HttpClient} 支持按连接和按请求分别设超时
 * —— 正好对上"连接 3 秒 / 读取 10 秒"这两个不同的语义。这和连接池选 HikariCP
 * 而不是 Druid 是同一个判断:能不加依赖就不加。
 *
 * <p><b>回答只读 {@code content},绝不读 {@code reasoning_content}。</b>
 * 这两个是两个字段,而 {@code reasoning_content} 是模型的内心独白
 * (实测长这样:"我们需要回答用户中文问题…需要提供关于押金如何计算的说明…")。
 * 取错字段的后果不是"回答不太对",而是把一段自言自语推给村民。
 *
 * <p><b>空回答当成失败,不当成"成功但没内容"。</b>
 * 实测踩到过:开启思维链时若 {@code max_tokens} 给小了,模型会把预算全花在
 * 推理上,返回 <b>HTTP 200 而 {@code content} 是空字符串</b>
 * ({@code finish_reason=length}、{@code reasoning_tokens} 等于上限)。
 * 不拦住的话,库里会存下一条空白回答,而且看起来是成功记录。
 */
@Slf4j
@Component
public class DeepSeekClient {

    private static final String CHAT_PATH = "/chat/completions";

    /** 列模型。实测 {@code /models} 和 {@code /v1/models} 都通,用不带版本号的那个 */
    private static final String MODELS_PATH = "/models";

    /** 重试前的等待。固定间隔而不是指数退避:最多重试 1 次,退避曲线没有意义 */
    private static final long RETRY_BACKOFF_MS = 500L;

    /** 日志里正文的截断长度 */
    private static final int BODY_SNIPPET = 200;

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;
    private final int maxRetries;
    private final boolean disableThinking;
    private final int maxOutputTokens;
    private final ObjectMapper objectMapper;

    /**
     * 连接池。HttpClient 是线程安全的、内部自带连接池,<b>建一次复用</b> ——
     * 每次调用都 new 一个的话,连接池和线程池都会跟着重建,
     * 而"每次请求新建 HttpClient"正是 JDK 文档里明确提醒不要做的事。
     */
    private final HttpClient httpClient;

    public DeepSeekClient(
            @Value("${app.ai.deepseek.api-key:}") String apiKey,
            @Value("${app.ai.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${app.ai.deepseek.model:deepseek-flash}") String model,
            @Value("${app.ai.deepseek.connect-timeout-seconds:3}") int connectTimeoutSeconds,
            @Value("${app.ai.deepseek.read-timeout-seconds:10}") int readTimeoutSeconds,
            @Value("${app.ai.deepseek.max-retries:1}") int maxRetries,
            @Value("${app.ai.deepseek.disable-thinking:true}") boolean disableThinking,
            @Value("${app.ai.deepseek.max-output-tokens:800}") int maxOutputTokens,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.maxRetries = Math.max(maxRetries, 0);
        this.disableThinking = disableThinking;
        this.maxOutputTokens = maxOutputTokens;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
    }

    /**
     * 启动时把生效的配置打出来。
     *
     * <p><b>为什么非打不可:模型名配错了是发现不了的。</b>
     * 实测传一个已退役的模型名(deepseek-chat),接口返回 200,
     * 而且响应体里的 {@code model} 被换成了 deepseek-flash ——
     * 服务端会静默回退到默认模型。也就是说"配错模型"既不会报错、
     * 也不会体现在结果里,只能靠日志确认。这条日志就是那个确认点。
     */
    @PostConstruct
    void logConfiguration() {
        if (!hasApiKey()) {
            log.warn("AI 智能客服未配置密钥,问答将直接走兜底话术。"
                    + "请在 application-dev.yml 里填 app.ai.deepseek.api-key,"
                    + "或设置环境变量 DEEPSEEK_API_KEY,然后重启");
            return;
        }
        log.info("AI 智能客服: model={}, baseUrl={}, key={}, 连接超时={}s, 读取超时={}s, "
                        + "最大重试={}, 思维链={}, 单次上限={} tokens",
                model, baseUrl, mask(apiKey), connectTimeoutSeconds, readTimeoutSeconds,
                maxRetries, disableThinking ? "已关闭" : "开启", maxOutputTokens);
    }

    // ═══════════════════════════════════════════════════════════
    //  调用
    // ═══════════════════════════════════════════════════════════

    /** 一轮对话消息 */
    public record ChatMessage(String role, String content) {
    }

    /** 一个可用模型 */
    public record ModelInfo(String id, String name, int contextWindow) {
    }

    /**
     * 生效的配置 —— 给自检端点用。
     *
     * <p>密钥是<b>掩码后</b>的。这个 record 会被序列化成 HTTP 响应,
     * 所以它绝不能含有完整的密钥。
     */
    public record Configuration(boolean configured, String model, String maskedKey,
                                String baseUrl, int connectTimeoutSeconds, int readTimeoutSeconds,
                                int maxRetries, boolean thinkingDisabled, int maxOutputTokens) {
    }

    /**
     * 一次成功的问答。
     *
     * @param answer           回答正文(只含 {@code content},不含思维链)
     * @param actualModel      服务端实际使用的模型 —— 正常应当等于配置值;
     *                         不相等说明配置里的模型名被静默回退了,值得记一笔
     * @param promptTokens     提示 token 数
     * @param completionTokens 生成 token 数
     * @param costMs           耗时(毫秒),含重试
     */
    public record ChatResult(String answer, String actualModel,
                             int promptTokens, int completionTokens, long costMs) {
    }

    /**
     * 问一次。
     *
     * @param systemPrompt 业务规则(提示词)。可以为空
     * @param history      最近几轮对话,按时间正序,<b>不含本次提问</b>。可以为空
     * @param question     本次提问
     */
    public ChatResult chat(String systemPrompt, List<ChatMessage> history, String question) {
        requireApiKey();
        String body = buildRequestBody(systemPrompt, history, question);

        AiCallException lastFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return doCall(body);
            } catch (AiCallException e) {
                lastFailure = e;
                // 不可重试的失败立刻放弃 —— 密钥失效、欠费、参数错误,
                // 重试一次的结果和第一次完全一样,只是让用户多等 500 毫秒
                if (!e.retryable() || attempt == maxRetries) {
                    break;
                }
                log.warn("AI 调用失败,准备重试(第 {} 次,共 {} 次):{} —— {}",
                        attempt + 1, maxRetries, e.failure().reason(), e.getMessage());
                sleepQuietly(RETRY_BACKOFF_MS);
            }
        }
        throw lastFailure;
    }

    // ═══════════════════════════════════════════════════════════
    //  自检
    // ═══════════════════════════════════════════════════════════

    /** 当前生效的配置(密钥已掩码) */
    public Configuration configuration() {
        return new Configuration(hasApiKey(), model, mask(apiKey), baseUrl,
                connectTimeoutSeconds, readTimeoutSeconds,
                maxRetries, disableThinking, maxOutputTokens);
    }

    /**
     * 列出这把密钥可用的模型。
     *
     * <p><b>这个接口是免费的,不消耗 token</b> —— 所以它才能做成一个随时可点的自检。
     * "发一句真话去试试通不通"那种做法不行:自检端点通常免登录,
     * 等于把烧额度的开关摆给所有人。
     *
     * <p>它的第二个用处是抓"模型名静默回退":实测传一个已退役的模型名,
     * 对话接口会返回 200 并悄悄换成默认模型,不报任何错。
     * 拿配置值和这张列表对一下,配错就能立刻看出来。
     */
    public List<ModelInfo> listModels() {
        requireApiKey();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + MODELS_PATH))
                .timeout(Duration.ofSeconds(readTimeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw new AiCallException(AiFailure.TIMEOUT, true,
                    "连接 " + baseUrl + " 超过 " + connectTimeoutSeconds + " 秒", e);
        } catch (HttpTimeoutException e) {
            throw new AiCallException(AiFailure.TIMEOUT, false,
                    "读取响应超过 " + readTimeoutSeconds + " 秒", e);
        } catch (IOException e) {
            throw new AiCallException(AiFailure.NETWORK, true, "网络异常:" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiCallException(AiFailure.NETWORK, false, "调用被中断", e);
        }

        if (response.statusCode() != 200) {
            throw classifyHttpError(response);
        }
        try {
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            List<ModelInfo> models = new ArrayList<>();
            for (JsonNode node : data) {
                models.add(new ModelInfo(
                        node.path("id").asText(""),
                        node.path("name").asText(""),
                        node.path("context_window").asInt(0)));
            }
            return models;
        } catch (JsonProcessingException e) {
            throw new AiCallException(AiFailure.BAD_RESPONSE, false,
                    "模型列表不是合法 JSON:" + abbreviate(response.body()), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  请求
    // ═══════════════════════════════════════════════════════════

    /**
     * 拼请求体。
     *
     * <p>用 Jackson 建树而不是拼字符串:系统提示词里会有换行、引号、
     * 甚至用户可能引用了别处的 JSON —— 手工拼接的 JSON 一旦遇到这些字符
     * 就会拼出非法请求,而报错只显示"Failed to deserialize the JSON body",
     * 排查起来要回头看那一大段提示词。构建器把转义交给库。
     */
    private String buildRequestBody(String systemPrompt, List<ChatMessage> history, String question) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxOutputTokens);

        ArrayNode messages = root.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.addObject().put("role", "system").put("content", systemPrompt);
        }
        if (history != null) {
            for (ChatMessage m : history) {
                if (m.content() == null || m.content().isBlank()) {
                    continue;
                }
                messages.addObject().put("role", m.role()).put("content", m.content());
            }
        }
        messages.addObject().put("role", "user").put("content", question);

        if (disableThinking) {
            // {"type":"disabled"} 是实测过的写法。关掉之后同一句提问:
            // completion 241 → 20 tokens、耗时 1918 → 1118 ms,
            // 而且响应里不再有 reasoning_content,取错字段的风险也一并消失
            root.putObject("thinking").put("type", "disabled");
        }
        return root.toString();
    }

    private ChatResult doCall(String body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + CHAT_PATH))
                .timeout(Duration.ofSeconds(readTimeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            // 连不上。重试有意义 —— 一秒之内重连上了就不用降级
            throw new AiCallException(AiFailure.TIMEOUT, true,
                    "连接 " + baseUrl + " 超过 " + connectTimeoutSeconds + " 秒", e);
        } catch (HttpTimeoutException e) {
            // 读超时。**不重试**:已经等满 readTimeoutSeconds,再重试一次
            // 就是让用户等两倍,不如立刻走兜底话术把答案给他
            throw new AiCallException(AiFailure.TIMEOUT, false,
                    "读取响应超过 " + readTimeoutSeconds + " 秒", e);
        } catch (IOException e) {
            throw new AiCallException(AiFailure.NETWORK, true,
                    "网络异常:" + e.getMessage(), e);
        } catch (InterruptedException e) {
            // 恢复中断标记 —— 吃掉它会让"停服时线程要能退出"这件事失效
            Thread.currentThread().interrupt();
            throw new AiCallException(AiFailure.NETWORK, false, "调用被中断", e);
        }

        long costMs = System.currentTimeMillis() - start;
        if (response.statusCode() != 200) {
            throw classifyHttpError(response);
        }
        return parseSuccess(response.body(), costMs);
    }

    // ═══════════════════════════════════════════════════════════
    //  响应
    // ═══════════════════════════════════════════════════════════

    /**
     * 把 HTTP 状态码翻译成归因 + 是否重试。
     *
     * <p>实测的错误响应形状是统一的:
     * <pre>{"error":{"message":"...","type":"...","code":"..."}}</pre>
     * 所以能取到原话。原话里带 {@code request_id},排查时拿它去问 DeepSeek
     * 比什么都好使,所以日志里要留着。
     */
    private AiCallException classifyHttpError(HttpResponse<String> response) {
        int code = response.statusCode();
        String detail = extractErrorMessage(response.body());
        return switch (code) {
            case 401 -> new AiCallException(AiFailure.AUTH, false,
                    "鉴权失败,密钥可能已失效或被撤销:" + detail);
            case 402 -> new AiCallException(AiFailure.INSUFFICIENT_BALANCE, false,
                    "账户余额不足:" + detail);
            case 429 -> new AiCallException(AiFailure.RATE_LIMIT, true,
                    "被限流:" + detail);
            case 400, 422 -> new AiCallException(AiFailure.BAD_REQUEST, false,
                    "请求不合法:" + detail);
            default -> code >= 500
                    ? new AiCallException(AiFailure.SERVER_ERROR, true,
                            "DeepSeek 服务端错误(HTTP " + code + "):" + detail)
                    : new AiCallException(AiFailure.BAD_RESPONSE, false,
                            "未预期的状态码 HTTP " + code + ":" + detail);
        };
    }

    /** 从错误响应里取 {@code error.message};取不到就返回截断后的原文 */
    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "(响应体为空)";
        }
        try {
            String message = objectMapper.readTree(body).path("error").path("message").asText("");
            return message.isBlank() ? abbreviate(body) : message;
        } catch (JsonProcessingException e) {
            return abbreviate(body);
        }
    }

    private ChatResult parseSuccess(String body, long costMs) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new AiCallException(AiFailure.BAD_RESPONSE, false,
                    "响应不是合法 JSON:" + abbreviate(body), e);
        }

        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String finishReason = choice.path("finish_reason").asText("");

        // 只取 content。理由见类注释
        String answer = message.path("content").asText("");
        if (answer.isBlank()) {
            throw new AiCallException(AiFailure.BAD_RESPONSE, false, describeBlankAnswer(message, finishReason));
        }

        JsonNode usage = root.path("usage");
        String actualModel = root.path("model").asText(model);
        if (!model.equals(actualModel)) {
            // 服务端把模型名换掉了。不报错(调用是成功的),但必须留痕 ——
            // 这说明配置里的模型名已经不被支持,静默回退到了别的模型
            log.warn("请求的模型是 {},服务端实际用的是 {} —— 配置里的模型名可能已失效",
                    model, actualModel);
        }
        return new ChatResult(
                answer.trim(),
                actualModel,
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                costMs);
    }

    /**
     * 空回答的失败原因。
     *
     * <p>分开写这两种,是因为它们的处置完全不同:
     * 被思维链吃光预算 → 去查 {@code disable-thinking} 和 {@code max-output-tokens};
     * 其他空回答 → 去查提示词和模型本身。
     * 合成一句"模型返回了空回答",排查到天亮也找不到前者。
     */
    private String describeBlankAnswer(JsonNode message, String finishReason) {
        boolean reasoningPresent = !message.path("reasoning_content").asText("").isBlank();
        if ("length".equals(finishReason) && reasoningPresent) {
            return "模型把 token 预算全用在了思维链上,content 为空(finish_reason=length)。"
                    + "检查 app.ai.deepseek.disable-thinking 是否为 true、max-output-tokens 是否过小";
        }
        return "模型返回了空回答(finish_reason=" + finishReason + ")";
    }

    // ═══════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════

    private boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 没配密钥就别发请求。
     *
     * <p>空的 {@code Authorization: Bearer} 发出去会拿回一个 401,
     * 而归因会写成"鉴权失败" —— 于是"密钥没填"和"密钥不对"混成一件,
     * 排查的人会去 platform.deepseek.com 反复确认自己的 key 有没有过期。
     */
    private void requireApiKey() {
        if (!hasApiKey()) {
            throw new AiCallException(AiFailure.NOT_CONFIGURED, false,
                    "没有配置 DeepSeek 密钥:填 app.ai.deepseek.api-key 或设置环境变量 DEEPSEEK_API_KEY");
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiCallException(AiFailure.NETWORK, false, "重试等待被中断", e);
        }
    }

    /**
     * 密钥掩码。
     *
     * <p>日志里出现完整密钥就等于密钥泄露了 —— 日志会被复制、会被贴进问题单、
     * 会被打进 Docker 的 stdout。留头 6 位和尾 4 位足够认出"用的是哪一把",
     * 又拼不回完整的密钥。
     */
    private static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "(未配置)";
        }
        if (key.length() <= 12) {
            return "***";
        }
        return key.substring(0, 6) + "***" + key.substring(key.length() - 4);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= BODY_SNIPPET ? text : text.substring(0, BODY_SNIPPET) + "…";
    }
}
