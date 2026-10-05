package com.gjf.village.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员处理纠纷。
 *
 * <p>只有两个可选结果:2 已调解 / 3 已关闭。这里的 {@code status} 用
 * {@code @Min/@Max} 卡在 2–3 之间,是因为 {@code BizDispute} 的状态是
 * 0 待受理 / 1 处理中 / 2 已调解 / 3 已关闭,而后两个只应该由这个接口写入 ——
 * 不卡的话,调用方可以传 0 把一条已经调解完的纠纷退回待受理。
 *
 * <p>这两个状态的业务区别:
 * <ul>
 *   <li><b>已调解</b> —— 管理员给出了结论,双方认可。订单会放回申请前的状态</li>
 *   <li><b>已关闭</b> —— 管理员查完发现不成立(如申请人自己搞错了),
 *       或双方私下和解撤诉。订单同样放回申请前的状态</li>
 * </ul>
 * 两者的区别只在语义和统计口径上,对订单的处理是一样的。
 *
 * <p><b>「退钱」这个动作刻意不在这里做。</b>要退钱说明这笔交易不该继续,
 * 管理员应当用已经做好的「强制取消订单」按钮 —— 它会走完整的
 * 状态校验、押金退还和流水记录。在纠纷处理里另写一套部分退款的逻辑,
 * 等于把同一件事实现两遍,两遍的口径早晚会不一致。
 */
@Data
public class DisputeHandleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "请选择处理结果")
    @Min(value = 2, message = "处理结果只能是「已调解」或「已关闭」")
    @Max(value = 3, message = "处理结果只能是「已调解」或「已关闭」")
    private Integer status;

    @NotBlank(message = "请填写调解结果")
    @Size(max = 1000, message = "调解结果最多 1000 字")
    private String handleResult;
}
