package com.gjf.village.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 提交评价请求。
 *
 * <p><b>{@code orderId} 是唯一的必填定位字段</b> —— 评价人、被评价人、
 * 资源、村组全部由服务端从订单里推出来,不接受前端传入。
 * 否则谁都能伪造一条"我是卖家,评价买家"的记录来刷分。
 */
@Data
public class EvaluationCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "缺少订单ID")
    private Long orderId;

    /** 综合评分 1-5,必填 */
    @NotNull(message = "请给出综合评分")
    @Min(value = 1, message = "评分最低 1 分")
    @Max(value = 5, message = "评分最高 5 分")
    private Integer score;

    /** 资源相符度 1-5,选填。这项参与"资源完好率"计算 */
    @Min(value = 1, message = "评分最低 1 分")
    @Max(value = 5, message = "评分最高 5 分")
    private Integer resourceScore;

    /** 服务态度 1-5,选填 */
    @Min(value = 1, message = "评分最低 1 分")
    @Max(value = 5, message = "评分最高 5 分")
    private Integer serviceScore;

    @Size(max = 500, message = "评价内容最多 500 字")
    private String content;

    /** 评价图片 URL 列表 */
    private List<String> images;

    /** 是否匿名展示 */
    private Boolean anonymous;
}
