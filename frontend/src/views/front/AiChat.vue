<script setup>
/**
 * 前台 —— 智能客服。
 *
 * 【这个页面最需要说清楚的一件事:AI 答不上来不是"出错了"】
 * 后端把一次问答分成三种结局,而且三种都会返回一段话:
 *   status=1 成功  —— AI 的回答
 *   status=2 降级  —— 本地兜底话术(超时、限流、欠费、密钥失效)
 *   status=3 失败  —— 提问被内容安全拦下
 * 所以这里**没有**"客服暂不可用,请稍后再试"这种整页错误态 ——
 * 用户永远能读到一段话,差别只在于要不要告诉他这段话是兜底的。
 *
 * 兜底时挂一个标记,是刻意的:兜底话术是固定的 FAQ 段落,和 AI 现场生成的
 * 回答读起来不一样。不说破的话,用户会以为"这个客服就这个水平";
 * 说破了,他要么换个问法,要么知道该去找村里人。
 *
 * 【会话是临时的】
 * sessionId 在组件挂载时生成,只存在于内存:刷新页面就是一段新对话。
 * 不把它存进 localStorage 是有意的 —— 存了的话刷新后界面上是空的、
 * 服务端却还记得上文,AI 会突然问起用户看不见的事,那比"上下文丢了"更让人困惑。
 */
import { ref, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Promotion, Service, ChatLineSquare } from '@element-plus/icons-vue'
import { askAi, markAiResolved } from '@/api/ai'

const messages = ref([])
const draft = ref('')
const asking = ref(false)
const scrollBox = ref(null)

/**
 * 会话语标识。
 *
 * 只用 crypto.randomUUID(),不用时间戳拼随机数 —— 后者在同一毫秒内
 * 多次调用会撞号,而撞号的后果是两个人的对话被拼成一段上下文。
 *
 * 兜底那一段是为"用手机连局域网 IP 打开"准备的:crypto.randomUUID
 * 只在安全上下文(https 或 localhost)里存在,用 http://192.168.x.x 访问时
 * 它是 undefined,直接调会白屏。答辩现场真拿手机演示的话,这个兜底就是
 * "页面能打开"和"页面打不开"的区别。
 */
function newSessionId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'sess-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12)
}

const sessionId = ref('')

onMounted(() => {
  sessionId.value = newSessionId()
})

/** 常见问题。既是空状态的内容,也是随时可点的快捷键 —— 演示时省事 */
const QUICK_QUESTIONS = [
  '租拖拉机押金怎么算?',
  '发布资源要什么条件?',
  '信用分多少才能下单?',
  '提现多久能到账?',
  '东西坏了或者对方赖账怎么办?'
]

function scrollToBottom() {
  nextTick(() => {
    const box = scrollBox.value
    if (box) {
      box.scrollTop = box.scrollHeight
    }
  })
}

function onKeydown(event) {
  // 回车发送、Shift+回车换行。输入框是多行的,所以必须自己判 ——
  // 一律拦截回车的话,用户就再也打不出换行了
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    send()
  }
}

async function send(preset) {
  const question = String(preset ?? draft.value).trim()
  if (!question || asking.value) {
    return
  }
  draft.value = ''
  messages.value.push({ role: 'user', text: question })
  asking.value = true
  scrollToBottom()

  try {
    const data = await askAi({ sessionId: sessionId.value, question })
    messages.value.push({
      role: 'ai',
      text: data.answer,
      status: data.status,
      statusName: data.statusName,
      reason: data.degradeReason,
      recordId: data.recordId,
      resolved: false
    })
  } catch (e) {
    // 走到这里说明是 HTTP 层的问题(401、网络断开、后端没起来),
    // 真正的"AI 答不上来"不会抛异常 —— 那三种结局都是 200
    messages.value.push({
      role: 'ai',
      text: '没能连上服务,请检查网络后重试。如果一直这样,可以到「村民留言板」留言。',
      status: 2,
      reason: '请求失败'
    })
  } finally {
    asking.value = false
    scrollToBottom()
  }
}

async function resolve(message) {
  try {
    await markAiResolved(message.recordId)
    message.resolved = true
  } catch (e) {
    // 提示由 request.js 统一弹;标记失败不影响继续提问,所以不打断
  }
}
</script>

<template>
  <div class="page-container ai-page">
    <header class="page-head">
      <div>
        <h1 class="page-title">智能客服</h1>
        <p class="page-sub">
          问平台怎么用 —— 租金押金、实名认证、发布审核、提现、纠纷,都可以问
        </p>
      </div>
    </header>

    <div class="ai-shell">
      <!-- ── 对话区 ──────────────────────────────────────── -->
      <div ref="scrollBox" class="chat-card card">
        <!-- 空状态:先把"能问什么"摆出来,别让用户对着空白输入框发呆 -->
        <div v-if="!messages.length" class="welcome">
          <el-icon class="welcome-icon"><Service /></el-icon>
          <h2 class="welcome-title">我可以帮你查平台的使用规则</h2>
          <p class="welcome-desc">
            具体某台农机的租金、某个订单的状态这类信息我看不到,你要去对应的页面看。
            但"平台是怎么规定的"这类问题,问我最快。
          </p>
          <div class="quick-list">
            <button
              v-for="q in QUICK_QUESTIONS"
              :key="q"
              type="button"
              class="quick-chip"
              @click="send(q)"
            >
              {{ q }}
            </button>
          </div>
        </div>

        <div
          v-for="(m, i) in messages"
          :key="i"
          class="msg"
          :class="m.role === 'user' ? 'is-user' : 'is-ai'"
        >
          <div class="avatar">
            <el-icon v-if="m.role === 'ai'"><Service /></el-icon>
            <span v-else>我</span>
          </div>
          <div class="bubble-wrap">
            <div class="bubble">{{ m.text }}</div>
            <!--
              兜底标记。挂在气泡下面而不是盖在上面:用户先读到话,
              再看这是不是 AI 说的 —— 反过来会让"兜底"这件事显得像报错
            -->
            <div v-if="m.role === 'ai' && m.status !== 1" class="meta">
              <el-tag
                size="small"
                effect="plain"
                :type="m.status === 3 ? 'danger' : 'warning'"
              >
                {{ m.status === 3 ? '内容被拦下' : '兜底答复' }}
              </el-tag>
              <span class="meta-reason">{{ m.reason }}</span>
            </div>
            <div v-else-if="m.role === 'ai' && m.recordId" class="meta">
              <el-button v-if="!m.resolved" link size="small" @click="resolve(m)">
                <el-icon><ChatLineSquare /></el-icon>&nbsp;这个回答解决了我的问题
              </el-button>
              <span v-else class="meta-resolved">已标记为解决了</span>
            </div>
          </div>
        </div>

        <div v-if="asking" class="msg is-ai">
          <div class="avatar"><el-icon><Service /></el-icon></div>
          <div class="bubble-wrap">
            <div class="bubble thinking">正在查平台规则…</div>
          </div>
        </div>
      </div>

      <!-- ── 输入区 ──────────────────────────────────────── -->
      <div class="composer card">
        <div class="quick-row" v-if="messages.length">
          <button
            v-for="q in QUICK_QUESTIONS.slice(0, 3)"
            :key="q"
            type="button"
            class="quick-chip is-small"
            :disabled="asking"
            @click="send(q)"
          >
            {{ q }}
          </button>
        </div>
        <el-input
          v-model="draft"
          type="textarea"
          :rows="2"
          resize="none"
          maxlength="1000"
          placeholder="输入你的问题,回车发送,Shift + 回车换行"
          @keydown="onKeydown"
        />
        <div class="composer-foot">
          <span class="hint">
            AI 的回答仅供参考;涉及你自己的订单、余额、信用分,以对应页面显示的为准
          </span>
          <el-button
            type="primary"
            :icon="Promotion"
            :loading="asking"
            :disabled="!draft.trim()"
            @click="send()"
          >
            发送
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-page {
  padding-bottom: var(--space-xl);
}

.ai-shell {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  max-width: 900px;
  margin: 0 auto;
}

/* ── 对话区 ─────────────────────────────────────────────────── */
.chat-card {
  padding: var(--space-lg);
  min-height: 380px;
  max-height: 62vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}

.welcome {
  margin: auto;
  text-align: center;
  padding: var(--space-lg) 0;
}

.welcome-icon {
  font-size: 40px;
  color: var(--color-primary);
}

.welcome-title {
  margin: var(--space-md) 0 var(--space-sm);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.welcome-desc {
  margin: 0 auto var(--space-lg);
  max-width: 460px;
  font-size: var(--font-size-sm);
  line-height: 1.7;
  color: var(--color-text-secondary);
}

.quick-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  justify-content: center;
}

.quick-chip {
  padding: 6px 14px;
  font-size: var(--font-size-sm);
  color: var(--color-primary);
  background: var(--color-primary-light);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: background var(--duration-fast), border-color var(--duration-fast);
}

.quick-chip:hover:not(:disabled) {
  background: var(--color-bg-card);
  border-color: var(--color-primary);
}

.quick-chip:disabled {
  color: var(--color-text-disabled);
  background: var(--color-info-light);
  border-color: var(--color-border);
  cursor: not-allowed;
}

.quick-chip.is-small {
  font-size: var(--font-size-xs);
  padding: 3px 10px;
}

/* ── 气泡 ───────────────────────────────────────────────────── */
.msg {
  display: flex;
  gap: var(--space-sm);
  align-items: flex-start;
}

.msg.is-user {
  flex-direction: row-reverse;
}

.avatar {
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-full);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-xs);
  background: var(--color-primary-light);
  color: var(--color-primary);
}

.msg.is-user .avatar {
  background: var(--color-primary);
  color: #fff;
}

.bubble-wrap {
  max-width: 78%;
}

.bubble {
  padding: 10px 14px;
  border-radius: var(--radius-md);
  font-size: var(--font-size-base);
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-regular);
}

.msg.is-user .bubble {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
}

.bubble.thinking {
  color: var(--color-text-placeholder);
}

.meta {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-top: 6px;
  font-size: var(--font-size-xs);
}

.meta-reason {
  color: var(--color-warning);
}

.meta-resolved {
  color: var(--color-success);
}

/* ── 输入区 ─────────────────────────────────────────────────── */
.composer {
  padding: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.quick-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.composer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
}

.hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

@media (max-width: 768px) {
  .bubble-wrap {
    max-width: 88%;
  }

  .hint {
    display: none;
  }

  .composer-foot {
    justify-content: flex-end;
  }
}
</style>
