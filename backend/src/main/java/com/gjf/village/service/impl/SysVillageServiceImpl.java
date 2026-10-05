package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.dto.VillageDTO;
import com.gjf.village.entity.SysVillage;
import com.gjf.village.mapper.SysVillageMapper;
import com.gjf.village.service.SysVillageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 村组服务实现。
 */
@Service
@RequiredArgsConstructor
public class SysVillageServiceImpl implements SysVillageService {

    private final SysVillageMapper villageMapper;

    @Override
    public List<SysVillage> listEnabled() {
        return villageMapper.selectList(new LambdaQueryWrapper<SysVillage>()
                .eq(SysVillage::getStatus, 1)
                .orderByAsc(SysVillage::getSortOrder, SysVillage::getId));
    }

    @Override
    public List<SysVillage> buildTree() {
        List<SysVillage> all = listEnabled();
        return buildTreeRecursively(all, null);
    }

    @Override
    public List<SysVillage> listTree() {
        // 不过滤 status:停用的村组也得出现在管理页里,否则启用不回来
        List<SysVillage> all = villageMapper.selectList(new LambdaQueryWrapper<SysVillage>()
                .orderByAsc(SysVillage::getSortOrder, SysVillage::getId));
        return buildTreeRecursively(all, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createVillage(VillageDTO dto) {
        requireCodeAvailable(dto.getVillageCode(), null);
        requireParentExists(dto.getParentId());

        SysVillage village = new SysVillage();
        applyDto(village, dto);
        villageMapper.insert(village);
        return village.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateVillage(VillageDTO dto) {
        SysVillage exist = requireVillage(dto.getId());

        if (Objects.equals(dto.getParentId(), dto.getId())) {
            throw BusinessException.badRequest("上级节点不能是自己");
        }
        if (dto.getParentId() != null && isDescendant(dto.getId(), dto.getParentId())) {
            throw BusinessException.badRequest("上级节点不能是自己的下级");
        }
        requireCodeAvailable(dto.getVillageCode(), dto.getId());
        requireParentExists(dto.getParentId());

        SysVillage village = new SysVillage();
        village.setId(exist.getId());
        applyDto(village, dto);
        villageMapper.updateById(village);
    }

    /**
     * 删除村组。
     *
     * <p>村组是全系统数据级权限的锚点 —— 用户、资源都挂在它上面。
     * 直接删会让那些数据的 village_id 指向不存在的行,
     * 于是"本村数据"的过滤条件查不出任何东西,看起来像数据丢了。
     * 所以只要有引用就拒绝,让管理员先去迁移。
     *
     * <p><b>这里只做了"能给出更好提示"的两类预检</b>(下级村组、用户、资源)。
     * 真正兜底的是数据库的外键:<b>13 张表</b>都用
     * {@code ON DELETE RESTRICT} 指向 sys_village,包括订单、支付、评价、
     * 纠纷、提现、公告、留言板、AI 会话等 —— 逐个在这里数一遍既啰嗦又容易漏,
     * 漏一个就会变成 500。剩下的交给外键拦,由
     * {@code GlobalExceptionHandler} 把外键冲突翻译成能看懂的提示。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteVillage(Long id) {
        requireVillage(id);

        Long childCount = villageMapper.selectCount(new LambdaQueryWrapper<SysVillage>()
                .eq(SysVillage::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw BusinessException.badRequest(
                    "该节点下还有 " + childCount + " 个村组,请先删除下级");
        }

        // 走 Mapper 上的原生 COUNT,绕开 @TableLogic ——
        // 软删除的行物理上还在,外键照样拦得住删除,数漏了就会放行到数据库再报错
        long userCount = villageMapper.countUsersReferencing(id);
        long resourceCount = villageMapper.countResourcesReferencing(id);
        if (userCount > 0 || resourceCount > 0) {
            throw BusinessException.badRequest(
                    "该村组下还有 " + userCount + " 个用户、" + resourceCount
                            + " 条资源(含已删除的),无法删除,请先把它们迁到别的村组");
        }

        villageMapper.deleteById(id);
    }

    // ── 内部方法 ────────────────────────────────────────────────

    private void applyDto(SysVillage village, VillageDTO dto) {
        village.setParentId(dto.getParentId());
        village.setVillageName(dto.getVillageName());
        village.setVillageCode(dto.getVillageCode());
        village.setTownName(dto.getTownName());
        village.setRegionCode(dto.getRegionCode());
        village.setSortOrder(dto.getSortOrder() == null ? 99 : dto.getSortOrder());
        village.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
    }

    private SysVillage requireVillage(Long id) {
        if (id == null) {
            throw BusinessException.badRequest("村组ID不能为空");
        }
        SysVillage village = villageMapper.selectById(id);
        if (village == null) {
            throw BusinessException.notFound("村组不存在");
        }
        return village;
    }

    private void requireParentExists(Long parentId) {
        if (parentId == null) {
            return;
        }
        if (villageMapper.selectById(parentId) == null) {
            throw BusinessException.badRequest("上级节点不存在");
        }
    }

    /**
     * 村组编码必须唯一。
     *
     * <p>编码在库里是 {@code NOT NULL UNIQUE VARCHAR(20)},所以这里不做"为空就跳过"
     * 的处理 —— 空值会被 DTO 上的 {@code @NotBlank} 挡在入口,真漏进来也是数据库报错,
     * 不如在这里给一句能看懂的话。
     */
    private void requireCodeAvailable(String villageCode, Long excludeId) {
        Long count = villageMapper.selectCount(new LambdaQueryWrapper<SysVillage>()
                .eq(SysVillage::getVillageCode, villageCode)
                .ne(excludeId != null, SysVillage::getId, excludeId));
        if (count != null && count > 0) {
            throw BusinessException.badRequest("村组编码「" + villageCode + "」已存在");
        }
    }

    private boolean isDescendant(Long villageId, Long candidateId) {
        Long cursor = candidateId;
        for (int depth = 0; cursor != null && depth < 50; depth++) {
            if (cursor.equals(villageId)) {
                return true;
            }
            SysVillage parent = villageMapper.selectById(cursor);
            cursor = parent == null ? null : parent.getParentId();
        }
        return false;
    }

    private List<SysVillage> buildTreeRecursively(List<SysVillage> all, Long parentId) {
        return all.stream()
                .filter(v -> Objects.equals(v.getParentId(), parentId))
                .sorted(Comparator.comparing(SysVillage::getSortOrder,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(SysVillage::getId))
                .map(v -> {
                    v.setChildren(buildTreeRecursively(all, v.getId()));
                    return v;
                })
                .toList();
    }
}
