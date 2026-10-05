package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 敏感词表。
 */
@Data
@TableName("sys_sensitive_word")
public class SysSensitiveWord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 处理动作:替换为星号 */
    public static final int ACTION_REPLACE = 1;

    /** 处理动作:直接拦截 */
    public static final int ACTION_BLOCK = 2;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String word;
    private String category;

    /** 处理方式:1替换为* 2直接拦截 */
    private Integer action;

    /** 状态:1启用 0停用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
