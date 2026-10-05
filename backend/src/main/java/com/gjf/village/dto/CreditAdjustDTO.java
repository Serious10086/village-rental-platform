package com.gjf.village.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员手动调整信用分。
 *
 * <p>自动规则覆盖不到的情形全落在这里:线下调解达成一致、经查实的恶意行为、
 * 新用户初始补分等。也正因为是人工兜底,<b>原因必须填</b>且会写进流水 ——
 * 用户问"我这 10 分为什么被扣",管理员得能答上来。
 */
@Data
public class CreditAdjustDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "缺少用户ID")
    private Long userId;

    /** 变更值,正加负减。不允许为 0 —— 那只是一条无意义的流水 */
    @NotNull(message = "请填写调整分值")
    private Integer delta;

    @Size(max = 200, message = "原因最多 200 字")
    private String reason;
}
