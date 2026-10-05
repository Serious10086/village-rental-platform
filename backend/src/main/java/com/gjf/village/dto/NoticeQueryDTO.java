package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 通知列表查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NoticeQueryDTO extends BaseQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 通知类型筛选,不传表示全部 */
    private Integer noticeType;

    /** 只看未读:1是;不传表示全部 */
    private Integer isRead;
}
