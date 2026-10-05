package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.dto.OperationLogQueryDTO;
import com.gjf.village.entity.SysOperationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 操作日志 Mapper。
 *
 * <p>写入走 {@code BaseMapper.insert};列表要按日期区间和几列做筛选,
 * 所以单独写一条。
 */
@Mapper
public interface SysOperationLogMapper extends BaseMapper<SysOperationLog> {

    /** 日志列表。按时间倒序 —— 看日志永远是"先看最近发生了什么" */
    IPage<SysOperationLog> selectLogPage(IPage<SysOperationLog> page,
                                         @Param("q") OperationLogQueryDTO query);

    /**
     * 日志里出现过的模块名去重列表。
     *
     * <p>给前端的"模块"下拉用。写死一份模块清单在前端更简单,
     * 但那样每次新增一个 {@code @Log(module = "…")} 都得回去改前端 ——
     * 而漏改的表现只是"筛选项里少一个",不会报错,拖到有人发现为止。
     * 从数据里取就没有这个问题。
     */
    List<String> selectDistinctModules();
}
