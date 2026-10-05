package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.DisputeApplyDTO;
import com.gjf.village.dto.DisputeHandleDTO;
import com.gjf.village.dto.DisputeQueryDTO;
import com.gjf.village.vo.DisputeVO;

import java.util.List;

/**
 * 纠纷服务 —— 交易争议的申请与调解。
 *
 * <p><b>纠纷模块与订单模块的边界:</b>纠纷只负责"把订单挂起、记录争议、
 * 由管理员给出结论、然后把订单放回去"。至于争议的<b>后果</b>
 * (该不该退钱、该不该取消交易),一律走已经做好的订单接口 ——
 * 管理员用「强制取消订单」按钮处理退款,用「信用分调整」扣分。
 *
 * <p>为什么不在纠纷处理里直接做退款和扣分:那两个动作都已经有
 * 经过测试的、带完整状态校验和流水记录的实现。在纠纷里再写一套,
 * 等于把同一件事实现两遍,而两遍的口径迟早会不一致 ——
 * 到时候"纠纷退的押金"和"取消订单退的押金"算法不同,
 * 对账时没人说得清哪个对。
 *
 * <p>这也是答辩时可以主动讲的一处取舍:<b>宁可让管理员多点一个按钮,
 * 也不要让同一个业务规则有两个实现。</b>
 */
public interface BizDisputeService {

    /**
     * 申请纠纷。事务边界之八的第一段。
     *
     * <p>把一个已付款的订单置为"纠纷中",并冻结它原本的进度状态 ——
     * 调解结束后要放回原位,而不是一律退回某个写死的状态。
     *
     * @return 纠纷单ID
     */
    Long apply(DisputeApplyDTO dto);

    /** 申请人撤销纠纷,订单放回申请前的状态 */
    void cancel(Long id);

    /** 与我有关的纠纷(我是申请人或被申请人) */
    PageResult<DisputeVO> pageMine(DisputeQueryDTO query);

    /** 某笔订单的纠纷记录 —— 订单详情页据此显示"申请纠纷"还是"查看纠纷" */
    List<DisputeVO> listByOrder(Long orderId);

    /** 纠纷详情(申请人、被申请人、有权管辖的管理员可见) */
    DisputeVO getDetail(Long id);

    // ── 后台 ────────────────────────────────────────────────────

    /** 后台纠纷列表(受数据级权限约束,待受理的排在前面) */
    PageResult<DisputeVO> pageAdmin(DisputeQueryDTO query);

    /**
     * 处理纠纷。事务边界之八的第二段。
     *
     * <p>管理员给出结论,订单放回申请前的状态,双方收到通知。
     */
    void handle(Long id, DisputeHandleDTO dto);
}
