/**
 * 批次 5(交易闭环)端到端验证脚本。
 *
 * 验证真实浏览器里的:资源详情 → 下单确认页 → 我的订单 → 状态机按钮联动
 * → 订单详情时间轴 → 后台订单管理。
 *
 * 重点验证的是「按钮随状态变化」这条 —— 同一个订单,买家看到的按钮
 * 和卖家看到的完全不同,而且每推进一步按钮就换一组。这是状态机
 * 在界面上最直观的体现,也是最容易出问题的地方(后端改了流转规则、
 * 前端没跟上,就会出现点了报错的按钮)。
 *
 * 【运行前请先重置演示数据】
 *     mysql -uroot -proot < sql/05_mock_data.sql
 * 本脚本会真实下单、支付、完成订单,跑完数据就不再是干净的演示快照了。
 *
 * 用法(需先启动后端 8080 和前端 5173):
 *     cd frontend && node e2e/order.mjs
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

/**
 * 带重试的跳转。
 *
 * 开发服务器(Vite)在连续跳转时偶尔会抛 net::ERR_ABORTED ——
 * 上一次导航还没稳定,新的导航就被取消了。这不是产品问题,
 * 但会让整个用例无谓地失败,所以这里重试两次。
 * 用 domcontentloaded 而不是 networkidle:后者要等所有请求静默,
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

async function login(page, phone, password = '123456') {
  // 先清登录态 —— 已登录访问 /login 会被守卫重定向走
  await goto(page, `${BASE}/`)
  await page.waitForTimeout(500)

  // 清 localStorage 时页面可能正处在守卫触发的重定向中,
  // 此时执行上下文会被销毁,evaluate 抛
  // "Execution context was destroyed"。重试几次即可 —— 这是开发环境下
  // 的导航竞态,不是产品问题。
  for (let i = 0; i < 3; i++) {
    try {
      await page.evaluate(() => localStorage.clear())
      break
    } catch (e) {
      if (i === 2) throw e
      await page.waitForTimeout(600)
    }
  }

  await goto(page, `${BASE}/login`)
  await page.waitForTimeout(600)
  await page.fill('input[placeholder="请输入手机号"]', phone)
  await page.fill('input[placeholder="请输入密码"]', password)
  await page.click('button:has-text("登录")')
  await page.waitForTimeout(1800)
}

/** 弹出一个确认框并点确定(Element Plus 的 MessageBox) */
async function confirmDialog(page, buttonText = '确定') {
  await page.waitForSelector('.el-message-box', { timeout: 5000 })
  await page.click(`.el-message-box button:has-text("${buttonText}")`)
  await page.waitForTimeout(1500)
}

/**
 * 在日期范围选择器里选出[start, end]。
 *
 * 直接往输入框里打字是不可靠的:el-date-picker 的输入框只用于展示,
 * 真正决定值的是面板里被点中的单元格。所以这里老实地点开面板、
 * 翻到目标月份、再点日期 —— 这也是用户真实的操作路径,
 * 测出来的东西才有意义。
 */
async function pickDateRange(page, startDate, endDate) {
  await page.click('.date-picker input')
  await page.waitForTimeout(600)

  // 面板默认停在当前月,按差值翻页(往前翻是 arrow-left,往后是 arrow-right)
  const now = new Date()
  const target = new Date(startDate)
  const diff = (target.getFullYear() - now.getFullYear()) * 12
    + (target.getMonth() - now.getMonth())
  const arrow = diff >= 0 ? '.arrow-right' : '.arrow-left'
  for (let i = 0; i < Math.abs(diff); i++) {
    await page.click(`.el-date-range-picker__header ${arrow}`)
    await page.waitForTimeout(220)
  }

  // 起止日同月时两个单元格都在左侧面板里,所以固定点左表
  const leftTable = page.locator('.el-date-range-picker__content.is-left .el-date-table')
  const dayOf = (d) => String(new Date(d).getDate())

  await leftTable.locator('.el-date-table-cell__text')
    .filter({ hasText: new RegExp(`^${dayOf(startDate)}$`) })
    .first().click()
  await page.waitForTimeout(400)
  await leftTable.locator('.el-date-table-cell__text')
    .filter({ hasText: new RegExp(`^${dayOf(endDate)}$`) })
    .first().click()
  await page.waitForTimeout(800)
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

// 测试用的资源:临时配电箱(张建国发布,战旗村,80/天 押金 300)
// 它是高风险资源,顺带能验证"安全须知确认"这一条
const RES_ID = 16
const BUYER = '13800000005'    // 刘小明
const SELLER = '13800000003'   // 张建国
const SLOT = ['2026-12-05', '2026-12-07']

/**
 * 操作区里"独立于订单状态机"的辅助入口(批次 6b 加入)。
 *
 * 【为什么要把它们摘出去再断言】
 * 下面几条用例的意图是"这个状态下能做的状态流转动作只有 X" ——
 * 比如待交付时卖家只该看到「确认交付」,不该冒出「确认归还」这种越权的按钮。
 * 「联系对方」和「申请纠纷」不属于状态流转:前者任何状态都能点,
 * 后者由"是否已付款"决定,两者都和订单状态机无关。
 * 不摘出去的话,每加一个辅助入口这条用例就得改一次 ——
 * 那时它挡不住真正的回归,只会变成一条需要不断伺候的脆弱断言。
 */
const AUX_ACTIONS = ['联系对方', '申请纠纷', '撤销纠纷申请', '查看纠纷结论']

function stateActions(buttons) {
  return buttons.filter((t) => !AUX_ACTIONS.some((a) => t.includes(a)))
}

try {
  console.log('='.repeat(70))
  console.log('批次 5 —— 交易闭环 端到端验证')
  console.log('='.repeat(70))

  // ══ 1. 从资源详情进入下单页 ═══════════════════════════════
  console.log('\n[1] 资源详情 → 下单确认页')
  await login(page, BUYER)
  await goto(page, `${BASE}/resource/${RES_ID}`)
  await page.waitForTimeout(1200)

  const orderBtn = page.locator('.btn-wrap button.el-button--primary')
  check('详情页显示「立即预约」按钮', (await orderBtn.count()) > 0)
  await page.screenshot({ path: `${SHOT_DIR}/25-资源详情-可下单.png`, fullPage: true })

  await orderBtn.click()
  await page.waitForTimeout(1500)
  check('点击后进入下单确认页', page.url().includes('/order/create/'), page.url())

  // ══ 2. 下单页:安全须知与费用计算 ═════════════════════════
  console.log('\n[2] 下单确认页')
  check('显示资源摘要卡片', (await page.locator('.res-row').count()) > 0)
  check('高风险资源显示「安全须知」区块', (await page.locator('.training-block').count()) > 0)
  check('安全须知正文非空',
    ((await page.textContent('.training-content')) || '').length > 10)

  const submitBtn = page.locator('.submit-btn')
  check('未勾选安全须知时提交按钮禁用', await submitBtn.isDisabled())
  await page.screenshot({ path: `${SHOT_DIR}/26-下单确认页-安全须知.png`, fullPage: true })

  await page.click('.training-check')
  await page.waitForTimeout(300)

  // 选择租期
  await pickDateRange(page, SLOT[0], SLOT[1])

  const dayHint = (await page.textContent('.hint')) || ''
  check('租期选中后显示天数(12-05~12-07 = 3 天)', dayHint.includes('共 3 天'), dayHint.slice(0, 90))

  const totalText = (await page.textContent('.total-value')) || ''
  // 80 × 3 = 240 租金,押金 300,应付 540
  // 断言里带上 ".00":这一批把全站金额统一成两位小数,只查 "540" 的话,
  // 退回 ¥540 的旧写法它照样通过 —— 那就等于没验。
  check('应付总额实时计算为 ¥540.00(80×3 + 300 押金)',
    totalText.includes('¥540.00'), totalText)

  await page.fill('textarea', '村里搭棚子临时用电,三天就够')
  await page.waitForTimeout(300)
  await page.screenshot({ path: `${SHOT_DIR}/27-下单确认页-费用明细.png`, fullPage: true })

  check('勾选安全须知并选好租期后提交按钮可用', await submitBtn.isEnabled())

  // ══ 3. 提交订单 → 订单详情 ════════════════════════════════
  console.log('\n[3] 提交订单')
  await submitBtn.click()
  await page.waitForTimeout(2500)
  check('提交后跳转到订单详情页', /\/order\/\d+$/.test(page.url()), page.url())

  const orderUrl = page.url()
  const orderId = orderUrl.split('/').pop()

  // 用完整订单号做后续定位,不要用数字 ID —— 数字是订单号的子串,
  // 拿 "10" 去过滤卡片会命中 LO20260901000001,匹配到完全不相干的订单
  const orderNo = ((await page.textContent('.info-row:has-text("订单号") .num-text')) || '').trim()
  check('订单详情页展示订单号', /^LO\d{14}$/.test(orderNo), orderNo)

  let banner = (await page.textContent('.status-banner')) || ''
  check('订单状态为「待卖家确认」', banner.includes('待卖家确认'), banner.slice(0, 60))

  let actionBtns = await page.locator('.action-bar button').allTextContents()
  check('买家在待确认状态只能「取消订单」',
    stateActions(actionBtns).length === 1
      && stateActions(actionBtns)[0].includes('取消订单'),
    actionBtns.join(' / '))

  check('时间轴渲染出多个节点', (await page.locator('.el-step').count()) >= 5,
    `找到 ${await page.locator('.el-step').count()} 个节点`)

  await page.screenshot({ path: `${SHOT_DIR}/28-订单详情-待确认.png`, fullPage: true })

  // ══ 4. 时段冲突提示 ═══════════════════════════════════════
  console.log('\n[4] 时段冲突(同一资源再下一次重叠的日期)')
  await goto(page, `${BASE}/order/create/${RES_ID}`)
  await page.waitForTimeout(1200)
  await page.click('.training-check')
  await pickDateRange(page, '2026-12-06', '2026-12-08')
  await page.click('.submit-btn')
  await page.waitForTimeout(2000)

  const errMsg = (await page.textContent('.el-message--error')) || ''
  check('重叠时段提交时弹出「时段已被占用」提示',
    errMsg.includes('已被占用'), errMsg.slice(0, 80))
  await page.screenshot({ path: `${SHOT_DIR}/29-下单-时段冲突提示.png`, fullPage: true })

  // ══ 5. 我的订单:买家视角 ═════════════════════════════════
  console.log('\n[5] 我的订单(买家视角)')
  await goto(page, `${BASE}/my/order`)
  await page.waitForTimeout(1500)

  const tabs = await page.locator('.el-tabs__item').allTextContents()
  check('页面有两个标签页:我买到的 / 我卖出的',
    tabs.some((t) => t.includes('我买到的')) && tabs.some((t) => t.includes('我卖出的')),
    tabs.join(' / '))

  const myCard = page.locator('.order-card').filter({ hasText: orderNo }).first()
  check('列表里能找到刚下的订单', (await myCard.count()) > 0, `订单号 ${orderNo}`)
  await page.screenshot({ path: `${SHOT_DIR}/30-我的订单-买家.png`, fullPage: true })

  // ══ 6. 卖家视角:接到订单并确认 ═══════════════════════════
  console.log('\n[6] 卖家确认接单')
  await login(page, SELLER)
  await goto(page, `${BASE}/my/order`)
  await page.waitForTimeout(1500)
  await page.click('.el-tabs__item:has-text("我卖出的")')
  await page.waitForTimeout(1500)

  const sellerCard = page.locator('.order-card').filter({ hasText: orderNo }).first()
  check('卖家在「我卖出的」里看到该订单', (await sellerCard.count()) > 0)

  const sellerBtns = await sellerCard.locator('.card-foot button').allTextContents()
  check('卖家在待确认状态可「确认接单」和「拒绝」',
    sellerBtns.some((t) => t.includes('确认接单')) && sellerBtns.some((t) => t.includes('拒绝')),
    sellerBtns.join(' / '))
  await page.screenshot({ path: `${SHOT_DIR}/31-我的订单-卖家待处理.png`, fullPage: true })

  await sellerCard.locator('button:has-text("确认接单")').click()
  await confirmDialog(page, '确定')
  await page.waitForTimeout(1500)

  const afterConfirm = await page.locator('.order-card').filter({ hasText: orderNo })
    .first().textContent()
  check('确认后订单变为「待付款」', afterConfirm.includes('待付款'), afterConfirm.slice(0, 80))

  // ══ 7. 买家支付 ═══════════════════════════════════════════
  console.log('\n[7] 买家支付')
  await login(page, BUYER)
  await goto(page, `${BASE}/order/${orderId}`)
  await page.waitForTimeout(1500)

  banner = (await page.textContent('.status-banner')) || ''
  check('买家看到状态为「待付款」', banner.includes('待付款'), banner.slice(0, 60))

  actionBtns = await page.locator('.action-bar button').allTextContents()
  check('买家此时可「去支付」和「取消订单」',
    actionBtns.some((t) => t.includes('去支付')) && actionBtns.some((t) => t.includes('取消订单')),
    actionBtns.join(' / '))

  await page.click('.action-bar button:has-text("去支付")')
  await confirmDialog(page, '确定')
  await page.waitForTimeout(2000)

  banner = (await page.textContent('.status-banner')) || ''
  check('支付后状态变为「待交付」', banner.includes('待交付'), banner.slice(0, 60))
  await page.screenshot({ path: `${SHOT_DIR}/32-订单详情-已支付待交付.png`, fullPage: true })

  // ══ 8. 卖家交付 ═══════════════════════════════════════════
  console.log('\n[8] 卖家交付 → 使用中')
  await login(page, SELLER)
  await goto(page, `${BASE}/order/${orderId}`)
  await page.waitForTimeout(1500)

  actionBtns = await page.locator('.action-bar button').allTextContents()
  check('卖家在待交付状态只有「确认交付」',
    stateActions(actionBtns).length === 1
      && stateActions(actionBtns)[0].includes('确认交付'),
    actionBtns.join(' / '))

  await page.click('.action-bar button:has-text("确认交付")')
  await confirmDialog(page, '确定')
  await page.waitForTimeout(2000)

  banner = (await page.textContent('.status-banner')) || ''
  check('交付后状态变为「使用中」', banner.includes('使用中'), banner.slice(0, 60))

  // ══ 9. 买家归还 → 完成 ════════════════════════════════════
  console.log('\n[9] 买家归还 → 订单完成')
  await login(page, BUYER)
  await goto(page, `${BASE}/order/${orderId}`)
  await page.waitForTimeout(1500)

  actionBtns = await page.locator('.action-bar button').allTextContents()
  check('买家在使用中状态只有「确认归还」',
    stateActions(actionBtns).length === 1
      && stateActions(actionBtns)[0].includes('确认归还'),
    actionBtns.join(' / '))

  await page.click('.action-bar button:has-text("确认归还")')
  await confirmDialog(page, '确定')
  await page.waitForTimeout(2500)

  banner = (await page.textContent('.status-banner')) || ''
  check('归还后订单「已完成」', banner.includes('已完成'), banner.slice(0, 60))
  // 完成后状态流转按钮全部消失,但「去评价」会出现 ——
  // 评价不属于状态流转,它是订单完成之后的独立动作,不受状态机约束
  const finalBtns = await page.locator('.action-bar button').allTextContents()
  check('完成后不再有状态流转按钮',
    !finalBtns.some((t) => ['归还', '支付', '取消', '确认'].some((k) => t.includes(k))),
    finalBtns.join(' / '))
  check('未评价时出现「去评价」入口',
    finalBtns.some((t) => t.includes('去评价')), finalBtns.join(' / '))

  // Element Plus 把状态类挂在 .el-step__head 上,不是根节点 .el-step
  const successSteps = await page.locator('.el-step__head.is-success').count()
  const allSteps = await page.locator('.el-step').count()
  check('时间轴全部节点变绿(全流程走完)',
    successSteps === allSteps && allSteps >= 5,
    `${successSteps}/${allSteps}`)

  await page.screenshot({ path: `${SHOT_DIR}/33-订单详情-已完成时间轴.png`, fullPage: true })

  // ══ 10. 后台订单管理 ══════════════════════════════════════
  console.log('\n[10] 后台订单管理')
  await login(page, '13800000000')
  await goto(page, `${BASE}/admin/trade/order`)
  await page.waitForTimeout(2000)

  check('后台订单页渲染出数据范围提示', (await page.locator('.scope-alert').count()) > 0)

  const rowCount = await page.locator('.el-table__body tr').count()
  check('后台订单表格有数据行', rowCount > 0, `${rowCount} 行`)

  await page.screenshot({ path: `${SHOT_DIR}/34-后台订单管理.png`, fullPage: true })

  await page.click(`.order-no:has-text("${orderNo}")`)
  await page.waitForTimeout(1800)
  check('点击订单号打开详情抽屉', (await page.locator('.el-drawer').isVisible()))
  const drawerText = (await page.textContent('.el-drawer')) || ''
  check('抽屉里能看到金额明细与买卖双方',
    drawerText.includes('应付总额') && drawerText.includes('买家') && drawerText.includes('卖家'),
    drawerText.slice(0, 80))
  await page.screenshot({ path: `${SHOT_DIR}/35-后台订单详情抽屉.png`, fullPage: true })

  // 强制取消按钮需要 order:cancel 权限 —— 超管有这个权限点
  check('超管可见「强制取消」按钮(按钮级权限)',
    (await page.locator('button:has-text("强制取消")').count()) > 0)

  // ══ 11. 控制台无报错 ══════════════════════════════════════
  console.log('\n[11] 控制台检查')
  const realErrors = consoleErrors.filter(
    (e) => !e.includes('favicon') && !e.includes('404 (Not Found)'))
  check('全程无 JS 运行时错误', realErrors.length === 0, realErrors.slice(0, 3).join(' | '))

} catch (e) {
  failed++
  console.log(`\n[EXCEPTION] ${e.message}`)
  await page.screenshot({ path: `${SHOT_DIR}/99-异常现场.png`, fullPage: true }).catch(() => {})
} finally {
  await browser.close()
}

console.log()
console.log('='.repeat(70))
console.log(`结果: ${passed} 项通过,${failed} 项失败`)
console.log('='.repeat(70))
if (failed === 0) {
  console.log()
  console.log('提示:本脚本会真实走完一笔订单,如需恢复干净演示数据请执行:')
  console.log('      mysql -uroot -proot < sql/05_mock_data.sql')
}
process.exit(failed ? 1 : 0)
