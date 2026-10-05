package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 后台 AI 会话记录的查询条件。
 *
 * <p><b>刻意没有 {@code villageId} 字段</b>,和 {@code AdminBoardQueryDTO} 是同一个理由:
 * 村组白名单由 {@code DataScopeHelper} 无条件覆盖 {@code villageIds},
 * 所以"前端传了别村 id"这条路径根本不存在。留一个 {@code villageId} 字段
 * 只会让人以为它能用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AiChatQueryDTO extends BaseQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键词,模糊匹配提问内容 */
    private String keyword;

    /** 状态:1成功 2降级 3失败。为空则全部 */
    private Integer status;

    /** 只看降级/失败的(排查用)。为 true 时把 1 排除掉 */
    private Boolean onlyProblem;
}
