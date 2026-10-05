/**
 * 批次 6b(站内信 + 提现 + 纠纷)端到端验证脚本。
 *
 * 验证真实浏览器里的:钱包余额与流水口径、提现申请、会话复用与敏感词打码、
 * 订单页申请/撤销纠纷、后台纠纷调解(含调取聊天记录)、后台提现审核。
 *
 * 【为什么这一批必须跑浏览器,不能只跑接口测试】
 * 这一批的功能几乎全靠"同一份数据在不同人眼里不一样"支撑:
 *   同一笔押金退还流水,买家看到的是入账、卖家看不到;
 *   同一个纠纷,申请人和被申请人看到的按钮不同;
 *   同一段聊天原文,当事人看到打码版、管理员看到原文。
 * 这些"因人而异"的部分只在渲染层成立,接口返回对了不代表界面摆对了 ——
 * 接口测试证明不了"两个 Tab 拿到的是两份数据"这种事。
 *
 * 用法(需先启动后端 8080 和前端 5173):
 *     cd frontend && node e2e/interact.mjs
 *
 * 注意:本脚本会写数据(申请提现、发消息、申请并撤销纠纷、打款)。
 * 重复跑之前请重置演示数据:
 *     mysql -uroot -proot < sql/05_mock_data.sql
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5173'
const SHOT_DIR = 'D:/bishe/claude/docs/screenshots'

let passed = 0
let failed = 0

function check(name, ok, detail = '') {
  if (ok) {
    passed++
    console.log(`  [PASS] ${name}`)
  } else {
    failed++
    console.log(`  [FAIL] ${name}  ${detail}`)
  }
}

async function login(page, phone, password = '123456') {
  // 先清登录态 —— 已登录访问 /login 会被守卫重定向走
  await goto(page, `${BASE}/`)
  await page.evaluate(() => localStorage.clear())
  await goto(page, `${BASE}/login`)
  await page.fill('input[placeholder="请输入手机号"]', phone)
  await page.fill('input[placeholder="请输入密码"]', password)
  await page.click('button:has-text("登录")')
  await page.waitForTimeout(1800)
}

/**
 * 带重试的跳转。
 *
 * 开发服务器(Vite)在连续跳转时偶尔会抛 net::ERR_ABORTED ——
 * 上一次导航还没稳定,新的导航就被取消了。这个异常会让整个用例
 * 无谓地失败,而且只在连着跑多个脚本时出现,排查起来很费时间。
 *
 * 统一用 domcontentloaded 而不是 networkidle:后者要等所有请求静默,
 * 在带 HMR 长连接的开发服务器上更容易卡住。
 */
async function goto(page, url) {
  let lastErr
  for (let i = 0; i < 3; i++) {
    try {
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 20000 })
      return
    } catch (e) {
      lastErr = e
      await page.waitForTimeout(800)
    }
  }
  throw lastErr
}

/**
 * 把界面上的一段文本解析成金额数值。
 *
 * 【为什么不直接比对 "4240.00" 这个字符串】
 * 后端返回的是 BigDecimal("4240.00"),但经过 JSON.parse 之后就是 JS 的
 * number 4240,渲染出来是 "4240" —— 小数位在 JSON 往返里就没了。
 * 写死带小数的期望值会把一个纯粹的显示格式问题报成功能失败。
 * 统一解析成数值再比,断言才是在验"钱对不对",而不是"格式巧不巧"。
 */
function amountOf(text) {
  const m = String(text ?? '').replace(/,/g, '').match(/-?\d+(\.\d+)?/)
  return m ? Number(m[0]) : NaN
}

/** 点击一个 ElMessageBox 的确认按钮 —— 三个分支的确认文案完全不同,不能硬编码 */
async function confirmBox(page, buttonText) {
  await page.waitForTimeout(600)
  await page.click(`.el-message-box__btns button:has-text("${buttonText}")`)
  await page.waitForTimeout(1500)
}

mkdirSync(SHOT_DIR, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()

const consoleErrors = []
page.on('pageerror', (e) => consoleErrors.push('PAGEERROR: ' + e.message))
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})

try {
  console.log('='.repeat(70))
  console.log('批次 6b —— 站内信 + 提现 + 纠纷 端到端验证')
  console.log('='.repeat(70))

  // ══ 1. 钱包:口径与余额 ═══════════════════════════════════
  console.log('\n[1] 我的钱包(张建国 13800000003)')
  await login(page, '13800000003')
  await goto(page, `${BASE}/user/wallet`)
  await page.waitForTimeout(1500)

  const balanceBefore = amountOf(await page.textContent('.balance-num'))
  check('余额展示(种子数据 4240.00)', balanceBefore === 4240, String(balanceBefore))
  await page.screenshot({ path: `${SHOT_DIR}/47-我的钱包.png`, fullPage: true })

  // 三个数字的口径必须分开摆 —— 这是 WalletVO 刻意不做恒等式的直接体现
  const statLabels = await page.locator('.stat-label').allTextContents()
  check('三张统计卡口径齐全',
    statLabels.join(',').includes('累计入账') && statLabels.join(',').includes('累计提现')
      && statLabels.join(',').includes('审核中'),
    statLabels.join(' | '))

  const ledgerNote = await page.textContent('.ledger-note')
  check('页面说明了"押金不在这里"', ledgerNote?.includes('押金'), ledgerNote?.slice(0, 80))

  // 收益明细:张建国是卖家,应能看到结算入账
  const earningRows = await page.locator('.el-tab-pane:visible .el-table__row').count()
  check('收益明细有流水', earningRows > 0, `找到 ${earningRows} 行`)

  // ── 提现申请 ──────────────────────────────────────────────
  await page.click('.withdraw-btn')
  await page.waitForTimeout(900)
  const dialogTitle = await page.textContent('.el-dialog__title')
  check('提现弹窗打开', dialogTitle?.includes('申请提现'), dialogTitle)

  const accountNameValue = await page
    .locator('.el-dialog .el-form-item:has-text("收款户名") input').inputValue()
  check('户名默认填实名姓名', accountNameValue === '张建国', accountNameValue)

  await page.fill('.el-dialog .el-input-number input', '100')
  await page.click('.el-dialog .el-radio:has-text("微信")')
  await page.fill('.el-dialog .el-form-item:has-text("收款账号") input', '13800000003')
  await page.screenshot({ path: `${SHOT_DIR}/48-提现申请.png`, fullPage: true })

  await page.click('.el-dialog button:has-text("提交申请")')
  await page.waitForTimeout(2000)

  const balanceAfter = amountOf(await page.textContent('.balance-num'))
  check('提现后余额立即扣减(4240 → 4140)',
    balanceAfter === 4140, `${balanceBefore} → ${balanceAfter}`)

  // 提现记录:新增一条待审核
  await page.click('.el-tabs__item:has-text("提现记录")')
  await page.waitForTimeout(1200)
  const withdrawText = await page.textContent('.el-tab-pane:visible')
  check('提现记录出现新申请', withdrawText?.includes('¥100.00'),
    withdrawText?.slice(0, 100))
  check('新申请状态为待审核', withdrawText?.includes('待审核'),
    withdrawText?.slice(0, 100))
  check('旧的 500 元待审核记录仍在(累计提现含待审核)',
    withdrawText?.includes('¥500.00'), withdrawText?.slice(0, 120))
  await page.screenshot({ path: `${SHOT_DIR}/49-提现记录.png`, fullPage: true })

  // ══ 2. 我的纠纷:两个 Tab 必须是两份数据 ══════════════════
  console.log('\n[2] 我的纠纷 —— "我申请的" 与 "别人对我申请的"')
  await goto(page, `${BASE}/my/dispute`)
  await page.waitForTimeout(1500)

  const appliedNos = await page.locator('.dispute-card .dispute-no').allTextContents()
  check('「我申请的」只有我作为申请人的那条',
    appliedNos.length === 1 && appliedNos[0].includes('DP20260920000002'),
    appliedNos.join(' | '))
  const cancelBtnCount = await page.locator('.card-foot button:has-text("撤销申请")').count()
  check('待受理的纠纷可以撤销', cancelBtnCount === 1, `找到 ${cancelBtnCount} 个撤销按钮`)
  await page.screenshot({ path: `${SHOT_DIR}/50-我的纠纷-我申请的.png`, fullPage: true })

  await page.click('.el-tabs__item:has-text("别人对我申请的")')
  await page.waitForTimeout(1500)

  const receivedNos = await page.locator('.dispute-card .dispute-no').allTextContents()
  check('「别人对我申请的」是另一条',
    receivedNos.length === 1 && receivedNos[0].includes('DP20260914000001'),
    receivedNos.join(' | '))
  check('两个 Tab 内容确实不同(不是一个列表渲染两遍)',
    appliedNos.join() !== receivedNos.join(),
    `${appliedNos.join()} vs ${receivedNos.join()}`)
  const receivedTag = await page.textContent('.dispute-card')
  check('标出了"我是被申请人"', receivedTag?.includes('我是被申请人'),
    receivedTag?.slice(0, 80))
  const cancelBtnReceived = await page.locator('.card-foot button:has-text("撤销申请")').count()
  check('已调解的纠纷没有撤销按钮', cancelBtnReceived === 0, `找到 ${cancelBtnReceived} 个`)
  await page.screenshot({ path: `${SHOT_DIR}/51-我的纠纷-别人对我申请的.png`, fullPage: true })

  // 详情抽屉:结论只读
  await page.click('.dispute-card .dispute-no')
  await page.waitForTimeout(1500)
  const drawerText = await page.textContent('.el-drawer')
  check('详情抽屉展示纠纷双方', drawerText?.includes('申请人') && drawerText?.includes('被申请人'),
    drawerText?.slice(0, 80))
  check('已出结论的纠纷展示处理结论', drawerText?.includes('已调解') || drawerText?.includes('处理人'),
    drawerText?.slice(0, 120))
  await page.screenshot({ path: `${SHOT_DIR}/52-纠纷详情抽屉.png`, fullPage: true })
  await page.keyboard.press('Escape')
  await page.waitForTimeout(800)

  // ══ 3. 站内信:会话复用 + 敏感词打码 ═══════════════════════
  console.log('\n[3] 站内信(刘小明 13800000005 —— 从资源详情页进入)')
  await login(page, '13800000005')
  await goto(page, `${BASE}/resource/1`)
  await page.waitForTimeout(1500)

  const contactBtn = await page.locator('button:has-text("联系发布者")').count()
  check('资源详情页有「联系发布者」', contactBtn === 1, `找到 ${contactBtn} 个`)

  await page.click('button:has-text("联系发布者")')
  await page.waitForTimeout(2500)

  check('跳转到消息页并带上了目标会话',
    page.url().includes('/messages'), page.url())

  // (刘小明, 张建国, 资源1) 的会话种子数据里已经存在 ——
  // 点进来应该是"打开已有会话",而不是"新建一个"
  const convCount = await page.locator('.conv-item').count()
  check('复用已有会话,没有新建第二个', convCount === 2, `会话列表 ${convCount} 条`)

  const peer = await page.textContent('.chat-peer')
  check('打开的是与张建国的会话', peer?.includes('张建国'), peer)

  const chatContext = await page.textContent('.chat-context')
  check('会话标注了关联资源', chatContext?.includes('关于「'), chatContext)

  const msgBefore = await page.locator('.msg-row').count()
  check('载入了历史消息(种子 3 条)', msgBefore === 3, `找到 ${msgBefore} 条`)
  await page.screenshot({ path: `${SHOT_DIR}/53-站内信-会话复用.png`, fullPage: true })

  // 发一条含"替换类"敏感词的消息 —— 存原文、显示打码
  await page.fill('.chat-input textarea', '咱们加微信详谈吧,价格还能再商量')
  await page.click('.chat-input button:has-text("发送")')
  await page.waitForTimeout(2000)

  const msgAfter = await page.locator('.msg-row').count()
  check('消息发送成功', msgAfter === msgBefore + 1, `${msgBefore} → ${msgAfter}`)

  const lastBubble = await page.locator('.msg-row').last().locator('.bubble').textContent()
  check('敏感词被替换成 ***', lastBubble?.includes('***'), lastBubble)
  check('原文没有漏出去', !lastBubble?.includes('加微信'), lastBubble)

  const filteredTip = await page.locator('.msg-row').last().locator('.filtered-tip').count()
  check('气泡标注「内容已过滤」', filteredTip === 1, `找到 ${filteredTip} 个标记`)
  await page.screenshot({ path: `${SHOT_DIR}/54-站内信-敏感词打码.png`, fullPage: true })

  // ══ 4. 申请纠纷 → 撤销(王大山对订单9) ═════════════════════
  console.log('\n[4] 纠纷申请与撤销(王大山 13800000007,订单 9 待交付)')
  await login(page, '13800000007')
  await goto(page, `${BASE}/order/9`)
  await page.waitForTimeout(2000)

  const applyBtn = await page.locator('button:has-text("申请纠纷")').count()
  check('待交付订单可以申请纠纷', applyBtn === 1, `找到 ${applyBtn} 个`)
  check('未申请时不显示「撤销纠纷申请」',
    (await page.locator('button:has-text("撤销纠纷申请")').count()) === 0,
    '撤销按钮不应出现')
  check('未申请时不显示纠纷挂起提示',
    (await page.locator('.dispute-alert').count()) === 0, '提示不应出现')

  await page.click('button:has-text("申请纠纷")')
  await page.waitForTimeout(1200)
  check('申请纠纷弹窗打开',
    (await page.textContent('.el-dialog__title'))?.includes('申请纠纷'),
    await page.textContent('.el-dialog__title'))

  // 诉求金额默认必须为空 —— 预填订单金额会诱导用户"以为要退这么多"
  const amountDefault = await page
    .locator('.el-dialog .el-input-number input').inputValue()
  check('诉求金额默认留空', amountDefault === '', `实际值 "${amountDefault}"`)

  await page.click('.el-dialog .el-radio:has-text("损坏赔偿")')
  await page.fill('.el-dialog .el-input-number input', '500')
  await page.fill('.el-dialog textarea',
    '约定的交付日期已过三天,对方仍未交付农机,也无法联系上,要求退还已付租金并赔偿误工损失。')
  await page.screenshot({ path: `${SHOT_DIR}/55-申请纠纷.png`, fullPage: true })

  await page.click('.el-dialog button:has-text("提交纠纷")')
  await page.waitForTimeout(2500)

  const alertAfter = await page.locator('.dispute-alert').count()
  check('提交后出现纠纷挂起提示', alertAfter === 1, `找到 ${alertAfter} 个`)
  const alertText = await page.textContent('.dispute-alert').catch(() => '')
  check('提示说明纠纷不动钱、不改信用分',
    alertText?.includes('不动钱') || alertText?.includes('信用分'),
    alertText?.slice(0, 120))

  check('「申请纠纷」按钮被「撤销纠纷申请」取代',
    (await page.locator('button:has-text("申请纠纷")').count()) === 0
      && (await page.locator('button:has-text("撤销纠纷申请")').count()) === 1,
    '申请/撤销应当互斥')
  await page.screenshot({ path: `${SHOT_DIR}/56-申请纠纷后-挂起提示.png`, fullPage: true })

  // 撤销 —— 订单应恢复
  await page.click('button:has-text("撤销纠纷申请")')
  await confirmBox(page, '确认撤销')
  await page.waitForTimeout(1500)

  check('撤销后挂起提示消失',
    (await page.locator('.dispute-alert').count()) === 0, '提示应当收起')

  // ══ 5. 后台:纠纷调解 + 调取聊天记录 ═══════════════════════
  console.log('\n[5] 后台纠纷调解(超级管理员 13800000000)')
  await login(page, '13800000000')
  // 后台路径来自菜单表(sys_menu.path),不是按文件名猜的 —— 纠纷和提现
  // 都挂在「交易管理」目录下,写成 /admin/dispute 会落到空路由上,
  // 表现为表格 0 行而不是报错,很容易被当成"列表没数据"。
  await goto(page, `${BASE}/admin/trade/dispute`)
  await page.waitForTimeout(2000)

  const disputeRows = await page.locator('.el-table__row').count()
  check('后台纠纷列表有数据', disputeRows > 0, `找到 ${disputeRows} 行`)
  check('列表展示了未决纠纷的「受理调解」按钮',
    (await page.locator('button:has-text("受理调解")').count()) >= 1, '应有可受理的纠纷')
  await page.screenshot({ path: `${SHOT_DIR}/57-后台-纠纷列表.png`, fullPage: true })

  // 打开待受理的那条(DP...0002,订单 10)—— 按纠纷编号定位而不是
  // "第一个按钮":列表可能有多条未决纠纷,取第一个等于把测试绑在排序上
  const pendingRow = page.locator('.el-table__row')
    .filter({ hasText: 'DP20260920000002' }).first()
  check('待受理的纠纷在列表中', (await pendingRow.count()) === 1, '未找到 DP20260920000002')
  await pendingRow.locator('button:has-text("受理调解")').click()
  await page.waitForTimeout(2000)

  const drawerBody = await page.textContent('.el-drawer')
  check('抽屉展示争议订单与双方', drawerBody?.includes('申请人') && drawerBody?.includes('被申请人'),
    drawerBody?.slice(0, 80))
  check('抽屉给出处理意见表单', drawerBody?.includes('处理意见'), drawerBody?.slice(0, 120))
  check('抽屉提示结案后订单自动恢复', drawerBody?.includes('恢复'), drawerBody?.slice(0, 160))

  // 聊天记录默认折叠,展开才发请求
  const chatRowsBefore = await page.locator('.chat-row').count()
  check('聊天记录默认不加载', chatRowsBefore === 0, `已加载 ${chatRowsBefore} 行`)

  await page.click('.chat-collapse .el-collapse-item__header')
  await page.waitForTimeout(2000)
  const chatRowsAfter = await page.locator('.chat-row').count()
  check('展开后调到本单聊天记录', chatRowsAfter > 0, `找到 ${chatRowsAfter} 行`)

  const chatBody = await page.textContent('.chat-body').catch(() => '')
  check('聊天记录标出了被过滤的消息(含未打码原文)',
    chatBody?.includes('当事人看到的是打码版'), chatBody?.slice(0, 150))
  await page.screenshot({ path: `${SHOT_DIR}/58-后台-调解抽屉-调取聊天记录.png`, fullPage: true })

  // 不点「提交结案」—— 结案不可逆,不能为了测试把它消耗掉
  await page.keyboard.press('Escape')
  await page.waitForTimeout(1000)

  // ══ 6. 后台:提现审核 ═════════════════════════════════════
  console.log('\n[6] 后台提现审核')
  await goto(page, `${BASE}/admin/trade/withdraw`)
  await page.waitForTimeout(2000)

  const withdrawRows = await page.locator('.el-table__row').count()
  check('后台提现列表有数据', withdrawRows > 0, `找到 ${withdrawRows} 行`)
  const withdrawTable = await page.textContent('.table-card')
  check('刚才申请的 100 元出现在列表里', withdrawTable?.includes('¥100.00'),
    withdrawTable?.slice(0, 120))
  check('列表标出了账户与状态', withdrawTable?.includes('待审核')
    || withdrawTable?.includes('已打款'), withdrawTable?.slice(0, 120))
  await page.screenshot({ path: `${SHOT_DIR}/59-后台-提现审核列表.png`, fullPage: true })

  // 对刚申请的那条打款 —— 只动本脚本自己造的数据,不碰种子里的待审核记录
  const targetRow = page.locator('.el-table__row').filter({ hasText: '¥100.00' }).first()
  await targetRow.locator('button:has-text("已打款")').click()
  await confirmBox(page, '已打款')
  await page.waitForTimeout(1500)

  const targetRowText = await targetRow.textContent()
  check('打款后状态变为已打款', targetRowText?.includes('已打款'), targetRowText?.slice(0, 120))
  await page.screenshot({ path: `${SHOT_DIR}/60-后台-提现打款.png`, fullPage: true })

  // 打款后余额不应再变动(钱在申请时就已经扣走了)
  await login(page, '13800000003')
  await goto(page, `${BASE}/user/wallet`)
  await page.waitForTimeout(1500)
  const balanceFinal = amountOf(await page.textContent('.balance-num'))
  check('打款不再扣余额(申请时已扣)', balanceFinal === 4140, String(balanceFinal))

  // ══ 7. 控制台 ═════════════════════════════════════════════
  console.log('\n[7] 浏览器控制台')
  const realErrors = consoleErrors.filter(
    (e) => !e.includes('favicon') && !e.includes('DevTools')
      && !e.includes('Download the Vue Devtools') && !e.includes('404 (Not Found)')
  )
  check('无前端运行时错误', realErrors.length === 0,
    realErrors.slice(0, 3).join(' | ').slice(0, 300))

} catch (e) {
  failed++
  console.log(`\n[ERROR] 测试执行异常: ${e.message}`)
  await page.screenshot({ path: `${SHOT_DIR}/99-异常现场-互动.png`, fullPage: true }).catch(() => {})
} finally {
  await browser.close()
}

console.log('\n' + '='.repeat(70))
console.log(`结果:通过 ${passed} 项,失败 ${failed} 项`)
console.log(`截图已保存到 ${SHOT_DIR}`)
console.log('本脚本会写数据,重复运行前请重置:mysql -uroot -proot < sql/05_mock_data.sql')
console.log('='.repeat(70))
process.exit(failed ? 1 : 0)
