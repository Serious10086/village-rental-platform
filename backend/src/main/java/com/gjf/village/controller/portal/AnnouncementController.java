package com.gjf.village.controller.portal;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.AnnouncementQueryDTO;
import com.gjf.village.service.BizAnnouncementService;
import com.gjf.village.vo.AnnouncementVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台公告接口(免登录)。
 *
 * <p>公告要在<b>登录之前</b>就能看 —— 一个刚打开平台的访客,
 * 先看到的是这个村在做什么,而不是一个登录框。所以两个接口都在白名单里。
 *
 * <p>没登录的人只看得到「全平台公告」;登录之后,
 * 他所属村组的公告会自动出现在同一条列表里。这个差别不靠分支实现,
 * 而是由 {@code villageId} 为空时 SQL 里那条
 * {@code village_id IS NULL OR village_id = null} 自然退化出来的。
 */
@RestController
@RequestMapping("/api/announcement")
@RequiredArgsConstructor
@Tag(name = "公告", description = "村级公告与全平台公告的浏览")
public class AnnouncementController {

    private final BizAnnouncementService announcementService;

    @GetMapping("/list")
    @Operation(summary = "公告列表",
            description = "全平台公告 + 当前用户所属村的公告。"
                    + "未登录时只有全平台公告。置顶的排在最前")
    public Result<PageResult<AnnouncementVO>> list(AnnouncementQueryDTO query) {
        return Result.success(announcementService.pageFront(query));
    }

    @GetMapping("/detail/{id}")
    @Operation(summary = "公告详情",
            description = "带正文,并把浏览量 +1。看不到的公告返回 404 而不是 403 "
                    + "—— 403 会暴露「这条公告存在」这个信息")
    public Result<AnnouncementVO> detail(@PathVariable Long id) {
        return Result.success(announcementService.detailFront(id));
    }
}
