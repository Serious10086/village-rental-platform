package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.EvaluationCreateDTO;
import com.gjf.village.dto.EvaluationQueryDTO;
import com.gjf.village.dto.EvaluationReplyDTO;
import com.gjf.village.vo.EvaluationVO;

/**
 * 评价服务。
 *
 * <p>评价是整个信用体系的数据来源之一:{@code v_user_credit_summary} 里
 * 的"评价均分"和"资源完好率"两列都算在这张表上。所以评价不是"给个好评
 * 乐呵一下"的装饰功能,它是信用分口径的一半。
 */
public interface BizEvaluationService {

    /**
     * 提交评价。
     *
     * <p>本方法是事务边界:写评价 + 调整被评价人信用分 + 发通知,
     * 三件事要么都成、要么都不成。只写评价不加分,用户的信用分涨不上去;
     * 只加分不写评价,分数就成了一笔无据可查的糊涂账。
     *
     * @return 评价ID
     */
    Long create(EvaluationCreateDTO dto);

    /** 被评价人回复(差评的申诉通道,只能回复一次) */
    void reply(Long id, EvaluationReplyDTO dto);

    // ── 查询 ────────────────────────────────────────────────────

    /** 我收到的评价 */
    PageResult<EvaluationVO> pageReceived(EvaluationQueryDTO query);

    /** 我发出的评价 */
    PageResult<EvaluationVO> pageSent(EvaluationQueryDTO query);

    /** 某个资源下的评价(前台公开,资源详情页展示) */
    PageResult<EvaluationVO> pageByResource(EvaluationQueryDTO query);

    /** 某笔订单的评价情况 —— 订单详情页据此显示"待评价"还是"已评价" */
    PageResult<EvaluationVO> listByOrder(Long orderId);

    // ── 后台 ────────────────────────────────────────────────────

    /** 后台评价管理(受数据级权限约束,能看到被隐藏的) */
    PageResult<EvaluationVO> pageAdmin(EvaluationQueryDTO query);

    /** 隐藏违规评价。隐藏后不再计入信用分口径 */
    void hide(Long id);

    /** 恢复被隐藏的评价 */
    void show(Long id);
}
