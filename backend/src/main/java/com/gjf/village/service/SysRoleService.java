package com.gjf.village.service;

import com.gjf.village.dto.RoleDTO;
import com.gjf.village.entity.SysRole;

import java.util.List;

/**
 * 角色服务。
 */
public interface SysRoleService {

    /** 全部角色,按 sort_order 排 */
    List<SysRole> listAll();

    /** 新增角色,返回新角色ID */
    Long create(RoleDTO dto);

    void update(RoleDTO dto);

    void delete(Long id);

    /** 某角色已授权的菜单ID(含按钮),给授权树回填勾选用 */
    List<Long> listMenuIds(Long roleId);

    /** 保存角色授权:全量覆盖,不是增量追加 */
    void assignMenus(Long roleId, List<Long> menuIds);
}
