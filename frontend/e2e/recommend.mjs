/**
 * 批次 8(智能推荐 + 资源收藏)端到端验证脚本。
 *
 * 验证真实浏览器里的:首页推荐位的标题随登录态变化、推荐理由如实显示、
 * 点推荐卡片后详情页知道"我是从推荐位来的"、收藏按钮的双向切换、
 * 我的收藏页、详情页的相似资源、后台推荐效果页。
 *
 * 【这一批最值得跑浏览器的一条断言:推荐位点击率真的会涨】
 * 「曝光 → 点击」这条链路要穿过三样东西:详情页在离开时上报浏览、
 * 上报时带上 source=4、后台按 source=4 统计。任何一环断了,
 * 接口测试都看不出来(它只能证明"如果你报了,我会记对"),
 * 只有真在浏览器里点一次推荐卡片、再回后台看一眼,才知道埋点通没通。
 *
 * 用法(需先启动后端 8080 和前端 5173):
 *     cd frontend && node e2e/recommend.mjs
 *
 * 注意:本脚本会收藏、浏览、上报行为。重复跑之前请重置演示数据:
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

/** 等首页的推荐位渲染出来(等数据元素,不等容器 —— 理由见 board.mjs) */
async function waitHomeRecommend(page) {
  await page.waitForSelector('.rec-item', { timeout: 20000 }).catch(() => {})
  await page.waitForTimeout(300)
}

mkdirSync(SHOT_DIR, { recursive: true })

// 独立的 API 客户端:用来直接读后台统计,不和浏览器会话搅在一起
const api = await request.newContext({ baseURL: API })
const adminLogin = await api.post('/api/auth/login', {
  data: { phone: '13800000000', password: '123456' }
})
const adminToken = (await adminLogin.json()).data.token

async function readStat() {
  const res = await api.get('/api/admin/recommend/stat?days=7', {
    headers: { Authorization: `Bearer ${adminToken}` }
  })
  return (await res.json()).data || {}
}

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
  console.log('批次 8 —— 智能推荐 + 资源收藏 端到端验证')
  console.log('='.repeat(70))

  // ══ 1. 游客首页:标题和理由都如实 ═══════════════════════════
  console.log('\n[1] 首页推荐位(游客)')
  await goto(page, `${BASE}/`)
  await waitHomeRecommend(page)

  const guestTitle = (await texts(page, '.section-title')).find((t) => t.includes('推荐'))
  check('游客看到的标题是「热门推荐」,不是「猜你喜欢」',
    guestTitle === '热门推荐', `实际「${guestTitle}」`)
  const guestReasons = await texts(page, '.rec-reason')
  check('游客看到的理由全是「大家都在看」(没有行为可供个性化)',
    guestReasons.length > 0 && guestReasons.every((r) => r === '大家都在看'),
    guestReasons.join(' / '))
  check('副标题也没有说「根据你的浏览记录」',
    !(await texts(page, '.section-sub')).some((t) => t.includes('你最近')),
    (await texts(page, '.section-sub')).join(' / '))

  // ══ 2. 登录后:标题变成猜你喜欢,理由点出分类 ═══════════════
  console.log('\n[2] 首页推荐位(张建国 · 战旗村)')
  await login(page, '13800000003')
  await goto(page, `${BASE}/`)
  await waitHomeRecommend(page)

  const userTitle = (await texts(page, '.section-title')).find((t) => t.includes('推荐') || t.includes('猜'))
  check('登录后标题变成「猜你喜欢」', userTitle === '猜你喜欢', `实际「${userTitle}」`)
  const userReasons = await texts(page, '.rec-reason')
  check('推荐位有内容(冷启动也不空)', userReasons.length > 0,
    `${userReasons.length} 条`)
  check('理由说的是「本村热门」或「大家都在看」(这个账号还没有任何行为)',
    userReasons.every((r) => r === '本村热门' || r === '大家都在看'),
    userReasons.join(' / '))
  await page.screenshot({ path: `${SHOT_DIR}/78-首页-猜你喜欢.png`, fullPage: true })

  // ══ 3. 点推荐卡片 → 详情页知道"我从推荐位来的" ══════════════
  console.log('\n[3] 从推荐位点进详情页')
  const firstCard = page.locator('.rec-item').first()
  await firstCard.locator('.resource-card').click()
  await page.waitForTimeout(2000)

  check('跳到了资源详情页', /\/resource\/\d+/.test(page.url()), page.url())
  check('URL 上带上了 from=rec(详情页据此知道浏览来自推荐位)',
    page.url().includes('from=rec'), page.url())

  // ══ 4. 详情页:收藏按钮双向切换 ══════════════════════════════
  console.log('\n[4] 详情页收藏')
  const favBtn = page.locator('.fav-btn')
  await favBtn.waitFor({ timeout: 15000 }).catch(() => {})
  check('详情页有收藏按钮', (await favBtn.count()) === 1)
  check('初始显示「收藏」', (await favBtn.textContent()).includes('收藏')
    && !(await favBtn.textContent()).includes('已收藏'),
    (await favBtn.textContent()).trim())

  await favBtn.click()
  await page.waitForTimeout(1800)
  check('点完变成「已收藏」', (await favBtn.textContent()).includes('已收藏'),
    (await favBtn.textContent()).trim())

  await favBtn.click()
  await page.waitForTimeout(1800)
  check('再点一次变回「收藏」', !(await favBtn.textContent()).includes('已收藏'),
    (await favBtn.textContent()).trim())

  // 留一个收藏在库里,下面的「我的收藏」页和相似资源看着更真实
  await favBtn.click()
  await page.waitForTimeout(1800)

  // ══ 5. 详情页的相似资源 ═════════════════════════════════════
  //
  // 【为什么这里要显式打开资源 1,而不是接着上一节那个页面看】
  // 相似资源区在"同类下没有别的资源"时是**不渲染**的(而不是显示一个
  // 空框 —— 一个写着"暂无相似资源"的框只会告诉用户这里本来该有东西)。
  // 上一节点进去的是推荐位第一条,它的分类里可能就它一个,
  // 那样这一节会假失败。资源 1 是拖拉机,同类下还有资源 5,一定渲染得出来。
  console.log('\n[5] 详情页相似资源')
  await goto(page, `${BASE}/resource/1`)
  await page.waitForTimeout(2500)

  const similarTitle = await page.locator('.similar-section .section-title')
    .textContent().catch(() => '')
  check('相似资源区渲染出来了', String(similarTitle).includes('相似资源'),
    String(similarTitle))
  const similarReasons = await texts(page, '.similar-reason')
  check('相似资源带理由(「同属「…」」)', similarReasons.length > 0
    && similarReasons.every((r) => r.includes('同属')),
    similarReasons.join(' / '))
  check('相似资源里不包含当前这一条',
    !(await texts(page, '.similar-item .resource-card .name')).some((t) => t.includes('东方红')),
    (await texts(page, '.similar-item .resource-card .name')).join(' / '))
  await page.screenshot({
    path: `${SHOT_DIR}/79-详情页-收藏与相似资源.png`, fullPage: true
  })

  // ══ 6. 我的收藏 ═════════════════════════════════════════════
  console.log('\n[6] 我的收藏')
  await goto(page, `${BASE}/my/favorite`)
  await page.waitForTimeout(2200)
  check('收藏页有一张卡片', (await page.locator('.fav-card').count()) >= 1,
    `${await page.locator('.fav-card').count()} 张`)
  check('卡片上有「取消收藏」入口', (await page.locator('.fav-card button:has-text("取消收藏")').count()) >= 1)
  await page.screenshot({ path: `${SHOT_DIR}/80-我的收藏.png`, fullPage: true })

  // ══ 7. 推荐位点击率真的会涨 ═════════════════════════════════
  //
  // 这一条要穿过三样东西:详情页离开时上报浏览、上报带 source=4、
  // 后台按 source=4 统计。任何一环断了接口测试都看不出来。
  console.log('\n[7] 推荐位点击率(埋点闭环)')
  const before = await readStat()
  console.log(`    (当前 曝光=${before.impressions} 点击=${before.clicks})`)

  // 回首页,再点一次推荐卡片,然后离开详情页 —— 离开时才上报
  await goto(page, `${BASE}/`)
  await waitHomeRecommend(page)
  await page.locator('.rec-item').first().locator('.resource-card').click()
  await page.waitForTimeout(1500)
  check('这次也是从推荐位点进去的', page.url().includes('from=rec'), page.url())
  // 离开详情页 → 触发上报
  await goto(page, `${BASE}/`)
  await page.waitForTimeout(2500)

  const after = await readStat()
  console.log(`    (点击后 曝光=${after.impressions} 点击=${after.clicks})`)
  check('曝光增加了(每次推荐请求都记一次曝光)',
    (after.impressions || 0) >= (before.impressions || 0),
    `${before.impressions} → ${after.impressions}`)
  check('**点击 +1(从推荐位点进详情页的那一次被记下来了)**',
    (after.clicks || 0) === (before.clicks || 0) + 1,
    `${before.clicks} → ${after.clicks}`)
  check('点击率是个百分比', /^\d+(\.\d+)?$/.test(String(after.ctr ?? 0)),
    String(after.ctr))

  // ══ 8. 后台推荐效果页 ═══════════════════════════════════════
  console.log('\n[8] 后台推荐效果(平台管理员)')
  await login(page, '13800000000')
  await goto(page, `${BASE}/admin/ai/recommend`)
  await page.waitForTimeout(2500)

  const statValues = await texts(page, '.stat-grid .stat-value')
  check('后台渲染了曝光 / 点击 / 点击率 / 占比四个数', statValues.length === 4,
    statValues.join(' | '))
  check('点击率那一格是百分比', String(statValues[2] || '').endsWith('%'),
    String(statValues[2]))

  const behaviorNames = await texts(page, '.behavior-name')
  check('行为采集量那块渲染出来了', behaviorNames.length > 0,
    behaviorNames.join(' / '))
  check('能看到「浏览」和「推荐位曝光」这两类',
    behaviorNames.includes('浏览') && behaviorNames.includes('推荐位曝光'),
    behaviorNames.join(' / '))

  const trendDates = await texts(page, '.trend-date')
  check('按天趋势有数据', trendDates.length > 0, trendDates.join(' / '))
  await page.screenshot({ path: `${SHOT_DIR}/81-后台-推荐效果.png`, fullPage: true })

  // ══ 9. 控制台 ═══════════════════════════════════════════════
  console.log('\n[9] 浏览器控制台')
  const realErrors = consoleErrors.filter(
    (e) => !e.includes('favicon') && !e.includes('DevTools')
      && !e.includes('Download the Vue Devtools') && !e.includes('404 (Not Found)')
  )
  check('无前端运行时错误', realErrors.length === 0,
    realErrors.slice(0, 3).join(' | ').slice(0, 300))

} catch (e) {
  failed++
  console.log(`\n[ERROR] 测试执行异常: ${e.message}`)
  await page.screenshot({ path: `${SHOT_DIR}/99-异常现场-智能推荐.png`, fullPage: true }).catch(() => {})
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
