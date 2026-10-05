package com.gjf.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 村组新增 / 修改。
 */
@Data
public class VillageDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 修改时传入 */
    private Long id;

    /** 上级行政区ID,null 表示顶级(乡镇) */
    private Long parentId;

    @NotBlank(message = "请输入村组名称")
    @Size(max = 50, message = "村组名称不能超过 50 个字符")
    private String villageName;

    /**
     * 村组编码。库里是 {@code NOT NULL UNIQUE VARCHAR(20)},所以这里同样必填 ——
     * 放宽成可选只会让空值溜到数据库,报一个 500 而不是能看懂的提示。
     */
    @NotBlank(message = "请输入村组编码")
    @Size(max = 20, message = "村组编码不能超过 20 个字符")
    private String villageCode;

    @Size(max = 50, message = "所属乡镇不能超过 50 个字符")
    private String townName;

    @Size(max = 12, message = "区划代码不能超过 12 个字符")
    private String regionCode;

    private Integer sortOrder;

    /** 状态:1启用 0停用 */
    private Integer status;
}
