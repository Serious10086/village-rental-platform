package com.gjf.village.dto;

import lombok.Data;

import java.io.Serial;

/**
 * 前台留言板查询。
 *
 * <p><b>为什么后台另有一个 {@code AdminBoardQueryDTO},而不是共用这一个:</b>
 * {@code villageId} 在这里是"我要看哪个村的留言板"(用户可以主动切村浏览),
 * 而到了后台,同一件事变成了"数据级权限允许我看哪些村" ——
 * 后者由 {@code DataScopeHelper} 无条件覆盖,前端传来的值必须被丢掉。
 *
 * <p>把两者塞进一个类,Service 里就会长出一句
 * "后台调用时记得忽略 villageId" 的注释 —— 靠注释维持的约定迟早会失效,
 * 而且失效的表现是越权(村管传了别村的 id)。分成两个类,
 * 编译期就不存在这条路径。
 */
@Data
public class BoardQueryDTO extends BaseQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 要浏览的村组。不传则默认当前登录用户所在的村。
     *
     * <p>留言板按村组织,所以"看哪个村"必须显式确定。允许跨村浏览是
     * 有意保留的:本村没有会修水泵的人时,去邻村问一句是真实需求。
     */
    private Long villageId;

    /** 只看某个人的留言(点作者名进入) */
    private Long userId;

    /** 正文关键词 */
    private String keyword;
}
