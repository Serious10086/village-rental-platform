package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.ResultCode;
import com.gjf.village.common.util.SensitiveWordUtil;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.AdminBoardQueryDTO;
import com.gjf.village.dto.BoardPostDTO;
import com.gjf.village.dto.BoardQueryDTO;
import com.gjf.village.dto.BoardReplyDTO;
import com.gjf.village.entity.BizBoard;
import com.gjf.village.entity.BizBoardLike;
import com.gjf.village.entity.MsgNotice;
import com.gjf.village.mapper.BizBoardLikeMapper;
import com.gjf.village.mapper.BizBoardMapper;
import com.gjf.village.security.LoginUser;
import com.gjf.village.service.BizBoardService;
import com.gjf.village.service.DataScopeHelper;
import com.gjf.village.service.MsgNoticeService;
import com.gjf.village.vo.BoardItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 留言板服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizBoardServiceImpl implements BizBoardService {

    private final BizBoardMapper boardMapper;
    private final BizBoardLikeMapper likeMapper;
    private final MsgNoticeService noticeService;
    private final DataScopeHelper dataScopeHelper;
    private final SensitiveWordUtil sensitiveWordUtil;

    /** 通知里的业务类型:留言(boardId)。沿用既有的约定 3=订单 4=提现 */
    private static final int NOTICE_BIZ_BOARD = 5;

    /** 回复接口的页大小上限 —— 不设上限的话,一条 pageSize=100000 的请求就能把整个村的数据拉走 */
    private static final long MAX_REPLY_PAGE_SIZE = 50;

    private static final int FILTERED_YES = 1;

    // ═══════════════════════════════════════════════════════════
    //  写:发帖 / 回复 / 点赞 / 删除
    // ═══════════════════════════════════════════════════════════

    /**
     * 发主帖。
     *
     * <p>三步:校验村组 → 敏感词 → 落库。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public Long post(BoardPostDTO dto) {
        LoginUser me = UserContext.get();
        Long villageId = requireMyVillage(me);

        int filtered = checkSensitive(dto);

        BizBoard board = new BizBoard();
        board.setParentId(null);              // NULL = 主帖
        board.setUserId(me.getUserId());
        board.setVillageId(villageId);        // 只认登录态,不认前端传的
        board.setContent(dto.getContent());
        board.setImages(join(dto.getImages()));
        board.setLikeCount(0);
        board.setReplyCount(0);
        board.setIsFiltered(filtered);
        board.setStatus(BizBoard.STATUS_NORMAL);
        boardMapper.insert(board);

        log.info("发布留言: id={}, userId={}, villageId={}, filtered={}",
                board.getId(), me.getUserId(), villageId, filtered != 0);
        return board.getId();
    }

    /**
     * 回复主帖。
     *
     * <p>这里做的校验比发帖多,因为回复是"挂在别人的东西下面"的操作:
     * 主帖必须存在、必须没被隐藏、必须和我在同一个村,
     * 而且 —— <b>被回复的必须是一条主帖,不能是另一条回复</b>。
     *
     * <p>最后一条是"层级压到两级"这个设计在代码里的落点。留言板的层级
     * 深度如果是任意的,列表渲染就得递归、删除要处理整棵子树、
     * 权限校验要顺着链路往上找。村级留言板用不到那么复杂的东西;
     * 真要追问某个人,正确做法是在回复里点名(村民之间本来就这么说话)。
     *
     * <p>读主帖用的是 {@code selectByIdForUpdate} 而不是 {@code selectById}:
     * 下面要插一条子行、再把主帖的 reply_count +1,这一对操作必须以
     * "先锁住主帖行"开头,否则并发回复同一条帖会死锁(原因见 Mapper 的注释)。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public Long reply(BoardReplyDTO dto) {
        LoginUser me = UserContext.get();
        BizBoard parent = boardMapper.selectByIdForUpdate(dto.getParentId());
        if (parent == null) {
            throw BusinessException.notFound("被回复的留言不存在或已删除");
        }
        if (Integer.valueOf(BizBoard.STATUS_HIDDEN).equals(parent.getStatus())) {
            throw BusinessException.badRequest("这条留言已被管理员隐藏,无法回复");
        }
        // 只接受主帖:parent_id 非空说明传进来的是"一条回复的 id"
        if (parent.getParentId() != null) {
            throw BusinessException.badRequest("只能回复主帖,不支持对回复再回复");
        }

        // 回复是"写"操作,和发帖一样限本村。读可以跨村,说话不行。
        Long myVillage = requireMyVillage(me);
        if (!Objects.equals(parent.getVillageId(), myVillage)) {
            throw BusinessException.forbidden("只能回复本村留言板上的留言");
        }

        int filtered = checkSensitive(dto);

        BizBoard reply = new BizBoard();
        reply.setParentId(parent.getId());
        // 村组跟着主帖走,而不是跟着"我的村"走 —— 两者在上面已经校验过相等,
        // 但显式取主帖的值能让"回复和主帖永远同村"这条不变式不依赖那次校验
        reply.setVillageId(parent.getVillageId());
        reply.setUserId(me.getUserId());
        reply.setContent(dto.getContent());
        reply.setImages(join(dto.getImages()));
        reply.setLikeCount(0);
        reply.setReplyCount(0);           // 回复下面不再有回复,这个计数恒为 0
        reply.setIsFiltered(filtered);
        reply.setStatus(BizBoard.STATUS_NORMAL);
        boardMapper.insert(reply);

        // 冗余计数在数据库端 +1,不是读出来加完写回去
        boardMapper.increaseReplyCount(parent.getId());

        notifyReply(parent, me, dto.getContent());

        log.info("回复留言: id={}, parentId={}, userId={}", reply.getId(), parent.getId(), me.getUserId());
        return reply.getId();
    }

    /**
     * 点赞 / 取消点赞。
     *
     * <p><b>用 DELETE 的结果来决定走哪条分支,而不是先 SELECT 再判断。</b>
     * 先查后写有两个问题:多一次往返;更重要的是"查到没有"和"插入"之间
     * 存在窗口,并发的两次点赞会同时判定为"还没点过"。
     *
     * <p>DELETE 的影响行数天然是个原子的判据:删掉了说明本来点过(这次是取消),
     * 一条没删说明本来没点过(这次是点赞)。剩下的唯一竞态是"两个请求
     * 同时点赞"——那时两条 INSERT 都通过判定,由
     * {@code uk_board_user} 唯一约束顶掉第二条。捕获这个异常并当作
     * "已经点过了"处理,用户看到的是正常结果而不是一个 500。
     *
     * <p><b>读主帖用的是 {@code selectByIdForUpdate} 而不是 {@code selectById}。</b>
     * 这个方法里有两处并发敏感的地方:DELETE + INSERT 那一对靠
     * {@code uk_board_user} 唯一约束兜底,不需要额外上锁;但"插完点赞行、
     * 回主帖把 like_count +1"这一对不行 —— 插子行时外键检查会先在主帖行上
     * 加一把共享锁,紧接着的计数自增要的却是排他锁,两个并发请求各自攥着
     * 共享锁等对方放手,就成了死锁(完整过程见 Mapper 里的注释)。
     *
     * <p>事务开头先对主帖行加排他锁,同一个帖子上的点赞就排起队了:
     * 后面的外键检查和计数自增要的锁都已经在自己手里,那一环形不成。
     * 连"同一个人连点几下"的间隙锁问题也顺带覆盖 —— 那条 DELETE 和
     * 后面的 INSERT 不再并发,就没有两条语句去争 {@code uk_board_user}
     * 上的同一个间隙了。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public boolean toggleLike(Long boardId) {
        LoginUser me = UserContext.get();
        BizBoard board = boardMapper.selectByIdForUpdate(boardId);
        if (board == null) {
            throw BusinessException.notFound("留言不存在或已删除");
        }
        if (Integer.valueOf(BizBoard.STATUS_HIDDEN).equals(board.getStatus())) {
            throw BusinessException.badRequest("这条留言已被管理员隐藏");
        }
        Long myVillage = requireMyVillage(me);
        if (!Objects.equals(board.getVillageId(), myVillage)) {
            throw BusinessException.forbidden("只能点赞本村留言板上的留言");
        }

        int removed = likeMapper.delete(new LambdaQueryWrapper<BizBoardLike>()
                .eq(BizBoardLike::getBoardId, boardId)
                .eq(BizBoardLike::getUserId, me.getUserId()));

        if (removed > 0) {
            boardMapper.decreaseLikeCount(boardId);
            return false;
        }

        BizBoardLike like = new BizBoardLike();
        like.setBoardId(boardId);
        like.setUserId(me.getUserId());
        try {
            likeMapper.insert(like);
        } catch (DuplicateKeyException e) {
            // 并发下另一个请求已经点上了。对他来说结果一样是"已点赞",
            // 所以不报错、也不再自增一次计数 —— 计数要跟记录数对得上。
            log.debug("并发重复点赞,已忽略: boardId={}, userId={}", boardId, me.getUserId());
            return true;
        }
        boardMapper.increaseLikeCount(boardId);
        return true;
    }

    /**
     * 删除自己的留言。
     *
     * <p>删主帖会连带删掉它下面的回复。不连带删的话,回复会变成
     * 一批"没有父母的孤儿"—— 它们仍带着 parent_id,但那个 id 查不到了,
     * 列表里既显示不出来也删不掉,变成数据库里的垃圾。
     *
     * <p>注意这是<b>逻辑删除</b>({@code deleted = 1}),不是物理删除:
     * 留言可能是纠纷处理的证据。前台看不到,后台和数据库里还在。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void delete(Long id) {
        LoginUser me = UserContext.get();
        BizBoard board = boardMapper.selectById(id);
        if (board == null) {
            throw BusinessException.notFound("留言不存在或已删除");
        }
        if (!Objects.equals(board.getUserId(), me.getUserId())) {
            throw BusinessException.forbidden("只能删除自己的留言");
        }
        softDelete(board);
    }

    /**
     * 后台删除留言。
     *
     * <p>比起"本人删除"多了数据级权限校验,少了"必须是作者"的限制。
     * 权限校验不能省 —— 只在列表查询里过滤、写接口不校验的话,
     * 村管改一下请求里的 ID 就能删掉别村的留言。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void adminDelete(Long id) {
        BizBoard board = boardMapper.selectById(id);
        if (board == null) {
            throw BusinessException.notFound("留言不存在或已删除");
        }
        LoginUser me = UserContext.get();
        if (!me.isSuperAdmin() && !dataScopeHelper.canAccessVillage(board.getVillageId())) {
            throw BusinessException.forbidden("只能管理本村的留言");
        }
        softDelete(board);
        log.info("后台删除留言: id={}, parentId={}, 操作人={}", id, board.getParentId(), me.getUserId());
    }

    // ═══════════════════════════════════════════════════════════
    //  查询
    // ═══════════════════════════════════════════════════════════

    /**
     * 主帖列表。
     *
     * <p>村组的确定顺序是「前端传的 → 当前用户的 → 报错」:
     * 前两者分别对应"想去邻村看看"(公开接口,可能压根没登录)和
     * "在自己村里逛"这两种正常场景。两个都拿不到才报错 ——
     * 一个没绑定村组的用户又不指定村组,列表里该显示什么是没有答案的。
     */
    @Override
    public PageResult<BoardItemVO> pageRoot(BoardQueryDTO query) {
        Long villageId = query.getVillageId();
        LoginUser me = UserContext.getOrNull();
        if (villageId == null && me != null) {
            villageId = me.getVillageId();
        }
        if (villageId == null) {
            throw BusinessException.badRequest("请先选择要浏览的村组");
        }
        query.setVillageId(villageId);

        Long meId = me == null ? null : me.getUserId();
        Page<BoardItemVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<BoardItemVO> result = boardMapper.selectRootPage(page, query, meId);
        maskAll(result.getRecords());
        return PageResult.of(result);
    }

    /**
     * 某条主帖下的回复。
     *
     * <p>先确认主帖本身可见,再返回它的回复 —— 否则一条被隐藏的主帖,
     * 只要知道它的 id 就能把下面的回复读出来。
     */
    @Override
    public PageResult<BoardItemVO> pageReplies(Long parentId, long pageNum, long pageSize) {
        BizBoard parent = boardMapper.selectById(parentId);
        if (parent == null || Integer.valueOf(BizBoard.STATUS_HIDDEN).equals(parent.getStatus())) {
            throw BusinessException.notFound("留言不存在或已隐藏");
        }

        long size = Math.min(Math.max(pageSize, 1L), MAX_REPLY_PAGE_SIZE);
        LoginUser me = UserContext.getOrNull();
        Long meId = me == null ? null : me.getUserId();

        Page<BoardItemVO> page = new Page<>(Math.max(pageNum, 1L), size);
        IPage<BoardItemVO> result = boardMapper.selectReplyPage(page, parentId, meId);
        maskAll(result.getRecords());
        return PageResult.of(result);
    }

    /**
     * 后台留言巡查。
     *
     * <p>村组白名单由 {@code DataScopeHelper} 无条件覆盖 —— 这是它存在的
     * 全部意义。{@code AdminBoardQueryDTO} 里刻意没有 villageId 字段,
     * 所以这里连"前端传了别村 id"这条路径都不存在。
     */
    @Override
    public PageResult<BoardItemVO> pageAdmin(AdminBoardQueryDTO query) {
        dataScopeHelper.apply(query);
        Page<BoardItemVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<BoardItemVO> result = boardMapper.selectAdminPage(page, query);
        maskAll(result.getRecords());
        return PageResult.of(result);
    }

    // ═══════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════

    /**
     * 取当前登录用户的村组,取不到就报错。
     *
     * <p>为什么不允许 village_id 为空:所有写操作都以它为前提。
     * 一个没绑定村组的账号发了帖,这条帖子挂在哪?没人管得着它,
     * 后台任何村的管理员都看不到它。与其留下这种数据,不如直接拒绝。
     */
    private Long requireMyVillage(LoginUser me) {
        if (me.getVillageId() == null) {
            throw BusinessException.badRequest("你的账号还没有绑定村组,暂时不能发布留言");
        }
        return me.getVillageId();
    }

    /**
     * 敏感词两级处理,返回 {@code is_filtered} 该写的值。
     *
     * <p>沿用聊天消息那套(而不是评价那套):
     * <ul>
     *   <li><b>拦截类</b>命中 → 整条拒绝,返回 {@code SENSITIVE_CONTENT},
     *       并把命中的词回给用户,让他知道改哪里。</li>
     *   <li><b>替换类</b>命中 → <b>库里存原文</b>,只把 is_filtered 标上;
     *       打码发生在读取的时候。</li>
     * </ul>
     * "存原文、读时打码"比"存打码后的文本"多一层考虑,但换来两件事:
     * 词库调整后老内容能跟着变;将来要做"违规内容复核"时,
     * 原文还在,不需要让用户重打一遍。<b>保留事实、控制展示。</b>
     */
    private int checkSensitive(BoardPostDTO dto) {
        if (sensitiveWordUtil.shouldBlock(dto.getContent())) {
            Set<String> hits = sensitiveWordUtil.findAll(dto.getContent());
            throw new BusinessException(ResultCode.SENSITIVE_CONTENT,
                    "留言包含违规词,请修改后重试:" + String.join("、", hits));
        }
        boolean filtered = !sensitiveWordUtil.filter(dto.getContent()).equals(dto.getContent());
        return filtered ? FILTERED_YES : 0;
    }

    /**
     * 读取时打码。
     *
     * <p>只有 {@code isFiltered = 1} 的才需要跑一遍 DFA —— 绝大多数留言
     * 没命中过敏感词,对它们来说这个方法是直接返回的。
     *
     * <p><b>作者本人看到的也是打码后的版本</b>,和聊天消息一致。
     * 让他看到原文会更"友好",但那意味着同一个字段对不同的人返回
     * 不同的内容,前端就得知道"我是作者",多一个可能出错的分支,
     * 换来的只是省一次改字。
     */
    private void maskAll(List<BoardItemVO> items) {
        for (BoardItemVO vo : items) {
            if (Integer.valueOf(FILTERED_YES).equals(vo.getIsFiltered())) {
                vo.setContent(sensitiveWordUtil.filter(vo.getContent()));
            }
        }
    }

    /**
     * 软删除:主帖连带删回复,回复则把父帖的回复数减一。
     *
     * <p>两个分支的差别在于"谁需要维护冗余计数"——主帖的 reply_count
     * 记录的是它的回复条数,删掉一条回复就得减;而删主帖时,
     * 它自己连同回复一起消失了,没有任何人需要更新计数。
     *
     * <p>两个分支的上锁顺序是同一套:<b>先主帖行,后子行</b>。删主帖天然
     * 就是父在子前;删回复则是先补一把父帖的行锁再删自己。保持每个事务都
     * 按这个顺序拿锁,才不会出现"A 拿着子行等父行、B 拿着父行等子行"的环。
     */
    private void softDelete(BizBoard board) {
        if (board.getParentId() == null) {
            boardMapper.deleteById(board.getId());
            int replies = boardMapper.delete(new LambdaQueryWrapper<BizBoard>()
                    .eq(BizBoard::getParentId, board.getId()));
            log.info("删除主帖: id={}, 连带删除回复 {} 条", board.getId(), replies);
        } else {
            boardMapper.selectByIdForUpdate(board.getParentId());
            boardMapper.deleteById(board.getId());
            boardMapper.decreaseReplyCount(board.getParentId());
        }
    }

    /**
     * 通知被回复的人。
     *
     * <p>自己回复自己不通知 —— 那是一句自言自语,推一条消息只会让人觉得系统很吵。
     *
     * <p>正文里<b>不写回复人是谁</b>,只给回复内容。想写的话就得在这里
     * 再实现一遍"实名优先、未实名退化成打码手机号"的取名规则 ——
     * 而这条规则已经写在 {@code BizBoardMapper.xml} 里了。
     * 同一条展示规则在两个地方各写一遍,早晚会不一致;
     * 让他点进留言板看,那里自然会用同一套规则把人名显示出来。
     *
     * <p><b>点赞刻意不发通知。</b>点赞是弱互动,一条热闹的留言一晚上能被点几十下,
     * 每下都推一条消息,用户很快就会把通知关掉 —— 那样真正重要的
     * 订单、纠纷通知也一起被无视了。通知的额度要留给需要对方采取行动的事。
     */
    private void notifyReply(BizBoard parent, LoginUser me, String content) {
        if (Objects.equals(parent.getUserId(), me.getUserId())) {
            return;
        }
        noticeService.send(parent.getUserId(), MsgNotice.TYPE_BOARD,
                "有人回复了你的留言",
                "你的留言收到一条新回复:" + abbreviate(sensitiveWordUtil.filter(content)),
                NOTICE_BIZ_BOARD, parent.getId());
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    /** List → 逗号分隔字符串(与 biz_board.images 的存储格式对应) */
    private String join(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
