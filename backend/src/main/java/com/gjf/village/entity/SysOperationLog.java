package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台操作日志。
 *
 * <p><b>为什么 {@code username} 要冗余一份,而不是只存 user_id 联表查。</b>
 * 日志是审计数据,它的第一要求是"多年以后还能读懂"。而用户表里的用户名和
 * 手机号是会变的 —— 一个人改了手机号之后,拿 user_id 联出来的名字就和
 * 当时不一样了,那条日志就再也说不清"当时是谁操作的"。
 * 所以这里存的是**快照**:操作发生的那一刻,这个人叫什么。
 * 这和订单里的 {@code unit_price} 存快照是同一个道理。
 *
 * <p><b>失败也要记。</b>{@code status} 有两档(1成功 0失败)。只记成功的话,
 * "有人反复尝试越权删除"这种事在日志里完全看不出来 —— 而那些失败的尝试
 * 恰恰是最该被看到的。
 *
 * <p><b>没有 {@code deleted} 列,也不该有。</b>和资金流水、AI 对话记录一样,
 * 审计数据是只增不改的。提供"删除日志"的入口,等于给审计留了后门。
 */
@Data
@TableName("sys_operation_log")
public class SysOperationLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态:成功 */
    public static final int STATUS_SUCCESS = 1;
    /** 状态:失败 */
    public static final int STATUS_FAILED = 0;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 操作人。理论上可能为空(未登录就触发的操作),但写操作都要登录,所以实际上是有的 */
    private Long userId;

    /** 操作人用户名(快照,见类注释) */
    private String username;

    private String module;

    private String operation;

    /** 类方法名,形如 {@code AdminResourceController.audit} */
    private String method;

    private String requestUrl;

    /** 请求参数(JSON,已脱敏并截断) */
    private String params;

    private String ip;

    /** 见上面的 STATUS_* */
    private Integer status;

    /** 异常信息,成功时为 NULL。只存异常的消息与类名,不存整个堆栈 —— 堆栈进日志表会把表撑爆 */
    private String errorMsg;

    /** 耗时(毫秒) */
    private Long costTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
