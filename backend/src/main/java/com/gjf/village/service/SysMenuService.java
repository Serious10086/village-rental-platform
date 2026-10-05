package com.gjf.village.service;

import com.gjf.village.dto.MenuDTO;
import com.gjf.village.entity.SysMenu;
import com.gjf.village.vo.RouterVO;

import java.util.List;

/**
 * 菜单与路由服务。
 */
public interface SysMenuService {

    /**
     * 构建某用户可见的路由树。
     *
     * <p>只包含 menu_type 为 M(目录)和 C(菜单)的节点 ——
     * F(按钮)不产生路由,它只贡献权限码。
     */
    List<RouterVO> buildRouters(Long userId);

    /**
     * 查询某用户被授权可见的菜单(含按钮),用于提取权限码。
     */
    List<SysMenu> listMenusByUserId(Long userId);

    /**
     * 查询全部菜单(超管维护菜单树用)。
     */
    List<SysMenu> listAllMenus();

    /**
     * 全部菜单的树形结构 —— 菜单管理页和角色授权树都用它。
     *
     * <p>与 {@link #listAllMenus()} 的区别:这里是树,而且<b>不过滤 status</b> ——
     * 停用的菜单也得出现在管理页里,否则停用之后就再也启用不回来了。
     */
    List<SysMenu> listMenuTree();

    /** 新增菜单,返回新菜单ID */
    Long createMenu(MenuDTO dto);

    void updateMenu(MenuDTO dto);

    void deleteMenu(Long id);
}
