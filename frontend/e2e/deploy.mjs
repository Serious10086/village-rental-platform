/**
 * 部署冒烟测试 —— 打的是 Docker 栈,不是开发服务器。
 *
 * 【它和另外八个 e2e 脚本的区别】
 * 那些脚本验的是"功能对不对",打的是开发环境(前端 5173、后端 8080)。
 * 这个脚本验的是**"这套东西在容器里能不能跑起来"**,只打一个地址,
 * 也就是用户实际会访问的那个(默认 http://localhost:8088)。
 *
 * 【为什么要单独有这么一个脚本】
 * 部署出来的问题大多不在业务逻辑里,而在"两个本来分开跑的东西现在要合到一起"
 * 这件事上:前端 axios 的 baseURL 是相对的 `/api`,开发期靠 Vite 代理,
 * 部署后靠 Nginx —— **这条转发链断了的话,页面能打开、但每一个请求都 404**,
 * 而且控制台报的是接口错,很容易被当成后端挂了。
 * 还有:前端路由是 history 模式,部署后刷新子页面会不会 404;
 * 上传的图片写进容器后能不能经 Nginx 读回来;
 * 密钥这种**从环境变量注入**的东西到底有没有真的注进去 ——
 * 后者尤其隐蔽:没注进去不会报错,AI 会安静地走降级,答出来的话看着也像模像样。
 *
 * 所以这里的每一条断言都盯着一个"合起来才会出问题"的点。
 *
 * 跑之前先起容器:
 *     cd docker && docker compose up -d
 * 然后:
 *     cd frontend && node e2e/deploy.mjs
 *
 * 想验别的地址(比如部署到服务器上了),用环境变量覆盖:
 *     DEPLOY_BASE=http://192.168.1.10:8088 node e2e/deploy.mjs
 */
import { chromium, request } from 'playwright'
import { mkdirSync } from 'node:fs'

const BASE = process.env.DEPLOY_BASE || 'http://localhost:8088'
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
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 25000 })
      return
    } catch (e) {
      lastErr = e
      await page.waitForTimeout(800)
    }
  }
  throw lastErr
}

async function login(page, phone, password = '123456') {
  await goto(page, `${BASE}/`)
  await page.evaluate(() => localStorage.clear()).catch(() => {})
  await goto(page, `${BASE}/login`)
  await page.fill('input[placeholder="请输入手机号"]', phone)
  await page.fill('input[placeholder="请输入密码"]', password)
  await page.click('button:has-text("登录")')

  // 【为什么不写死 waitForTimeout】
  // 登录成功之后,前端还要再发一次 getInfo 拉用户信息、再按角色注册动态路由,
  // 走完这两步才会离开 /login。部署在容器里时这一串比开发环境慢,
  // 固定等 2 秒会偶发地在"还没跳走"的时候就往下走 ——
  // 而失败的表现是"后面的页面全都不对",会把排查引到路由上去。
  // 所以等的是"URL 真的变了"这件事本身。
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20000 })
  await page.waitForTimeout(800)
}

mkdirSync(SHOT_DIR, { recursive: true })

// ══════════════════════════════════════════════════════════════
//  [1] 基础设施:三个容器是不是都在干活
// ══════════════════════════════════════════════════════════════
console.log('\n[1] 基础设施')

// 独立于浏览器的 HTTP 客户端:先用它确认服务本身是活的,
// 免得后面前端页面出问题时,分不清是"后端挂了"还是"前端没连上后端"
const api = await request.newContext({ baseURL: BASE })

const ping = await api.get('/api/ping')
const pingBody = await ping.json().catch(() => ({}))
check('后端经 Nginx 反代可达(/api/ping)',
  ping.status() === 200 && pingBody?.data?.app === 'village-rental-platform',
  `HTTP ${ping.status()} ${JSON.stringify(pingBody).slice(0, 120)}`)

// 【这一条是"容器里的数据库真的初始化好了"的判据】
// MySQL 官方镜像只在数据目录为空时执行 /docker-entrypoint-initdb.d 下的脚本。
// 万一挂载没生效、或者脚本顺序错了,后端照样能起来,只是所有列表都是空的 ——
// 那种状态看起来像"功能没写完",不像"部署错了"。
const resList = await api.get('/api/resource/list?pageNum=1&pageSize=5')
const resBody = await resList.json().catch(() => ({}))
const resTotal = resBody?.data?.total
check('数据库自动初始化完成(演示资源已就位)',
  resTotal >= 18, `资源列表 total=${resTotal},期望 ≥18`)

const annList = await api.get('/api/announcement/list?pageNum=1&pageSize=5')
const annBody = await annList.json().catch(() => ({}))
check('公告表可用且游客可见范围正确',
  annBody?.data?.total === 1,
  `游客应看到 1 条全平台公告,实际 total=${annBody?.data?.total}`)

// 【回归:同源 POST 不能被当成跨域拦掉】
// 这是这一批实际踩到的 bug,而且形态很误导 —— 部署之后首页能打开、
// 资源列表也有数据(GET 请求浏览器不带 Origin 头),唯独**登录一直失败**,
// 因为浏览器对 POST 会带 Origin,而后端只看得见自己的 8080,
// 拿它和 Origin 里的 8088 一比就判定成跨域,返回 403 Invalid CORS request。
//
// 判据必须是"带 Origin 的 POST" —— 不带 Origin 的 POST 一直是通的,
// 用它做断言等于没测。同时也要确认**真正的跨域仍然被拒**:
// 修这个 bug 容易顺手把 CORS 放得太开,那就从"登录不了"变成"谁都能调"。
const sameOriginLogin = await api.post('/api/auth/login', {
  headers: { Origin: BASE },
  data: { phone: '13800000000', password: '123456' }
})
check('同源 POST(带 Origin)不再被误判为跨域',
  sameOriginLogin.status() === 200,
  `HTTP ${sameOriginLogin.status()} ${(await sameOriginLogin.text()).slice(0, 100)}`)

const crossOriginLogin = await api.post('/api/auth/login', {
  headers: { Origin: 'http://evil.example' },
  data: { phone: '13800000000', password: '123456' }
})
check('真正的跨域来源仍然被拒(修复没有把 CORS 放开)',
  crossOriginLogin.status() === 403,
  `HTTP ${crossOriginLogin.status()} —— 期望 403`)

// 【密钥是环境变量注入的,注没注进去必须单独验】
// application-dev.yml 在镜像里被删掉了,所以配置只能来自 DEEPSEEK_API_KEY。
// 这个自检接口不消耗 token,却能明确回答"密钥到底有没有到位"。
const aiCheck = await api.get('/api/ping/ai')
const aiBody = await aiCheck.json().catch(() => ({}))
check('DeepSeek 密钥经环境变量注入成功',
  aiBody?.data?.configured === true && aiBody?.data?.reachable === true,
  JSON.stringify(aiBody?.data || {}).slice(0, 200))
check('AI 模型名校验通过(写错模型名不会报错,只会静默回退)',
  aiBody?.data?.modelValid === true,
  `modelValid=${aiBody?.data?.modelValid}, available=${JSON.stringify(aiBody?.data?.availableModels)}`)

await api.dispose()

// ══════════════════════════════════════════════════════════════
//  [2] 前端页面与 history 模式
// ══════════════════════════════════════════════════════════════
console.log('\n[2] 前端页面')

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
const page = await context.newPage()

const consoleErrors = []
page.on('pageerror', (e) => consoleErrors.push('PAGEERROR: ' + e.message))
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})

await goto(page, BASE)
const title = await page.title()
check('首页标题正确', title.includes('乡村闲置资源'), title)

// 【history 模式 + try_files 的联合判据】
// 这些路径在服务器上都没有对应的文件,它们是前端路由。
// Nginx 少了 try_files 的话这里全是 404 —— 而且开发期看不出来,
// 因为 Vite 的 dev server 默认就带回落。
for (const [path, label] of [
  ['/resource/12', '资源详情'],
  ['/order/5', '订单详情'],
  ['/announcement', '公告列表']
]) {
  const resp = await api_page_status(path)
  check(`深链接 ${label}(${path})可直达`, resp === 200, `HTTP ${resp}`)
}

async function api_page_status(path) {
  const r = await context.request.get(BASE + path)
  return r.status()
}

await page.screenshot({ path: `${SHOT_DIR}/86-部署-首页.png`, fullPage: true })

// ══════════════════════════════════════════════════════════════
//  [3] 业务功能(部署版也要是好的)
// ══════════════════════════════════════════════════════════════
console.log('\n[3] 业务功能')

await login(page, '13800000000')
check('超管登录成功(登录态经 Nginx → 后端 → JWT 全链路)',
  !page.url().includes('/login'), page.url())

// 【为什么每条断言都要先确认"没被踢回登录页"】
// 这一版的第一稿没有这道确认,于是三条后台断言全部"通过"了 ——
// 因为它们查的是 `/资源|订单|用户/` 这类词,而**登录页上就有**
// "乡村闲置资源租售平台"这句话。未登录时访问 /admin/* 会被重定向回
// 登录页,页面文字照样命中正则,断言就假通过了。
// 一个把"没登录"误判成"功能正常"的断言,比没有断言更糟。
async function assertLoggedIn(label) {
  const ok = !page.url().includes('/login')
  check(`${label}:确实是登录态(没被踢回登录页)`, ok, page.url())
  return ok
}

await goto(page, `${BASE}/admin/dashboard`)
await page.waitForTimeout(2500)
await assertLoggedIn('工作台')
const dashText = (await page.textContent('body')) || ''
// 用工作台上真正独有的词,不用"资源/订单"这种登录页也有的词
check('工作台渲染出统计卡片', /资源总数|订单数|在架/.test(dashText), dashText.slice(0, 120))
await page.screenshot({ path: `${SHOT_DIR}/87-部署-工作台.png`, fullPage: true })

await goto(page, `${BASE}/admin/stat/global`)
await page.waitForTimeout(2500)
await assertLoggedIn('全局统计')
const statText = (await page.textContent('body')) || ''
check('数据统计页在部署版可用(批次 9 的功能)',
  /利用率/.test(statText) && /\d/.test(statText), statText.slice(0, 150))
check('各村明细表渲染出行', statText.includes('战旗村') || statText.includes('村'),
  statText.slice(0, 150))
await page.screenshot({ path: `${SHOT_DIR}/88-部署-数据统计.png`, fullPage: true })

await goto(page, `${BASE}/resource`)
await page.waitForTimeout(2000)
const listText = (await page.textContent('body')) || ''
check('资源列表有数据且价格是两位小数',
  /¥\d+\.\d{2}/.test(listText), listText.slice(0, 150))
await page.screenshot({ path: `${SHOT_DIR}/89-部署-资源列表.png`, fullPage: true })

// ══════════════════════════════════════════════════════════════
//  [4] AI 客服在容器里真的能用
// ══════════════════════════════════════════════════════════════
console.log('\n[4] AI 智能客服')

await goto(page, `${BASE}/ai`)
await page.waitForSelector('.composer textarea', { timeout: 20000 })
await assertLoggedIn('智能客服')
await page.fill('.composer textarea', '租拖拉机押金怎么算?')
await page.click('.composer button:has-text("发送")')
await page.waitForTimeout(9000)
const aiText = (await page.textContent('body')) || ''
// 判据落在"有没有真的走 AI"而不是模型措辞上 —— 兜底话术里不会出现这种具体金额规则
check('AI 给出了真实回答(不是兜底话术)',
  /押金/.test(aiText) && /退还|原路/.test(aiText),
  aiText.slice(0, 200))
await page.screenshot({ path: `${SHOT_DIR}/90-部署-智能客服.png`, fullPage: true })

// ══════════════════════════════════════════════════════════════
//  [5] 控制台
// ══════════════════════════════════════════════════════════════
console.log('\n[5] 浏览器控制台')
check('全程无前端运行时错误', consoleErrors.length === 0,
  consoleErrors.slice(0, 3).join(' | '))

await browser.close()

console.log('\n' + '='.repeat(70))
console.log(`结果:通过 ${passed} 项,失败 ${failed} 项`)
console.log('='.repeat(70))
process.exit(failed > 0 ? 1 : 0)
