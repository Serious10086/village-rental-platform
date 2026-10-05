package com.gjf.village.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发布公告。
 *
 * <p><b>{@code publisherId} 由服务端从登录态取,{@code villageId} 由服务端
 * 按发布人的数据范围决定</b>,两者都不接受前端传入。理由:
 * 允许前端传 villageId,村级管理员就能发一条挂着别村村名的公告;
 * 允许传 publisherId,公告的发布人就成了可以随便填的署名 ——
 * 而政策宣传类公告是要"找到是谁发的"的。
 */
@Data
public class AnnouncementDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "公告标题不能为空")
    @Size(max = 200, message = "标题最多 200 字")
    private String title;

    @NotBlank(message = "公告正文不能为空")
    private String content;

    @Size(max = 255, message = "封面地址过长")
    private String coverUrl;

    /** 1村级共享倡议 2安全使用须知 3农机补贴政策 4节庆活动 5系统公告 */
    @NotNull(message = "请选择公告类型")
    @Min(value = 1, message = "公告类型不合法")
    @Max(value = 5, message = "公告类型不合法")
    private Integer noticeType;

    /** 是否置顶:1是 0否 */
    private Integer isTop;

    /**
     * 是否存成草稿而不是直接发布。<b>只在"新建"时有意义,编辑不看它。</b>
     *
     * <p>不取名叫 {@code status},是因为它表达的是"这次操作做不做发布
     * 这个动作",而不是"这条公告的状态是什么" —— 后者由服务端的状态机决定:
     * 存草稿只可能落成 0(草稿),发布只可能落成 1(已发布),
     * 前端说不出第三种可能。字段名叫 status 的话,下一个读代码的人
     * 会以为可以传 2 把它直接下架。
     *
     * <p>为空按 false 处理(直接发布),这样既有的调用方不用改。
     */
    private Boolean asDraft;
}
