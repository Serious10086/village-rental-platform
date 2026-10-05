/**
 * 审核相关字典。
 */

/** 业务类型 —— 与后端 sys_audit_record.biz_type 对应 */
export const BIZ_TYPE = {
  1: { label: '实名认证', perm: 'user:realname:audit' },
  2: { label: '资源发布', perm: 'resource:audit' },
  3: { label: '外来用户备案', perm: 'user:outside' },
  4: { label: '提现申请', perm: 'withdraw:audit' }
}

/** 审核状态 */
export const AUDIT_STATUS = {
  0: { label: '待审核', type: 'warning' },
  1: { label: '已通过', type: 'success' },
  2: { label: '已驳回', type: 'danger' }
}

/** 通知类型 */
export const NOTICE_TYPE = {
  1: { label: '实名认证', type: 'primary', icon: 'UserFilled' },
  2: { label: '资源审核', type: 'warning', icon: 'Goods' },
  3: { label: '订单状态', type: 'success', icon: 'Tickets' },
  4: { label: '到期提醒', type: 'danger', icon: 'Clock' },
  5: { label: '押金退还', type: 'success', icon: 'Money' },
  6: { label: '系统公告', type: 'info', icon: 'Bell' },
  7: { label: '纠纷处理', type: 'danger', icon: 'Warning' },
  8: { label: '信用变动', type: 'warning', icon: 'Medal' },
  9: { label: '提现通知', type: 'success', icon: 'Wallet' },
  // 10 是批次 6c 加的:留言板里有人回复了你。
  // 点赞刻意不发通知 —— 弱互动推消息会把通知栏淹掉
  10: { label: '留言互动', type: 'primary', icon: 'ChatDotSquare' },
  // 11:外来用户备案结果。注意这里的编号是通知类型,和 BIZ_TYPE 不是一套 ——
  // 备案通知的后端 bizType 传的是 null(不挂跳转链接),所以它不会链到订单去
  11: { label: '外来用户备案', type: 'primary', icon: 'UserFilled' }
}

/** 敏感词处理方式 */
export const WORD_ACTION = {
  1: { label: '替换为 *', type: 'info', desc: '内容仍可发布,敏感词打码' },
  2: { label: '直接拦截', type: 'danger', desc: '整条内容不予发布' }
}

/** 敏感词分类 */
export const WORD_CATEGORY = ['广告', '辱骂', '违法', '政治']
