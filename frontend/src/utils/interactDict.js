/**
 * 互动域(纠纷 / 提现 / 站内信 / 公告 / 留言板)的展示常量。
 *
 * 与 orderDict.js、resourceDict.js 同样的理由:字典集中一处,
 * 前台列表、详情页、后台管理页三处共用。分散写的话,
 * 后端改了文案或者加了新状态,总有一处忘了改,界面就会自相矛盾。
 *
 * 公告和留言板之所以并进这个文件而不是另起一个 contentDict.js:
 * 数据库里它们本就属于同一个域(互动域 6 张表),界面上也出现在
 * 同一批页面里。按"类型"拆文件会让人以为它们和纠纷提现无关,
 * 而按"域"归堆则一眼看得出谁和谁是一组。
 */

/** 纠纷类型。与后端 BizDispute 的 TYPE_* 常量一一对应 */
export const DISPUTE_TYPE = {
  1: { label: '损坏赔偿', type: 'danger' },
  2: { label: '押金争议', type: 'warning' },
  3: { label: '实物不符', type: 'warning' },
  4: { label: '逾期归还', type: 'info' },
  5: { label: '其他', type: 'info' }
}

/**
 * 纠纷状态。
 *
 * 0/1 是"未决",2/3 是"已决" —— 这个二分比具体是哪一个更重要,
 * 因为界面上所有"还能不能操作"的判断都按它来。所以单独导出
 * UNRESOLVED_STATUSES,免得每个页面各写一遍 [0, 1]。
 */
export const DISPUTE_STATUS = {
  0: { label: '待受理', type: 'danger' },
  1: { label: '处理中', type: 'warning' },
  2: { label: '已调解', type: 'success' },
  3: { label: '已关闭', type: 'info' }
}

/** 未决状态 —— 纠纷还没出结论,订单仍挂在「纠纷中」 */
export const UNRESOLVED_STATUSES = [0, 1]

/** 纠纷结论:管理员能选的处理结果(只有这两个,订单状态不允许别填) */
export const DISPUTE_RESULT = {
  2: { label: '已调解', type: 'success', desc: '双方达成一致,订单恢复履约' },
  3: { label: '已关闭', type: 'info', desc: '证据不足或双方各执一词,订单恢复履约' }
}

/** 提现收款方式 */
export const ACCOUNT_TYPE = {
  1: { label: '微信', icon: 'ChatDotRound', placeholder: '收款手机号' },
  2: { label: '支付宝', icon: 'Wallet', placeholder: '支付宝账号' },
  3: { label: '银行卡', icon: 'CreditCard', placeholder: '银行卡号' }
}

/** 提现状态 */
export const WITHDRAW_STATUS = {
  0: { label: '待审核', type: 'warning' },
  1: { label: '已打款', type: 'success' },
  2: { label: '已驳回', type: 'danger' }
}

/**
 * 资金流水方向。
 *
 * 【键是 1 和 -1,不是 1 和 2】
 * 后端 PaymentVO.direction 的取值是「1 入账 / -1 出账」——用正负号
 * 表达方向,这样前端可以直接拿它当符号用(加减号、颜色),不必再
 * 记一张"哪个值代表出账"的对照表。写成 1/2 的话,`+` 和 `−` 这两个
 * 字符就得靠 if 拼,而且看代码的人会以为方向是个分类而不是符号。
 *
 * direction 由后端算好而不是前端按 payType 推 —— 押金退还对买家是
 * 入账、对卖家什么都不算,同一笔流水在不同人眼里方向不同,推不出来,
 * 只能由知道"我是谁"的那一端给出结论。
 */
export const PAY_DIRECTION = {
  1: { label: '入账', type: 'success' },
  '-1': { label: '出账', type: 'danger' }
}

// ── 公告 ──────────────────────────────────────────────────────

/**
 * 公告类型。与后端 BizAnnouncement 的 TYPE_* 常量一一对应。
 *
 * 带 color 是因为公告卡片要按类型着色 —— 五种类型在列表里混在一起,
 * 只有文字标签的话扫一眼分不出哪条是政策、哪条是活动。颜色是给
 * "快速定位"用的,不是装饰。
 */
export const ANNOUNCEMENT_TYPE = {
  1: { label: '村级共享倡议', type: 'primary', color: '#2b6cb0' },
  2: { label: '安全使用须知', type: 'danger', color: '#c53030' },
  3: { label: '农机补贴政策', type: 'success', color: '#2f855a' },
  4: { label: '节庆活动', type: 'warning', color: '#b7791f' },
  5: { label: '系统公告', type: 'info', color: '#4a5568' }
}

/**
 * 公告状态。
 *
 * 【这三个值前台看不到】
 * 前台那条 SQL 把 status = 1 硬编码在里面,所以草稿和下架状态
 * 根本不会出现在前台列表里。这张表只被后台管理页用 —— 前台页面
 * 引了它也没地方显示,不要白引。
 */
export const ANNOUNCEMENT_STATUS = {
  0: { label: '草稿', type: 'info' },
  1: { label: '已发布', type: 'success' },
  2: { label: '已下架', type: 'warning' }
}

// ── 留言板 ────────────────────────────────────────────────────

/**
 * 留言状态。
 *
 * 一样的道理:前台列表的 SQL 里写死了 status = 1,
 * 所以前台永远只会拿到「正常」的留言,这张表是给后台巡查页用的。
 */
export const BOARD_STATUS = {
  1: { label: '正常', type: 'success' },
  2: { label: '已隐藏', type: 'danger' }
}

/**
 * 敏感词命中标记。
 *
 * 【为什么前台也要显示这个】
 * 命中敏感词的留言,后端返回的是<b>打码后</b>的正文(原文仍在库里),
 * 但打码之后的文字未必看得出被打码了 —— 比如"这个价格真是***"。
 * 不额外给个标记的话,用户会以为是发帖人自己打的星号,
 * 而不是"这条被系统处理过"。这个标记就是那层说明。
 */
export const FILTERED_FLAG = {
  1: { label: '含敏感词', type: 'warning' }
}

/**
 * AI 对话状态 → 标签颜色。
 *
 * 【为什么这里只有颜色,没有文字】
 * 文字由后端在 statusName 字段里给(见 AiChatRecordVO 的注释):
 * 状态码是后端定义的,让前端再写一份「1=成功」的映射,
 * 改一处忘一处就会出现"后台写着成功、前台写着降级"。
 * 但颜色是纯展示的事,后端不该管,所以留在前端。
 */
export const AI_STATUS_TYPE = {
  1: 'success',
  2: 'warning',
  3: 'danger'
}
