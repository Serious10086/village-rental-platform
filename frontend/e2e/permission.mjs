/**
 * 批次 1(认证与权限)端到端验证脚本。
 *
 * 验证的是"编译通过"验证不了的东西:真实浏览器里的登录流程、动态路由注册、
 * 菜单渲染差异、按钮级权限是否真的把按钮从 DOM 里摘掉了。
 *
 * 用系统已装的 Edge(channel: 'msedge'),不额外下载 Chromium。
 *
 * 用法(需先启动后端 8080 和前端 5173):
 *     cd frontend && node e2e/permission.mjs
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

/** 登录并等待进入后台 */
async function login(page, phone, password = '123456', expectAdmin = true) {
  await goto(page, `${BASE}/login`)
  await page.fill('input[placeholder="请输入手机号"]', phone)
  await page.fill('input[placeholder="请输入密码"]', password)
  await page.click('button:has-text("登录")')

  if (expectAdmin) {
    // 等侧边栏出现,说明动态路由已注册、布局已渲染
    await page.waitForSelector('.sidebar-menu', { timeout: 15000 })
  } else {
    // 无后台权限的用户会被守卫重定向走,不会出现侧边栏
    await page.waitForTimeout(1500)
  }
  await page.waitForTimeout(600)
}

/** 退出登录 */
async function logout(page) {
  await page.click('.user-info')
  await page.waitForTimeout(300)
  await page.click('text=退出登录')
  await page.waitForTimeout(300)
  await page.click('button:has-text("退出")')
  await page.waitForURL('**/login', { timeout: 10000 })
}

/** 取侧边栏里所有可见的菜单文本 */
async function getMenuTexts(page) {
  return page.$$eval('.sidebar-menu .el-menu-item, .sidebar-menu .el-sub-menu__title',
    (els) => els.map((e) => e.textContent.trim()))
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
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()

// 收集浏览器控制台错误 —— 前端运行时异常不会体现在 HTTP 状态码里,只能从这里抓
const consoleErrors = []
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text())
})
page.on('pageerror', (err) => consoleErrors.push('PAGEERROR: ' + err.message))

try {
  console.log('='.repeat(70))
  console.log('批次 1 —— 认证与权限 端到端验证')
  console.log('='.repeat(70))

  // ── 1. 登录页 ──────────────────────────────────────────────
  console.log('\n[1] 登录页与未登录拦截')
  await goto(page, `${BASE}/login`)
  const loginTitle = await page.textContent('.brand-title')
  check('登录页标题正确', loginTitle?.includes('乡村闲置资源租售平台'), loginTitle)
  const villageCount = await page.locator('.el-select').count()
  check('注册表单已渲染', villageCount > 0)
  await page.screenshot({ path: `${SHOT_DIR}/01-登录页.png`, fullPage: true })

  // 回归测试:未登录访问后台路径,必须跳登录页,不能显示 404。
  //
  // 这里踩过一个坑:404 兜底路由曾标了 meta.public,
  // 而兜底路由会匹配所有未注册的路径,于是守卫读到的 to.meta.public 是 true,
  // 误判为公开页面放行 —— 用户看到的是一个莫名其妙的 404 而不是登录页。
  // 现在改成按路径前缀判断,加这条用例锁住行为。
  for (const adminPath of ['/admin/dashboard', '/admin/user/list']) {
    await goto(page, `${BASE}${adminPath}`)
    await page.waitForTimeout(900)
    check(`未登录访问 ${adminPath} 跳转到登录页`,
      page.url().includes('/login'), `实际停留在 ${page.url().replace(BASE, '')}`)
  }

  // 未登录访问前台页面不应被拦住
  await goto(page, `${BASE}/`)
  await page.waitForTimeout(600)
  check('未登录可正常访问前台首页', !page.url().includes('/login'), page.url())

  // ── 2. 超级管理员 ──────────────────────────────────────────
  console.log('\n[2] 超级管理员登录(13800000000)')
  await login(page, '13800000000')

  const superMenus = await getMenuTexts(page)
  console.log(`      可见菜单(${superMenus.length}):${superMenus.join(' / ')}`)

  check('能看到「系统管理」', superMenus.some((m) => m.includes('系统管理')),
    superMenus.join(','))
  check('能看到「角色管理」', superMenus.some((m) => m.includes('角色管理')))
  check('能看到「审核管理」', superMenus.some((m) => m.includes('审核管理')))
  check('能看到「数据统计」', superMenus.some((m) => m.includes('数据统计')))
  await page.screenshot({ path: `${SHOT_DIR}/02-超管工作台.png`, fullPage: true })

  // 展开系统管理,截图给答辩用
  await page.click('.sidebar-menu .el-sub-menu__title:has-text("系统管理")')
  await page.waitForTimeout(400)
  await page.screenshot({ path: `${SHOT_DIR}/03-超管-系统管理菜单.png`, fullPage: true })

  // 超管进用户管理,应能看到全部用户
  await goto(page, `${BASE}/admin/user/list`)
  await page.waitForSelector('.el-table__row', { timeout: 10000 })
  const superTotal = Number(await page.textContent('.filter-bar .text-secondary b'))
  const superVillages = await page.$$eval('.el-table__row', (rows) =>
    rows.map((r) => r.querySelectorAll('td')[2]?.textContent.trim()).filter(Boolean)
  )
  const superVillageSet = [...new Set(superVillages)]
  check('超管能看到多个村组的用户', superVillageSet.length > 1,
    `共 ${superTotal} 人,涉及村组:${superVillageSet.join(',')}`)
  await page.screenshot({ path: `${SHOT_DIR}/04-超管-用户列表(全部村组).png`, fullPage: true })

  await logout(page)

  // ── 3. 村级管理员 ──────────────────────────────────────────
  console.log('\n[3] 村级管理员登录(13800000001 / 战旗村支书)')
  await login(page, '13800000001')

  const villageMenus = await getMenuTexts(page)
  console.log(`      可见菜单(${villageMenus.length}):${villageMenus.join(' / ')}`)

  check('看不到「系统管理」', !villageMenus.some((m) => m.includes('系统管理')),
    villageMenus.join(','))
  check('看不到「角色管理」', !villageMenus.some((m) => m.includes('角色管理')))
  check('仍能看到「审核管理」', villageMenus.some((m) => m.includes('审核管理')))
  check('菜单数量比超管少', villageMenus.length < superMenus.length,
    `村管 ${villageMenus.length} vs 超管 ${superMenus.length}`)
  await page.screenshot({ path: `${SHOT_DIR}/05-村管工作台.png`, fullPage: true })

  // ── 4. 数据级权限 ──────────────────────────────────────────
  console.log('\n[4] 数据级权限(村里的用户列表)')
  await goto(page, `${BASE}/admin/user/list`)
  await page.waitForSelector('.el-table__row', { timeout: 10000 })

  const villageTotal = Number(await page.textContent('.filter-bar .text-secondary b'))
  const villageNames = await page.$$eval('.el-table__row', (rows) =>
    rows.map((r) => {
      const cells = r.querySelectorAll('td')
      return cells[2]?.textContent.trim()
    })
  )
  const uniqueVillages = [...new Set(villageNames.filter(Boolean))]

  check('只能看到本村用户(比超管少)', villageTotal < superTotal,
    `村管 ${villageTotal} 人 vs 超管 ${superTotal} 人`)
  check('数据只涉及战旗村', uniqueVillages.length === 1 && uniqueVillages[0] === '战旗村',
    uniqueVillages.join(','))

  // 按钮级:村管没有 user:ban 权限,封禁按钮不应存在
  const banButtons = await page.locator('button:has-text("封禁")').count()
  const noPermTips = await page.locator('text=无操作权限').count()
  check('「封禁」按钮被移除(按钮级权限)', banButtons === 0, `找到 ${banButtons} 个`)
  check('显示「无操作权限」占位', noPermTips > 0, `找到 ${noPermTips} 个`)
  await page.screenshot({ path: `${SHOT_DIR}/06-村管-用户列表(仅本村).png`, fullPage: true })

  // 越权尝试:直接在地址栏加 villageId=3,看服务端是否覆盖
  await goto(page, `${BASE}/admin/user/list?villageId=3`)
  await page.waitForTimeout(800)
  const forcedNames = await page.$$eval('.el-table__row', (rows) =>
    rows.map((r) => r.querySelectorAll('td')[2]?.textContent.trim())
  )
  const forcedUnique = [...new Set(forcedNames.filter(Boolean))]
  check('手动传 villageId=3 无法越权', forcedUnique.length === 1 && forcedUnique[0] === '战旗村',
    `实际看到:${forcedUnique.join(',')}`)

  // ── 5. 超管的封禁按钮应该存在 ──────────────────────────────
  console.log('\n[5] 按钮级权限对照(超管应有封禁按钮)')
  await logout(page)
  await login(page, '13800000000')
  await goto(page, `${BASE}/admin/user/list`)
  await page.waitForSelector('.el-table__row', { timeout: 10000 })
  const superBanButtons = await page.locator('button:has-text("封禁")').count()
  check('超管能看到「封禁」按钮', superBanButtons > 0, `找到 ${superBanButtons} 个`)

  // ── 6. 村民访问后台应被拒 ─────────────────────────────────
  console.log('\n[6] 村民(13800000003)访问后台')
  await logout(page)
  // 村民没有后台角色,登录后不会进入 /admin,所以不等待侧边栏
  await login(page, '13800000003', '123456', false)
  const urlAfterVillagerLogin = page.url()
  check('村民登录后跳到前台而非后台', !urlAfterVillagerLogin.includes('/admin'),
    urlAfterVillagerLogin)

  await goto(page, `${BASE}/admin/dashboard`)
  await page.waitForTimeout(1000)
  check('村民直接访问 /admin/dashboard 被重定向走', !page.url().includes('/admin/dashboard'),
    page.url())

  // ── 7. 控制台错误 ──────────────────────────────────────────
  console.log('\n[7] 浏览器控制台')
  // 过滤掉与业务无关的噪音(如 favicon、Vue devtools 提示)
  const realErrors = consoleErrors.filter(
    (e) => !e.includes('favicon') && !e.includes('DevTools') && !e.includes('Download the Vue Devtools')
  )
  check('无前端运行时错误', realErrors.length === 0,
    realErrors.slice(0, 3).join(' | '))

} catch (e) {
  failed++
  console.log(`\n[ERROR] 测试执行异常: ${e.message}`)
  await page.screenshot({ path: `${SHOT_DIR}/error.png`, fullPage: true }).catch(() => {})
} finally {
  await browser.close()
}

console.log('\n' + '='.repeat(70))
console.log(`结果:通过 ${passed} 项,失败 ${failed} 项`)
console.log(`截图已保存到 ${SHOT_DIR}`)
console.log('='.repeat(70))
process.exit(failed ? 1 : 0)
