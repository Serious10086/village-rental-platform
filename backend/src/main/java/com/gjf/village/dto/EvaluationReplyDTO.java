package com.gjf.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 被评价人回复评价。
 *
 * <p>这是差评的申诉通道。没有它,被评价方只能被动挨打 ——
 * 一条"东西是坏的"的差评可能是买家自己操作不当造成的,
 * 但他没有任何地方能说明。有了回复,后来的租户能看到双方的说法,
 * 而不是只看一面之词。
 */
@Data
public class EvaluationReplyDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "回复内容不能为空")
    @Size(max = 500, message = "回复最多 500 字")
    private String replyContent;
}
