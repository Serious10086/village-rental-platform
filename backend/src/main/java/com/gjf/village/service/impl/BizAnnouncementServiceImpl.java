package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.ResultCode;
import com.gjf.village.common.util.SensitiveWordUtil;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.AnnouncementDTO;
import com.gjf.village.dto.AnnouncementQueryDTO;
import com.gjf.village.entity.BizAnnouncement;
import com.gjf.village.entity.MsgNotice;
import com.gjf.village.entity.SysUser;
import com.gjf.village.mapper.BizAnnouncementMapper;
import com.gjf.village.mapper.SysUserMapper;
import com.gjf.village.security.LoginUser;
import com.gjf.village.service.BizAnnouncementService;
import com.gjf.village.service.DataScopeHelper;
import com.gjf.village.service.MsgNoticeService;
import com.gjf.village.vo.AnnouncementVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 公告服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizAnnouncementServiceImpl implements BizAnnouncementService {

    private final BizAnnouncementMapper announcementMapper;
    private final SysUserMapper userMapper;
    private final MsgNoticeService noticeService;
    private final DataScopeHelper dataScopeHelper;
    private final SensitiveWordUtil sensitiveWordUtil;

    /** 通知里的业务类型:公告(announcementId) */
    private static final int NOTICE_BIZ_ANNOUNCEMENT = 6;

    /** sys_user.account_status:1 正常 */
    private static final int ACCOUNT_STATUS_NORMAL = 1;

    /** 通知正文的摘要长度 */
    private static final int SNIPPET_LENGTH = 40;

    // ═══════════════════════════════════════════════════════════
    //  新建:发布 / 存草稿
    // ═══════════════════════════════════════════════════════════

    /**
     * 新建公告 —— 直接发布,或者先存成草稿。
     *
     * <p>三个关键判断,每一个都对应一种"不该发生但很容易写出来"的情况:
     * <ol>
     *   <li><b>谁能发系统公告</b> —— 「系统公告」代表平台而非某个村。
     *       不拦的话,村管可以发一条落款像平台的公告通知全村,这是冒用。
     *       注意这一条<b>和存不存草稿无关</b>:草稿也是"写下来的东西",
     *       而且草稿是要被发布的,不能让它先绕过检查、再在发布那一步
     *       变成一个没有归属的怪东西。</li>
     *   <li><b>公告挂在哪</b> —— 由服务端从登录态推,前端传什么都不看。</li>
     *   <li><b>通知发给谁</b> —— 跟着公告的村组走,和可见性口径保持一致:
     *       在页面上看得到的人,才应该收到那条消息。
     *       <b>存草稿不发通知</b> —— 草稿还没打算给人看,
     *       发通知等于把没写完的东西喊出去了。</li>
     * </ol>
     *
     * <p>草稿和已发布在库里只差两列:{@code status} 和 {@code publish_time}。
     * 后者留空而不是盖一个时间戳 —— "什么时候写的"是 {@code create_time}
     * 管的事,{@code publish_time} 记的是"什么时候发出去的",
     * 一条没发过的公告在这一列上不该有值。列表按 {@code publish_time DESC}
     * 排序时草稿会沉到底,正是想要的顺序。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public Long publish(AnnouncementDTO dto) {
        LoginUser me = UserContext.get();
        requireSystemNoticePermission(me, dto.getNoticeType());

        Long villageId = resolvePublishVillage(me);
        checkSensitive(dto);

        boolean asDraft = Boolean.TRUE.equals(dto.getAsDraft());

        BizAnnouncement announcement = new BizAnnouncement();
        announcement.setTitle(dto.getTitle());
        announcement.setContent(dto.getContent());
        announcement.setCoverUrl(dto.getCoverUrl());
        announcement.setNoticeType(dto.getNoticeType());
        announcement.setPublisherId(me.getUserId());   // 署名不接受前端传入
        announcement.setVillageId(villageId);
        announcement.setIsTop(Integer.valueOf(1).equals(dto.getIsTop()) ? 1 : 0);
        announcement.setViewCount(0);
        announcement.setStatus(asDraft
                ? BizAnnouncement.STATUS_DRAFT
                : BizAnnouncement.STATUS_PUBLISHED);
        announcement.setPublishTime(asDraft ? null : LocalDateTime.now());
        announcementMapper.insert(announcement);

        // 存草稿不该惊动任何人
        if (!asDraft) {
            notifyMembers(announcement);
        }

        log.info("新建公告: id={}, 发布人={}, villageId={}, type={}, asDraft={}, title={}",
                announcement.getId(), me.getUserId(), villageId,
                dto.getNoticeType(), asDraft, dto.getTitle());
        return announcement.getId();
    }

    /**
     * 编辑公告内容。
     *
     * <p>用 {@code LambdaUpdateWrapper} 逐列 {@code set},而不是
     * {@code updateById(实体)}:后者对 null 字段是"跳过不更新"
     * (MyBatis-Plus 默认的字段策略),于是把封面图移除之后
     * {@code coverUrl = null} 传下来,那一列纹丝不动 —— 界面上图没了,
     * 库里还在,刷新一下又回来。这类"只有把值改成空的场景才会复现"的坑,
     * 靠的是把要写的列写全,而不是靠默认策略。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void update(Long id, AnnouncementDTO dto) {
        LoginUser me = UserContext.get();
        // 这一句的返回值用不上,它是**为了那次归属校验**才调的 ——
        // 少了它,这条按 id 的写接口就绕过了列表里那条村组白名单
        loadManageable(id);
        requireSystemNoticePermission(me, dto.getNoticeType());
        checkSensitive(dto);

        announcementMapper.update(null, new LambdaUpdateWrapper<BizAnnouncement>()
                .eq(BizAnnouncement::getId, id)
                .set(BizAnnouncement::getTitle, dto.getTitle())
                .set(BizAnnouncement::getContent, dto.getContent())
                .set(BizAnnouncement::getCoverUrl, dto.getCoverUrl())
                .set(BizAnnouncement::getNoticeType, dto.getNoticeType())
                .set(BizAnnouncement::getIsTop, Integer.valueOf(1).equals(dto.getIsTop()) ? 1 : 0));

        log.info("编辑公告: id={}, 操作人={}, title={}", id, me.getUserId(), dto.getTitle());
    }

    /**
     * 把草稿或已下架的公告发出去。
     *
     * <p>会重新盖一次 {@code publishTime} 并重发通知 —— 一条下架半年后被
     * 重新发布的公告,盖着半年前的时间戳排在列表中间,没人会注意到它。
     *
     * <p>通知因此可能重复:下架 → 重新发布这一轮,村民会收到第二条。
     * 这是想清楚之后的选择 —— <b>"公告重新可见了"这件事本身就值得再推一次</b>,
     * 而沉默地让它复活,村民会以为那条公告一直是可见的。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void publishDraft(Long id) {
        LoginUser me = UserContext.get();
        BizAnnouncement announcement = loadManageable(id);

        if (Integer.valueOf(BizAnnouncement.STATUS_PUBLISHED).equals(announcement.getStatus())) {
            throw BusinessException.badRequest("这条公告已经是发布状态");
        }

        announcementMapper.update(null, new LambdaUpdateWrapper<BizAnnouncement>()
                .eq(BizAnnouncement::getId, id)
                .set(BizAnnouncement::getStatus, BizAnnouncement.STATUS_PUBLISHED)
                .set(BizAnnouncement::getPublishTime, LocalDateTime.now()));

        notifyMembers(announcement);

        log.info("发布已存在的公告: id={}, 操作人={}, 原标题={}",
                id, me.getUserId(), announcement.getTitle());
    }

    /**
     * 下架公告。
     *
     * <p>{@code publish_time} 保留不动 —— 它记的是"这条公告曾经什么时候发过",
     * 下架不改变这个事实,清掉反而丢了一段可以追溯的信息。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void offline(Long id) {
        LoginUser me = UserContext.get();
        BizAnnouncement announcement = loadManageable(id);

        if (!Integer.valueOf(BizAnnouncement.STATUS_PUBLISHED).equals(announcement.getStatus())) {
            throw BusinessException.badRequest("只有已发布的公告才能下架");
        }

        announcementMapper.update(null, new LambdaUpdateWrapper<BizAnnouncement>()
                .eq(BizAnnouncement::getId, id)
                .set(BizAnnouncement::getStatus, BizAnnouncement.STATUS_OFFLINE));

        log.info("下架公告: id={}, 操作人={}, title={}", id, me.getUserId(), announcement.getTitle());
    }

    // ═══════════════════════════════════════════════════════════
    //  前台
    // ═══════════════════════════════════════════════════════════

    /**
     * 公告列表。
     *
     * <p>把 {@code includeGlobal} 打开、{@code villageId} 设成当前用户的村组,
     * 于是没登录的访客拿到的是 {@code villageId = null},
     * SQL 里那条 {@code village_id IS NULL OR village_id = null} 退化成
     * 只匹配全平台公告 —— 这正好是访客应得的可见范围,
     * 不需要为"未登录"单独写一条分支。
     *
     * <p>{@code villageIds} 被清空,是因为它是后台那条口径的字段。
     * 清掉它,前台就不可能意外走到后台的分支上。
     */
    @Override
    public PageResult<AnnouncementVO> pageFront(AnnouncementQueryDTO query) {
        LoginUser me = UserContext.getOrNull();
        query.setIncludeGlobal(true);
        query.setVillageId(me == null ? null : me.getVillageId());
        query.setVillageIds(null);
        query.setStatus(null);            // 状态由 SQL 硬编码,传了也不看
        return page(query);
    }

    /**
     * 公告详情。
     *
     * <p><b>无权查看时抛的是 404 而不是 403。</b>403 等于告诉对方
     * "这条公告确实存在,只是你看不了" —— 拿它当探针遍历 id,
     * 就能数出别村发了多少条公告。404 什么都不透露,
     * 对调用方来说"不存在"和"不该你看"是同一件事。
     *
     * <p>浏览量在<b>校验通过之后</b>才自增。顺序反过来的话,
     * 每次失败的越权尝试都会给别人的公告刷一个浏览量。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public AnnouncementVO detailFront(Long id) {
        AnnouncementVO vo = announcementMapper.selectDetail(id);
        if (vo == null || !Integer.valueOf(BizAnnouncement.STATUS_PUBLISHED).equals(vo.getStatus())) {
            throw BusinessException.notFound("公告不存在或已下架");
        }
        if (vo.getVillageId() != null) {
            LoginUser me = UserContext.getOrNull();
            Long myVillage = me == null ? null : me.getVillageId();
            if (!vo.getVillageId().equals(myVillage)) {
                throw BusinessException.notFound("公告不存在或已下架");
            }
        }

        announcementMapper.increaseViewCount(id);
        // 返回自增后的值,否则用户看到的是自己刷新前的数字 —— 差一位很小,
        // 但"刷新一次它才涨"这种观感会让人觉得统计坏了
        vo.setViewCount(vo.getViewCount() == null ? 1 : vo.getViewCount() + 1);
        return vo;
    }

    // ═══════════════════════════════════════════════════════════
    //  后台
    // ═══════════════════════════════════════════════════════════

    /**
     * 后台公告列表。
     *
     * <p>这里<b>不能</b>碰 {@code includeGlobal} —— 走到后台分支靠的就是
     * 它为空。数据范围注入之后,村管只能看本村,超管不加限制。
     */
    @Override
    public PageResult<AnnouncementVO> pageAdmin(AnnouncementQueryDTO query) {
        dataScopeHelper.apply(query);
        query.setIncludeGlobal(null);
        query.setVillageId(null);
        return page(query);
    }

    /**
     * 后台公告详情。
     *
     * <p>先 {@link #loadManageable} 再取详情,顺序不能反 —— 反过来也能拿到
     * 数据,但会先读出一条别村的公告再把它丢掉,而且一旦哪次忘了校验,
     * 漏出去的就是一篇完整正文。
     */
    @Override
    public AnnouncementVO detailAdmin(Long id) {
        loadManageable(id);
        return announcementMapper.selectDetail(id);
    }

    // ═══════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════

    private PageResult<AnnouncementVO> page(AnnouncementQueryDTO query) {
        Page<AnnouncementVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<AnnouncementVO> result = announcementMapper.selectPage(page, query);
        return PageResult.of(result);
    }

    /**
     * 按 id 取出这条公告,并确认当前用户管得着它。
     *
     * <p><b>为什么"按 id 查"必须配一次归属校验:</b>列表接口的数据级权限
     * 是靠 SQL 里的村组白名单生效的,而按 id 操作的接口走不到那条 SQL。
     * 不单独校验的话,把请求里的 id 换成别村公告的 id 就能改别人的公告 ——
     * 这是数据级权限最常见的漏法,和留言板 adminDelete 那道校验是同一件事。
     *
     * <p><b>全平台公告({@code village_id} 为 NULL)只有超管能动。</b>
     * 它的受众是所有村的用户,一个村管没有资格去改一条发给全县的通知。
     * 注意这一条不是"村里的那部分不让改",而是整条都不让碰 ——
     * 公告是一个整体,没有"只改属于我的那一半"这种操作。
     */
    private BizAnnouncement loadManageable(Long id) {
        BizAnnouncement announcement = announcementMapper.selectById(id);
        if (announcement == null) {
            throw BusinessException.notFound("公告不存在或已删除");
        }
        LoginUser me = UserContext.get();
        if (announcement.getVillageId() == null) {
            if (!me.isSuperAdmin()) {
                throw BusinessException.forbidden("全平台公告只能由平台管理员维护");
            }
            return announcement;
        }
        if (!dataScopeHelper.canManageVillage(announcement.getVillageId())) {
            throw BusinessException.forbidden("只能管理本村发布的公告");
        }
        return announcement;
    }

    /**
     * 「系统公告」类型只有超管能用。
     *
     * <p>发布时前端会把这个选项藏起来,后端这里再挡一道 ——
     * 前端隐藏挡不住直接发 HTTP 请求。
     *
     * <p>编辑时挡的是<b>另一个方向</b>:不能让一条村级公告把类型改成 5。
     * 至于"原本就是系统公告的那条能不能被村管编辑",这个问题
     * {@link #loadManageable} 已经回答了 —— 系统公告一定是超管发布的,
     * 发布时 {@code resolvePublishVillage} 给超管的归属就是 NULL(全平台),
     * 而全平台公告只有超管碰得到。所以这里只需要看传上来的新类型。
     */
    private void requireSystemNoticePermission(LoginUser me, Integer noticeType) {
        if (Integer.valueOf(BizAnnouncement.TYPE_SYSTEM).equals(noticeType)
                && !me.isSuperAdmin()) {
            throw BusinessException.forbidden("「系统公告」只能由平台管理员发布");
        }
    }

    /**
     * 决定这条公告挂在哪个村组。
     *
     * <p>超管发的是<b>全平台公告</b>({@code village_id = null})。
     * 现在没有"超管代某个村发公告"的入口 —— 那需要让前端能选村组,
     * 而 {@link AnnouncementDTO} 刻意不收 villageId 字段。真要做,
     * 加一个可选字段并在 Service 里用数据范围校验即可,不是改不动,
     * 是现在没有这个需求。
     */
    private Long resolvePublishVillage(LoginUser me) {
        if (me.isSuperAdmin()) {
            return null;
        }
        if (me.getVillageId() == null) {
            throw BusinessException.badRequest("你的账号还没有绑定村组,无法发布公告");
        }
        return me.getVillageId();
    }

    /**
     * 敏感词检查。
     *
     * <p><b>只拦截、不替换。</b>两个理由,第二个比第一个更硬。
     *
     * <p>其一,这一列是 LONGTEXT,定位上是留给富文本的。替换类词一旦打码,
     * 可能把一个 HTML 标签切成两半("&lt;st**rong&gt;" 这种),
     * 页面直接渲染坏掉。而留言板、聊天那种纯文本内容打码是安全的。
     * (注:当前版本的发布端用的是普通 textarea,正文以纯文本 + 换行
     * 保存,前端按 pre-wrap 原样渲染 —— 眼下这个风险还没被触发。
     * 但把它写在这儿,是为了将来真接上富文本编辑器时这一步不用回头改。)
     *
     * <p>其二,也是更要紧的:公告的发布人是有发布权限的管理员,不是普通用户。
     * 对管理员的输入做"悄悄改掉几个字"这种事,不如直接拒绝、让他自己改 ——
     * 一份被系统改过字的公文,署名的人却没看过改后的版本,这是不合适的。
     */
    private void checkSensitive(AnnouncementDTO dto) {
        checkText(dto.getTitle());
        checkText(dto.getContent());
    }

    private void checkText(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (sensitiveWordUtil.shouldBlock(text)) {
            Set<String> hits = sensitiveWordUtil.findAll(text);
            throw new BusinessException(ResultCode.SENSITIVE_CONTENT,
                    "公告包含违规词,请修改后重试:" + String.join("、", hits));
        }
    }

    /**
     * 把公告推给能看到它的人。
     *
     * <p>接收人范围与可见性口径一致:全平台公告发给所有人,
     * 村级公告只发给本村人。<b>在页面上看不到这条公告的人,
     * 不应该收到它的通知</b> —— 否则通知栏里会出现一条点进去查无此物的消息。
     *
     * <p><b>这是写扩散。</b>发一条全平台公告会插入 N 行通知记录,
     * N 是用户数。在村级规模(一个村几十到几百人)下这完全没问题,
     * 但如果用户量级到了几十万,正确做法是反过来:不写 N 行,
     * 而是给用户记一个"公告已读位置",读通知时再按位置取差集。
     * 这里选写扩散,是因为它让"已读/未读"这件事变得极其简单 ——
     * 每个用户一行记录,状态就在自己那一行上。
     */
    private void notifyMembers(BizAnnouncement announcement) {
        LoginUser me = UserContext.get();
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getAccountStatus, ACCOUNT_STATUS_NORMAL)
                .ne(SysUser::getId, me.getUserId())   // 不用通知自己
                .select(SysUser::getId);
        if (announcement.getVillageId() != null) {
            wrapper.eq(SysUser::getVillageId, announcement.getVillageId());
        }
        List<Long> receivers = userMapper.selectList(wrapper).stream()
                .map(SysUser::getId)
                .toList();
        if (receivers.isEmpty()) {
            return;
        }

        noticeService.sendBatch(receivers, MsgNotice.TYPE_ANNOUNCEMENT,
                "新公告:" + announcement.getTitle(),
                snippet(announcement.getContent()),
                NOTICE_BIZ_ANNOUNCEMENT, announcement.getId());
        log.info("公告通知已发送: announcementId={}, 接收人 {} 位",
                announcement.getId(), receivers.size());
    }

    /**
     * 从富文本正文里取一段纯文本摘要。
     *
     * <p>去掉标签再截断 —— 直接把带 HTML 的正文塞进通知里,
     * 通知列表会显示成一堆 {@code <p><span style=...>} 之类的噪声。
     */
    private String snippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String plain = content.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
        return plain.length() <= SNIPPET_LENGTH ? plain : plain.substring(0, SNIPPET_LENGTH) + "…";
    }
}
