package com.gjf.village.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 资源发布 / 编辑请求。
 *
 * <p>出租和出售共用这一个 DTO,靠 {@code tradeType} 区分。
 * 两类必填字段不同,用自定义校验方法在 Service 里做条件校验 ——
 * 用注解做"根据另一个字段决定是否必填"会很别扭。
 */
@Data
public class ResourcePublishDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 编辑时传入,新增时为空 */
    private Long id;

    @NotBlank(message = "请填写资源名称")
    @Size(max = 100, message = "资源名称不能超过 100 个字")
    private String resourceName;

    @NotNull(message = "请选择资源分类")
    private Long categoryId;

    @NotNull(message = "请选择交易方式")
    @Min(value = 1, message = "交易方式不合法")
    @Max(value = 2, message = "交易方式不合法")
    private Integer tradeType;

    /** 封面图 URL */
    private String coverUrl;

    /** 多图 URL 列表 */
    private List<String> images;

    /** 标签,前端传数组,存库时拼成逗号分隔 */
    private List<String> tags;

    @Size(max = 2000, message = "描述不能超过 2000 个字")
    private String description;

    // ── 出租专用 ────────────────────────────────────────────

    @NotNull(message = "请填写价格")
    @DecimalMin(value = "0.01", message = "价格必须大于 0")
    @DecimalMax(value = "9999999.99", message = "价格超出合理范围")
    private BigDecimal price;

    /** 计价单位:1元/天 2元/次 3元/件 */
    private Integer priceUnit;

    /** 押金,可为 0 */
    @DecimalMin(value = "0", message = "押金不能为负数")
    private BigDecimal deposit;

    /** 可租起始日 */
    private LocalDate rentStartDate;

    /** 可租截止日 */
    private LocalDate rentEndDate;

    /** 可租星期,如 [1,2,3,4,5](1=周一) */
    private List<Integer> rentableWeekdays;

    @Size(max = 1000, message = "使用规则不能超过 1000 个字")
    private String useRule;

    // ── 出售专用 ────────────────────────────────────────────

    /** 新旧程度:1全新 2九成新 3七成新 4五成新及以下 */
    private Integer oldDegree;

    /** 交货方式:1自提 2协商 */
    private Integer deliveryType;

    // ── 公共 ────────────────────────────────────────────────

    @NotBlank(message = "请填写资源位置")
    @Size(max = 200, message = "位置描述不能超过 200 个字")
    private String location;

    private BigDecimal longitude;
    private BigDecimal latitude;
}
