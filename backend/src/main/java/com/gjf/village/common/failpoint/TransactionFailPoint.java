package com.gjf.village.common.failpoint;

import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 事务失败点(故障注入)。
 *
 * <p><b>它解决的测试问题:</b>「下单事务在中途失败时,前面已经写进去的数据
 * 会不会残留」这条断言,正常情况下测不了 —— 因为代码是正确的时候
 * 根本不会失败,而想让它失败又只能去改代码。改代码再改回来,
 * 测的就不是发布的那份代码了。
 *
 * <p>所以做法是:在事务里埋一个「失败点」,平时它什么都不做;
 * 由超管调用接口把它<b>一次性</b>触发,下一个走到该点的请求就会抛异常。
 * 这是软件测试里标准的<b>故障注入(fault injection)</b>手法,
 * 目的是把「异常路径」变成可重复执行的用例。
 *
 * <p><b>三个安全设计:</b>
 * <ol>
 *   <li><b>默认关闭</b> —— 没有任何人被触发过时,它是一条空判断,
 *       对正常业务零影响</li>
 *   <li><b>一次性</b> —— 触发后自动清除({@code getAndSet(null)}),
 *       不会因为忘了关而让后续请求持续失败</li>
 *   <li><b>仅超管可触发</b> —— 接口层校验 {@code isSuperAdmin()},
 *       普通用户无法把系统切成"一用就报错"的状态</li>
 * </ol>
 *
 * <p>即便被恶意打开,后果也只是"某些接口暂时报错",不会造成数据损坏 ——
 * 异常是让事务回滚,而不是让事务写一半。
 */
@Slf4j
@Component
public class TransactionFailPoint {

    /** 下单事务的失败点标识 */
    public static final String CREATE_ORDER = "createOrder";

    /**
     * 已装载的失败点。<b>null 表示没有装载</b>,这是常态。
     *
     * <p>用 {@code getAndSet(null)} 而不是先读后写,是因为
     * 并发下两个请求可能同时读到同一个值、双双抛异常。
     * 原子替换保证「装载一次只影响一个请求」。
     */
    private final AtomicReference<String> armed = new AtomicReference<>();

    /** 装载失败点:下一个走到该点的请求将抛异常 */
    public void arm(String point) {
        armed.set(point);
        log.warn("【故障注入】已装载失败点: {},下一个经过该点的请求将触发事务回滚", point);
    }

    /** 清除装载 */
    public void clear() {
        armed.set(null);
    }

    /** 当前装载的失败点,未装载返回 null */
    public String armedPoint() {
        return armed.get();
    }

    /**
     * 经过失败点。若此前装载的正是这个点,则抛出异常并自动解除装载。
     *
     * <p>抛 {@link BusinessException} 而不是 {@code Error}:
     * 它是 RuntimeException 的子类,Spring 事务会照常回滚,
     * 全局异常处理器也会把它翻译成正常的 JSON 响应而不是 500 页面。
     */
    public void hit(String point) {
        if (point.equals(armed.getAndSet(null))) {
            log.warn("【故障注入】失败点 {} 已触发,当前事务将整体回滚", point);
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "【故障注入】事务在执行到「" + point + "」之后人为失败,用于验证回滚");
        }
    }
}
