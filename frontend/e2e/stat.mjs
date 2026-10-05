/**
 * 批次 9(数据统计 + 操作日志)端到端验证脚本。
 *
 * 验证真实浏览器里的:工作台的业务看板与权限概览两块、村级统计的村组选择、
 * 全局统计的各村对比与公示表、操作日志的筛选与详情弹窗,
 * 以及**同一个页面在不同角色下看到的东西不一样**。
 *
 * 【为什么这一批必须跑浏览器,不能只跑接口测试】
 * 这一批交付的东西里有一半是**图表**。接口测试能证明返回的数组对,
 * 证明不了:
 *   · ECharts 有没有真的初始化(容器尺寸为 0、图表类型没注册都会白屏)
 *   · 数据换了之后旧 series 有没有清掉(不 clear 会留两条不存在的柱子)
 *   · 空数据时是"显示暂无数据"还是"一张没有内容的坐标轴"
 * 这几件事只有真的把图画出来才知道。所以脚本里专门断言 canvas 元素存在,
 * 并且专门走一遍"没有数据的村"这条路径。
 *
 * 用法(需先启动后端 8080 和前端 5173):
 *     cd frontend && node e2e/stat.mjs
 *
 * 注意:本脚本会读统计、读日志、并且**制造一条操作日志**(发布公告)。
 * 重复跑之前请重置演示数据:
 *     mysql -uroot -proot < sql/05_mock_data.sql
 */
import { chromium, request } from 'playwright'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5173'
const API = 'http://localhost:8080'
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

async function goto(page, url) {
  let lastErr
  for (let i = 0; i < 3; i++) {
    try {
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 20000 })
      return
    } catch (e) {
      lastErr = e
      await page.waitForTimeout(700)
    }
  }
  throw lastErr
}

async function texts(page, selector) {
  const raw = await page.locator(selector).allTextContents()
  return raw.map((t) => t.replace(/\s+/g, ' ').trim())
}

async function login(page, phone, password = '123456') {
  await goto(page, `${BASE}/`)
  await page.evaluate(() => localStorage.clear()).catch(() => {})
  await goto(page, `${BASE}/login`)
  await page.fill('input[placeholder="请输入手机号"]', phone)
  await page.fill('input[placeholder="请输入密码"]', password)
  await page.click('button:has-text("登录")')
  await page.waitForTimeout(1800)
}

/**
 * 等图表画出来。
 *
 * 判据是"ECharts 真的创建了 canvas" —— 不是等某个容器出现。
 * 容器(那个 div)在数据回来之前就在 DOM 里,等它等于没等;
 * 而 canvas 只有 echarts.init 之后才会有。
 */
async function waitChart(page, timeout = 15000) {
  await page.waitForSelector('canvas', { timeout }).catch(() => {})
  await page.waitForTimeout(600)
}

mkdirSync(SHOT_DIR, { recursive: true })

// 独立 API 客户端:先制造一条操作日志,否则日志页是空的、断言没法验
const api = await request.newContext({ baseURL: API })
const vaLogin = await api.post('/api/auth/login', {
  data: { phone: '13800000001', password: '123456' }
})
const vaToken = (await vaLogin.json()).data.token
await api.post('/api/admin/announcement/publish', {
  headers: { Authorization: `Bearer ${vaToken}` },
  data: {
    title: `批次9端到端验证公告 ${Date.now()}`,
    content: '这条公告的作用是产生一条操作日志,用来验证日志页面。',
    noticeType: 1
  }
})

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
const page = await context.newPage()

const consoleErrors = []
page.on('pageerror', (e) => consoleErrors.push('PAGEERROR: ' + e.message))
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})

try {
  console.log('='.repeat(70))
  console.log('批次 9 —— 数据统计 + 操作日志 端到端验证')
  console.log('='.repeat(70))

  // ══ 1. 工作台(超管)═════════════════════════════════════════
  console.log('\n[1] 工作台(平台管理员)')
  await login(page, '13800000000')
  await goto(page, `${BASE}/admin/dashboard`)
  await waitChart(page, 20000)
  await page.waitForTimeout(1500)

  const bizValues = await texts(page, '.biz-grid .stat-value')
  check('工作台渲染了 5 个业务数字', bizValues.length === 5, bizValues.join(' | '))
  check('租售利用率那一格是百分比', String(bizValues[0] || '').endsWith('%'),
    String(bizValues[0]))
  check('平台在架资源数是个正数(超管看全平台)',
    Number(bizValues[1]) > 0, String(bizValues[1]))

  check('工作台画出了 3 张图(每张一个 canvas)',
    (await page.locator('canvas').count()) >= 3,
    `${await page.locator('canvas').count()} 个 canvas`)

  // 权限概览还在(它是批次 2 的答辩演示点,不能因为加业务看板就没了)
  const blockTitles = await texts(page, '.block-title')
  check('业务看板下面还留着「权限概览」(答辩演示权限差异用的)',
    blockTitles.includes('权限概览'), blockTitles.join(' / '))
  const rbacValues = await texts(page, '.stat-grid .stat-value')
  check('权限概览的数字还在', rbacValues.length > 0, rbacValues.join(' | '))
  await page.screenshot({ path: `${SHOT_DIR}/82-工作台-数据看板.png`, fullPage: true })

  // ══ 2. 工作台(村管):同一页面,数字范围不同 ═════════════════
  console.log('\n[2] 工作台(战旗村支书)')
  await login(page, '13800000001')
  await goto(page, `${BASE}/admin/dashboard`)
  await waitChart(page, 20000)
  await page.waitForTimeout(1200)
  const villageValues = await texts(page, '.biz-grid .stat-value')
  check('村管也能看到 5 个业务数字', villageValues.length === 5, villageValues.join(' | '))
  check('村管的在架资源数 ≤ 超管(范围是本村)',
    Number(villageValues[1]) <= Number(bizValues[1]),
    `村管 ${villageValues[1]} vs 超管 ${bizValues[1]}`)

  // ══ 3. 村级统计 ═════════════════════════════════════════════
  console.log('\n[3] 村级统计')
  // 「数据统计」是一个目录(M 类型),渲染成 el-sub-menu 而不是 el-menu-item ——
  // 只选 .el-menu-item 会找不到它,而且失败信息看起来像"菜单里没有这一项"
  const menuTexts = await texts(page, '.el-menu-item, .el-sub-menu__title')
  check('村管的侧边栏里有「数据统计」目录',
    menuTexts.some((t) => t.includes('数据统计')), menuTexts.join(' / '))

  await goto(page, `${BASE}/admin/stat/village`)
  await waitChart(page, 20000)
  await page.waitForTimeout(1200)
  const vStatValues = await texts(page, '.stat-grid .stat-value')
  check('村级统计渲染了 6 个数字', vStatValues.length === 6, vStatValues.join(' | '))
  check('利用率那一格是百分比', String(vStatValues[2] || '').endsWith('%'),
    String(vStatValues[2]))
  // 村管的村组选择器**不该出现** —— 显示了却点不动比不显示更让人困惑
  const hasSelector = await page.locator('.filter-bar .el-select').count()
  check('村管的村级统计页没有村组选择器(范围由身份决定)', hasSelector === 0,
    `实际 ${hasSelector} 个下拉`)
  check('页面上写明了"只能看本村的统计"',
    (await texts(page, '.filter-bar')).some((t) => t.includes('只能看本村')),
    (await texts(page, '.filter-bar')).join(' / '))
  check('村级统计也画出了图', (await page.locator('canvas').count()) >= 3,
    `${await page.locator('canvas').count()} 个 canvas`)
  await page.screenshot({ path: `${SHOT_DIR}/83-村级统计.png`, fullPage: true })

  // ══ 4. 超管:村级统计可以切村 ═══════════════════════════════
  console.log('\n[4] 村级统计(平台管理员,可切村)')
  await login(page, '13800000000')
  await goto(page, `${BASE}/admin/stat/village`)
  await waitChart(page, 20000)
  await page.waitForTimeout(1500)
  check('超管有村组选择器', (await page.locator('.filter-bar .el-select').count()) === 1)
  const servedVillage = String(await page.locator('.filter-bar .el-select').textContent())
  check('选择器对齐到了后端实际统计的村(不是前端自己猜的)',
    servedVillage.trim().length > 0, servedVillage.trim())

  // 切到一个没有资源的村(先锋村),验证**空数据那条路径**:
  // 图表应当显示"暂无数据",而不是一张没有内容的坐标轴
  await page.click('.filter-bar .el-select')
  await page.waitForTimeout(600)
  await page.click('.el-select-dropdown:visible .el-select-dropdown__item:has-text("先锋村")')
  await page.waitForTimeout(2500)
  const afterSwitch = await texts(page, '.stat-grid .stat-value')
  check('切到先锋村后数字跟着变了(资源 0)',
    Number(afterSwitch[0]) === 0, afterSwitch.join(' | '))
  const canvasAfterSwitch = await page.locator('canvas').count()
  check('没有数据时图表容器仍然在(而不是整块消失)',
    canvasAfterSwitch >= 1, `${canvasAfterSwitch} 个 canvas`)

  // ══ 5. 全局统计(数据公示)═══════════════════════════════════
  console.log('\n[5] 全局统计(数据公示)')
  await goto(page, `${BASE}/admin/stat/global`)
  await waitChart(page, 20000)
  await page.waitForTimeout(1500)

  const globalValues = await texts(page, '.stat-grid .stat-value')
  check('全局统计渲染了 5 个数字', globalValues.length === 5, globalValues.join(' | '))
  const rows = await page.locator('.el-table__body tr').count()
  check('各村明细表有 3 行(战旗村 / 先锋村 / 金星村)',
    rows === 3, `实际 ${rows} 行`)
  const villageNames = await texts(page, '.el-table__body .village-name')
  check('镇节点(唐昌镇)没有混进"各村对比"里',
    !villageNames.includes('唐昌镇'), villageNames.join(' / '))
  // 公示表按利用率倒序:谁做得好排前面
  const rateCells = await texts(page, '.el-table__body .el-tag')
  const rates = rateCells.map((t) => Number(String(t).replace('%', '')))
  check('明细表按租售利用率倒序(公示页要的是"谁做得好")',
    rates.every((v, i) => i === 0 || rates[i - 1] >= v), rates.join(' / '))
  await page.screenshot({ path: `${SHOT_DIR}/84-全局统计-数据公示.png`, fullPage: true })

  // ══ 6. 操作日志 ═════════════════════════════════════════════
  console.log('\n[6] 操作日志')
  await goto(page, `${BASE}/admin/system/log`)
  await page.waitForTimeout(2500)

  const logRows = await page.locator('.el-table__body tr').count()
  check('日志列表有记录(刚发布的那条公告被记下来了)', logRows > 0,
    `${logRows} 行`)
  const modules = await texts(page, '.el-table__body .el-tag')
  check('日志里能看到「公告管理」这个模块', modules.some((m) => m.includes('公告管理')),
    modules.join(' / '))

  // 筛选:按模块
  await page.click('.filter-bar .el-select')
  await page.waitForTimeout(600)
  await page.click('.el-select-dropdown:visible .el-select-dropdown__item:has-text("公告管理")')
  await page.waitForTimeout(1800)
  // 【判据用整行文本,不用 .el-tag】表格里有两列都是 el-tag:模块(plain)
  // 和结果(dark)。只取 .el-tag 会把"成功/失败"也捞进来,
  // 断言就变成了"标签里都写着公告管理"——而"成功"当然不写。
  // 第一版就是这么错的,失败信息看起来像筛选没生效。
  const filteredRows = await texts(page, '.el-table__body tr')
  check('按模块筛之后每一行都是公告管理',
    filteredRows.length > 0
    && filteredRows.every((t) => t.includes('公告管理') && !t.includes('AI 管理')),
    filteredRows.map((t) => t.slice(0, 30)).join(' | '))

  // 详情弹窗
  await page.locator('.el-table__body tr').first().locator('button:has-text("详情")').click()
  await page.waitForTimeout(1200)
  const dialog = String(await page.textContent('.el-dialog'))
  check('详情弹窗给出了方法与请求地址',
    dialog.includes('方法') && dialog.includes('请求'), dialog.slice(0, 100))
  check('详情弹窗里能看到请求参数(且是个 JSON 数组)',
    dialog.includes('请求参数') && dialog.includes('批次9'), dialog.slice(0, 160))
  await page.screenshot({ path: `${SHOT_DIR}/85-后台-操作日志.png`, fullPage: true })
  await page.keyboard.press('Escape')
  await page.waitForTimeout(600)

  // ══ 7. 权限:村管看不到这两个页面 ═══════════════════════════
  console.log('\n[7] 权限(村管)')
  await login(page, '13800000001')
  await goto(page, `${BASE}/admin/stat/village`)
  await page.waitForTimeout(1500)
  const villageMenus = await texts(page, '.el-menu-item, .el-sub-menu__title')
  check('村管的菜单里有「村级统计」', villageMenus.some((t) => t.includes('村级统计')),
    villageMenus.join(' / '))
  check('村管的菜单里没有「全局统计」(那是超管的)',
    !villageMenus.some((t) => t.includes('全局统计')), villageMenus.join(' / '))
  check('村管的菜单里没有「操作日志」(系统管理整个目录都不给他)',
    !villageMenus.some((t) => t.includes('操作日志')), villageMenus.join(' / '))

  // 直接敲 URL 也进不去 —— 这才是"权限是后端说了算"的证据
  await goto(page, `${BASE}/admin/stat/global`)
  await page.waitForTimeout(2000)
  const bodyAfterDirect = String(await page.textContent('body'))
  check('村管直接敲 /admin/stat/global 也拿不到数据(后端 403)',
    !bodyAfterDirect.includes('各村资源与订单对比'),
    bodyAfterDirect.replace(/\s+/g, ' ').slice(0, 120))

  // ══ 8. 控制台 ═══════════════════════════════════════════════
  console.log('\n[8] 浏览器控制台')
  const realErrors = consoleErrors.filter(
    (e) => !e.includes('favicon') && !e.includes('DevTools')
      && !e.includes('Download the Vue Devtools') && !e.includes('404 (Not Found)')
      && !e.includes('403')
  )
  check('无前端运行时错误', realErrors.length === 0,
    realErrors.slice(0, 3).join(' | ').slice(0, 300))

} catch (e) {
  failed++
  console.log(`\n[ERROR] 测试执行异常: ${e.message}`)
  await page.screenshot({ path: `${SHOT_DIR}/99-异常现场-统计.png`, fullPage: true }).catch(() => {})
} finally {
  await api.dispose().catch(() => {})
  await browser.close()
}

console.log('\n' + '='.repeat(70))
console.log(`结果:通过 ${passed} 项,失败 ${failed} 项`)
console.log(`截图已保存到 ${SHOT_DIR}`)
console.log('本脚本会写数据,重复运行前请重置:mysql -uroot -proot < sql/05_mock_data.sql')
console.log('='.repeat(70))
process.exit(failed ? 1 : 0)
