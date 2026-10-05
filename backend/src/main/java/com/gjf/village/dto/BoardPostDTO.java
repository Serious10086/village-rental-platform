package com.gjf.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 发布留言(主帖)。
 *
 * <p><b>{@code userId} 和 {@code villageId} 都不在这里</b> —— 它们由服务端
 * 从登录态取。让前端传的话,发帖人可以伪造成别人发的,
 * 或者把帖子发到别的村去。
 */
@Data
public class BoardPostDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "留言内容不能为空")
    @Size(max = 1000, message = "留言最多 1000 字")
    private String content;

    /** 图片 URL,最多 9 张 */
    @Size(max = 9, message = "图片最多 9 张")
    private List<String> images;
}
