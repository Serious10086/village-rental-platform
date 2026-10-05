/**
 * 批次 6c(留言板 + 公告)端到端验证脚本。
 *
 * 验证真实浏览器里的:公告的可见范围随登录身份变化、公告详情的越权访问、
 * 留言板的游客可读/写要登录、发帖与敏感词打码、点赞与回复、
 * 以及后台两个管理页的数据级权限与按钮级权限。
 *
 * 【为什么这一批必须跑浏览器,不能只跑接口测试】
 * 这一批的每个功能都有一个"同一份数据在不同人眼里不一样"的侧面,
 * 而且那个侧面是在渲染层表达的:
 *   同一个 /announcement 页面,游客看到 1 条、战旗村村民看到 5 条、
 *   金星村村民又看到 1 条 —— 页面里没有任何村组选择器,差别全部来自
 *   后端按 token 里的村组筛,前端只是把返回的列表画出来;
 *   留言板上"哪些帖带删除按钮"、"发帖框还是提示语"取决于我是谁;
 *   「系统公告」这个选项在村管的发布对话框里根本就不存在。
 * 接口测试能证明返回的 JSON 对,证明不了界面上摆对了 —— 比如
 * v-hasPerm 把按钮删掉了,接口测试是看不见的。
 *
 * 用法(需先启动后端 8080 和前端 5173):
 *     cd frontend && node e2e/board.mjs
 *
 * 注意:本脚本会写数据(发帖、点赞、回复)。重复跑之前请重置演示数据:
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

/** 把一串元素的文本取出来,空白压平 —— 断言"标题里有没有出现某句话"用 */
async function texts(page, selector) {
  const raw = await page.locator(selector).allTextContents()
  return raw.map((t) => t.replace(/\s+/g, ' ').trim())
}

/**
 * 读"共 N 条"里的数字。
 *
 * 表格只渲染当前页的行(后台 pageSize=10),而留言巡查在本村有 12 条,
 * 数 <tr> 只能数到 10。总数得从这段文案里取,才是在验"后端返回的总数"。
 */
async function totalOf(page) {
  const body = await page.textContent('.table-card, .admin-board, .admin-announcement')
  const m = String(body ?? '').match(/共\s*(\d+)\s*条/)
  return m ? Number(m[1]) : NaN
}

/** 点击一个 ElMessageBox 的按钮。三个分支的按钮文案不同,不能硬编码 */
async function clickBox(page, buttonText) {
  await page.waitForTimeout(600)
  await page.click(`.el-message-box__btns button:has-text("${buttonText}")`)
  await page.waitForTimeout(1200)
}

/**
 * 等"这一次请求真的把内容填进页面了"。
 *
 * 【判据为什么只能是"数据元素出现"】
 * 这几个页面都是同一个结构:先渲染骨架、再由 onMounted 发请求、回来才填内容。
 * 而骨架里**已经包含了空状态和容器** —— .feed / .article / .list-card
 * 都在,el-empty 也在(loading 初始值是 false,所以"空"这个状态
 * 在第一帧就成立了)。于是下面三种"看起来更稳妥"的等法全都是错的:
 *   · 等容器          → 第一帧就满足
 *   · 等空状态        → 第一帧就满足
 *   · 等 loading 遮罩消失 → 遮罩还没被创建的时候也满足
 * 它们都会在请求发出之前返回,断言读到空数组 —— 表现是
 * "游客看到 5 条留言 / 超管看到 7 条公告"偶发地变成 0,而且只在
 * 组件第一次编译、接口慢一点的时候复现,连跑十几轮才踩到一两次。
 *
 * 只有"帖子 / 公告条目 / 表格行"这些**数据元素**在请求回来之前不存在,
 * 拿它们当判据才是确定性的。
 *
 * 【等不到为什么用 catch 吞掉】
 * 吞掉之后往下走,后面那条断言会给出"实际 0 条"的失败信息 ——
 * 比在这里超时中断整段用例更有用(中断会连带吞掉后面所有断言的结果)。
 */
async function waitData(page, selector, timeout = 20000) {
  await page.waitForSelector(selector, { timeout }).catch(() => {})
}

/**
 * 以游客身份打开一个前台页面,返回"确实变成了游客"。
 *
 * 【为什么不只是 goto + localStorage.clear()】
 * `localStorage.clear()` 清掉的是**存储**,不是**已经在内存里的登录态**。
 * 如果紧接着那次导航没有让应用重新启动(或者应用启动得比 clear 晚),
 * 页面就会停在一个中间状态:Pinia 里还登录着,但后续请求已经不带 token ——
 * 表现为发帖框还在(于是看不到「游客可以浏览」那句提示),
 * 而留言列表因为拿不到村组直接失败(于是一条留言也读不到)。
 *
 * 这一节要验的是"游客看到什么",不是"能不能退出登录";
 * 把退出这件事当成**前提**确认掉,才不会让一个前提问题
 * 伪装成三条互不相干的断言失败,把排查引到完全没问题的代码上。
 * 这个中间状态是实测踩到的:连跑 8 轮出现 1 次。
 */
async function gotoAsGuest(page, path) {
  for (let i = 0; i < 3; i++) {
    await goto(page, `${BASE}/`)
    await page.evaluate(() => localStorage.clear())
    await goto(page, `${BASE}${path}`)
    // 先等页面挂载(.composer 在登录态和游客态下都存在),
    // 再看它落在哪一支上
    await page.waitForSelector('.composer', { timeout: 20000 }).catch(() => {})
    if ((await page.locator('.compose-empty').count()) > 0) {
      return true
    }
  }
  return false
}

/**
 * 等某段文字出现在一组元素里 / 从一组元素里消失。
 *
 * 用于"点了个按钮 → 服务端改完 → 前端重新拉列表"这条异步链:
 * 中间隔了一次请求和一次重渲染,睡固定时长是在赌。
 *
 * 这两个是同一条判据的两面,分开写是因为读起来是两件事:
 * "下架之后村民那边就没有它了"和"重新发布之后又有了"。
 * 等不到都会超时中断,而超时中断本身就是一条明确的失败信息
 * ("这个操作之后界面上始终没有/始终还有它"),比"睡完发现断言失败"好定位。
 */
async function waitText(page, selector, text, timeout = 15000) {
  await page.waitForFunction(
    ({ sel, t }) => [...document.querySelectorAll(sel)]
      .some((el) => el.textContent.includes(t)),
    { sel: selector, t: text }, { timeout }
  )
}

async function waitGoneText(page, selector, text, timeout = 15000) {
  await page.waitForFunction(
    ({ sel, t }) => ![...document.querySelectorAll(sel)]
      .some((el) => el.textContent.includes(t)),
    { sel: selector, t: text }, { timeout }
  )
}

/**
 * 等某次接口请求返回,再给页面一拍把结果渲染完。
 *
 * 【什么时候必须用它,而不是等元素】
 * 当"这次请求的正确结果是空的"时 —— 页面上没有任何元素能表达
 * "请求回来了,但没有数据":公告详情页的报错态 el-empty 用的是
 * `v-else-if="!loading"`,而 loading 的初始值是 false,
 * 所以它在**第一帧就已经在页面上了**,拿它当判据等于没等。
 *
 * 【必须在导航之前挂上】:after the fact 的等待会漏掉已经回来的响应。
 * 返回的是一个 promise,调用方在导航之后再 await 它。
 */
function waitApi(page, fragment, timeout = 20000) {
  return page.waitForResponse((r) => r.url().includes(fragment), { timeout })
    .then(() => page.waitForTimeout(400))   // 把这次结果提交到 DOM 的那一拍
    .catch(() => {})
}

/**
 * 等"包含 rowText 的那一行"同时也包含 expectText。
 *
 * 【为什么不能退化成 waitText('.el-table__body tr', '已发布')】
 * 公告列表里本来就有好几行是「已发布」的 —— 那样等会在第一个 tick
 * 就返回,等于没等,后面的断言读到的仍然是改之前的那一行。
 * 锚点必须落在"这一行"上:标题是每行唯一的,所以用标题定位状态。
 */
async function waitTableRow(page, rowText, expectText, timeout = 15000) {
  await page.waitForFunction(
    ({ r, e }) => [...document.querySelectorAll('.el-table__body tr')]
      .some((tr) => tr.textContent.includes(r) && tr.textContent.includes(e)),
    { r: rowText, e: expectText }, { timeout }
  )
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
  console.log('批次 6c —— 留言板 + 公告 端到端验证')
  console.log('='.repeat(70))

  // ══ 1. 游客看公告:只看得到全平台公告 ═══════════════════════
  console.log('\n[1] 村务公告(游客,未登录)')
  await goto(page, `${BASE}/`)
  await page.evaluate(() => localStorage.clear())
  await goto(page, `${BASE}/announcement`)
  await waitData(page, '.notice-item')

  let items = await texts(page, '.notice-item')
  check('游客看得到全平台公告', items.some((t) => t.includes('国庆假期平台服务安排')),
    `实际 ${items.length} 条`)
  check('游客看不到村级公告(战旗村的倡议书不在里面)',
    !items.some((t) => t.includes('闲置农机共享倡议书')))
  check('全平台公告打上了「全平台」标记',
    items.some((t) => t.includes('全平台')))
  check(`游客看到的公告正好 ${items.length} 条,只有全平台那一条`, items.length === 1,
    `实际 ${items.length} 条:${items.map((t) => t.slice(0, 20))}`)
  // 页面里不该有村组选择器 —— 这份列表是按 token 里的村组筛的,不是选出来的
  check('公告页没有村组选择器(可见范围由登录身份决定,不是选出来的)',
    (await page.locator('.notice-page .el-select').count()) === 1,
    '只应该有一个"公告类型"下拉')
  await page.screenshot({ path: `${SHOT_DIR}/61-公告列表-游客视图.png`, fullPage: true })

  // ══ 2. 村民看公告:同一个页面,多出本村的 4 条 ══════════════
  console.log('\n[2] 村务公告(张建国 · 战旗村)')
  await login(page, '13800000003')
  await goto(page, `${BASE}/announcement`)
  await waitData(page, '.notice-item')

  items = await texts(page, '.notice-item')
  check('登录后同一个页面多出了本村公告', items.length === 5,
    `实际 ${items.length} 条:${items.map((t) => t.slice(0, 18))}`)
  check('看到了战旗村的倡议书', items.some((t) => t.includes('闲置农机共享倡议书')))
  check('草稿状态的公告不出现(拟稿那条给管理员看,不给村民看)',
    !items.some((t) => t.includes('拟稿')))
  check('别村的公告不出现', !items.some((t) => t.includes('先锋村共享农具角')))
  check('置顶的两条排在最前面',
    items[0]?.includes('置顶') && items[1]?.includes('置顶'),
    `前两条:${items.slice(0, 2)}`)
  check('公告类型标签渲染出来了',
    items.some((t) => t.includes('安全使用须知')) || items.some((t) => t.includes('农机补贴政策')),
    items.join(' | ').slice(0, 200))
  await page.screenshot({ path: `${SHOT_DIR}/62-公告列表-村民视图.png`, fullPage: true })

  // ══ 3. 公告详情:能读,以及越权读不到 ═══════════════════════
  console.log('\n[3] 公告详情')
  await goto(page, `${BASE}/announcement/3`)
  // 判据是 .article-title —— 它在 <template v-if="data"> 内部,
  // 请求回来之前不存在。这个页面上的其他东西(.detail-page、.article、
  // 报错用的 el-empty)在第一帧就已经在了,拿它们当判据等于没等
  await waitData(page, '.article-title')
  let article = await page.textContent('.article')
  check('本村公告的详情能打开', String(article).includes('农机租用安全须知'),
    String(article).slice(0, 80))
  check('详情页显示了浏览量', String(article).includes('浏览') || /浏览|view|\d+\s*次/.test(String(article)),
    String(article).slice(0, 120))
  await page.screenshot({ path: `${SHOT_DIR}/63-公告详情.png`, fullPage: true })

  // 换个村的账号去读战旗村的公告:后端按"不属于我的村"直接 404,
  // 前端拿不到数据,页面上就不该出现标题 —— 而不是出现标题再显示"无权限"
  await login(page, '13800000007')
  // 这一条只能用"等请求返回":本条的正确结果是**页面上什么都不出现**,
  // 而报错态 el-empty 用的是 v-else-if="!loading",loading 初始为 false,
  // 所以它在第一帧就渲染出来了 —— 页面元素没法表达"请求回来了但没数据"。
  const deniedResp = waitApi(page, '/api/announcement/detail/3')
  await goto(page, `${BASE}/announcement/3`)
  await deniedResp
  article = String(await page.textContent('.detail-page'))
  check('别的村的人打不开这条公告(界面上拿不到标题和正文)',
    !article.includes('农机租用安全须知'), article.slice(0, 120))

  // 金星村自己没有公告,所以他的列表里只剩全平台那一条 ——
  // 同一个 URL、同一个组件,列表内容完全由 token 里的村组决定。
  // 这里等的是"确实只剩一条":等条数从 0 变成 1,而不是睡固定时长。
  await goto(page, `${BASE}/announcement`)
  await waitData(page, '.notice-item')
  check('金星村村民的公告列表里只有全平台那一条',
    (await texts(page, '.notice-item')).length === 1,
    `实际 ${(await texts(page, '.notice-item')).length} 条`)

  // ══ 4. 留言板:游客能读、不能写 ═════════════════════════════
  console.log('\n[4] 村民留言板(游客)')
  // 先把"确实是游客"这个前提确认掉,再看游客读到了什么。
  // 不确认的话,一个没退干净的中间状态会伪装成"读不到留言"+"发帖区
  // 不是提示"两条互不相干的失败,排查时会被引到完全没问题的代码上。
  const isGuest = await gotoAsGuest(page, '/board')
  check('清掉登录态后确实变成了游客(本节其余断言的前提)', isGuest,
    '重试 3 次后页面仍然是登录态 —— 先查登录态清理,再看下面的断言')
  await waitData(page, '.post .content')

  const posts = await texts(page, '.post .content')
  check('游客读得到留言(公开页面,不设登录门槛)', posts.length === 5,
    `实际 ${posts.length} 条`)
  check('已隐藏的留言不出现',
    !posts.some((t) => t.includes('专业办证')), posts.find((t) => t.includes('办证')) ?? '')
  check('发帖区显示的是登录提示,不是输入框',
    (await page.locator('.compose-empty').count()) === 1
    && (await page.locator('.composer textarea').count()) === 0)
  const guestHint = await page.textContent('.compose-empty')
  check('提示语说明了"游客可以浏览"', String(guestHint).includes('游客可以浏览'),
    String(guestHint).slice(0, 60))
  await page.screenshot({ path: `${SHOT_DIR}/64-留言板-游客可读.png`, fullPage: true })

  // ══ 5. 敏感词打码在界面上的样子 ═════════════════════════════
  console.log('\n[5] 敏感词打码(读取时打码)')
  const maskedIdx = posts.findIndex((t) => t.includes('想租拖拉机的别在平台上磨叽'))
  check('含敏感词的留言本身还在(替换类不拦截,只是打码)', maskedIdx >= 0,
    `列表:${posts.map((t) => t.slice(0, 12))}`)
  check('正文里的「加微信」已经在界面上变成星号',
    maskedIdx >= 0 && !posts[maskedIdx].includes('加微信') && posts[maskedIdx].includes('*'),
    maskedIdx >= 0 ? posts[maskedIdx] : '')
  check('这条留言带上了「已过滤」标记',
    (await page.locator('.post', { hasText: '想租拖拉机的别在平台上磨叽' })
      .locator('.filtered-tag').count()) === 1)
  // 这张只截这一条帖(不做整页截图):论文里要给人看的是"原文存着、
  // 界面上打成了星号、还挂了已过滤标记"这个局部,整页截图里那几行字太小了
  await page.locator('.post', { hasText: '想租拖拉机的别在平台上磨叽' }).first()
    .screenshot({ path: `${SHOT_DIR}/67-留言板-敏感词打码.png` })

  // ══ 6. 村民发帖 ═══════════════════════════════════════════
  console.log('\n[6] 发帖(张建国 · 战旗村)')
  await login(page, '13800000003')
  await goto(page, `${BASE}/board`)
  // 等的是"这次请求落地"而不是页面容器:.board-page 和 .feed 在加载中
  // 就已经在 DOM 里,等它们等于没等 —— 发帖框的显隐跟着登录态走,
  // 但它所在的那一层要等列表请求回来才会稳定
  await waitData(page, '.post .content')

  check('登录后发帖框出现了', (await page.locator('.composer textarea').count()) === 1)
  check('默认落在本村(战旗村)',
    String(await page.textContent('.board-page .el-select')).includes('战旗村'),
    String(await page.textContent('.board-page .el-select')).slice(0, 40))

  const MY_POST = `批次6c:谁家有多余的玉米脱粒机 —— ${Date.now()}`
  await page.fill('.composer textarea', MY_POST)
  await page.click('button:has-text("发布")')
  await page.waitForTimeout(2000)

  let first = (await texts(page, '.post .content'))[0]
  check('发完就出现在列表第一条(新帖排最前)', first === MY_POST, `实际第一条:${first}`)
  check('自己的帖上有删除按钮',
    (await page.locator('.post').first().locator('button:has-text("删除")').count()) === 1)
  await page.screenshot({ path: `${SHOT_DIR}/65-留言板-发帖成功.png`, fullPage: true })

  // ══ 7. 点赞:界面先翻、服务端为准 ═══════════════════════════
  console.log('\n[7] 点赞')
  const myPost = page.locator('.post').first()
  const likeBtn = myPost.locator('.act').first()
  const countText = async () => {
    const t = await likeBtn.textContent()
    const m = String(t).match(/\d+/)
    return m ? Number(m[0]) : NaN
  }
  const likeBefore = await countText()
  await likeBtn.click()
  await page.waitForTimeout(1200)

  check('点完星标变实心(is-on)', (await likeBtn.getAttribute('class')).includes('is-on'))
  check(`点赞数 0 → 1`, (await countText()) === likeBefore + 1,
    `${likeBefore} → ${await countText()}`)

  const likedText = await likeBtn.getAttribute('class')
  await likeBtn.click()
  await page.waitForTimeout(1200)
  check('再点一次取消,星标恢复空心', !(await likeBtn.getAttribute('class')).includes('is-on'),
    likedText ?? '')
  check('取消后计数回到 0', (await countText()) === likeBefore,
    `实际 ${await countText()}`)
  // 留一次赞在库里,下面的回复和后台巡查看着更真
  await likeBtn.click()
  await page.waitForTimeout(1200)

  // ══ 8. 回复 ═══════════════════════════════════════════════
  console.log('\n[8] 回复')
  const replyToggle = myPost.locator('.act').nth(1)
  await replyToggle.click()
  await page.waitForTimeout(1500)
  check('展开后出现了回复区', (await myPost.locator('.reply-zone').count()) === 1)
  check('回复框的提示语带上了楼主名字',
    String(await myPost.locator('.reply-box textarea').getAttribute('placeholder')).includes('张建国'),
    String(await myPost.locator('.reply-box textarea').getAttribute('placeholder')))

  await myPost.locator('.reply-box textarea').fill('有的,在东屋墙上挂着,随时来拿 —— 批次6c测试回复')
  await myPost.locator('.reply-box button:has-text("回复")').click()
  await page.waitForTimeout(2000)

  const replies = await texts(page, '.post .reply-text')
  check('回复出现在回复区里', replies.some((t) => t.includes('批次6c测试回复')),
    replies.join(' | '))
  check('回复的作者名显示出来了',
    String(await myPost.locator('.reply').first().textContent()).includes('张建国'),
    String(await myPost.locator('.reply').first().textContent()).slice(0, 50))
  check('主帖上的回复数跟着涨到 1',
    (await replyToggle.textContent()).includes('1'),
    String(await replyToggle.textContent()))
  await page.screenshot({ path: `${SHOT_DIR}/66-留言板-点赞与回复.png`, fullPage: true })

  // ══ 9. 后台:公告管理(数据级权限) ══════════════════════════
  console.log('\n[9] 后台公告管理(战旗村支书)')
  await login(page, '13800000001')
  await goto(page, `${BASE}/admin/content/notice`)
  await waitData(page, '.el-table__body tr')

  let titles = await texts(page, '.el-table__body .title-text')
  check('村管看到 5 条(全平台 1 条 + 战旗村 4 条)', titles.length === 5,
    `实际 ${titles.length} 条:${titles.map((t) => t.slice(0, 14))}`)
  check('别村的公告不在列表里', !titles.some((t) => t.includes('先锋村共享农具角')))
  check('本村草稿在后台是看得到的(前台不给村民看,后台要能接着编辑)',
    titles.some((t) => t.includes('拟稿')), titles.join(' | '))
  check('村管有「发布公告」按钮',
    (await page.locator('button:has-text("发布公告")').count()) === 1)
  await page.screenshot({ path: `${SHOT_DIR}/68-后台-公告管理.png`, fullPage: true })

  // 「系统公告」这个类型不该出现在村管的选项里 —— 前端先挡一道,
  // 后端 publish 里还有一道 403(接口测试已覆盖)
  await page.click('button:has-text("发布公告")')
  await page.waitForTimeout(1000)
  await page.click('.el-dialog .el-select')
  await page.waitForTimeout(800)
  let opts = await texts(page, '.el-select-dropdown:visible .el-select-dropdown__item')
  check('村管的类型下拉里没有「系统公告」', !opts.some((t) => t.includes('系统公告')),
    opts.join(' / '))
  check('但村级能发的类型都在', opts.length === 4, opts.join(' / '))
  await page.keyboard.press('Escape')
  await page.waitForTimeout(500)
  await page.click('.el-dialog__footer button:has-text("取消")')
  await page.waitForTimeout(800)

  // ── 9b. 操作列:编辑 / 发布 / 下架(权限码 6012 / 6013) ────────
  //
  // 这一段的顺序是刻意的:先看按钮摆得对不对,再走一遍
  // "编辑 → 下架 → 前台确认消失 → 重新发布 → 前台确认又出现"。
  // 最后那两步是这整段的关键 —— 只有把"下架"和"它在前台的效果"连起来看,
  // 才能证明下架真的生效了,而不是只改了一个自己看得见的标签。
  const rowOf = (text) => page.locator('.el-table__body tr').filter({ hasText: text }).first()

  const publishedRow = rowOf('闲置农机共享倡议书')
  check('已发布的公告行上有「编辑」按钮',
    (await publishedRow.locator('button:has-text("编辑")').count()) === 1)
  check('已发布的公告行上是「下架」,不是「发布」',
    (await publishedRow.locator('button:has-text("下架")').count()) === 1
    && (await publishedRow.locator('button:has-text("发布")').count()) === 0)

  // 草稿那条应该显示「发布」而不是「下架」—— 一次都没发过的东西谈不上撤下
  const draftRow = rowOf('拟稿')
  check('草稿行上是「发布」按钮,没有「下架」',
    (await draftRow.locator('button:has-text("发布")').count()) === 1
    && (await draftRow.locator('button:has-text("下架")').count()) === 0)

  // 编辑弹窗要能把正文取回来 —— 列表接口刻意不返回 content,
  // 靠的是后台详情接口(GET /api/admin/announcement/{id})
  await publishedRow.locator('button:has-text("编辑")').click()
  await page.waitForTimeout(1600)
  const dialogTitle = String(await page.locator('.el-dialog__title').textContent())
  check('弹窗标题是「编辑公告」', dialogTitle.includes('编辑公告'), dialogTitle)
  const editTitle = await page.inputValue('.el-dialog input[placeholder^="例如"]')
  check('标题栏预填了这条公告的原标题', editTitle.includes('闲置农机共享倡议书'), editTitle)
  const editBody = await page.inputValue('.el-dialog textarea')
  check('正文也取回来了(说明是单独请求了详情接口)', editBody.length > 50,
    `正文长度 ${editBody.length}`)
  check('底部按钮是「保存」而不是「确认发布」',
    (await page.locator('.el-dialog__footer button:has-text("保存")').count()) === 1
    && (await page.locator('.el-dialog__footer button:has-text("确认发布")').count()) === 0)
  const editTip = String(await page.textContent('.el-dialog .el-alert'))
  check('弹窗上明说了编辑不会重发通知(免得管理员以为村民会再收到一遍)',
    editTip.includes('不会重新给用户发通知'), editTip.slice(0, 90))
  await page.screenshot({ path: `${SHOT_DIR}/71-后台-公告编辑弹窗.png`, fullPage: true })

  const EDITED = '界面改过'
  await page.fill('.el-dialog input[placeholder^="例如"]', `战旗村闲置农机共享倡议书(${EDITED})`)
  await page.click('.el-dialog__footer button:has-text("保存")')
  // 等"表格里出现了新标题",而不是睡 2 秒 —— 保存 → 关弹窗 → 重新拉列表
  // 是一条异步链,链上任何一环慢一点,睡多久都是赌
  await waitText(page, '.el-table__body .title-text', EDITED)
  titles = await texts(page, '.el-table__body .title-text')
  check('保存后列表里的标题跟着变了', titles.some((t) => t.includes(EDITED)),
    titles.map((t) => t.slice(0, 22)).join(' | '))

  // 下架
  await rowOf(EDITED).locator('button:has-text("下架")').click()
  await page.waitForTimeout(900)
  const offlineBox = String(await page.textContent('.el-message-box'))
  check('下架的确认框说清了前台立刻看不到、后台仍然查得到',
    offlineBox.includes('前台立刻看不到') && offlineBox.includes('后台仍然查得到'),
    offlineBox.slice(0, 130))
  await page.screenshot({ path: `${SHOT_DIR}/72-后台-公告下架确认.png`, fullPage: true })
  await clickBox(page, '确认下架')

  await waitTableRow(page, EDITED, '已下架')
  check('下架后这一行的状态标签变成「已下架」',
    (await rowOf(EDITED).locator('.el-tag:has-text("已下架")').count()) === 1,
    String(await rowOf(EDITED).textContent()).slice(0, 90))
  check('下架后的行上按钮变成了「发布」',
    (await rowOf(EDITED).locator('button:has-text("发布")').count()) === 1)

  // 换个人来看同一个库 —— 下架的效果只在"别人眼里"才成立
  await login(page, '13800000003')
  await goto(page, `${BASE}/announcement`)
  // 先等列表真的渲染出来:空列表上"没有它"也是成立的,
  // 少了这一等,下面那句就变成了没等
  await waitData(page, '.notice-item')
  await waitGoneText(page, '.notice-item', EDITED)
  check('下架后村民的前台公告列表里就没有它了',
    !(await texts(page, '.notice-item')).some((t) => t.includes(EDITED)),
    (await texts(page, '.notice-item')).join(' | ').slice(0, 150))

  // 下架不是死路:改完还能再发。一个只能下架、不能再发布的按钮,
  // 等于给后台留了一批谁也动不了的公告。
  await login(page, '13800000001')
  await goto(page, `${BASE}/admin/content/notice`)
  await waitData(page, '.el-table__body tr')
  await rowOf(EDITED).locator('button:has-text("发布")').click()
  await page.waitForTimeout(900)
  const republishBox = String(await page.textContent('.el-message-box'))
  check('重新发布的确认框写明了会给用户各发一条站内通知',
    republishBox.includes('站内通知'), republishBox.slice(0, 130))
  await clickBox(page, '确认发布')
  await waitTableRow(page, EDITED, '已发布')
  check('重新发布后状态回到「已发布」',
    (await rowOf(EDITED).locator('.el-tag:has-text("已发布")').count()) === 1,
    String(await rowOf(EDITED).textContent()).slice(0, 90))

  await login(page, '13800000003')
  await goto(page, `${BASE}/announcement`)
  await waitText(page, '.notice-item', EDITED)
  check('重新发布后村民又能看到了(下架可逆)',
    (await texts(page, '.notice-item')).some((t) => t.includes(EDITED)))

  // ══ 10. 超管的公告列表:多出别村的和全平台的 ════════════════
  console.log('\n[10] 后台公告管理(平台管理员)')
  await login(page, '13800000000')
  await goto(page, `${BASE}/admin/content/notice`)
  await waitData(page, '.el-table__body tr')
  titles = await texts(page, '.el-table__body .title-text')
  check('超管看到全部 7 条(不区分村组)', titles.length === 7,
    `实际 ${titles.length} 条`)
  check('超管看得到别村的公告', titles.some((t) => t.includes('先锋村共享农具角')))
  await page.click('button:has-text("发布公告")')
  await page.waitForTimeout(1000)
  await page.click('.el-dialog .el-select')
  await page.waitForTimeout(800)
  opts = await texts(page, '.el-select-dropdown:visible .el-select-dropdown__item')
  check('超管的类型下拉里有「系统公告」(5 种都在)', opts.length === 5,
    opts.join(' / '))
  await page.keyboard.press('Escape')
  await page.waitForTimeout(500)
  await page.click('.el-dialog__footer button:has-text("取消")')
  await page.waitForTimeout(800)

  // ══ 11. 后台留言巡查:只读口径 + 删除按钮 ═══════════════════
  console.log('\n[11] 后台留言巡查(战旗村支书)')
  await login(page, '13800000001')
  await goto(page, `${BASE}/admin/content/board`)
  await waitData(page, '.el-table__body tr')

  const total = await totalOf(page)
  // 战旗村 6 条主帖 + 6 条回复 = 12,加上刚才张建国新发的那条主帖和它的回复
  check('村管只看到本村的留言(共 14 条:7 主帖 + 7 回复)', total === 14,
    `实际共 ${total} 条`)
  const adminContents = await texts(page, '.el-table__body tr .text-body')
  check('别村的留言不在列表里',
    !adminContents.some((t) => t.includes('先锋村共享农具角')), adminContents.join(' | ').slice(0, 160))

  // 下面这两条断言验证的是"已隐藏的留言在后台读得到"。
  //
  // 这个状态**由演示数据预置**(05_mock_data.sql 里那条主帖直接写成
  // status = 2),当前版本没有任何界面能让人把一条留言改成"已隐藏" ——
  // sys_menu 里只定义了 board:list 和 board:delete,后台的处置动作
  // 只有"删除"。status = 2 是给前台查询预留的状态:前台那条 SQL
  // 写死了 status = 1,所以"隐藏"对前台的唯一效果就是看不见。
  //
  // 换句话说,这里验的是**后台的读取口径不受 status 影响**,
  // 而不是一条能从界面复现的操作路径。写在这里是为了让
  // "后台看得到前台看不到的东西"这件事有据可查。
  //
  // 匹配的是"三天出证"而不是原话里更靠前的「专业办证」—— 后台列表和前台
  // 共用同一套读取口径,内容同样是打码的(这条断言在下面单独说明)。
  const hiddenRow = adminContents.find((t) => t.includes('三天出证'))
  check('已隐藏的留言在后台是读得到的(该状态由演示数据预置,界面改不出来)', !!hiddenRow,
    adminContents.join(' | ').slice(0, 200))
  check('后台读到的这条也是打码版(读取口径与前台共用一套)',
    !!hiddenRow && !hiddenRow.includes('办证') && hiddenRow.includes('*'),
    hiddenRow ?? '')
  check('村管有「删除」按钮(权限码 board:delete)',
    (await page.locator('.el-table__body button:has-text("删除")').count()) > 0)
  await page.screenshot({ path: `${SHOT_DIR}/69-后台-留言巡查.png`, fullPage: true })

  // 挑张建国刚发的那条主帖来点删除。表格里主帖和回复是混排的,
  // 直接点第一个删除按钮会点到回复上 —— 那样弹出来的确认文案是
  // "该回复将从列表中移除",验不到"连带删除"这句话。
  const myRow = page.locator('.el-table__body tr')
    .filter({ hasText: MY_POST.slice(0, 18) }).first()
  check('刚发的那条帖在后台列表里也看得到(前后台是同一个库的两个视角)',
    (await myRow.count()) === 1, MY_POST.slice(0, 18))
  await myRow.locator('button:has-text("删除")').click()
  await page.waitForTimeout(900)
  const boxText = String(await page.textContent('.el-message-box'))
  check('删除前有二次确认,并且说清了会连带删掉几条回复',
    boxText.includes('该留言下的 1 条回复会一并删除'), boxText.slice(0, 120))
  await page.screenshot({ path: `${SHOT_DIR}/70-后台-留言删除确认.png`, fullPage: true })
  await clickBox(page, '取消')
  check('点取消没有真的删掉(列表条数不变)',
    (await texts(page, '.el-table__body .text-body')).length === adminContents.length)

  // ══ 11b. 新建公告:存草稿 ═══════════════════════════════════
  //
  // 这一段故意放在 §10 之后:它会**新建**一条公告,
  // 插在前面的话 §10 那句"超管看到全部 7 条"就得跟着改成 8 ——
  // 把一个新用例的副作用摊到已有的断言上,是那种"改着改着
  // 就没人知道 7 是怎么来的"的开端。放到最后,前面的计数全部不受影响。
  console.log('\n[11b] 新建公告:存草稿')
  await login(page, '13800000001')
  await goto(page, `${BASE}/admin/content/notice`)
  await waitData(page, '.el-table__body tr')

  await page.click('button:has-text("发布公告")')
  await page.waitForTimeout(1000)
  check('新建弹窗底部给了两个出口:「存草稿」和「确认发布」',
    (await page.locator('.el-dialog__footer button:has-text("存草稿")').count()) === 1
    && (await page.locator('.el-dialog__footer button:has-text("确认发布")').count()) === 1)
  const newTip = String(await page.textContent('.el-dialog .el-alert'))
  check('弹窗上分别写清了两个按钮各自会发生什么',
    newTip.includes('立即生效') && newTip.includes('不发通知'),
    newTip.slice(0, 110))

  const DRAFT_MARK = '批次6c新建的草稿'
  await page.fill('.el-dialog input[placeholder^="例如"]', `${DRAFT_MARK} —— ${Date.now()}`)
  // 类型是必填项 —— 漏选的话 formRef.validate() 不过,submit 直接 return,
  // 表现是"点了存草稿什么都没发生",而这一点从超时信息里看不出来
  await page.click('.el-dialog .el-select')
  await page.waitForTimeout(800)
  await page.click('.el-select-dropdown:visible .el-select-dropdown__item:first-child')
  await page.waitForTimeout(500)
  await page.fill('.el-dialog textarea', '这段还没写完,先存着,回头再补。')
  await page.click('.el-dialog__footer button:has-text("存草稿")')
  await waitText(page, '.el-table__body .title-text', DRAFT_MARK)

  const newDraftRow = rowOf(DRAFT_MARK)
  check('存完草稿,它出现在后台列表里(否则这条就没人管了)',
    (await newDraftRow.count()) === 1)
  check('它的状态标签是「草稿」',
    (await newDraftRow.locator('.el-tag:has-text("草稿")').count()) === 1,
    String(await newDraftRow.textContent()).slice(0, 90))
  check('草稿行上是「发布」按钮,没有「下架」—— 一次都没发过的东西谈不上撤下',
    (await newDraftRow.locator('button:has-text("发布")').count()) === 1
    && (await newDraftRow.locator('button:has-text("下架")').count()) === 0)
  await page.screenshot({ path: `${SHOT_DIR}/73-后台-公告存草稿.png`, fullPage: true })

  // 存草稿的意义就是"还没打算给人看" —— 换个人来确认这一点
  await login(page, '13800000003')
  await goto(page, `${BASE}/announcement`)
  await waitData(page, '.notice-item')
  check('草稿在村民的前台列表里看不到',
    !(await texts(page, '.notice-item')).some((t) => t.includes(DRAFT_MARK)),
    (await texts(page, '.notice-item')).join(' | ').slice(0, 150))

  // 再回到后台,从列表上把它发出去 —— 这是"存稿 → 发布"的第二步,
  // 走的和 §9b 里那条草稿完全同一条路径(列表上的「发布」按钮)
  await login(page, '13800000001')
  await goto(page, `${BASE}/admin/content/notice`)
  await waitData(page, '.el-table__body tr')
  await rowOf(DRAFT_MARK).locator('button:has-text("发布")').click()
  await page.waitForTimeout(900)
  await clickBox(page, '确认发布')
  await waitTableRow(page, DRAFT_MARK, '已发布')

  await login(page, '13800000003')
  await goto(page, `${BASE}/announcement`)
  await waitData(page, '.notice-item')
  check('发出去之后村民就看到了 —— 存稿和发布是同一条链上的两步',
    (await texts(page, '.notice-item')).some((t) => t.includes(DRAFT_MARK)))

  // ══ 12. 控制台 ═════════════════════════════════════════════
  console.log('\n[12] 浏览器控制台')
  const realErrors = consoleErrors.filter(
    (e) => !e.includes('favicon') && !e.includes('DevTools')
      && !e.includes('Download the Vue Devtools') && !e.includes('404 (Not Found)')
  )
  check('无前端运行时错误', realErrors.length === 0,
    realErrors.slice(0, 3).join(' | ').slice(0, 300))

} catch (e) {
  failed++
  console.log(`\n[ERROR] 测试执行异常: ${e.message}`)
  await page.screenshot({ path: `${SHOT_DIR}/99-异常现场-留言板公告.png`, fullPage: true }).catch(() => {})
} finally {
  await browser.close()
}

console.log('\n' + '='.repeat(70))
console.log(`结果:通过 ${passed} 项,失败 ${failed} 项`)
console.log(`截图已保存到 ${SHOT_DIR}`)
console.log('本脚本会写数据,重复运行前请重置:mysql -uroot -proot < sql/05_mock_data.sql')
console.log('='.repeat(70))
process.exit(failed ? 1 : 0)
