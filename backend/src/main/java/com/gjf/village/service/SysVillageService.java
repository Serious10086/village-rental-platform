package com.gjf.village.service;

import com.gjf.village.dto.VillageDTO;
import com.gjf.village.entity.SysVillage;

import java.util.List;

/**
 * 村组服务。
 */
public interface SysVillageService {

    /**
     * 查询启用的村组列表。
     *
     * <p>用于注册页的村组下拉框 —— 这是<b>公开接口</b>,
     * 因为用户还没有账号就得先选村组。
     * 只返回村组名称和ID,不涉及任何敏感信息。
     */
    List<SysVillage> listEnabled();

    /**
     * 构建两级村组树(乡镇 → 村)。
     */
    List<SysVillage> buildTree();

    /**
     * 全部村组的树形结构(后台管理用)。
     *
     * <p>与 {@link #buildTree()} 的区别:<b>不过滤 status</b> ——
     * 停用的村组也得出现在管理页里,否则停用之后就再也启用不回来。
     */
    List<SysVillage> listTree();

    /** 新增村组,返回新ID */
    Long createVillage(VillageDTO dto);

    void updateVillage(VillageDTO dto);

    void deleteVillage(Long id);
}
