package com.gjf.village.controller.portal;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.CreditQueryDTO;
import com.gjf.village.service.CreditService;
import com.gjf.village.vo.CreditRecordVO;
import com.gjf.village.vo.CreditSummaryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台信用接口 —— 「我的信用」页面。
 *
 * <p>只提供查自己的信用。原因很直接:信用流水里写着扣分的具体原因
 * ("恶意损坏设备"这类),这是相当敏感的信息,不该提供"查别人信用流水"的入口。
 * 别人信用分的高低,在订单详情页能看到一个数字就够了。
 */
@RestController
@RequestMapping("/api/credit")
@RequiredArgsConstructor
@Tag(name = "信用", description = "我的信用分、信用等级、信用变动流水")
public class CreditController {

    private final CreditService creditService;

    @GetMapping("/mine")
    @Operation(summary = "我的信用概览",
            description = "含信用分、等级,以及履约率/资源完好率/评价均分三个比率 "
                    + "(直接对应需求原文的「基于交易履约、资源完好率、用户评价」)")
    public Result<CreditSummaryVO> mine() {
        return Result.success(creditService.getMyCredit());
    }

    @GetMapping("/records")
    @Operation(summary = "我的信用变动流水",
            description = "每一笔变更都带前值、后值和原因 —— 用户问「我为什么被扣分」时有据可查")
    public Result<PageResult<CreditRecordVO>> records(CreditQueryDTO query) {
        return Result.success(creditService.pageMyRecords(query));
    }
}
