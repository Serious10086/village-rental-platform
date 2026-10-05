package com.gjf.village.aspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gjf.village.annotation.Log;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.entity.SysOperationLog;
import com.gjf.village.mapper.SysOperationLogMapper;
import com.gjf.village.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 操作日志切面。
 *
 * <p>拦 {@code @Log} 注解的方法,把"谁、什么时候、对什么做了什么、成没成"记进
 * {@code sys_operation_log}。它是**审计**而不是**调试**:调试看日志文件,
 * 审计要能回答"三个月前那条资源是谁下架的"。
 *
 * <h3>四条必须守住的规矩</h3>
 *
 * <p><b>一、失败也要记。</b>只记成功的话,"有人反复尝试越权删除"这种事
 * 在日志里完全看不到 —— 而失败的尝试恰恰最该被看见。所以 {@code status}
 * 有两档,异常信息也存下来(只存类名和消息,不存堆栈:堆栈进审计表会把表撑爆)。
 *
 * <p><b>二、日志写不进去绝不能影响业务。</b>和 AI 行为采集同一条原则 ——
 * 旁路就该有旁路的失败姿态。这里把 insert 包在 try 里,失败只留一行 warn。
 *
 * <p><b>三、密码类字段必须脱敏。</b>{@code sys_operation_log.params} 那一列的
 * 建表注释里就写了这一条。登录、注册、改密、实名提交<b>都会经过这个切面</b>,
 * 不脱敏的话明文密码就躺在审计表里 —— 而审计表是"很多人能看"的表,
 * 这比密码泄露在别处更糟。
 *
 * <p><b>四、参数要截断。</b>有人往备注里粘一万字,日志表就多一条巨长的记录。
 * 审计要的是"能看懂发生了什么",不是"完整复刻请求体"。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    /** 参数列的截断长度。够看清"做了什么",又不至于把表撑大 */
    private static final int PARAMS_MAX = 1000;

    /** 异常信息的截断长度 */
    private static final int ERROR_MAX = 500;

    /**
     * 值要打码的字段名(小写比较)。
     *
     * <p>按字段名匹配而不是按类型或位置 —— 因为它要挡的是**内容**:
     * 不管这个字段出现在哪个 DTO 里、叫什么驼峰写法,只要名字里带这些词,
     * 值就不该进审计表。{@code idCard} 是身份证:实名审核那一条链路上
     * 它必然出现,而身份证号和密码一样不该留在"很多人能看"的表里。
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "oldpassword", "newpassword", "confirmpassword",
            "token", "idcard", "idcardno", "bankcard"
    );

    private static final String MASK = "***";

    private final SysOperationLogMapper logMapper;
    private final ObjectMapper objectMapper;

    @Around("@annotation(logAnno)")
    public Object around(ProceedingJoinPoint point, Log logAnno) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = point.proceed();
            save(point, logAnno, System.currentTimeMillis() - start, true, null);
            return result;
        } catch (Throwable e) {
            // 业务抛了异常也要先记一笔再往上抛 —— 顺序反了的话,
            // 异常交给全局处理器之后这里就没机会写了
            save(point, logAnno, System.currentTimeMillis() - start, false, e);
            throw e;
        }
    }

    private void save(ProceedingJoinPoint point, Log logAnno,
                      long costTime, boolean success, Throwable error) {
        try {
            SysOperationLog entity = new SysOperationLog();

            // 操作人取快照。没登录时(理论上写操作都要求登录)留空而不是报错 ——
            // 记不下"是谁"总比整条日志丢掉强
            LoginUser me = UserContext.getOrNull();
            if (me != null) {
                entity.setUserId(me.getUserId());
                entity.setUsername(displayNameOf(me));
            }
            entity.setModule(logAnno.module());
            entity.setOperation(logAnno.operation());
            entity.setMethod(describeMethod(point));

            HttpServletRequest request = currentRequest();
            if (request != null) {
                entity.setRequestUrl(request.getMethod() + " " + request.getRequestURI());
                entity.setIp(clientIp(request));
            }

            entity.setParams(describeArgs(point.getArgs()));
            entity.setStatus(success ? SysOperationLog.STATUS_SUCCESS
                    : SysOperationLog.STATUS_FAILED);
            entity.setErrorMsg(error == null ? null
                    : abbreviate(error.getClass().getSimpleName()
                                 + ": " + error.getMessage(), ERROR_MAX));
            entity.setCostTime(costTime);
            logMapper.insert(entity);

            if (!success) {
                log.warn("操作失败已记入日志: module={}, operation={}, 原因={}",
                        logAnno.module(), logAnno.operation(), entity.getErrorMsg());
            }
        } catch (Exception e) {
            // 旁路失败不影响主流程 —— 详见类注释第二条
            log.warn("操作日志写入失败(不影响业务): module={}, operation={}",
                    logAnno.module(), logAnno.operation(), e);
        }
    }

    /**
     * 请求参数的 JSON 描述。
     *
     * <p>先把不可序列化的参数剔掉:{@code HttpServletRequest}、
     * {@code MultipartFile} 这些要么序列化会炸,要么序列化出来是一堆没用的字节。
     * 文件上传的参数只留个"有文件"的事实即可 —— 审计想知道的是"谁传了什么",
     * 不是"那个 PDF 的每一个字节"。
     */
    private String describeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        List<Object> safe = new ArrayList<>(args.length);
        int fileCount = 0;
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            if (arg instanceof MultipartFile || arg instanceof MultipartFile[]) {
                fileCount++;
                continue;
            }
            if (arg instanceof HttpServletRequest || arg instanceof HttpServletResponse) {
                continue;
            }
            safe.add(arg);
        }
        if (fileCount > 0) {
            safe.add("(含 " + fileCount + " 个上传文件)");
        }
        if (safe.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.valueToTree(safe);
            maskSensitive(node);
            return abbreviate(node.toString(), PARAMS_MAX);
        } catch (Exception e) {
            // 序列化失败不能让日志整条丢掉 —— 记个说明比留一片空白强
            return "(参数无法序列化:" + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * 递归给敏感字段打码。
     *
     * <p>递归是必须的:参数可能是 {@code [dto]}、{@code dto.nested} 或者
     * 一个包装对象,敏感字段藏在哪一层都可能。只处理第一层的话,
     * 一个 {@code {"user": {"password": "..."}}} 就能绕过去。
     */
    private void maskSensitive(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> toMask = new ArrayList<>();
            Iterator<String> names = object.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (SENSITIVE_KEYS.contains(name.toLowerCase(Locale.ROOT))) {
                    toMask.add(name);
                }
            }
            toMask.forEach(name -> object.put(name, MASK));
            object.forEach(this::maskSensitive);
        } else if (node.isArray()) {
            node.forEach(this::maskSensitive);
        }
    }

    /**
     * 客户端 IP。
     *
     * <p>先看 {@code X-Forwarded-For} 和 {@code X-Real-IP} —— 批次 9 上了 Nginx
     * 之后,{@code getRemoteAddr()} 拿到的是**Nginx 容器的地址**(通常是
     * 172.x 的内网 IP),每个请求都一样,审计价值归零。Nginx 那边配了
     * {@code proxy_set_header} 把真实 IP 传过来,这里取的就是那个。
     *
     * <p>{@code X-Forwarded-For} 可能是"客户端, 代理1, 代理2"的形式,
     * 取第一个才是真正的客户端。
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private static String describeMethod(ProceedingJoinPoint point) {
        return point.getSignature().getDeclaringType().getSimpleName()
                + "." + point.getSignature().getName();
    }

    /**
     * 操作人的显示名。
     *
     * <p>和留言板用同一套规则:实名优先(村里认人),未实名退化成打码手机号。
     * 审计表里存明文手机号没必要 —— 能认出是谁就够。
     */
    private static String displayNameOf(LoginUser me) {
        if (me.getRealName() != null && !me.getRealName().isBlank()) {
            return me.getRealName();
        }
        String phone = me.getPhone();
        if (phone != null && phone.length() == 11) {
            return phone.substring(0, 3) + "****" + phone.substring(7);
        }
        return phone == null ? String.valueOf(me.getUserId()) : phone;
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
