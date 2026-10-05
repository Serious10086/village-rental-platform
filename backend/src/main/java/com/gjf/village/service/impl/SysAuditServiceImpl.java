package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.common.enums.ResourceStatusEnum;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.ResultCode;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.AuditDTO;
import com.gjf.village.dto.AuditQueryDTO;
import com.gjf.village.dto.OutsideUserCreateDTO;
import com.gjf.village.dto.RealnameSubmitDTO;
import com.gjf.village.entity.BizResource;
import com.gjf.village.entity.MsgNotice;
import com.gjf.village.entity.SysAuditRecord;
import com.gjf.village.entity.SysUser;
import com.gjf.village.entity.SysVillage;
import com.gjf.village.mapper.BizResourceMapper;
import com.gjf.village.mapper.SysAuditRecordMapper;
import com.gjf.village.mapper.SysUserMapper;
import com.gjf.village.mapper.SysVillageMapper;
import com.gjf.village.security.LoginUser;
import com.gjf.village.service.DataScopeHelper;
import com.gjf.village.service.MsgNoticeService;
import com.gjf.village.service.SysAuditService;
import com.gjf.village.service.SysUserService;
import com.gjf.village.vo.AuditRecordVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 通用审核服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysAuditServiceImpl implements SysAuditService {

    private final SysAuditRecordMapper auditMapper;
    private final SysUserMapper userMapper;
    private final BizResourceMapper resourceMapper;
    private final SysVillageMapper villageMapper;
    private final MsgNoticeService noticeService;
    private final DataScopeHelper dataScopeHelper;
    private final SysUserService userService;

    /** 实名状态:未提交 / 待审核 / 已认证 / 已驳回 */
    private static final int REALNAME_NONE = 0;
    private static final int REALNAME_PENDING = 1;
    private static final int REALNAME_VERIFIED = 2;
    private static final int REALNAME_REJECTED = 3;

    // ═══════════════════════════════════════════════════════════
    //  提交实名认证
    // ═══════════════════════════════════════════════════════════

    /**
     * 提交实名认证。
     *
     * <p>用事务包住两张表的写入:用户表的认证资料 + 审核待办。
     * 只写成功一半的话,用户会显示"待审核"但管理员列表里找不到这条 ——
     * 用户以为系统坏了,管理员也不知道该审什么。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitRealname(RealnameSubmitDTO dto) {
        LoginUser loginUser = UserContext.get();
        Long userId = loginUser.getUserId();

        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }

        // 已认证的不允许重复提交 —— 否则认证通过后再传一份假资料,
        // 就又要走一遍审核,而旧资料已经生效了
        if (Objects.equals(user.getRealnameStatus(), REALNAME_VERIFIED)) {
            throw BusinessException.badRequest("你已完成实名认证,无需重复提交");
        }
        if (Objects.equals(user.getRealnameStatus(), REALNAME_PENDING)) {
            throw BusinessException.badRequest("实名认证正在审核中,请耐心等待");
        }

        // 身份证号查重:一个人只能用一张身份证注册
        Long duplicated = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getIdCard, dto.getIdCard())
                .ne(SysUser::getId, userId));
        if (duplicated != null && duplicated > 0) {
            throw BusinessException.badRequest("该身份证号已被其他账号使用");
        }

        // ── 更新用户资料 ──────────────────────────────────────
        // 注意只更新这几个字段,不用 updateById 传整个对象 ——
        // 那样会把前端没传的字段(如 balance)覆盖成 null
        SysUser update = new SysUser();
        update.setId(userId);
        update.setRealName(dto.getRealName());
        update.setIdCard(dto.getIdCard());
        update.setIdCardFront(dto.getIdCardFront());
        update.setIdCardBack(dto.getIdCardBack());
        update.setRealnameStatus(REALNAME_PENDING);
        userMapper.updateById(update);

        // ── 生成 / 复用审核待办 ───────────────────────────────
        //
        // 复用该申请人的最新一条记录,而不是新建。
        //
        // 【为什么按"最新一条"而不是"待审核的那条"去找】
        // 一开始我只查 audit_status = 0 的记录来复用,结果被驳回后重新提交时
        // 查不到(那条已经是"已驳回"了),于是又插了一条新的 ——
        // 管理员的列表里就出现同一个人两条待办,一条还是早已处理过的驳回记录。
        // 改成取最新一条(不论状态)并重置为待审核,一个人始终只有一条记录。
        //
        // 代价是丢失"被驳回几次"的历史。如果将来需要,可以另建一张
        // 审核历史表,而不是把历史堆在待办表里 —— 那样列表会永远清不干净。
        SysAuditRecord existing = auditMapper.selectOne(new LambdaQueryWrapper<SysAuditRecord>()
                .eq(SysAuditRecord::getBizType, SysAuditRecord.BIZ_REALNAME)
                .eq(SysAuditRecord::getBizId, userId)
                .orderByDesc(SysAuditRecord::getId)
                .last("LIMIT 1"));

        if (existing != null) {
            // 用 UpdateWrapper 而不是 updateById:后者会忽略 null 字段,
            // 清不掉上一次的审核人和驳回意见,列表里就会显示
            // "待审核 + 上次的驳回意见"这种自相矛盾的状态
            auditMapper.update(null, new LambdaUpdateWrapper<SysAuditRecord>()
                    .eq(SysAuditRecord::getId, existing.getId())
                    .set(SysAuditRecord::getAuditStatus, SysAuditRecord.STATUS_PENDING)
                    .set(SysAuditRecord::getSubmitTime, LocalDateTime.now())
                    .set(SysAuditRecord::getAuditorId, null)
                    .set(SysAuditRecord::getAuditRemark, null)
                    .set(SysAuditRecord::getAuditTime, null));
        } else {
            SysAuditRecord record = new SysAuditRecord();
            record.setBizType(SysAuditRecord.BIZ_REALNAME);
            // 实名认证的"业务ID"就是用户ID —— 审核的对象是一个账号
            record.setBizId(userId);
            record.setApplicantId(userId);
            // 归属村组决定哪个村的管员来审
            record.setVillageId(user.getVillageId());
            record.setAuditStatus(SysAuditRecord.STATUS_PENDING);
            auditMapper.insert(record);
        }

        log.info("实名认证提交: userId={}, realName={}", userId, dto.getRealName());
    }

    // ═══════════════════════════════════════════════════════════
    //  提交外来用户备案
    // ═══════════════════════════════════════════════════════════

    /**
     * 提交外来用户备案。
     *
     * <p>与实名认证的唯一结构差别:这里<b>没有申请人</b> —— 备案是管理员
     * 替外来人员办的,所以账号要先由这个方法建出来,申请人就是新账号本人。
     *
     * <p>建账号和建待办必须原子:只建了账号没建单,管理员不知道要审什么,
     * 而这个人会一直停在"没有归属村"的状态 —— 登录后发不了资源、下不了单。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitOutside(OutsideUserCreateDTO dto) {
        // 数据级权限:只能把人登记进自己管得到的村。
        // 不校验的话,村管改一下请求体里的 villageId 就能替别的村做决定。
        // 超管(范围 ALL)在这里直接放行,这也是全流程唯一绕过村组校验的入口
        if (!dataScopeHelper.canManageVillage(dto.getVillageId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能把外来人员登记到你管辖的村组");
        }

        // 目标还必须是真实存在的"村"。上面那个校验对超管是无条件放行的,
        // 所以少了这一步,villageId 传一个乡镇 id(如 1=唐昌镇)也能写进去 ——
        // 外键只保证"这个节点存在",不保证"它是村"。后果是这个人的归属村组
        // 落在乡镇上,此后没有任何村管看得到他,他发的资源也会掉出所有村的队列
        SysVillage village = villageMapper.selectById(dto.getVillageId());
        if (village == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "目标村组不存在");
        }
        // parent_id 为空即顶级节点,也就是乡镇,不是村
        if (village.getParentId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "只能把外来人员登记到具体的村,不能选乡镇");
        }

        // 建账号。此时 village_id 仍然留空,目标村组先只挂在备案单上
        Long userId = userService.createOutsideUser(dto);

        SysAuditRecord record = new SysAuditRecord();
        record.setBizType(SysAuditRecord.BIZ_OUTSIDE);
        // 备案的"业务ID"就是被登记人的用户ID —— 和实名认证同一个形状,
        // 所以 SysAuditRecordMapper.xml 里的 JOIN 不用改一行
        record.setBizId(userId);
        record.setApplicantId(userId);
        // 目标村组决定这条待办落在哪个村管的列表里
        record.setVillageId(dto.getVillageId());
        record.setAuditStatus(SysAuditRecord.STATUS_PENDING);
        auditMapper.insert(record);

        log.info("外来用户备案提交: userId={}, phone={}, 目标村组={}",
                userId, dto.getPhone(), dto.getVillageId());
        return userId;
    }

    // ═══════════════════════════════════════════════════════════
    //  审核
    // ═══════════════════════════════════════════════════════════

    /**
     * 审核。
     *
     * <p><b>一次调用改动三处,必须原子:</b>
     * <ol>
     *   <li>业务表状态(用户实名状态 / 资源状态)</li>
     *   <li>审核流水(审核人、意见、时间)</li>
     *   <li>给申请人发通知</li>
     * </ol>
     * 如果只改了业务状态而流水没写,事后查"这条资源是谁审的"就查不到;
     * 如果流水写了但通知没发,用户会一直等在那里不知道已经处理完了。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void audit(AuditDTO dto) {
        LoginUser auditor = UserContext.get();

        SysAuditRecord record = auditMapper.selectById(dto.getAuditId());
        if (record == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "审核记录不存在");
        }

        // ── 校验 1:类型必须对得上 ────────────────────────────
        // 防止把实名审核的"通过"误作用到资源上
        if (!Objects.equals(record.getBizType(), dto.getBizType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "审核单类型不匹配,请刷新页面后重试");
        }

        // ── 校验 2:只有待审核的单子能被审 ────────────────────
        if (!Objects.equals(record.getAuditStatus(), SysAuditRecord.STATUS_PENDING)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "该申请已被处理,不能重复审核");
        }

        // ── 校验 3:数据级权限 ─────────────────────────────────
        // 不做这一步的话,村级管理员改一下请求里的 auditId
        // 就能审别的村的单子 —— 数据级权限在写操作上同样要兜住,
        // 不能只在查询时过滤
        if (!dataScopeHelper.canAccessVillage(record.getVillageId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权审核其他村组的申请");
        }

        // ── 校验 4:驳回必须说明原因 ──────────────────────────
        boolean approved = Boolean.TRUE.equals(dto.getApproved());
        if (!approved && !StringUtils.hasText(dto.getRemark())) {
            throw BusinessException.badRequest("驳回时必须填写原因,否则申请人不知道该改什么");
        }

        // ── 分发到具体业务 ────────────────────────────────────
        if (Objects.equals(record.getBizType(), SysAuditRecord.BIZ_REALNAME)) {
            handleRealnameAudit(record, approved, dto.getRemark());
        } else if (Objects.equals(record.getBizType(), SysAuditRecord.BIZ_RESOURCE)) {
            handleResourceAudit(record, approved, dto.getRemark());
        } else if (Objects.equals(record.getBizType(), SysAuditRecord.BIZ_OUTSIDE)) {
            handleOutsideAudit(record, approved, dto.getRemark());
        } else {
            throw new BusinessException(ResultCode.BAD_REQUEST, "暂不支持该类型的审核");
        }

        // ── 写审核流水 ────────────────────────────────────────
        SysAuditRecord update = new SysAuditRecord();
        update.setId(record.getId());
        update.setAuditStatus(approved
                ? SysAuditRecord.STATUS_APPROVED : SysAuditRecord.STATUS_REJECTED);
        update.setAuditorId(auditor.getUserId());
        update.setAuditRemark(dto.getRemark());
        update.setAuditTime(LocalDateTime.now());
        auditMapper.updateById(update);

        log.info("审核完成: auditId={}, bizType={}, approved={}, auditor={}",
                record.getId(), record.getBizType(), approved, auditor.getUserId());
    }

    /** 实名认证的审核处理 */
    private void handleRealnameAudit(SysAuditRecord record, boolean approved, String remark) {
        Long userId = record.getBizId();

        SysUser update = new SysUser();
        update.setId(userId);
        update.setRealnameStatus(approved ? REALNAME_VERIFIED : REALNAME_REJECTED);
        userMapper.updateById(update);

        // 审核结果必须通知到本人 —— 否则他只能反复刷新页面猜有没有过
        noticeService.send(
                userId,
                MsgNotice.TYPE_REALNAME,
                approved ? "实名认证已通过" : "实名认证未通过",
                approved
                        ? "你的实名认证已通过审核,现在可以发布资源和下单了。"
                        : "你的实名认证未通过。原因:" + remark + "。请修改后重新提交。",
                SysAuditRecord.BIZ_REALNAME,
                userId
        );
    }

    /** 资源发布的审核处理 */
    private void handleResourceAudit(SysAuditRecord record, boolean approved, String remark) {
        BizResource resource = resourceMapper.selectById(record.getBizId());
        if (resource == null) {
            // 资源在等待审核期间被发布者删了。这种情况不该让整个审核失败,
            // 把审核单标记为驳回即可 —— 否则管理员会卡在一条永远审不过的单子上
            log.warn("审核对应的资源已不存在,跳过状态更新: auditId={}, resourceId={}",
                    record.getId(), record.getBizId());
            return;
        }

        BizResource update = new BizResource();
        update.setId(resource.getId());
        update.setAuditUserId(UserContext.getUserId());
        update.setAuditTime(LocalDateTime.now());

        if (approved) {
            update.setResourceStatus(ResourceStatusEnum.ONLINE.getCode());
            update.setOfflineReason(null);
        } else {
            update.setResourceStatus(ResourceStatusEnum.REJECTED.getCode());
            // 驳回原因写进资源表,发布者在"我的发布"里能直接看到该改什么
            update.setOfflineReason(remark);
        }
        resourceMapper.updateById(update);

        noticeService.send(
                resource.getPublisherId(),
                MsgNotice.TYPE_RESOURCE,
                approved ? "资源审核通过" : "资源审核未通过",
                approved
                        ? "你发布的「" + resource.getResourceName() + "」已通过审核,现在可以在前台被搜到了。"
                        : "你发布的「" + resource.getResourceName() + "」未通过审核。原因:"
                          + remark + "。修改后可重新提交。",
                SysAuditRecord.BIZ_RESOURCE,
                resource.getId()
        );
    }

    /** 外来用户备案的审核处理 */
    private void handleOutsideAudit(SysAuditRecord record, boolean approved, String remark) {
        Long userId = record.getBizId();

        // 只有通过才动用户 —— 备案到此才真正生效:他终于有了归属村组。
        // 驳回时 village_id 保持为空,他仍然是"在本村没有归属的外来人员",
        // 登记人改完资料可以重新提交
        if (approved) {
            SysUser update = new SysUser();
            update.setId(userId);
            update.setVillageId(record.getVillageId());
            userMapper.updateById(update);
        }

        // is_outside 保持 1 不动:它是"这个人是外来户"的永久身份标记,
        // 不是"还没备案"的临时标记。种子数据里的周伟就是
        // village_id=3 且 is_outside=1 —— 备案通过不改变他是外来户这件事

        noticeService.send(
                userId,
                MsgNotice.TYPE_OUTSIDE,
                approved ? "外来用户备案已通过" : "外来用户备案未通过",
                approved
                        ? "你的外来用户备案已通过,现在可以使用本村的服务了。"
                        : "你的外来用户备案未通过。原因:" + remark + "。请联系登记人修改后重新提交。",
                // bizType / bizId 都传 null,<b>不要</b>图省事传
                // SysAuditRecord.BIZ_OUTSIDE(=3):两套编号只在 1/2/4 上相同,
                // 3 在 MsgNotice 这边指的是"订单",会把通知链到「我的订单」去。
                // 备案的跳转由 buildLinkPath 按 noticeType 给出,落在个人中心
                null,
                null
        );
    }

    // ═══════════════════════════════════════════════════════════
    //  查询
    // ═══════════════════════════════════════════════════════════

    @Override
    public PageResult<AuditRecordVO> pageAudits(AuditQueryDTO query) {
        // 注入数据范围 —— 村级管理员只能看到本村的审核单
        dataScopeHelper.apply(query);

        Page<AuditRecordVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<AuditRecordVO> result = auditMapper.selectAuditPage(page, query);
        return PageResult.of(result);
    }

    @Override
    public AuditRecordVO getAuditDetail(Long auditId) {
        SysAuditRecord record = auditMapper.selectById(auditId);
        if (record == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "审核记录不存在");
        }
        // 查看详情同样要过数据级权限 —— 不然改个ID就能看到别村申请人的完整身份证号
        if (!dataScopeHelper.canAccessVillage(record.getVillageId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权查看其他村组的申请");
        }

        AuditRecordVO vo = auditMapper.selectAuditDetail(auditId);
        if (vo == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "审核记录不存在");
        }
        return vo;
    }

    @Override
    public long countPending(Integer bizType) {
        LoginUser user = UserContext.get();

        // 超管不限制村组;其余角色按自己的可见范围统计
        List<Long> villageIds = null;
        if (!user.isSuperAdmin() && user.getVillageId() != null) {
            AuditQueryDTO probe = new AuditQueryDTO();
            dataScopeHelper.apply(probe);
            villageIds = probe.getVillageIds();
        }
        return auditMapper.countPending(villageIds, bizType);
    }
}
