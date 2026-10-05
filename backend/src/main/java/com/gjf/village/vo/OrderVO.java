package com.gjf.village.vo;

import com.gjf.village.entity.OrderDetailView;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 订单展示对象。
 *
 * <p>在视图字段之上补三样东西,让前端不用重复实现一遍业务规则:
 * <ol>
 *   <li><b>中文名</b> —— 状态、类型、支付状态的描述文案</li>
 *   <li><b>我在这笔订单里是谁</b>({@code myRole}) —— 同一张订单卡片,
 *       买家看到的和卖家看到的按钮完全不同,前端得先知道自己的身份</li>
 *   <li><b>可执行动作清单</b>({@code actions}) —— 由后端按状态机算出来</li>
 * </ol>
 *
 * <p><b>为什么把状态机判定放到后端:前端最怕的就是"按钮能点但点了报错"。</b>
 * 如果前端自己写一遍 {@code if (status === 1) 显示支付按钮},
 * 那么后端状态机改了(比如加一个"待评价"中间态),前端不同步就会露出破绽。
 * 现在前端只认动作码,后端说能做什么它就显示什么按钮,
 * 状态机的唯一定义处留在 Java 枚举里。
 *
 * <p>后端仍然会独立校验一次动作合法性 —— 前端隐藏按钮只是体验,
 * 挡不住直接发 HTTP 请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderVO extends OrderDetailView {

    /** 状态中文名,如"待付款" */
    private String orderStatusName;

    /** 类型中文名,如"租赁" */
    private String orderTypeName;

    /** 支付状态中文名 */
    private String payStatusName;

    /** 当前登录用户在本订单中的身份:buyer 买家 / seller 卖家 / admin 管理员 */
    private String myRole;

    /** 当前登录用户可执行的动作码,见 {@code OrderAction} */
    private List<String> actions;

    /** 交互动作码常量。前端按这些字符串决定渲染哪些按钮 */
    public static final class OrderAction {
        /** 卖家确认接单 */
        public static final String CONFIRM = "confirm";
        /** 卖家拒绝接单 */
        public static final String REJECT = "reject";
        /** 买家付款 */
        public static final String PAY = "pay";
        /** 买家取消订单 */
        public static final String CANCEL = "cancel";
        /** 卖家交付 */
        public static final String DELIVER = "deliver";
        /** 买家确认收货(仅购买类) */
        public static final String RECEIVE = "receive";
        /** 买家确认归还(仅租赁类) */
        public static final String RETURN = "return";

        private OrderAction() {
        }
    }
}
