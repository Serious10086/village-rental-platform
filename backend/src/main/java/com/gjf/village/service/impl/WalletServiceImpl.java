package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.ResultCode;
import com.gjf.village.common.util.BizNoGenerator;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.PaymentQueryDTO;
import com.gjf.village.dto.WithdrawApplyDTO;
import com.gjf.village.dto.WithdrawAuditDTO;
import com.gjf.village.dto.WithdrawQueryDTO;
import com.gjf.village.entity.BizPayment;
import com.gjf.village.entity.BizWithdraw;
import com.gjf.village.entity.MsgNotice;
import com.gjf.village.entity.SysUser;
import com.gjf.village.mapper.BizPaymentMapper;
import com.gjf.village.mapper.BizWithdrawMapper;
import com.gjf.village.mapper.SysUserMapper;
import com.gjf.village.security.LoginUser;
import com.gjf.village.service.DataScopeHelper;
import com.gjf.village.service.MsgNoticeService;
import com.gjf.village.service.WalletService;
import com.gjf.village.vo.PaymentVO;
import com.gjf.village.vo.WalletVO;
import com.gjf.village.vo.WithdrawVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 钱包服务实现。
 *
 * <p>本类承载了论文「事务」这条硬指标里最关键的一处:
 * {@link #applyWithdraw} 的<b>并发提现</b>。对应的测试用例是 TC-09。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private final BizWithdrawMapper withdrawMapper;
    private final BizPaymentMapper paymentMapper;
    private final SysUserMapper userMapper;
    private final MsgNoticeService noticeService;
    private final DataScopeHelper dataScopeHelper;
    private final BizNoGenerator bizNoGenerator;

    /** 站内信里提现跳转的业务类型,与 MsgNoticeServiceImpl.buildLinkPath 的 case 4 对应 */
    private static final int NOTICE_BIZ_WITHDRAW = 4;

    /** 已驳回的状态值不参与"累计提现"汇总 */
    private static final List<Integer> STATUS_COUNTED_AS_WITHDRAWN =
            List.of(BizWithdraw.STATUS_PENDING, BizWithdraw.STATUS_PAID);

    private static final List<Integer> STATUS_PENDING_ONLY =
            List.of(BizWithdraw.STATUS_PENDING);

    // ═══════════════════════════════════════════════════════════
    //  钱包概览
    // ═══════════════════════════════════════════════════════════

    @Override
    public WalletVO getMyWallet() {
        Long me = UserContext.getUserId();

        // 余额从 sys_user 读,但只取余额一个字段的语义 ——
        // 这个对象里带密码哈希,绝不能直接返回给前端
        SysUser user = userMapper.selectById(me);
        if (user == null) {
            throw BusinessException.notFound("用户不存在");
        }

        WalletVO vo = new WalletVO();
        vo.setBalance(user.getBalance() == null ? BigDecimal.ZERO : user.getBalance());
        vo.setTotalIncome(paymentMapper.sumIncome(me));
        vo.setTotalWithdraw(withdrawMapper.sumAmountByStatus(me, STATUS_COUNTED_AS_WITHDRAWN));
        vo.setPendingWithdraw(withdrawMapper.sumAmountByStatus(me, STATUS_PENDING_ONLY));
        // 把起提门槛下发给前端,前端不要自己写死 10 ——
        // 写死之后改门槛要同时改后端和前端,而漏改前端不报错,
        // 只是按钮的禁用条件错了
        vo.setMinWithdrawAmount(BizWithdraw.MIN_AMOUNT);
        return vo;
    }

    @Override
    public PageResult<PaymentVO> pageMyEarnings(PaymentQueryDTO query) {
        // 强制只看自己的流水。这一行是安全边界:资金流水里有金额和订单号,
        // 能看到别人的就等于看到了别人在平台上做了多少生意
        query.setUserId(UserContext.getUserId());
        query.setVillageIds(null);

        Page<PaymentVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<PaymentVO> result = paymentMapper.selectPaymentPage(page, query);
        result.getRecords().forEach(this::fillPayLabels);
        return PageResult.of(result);
    }

    @Override
    public PageResult<WithdrawVO> pageMyWithdraws(WithdrawQueryDTO query) {
        query.setUserId(UserContext.getUserId());
        query.setVillageIds(null);
        return pageWithdraws(query);
    }

    // ═══════════════════════════════════════════════════════════
    //  申请提现 —— 事务边界之七,全系统并发最脆弱的一处
    // ═══════════════════════════════════════════════════════════

    /**
     * 申请提现。
     *
     * <p><b>为什么扣款在申请时而不是打款时:</b>
     * 如果打款才扣,余额校验和实际扣款之间隔着一个"等管理员操作"的
     * 时间窗(可能是几小时)。用户余额 100 元,同时提交两笔 100 元的提现,
     * 两笔都能通过校验、都进入待审核队列;管理员先打第一笔,余额变 0,
     * 再打第二笔时余额已经不够 —— 但这笔提现已提交,平台要么赖账,
     * 要么让余额变成负数。这个窗口用任何代码都补不上,只能靠改变时机消除。
     *
     * <p><b>为什么 {@code FOR UPDATE} 必须在余额校验之前:</b>
     * 这是典型的<b>检查后使用(TOCTOU)</b> 竞态。假设写成"先 selectById 读余额,
     * 判断够不够,再扣减":
     * <pre>
     *   余额 100,两个请求同时提 100
     *   T1: selectById → 100 → 够
     *   T2: selectById → 100 → 够        ← T1 还没提交,T2 读到的是旧值
     *   T1: UPDATE balance = balance - 100   → 0
     *   T2: UPDATE balance = balance - 100   → -100     ← 两笔都成功了
     * </pre>
     * 先拿行锁则不同:T2 会<b>阻塞在 SELECT ... FOR UPDATE 上</b>,
     * 直到 T1 提交;醒来后它读到的是已经被扣过的余额 0,于是被正常拒绝。
     *
     * <p><b>注意扣减本身仍然用 {@code balance = balance - ?} 而不是写回算好的值。</b>
     * 即使有了行锁,把"读到的值减掉金额再写回"写进代码也是坏习惯 ——
     * 锁的范围一旦因为将来重构而缩小(比如把扣减挪到别的方法里),
     * 丢失更新立刻复现,而且不会报错。让数据库做加法是不依赖锁的保险。
     *
     * <p><b>TC-09 的做法:</b>把这里的 {@code selectByIdForUpdate} 换成
     * {@code selectById} 再跑一次并发测试,余额会变成 -100 ——
     * 这是论文里最有说服力的一张对比截图。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public Long applyWithdraw(WithdrawApplyDTO dto) {
        Long me = UserContext.getUserId();
        BigDecimal amount = dto.getAmount();

        // ── ① 不依赖余额的校验先做完,再拿锁 ────────────────────
        //
        // 顺序很重要:能被参数校验拦掉的请求(比如提 5 元,不到起提门槛)
        // 不应该去争这把行锁。锁的持有时长直接决定并发吞吐。
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("提现金额必须大于 0");
        }
        if (amount.compareTo(BizWithdraw.MIN_AMOUNT) < 0) {
            throw BusinessException.badRequest(
                    "单笔提现不能低于 " + BizWithdraw.MIN_AMOUNT.stripTrailingZeros().toPlainString() + " 元");
        }
        if (dto.getAccountType() == null
                || dto.getAccountType() < BizWithdraw.ACCOUNT_WECHAT
                || dto.getAccountType() > BizWithdraw.ACCOUNT_BANK) {
            throw BusinessException.badRequest("收款方式不合法");
        }

        // ── ② 拿行锁,读到的一定是最新余额 ──────────────────────
        SysUser locked = userMapper.selectByIdForUpdate(me);
        if (locked == null) {
            throw BusinessException.notFound("用户不存在");
        }

        // 提现单的 village_id 是 NOT NULL 且带外键,而 sys_user.village_id
        // 允许为空(外来用户尚未备案时就是空的)。没有归属村的提现单
        // 没有任何管理员能审到,所以这里直接拒绝,而不是塞一个假值进去 ——
        // 塞假值会让它掉进某个村管的列表里,那个人无权判断这笔钱该不该放行
        if (locked.getVillageId() == null) {
            throw BusinessException.badRequest("你的账号尚未归属村组,暂时无法提现,请联系管理员");
        }

        // ── ③ 余额校验:此刻的值是锁保护下的最新值 ──────────────
        BigDecimal balance = locked.getBalance() == null ? BigDecimal.ZERO : locked.getBalance();
        if (balance.compareTo(amount) < 0) {
            throw new BusinessException(ResultCode.BALANCE_NOT_ENOUGH,
                    "账户余额不足,当前可提现 " + balance.toPlainString() + " 元");
        }

        // ── ④ 扣款(数据库端做减法) ─────────────────────────────
        userMapper.increaseBalance(me, amount.negate());

        // ── ⑤ 落提现单 ──────────────────────────────────────────
        BizWithdraw withdraw = new BizWithdraw();
        withdraw.setWithdrawNo(bizNoGenerator.nextWithdrawNo());
        withdraw.setUserId(me);
        withdraw.setAmount(amount);
        withdraw.setAccountType(dto.getAccountType());
        withdraw.setAccountNo(dto.getAccountNo().trim());
        withdraw.setAccountName(dto.getAccountName());
        withdraw.setStatus(BizWithdraw.STATUS_PENDING);
        withdraw.setApplyTime(LocalDateTime.now());
        withdraw.setVillageId(locked.getVillageId());
        withdrawMapper.insert(withdraw);

        log.info("提现申请: withdrawNo={}, userId={}, amount={}, 提现后余额={}",
                withdraw.getWithdrawNo(), me, amount, balance.subtract(amount));
        return withdraw.getId();
    }

    // ═══════════════════════════════════════════════════════════
    //  后台审核
    // ═══════════════════════════════════════════════════════════

    @Override
    public PageResult<WithdrawVO> pageAdminWithdraws(WithdrawQueryDTO query) {
        // 数据范围注入:村管只看本村。同时清掉可能被前端塞进来的 userId ——
        // 那个字段在前台是"只看我自己的",在后台是"筛选某个申请人",
        // 两者语义不同,这里保留它作为筛选项是有意的
        dataScopeHelper.apply(query);
        return pageWithdraws(query);
    }

    /**
     * 审核提现。
     *
     * <p>通过和驳回改的是不同的东西:
     * <ul>
     *   <li><b>通过</b> —— 只改提现单状态。钱在申请时就扣了,此刻不需要再动余额</li>
     *   <li><b>驳回</b> —— 改状态 + <b>把钱退回余额</b>。这两件事必须同生共死:
     *       只改状态不退钱,用户的钱就凭空消失了</li>
     * </ul>
     *
     * <p>状态迁移用<b>乐观锁</b>写法({@code WHERE status = 0}),
     * 而不是"先查状态再更新"。两个管理员同时点这条待审核的提现时,
     * 后者会影响 0 行 —— 驳回分支照着跑的话就会<b>退两次钱</b>。
     * 这是乐观锁在状态机上最典型的用法:把"当前状态"写进 WHERE 里,
     * 让数据库来判断这次迁移是否还有效。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void audit(Long id, WithdrawAuditDTO dto) {
        BizWithdraw withdraw = withdrawMapper.selectById(id);
        if (withdraw == null) {
            throw BusinessException.notFound("提现单不存在");
        }

        LoginUser me = UserContext.get();
        if (!dataScopeHelper.canManageVillage(withdraw.getVillageId())) {
            throw BusinessException.forbidden("只能审核本村的提现单");
        }

        boolean pass = Boolean.TRUE.equals(dto.getPass());
        if (!pass && (dto.getRemark() == null || dto.getRemark().isBlank())) {
            // 驳回必须说明理由。不写原因的话,用户只知道"提现被打回了",
            // 大概率会原样再提交一次,把同一个问题推到下一个管理员手上
            throw BusinessException.badRequest("驳回时必须填写审核意见");
        }

        LocalDateTime now = LocalDateTime.now();
        int targetStatus = pass ? BizWithdraw.STATUS_PAID : BizWithdraw.STATUS_REJECTED;

        int affected = withdrawMapper.update(null, new LambdaUpdateWrapper<BizWithdraw>()
                .eq(BizWithdraw::getId, id)
                // ← 这一行是乐观锁:只有还处于"待审核"的提现单才会被更新
                .eq(BizWithdraw::getStatus, BizWithdraw.STATUS_PENDING)
                .set(BizWithdraw::getStatus, targetStatus)
                .set(BizWithdraw::getAuditUserId, me.getUserId())
                .set(BizWithdraw::getAuditRemark, dto.getRemark())
                .set(BizWithdraw::getAuditTime, now)
                // 只有打款才有"完成时间";驳回的定义是"这笔操作终止了",
                // 填一个 finish_time 会让"已驳回"看起来像已经出过钱
                .set(pass, BizWithdraw::getFinishTime, now));

        if (affected == 0) {
            throw BusinessException.badRequest("这条提现已处理过,请刷新列表");
        }

        // ── 驳回:退回余额(数据库端加法) ───────────────────────
        if (!pass) {
            userMapper.increaseBalance(withdraw.getUserId(), withdraw.getAmount());
        }

        // ── 通知申请人 ──────────────────────────────────────────
        noticeService.send(withdraw.getUserId(), MsgNotice.TYPE_WITHDRAW,
                pass ? "提现已打款" : "提现申请被驳回",
                pass
                        ? "你申请的 " + withdraw.getAmount().toPlainString() + " 元提现已打款至"
                            + BizWithdraw.accountTypeName(withdraw.getAccountType())
                            + " " + maskAccount(withdraw.getAccountNo()) + ",请注意查收。"
                        : "你申请的 " + withdraw.getAmount().toPlainString() + " 元提现未通过审核,金额已退回余额。"
                            + "原因:" + dto.getRemark(),
                NOTICE_BIZ_WITHDRAW, withdraw.getId());

        log.info("提现审核: withdrawNo={}, 结果={}, 操作人={}, 金额={}",
                withdraw.getWithdrawNo(), pass ? "打款" : "驳回", me.getUserId(), withdraw.getAmount());
    }

    // ═══════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════

    private PageResult<WithdrawVO> pageWithdraws(WithdrawQueryDTO query) {
        Page<WithdrawVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<WithdrawVO> result = withdrawMapper.selectWithdrawPage(page, query);
        result.getRecords().forEach(this::fillWithdrawLabels);
        return PageResult.of(result);
    }

    private void fillWithdrawLabels(WithdrawVO vo) {
        vo.setAccountTypeName(BizWithdraw.accountTypeName(vo.getAccountType()));
        vo.setStatusName(BizWithdraw.statusName(vo.getStatus()));
    }

    /**
     * 把支付方式翻译成"这笔钱是进还是出"。
     *
     * <p>方向定义只在这一处。前端拿到 {@code direction} 直接决定
     * 显示 {@code +1200.00} 还是 {@code -1200.00} 以及用什么颜色,
     * 不再自己按 payType 判断 —— 让前端判断的话,
     * 将来新增一种支付方式必然有页面漏改,表现为"金额正负号反了",
     * 而这种错在小额测试数据下根本看不出来。
     */
    private void fillPayLabels(PaymentVO vo) {
        Integer type = vo.getPayType();
        if (type == null) {
            return;
        }
        switch (type) {
            case BizPayment.TYPE_WECHAT -> {
                vo.setPayTypeName("订单支付");
                vo.setDirection(-1);
            }
            case BizPayment.TYPE_ALIPAY -> {
                vo.setPayTypeName("订单支付");
                vo.setDirection(-1);
            }
            case BizPayment.TYPE_DEPOSIT_REFUND -> {
                vo.setPayTypeName("押金退还");
                vo.setDirection(1);
            }
            case BizPayment.TYPE_SETTLEMENT -> {
                vo.setPayTypeName("交易结算");
                vo.setDirection(1);
            }
            case BizPayment.TYPE_WITHDRAW -> {
                vo.setPayTypeName("提现");
                vo.setDirection(-1);
            }
            default -> vo.setPayTypeName("其他");
        }
        vo.setPayStatusName(payStatusName(vo.getPayStatus()));
    }

    private String payStatusName(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case BizPayment.STATUS_PENDING -> "处理中";
            case BizPayment.STATUS_SUCCESS -> "成功";
            case BizPayment.STATUS_FAILED -> "失败";
            case BizPayment.STATUS_REFUNDED -> "已退款";
            default -> "未知";
        };
    }

    /**
     * 收款账号打码:只留后四位。
     *
     * <p>站内信会长期躺在用户的通知列表里,而通知列表是随时可能被
     * 旁人瞄到一眼的界面。完整账号没有出现在这里的必要 ——
     * 用户自己知道填的是哪个号,这条消息的作用只是告诉他"钱发出去了"。
     */
    private String maskAccount(String accountNo) {
        if (accountNo == null || accountNo.isBlank()) {
            return "";
        }
        if (accountNo.length() <= 4) {
            return "****";
        }
        return "****" + accountNo.substring(accountNo.length() - 4);
    }
}
