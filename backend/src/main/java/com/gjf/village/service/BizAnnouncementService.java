package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.AnnouncementDTO;
import com.gjf.village.dto.AnnouncementQueryDTO;
import com.gjf.village.vo.AnnouncementVO;

/**
 * 公告服务。
 *
 * <p><b>为什么公告和留言板的可见性口径不一样:</b>
 * <ul>
 *   <li><b>公告</b>是村级公务 —— 补贴名单、会议通知、安全须知。
 *       它默认只给本村人看,没登录的访客只匹配得上"全平台公告"
 *       ({@code village_id IS NULL} 那种)。</li>
 *   <li><b>留言板</b>是公共讨论区,默认对所有人开放阅读。</li>
 * </ul>
 * 两者的差别不来自"内容敏不敏感",而来自"它是发给谁的":
 * 公告有明确的送达对象,讨论区没有。
 */
public interface BizAnnouncementService {

    /**
     * 新建公告 —— 按 {@code asDraft} 决定直接发布还是先存成草稿。
     *
     * <p>发布即生效(直接落成「已发布」),存草稿则落在「草稿」态、
     * 不给任何人发通知。两条路都不需要额外的权限码:
     * 「存草稿」不是一种新的能力,它只是「发布公告」这个动作的一个分支 ——
     * 一个人如果能新建公告,他就有权决定这条公告现在发还是晚点发。
     *
     * @return 公告ID
     */
    Long publish(AnnouncementDTO dto);

    /**
     * 编辑公告内容。
     *
     * <p><b>只覆盖标题 / 正文 / 封面 / 类型 / 置顶这五个字段。</b>
     * {@code village_id}、{@code publisher_id}、{@code view_count}、
     * {@code status} 一律不动 —— 尤其是归属:它是发布时定下来的,
     * 允许编辑时改归属,一条村级公告就能被"搬"到别的村去,
     * 而"公告挂在哪"恰恰是可见性和数据级权限的锚点。
     *
     * <p><b>不会重新发通知。</b>编辑是"改错字",不是"又发了一遍" ——
     * 改一个标点就把全村人的通知栏再刷一遍,用户很快就会把通知关掉。
     * 只有真正从"没发过"变成"已发布"的动作({@link #publishDraft})才发通知。
     */
    void update(Long id, AnnouncementDTO dto);

    /**
     * 把草稿或已下架的公告发出去。
     *
     * <p>这是 {@code announcement:add}(「发布公告」)这个权限码覆盖的第二个动作:
     * 新建一条、和把一条已存在的发出去,在业务上是同一件事 ——
     * 让公告进入可见状态。单独再造一个"重新发布"的码,
     * 只会让权限表里多一条永远和 {@code announcement:add} 一起授予的记录。
     */
    void publishDraft(Long id);

    /**
     * 下架公告。
     *
     * <p>下架之后前台列表和详情都读不到(前台那条 SQL 把 {@code status = 1}
     * 硬编码在里面),但后台仍然查得到 —— 后台不带这个状态条件,
     * 就是为了让下架过的公告还能被找回来重新编辑。
     *
     * <p><b>下架不发通知。</b>已经推出去的通知收不回来,再补一条
     * "某公告已下架"只会让人困惑 —— 多数人根本还没看过那条公告。
     */
    void offline(Long id);

    // ── 前台(免登录) ──────────────────────────────────────────

    /** 公告列表:全平台公告 + 当前用户所属村的公告 */
    PageResult<AnnouncementVO> pageFront(AnnouncementQueryDTO query);

    /** 公告详情。会校验可见性,并通过后让浏览量 +1 */
    AnnouncementVO detailFront(Long id);

    // ── 后台 ────────────────────────────────────────────────────

    /** 后台公告列表(受数据级权限约束,可按状态和类型筛选) */
    PageResult<AnnouncementVO> pageAdmin(AnnouncementQueryDTO query);

    /**
     * 后台公告详情,<b>带正文</b>。
     *
     * <p>为什么不能拿前台的 {@link #detailFront} 顶替:那个接口只认
     * 「已发布」,草稿和下架的一律 404 —— 而后台点「编辑」时恰恰要读的
     * 就是草稿和下架的那条。编辑功能能不能做,取决于有没有这条接口。
     *
     * <p>与列表一样受数据级权限约束,但走的是"查到之后校验归属"这条路
     * (按 id 的查询用不上列表里的村组白名单)。
     */
    AnnouncementVO detailAdmin(Long id);
}
