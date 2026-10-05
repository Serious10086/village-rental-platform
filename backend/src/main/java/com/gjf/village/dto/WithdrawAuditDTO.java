package com.gjf.village.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 提现审核。
 *
 * <p>用 {@code pass} 布尔而不是 {@code status} 整数,是要让接口的语义
 * 无法被误用:传 status 的话,调用方可以传 0(待审核)或 1(已打款)甚至
 * 别的数,而"审核"这个动作只可能有两个结果。收窄成布尔之后,
 * "审核成待审核"这种自相矛盾的状态在类型层面就不存在。
 *
 * <p>驳回时 {@code remark} 实际上是必填的,但校验放在 Service 层 ——
 * 因为"打款时备注选填、驳回时备注必填"是业务规则,不是字段格式规则,
 * 写在注解里没法表达这个条件依赖。
 */
@Data
public class WithdrawAuditDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** true 打款通过,false 驳回 */
    @NotNull(message = "请选择审核结果")
    private Boolean pass;

    @Size(max = 255, message = "审核意见最多 255 字")
    private String remark;
}
