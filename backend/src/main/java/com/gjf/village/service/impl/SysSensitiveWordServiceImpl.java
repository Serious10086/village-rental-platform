package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.util.SensitiveWordUtil;
import com.gjf.village.dto.SensitiveWordDTO;
import com.gjf.village.dto.SensitiveWordQueryDTO;
import com.gjf.village.entity.SysSensitiveWord;
import com.gjf.village.mapper.SysSensitiveWordMapper;
import com.gjf.village.service.SysSensitiveWordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 敏感词管理服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysSensitiveWordServiceImpl implements SysSensitiveWordService {

    private final SysSensitiveWordMapper wordMapper;
    private final SensitiveWordUtil sensitiveWordUtil;

    /** 单次批量导入的上限。防止管理员误传一个几万行的文件把内存词库撑爆 */
    private static final int MAX_IMPORT = 2000;

    @Override
    public PageResult<SysSensitiveWord> page(SensitiveWordQueryDTO query) {
        LambdaQueryWrapper<SysSensitiveWord> wrapper = new LambdaQueryWrapper<SysSensitiveWord>()
                .like(StringUtils.hasText(query.getKeyword()), SysSensitiveWord::getWord, query.getKeyword())
                .eq(StringUtils.hasText(query.getCategory()), SysSensitiveWord::getCategory, query.getCategory())
                .eq(query.getStatus() != null, SysSensitiveWord::getStatus, query.getStatus())
                .orderByDesc(SysSensitiveWord::getId);

        Page<SysSensitiveWord> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<SysSensitiveWord> result = wordMapper.selectPage(page, wrapper);
        return PageResult.of(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(SensitiveWordDTO dto) {
        String word = dto.getWord().trim();
        if (exists(word)) {
            throw BusinessException.badRequest("敏感词「" + word + "」已存在");
        }

        SysSensitiveWord entity = new SysSensitiveWord();
        entity.setWord(word);
        entity.setCategory(StringUtils.hasText(dto.getCategory()) ? dto.getCategory() : "广告");
        entity.setAction(dto.getAction() == null
                ? SysSensitiveWord.ACTION_REPLACE : dto.getAction());
        entity.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        wordMapper.insert(entity);

        // 改完立刻生效,不用重启服务
        sensitiveWordUtil.reload();
        log.info("新增敏感词: {}", word);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SensitiveWordDTO dto) {
        if (dto.getId() == null) {
            throw BusinessException.badRequest("缺少敏感词ID");
        }
        SysSensitiveWord existing = wordMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BusinessException(com.gjf.village.common.result.ResultCode.NOT_FOUND,
                    "敏感词不存在");
        }

        String word = dto.getWord().trim();
        // 改词时也要查重(排除自己)
        if (!word.equals(existing.getWord()) && exists(word)) {
            throw BusinessException.badRequest("敏感词「" + word + "」已存在");
        }

        SysSensitiveWord update = new SysSensitiveWord();
        update.setId(dto.getId());
        update.setWord(word);
        update.setCategory(dto.getCategory());
        update.setAction(dto.getAction());
        update.setStatus(dto.getStatus());
        wordMapper.updateById(update);

        sensitiveWordUtil.reload();
        log.info("修改敏感词: id={}, word={}", dto.getId(), word);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) {
            throw BusinessException.badRequest("缺少敏感词ID");
        }
        wordMapper.deleteById(id);
        sensitiveWordUtil.reload();
        log.info("删除敏感词: id={}", id);
    }

    /**
     * 批量导入。
     *
     * <p>分隔符同时支持换行和逗号 —— 管理员可能从 Excel 复制(制表符/换行),
     * 也可能手打一串逗号分隔的词。用 {@code [\n\r,，]} 一次切干净,
     * 免得因为分隔符不对而导入失败。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchImport(String text, String category, Integer action) {
        if (!StringUtils.hasText(text)) {
            throw BusinessException.badRequest("导入内容为空");
        }

        Set<String> words = new LinkedHashSet<>();
        for (String part : text.split("[\\n\\r,，]")) {
            String w = part.trim();
            if (!w.isEmpty() && w.length() <= 50) {
                words.add(w);
            }
        }

        if (words.isEmpty()) {
            throw BusinessException.badRequest("没有解析出有效的敏感词");
        }
        if (words.size() > MAX_IMPORT) {
            throw BusinessException.badRequest(
                    "单次最多导入 " + MAX_IMPORT + " 个词,当前 " + words.size() + " 个");
        }

        String cat = StringUtils.hasText(category) ? category : "广告";
        Integer act = action == null ? SysSensitiveWord.ACTION_REPLACE : action;

        int inserted = 0;
        for (String word : words) {
            // 已存在的跳过而不是报错 —— 批量导入时因为个别重复词整批失败,
            // 会让管理员反复删改重试,体验很差
            if (exists(word)) {
                continue;
            }
            SysSensitiveWord entity = new SysSensitiveWord();
            entity.setWord(word);
            entity.setCategory(cat);
            entity.setAction(act);
            entity.setStatus(1);
            wordMapper.insert(entity);
            inserted++;
        }

        sensitiveWordUtil.reload();
        log.info("批量导入敏感词: 解析 {} 个,新增 {} 个", words.size(), inserted);
        return inserted;
    }

    @Override
    public int size() {
        return sensitiveWordUtil.size();
    }

    // ── 内部方法 ────────────────────────────────────────────────

    private boolean exists(String word) {
        return wordMapper.selectCount(new LambdaQueryWrapper<SysSensitiveWord>()
                .eq(SysSensitiveWord::getWord, word)) > 0;
    }
}
