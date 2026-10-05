package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.AdminBoardQueryDTO;
import com.gjf.village.dto.BoardPostDTO;
import com.gjf.village.dto.BoardQueryDTO;
import com.gjf.village.dto.BoardReplyDTO;
import com.gjf.village.vo.BoardItemVO;

/**
 * 留言板服务。
 *
 * <p><b>读与写是两套权限:</b>
 * <ul>
 *   <li><b>读</b> —— 公开。任何村的留言板都能看,没登录也能看
 *       (配置在 {@code app.security.whitelist} 里)。这和资源列表是同一个
 *       口径:平台做的是跨村交易,买家得能先看看邻村的人在聊什么。</li>
 *   <li><b>写</b> —— 只限本村。发帖、回复、点赞、删除都必须登录,
 *       且只能作用于自己所属村组的留言板。{@code village_id} 这个列
 *       就是为这条规则存在的。</li>
 * </ul>
 * 于是"看得到"和"说得上话"被分开了 —— 这是设计意图,不是漏洞。
 */
public interface BizBoardService {

    /**
     * 发主帖。
     *
     * <p><b>村组由服务端从登录态取</b>,不接受前端传 ——
     * 否则任何人改一下请求体就能跑到别村的留言板上发帖。
     *
     * @return 主帖ID
     */
    Long post(BoardPostDTO dto);

    /**
     * 回复主帖。
     *
     * @return 回复ID
     */
    Long reply(BoardReplyDTO dto);

    /**
     * 点赞 / 取消点赞(同一个接口来回切)。
     *
     * <p>做成 toggle 而不是点赞、取消两个接口:前端只有一个按钮,
     * 两个接口会让前端必须自己记住"当前是什么状态",而这个状态
     * 服务端本来就有。
     *
     * @return {@code true} = 现在处于已点赞状态,{@code false} = 已取消
     */
    boolean toggleLike(Long boardId);

    /**
     * 删除自己的留言。删主帖会连带删掉它下面的回复。
     */
    void delete(Long id);

    // ── 查询(公开) ────────────────────────────────────────────

    /** 主帖列表。{@code villageId} 不传时取当前登录用户的村组 */
    PageResult<BoardItemVO> pageRoot(BoardQueryDTO query);

    /** 某条主帖下的回复,按时间正序 */
    PageResult<BoardItemVO> pageReplies(Long parentId, long pageNum, long pageSize);

    // ── 后台 ────────────────────────────────────────────────────

    /** 后台留言巡查(受数据级权限约束,能看到已隐藏的、也能看到回复) */
    PageResult<BoardItemVO> pageAdmin(AdminBoardQueryDTO query);

    /**
     * 后台删除留言。
     *
     * <p>是逻辑删除,不是物理删除 —— 留言可能是纠纷处理时的证据,
     * 删掉之后数据库里仍然查得到,前台却看不到了。这正是
     * {@code biz_board.deleted} 这一列存在的意义。
     */
    void adminDelete(Long id);
}
