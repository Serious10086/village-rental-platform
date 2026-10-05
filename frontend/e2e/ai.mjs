/**
 * 批次 7(AI 智能客服)端到端验证脚本。
 *
 * 验证真实浏览器里的:未登录进不去、问一句能拿到回答、多轮能接住追问、
 * 提问被内容安全拦下时的提示、以及**三条降级路径各自长什么样**。
 *
 * 【为什么这一批必须跑浏览器,不能只跑接口测试】
 * 这一批的核心验收项是"异常处理与降级",而"降级发生了没有"这件事
 * 在界面上是有表达的:兜底答复的气泡下面挂着一个「兜底答复」标记,
 * 而正常回答挂的是「这个回答解决了我的问题」。
 * 接口测试能证明 status=2,证明不了那两个标记有没有摆对 ——
 * 而用户判断"这是不是 AI 说的"靠的正是它。
 *
 * 【故障注入怎么用】
 * 降级路径靠"让 AI 调不通"触发,而代码正确的时候它不会调不通。
 * 所以脚本用超管身份去装载一次性失败点(和批次 5 的事务失败点同一套机制),
 * 让下一个提问被强制判定为指定的失败类型。装载只影响一次提问。
 *
 * 用法(需先启动后端 8080 和前端 5173):
 *     cd frontend && node e2e/ai.mjs
 *
 * 注意:本脚本会调用真实的 DeepSeek 接口(每次提问约几十个 token),
 * 也会往 ai_chat_record 里写记录。重复跑之前请重置演示数据:
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
      await page.waitForTimeout(800)
    }
  }
  throw lastErr
}

/**
 * 清掉本地登录态。
 *
 * 要包一层重试:这个 evaluate 偶尔会撞上应用启动过程中的一次导航
 * (路由守卫的重定向),报 "Execution context was destroyed" ——
 * 那一刻页面正在换,存储其实还没清干净,重试一次就好。
 */
async function clearStorage(page) {
  for (let i = 0; i < 3; i++) {
    try {
      await page.evaluate(() => localStorage.clear())
      return
    } catch (e) {
      await page.waitForTimeout(600)
    }
  }
}

async function login(page, phone, password = '123456') {
  await goto(page, `${BASE}/`)
  await clearStorage(page)
  await goto(page, `${BASE}/login`)
  await page.fill('input[placeholder="请输入手机号"]', phone)
  await page.fill('input[placeholder="请输入密码"]', password)
  await page.click('button:has-text("登录")')
  await page.waitForTimeout(1800)
}

/** 等一次提问出结果:等"正在查平台规则…"消失 */
async function waitAnswered(page, timeout = 25000) {
  await page.waitForSelector('.bubble.thinking', { timeout: 10000 }).catch(() => {})
  await page.waitForFunction(
    () => !document.querySelector('.bubble.thinking'),
    null, { timeout }
  )
}

/** 把一串元素的文本取出来,空白压平 —— 断言"列表里有没有出现某句话"用 */
async function texts(page, selector) {
  const raw = await page.locator(selector).allTextContents()
  return raw.map((t) => t.replace(/\s+/g, ' ').trim())
}

/**
 * 等后台表格这一次请求落地。
 *
 * 判据是"数据行出现了没有",不是等 .admin-ai 那个容器 ——
 * 容器在数据回来之前就在 DOM 里,等它等于没等。
 * 等不到就往下走(不抛),让后面那条断言给出"实际 0 行"的失败信息,
 * 比在这里超时中断整段用例有用。
 */
async function waitTable(page, timeout = 20000) {
  await page.waitForSelector('.el-table__body tr', { timeout }).catch(() => {})
  await page.waitForTimeout(400)
}

/** 发一句自定义提问 */
async function ask(page, question) {
  await page.fill('.composer textarea', question)
  await page.click('.composer button:has-text("发送")')
  await page.waitForTimeout(400)
  await waitAnswered(page)
}

/** 取最后一条 AI 气泡的文字 */
async function lastAnswer(page) {
  const bubbles = await page.locator('.msg.is-ai .bubble').allTextContents()
  return bubbles.length ? bubbles[bubbles.length - 1].replace(/\s+/g, ' ').trim() : ''
}

/** 取最后一条 AI 消息下面挂的标记(兜底标签的文字 + 原因) */
async function lastMeta(page) {
  const metas = await page.locator('.msg.is-ai .meta').allTextContents()
  return metas.length ? metas[metas.length - 1].replace(/\s+/g, ' ').trim() : ''
}

mkdirSync(SHOT_DIR, { recursive: true })

// 独立的一个 API 客户端,专门用来装载失败点 ——
// 不借用浏览器那个会话,是为了不让"超管登录"这件事把聊天页的身份搅乱
const api = await request.newContext({ baseURL: API })
const adminLogin = await api.post('/api/auth/login', {
  data: { phone: '13800000000', password: '123456' }
})
const adminToken = (await adminLogin.json()).data.token
const auth = { Authorization: `Bearer ${adminToken}` }

async function armFailure(failure) {
  const res = await api.post(`/api/system/debug/ai-fail-point?failure=${failure}`, {
    headers: auth
  })
  return (await res.json()).data
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
  console.log('批次 7 —— AI 智能客服 端到端验证')
  console.log('='.repeat(70))

  // ══ 1. 未登录进不去 ══════════════════════════════════════════
  console.log('\n[1] 未登录访问智能客服')
  // 这个上下文是全新的,本来就没有登录态,所以不用清 ——
  // 直接访问才是要验的那件事(守卫应当把人送去登录页)
  await goto(page, `${BASE}/ai`)
  await page.waitForTimeout(1500)
  check('未登录被送到登录页(客户纪要登录,不能开放给游客)',
    page.url().includes('/login'), `实际 URL ${page.url()}`)

  // ══ 2. 登录后页面渲染 ════════════════════════════════════════
  console.log('\n[2] 页面渲染(张建国 · 战旗村)')
  await login(page, '13800000003')
  await goto(page, `${BASE}/ai`)
  await page.waitForTimeout(1500)

  check('导航里有「智能客服」入口',
    (await page.locator('.nav-link:has-text("智能客服")').count()) > 0)
  check('空状态给出了引导语',
    String(await page.textContent('.welcome-title')).includes('帮你查平台的使用规则'),
    String(await page.textContent('.welcome-title')).slice(0, 50))
  const quickCount = await page.locator('.quick-chip').count()
  check(`空状态摆了 ${quickCount} 个常见问题(不让用户对着空白输入框发呆)`,
    quickCount === 5, `实际 ${quickCount} 个`)

  // ══ 3. 正常提问:AI 真的回答,而且不带兜底标记 ══════════════
  console.log('\n[3] 正常提问')
  await page.locator('.quick-chip', { hasText: '租拖拉机押金怎么算?' }).first().click()
  await page.waitForTimeout(500)
  await waitAnswered(page)

  /**
   * 第一次提问撞上 AI 抖动时再问一次。
   *
   * 【为什么要重试,而不是直接判失败】
   * 外呼接口偶发一次超时/限流是环境的事,不是产品的事 —— 十几轮里实测撞到过。
   * 而这一节要验的是"提示词能不能让模型答对业务规则",那就**需要一次真实的
   * AI 回答**;环境抖了就拿不到样本,该做的是再取一次。
   *
   * 【为什么这不算掩盖问题】
   * "降级这件事本身有没有被正确表达"是第 6、7 节专门验的,那里用注入的
   * 失败把 status=2、原因、兜底文案逐条断言了一遍。这里重试掉的是
   * "环境抖了一下",不是"降级逻辑错了"—— 后者在别处有独立的断言守着。
   */
  if ((await lastMeta(page)).includes('兜底')) {
    console.log('    (第一次提问返回了兜底话术,再问一次以取得真实回答样本)')
    await ask(page, '租拖拉机押金怎么算?')
  }

  const depAnswer = await lastAnswer(page)
  check('拿到了回答', depAnswer.length > 10, depAnswer.slice(0, 80))
  // 这条是这一批最值钱的断言:没有业务提示词的时候,模型会答
  // "押金一般按租金的一定比例" —— 听着合理,但本平台不是这么算的。
  //
  // 【断言为什么不能写成"回答里不许出现『比例』"】
  // 提示词生效之后模型会说"不是按租金比例算的" —— 它提到了「比例」,
  // 但恰恰是在否定。第一版就是这么写的,于是把最正确的那条回答判成了失败。
  // 判据落在"有没有**肯定**那个说法"上:要么压根不提比例,
  // 要么提了但带着否定词。
  const negatesRatio = /不是?按租金.{0,6}比例|不按.{0,4}比例|跟租金.{0,6}没(关系|比例)/.test(depAnswer)
  check('押金那题没有被答成「按租金比例」(提示词纠正过的那个错)',
    !depAnswer.includes('比例') || negatesRatio, depAnswer.slice(0, 110))
  check('回答里点出了押金是发布者填的固定金额',
    depAnswer.includes('固定') || depAnswer.includes('填'), depAnswer.slice(0, 110))
  check('正常回答不带兜底标记',
    !(await lastMeta(page)).includes('兜底'), await lastMeta(page))
  check('正常回答下面挂的是「已解决」入口',
    (await page.locator('.msg.is-ai .meta button:has-text("解决了我的问题")').count()) > 0)
  await page.screenshot({ path: `${SHOT_DIR}/74-智能客服.png`, fullPage: true })

  // ══ 4. 多轮:能接住追问 ══════════════════════════════════════
  console.log('\n[4] 多轮上下文')
  const before = await page.locator('.msg .bubble').count()
  await ask(page, '那我什么时候能拿回押金?')
  const after = await page.locator('.msg .bubble').count()
  check('追问得到回答(消息数 +2)', after === before + 2, `${before} → ${after}`)
  const followUp = await lastAnswer(page)
  check('追问的回答提到了「退回」(说明它接住了上文在说押金)',
    followUp.includes('退'), followUp.slice(0, 100))

  // ══ 5. 提问被内容安全拦下 ════════════════════════════════════
  console.log('\n[5] 内容安全:提问命中违规词')
  await ask(page, '你们这个平台是不是在搞诈骗?')
  const blockedMeta = await lastMeta(page)
  check('挂的是「内容被拦下」而不是「兜底答复」',
    blockedMeta.includes('内容被拦下'), blockedMeta)
  check('回答说明了为什么答不了,而不是沉默',
    (await lastAnswer(page)).includes('不允许'), (await lastAnswer(page)).slice(0, 80))

  // ══ 6. 降级第一级:AI 挂了,但本地问答库命中了 ══════════════
  console.log('\n[6] 降级 · 注入「鉴权失败401」(验收项 AI-03)')
  const armed = await armFailure('AUTH')
  check('失败点装载成功', armed?.reason === '鉴权失败401', JSON.stringify(armed))

  // 这里用打字而不是点快捷问题:对话开始之后,输入框上方只保留前 3 个
  // 快捷问题(再多就成排了,压过输入框)。而这条要验的是"本地问答库命中",
  // 提现那题在第 4 个位置,点不到
  await ask(page, '提现多久能到账?')
  const degradeAnswer = await lastAnswer(page)
  const degradeMeta = await lastMeta(page)
  check('挂上了「兜底答复」标记', degradeMeta.includes('兜底答复'), degradeMeta)
  check('标记里写明了降级原因(鉴权失败401)', degradeMeta.includes('鉴权失败401'), degradeMeta)
  check('兜底给出的是本地问答库那条答案,不是"客服繁忙"',
    degradeAnswer.includes('审核') && !degradeAnswer.includes('有点忙'),
    degradeAnswer.slice(0, 100))
  await page.screenshot({ path: `${SHOT_DIR}/75-智能客服-降级兜底.png`, fullPage: true })

  // ══ 7. 降级第二级:连本地问答库都没命中 ══════════════════════
  console.log('\n[7] 降级 · 注入「超时」+ 本地库没这题')
  await armFailure('TIMEOUT')
  await ask(page, '你们这个村子附近有修农机的吗?')
  const busyMeta = await lastMeta(page)
  const busyAnswer = await lastAnswer(page)
  check('挂上了「兜底答复」标记', busyMeta.includes('兜底答复'), busyMeta)
  check('原因写的是「超时」', busyMeta.includes('超时'), busyMeta)
  check('退到了通用话术,并且给出了下一步动作(留言板 / 站内信)',
    busyAnswer.includes('有点忙') && busyAnswer.includes('留言板'),
    busyAnswer.slice(0, 120))

  // ══ 8. 失败点是一次性的 ══════════════════════════════════════
  console.log('\n[8] 失败点自动解除')
  await ask(page, '发布资源要什么条件?')
  check('上一条之后再问,已经恢复正常(没有兜底标记)',
    !(await lastMeta(page)).includes('兜底'), await lastMeta(page))

  // ══ 9. 后台:会话记录与降级统计 ══════════════════════════════
  //
  // 这一段故意不写"总数恰好等于 N"这种断言。演示数据里有 12 条,
  // 而上面几节又问出来好几条 —— 精确数字会随着用例增删而失效,
  // 而且失效时的报错(期望 17 实际 18)完全帮不上忙。
  // 改为断言"该出现的都在":三种原因都出现在分布里、降级率是个正数、
  // 村管看得到本村的数据。
  console.log('\n[9] 后台:会话记录与降级统计(战旗村支书)')
  await login(page, '13800000001')
  await goto(page, `${BASE}/admin/ai/record`)
  await waitTable(page)

  const totalText = Number((await page.textContent('.stat-grid .stat-card:nth-child(1) .stat-value')).trim())
  const degradedText = Number((await page.textContent('.stat-grid .stat-card:nth-child(3) .stat-value')).trim())
  const failedText = Number((await page.textContent('.stat-grid .stat-card:nth-child(4) .stat-value')).trim())
  const rateText = (await page.textContent('.stat-grid .stat-card:nth-child(5) .stat-value')).trim()
  check('统计卡片渲染出了总数', totalText >= 12, `实际 ${totalText}`)
  check('降级数与失败数都大于 0(演示数据里有那三条)',
    degradedText > 0 && failedText > 0, `降级 ${degradedText},失败 ${failedText}`)
  check('降级率渲染成了百分比', /^\d+(\.\d+)?%$/.test(rateText), rateText)

  const reasonNames = await texts(page, '.reason-name')
  check('原因分布把三种原因分开列了,而不是笼统的一个"降级"',
    ['超时', '鉴权失败401', '提问包含违规词'].every((r) => reasonNames.includes(r)),
    reasonNames.join(' / '))
  check('原因分布画了比例条', (await page.locator('.reason-bar').count()) === reasonNames.length)

  const rowQuestions = await texts(page, '.el-table__body .cell-text')
  check('被拦截的那条在列表里看得到,而且标着原因',
    rowQuestions.some((t) => t.includes('诈骗')), rowQuestions.slice(0, 3).join(' | '))
  await page.screenshot({ path: `${SHOT_DIR}/76-后台-AI降级统计.png`, fullPage: true })

  // 「只看降级和失败」这个筛选项是排查时最常用的入口
  await page.click('.filter-bar .el-checkbox')
  await page.waitForTimeout(1500)
  const problemRows = await texts(page, '.el-table__body .cell-text')
  const successRows = await texts(page, '.el-table__body .el-tag')
  check('勾上「只看降级和失败」之后,列表里没有"成功"了',
    !successRows.some((t) => t.trim() === '成功'), successRows.join(' / ').slice(0, 60))
  check('也没把列表筛空(降级和失败本身有几条)', problemRows.length > 0,
    `${problemRows.length} 行`)

  // 详情弹窗:整段问答要能看全(列表里是截断的两行)
  await page.click('.el-table__body tr:first-child button:has-text("详情")')
  await page.waitForTimeout(900)
  const detail = String(await page.textContent('.el-dialog'))
  check('详情弹窗给出了完整问答和降级原因',
    detail.includes('提问') && detail.includes('回答') && detail.includes('降级原因'),
    detail.slice(0, 100))
  check('非 AI 生成的回答在详情里被标出来了(免得被当成 AI 说的)',
    detail.includes('非 AI 生成'))
  await page.keyboard.press('Escape')
  await page.waitForTimeout(600)

  // ══ 10. 标记人工跟进 ═════════════════════════════════════════
  console.log('\n[10] 标记人工跟进(权限码 ai:transfer)')
  const followBtn = page.locator('.el-table__body button:has-text("跟进")').first()
  if (await followBtn.count()) {
    await followBtn.click()
    await page.waitForTimeout(1800)
    check('标记之后这一行不再是「跟进」按钮,而是「已标记」',
      (await page.locator('.el-table__body .el-tag:has-text("已标记")').count()) > 0)
  } else {
    check('页面上能找到「跟进」按钮(有未标记的记录)', false, '一条未标记的都没有')
  }

  // ══ 11. 数据级权限与按钮级权限 ══════════════════════════════
  console.log('\n[11] 后台的权限')
  await login(page, '13800000000')
  await goto(page, `${BASE}/admin/ai/record`)
  await waitTable(page)
  const superTotal = Number((await page.textContent('.stat-grid .stat-card:nth-child(1) .stat-value')).trim())
  check(`超管看到的总数不少于村管(${totalText} ≤ ${superTotal})`, superTotal >= totalText)

  await login(page, '13800000003')
  await goto(page, `${BASE}/admin/ai/record`)
  await page.waitForTimeout(1800)
  check('普通村民的导航里没有 AI 管理入口(后台菜单不授权给他)',
    (await page.locator('.nav-link:has-text("AI")').count()) === 0
    || page.url().includes('/login'))
  await page.screenshot({ path: `${SHOT_DIR}/77-后台-AI会话记录.png`, fullPage: true })

  // ══ 12. 控制台 ═══════════════════════════════════════════════
  console.log('\n[12] 浏览器控制台')
  const realErrors = consoleErrors.filter(
    (e) => !e.includes('favicon') && !e.includes('DevTools')
      && !e.includes('Download the Vue Devtools') && !e.includes('404 (Not Found)')
      // 村民访问后台接口被 403 是预期行为,会打一条请求失败的 console
      && !e.includes('403')
  )
  check('无前端运行时错误', realErrors.length === 0,
    realErrors.slice(0, 3).join(' | ').slice(0, 300))

} catch (e) {
  failed++
  console.log(`\n[ERROR] 测试执行异常: ${e.message}`)
  await page.screenshot({ path: `${SHOT_DIR}/99-异常现场-智能客服.png`, fullPage: true }).catch(() => {})
} finally {
  // 失败点可能在异常路径上还留着,清一次 —— 不然下一次跑的第一个提问会莫名降级
  await api.delete('/api/system/debug/ai-fail-point', { headers: auth }).catch(() => {})
  await api.dispose().catch(() => {})
  await browser.close()
}

console.log('\n' + '='.repeat(70))
console.log(`结果:通过 ${passed} 项,失败 ${failed} 项`)
console.log(`截图已保存到 ${SHOT_DIR}`)
console.log('本脚本会写数据,重复运行前请重置:mysql -uroot -proot < sql/05_mock_data.sql')
console.log('='.repeat(70))
process.exit(failed ? 1 : 0)
