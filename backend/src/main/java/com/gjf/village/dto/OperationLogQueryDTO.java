package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.time.LocalDate;

/**
 * 操作日志查询条件。
 *
 * <p><b>刻意没有 villageId 字段。</b>{@code sys_operation_log} 表里没有村组列 ——
 * 日志记的是"谁在什么时候做了什么",而一次操作可能跨村(比如超管批量导出)。
 * 好在种子数据里「系统管理」整个目录只授给了超管,所以这个页面天然是超管专属,
 * 不存在"村管看到别村日志"的问题。<b>这是靠 RBAC 的菜单授权挡住的,
 * 不是靠数据级权限</b>——所以这里要写清楚,免得以后有人把 log:list 授给村管时
 * 以为数据级过滤会自动生效。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OperationLogQueryDTO extends BaseQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 操作人用户名,模糊匹配 */
    private String username;

    /** 模块名,精确匹配(下拉选) */
    private String module;

    /** 状态:1成功 0失败。为空则全部 */
    private Integer status;

    /** 起始日期(含) */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    /** 结束日期(含) */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;
}
