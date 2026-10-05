package com.gjf.village.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 审核操作请求。
 *
 * <p>实名认证、资源发布和外来用户备案共用这个 DTO —— 几条审核流的操作字段完全一样
 * (审核哪一条、通过还是驳回、驳回原因),靠 bizType 区分。
 */
@Data
public class AuditDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务类型:1实名认证 2资源发布 3外来用户备案。
     *
     * <p><b>由服务端按端点强制写入</b> —— 见三个 Controller 里的
     * {@code dto.setBizType(...)}。请求体里传不传、传什么,最后都会被覆盖。
     *
     * <p>所以这里<b>刻意不加 {@code @NotNull}</b>。加了的话 {@code @Valid}
     * 会在控制器方法体<b>之前</b>执行:那句强制写入还没机会跑,校验就先失败了,
     * 客户端必须先填一个马上被丢弃的值,否则拿到的是"缺少业务类型" ——
     * 一句对这个接口毫无意义的报错。
     *
     * <p>服务端拿它去核对审核记录的类型是否一致 —— 防止把实名审核的通过
     * 误作用到资源上。
     */
    private Integer bizType;

    /** 审核记录ID(不是业务ID)*/
    @NotNull(message = "缺少审核记录ID")
    private Long auditId;

    /** 是否通过 */
    @NotNull(message = "请选择审核结果")
    private Boolean approved;

    /**
     * 审核意见。
     *
     * <p>驳回时必填 —— 不说明原因,发布者不知道该改什么,
     * 只能反复提交同一份内容,审核变成拉锯。这个校验在 Service 里做。
     *
     * <p>长度上限对齐 `sys_audit_record.audit_remark` 的 VARCHAR(500):
     * 不拦的话会一路插到数据库才报错,而那条错误被全局异常处理器
     * 归一成"该数据还被其他记录引用…",对"备注太长"来说是句假话。
     */
    @Size(max = 500, message = "审核意见不能超过 500 个字")
    private String remark;
}
