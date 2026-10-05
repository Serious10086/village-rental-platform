/**
 * 批次 4(审核流 + 通知 + 敏感词)端到端验证脚本。
 *
 * 验证真实浏览器里的:个人中心实名状态展示、通知铃铛红点、通知列表、
 * 后台审核列表与详情抽屉、敏感词"试一试"。
 *
 * 用法(需先启动后端 8080 和前端 5173):
 *     cd frontend && node e2e/audit.mjs
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
  console.log('批次 4 —— 审核流 + 通知 + 敏感词 端到端验证')
  console.log('='.repeat(70))

  // ══ 1. 个人中心:待审核状态 ═══════════════════════════════
  console.log('\n[1] 个人中心(赵春梅 13800000009 —— 实名待审核)')
  await login(page, '13800000009')
  await goto(page, `${BASE}/user/profile`)
  await page.waitForTimeout(1200)

  const statusText = await page.textContent('.status-alert')
  check('显示"审核中"状态', statusText?.includes('审核中'), statusText?.slice(0, 60))
  const hasForm = await page.locator('.realname-form').count()
  check('审核中不再显示提交表单', hasForm === 0, `找到 ${hasForm} 个表单`)
  check('显示等待提示块', (await page.locator('.waiting-block').count()) > 0)
  await page.screenshot({ path: `${SHOT_DIR}/16-个人中心-实名审核中.png`, fullPage: true })

  // ══ 2. 个人中心:已实名状态 ═══════════════════════════════
  console.log('\n[2] 个人中心(张建国 13800000003 —— 已实名)')
  await login(page, '13800000003')
  await goto(page, `${BASE}/user/profile`)
  await page.waitForTimeout(1200)

  const verifiedText = await page.textContent('.status-alert')
  check('显示"已完成实名认证"', verifiedText?.includes('已完成实名认证'),
    verifiedText?.slice(0, 60))

  const idCardText = await page.textContent('.verified-info')
  check('身份证号已脱敏展示', idCardText?.includes('********'),
    idCardText?.slice(0, 80))
  await page.screenshot({ path: `${SHOT_DIR}/17-个人中心-已实名.png`, fullPage: true })

  // ══ 3. 通知铃铛与列表 ═════════════════════════════════════
  console.log('\n[3] 消息通知')
  await goto(page, `${BASE}/`)
  await page.waitForTimeout(1500)

  const badgeCount = await page.locator('.notice-badge .el-badge__content').count()
  check('顶栏显示未读通知红点', badgeCount > 0, `找到 ${badgeCount} 个红点`)

  await goto(page, `${BASE}/notice`)
  await page.waitForSelector('.notice-item', { timeout: 10000 })
  const noticeCount = await page.locator('.notice-item').count()
  check('通知列表有内容', noticeCount > 0, `共 ${noticeCount} 条`)

  const unreadCount = await page.locator('.notice-item.is-unread').count()
  check('未读通知有视觉区分', unreadCount > 0, `未读 ${unreadCount} 条`)

  const titleText = await page.textContent('.notice-title')
  check('通知标题渲染', titleText?.length > 0, titleText)
  await page.screenshot({ path: `${SHOT_DIR}/18-消息通知.png`, fullPage: true })

  // 顺序很关键:先测「全部已读」,再测「点击跳转」。
  //
  // 反过来的话,点通知会把它标成已读,未读数归零 →
  // 「全部已读」按钮变成禁用状态,后续的点击就超时了。
  // (张建国只有 1 条未读,点一下就清零了)
  check('未读时「全部已读」按钮可用',
    await page.locator('button:has-text("全部已读")').isEnabled())

  await page.click('button:has-text("全部已读")')
  await page.waitForTimeout(1200)
  const unreadAfter = await page.locator('.notice-item.is-unread').count()
  check('全部已读后无未读条目', unreadAfter === 0, `还剩 ${unreadAfter} 条`)

  // 点一条带跳转路径的通知,应该跳走
  const clickable = page.locator('.notice-item:has(.link-hint)').first()
  if (await clickable.count() > 0) {
    await clickable.click()
    await page.waitForTimeout(1500)
    check('点击通知能跳转', !page.url().includes('/notice'), page.url().replace(BASE, ''))
  }

  // ══ 4. 后台实名审核 ═══════════════════════════════════════
  console.log('\n[4] 后台实名审核(战旗村支书 13800000001)')
  await login(page, '13800000001')
  await goto(page, `${BASE}/admin/audit/realname`)
  await page.waitForTimeout(1500)

  const auditRows = await page.locator('.el-table__row').count()
  check('实名审核列表有数据', auditRows > 0, `共 ${auditRows} 行`)

  const maskedCard = await page.textContent('.el-table__row td:nth-child(3) .num')
  check('列表里身份证号已脱敏', maskedCard?.includes('********'), maskedCard)

  const auditBtns = await page.locator('.el-table__row button:has-text("通过")').count()
  check('待审核行显示通过按钮', auditBtns > 0, `找到 ${auditBtns} 个`)
  await page.screenshot({ path: `${SHOT_DIR}/19-后台-实名审核列表.png`, fullPage: true })

  // 打开详情抽屉 —— 应显示完整身份证号
  await page.click('.el-table__row button:has-text("查看")')
  await page.waitForTimeout(1200)
  const drawerVisible = await page.locator('.el-drawer').isVisible()
  check('详情抽屉打开', drawerVisible)

  const drawerCard = await page.textContent('.el-drawer .el-descriptions')
  check('详情里身份证号未脱敏', drawerCard && !drawerCard.includes('********'),
    drawerCard?.slice(0, 100))

  const hasImages = await page.locator('.id-image').count()
  check('详情显示证件照区域', hasImages === 2, `找到 ${hasImages} 个`)
  await page.screenshot({ path: `${SHOT_DIR}/20-后台-实名审核详情.png`, fullPage: true })

  // 驳回:先测不填原因
  await page.click('.drawer-actions button:has-text("驳回")')
  await page.waitForTimeout(800)
  await page.click('.el-dialog button:has-text("确认驳回")')
  await page.waitForTimeout(800)
  const stillOpen = await page.locator('.el-dialog').isVisible()
  check('驳回不填原因被拦下', stillOpen, '对话框应保持打开')

  // 用快捷理由填入并提交
  await page.click('.quick-tag >> nth=0')
  await page.waitForTimeout(300)
  const remarkValue = await page.inputValue('.el-dialog textarea')
  check('快捷理由能一键填入', remarkValue?.length > 0, remarkValue)

  await page.click('.el-dialog button:has-text("确认驳回")')
  await page.waitForTimeout(1500)
  check('驳回成功后对话框关闭', !(await page.locator('.el-dialog').isVisible()))

  // ══ 5. 后台资源审核 ═══════════════════════════════════════
  console.log('\n[5] 后台资源审核')
  await goto(page, `${BASE}/admin/audit/resource`)
  await page.waitForTimeout(1500)

  const resAuditRows = await page.locator('.el-table__row').count()
  check('资源审核列表有数据', resAuditRows > 0, `共 ${resAuditRows} 行`)

  const resNames = await page.$$eval('.el-table__row .res-name', (els) =>
    els.map((e) => e.textContent.trim()))
  check('显示资源名称', resNames.length > 0, String(resNames.slice(0, 3)))
  await page.screenshot({ path: `${SHOT_DIR}/21-后台-资源审核列表.png`, fullPage: true })

  await page.click('.el-table__row button:has-text("查看")')
  await page.waitForTimeout(1200)
  const resDrawer = await page.locator('.el-drawer').isVisible()
  check('资源详情抽屉打开', resDrawer)
  check('详情显示资源封面', (await page.locator('.detail-cover').count()) > 0)
  check('详情显示资源描述', (await page.locator('.description').count()) > 0)
  await page.screenshot({ path: `${SHOT_DIR}/22-后台-资源审核详情.png`, fullPage: true })

  // 通过
  await page.click('.drawer-actions button:has-text("通过并上架")')
  await page.waitForTimeout(800)
  await page.click('.el-dialog button:has-text("确认通过")')
  await page.waitForTimeout(1500)
  check('资源审核通过', !(await page.locator('.el-dialog').isVisible()))

  // ══ 6. 敏感词管理 ═════════════════════════════════════════
  console.log('\n[6] 敏感词管理(超管 13800000000)')
  await login(page, '13800000000')
  await goto(page, `${BASE}/admin/system/sensitive`)
  await page.waitForTimeout(1500)

  const wordRows = await page.locator('.el-table__row').count()
  check('敏感词列表有数据', wordRows > 0, `共 ${wordRows} 行`)
  check('显示词库大小', (await page.textContent('.intro-alert'))?.includes('个词'),
    (await page.textContent('.intro-alert'))?.slice(0, 80))
  await page.screenshot({ path: `${SHOT_DIR}/23-后台-敏感词管理.png`, fullPage: true })

  // 试一试:替换类
  await page.fill('.test-card input', '有需要的加微信联系我')
  await page.click('.test-card button:has-text("测试")')
  await page.waitForTimeout(1200)

  const testResultText = await page.textContent('.test-result')
  check('试一试返回结果', testResultText?.includes('允许发布') || testResultText?.includes('整条拦截'),
    testResultText?.slice(0, 100))
  check('替换类敏感词被识别', testResultText?.includes('加微信'),
    testResultText?.slice(0, 120))
  check('打码后文本正确', testResultText?.includes('***'),
    testResultText?.slice(0, 150))
  await page.screenshot({ path: `${SHOT_DIR}/24-后台-敏感词试一试.png`, fullPage: true })

  // 试一试:拦截类
  await page.fill('.test-card input', '这是赌博网站大家别信')
  await page.click('.test-card button:has-text("测试")')
  await page.waitForTimeout(1200)
  const blockResult = await page.textContent('.test-result')
  check('拦截类敏感词触发整条拦截', blockResult?.includes('整条拦截'),
    blockResult?.slice(0, 120))

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
  await page.screenshot({ path: `${SHOT_DIR}/error-audit.png`, fullPage: true }).catch(() => {})
} finally {
  await browser.close()
}

console.log('\n' + '='.repeat(70))
console.log(`结果:通过 ${passed} 项,失败 ${failed} 项`)
console.log(`截图已保存到 ${SHOT_DIR}`)
console.log('='.repeat(70))
process.exit(failed ? 1 : 0)
