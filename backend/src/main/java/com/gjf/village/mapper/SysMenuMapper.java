package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gjf.village.entity.SysMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 菜单 Mapper。
 */
@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {

    /**
     * 按角色ID列表查询其被授权的全部菜单(含目录、菜单、按钮)。
     *
     * <p>用 DISTINCT:一个用户可能有多个角色,而多个角色可能都被授予了同一个菜单,
     * 不去重会出现重复的菜单项。
     *
     * <p>只查 status = 1 的:被停用的菜单不应该出现在任何人的侧边栏里。
     */
    @Select("""
            <script>
            SELECT DISTINCT m.*
            FROM sys_menu m
            JOIN sys_role_menu rm ON rm.menu_id = m.id
            WHERE rm.role_id IN
                <foreach collection="roleIds" item="roleId" open="(" separator="," close=")">
                    #{roleId}
                </foreach>
              AND m.status = 1
            ORDER BY m.sort_order, m.id
            </script>
            """)
    List<SysMenu> selectMenusByRoleIds(@Param("roleIds") List<Long> roleIds);
}
