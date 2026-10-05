package com.gjf.village.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 回复留言。
 *
 * <p>继承 {@link BoardPostDTO} 而不是重写一遍 —— 回复和主帖的校验规则
 * (内容非空、1000 字、图片最多 9 张)完全一样,复制一份的话,
 * 哪天字数上限调整就会只改一处。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BoardReplyDTO extends BoardPostDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 被回复的主帖ID。
     *
     * <p>服务端只接受主帖 id —— 传一条回复的 id 进来会被拒。
     * 这是"层级压到两级"这条设计在接口上的落地:如果允许回复的回复,
     * 就要允许任意层级,渲染时得递归展开。
     */
    @NotNull(message = "缺少被回复的留言ID")
    private Long parentId;
}
