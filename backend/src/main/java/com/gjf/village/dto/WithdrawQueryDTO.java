package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 提现单查询条件。
 *
 * <p>{@code userId} 这个字段在前后台的含义完全不同,这一点必须写清楚:
 * <ul>
 *   <li><b>前台</b>("我的提现记录"):由 Service 强制写成当前登录用户ID,
 *       忽略前端传入 —— 否则把 userId 换成别人的就能看到别人的提现记录,
 *       里面有收款账号</li>
 *   <li><b>后台</b>:作为筛选条件使用,同时叠加数据级权限
 *       ({@code villageIds}),村管只能在"本村 ∩ 指定用户"里查</li>
 * </ul>
 *
 * <p>同一个 DTO 承担两种语义是可以接受的,前提是两条路径都在 Service 里
 * 显式设置过这个字段,而不是让前端想传就传。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WithdrawQueryDTO extends BaseQueryDTO {

    /** 提现单号,精确匹配 */
    private String withdrawNo;

    /** 按申请人筛选(前台由 Service 强制写入) */
    private Long userId;

    /** 状态:0待审核 1已打款 2已驳回 */
    private Integer status;
}
