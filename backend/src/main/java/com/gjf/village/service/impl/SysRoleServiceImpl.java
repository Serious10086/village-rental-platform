package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.dto.RoleDTO;
import com.gjf.village.entity.SysRole;
import com.gjf.village.entity.SysRoleMenu;
import com.gjf.village.entity.SysUserRole;
import com.gjf.village.mapper.SysMenuMapper;
import com.gjf.village.mapper.SysRoleMapper;
import com.gjf.village.mapper.SysRoleMenuMapper;
import com.gjf.village.mapper.SysUserRoleMapper;
import com.gjf.village.service.SysRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 角色服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysRoleServiceImpl implements SysRoleService {

    /**
     * 内置角色 —— 它们的编码被代码里硬引用(至少数据级权限靠 VILLAGE_ADMIN 判定),
     * 所以不许改编码、不许删。
     *
     * <p>为什么按编码而不是按 id 判断:id 是自增值,换个环境灌一次种子就可能变;
     * 编码是业务标识,它才有稳定的语义。
     */
    private static final Set<String> BUILT_IN_ROLE_CODES =
            Set.of("SUPER_ADMIN", "VILLAGE_ADMIN", "VILLAGER", "BUYER");

    private static final int STATUS_ENABLED = 1;

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysMenuMapper menuMapper;

    @Override
    public List<SysRole> listAll() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .orderByAsc(SysRole::getSortOrder, SysRole::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(RoleDTO dto) {
        if (existsCode(dto.getRoleCode(), null)) {
            throw BusinessException.badRequest("角色编码「" + dto.getRoleCode() + "」已存在");
        }

        SysRole role = new SysRole();
        role.setRoleCode(dto.getRoleCode());
        role.setRoleName(dto.getRoleName());
        role.setDataScope(dto.getDataScope() == null ? 4 : dto.getDataScope());
        role.setSortOrder(dto.getSortOrder() == null ? 99 : dto.getSortOrder());
        role.setStatus(dto.getStatus() == null ? STATUS_ENABLED : dto.getStatus());
        role.setRemark(dto.getRemark());

        roleMapper.insert(role);
        return role.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(RoleDTO dto) {
        SysRole exist = requireRole(dto.getId());

        // 内置角色可以改名、改备注,但不许改编码 —— 编码变了,
        // 代码里按编码做的判断(比如数据级权限)会静默失效
        if (BUILT_IN_ROLE_CODES.contains(exist.getRoleCode())
                && !exist.getRoleCode().equals(dto.getRoleCode())) {
            throw BusinessException.badRequest("内置角色的编码不允许修改");
        }
        if (existsCode(dto.getRoleCode(), dto.getId())) {
            throw BusinessException.badRequest("角色编码「" + dto.getRoleCode() + "」已存在");
        }

        SysRole role = new SysRole();
        role.setId(dto.getId());
        role.setRoleCode(dto.getRoleCode());
        role.setRoleName(dto.getRoleName());
        role.setDataScope(dto.getDataScope());
        role.setSortOrder(dto.getSortOrder());
        role.setStatus(dto.getStatus());
        role.setRemark(dto.getRemark());

        roleMapper.updateById(role);
    }

    /**
     * 删除角色。
     *
     * <p>两条拦截:内置角色不能删(删了系统直接不可用);
     * 还有账号挂着这个角色的也不能删 —— 那些账号会变成"有账号、没权限"的
     * 半残状态,登进去是一屏空白菜单,排查起来很费劲。宁可让管理员先去改绑。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysRole role = requireRole(id);

        if (BUILT_IN_ROLE_CODES.contains(role.getRoleCode())) {
            throw BusinessException.badRequest("内置角色不允许删除");
        }

        Long refCount = userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getRoleId, id));
        if (refCount != null && refCount > 0) {
            throw BusinessException.badRequest(
                    "还有 " + refCount + " 个账号使用该角色,请先解除关联再删除");
        }

        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getRoleId, id));
        roleMapper.deleteById(id);
    }

    @Override
    public List<Long> listMenuIds(Long roleId) {
        requireRole(roleId);
        return roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .eq(SysRoleMenu::getRoleId, roleId)
                        .select(SysRoleMenu::getMenuId))
                .stream()
                .map(SysRoleMenu::getMenuId)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    /**
     * 保存角色授权 —— <b>全量覆盖</b>。
     *
     * <p>不是增量追加:前端交上来的是一棵"最终应该长这样"的勾选树,
     * 追加的话取消勾选永远删不掉。所以要先把旧的清空再写新的。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignMenus(Long roleId, List<Long> menuIds) {
        SysRole role = requireRole(roleId);

        // 超级管理员必须保留全部菜单。一旦它的授权被改窄,
        // 就再也没人能进"角色管理"把权限改回来 —— 系统被自己锁死
        if ("SUPER_ADMIN".equals(role.getRoleCode())) {
            throw BusinessException.badRequest("超级管理员的权限不可修改");
        }

        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getRoleId, roleId));

        if (menuIds == null || menuIds.isEmpty()) {
            log.info("角色 {} 的菜单授权被清空", roleId);
            return;
        }

        // 过滤掉不存在的菜单ID:前端传了脏数据时,与其插入一堆外键悬空的关联,
        // 不如按实际存在的菜单来授权
        List<Long> validIds = menuMapper.selectBatchIds(menuIds.stream().distinct().toList())
                .stream()
                .map(m -> m.getId())
                .toList();

        for (Long menuId : validIds) {
            SysRoleMenu rm = new SysRoleMenu();
            rm.setRoleId(roleId);
            rm.setMenuId(menuId);
            roleMenuMapper.insert(rm);
        }

        // 菜单是缓存在每个登录用户的 profile 里的,改了授权不会立刻反映到
        // 已经登录的人身上 —— 他们需要重新登录。这里记一条日志便于排查
        log.info("角色 {} 的菜单授权已更新,共 {} 项", roleId, validIds.size());
    }

    // ── 内部方法 ────────────────────────────────────────────────

    private SysRole requireRole(Long id) {
        if (id == null) {
            throw BusinessException.badRequest("角色ID不能为空");
        }
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw BusinessException.notFound("角色不存在");
        }
        return role;
    }

    /** 角色编码是否已被占用。excludeId 用于"编辑时排除自己" */
    private boolean existsCode(String roleCode, Long excludeId) {
        return roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, roleCode)
                .ne(excludeId != null, SysRole::getId, excludeId)) > 0;
    }
}
