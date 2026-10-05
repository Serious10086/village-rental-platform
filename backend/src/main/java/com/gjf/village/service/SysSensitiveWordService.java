package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.SensitiveWordDTO;
import com.gjf.village.dto.SensitiveWordQueryDTO;
import com.gjf.village.entity.SysSensitiveWord;

/**
 * 敏感词管理服务。
 *
 * <p>增删改后会自动重新加载内存中的 DFA 词库,
 * 保证后台改完立刻生效,不用重启服务。
 */
public interface SysSensitiveWordService {

    PageResult<SysSensitiveWord> page(SensitiveWordQueryDTO query);

    /** 新增单条 */
    void add(SensitiveWordDTO dto);

    /** 修改(词、分类、处理方式、启用状态) */
    void update(SensitiveWordDTO dto);

    /** 删除 */
    void delete(Long id);

    /** 批量导入。按换行或逗号分隔,忽略已存在的词 */
    int batchImport(String text, String category, Integer action);

    /** 当前词库大小 */
    int size();
}
