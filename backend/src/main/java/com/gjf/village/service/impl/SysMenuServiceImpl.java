package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gjf.village.common.enums.MenuTypeEnum;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.dto.MenuDTO;
import com.gjf.village.entity.SysMenu;
import com.gjf.village.entity.SysRoleMenu;
import com.gjf.village.entity.SysUserRole;
import com.gjf.village.mapper.SysMenuMapper;
import com.gjf.village.mapper.SysRoleMenuMapper;
import com.gjf.village.mapper.SysUserRoleMapper;
import com.gjf.village.service.SysMenuService;
import com.gjf.village.vo.RouterVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 菜单与路由服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysMenuServiceImpl implements SysMenuService {

    private final SysMenuMapper menuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMenuMapper roleMenuMapper;

    /** 顶层目录对应的前端组件 —— 后台的骨架布局(侧边栏 + 顶栏 + 内容区) */
    private static final String LAYOUT_COMPONENT = "Layout";

    @Override
    public List<RouterVO> buildRouters(Long userId) {
        List<Long> roleIds = listRoleIds(userId);
        if (roleIds.isEmpty()) {
            return List.of();
        }

        List<SysMenu> allMenus = menuMapper.selectMenusByRoleIds(roleIds);

        // 只保留目录和菜单 —— 按钮(F)不产生路由,它只贡献权限码
        List<SysMenu> routableMenus = allMenus.stream()
                .filter(m -> !MenuTypeEnum.BUTTON.getCode().equals(m.getMenuType()))
                .toList();

        return buildRouterTree(routableMenus, null);
    }

    @Override
    public List<SysMenu> listMenusByUserId(Long userId) {
        List<Long> roleIds = listRoleIds(userId);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return menuMapper.selectMenusByRoleIds(roleIds);
    }

    @Override
    public List<SysMenu> listAllMenus() {
        return menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getStatus, 1)
                .orderByAsc(SysMenu::getSortOrder, SysMenu::getId));
    }

    // ═══════════════════════════════════════════════════════════
    //  菜单维护
    // ═══════════════════════════════════════════════════════════

    @Override
    public List<SysMenu> listMenuTree() {
        // 不过滤 status:停用的菜单也得出现在管理页里,否则停用之后就再也启用不回来
        List<SysMenu> all = menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .orderByAsc(SysMenu::getSortOrder, SysMenu::getId));
        return buildMenuTree(all, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createMenu(MenuDTO dto) {
        validateType(dto.getMenuType());
        requireParentExists(dto.getParentId());

        SysMenu menu = new SysMenu();
        applyDto(menu, dto);
        menuMapper.insert(menu);
        return menu.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMenu(MenuDTO dto) {
        SysMenu exist = requireMenu(dto.getId());
        validateType(dto.getMenuType());

        // 上级不能是自己,也不能是自己的后代 —— 否则菜单树里会出现环,
        // 递归构树时直接栈溢出
        if (Objects.equals(dto.getParentId(), dto.getId())) {
            throw BusinessException.badRequest("上级菜单不能是自己");
        }
        if (dto.getParentId() != null && isDescendant(dto.getId(), dto.getParentId())) {
            throw BusinessException.badRequest("上级菜单不能是自己的子菜单");
        }
        requireParentExists(dto.getParentId());

        SysMenu menu = new SysMenu();
        menu.setId(exist.getId());
        applyDto(menu, dto);
        menuMapper.updateById(menu);
    }

    /**
     * 删除菜单。
     *
     * <p>有子菜单时拒绝 —— 直接级联删掉会把管理员没看见的一整棵子树一起带走,
     * 而且角色的授权关联也跟着悬空。让管理员先把子节点处理掉更稳妥。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMenu(Long id) {
        requireMenu(id);

        Long childCount = menuMapper.selectCount(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw BusinessException.badRequest(
                    "该菜单下还有 " + childCount + " 个子节点,请先删除子节点");
        }

        // 同步清理角色授权,避免留下指向不存在菜单的关联记录
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getMenuId, id));
        menuMapper.deleteById(id);
    }

    /** 把 DTO 的字段搬到实体上,新增和修改共用 */
    private void applyDto(SysMenu menu, MenuDTO dto) {
        menu.setParentId(dto.getParentId());
        menu.setMenuName(dto.getMenuName());
        menu.setMenuType(dto.getMenuType());
        menu.setPerms(dto.getPerms());
        menu.setPath(dto.getPath());
        menu.setComponent(dto.getComponent());
        menu.setIcon(dto.getIcon());
        menu.setSortOrder(dto.getSortOrder() == null ? 99 : dto.getSortOrder());
        menu.setVisible(dto.getVisible() == null ? 1 : dto.getVisible());
        menu.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
    }

    private void validateType(String menuType) {
        boolean ok = Arrays.stream(MenuTypeEnum.values())
                .anyMatch(t -> t.getCode().equals(menuType));
        if (!ok) {
            throw BusinessException.badRequest("菜单类型只能是 M(目录) / C(菜单) / F(按钮)");
        }
    }

    private void requireParentExists(Long parentId) {
        if (parentId == null) {
            return;
        }
        if (menuMapper.selectById(parentId) == null) {
            throw BusinessException.badRequest("上级菜单不存在");
        }
    }

    private SysMenu requireMenu(Long id) {
        if (id == null) {
            throw BusinessException.badRequest("菜单ID不能为空");
        }
        SysMenu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw BusinessException.notFound("菜单不存在");
        }
        return menu;
    }

    /**
     * {@code candidateId} 是否是 {@code menuId} 的后代。
     *
     * <p>顺着 parentId 往上爬,爬到根或者碰到自己为止。
     * 比"把整棵树展开再找"省事,而且不依赖树的构建结果。
     */
    private boolean isDescendant(Long menuId, Long candidateId) {
        Long cursor = candidateId;
        // 菜单层级很浅(最多三四层),限制深度只是防止数据异常时死循环
        for (int depth = 0; cursor != null && depth < 50; depth++) {
            if (cursor.equals(menuId)) {
                return true;
            }
            SysMenu parent = menuMapper.selectById(cursor);
            cursor = parent == null ? null : parent.getParentId();
        }
        return false;
    }

    /** 递归构建菜单树 */
    private List<SysMenu> buildMenuTree(List<SysMenu> menus, Long parentId) {
        return menus.stream()
                .filter(m -> Objects.equals(m.getParentId(), parentId))
                .sorted(Comparator.comparing(SysMenu::getSortOrder,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(SysMenu::getId))
                .peek(menu -> {
                    List<SysMenu> children = buildMenuTree(menus, menu.getId());
                    menu.setChildren(children.isEmpty() ? null : children);
                })
                .collect(Collectors.toList());
    }

    // ── 内部方法 ────────────────────────────────────────────────

    private List<Long> listRoleIds(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId)
                        .select(SysUserRole::getRoleId))
                .stream()
                .map(SysUserRole::getRoleId)
                .toList();
    }

    /**
     * 递归构建路由树。
     *
     * @param menus    已过滤掉按钮的菜单列表
     * @param parentId 当前层的父节点ID,null 表示顶层
     */
    private List<RouterVO> buildRouterTree(List<SysMenu> menus, Long parentId) {
        return menus.stream()
                .filter(m -> Objects.equals(m.getParentId(), parentId))
                .sorted(Comparator.comparing(SysMenu::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(SysMenu::getId))
                .map(menu -> {
                    RouterVO router = new RouterVO();
                    router.setPath(menu.getPath());
                    router.setName(buildRouteName(menu.getPath()));

                    List<RouterVO> children = buildRouterTree(menus, menu.getId());
                    boolean isDirectory = MenuTypeEnum.DIRECTORY.getCode().equals(menu.getMenuType());

                    if (isDirectory) {
                        // 目录用布局组件作为容器,子菜单渲染在它的 <router-view> 里
                        router.setComponent(LAYOUT_COMPONENT);
                        router.setChildren(children);
                    } else if (!children.isEmpty()) {
                        // 极少数情况:菜单下还挂了子菜单。按目录处理
                        router.setComponent(LAYOUT_COMPONENT);
                        router.setChildren(children);
                    } else {
                        router.setComponent(menu.getComponent());
                    }

                    RouterVO.Meta meta = new RouterVO.Meta();
                    meta.setTitle(menu.getMenuName());
                    meta.setIcon(menu.getIcon());
                    meta.setHidden(!Integer.valueOf(1).equals(menu.getVisible()));
                    meta.setPerms(menu.getPerms());
                    router.setMeta(meta);

                    return router;
                })
                .collect(Collectors.toList());
    }

    /**
     * 由路径生成路由名。
     *
     * <p>Vue Router 要求 name 全局唯一。用路径派生能保证唯一(路径本身是唯一的),
     * 同时保持可读 —— 比用菜单ID拼出来的 {@code Menu_101} 好排查得多。
     *
     * <p>例:/admin/audit/realname → AdminAuditRealname
     */
    private String buildRouteName(String path) {
        if (path == null || path.isBlank()) {
            return "Unnamed";
        }
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isBlank()) {
                continue;
            }
            // 去掉连字符并把每段首字母大写
            for (String part : segment.split("-")) {
                if (!part.isBlank()) {
                    sb.append(Character.toUpperCase(part.charAt(0)));
                    if (part.length() > 1) {
                        sb.append(part.substring(1));
                    }
                }
            }
        }
        return sb.isEmpty() ? "Unnamed" : sb.toString();
    }
}
