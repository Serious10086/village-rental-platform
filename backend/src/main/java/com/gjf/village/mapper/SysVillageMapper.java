package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gjf.village.entity.SysVillage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 村组 Mapper。
 */
@Mapper
public interface SysVillageMapper extends BaseMapper<SysVillage> {

    /**
     * 统计挂在某村组下的用户数 —— <b>含已逻辑删除的</b>。
     *
     * <p>为什么不能直接用 {@code sysUserMapper.selectCount(...)}:
     * {@code SysUser} 上有 {@code @TableLogic},MyBatis-Plus 会自动补上
     * {@code deleted = 0}。而软删除只是把标记位置了 1,<b>行还在、外键还挂着</b>,
     * 数据库层面依然禁止删掉被引用的村组。
     *
     * <p>数漏的直接后果:删除放行 → 在数据库撞上外键约束 → 报 500,
     * 而管理员看到的是一句"系统繁忙",完全不知道该去改什么。
     */
    @Select("SELECT COUNT(*) FROM sys_user WHERE village_id = #{villageId}")
    long countUsersReferencing(@Param("villageId") Long villageId);

    /**
     * 统计挂在某村组下的资源数 —— <b>含已逻辑删除的</b>。原因同上。
     */
    @Select("SELECT COUNT(*) FROM biz_resource WHERE village_id = #{villageId}")
    long countResourcesReferencing(@Param("villageId") Long villageId);
}
