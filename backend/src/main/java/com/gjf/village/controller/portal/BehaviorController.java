package com.gjf.village.controller.portal;

import com.gjf.village.common.result.Result;
import com.gjf.village.dto.BehaviorReportDTO;
import com.gjf.village.entity.AiUserBehavior;
import com.gjf.village.service.AiBehaviorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台 —— 行为上报。
 *
 * <h3>只上报"浏览",别的行为在服务端记</h3>
 *
 * <p>收藏、下单、评价这三件事在服务端**必然发生**(不写库就做不成),
 * 所以顺手记一笔最可靠。而浏览不一样:后端只知道"详情接口被调了一次",
 * 不知道用户在这页待了多久、是从哪儿点进来的 —— 那两样只有前端知道。
 * 所以只有浏览让前端上报。
 *
 * <h3>这个接口为什么在免登录白名单里</h3>
 *
 * <p>游客浏览详情页也会调它。不放行的话前端会收到 401、
 * 控制台里多一条红字,而它其实什么也不该做 —— 游客不采集(隐私 + 表结构所限),
 * 让服务层静默忽略比让前端先判断"我登录了吗"干净得多:
 * <b>前端不该为了一个旁路功能去关心登录态。</b>
 *
 * <p>放行它没有越权风险:{@code user_id} 来自 token、从不接受请求体传入;
 * 游客调用时服务层直接返回。最坏情况是有人拿自己的账号刷一堆浏览记录 ——
 * 那只会把他自己的推荐带偏,刷不出任何实际收益。
 */
@RestController
@RequestMapping("/api/behavior")
@RequiredArgsConstructor
@Tag(name = "行为上报", description = "前端上报浏览行为(停留时长与来源)")
public class BehaviorController {

    private final AiBehaviorService behaviorService;

    @PostMapping("/view")
    @Operation(summary = "上报一次浏览",
            description = "登录时记录;游客静默忽略。source 传 4 表示来自推荐位,"
                    + "后台的推荐位点击率就是靠它统计的")
    public Result<Void> view(@Valid @RequestBody BehaviorReportDTO dto) {
        behaviorService.viewed(
                dto.getResourceId(),
                dto.getStaySeconds(),
                // 不传来源按"详情页"(直接打开链接或刷新)算
                dto.getSource() == null ? AiUserBehavior.SOURCE_DETAIL : dto.getSource());
        return Result.success("已记录", null);
    }
}
