package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.dto.AnnouncementQueryDTO;
import com.gjf.village.entity.BizAnnouncement;
import com.gjf.village.vo.AnnouncementVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 公告 Mapper。
 */
@Mapper
public interface BizAnnouncementMapper extends BaseMapper<BizAnnouncement> {

    /**
     * 公告列表。前台与后台<b>共用这一条</b>。
     *
     * <p>两条口径互斥,由 XML 里的 {@code <choose>} 保证结构上只能走一条:
     * <ul>
     *   <li>{@code includeGlobal = true} —— 前台:「全平台公告 + 我的村」,
     *       状态被硬编码为「已发布」。</li>
     *   <li>否则 —— 后台:村组白名单来自 {@code DataScopeHelper} 注入的
     *       {@code villageIds},可按状态筛选。</li>
     * </ul>
     */
    IPage<AnnouncementVO> selectPage(IPage<AnnouncementVO> page,
                                     @Param("q") AnnouncementQueryDTO query);

    /**
     * 公告详情,<b>带正文</b>。
     *
     * <p>列表接口刻意不带正文 —— 一条公告的正文是 LONGTEXT 富文本,
     * 十条就是几十 KB 的传输,而列表页一个字都不显示。这类"表里有一列
     * 但列表用不上"的情况,投影比传输后再丢掉划算。
     *
     * <p>注意这条查询<b>不带任何可见性条件</b>:它按 id 取,返回什么
     * 由调用方判断。前台必须自己校验「已发布 + 全平台或本村」——
     * 这道校验在 Service 里,漏了就变成"猜 id 能读别村公告"。
     */
    AnnouncementVO selectDetail(@Param("id") Long id);

    /** 浏览量 +1,数据库端自增(理由同留言板的点赞数) */
    int increaseViewCount(@Param("id") Long id);
}
