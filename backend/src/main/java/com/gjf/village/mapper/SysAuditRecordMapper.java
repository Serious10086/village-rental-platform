package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.dto.AuditQueryDTO;
import com.gjf.village.entity.SysAuditRecord;
import com.gjf.village.vo.AuditRecordVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 通用审核流水 Mapper。
 *
 * <p>列表查询写在 XML 里(见 resources/mapper/SysAuditRecordMapper.xml),
 * 因为它要把审核流水和多张业务表 LEFT JOIN 在一起摊平成一行 ——
 * 这种 SQL 用条件构造器写出来基本没法读。
 */
@Mapper
public interface SysAuditRecordMapper extends BaseMapper<SysAuditRecord> {

    /**
     * 审核待办 / 记录分页查询。
     *
     * @param villageIds 数据级权限的村组范围;为空表示不限(超管)
     */
    IPage<AuditRecordVO> selectAuditPage(IPage<AuditRecordVO> page,
                                         @Param("q") AuditQueryDTO query);

    /**
     * 单条审核详情(含完整身份证号)。
     */
    AuditRecordVO selectAuditDetail(@Param("id") Long id);

    /**
     * 统计待审核数量,供后台工作台的待办角标使用。
     *
     * @param villageIds 村组范围;null 表示不限
     * @param bizType    业务类型;null 表示全部类型
     */
    long countPending(@Param("villageIds") List<Long> villageIds,
                      @Param("bizType") Integer bizType);
}
