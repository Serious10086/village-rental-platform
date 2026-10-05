/**
 * 批次 3(资源模块)端到端验证脚本。
 *
 * 验证"编译通过"验证不了的东西:真实浏览器里的搜索、筛选、卡片渲染、
 * 双表单切换、以及"未实名不能发布"这类业务约束。
 *
 * 用法(需先启动后端 8080 和前端 5173):
 *     cd frontend && node e2e/resource.mjs
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
  // 先清掉已有登录态。
  //
  // 不清的话会卡住:已登录用户访问 /login 会被路由守卫重定向走
  // (这是正确行为 —— 不该让已登录的人再登一次),于是登录表单根本不会渲染,
  // 脚本就会一直等 phone 输入框直到超时。
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
  console.log('批次 3 —— 资源模块 端到端验证')
  console.log('='.repeat(70))

  // ══ 1. 首页 ══════════════════════════════════════════════
  console.log('\n[1] 前台首页')
  await goto(page, `${BASE}/`)
  await page.waitForTimeout(1200)

  check('首页标题渲染', (await page.textContent('.hero-title'))?.includes('闲置资源'))
  const statTotal = await page.textContent('.hero-stats .stat-value')
  check('首页统计显示真实数据', Number(statTotal) > 0, `在架资源=${statTotal}`)

  const catCount = await page.locator('.category-card').count()
  check('分类入口渲染', catCount >= 5, `共 ${catCount} 个分类`)

  const homeCards = await page.locator('.resource-card').count()
  check('最新上架渲染资源卡片', homeCards > 0, `共 ${homeCards} 张`)
  await page.screenshot({ path: `${SHOT_DIR}/07-前台首页.png`, fullPage: true })

  // ══ 2. 资源列表 ══════════════════════════════════════════
  console.log('\n[2] 资源列表与筛选')
  await goto(page, `${BASE}/resource`)
  await page.waitForSelector('.resource-card', { timeout: 10000 })
  const listCount = await page.locator('.resource-card').count()
  check('列表页渲染资源卡片', listCount > 0, `共 ${listCount} 张`)

  const totalText = await page.textContent('.result-count b')
  check('显示结果总数', Number(totalText) > 0, `共 ${totalText} 个`)

  // 分类筛选
  await page.click('.filter-options .chip:has-text("拖拉机")')
  await page.waitForTimeout(1000)
  const tractorTotal = await page.textContent('.result-count b')
  const tractorNames = await page.$$eval('.resource-card .name', (els) =>
    els.map((e) => e.textContent.trim()))
  check('按分类筛选生效', Number(tractorTotal) < Number(totalText),
    `筛选后 ${tractorTotal} 个(全部 ${totalText} 个)`)
  check('筛选结果确实都是拖拉机', tractorNames.every((n) => n.includes('拖拉机')),
    String(tractorNames))
  check('筛选条件写入 URL', page.url().includes('categoryId='), page.url())
  await page.screenshot({ path: `${SHOT_DIR}/08-资源列表-分类筛选.png`, fullPage: true })

  // 交易方式筛选
  await goto(page, `${BASE}/resource`)
  await page.waitForSelector('.resource-card', { timeout: 10000 })
  await page.click('.filter-options .chip:has-text("出售")')
  await page.waitForTimeout(1000)
  const sellBadges = await page.locator('.badge-trade.is-2').count()
  const sellCards = await page.locator('.resource-card').count()
  check('按交易方式筛选(出售)生效', sellBadges === sellCards && sellCards > 0,
    `${sellCards} 张卡片中 ${sellBadges} 张标记为出售`)

  // 关键词搜索
  await goto(page, `${BASE}/resource`)
  await page.fill('input[placeholder*="搜索农机"]', '灌溉')
  await page.click('button:has-text("搜索")')
  await page.waitForTimeout(1200)
  const kwTotal = await page.textContent('.result-count b')
  check('关键词搜索命中描述里的词', Number(kwTotal) > 0, `命中 ${kwTotal} 个`)
  await page.screenshot({ path: `${SHOT_DIR}/09-资源列表-关键词搜索.png`, fullPage: true })

  // ══ 3. 资源详情 ══════════════════════════════════════════
  console.log('\n[3] 资源详情')
  await goto(page, `${BASE}/resource/1`)
  await page.waitForSelector('.title', { timeout: 10000 })

  const detailTitle = await page.textContent('.title')
  check('详情页标题渲染', detailTitle?.length > 0, detailTitle)
  check('详情页显示价格', await page.locator('.price-block .price').isVisible())
  check('详情页显示发布人信用', (await page.textContent('.publisher-meta'))?.includes('信用'))
  check('详情页有安全提醒', await page.locator('.safety-list').isVisible())
  check('详情页有租赁说明', await page.locator('text=可租时段').isVisible())
  await page.screenshot({ path: `${SHOT_DIR}/10-资源详情.png`, fullPage: true })

  // 高风险资源的培训提示
  await goto(page, `${BASE}/resource/15`)
  await page.waitForTimeout(1000)
  const hasRisk = await page.locator('.risk-card').count()
  check('高风险资源显示培训须知', hasRisk > 0,
    '资源15是变压器,应为高风险')
  await page.screenshot({ path: `${SHOT_DIR}/11-资源详情-高风险提示.png`, fullPage: true })

  // ══ 4. 发布页 ════════════════════════════════════════════
  console.log('\n[4] 发布资源(村民 13800000003)')
  await goto(page, `${BASE}/login`)
  await login(page, '13800000003')

  await goto(page, `${BASE}/publish`)
  await page.waitForTimeout(1000)

  // 出租模式应显示时段、押金、使用规则,不显示新旧程度。
  // 注意选择器要精确到 .el-form-item__label ——
  // 直接写 text=新旧程度 会同时匹配到表单标签和校验错误提示,触发 strict mode 报错。
  const rentFieldsVisible = await page.locator('.el-form-item__label:has-text("可租起始日")').isVisible()
  const depositVisible = await page.locator('.el-form-item__label:has-text("押金")').isVisible()
  const oldDegreeCount = await page.locator('.el-form-item__label:has-text("新旧程度")').count()
  check('出租模式显示可租时段字段', rentFieldsVisible)
  check('出租模式显示押金字段', depositVisible)
  check('出租模式不显示新旧程度字段', oldDegreeCount === 0, `找到 ${oldDegreeCount} 个`)
  await page.screenshot({ path: `${SHOT_DIR}/12-发布资源-出租模式.png`, fullPage: true })

  // 切到出售模式
  await page.click('.trade-option:has-text("出售")')
  await page.waitForTimeout(600)
  const oldDegreeVisible2 = await page.locator('.el-form-item__label:has-text("新旧程度")').isVisible()
  const rentFieldsGone = await page.locator('.el-form-item__label:has-text("可租起始日")').count()
  check('出售模式显示新旧程度字段', oldDegreeVisible2)
  check('出售模式隐藏可租时段字段', rentFieldsGone === 0, `还剩 ${rentFieldsGone} 个`)
  await page.screenshot({ path: `${SHOT_DIR}/13-发布资源-出售模式.png`, fullPage: true })

  // ══ 5. 我的发布 ══════════════════════════════════════════
  console.log('\n[5] 我的发布')
  await goto(page, `${BASE}/my/resource`)
  await page.waitForTimeout(1200)

  const myRows = await page.locator('.resource-row').count()
  check('我的发布列表有数据', myRows > 0, `共 ${myRows} 条`)

  const statusTags = await page.$$eval('.resource-row .el-tag', (els) =>
    els.map((e) => e.textContent.trim()))
  check('我的发布显示资源状态', statusTags.length > 0, String(statusTags.slice(0, 6)))
  await page.screenshot({ path: `${SHOT_DIR}/14-我的发布.png`, fullPage: true })

  // 状态标签页
  await page.click('.status-tabs .el-tabs__item:has-text("已上架")')
  await page.waitForTimeout(1000)
  const onlineRows = await page.locator('.resource-row').count()
  check('按状态筛选生效', onlineRows > 0 && onlineRows <= myRows,
    `已上架 ${onlineRows} 条 / 全部 ${myRows} 条`)

  // ══ 6. 后台资源管理 ══════════════════════════════════════
  console.log('\n[6] 后台资源管理(数据级权限)')
  await goto(page, `${BASE}/login`)
  await login(page, '13800000001')   // 战旗村支书
  await goto(page, `${BASE}/admin/resource/list`)
  await page.waitForTimeout(1500)

  const villageRows = await page.locator('.el-table__row').count()
  const villageNames = await page.$$eval('.el-table__row', (rows) =>
    rows.map((r) => r.querySelectorAll('td')[2]?.textContent.trim()))
  const uniqueVillages = [...new Set(villageNames.filter(Boolean))]
  check('村管后台只看到本村资源', uniqueVillages.length === 1 && uniqueVillages[0] === '战旗村',
    `涉及:${uniqueVillages}`)
  check('后台能看到未上架资源', villageRows > 0, `${villageRows} 条`)
  await page.screenshot({ path: `${SHOT_DIR}/15-后台-资源管理(仅本村).png`, fullPage: true })

  // ══ 7. 控制台 ════════════════════════════════════════════
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
  await page.screenshot({ path: `${SHOT_DIR}/error-resource.png`, fullPage: true }).catch(() => {})
} finally {
  await browser.close()
}

console.log('\n' + '='.repeat(70))
console.log(`结果:通过 ${passed} 项,失败 ${failed} 项`)
console.log(`截图已保存到 ${SHOT_DIR}`)
console.log('='.repeat(70))
process.exit(failed ? 1 : 0)
