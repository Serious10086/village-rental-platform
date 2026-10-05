package com.gjf.village.vo;

import com.gjf.village.entity.ResourceListView;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * "我的发布"列表项。
 *
 * <p>继承 {@link ResourceListView} 而不是另起一个类:字段有 20 多个是重叠的,
 * 复制一份只会让两边逐渐不一致。这里只补上"发布者视角"才需要、而公开列表
 * 看不到的两个字段。
 *
 * <p>注意它的数据来源是 <b>biz_resource 基表</b>而不是 v_resource_list 视图 ——
 * 因为视图把 resource_status = 1 写死在定义里了,而发布者需要看到
 * 自己那些待审核、已下架、交易中的资源。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MyResourceVO extends ResourceListView {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 资源状态:0待审核 1已上架 2临时下架 3交易中 4已完成 5审核驳回 6违规下架 */
    private Integer resourceStatus;

    /** 下架/驳回原因 */
    private String offlineReason;

    /** 审核时间 */
    private java.time.LocalDateTime auditTime;
}
