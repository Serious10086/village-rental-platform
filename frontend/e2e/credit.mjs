/**
 * 批次 6a(评价 + 信用)端到端验证脚本。
 *
 * 验证真实浏览器里的:订单完成后评价 → 评价上墙 → 信用分变化
 * → 我的信用页 → 回复评价 → 后台信用管理 / 评价管理。
 *
 * 【为什么用"订单8"而不是现造一笔订单】
 * 演示数据里订单⑧(旋耕机出售)刻意只让买家评了、卖家没评 ——
 * 就是为了留一条"待评价"的记录给演示用。E2E 直接拿它当起点,
 * 不用先走一遍下单→支付→交付→归还,脚本能短一半。
 *
 * 【运行前请先重置演示数据】
 *     mysql -uroot -proot < sql/05_mock_data.sql
 * 本脚本会真实提交评价并改动信用分,跑完数据就不再是干净的演示快照了。
 *
 * 用法(需先启动后端 8080 和前端 5173):
 *     cd frontend && node e2e/credit.mjs
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

/** 带重试的跳转 —— Vite 开发服务器连续跳转时偶尔抛 net::ERR_ABORTED */
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
  await page.waitForTimeout(700)
  await page.fill('input[placeholder="请输入手机号"]', phone)
  await page.fill('input[placeholder="请输入密码"]', password)
  await page.click('button:has-text("登录")')
  await page.waitForTimeout(1800)
}

/** 点 el-rate 的第 n 颗星(n 从 1 开始) */
async function rate(page, scope, n) {
  await page.locator(`${scope} .el-rate__item`).nth(n - 1).click()
  await page.waitForTimeout(200)
}

/**
 * 跳到后台列表页,并等表格真正渲染出数据行再返回。
 *
 * 固定 sleep 在开发服务器上不可靠 —— 首次访问某个路由时 Vite 要现编译
 * 组件,慢个一两秒很正常,断言会随机失败。
 */
async function gotoTable(page, path) {
  await goto(page, `${BASE}${path}`)
  await page.waitForSelector('.el-table__body tr', { timeout: 20000 }).catch(() => {})
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

const SELLER = '13800000004'   // 李秀兰(订单⑧的卖家,还没评价)
const BUYER = '13800000005'    // 刘小明(订单⑧的买家,已经评过了)
const ORDER_NO = 'LO20260901000008'
const SUPER = '13800000000'
const VILLAGE_ADMIN = '13800000001'

try {
  console.log('='.repeat(70))
  console.log('批次 6a —— 评价 + 信用 端到端验证')
  console.log('='.repeat(70))

  // ══ 0. 前置:订单⑧应当处于"卖家待评价"状态 ═══════════════
  console.log('\n[0] 前置检查')

  // 记两个基线。李秀兰是评价人,张建国是被评价人 ——
  // 加分记在**被评价人**账上,这一点很容易搞反,所以先取好数
  await login(page, BUYER)
  await goto(page, `${BASE}/my/credit`)
  await page.waitForTimeout(1500)
  const buyerBase = parseInt(await page.textContent('.score-num'), 10)
  check('买家信用页能打开', Number.isInteger(buyerBase), String(buyerBase))

  await login(page, SELLER)
  await goto(page, `${BASE}/my/credit`)
  await page.waitForTimeout(1500)
  const sellerBase = parseInt(await page.textContent('.score-num'), 10)
  check('卖家信用页能打开', Number.isInteger(sellerBase), String(sellerBase))
  console.log(`     基线:李秀兰(评价人)${sellerBase} 分 / 刘小明(被评价人)${buyerBase} 分`)

  // ══ 1. 从"我卖出的"进入订单⑧ ═════════════════════════════
  console.log('\n[1] 卖家视角:找到待评价的订单')
  await goto(page, `${BASE}/my/order`)
  await page.waitForTimeout(1500)
  await page.click('.el-tabs__item:has-text("我卖出的")')
  await page.waitForTimeout(1500)

  const card = page.locator('.order-card').filter({ hasText: ORDER_NO }).first()
  check('在我卖出的列表里找到订单⑧', (await card.count()) > 0, ORDER_NO)

  await card.locator('.order-no').click()
  await page.waitForTimeout(2000)
  check('进入订单详情页', page.url().includes('/order/'), page.url())

  const evaluateBtn = page.locator('.action-bar button:has-text("去评价")')
  check('已完成且未评价时,详情页出现「去评价」按钮',
    (await evaluateBtn.count()) > 0)
  await page.screenshot({ path: `${SHOT_DIR}/36-订单详情-待评价.png`, fullPage: true })

  // ══ 2. 打开评价对话框 ═════════════════════════════════════
  console.log('\n[2] 评价对话框')
  await evaluateBtn.click()
  await page.waitForTimeout(1200)

  check('评价对话框已弹出', await page.locator('.eval-form').isVisible())
  const targetText = await page.textContent('.target-hint')
  check('对话框说明评价对象是买家', targetText.includes('买家'), targetText.slice(0, 40))
  check('资源相符度不显示给卖家(卖家评买家没有这一项)',
    (await page.locator('.rate-row:has-text("资源相符度")').count()) === 0)
  await page.screenshot({ path: `${SHOT_DIR}/37-评价对话框.png`, fullPage: true })

  // ══ 3. 提交评价 ═══════════════════════════════════════════
  console.log('\n[3] 提交评价')
  const submitBtn = page.locator('.el-dialog__footer button:has-text("提交评价")')
  check('未打分时提交按钮禁用', await submitBtn.isDisabled())

  await rate(page, '.eval-form .rate-row:has-text("综合评分")', 5)
  await rate(page, '.eval-form .rate-row:has-text("服务态度")', 4)
  await page.fill('.eval-form textarea', '买家很爽快,当天就转账了,机器也爱惜,以后有需要再来。')
  await page.waitForTimeout(300)
  check('打分后提交按钮可用', await submitBtn.isEnabled())

  await submitBtn.click()
  await page.waitForTimeout(2500)

  check('对话框已关闭', !(await page.locator('.eval-form').isVisible()),
    '对话框仍然可见')
  const orderDetailText = (await page.textContent('.detail-grid')) || ''
  check('订单详情页出现了刚提交的评价',
    orderDetailText.includes('买家很爽快'), orderDetailText.slice(0, 80))
  const rateCount = await page.locator('.eval-list .el-rate').count()
  check('双方评价区域渲染出星级', rateCount >= 2, `${rateCount} 条评价`)
  await page.screenshot({ path: `${SHOT_DIR}/38-订单详情-双方评价.png`, fullPage: true })

  // ══ 4. 被评价人的信用分应当 +1 ════════════════════════════
  //
  // 【这一步最容易搞反】李秀兰是**评价人**,加分记在**被评价人**头上 ——
  // 所以要看的是买家刘小明的分,不是李秀兰的。
  console.log('\n[4] 评价对被评价人的影响')
  await login(page, BUYER)
  await goto(page, `${BASE}/my/credit`)
  await page.waitForTimeout(1800)

  const buyerAfter = parseInt(await page.textContent('.score-num'), 10)
  check('收到 5 星好评后,被评价人信用分 +1',
    buyerAfter === buyerBase + 1, `刘小明 ${buyerBase} → ${buyerAfter}`)

  const buyerFirst = (await page.locator('.record-item').first().textContent()) || ''
  check('被评价人的流水最新一条就是这条好评',
    buyerFirst.includes('收到好评') && buyerFirst.includes('+1')
    && buyerFirst.includes(ORDER_NO),
    buyerFirst.replace(/\s+/g, ' ').slice(0, 80))

  const buyerChain = (await page.locator('.record-right .text-xs').allTextContents())
  check('流水的「前值 → 后值」与当前分数对得上',
    buyerChain.length > 0
    && buyerChain[0].includes(`→ ${buyerAfter}`),
    `${buyerChain[0]} / 当前 ${buyerAfter}`)

  // ══ 5. 评价人的信用页 ═════════════════════════════════════
  console.log('\n[5] 我的信用页')
  await login(page, SELLER)
  await goto(page, `${BASE}/my/credit`)
  await page.waitForTimeout(1800)

  const levelText = (await page.textContent('.level-head')) || ''
  check('显示信用等级标签', levelText.trim().length > 0, levelText.slice(0, 30))

  const statValues = await page.locator('.stat-value').allTextContents()
  check('展示三个比率(交易履约率 / 资源完好率 / 用户评价均分)',
    statValues.length === 3, statValues.join(' | '))
  check('比率都渲染出了内容(含「暂无数据」占位)',
    statValues.every((v) => v.trim().length > 0), statValues.join(' | '))

  const statLabels = await page.locator('.stat-label').allTextContents()
  check('三个比率的名称对应需求原文的三个词',
    statLabels.some((l) => l.includes('交易履约'))
    && statLabels.some((l) => l.includes('资源完好率'))
    && statLabels.some((l) => l.includes('用户评价')),
    statLabels.join(' | '))

  const halfEmpty = statValues.some((v) => v.includes('暂无'))
  check('没有把「暂无数据」渲染成 0% 这样的误导性数字',
    !statValues.some((v) => v.trim() === '0%') || halfEmpty,
    statValues.join(' | '))
  await page.screenshot({ path: `${SHOT_DIR}/39-我的信用.png`, fullPage: true })

  // ══ 6. 信用变动流水 ═══════════════════════════════════════
  console.log('\n[6] 信用变动流水')
  const recordItems = await page.locator('.record-item').count()
  check('信用变动流水有记录', recordItems > 0, `${recordItems} 条`)

  const allReasons = await page.locator('.record-reason').allTextContents()
  check('流水里能看到订单履约和收到好评两类记录',
    allReasons.some((r) => r.includes('按时履约'))
    && allReasons.some((r) => r.includes('收到好评')),
    allReasons.slice(0, 4).join(' | '))

  const deltas = await page.locator('.delta').allTextContents()
  check('加分带 + 号,便于一眼看出方向',
    deltas.some((d) => d.includes('+')), deltas.join(','))

  const chain = (await page.locator('.record-right .text-xs').allTextContents())
  check('每条流水都带「前值 → 后值」',
    chain.every((t) => t.includes('→')), chain.slice(0, 3).join(' | '))

  // ══ 6. 我收到的评价 ═══════════════════════════════════════
  console.log('\n[7] 我收到的评价')
  await page.click('.el-tabs__item:has-text("我收到的评价")')
  await page.waitForTimeout(1500)

  const receivedCount = await page.locator('.eval-item').count()
  check('「我收到的评价」列出评价', receivedCount >= 2, `${receivedCount} 条`)

  const replyBtns = await page.locator('.reply-box button').count()
  check('有一条评价可以回复(演示数据里刻意留的)', replyBtns > 0, `${replyBtns} 个回复按钮`)
  await page.screenshot({ path: `${SHOT_DIR}/40-我收到的评价.png`, fullPage: true })

  // ══ 7. 回复评价 ═══════════════════════════════════════════
  console.log('\n[8] 回复评价')
  await page.locator('.reply-box button').first().click()
  await page.waitForTimeout(500)
  check('回复输入框出现', (await page.locator('.reply-box textarea').count()) > 0)

  await page.fill('.reply-box textarea', '排种器确实是用了两年的旧件,已经订了新的,下次来免费调。')
  await page.locator('.reply-actions button:has-text("提交回复")').click()
  await page.waitForTimeout(2000)

  const afterReply = (await page.textContent('.eval-list')) || ''
  check('回复内容已上墙', afterReply.includes('已经订了新的'), afterReply.slice(0, 80))
  const remaining = await page.locator('.reply-box button').count()
  check('回复后该条不再显示回复按钮',
    remaining === replyBtns - 1, `${replyBtns} → ${remaining}`)

  // ══ 8. 匿名评价在订单页的表现 ═════════════════════════════
  console.log('\n[9] 资源详情页展示评价')
  await goto(page, `${BASE}/resource/1`)
  await page.waitForTimeout(1800)

  const evalSection = (await page.textContent('.eval-head')) || ''
  check('资源详情页有「用户评价」区块', evalSection.includes('用户评价'), evalSection.slice(0, 40))
  check('显示评价均分与条数', /分 · \d+ 条/.test(evalSection), evalSection.slice(0, 60))

  const resEvalCount = await page.locator('.eval-item').count()
  check('资源下渲染出评价条目', resEvalCount >= 2, `${resEvalCount} 条`)
  await page.screenshot({ path: `${SHOT_DIR}/41-资源详情-用户评价.png`, fullPage: true })

  // ══ 9. 后台信用管理 ═══════════════════════════════════════
  console.log('\n[10] 后台信用管理')
  await login(page, SUPER)
  await gotoTable(page, '/admin/credit/list')

  const creditRows = await page.locator('.el-table__body tr').count()
  check('后台信用列表有数据', creditRows > 0, `${creditRows} 行`)
  check('页面提示了当前数据范围', (await page.locator('.scope-alert').count()) > 0)

  const scoreCells = await page.locator('.score-num').allTextContents()
  const nums = scoreCells.map((s) => parseInt(s, 10))
  check('列表按信用分升序(最需要关注的排最前)',
    nums.join() === [...nums].sort((a, b) => a - b).join(), nums.join(','))

  check('显示信用等级标签', (await page.locator('.score-cell .el-tag').count()) > 0)
  await page.screenshot({ path: `${SHOT_DIR}/42-后台信用管理.png`, fullPage: true })

  // 用搜索框精确定位目标用户,而不是靠翻列表找。
  //
  // 列表按信用分升序排,有交易记录的人分数最高、排在最后一页 ——
  // 演示账号一旦多起来(比如测试脚本注册的账号没清干净),
  // 直接 filter 会找不到人,断言随机失败。搜索不受分页影响。
  await page.fill('.filter-bar input', '刘小明')
  await page.click('.filter-bar button:has-text("查询")')
  await page.waitForTimeout(1500)

  const rowWithRecords = page.locator('.el-table__body tr').filter({ hasText: '刘小明' }).first()
  check('列表里能找到有交易记录的用户', (await rowWithRecords.count()) > 0)
  check('按姓名搜索能精确命中一个人',
    (await page.locator('.el-table__body tr').count()) === 1)
  await rowWithRecords.locator('button:has-text("信用流水")').click()
  await page.waitForTimeout(1800)
  check('信用流水抽屉打开', await page.locator('.el-drawer').isVisible())
  const drawerText = (await page.textContent('.el-drawer')) || ''
  check('抽屉里能看到变动原因与前后值',
    drawerText.includes('→') && drawerText.includes('收到好评'),
    drawerText.replace(/\s+/g, ' ').slice(0, 80))
  await page.screenshot({ path: `${SHOT_DIR}/43-后台-信用流水抽屉.png`, fullPage: true })
  await page.keyboard.press('Escape')
  await page.waitForTimeout(1000)

  // 手动调分
  console.log('\n[11] 管理员手动调分')
  await rowWithRecords.locator('button:has-text("调整分数")').click()
  await page.waitForTimeout(800)
  check('调分弹窗打开', (await page.locator('.el-dialog:has-text("调整信用分")').count()) > 0)

  const adjustDialog = page.locator('.el-dialog:has-text("调整信用分")')
  await adjustDialog.locator('.el-input-number input').fill('5')
  await page.waitForTimeout(300)
  const confirmAdjust = adjustDialog.locator('button:has-text("确认调整")')
  await confirmAdjust.click()
  await page.waitForTimeout(1500)

  let warnMsg = ''
  try {
    warnMsg = await page.textContent('.el-message', { timeout: 2000 })
  } catch (e) {
    warnMsg = ''
  }
  check('不填原因时被拦下并提示', warnMsg.includes('原因'), warnMsg.slice(0, 50))

  // 等提示条消失再操作 —— Element Plus 的消息浮在页面上方,
  // 提示还在显示时点击可能落到它身上而不是弹窗的按钮上
  await page.waitForTimeout(2500)

  const reasonBox = page.locator('textarea:visible').first()
  await reasonBox.fill('线下调解达成一致,补偿性加分')
  await page.waitForTimeout(400)
  check('原因已填入输入框', (await reasonBox.inputValue()).length > 0)

  await confirmAdjust.click()
  await page.waitForTimeout(2500)

  // 用"可见的确认按钮还有没有"判断弹窗是否关闭 ——
  // Element Plus 关闭后 DOM 会保留,直接数元素个数会误判
  const stillOpen = await page
    .locator('.el-dialog__footer button:has-text("确认调整"):visible').count()
  check('填写原因后调分成功,弹窗关闭', stillOpen === 0, `仍有 ${stillOpen} 个弹窗`)

  // 调分应当写进流水,列表刷新后能查到
  const adjustedRow = page.locator('.el-table__body tr').filter({ hasText: '刘小明' }).first()
  await adjustedRow.locator('button:has-text("信用流水")').click()
  await page.waitForTimeout(1800)
  const adjustDrawer = (await page.textContent('.el-drawer')) || ''
  check('手动调整的 5 分写进了流水',
    adjustDrawer.includes('管理员调整') && adjustDrawer.includes('+5'),
    adjustDrawer.replace(/\s+/g, ' ').slice(0, 80))
  await page.keyboard.press('Escape')
  await page.waitForTimeout(1000)
  await page.screenshot({ path: `${SHOT_DIR}/44-后台-手动调整信用分.png`, fullPage: true })

  // ══ 11. 后台评价管理 ══════════════════════════════════════
  console.log('\n[12] 后台评价管理')
  await gotoTable(page, '/admin/content/evaluation')

  const evalRows = await page.locator('.el-table__body tr').count()
  check('后台评价列表有数据', evalRows > 0, `${evalRows} 行`)
  check('列表显示评分星级', (await page.locator('.el-table .el-rate').count()) > 0)
  await page.screenshot({ path: `${SHOT_DIR}/45-后台评价管理.png`, fullPage: true })

  // 只看差评
  await page.click('.filter-bar .el-checkbox')
  await page.waitForTimeout(1500)
  const badRows = await page.locator('.el-table__body tr').count()
  const emptyShown = (await page.locator('.el-table__empty-text').count()) > 0
  check('「只看差评」筛选生效(演示数据里没有差评 → 空列表)',
    badRows === 0 || emptyShown, `${badRows} 行`)
  await page.click('.filter-bar .el-checkbox')
  await page.waitForTimeout(1500)

  // 隐藏一条评价
  const hideBtn = page.locator('.el-table__body button:has-text("隐藏")').first()
  check('列表里有「隐藏」按钮(按钮级权限)', (await hideBtn.count()) > 0)
  await hideBtn.click()
  await page.waitForTimeout(800)
  await page.locator('.el-message-box button:has-text("确定")').click()
  await page.waitForTimeout(2000)

  const hiddenTag = await page.locator('.el-table__body .el-tag:has-text("已隐藏")').count()
  check('隐藏后该行标记为「已隐藏」', hiddenTag > 0, `${hiddenTag} 个标记`)
  const restoreBtn = await page.locator('.el-table__body button:has-text("恢复")').count()
  check('隐藏后按钮变为「恢复」(可以撤销)', restoreBtn > 0)
  await page.screenshot({ path: `${SHOT_DIR}/46-后台-隐藏违规评价.png`, fullPage: true })

  // 恢复
  await page.locator('.el-table__body button:has-text("恢复")').first().click()
  await page.waitForTimeout(800)
  await page.locator('.el-message-box button:has-text("确定")').click()
  await page.waitForTimeout(2000)
  check('恢复后评价回到正常状态',
    (await page.locator('.el-table__body .el-tag:has-text("已隐藏")').count()) === 0)

  // ══ 12. 数据级权限 ════════════════════════════════════════
  console.log('\n[13] 数据级权限')
  await login(page, VILLAGE_ADMIN)
  await gotoTable(page, '/admin/credit/list')

  const vaNames = await page.locator('.user-name').allTextContents()
  check('村管的信用列表里没有金星村的王大山',
    !vaNames.includes('王大山'), vaNames.join(','))
  check('村管能看到本村用户', vaNames.length > 0, vaNames.join(','))

  await gotoTable(page, '/admin/content/evaluation')
  const vaEvalRows = await page.locator('.el-table__body tr').count()
  check('村管的评价列表有数据(本村的)', vaEvalRows > 0, `${vaEvalRows} 行`)

  // ══ 13. 控制台检查 ════════════════════════════════════════
  console.log('\n[14] 控制台检查')
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
  console.log('提示:本脚本会真实提交评价并改动信用分,如需恢复干净演示数据请执行:')
  console.log('      mysql -uroot -proot < sql/05_mock_data.sql')
}
process.exit(failed ? 1 : 0)
