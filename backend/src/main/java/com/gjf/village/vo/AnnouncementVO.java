package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 公告。
 */
@Data
public class AnnouncementVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    /** 详情接口才返回正文;列表接口为 null,避免把富文本整页传到列表里 */
    private String content;

    private String coverUrl;

    private Integer noticeType;

    private Long publisherId;
    private String publisherName;

    /** NULL 表示全平台公告 */
    private Long villageId;
    private String villageName;

    private Integer isTop;

    private Integer viewCount;

    private Integer status;

    private LocalDateTime publishTime;

    private LocalDateTime createTime;
}
