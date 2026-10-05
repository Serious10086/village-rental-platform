package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 某一种降级原因出现了多少次。
 *
 * <p><b>刻意做成独立的类而不是 {@code AiDegradeStatVO} 的嵌套 record</b>:
 * MyBatis 映射 record 要走"按构造器参数名匹配",而参数名的保留依赖编译期
 * 的 {@code -parameters},一旦哪次构建没带上,映射会静默给出 null 而不是报错。
 * 一个只有两个字段的展示对象,用默认构造器 + setter 换掉这份不确定性是划算的。
 */
@Data
public class AiDegradeReasonVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 降级原因,取自 {@code AiFailure.reason()},例:超时 / 限流429 / 鉴权失败401 */
    private String reason;

    /** 出现次数 */
    private Long count;
}
