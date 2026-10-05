package com.gjf.village.controller.admin;

import com.gjf.village.annotation.Log;
import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.AnnouncementDTO;
import com.gjf.village.dto.AnnouncementQueryDTO;
import com.gjf.village.service.BizAnnouncementService;
import com.gjf.village.vo.AnnouncementVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 后台 —— 公告管理。
 *
 * <p>五个能力:<b>看列表</b> / <b>发布新建</b> / <b>编辑</b> / <b>发布已有</b> /
 * <b>下架</b>。对应 {@code sys_menu} 里挂在菜单 601 下的四个权限码:
 * {@code announcement:list}、{@code announcement:add}、
 * {@code announcement:edit}、{@code announcement:offline}
 * (「发布新建」和「发布已有」共用 {@code announcement:add})。
 *
 * <p><b>权限码和接口是成对长出来的,顺序不能反。</b>
 * 权限表定义的是系统对外承诺的能力,代码里多出一个权限表没定义的按钮,
 * 结果就是那个按钮谁都点不到(前端 {@code v-hasPerm} 按权限码过滤,
 * 没有任何角色持有这个码),既不能演示也不能用 —— 所以是先加菜单行
 * 再写接口。反过来的毛病更隐蔽:接口写好了但没有权限码保护,
 * 那是一条谁都能调的越权入口。
 *
 * <p>「发布已有」复用 {@code announcement:add},没有再单独造一个码:
 * 新建一条和把一条草稿发出去,在业务上是同一件事(让公告进入可见状态)。
 * 这也是 {@code evaluation:hide} 一个码管「隐藏」和「恢复」两个方向的同一套做法。
 */
@RestController
@RequestMapping("/api/admin/announcement")
@RequiredArgsConstructor
@Tag(name = "后台-公告管理", description = "本村公告列表、发布、编辑与下架")
public class AdminAnnouncementController {

    private final BizAnnouncementService announcementService;

    @GetMapping("/list")
    @RequiresPerm("announcement:list")
    @Operation(summary = "公告列表",
            description = "村级管理员只能看到本村公告;超级管理员看到全部。"
                    + "可按状态(status)和类型(noticeType)筛选 —— "
                    + "草稿和下架状态只有这里查得到")
    public Result<PageResult<AnnouncementVO>> list(AnnouncementQueryDTO query) {
        return Result.success(announcementService.pageAdmin(query));
    }

    @GetMapping("/{id}")
    @RequiresPerm("announcement:list")
    @Operation(summary = "公告详情",
            description = "带正文。**草稿和已下架的也能读** —— 编辑时要用;"
                    + "前台的详情接口只认「已发布」,拿草稿去问它是 404")
    public Result<AnnouncementVO> detail(@PathVariable Long id) {
        return Result.success(announcementService.detailAdmin(id));
    }

    @PostMapping("/publish")
    @RequiresPerm("announcement:add")
    @Log(module = "公告管理", operation = "发布公告")
    @Operation(summary = "新建公告(发布 / 存草稿)",
            description = "asDraft=true 存成草稿(不发通知、前台看不到);"
                    + "不传或传 false 则直接发布并给能看到它的人发站内通知。"
                    + "村级管理员新建的公告挂在本村,「系统公告」类型仅超级管理员可选")
    public Result<Long> publish(@Valid @RequestBody AnnouncementDTO dto) {
        boolean asDraft = Boolean.TRUE.equals(dto.getAsDraft());
        return Result.success(asDraft ? "已存为草稿" : "公告已发布",
                announcementService.publish(dto));
    }

    @PutMapping("/{id}")
    @RequiresPerm("announcement:edit")
    @Log(module = "公告管理", operation = "编辑公告")
    @Operation(summary = "编辑公告",
            description = "只改标题/正文/封面/类型/置顶,不改归属、不改状态、不发通知。"
                    + "村管只能编辑本村公告;全平台公告只有超级管理员能改")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AnnouncementDTO dto) {
        announcementService.update(id, dto);
        return Result.success("公告已更新", null);
    }

    @PutMapping("/{id}/publish")
    @RequiresPerm("announcement:add")
    @Log(module = "公告管理", operation = "发布已有公告")
    @Operation(summary = "发布已有的公告",
            description = "把草稿或已下架的公告发出去,会重新盖发布时间并重发通知。"
                    + "已经是发布状态的再调一次会返回 400")
    public Result<Void> publishDraft(@PathVariable Long id) {
        announcementService.publishDraft(id);
        return Result.success("公告已发布", null);
    }

    @PutMapping("/{id}/offline")
    @RequiresPerm("announcement:offline")
    @Log(module = "公告管理", operation = "下架公告")
    @Operation(summary = "下架公告",
            description = "下架后前台列表和详情都读不到,后台仍然查得到(可以再编辑后重新发布)。"
                    + "不发通知 —— 已经推出去的通知收不回来")
    public Result<Void> offline(@PathVariable Long id) {
        announcementService.offline(id);
        return Result.success("公告已下架", null);
    }
}
