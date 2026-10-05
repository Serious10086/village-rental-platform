package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.common.enums.ResourceStatusEnum;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.ResultCode;
import com.gjf.village.common.util.BizNoGenerator;
import com.gjf.village.common.util.SensitiveWordUtil;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.ResourcePublishDTO;
import com.gjf.village.dto.ResourceQueryDTO;
import com.gjf.village.entity.BizResource;
import com.gjf.village.entity.BizResourceCategory;
import com.gjf.village.entity.BizResourceFavorite;
import com.gjf.village.entity.ResourceListView;
import com.gjf.village.entity.SysAuditRecord;
import com.gjf.village.mapper.BizResourceCategoryMapper;
import com.gjf.village.mapper.BizResourceFavoriteMapper;
import com.gjf.village.mapper.BizResourceMapper;
import com.gjf.village.mapper.SysAuditRecordMapper;
import com.gjf.village.security.LoginUser;
import com.gjf.village.service.AiBehaviorService;
import com.gjf.village.service.BizResourceService;
import com.gjf.village.service.DataScopeHelper;
import com.gjf.village.vo.MyResourceVO;
import com.gjf.village.vo.ResourceDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 资源服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizResourceServiceImpl implements BizResourceService {

    private final BizResourceMapper resourceMapper;
    private final BizResourceCategoryMapper categoryMapper;
    private final BizResourceFavoriteMapper favoriteMapper;
    private final SysAuditRecordMapper auditRecordMapper;
    private final DataScopeHelper dataScopeHelper;
    private final BizNoGenerator bizNoGenerator;
    private final SensitiveWordUtil sensitiveWordUtil;
    private final AiBehaviorService behaviorService;

    /** 实名状态:已认证 */
    private static final int REALNAME_VERIFIED = 2;

    /** 交易方式:出租 / 出售 */
    private static final int TRADE_RENT = 1;
    private static final int TRADE_SELL = 2;

    // ═══════════════════════════════════════════════════════════
    //  查询
    // ═══════════════════════════════════════════════════════════

    /**
     * 前台资源搜索。
     *
     * <p><b>刻意不调用 applyDataScope。</b>买家本来就该能跨村找资源,
     * 把搜索限制在"本村"会让平台失去意义。数据级权限的边界是
     * "管理类查询",不是"所有查询" —— 这两处放在一起看,能说清楚
     * 为什么权限不能一刀切。
     */
    @Override
    public PageResult<ResourceListView> pageResources(ResourceQueryDTO query) {
        Page<ResourceListView> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<ResourceListView> result = resourceMapper.selectResourcePage(page, query);

        // ── 记一次搜索行为 ──────────────────────────────────────
        // 搜索由服务端记而不是让前端上报:服务端**本来就知道**用户搜了什么词
        // (query.keyword),让前端再报一遍等于多一个可能对不上的数据源。
        //
        // 两个条件缺一不可:
        //   · 有关键词 —— 不带关键词的列表浏览不是搜索,记下来只会污染
        //     "他找过什么"这个信号
        //   · pageNum == 1 —— 否则翻 8 页就会被记成"同一个词搜了 8 次",
        //     后台看数据时会以为这个人反复在找同一个东西
        if (StringUtils.hasText(query.getKeyword()) && Objects.equals(query.getPageNum(), 1L)) {
            behaviorService.searched(query.getKeyword());
        }

        return PageResult.of(result);
    }

    /**
     * 我的发布。
     *
     * <p>强制把 publisherId 设成当前用户 —— 不接受前端传入,
     * 否则任何人都能传别人的ID看人家的未上架资源。
     */
    @Override
    public PageResult<MyResourceVO> pageMyResources(ResourceQueryDTO query) {
        query.setPublisherId(UserContext.getUserId());
        query.setVillageIds(null);   // 自己看自己的,不受村组限制
        query.setResourceStatus(query.getResourceStatus());  // 前端可按状态筛

        Page<MyResourceVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<MyResourceVO> result = resourceMapper.selectMyResourcePage(page, query);
        return PageResult.of(result);
    }

    /**
     * 后台资源管理。
     *
     * <p>这里<b>必须</b>调 applyDataScope:村级管理员只能看本村资源,
     * 这是"数据级权限"在资源模块的落点。
     */
    @Override
    public PageResult<MyResourceVO> pageAdminResources(ResourceQueryDTO query) {
        // ① 先注入数据范围(会覆盖前端传来的任何村组参数)
        dataScopeHelper.apply(query);
        // ② publisherId 只用于"仅本人"范围,后台管理不该按发布人过滤
        if (query.getPublisherId() != null && !Objects.equals(query.getPublisherId(),
                UserContext.getUserId())) {
            query.setPublisherId(null);
        }

        Page<MyResourceVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<MyResourceVO> result = resourceMapper.selectMyResourcePage(page, query);
        return PageResult.of(result);
    }

    @Override
    public ResourceDetailVO getDetail(Long id) {
        ResourceDetailVO detail = resourceMapper.selectResourceDetail(id);
        if (detail == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "资源不存在或已被删除");
        }

        ResourceStatusEnum status = ResourceStatusEnum.of(detail.getResourceStatus());
        LoginUser user = UserContext.getOrNull();
        boolean isOwner = user != null && Objects.equals(user.getUserId(), detail.getPublisherId());
        detail.setIsOwner(isOwner);

        // 我收藏过没有 —— 游客恒为 false(没登录就是没收藏)
        detail.setFavorited(user != null && favoriteMapper.selectCount(
                new LambdaQueryWrapper<BizResourceFavorite>()
                        .eq(BizResourceFavorite::getUserId, user.getUserId())
                        .eq(BizResourceFavorite::getResourceId, id)) > 0);

        // ── 可见性判断 ────────────────────────────────────────
        // 已上架的资源所有人可见;
        // 未上架的只有发布者本人、本村管理员、超管能看。
        //
        // 不判断的话,任何人都能通过遍历 id 看到别人还没审核通过的资源 ——
        // 包括被驳回的、被违规下架的,这是实打实的信息泄露。
        if (!status.isVisibleToPublic() && !isOwner) {
            boolean canManage = user != null && (user.isSuperAdmin()
                    || (user.getRoleCodes().contains("VILLAGE_ADMIN")
                        && dataScopeHelper.canAccessVillage(detail.getVillageId())));
            if (!canManage) {
                throw new BusinessException(ResultCode.NOT_FOUND, "资源不存在或尚未上架");
            }
        }

        // ── 浏览量 +1 ────────────────────────────────────────
        // 只对已上架的资源计数,避免发布者刷自己的浏览量
        if (status.isVisibleToPublic()) {
            try {
                // 【为什么改成 SQL 自增】原来是"读出来加一再 updateById":
                // 两个人同时打开同一个详情页,两边都读到 100、各自写回 101,
                // 结果是"看了两次、只加了一次"。浏览量原本只是个装饰
                // (错了也没人看得出),但批次 8 的<b>村组热度推荐直接按它排序</b>
                // —— 它从"给人看一眼"变成了"喂给算法的输入"。
                // 输入数据偏小会把真正热门的资源排到后面,而那种错
                // 在界面上完全看不出来。所以按数据的标准要求它。
                resourceMapper.increaseViewCount(id);
                // 详情 VO 上那个数字也要跟着涨,否则用户看到的是自己刷新前的值
                detail.setViewCount(detail.getViewCount() == null ? 1 : detail.getViewCount() + 1);
            } catch (Exception e) {
                // 浏览量统计失败不该让详情页打不开
                log.warn("浏览量更新失败: resourceId={}", id, e);
            }
        }

        return detail;
    }

    // ═══════════════════════════════════════════════════════════
    //  发布 / 编辑
    // ═══════════════════════════════════════════════════════════

    /**
     * 发布资源。
     *
     * <p>用事务包住两张表的写入:资源本身 + 待审核记录。
     * 如果只写成功资源而审核记录失败,这条资源会永远卡在"待审核"状态,
     * 但管理员的审核列表里根本找不到它 —— 发布者会以为系统坏了。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publish(ResourcePublishDTO dto) {
        LoginUser user = UserContext.get();

        // ── 前置校验 ──────────────────────────────────────────
        requireRealnameVerified(user);
        if (user.getVillageId() == null) {
            throw BusinessException.badRequest("你的账号还没有绑定村组,无法发布资源");
        }

        BizResourceCategory category = requireCategory(dto.getCategoryId());
        validateByTradeType(dto);
        checkSensitiveContent(dto);

        // ── 组装资源 ──────────────────────────────────────────
        BizResource resource = new BizResource();
        resource.setResourceNo(bizNoGenerator.nextResourceNo());
        resource.setPublisherId(user.getUserId());
        // 资源归属发布者所在村组,不接受前端传入 ——
        // 否则发布者可以把资源挂到别的村,绕过本村管理员的审核
        resource.setVillageId(user.getVillageId());
        resource.setResourceStatus(ResourceStatusEnum.PENDING.getCode());
        // 风险等级继承自分类,"高风险资源需培训"由此驱动
        resource.setRiskLevel(category.getRiskLevel());
        resource.setNeedTraining(Integer.valueOf(3).equals(category.getRiskLevel()) ? 1 : 0);

        applyPublishFields(resource, dto);

        resourceMapper.insert(resource);

        // ── 生成审核待办 ──────────────────────────────────────
        SysAuditRecord record = new SysAuditRecord();
        record.setBizType(SysAuditRecord.BIZ_RESOURCE);
        record.setBizId(resource.getId());
        record.setApplicantId(user.getUserId());
        record.setVillageId(user.getVillageId());
        record.setAuditStatus(SysAuditRecord.STATUS_PENDING);
        auditRecordMapper.insert(record);

        log.info("资源发布成功: id={}, no={}, publisher={}, tradeType={}",
                resource.getId(), resource.getResourceNo(), user.getUserId(), dto.getTradeType());
        return resource.getId();
    }

    /**
     * 编辑资源。
     *
     * <p><b>已上架的资源编辑后会退回待审核。</b>
     * 不这样做的话,发布者可以先提交一份合规内容通过审核,
     * 再把标题、描述、图片全换成违规内容 —— 审核就白做了。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(ResourcePublishDTO dto) {
        if (dto.getId() == null) {
            throw BusinessException.badRequest("缺少资源ID");
        }
        BizResource existing = requireOwnedResource(dto.getId());

        ResourceStatusEnum status = ResourceStatusEnum.of(existing.getResourceStatus());
        if (!status.isEditable()) {
            throw new BusinessException(ResultCode.RESOURCE_STATUS_INVALID,
                    "当前状态(" + status.getDescription() + ")不允许编辑");
        }

        BizResourceCategory category = requireCategory(dto.getCategoryId());
        validateByTradeType(dto);
        checkSensitiveContent(dto);

        BizResource update = new BizResource();
        update.setId(existing.getId());
        update.setRiskLevel(category.getRiskLevel());
        update.setNeedTraining(Integer.valueOf(3).equals(category.getRiskLevel()) ? 1 : 0);
        applyPublishFields(update, dto);

        // ── 编辑后是否需要重新审核 ────────────────────────────
        //
        // 三种状态需要打回待审核并生成新的审核单:
        //   · 已上架 / 临时下架 —— 内容变了,原审核结论不再适用
        //   · 审核驳回 —— 发布者正是为了重新过审才改的
        //
        // 【踩过的坑】最初只处理了前两种,漏掉"已驳回"。结果是:
        // 发布者被驳回后改了内容点保存,资源状态仍是"审核驳回",
        // 也没有生成新的待审记录 —— 管理员列表里看不到,发布者以为改好了,
        // 实际卡死在驳回状态,除非删掉重发。
        boolean needReaudit = status == ResourceStatusEnum.ONLINE
                || status == ResourceStatusEnum.OFFLINE
                || status == ResourceStatusEnum.REJECTED;

        if (needReaudit) {
            update.setResourceStatus(ResourceStatusEnum.PENDING.getCode());
            // 驳回原因要一并清掉,否则资源还是待审核状态却挂着上次的驳回理由
            update.setOfflineReason("");

            // 删掉旧的待审单再建新的,避免同一个资源堆出多条待办 ——
            // 管理员列表里出现同一资源两条待审,不知道该批哪条
            auditRecordMapper.delete(new LambdaQueryWrapper<SysAuditRecord>()
                    .eq(SysAuditRecord::getBizType, SysAuditRecord.BIZ_RESOURCE)
                    .eq(SysAuditRecord::getBizId, existing.getId())
                    .eq(SysAuditRecord::getAuditStatus, SysAuditRecord.STATUS_PENDING));

            createAuditRecord(existing.getId(), existing.getVillageId(), UserContext.getUserId());

            log.info("资源被编辑,重新提交审核: id={}, 原状态={}",
                    existing.getId(), status.getDescription());
        }

        resourceMapper.updateById(update);
    }

    // ═══════════════════════════════════════════════════════════
    //  状态管理
    // ═══════════════════════════════════════════════════════════

    @Override
    public void offline(Long id) {
        BizResource resource = requireOwnedResource(id);
        ResourceStatusEnum status = ResourceStatusEnum.of(resource.getResourceStatus());
        if (!status.isOfflinable()) {
            throw new BusinessException(ResultCode.RESOURCE_STATUS_INVALID,
                    "当前状态(" + status.getDescription() + ")不能下架");
        }
        updateStatus(id, ResourceStatusEnum.OFFLINE);
    }

    @Override
    public void online(Long id) {
        BizResource resource = requireOwnedResource(id);
        ResourceStatusEnum status = ResourceStatusEnum.of(resource.getResourceStatus());
        if (!status.isOnlinable()) {
            throw new BusinessException(ResultCode.RESOURCE_STATUS_INVALID,
                    "当前状态(" + status.getDescription() + ")不能直接上架,"
                            + "被驳回或被下架的资源需要修改后重新提交审核");
        }
        updateStatus(id, ResourceStatusEnum.ONLINE);
    }

    @Override
    public void adjustPrice(Long id, BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("价格必须大于 0");
        }
        BizResource resource = requireOwnedResource(id);
        ResourceStatusEnum status = ResourceStatusEnum.of(resource.getResourceStatus());

        // 交易中不能改价:买家已经基于当前价格下单了
        if (status == ResourceStatusEnum.IN_TRADE || status == ResourceStatusEnum.FINISHED) {
            throw new BusinessException(ResultCode.RESOURCE_STATUS_INVALID,
                    "交易中或已完成的资源不能调价");
        }

        BizResource update = new BizResource();
        update.setId(id);
        update.setPrice(price);
        resourceMapper.updateById(update);
        log.info("资源调价: id={}, 新价格={}", id, price);
    }

    @Override
    public void delete(Long id) {
        BizResource resource = requireOwnedResource(id);
        ResourceStatusEnum status = ResourceStatusEnum.of(resource.getResourceStatus());
        if (status == ResourceStatusEnum.IN_TRADE) {
            throw new BusinessException(ResultCode.RESOURCE_STATUS_INVALID,
                    "交易中的资源不能删除,请先取消相关订单");
        }
        // 逻辑删除:@TableLogic 会把 deleted 置 1,
        // 历史订单和评价仍能关联到这条资源,不会出现"订单指向一个不存在的资源"
        resourceMapper.deleteById(id);
        log.info("资源已删除(逻辑): id={}", id);
    }

    // ═══════════════════════════════════════════════════════════
    //  私有方法
    // ═══════════════════════════════════════════════════════════

    /** 校验实名状态 —— 未实名认证不能发布资源 */
    private void requireRealnameVerified(LoginUser user) {
        if (!Objects.equals(user.getRealnameStatus(), REALNAME_VERIFIED)) {
            throw new BusinessException(ResultCode.REALNAME_REQUIRED,
                    "请先完成实名认证后再发布资源");
        }
    }

    /** 校验分类存在 */
    private BizResourceCategory requireCategory(Long categoryId) {
        BizResourceCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw BusinessException.badRequest("所选分类不存在");
        }
        return category;
    }

    /**
     * 校验资源归属:必须是当前用户发布的。
     *
     * <p>只检查"是不是我的资源",不检查角色 —— 之所以不检查,是因为
     * 超管和村管走的是后台管理接口(AdminResourceController),
     * 那里有独立的权限注解和数据范围校验。把两种角色的校验混在一个方法里,
     * 很容易出现"以为走的是后台接口、其实走的是前台接口"导致的越权。
     */
    private BizResource requireOwnedResource(Long id) {
        BizResource resource = resourceMapper.selectById(id);
        if (resource == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "资源不存在");
        }
        if (!Objects.equals(resource.getPublisherId(), UserContext.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能操作自己发布的资源");
        }
        return resource;
    }

    /**
     * 敏感词检查。
     *
     * <p>两级处理,由词库里的 {@code action} 决定:
     * <ul>
     *   <li><b>拦截类</b>(辱骂、违法)→ 直接拒绝提交,并告知命中哪个词</li>
     *   <li><b>替换类</b>(广告)→ 把词打成星号后<b>就地改写 DTO</b>,内容照常发布</li>
     * </ul>
     *
     * <p>这里和后台人工审核是互补关系而不是替代:自动过滤负责拦下明显的违规内容
     * (省掉管理员一批无效审核),人工审核负责判断语义和真实性 ——
     * 机器判断不了"这个描述是不是在吹牛"。
     */
    private void checkSensitiveContent(ResourcePublishDTO dto) {
        // 名称、描述、标签、使用规则都要过一遍 ——
        // 只查描述的话,把广告词塞进标题或标签就绕过去了
        String[] fields = {dto.getResourceName(), dto.getDescription(),
                dto.getUseRule(), join(dto.getTags())};

        for (String field : fields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            if (sensitiveWordUtil.shouldBlock(field)) {
                Set<String> hits = sensitiveWordUtil.findAll(field);
                throw new BusinessException(ResultCode.SENSITIVE_CONTENT,
                        "内容包含违规词,请修改后重新提交:" + String.join("、", hits));
            }
        }

        // 拦截类已排除,这里处理替换类:直接改写 DTO,后续落库的就是打码后的内容
        dto.setResourceName(sensitiveWordUtil.filter(dto.getResourceName()));
        dto.setDescription(sensitiveWordUtil.filter(dto.getDescription()));
        dto.setUseRule(sensitiveWordUtil.filter(dto.getUseRule()));
        if (dto.getTags() != null) {
            dto.setTags(dto.getTags().stream()
                    .map(sensitiveWordUtil::filter)
                    .toList());
        }
    }

    /**
     * 按交易方式做条件校验。
     *
     * <p>出租和出售的必填项不同,用注解做不了"根据另一个字段决定是否必填",
     * 所以手写在这里。校验失败时给出的是<b>具体缺哪一项</b>,而不是笼统的
     * "参数不合法" —— 用户填了十几个字段的表单,你需要告诉他缺哪个。
     */
    private void validateByTradeType(ResourcePublishDTO dto) {
        if (Objects.equals(dto.getTradeType(), TRADE_RENT)) {
            if (dto.getRentStartDate() == null || dto.getRentEndDate() == null) {
                throw BusinessException.badRequest("出租资源必须填写可租时段");
            }
            if (dto.getRentEndDate().isBefore(dto.getRentStartDate())) {
                throw BusinessException.badRequest("可租截止日不能早于起始日");
            }
            if (dto.getRentableWeekdays() == null || dto.getRentableWeekdays().isEmpty()) {
                throw BusinessException.badRequest("请选择可租的星期");
            }
            if (!StringUtils.hasText(dto.getUseRule())) {
                throw BusinessException.badRequest("出租资源必须填写使用规则");
            }
        } else if (Objects.equals(dto.getTradeType(), TRADE_SELL)) {
            if (dto.getOldDegree() == null) {
                throw BusinessException.badRequest("出售资源必须填写新旧程度");
            }
            if (dto.getDeliveryType() == null) {
                throw BusinessException.badRequest("出售资源必须选择交货方式");
            }
        } else {
            throw BusinessException.badRequest("交易方式不合法");
        }
    }

    /** 把 DTO 的字段搬到实体上(新增和编辑共用) */
    private void applyPublishFields(BizResource resource, ResourcePublishDTO dto) {
        resource.setCategoryId(dto.getCategoryId());
        resource.setResourceName(dto.getResourceName());
        resource.setCoverUrl(dto.getCoverUrl());
        resource.setImages(join(dto.getImages()));
        resource.setTags(join(dto.getTags()));
        resource.setDescription(dto.getDescription());
        resource.setTradeType(dto.getTradeType());
        resource.setPrice(dto.getPrice());
        resource.setPriceUnit(dto.getPriceUnit() == null ? 1 : dto.getPriceUnit());
        resource.setDeposit(dto.getDeposit() == null ? BigDecimal.ZERO : dto.getDeposit());
        resource.setRentStartDate(dto.getRentStartDate());
        resource.setRentEndDate(dto.getRentEndDate());
        resource.setRentableWeekdays(dto.getRentableWeekdays() == null
                ? null : join(dto.getRentableWeekdays()));
        resource.setUseRule(dto.getUseRule());
        resource.setOldDegree(dto.getOldDegree());
        resource.setDeliveryType(dto.getDeliveryType());
        resource.setLocation(dto.getLocation());
        resource.setLongitude(dto.getLongitude());
        resource.setLatitude(dto.getLatitude());
    }

    /** List → 逗号分隔字符串 */
    private String join(List<?> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** 写一条待审核记录 */
    private void createAuditRecord(Long bizId, Long villageId, Long applicantId) {
        SysAuditRecord record = new SysAuditRecord();
        record.setBizType(SysAuditRecord.BIZ_RESOURCE);
        record.setBizId(bizId);
        record.setApplicantId(applicantId);
        record.setVillageId(villageId);
        record.setAuditStatus(SysAuditRecord.STATUS_PENDING);
        auditRecordMapper.insert(record);
    }

    private void updateStatus(Long id, ResourceStatusEnum status) {
        BizResource update = new BizResource();
        update.setId(id);
        update.setResourceStatus(status.getCode());
        resourceMapper.updateById(update);
        log.info("资源状态变更: id={}, 新状态={}", id, status.getDescription());
    }

    /**
     * 按状态统计(供后台工作台用)。
     */
    public long countByStatus(Integer status) {
        return resourceMapper.selectCount(new LambdaQueryWrapper<BizResource>()
                .eq(status != null, BizResource::getResourceStatus, status));
    }
}
