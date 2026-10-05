<script setup>
/**
 * 消息 —— 会话列表 + 聊天窗。
 *
 * 【会话不是"新建"出来的,是"打开"出来的】
 * 用户在这一页找不到"发起聊天"按钮。所有会话都源于资源详情页的
 * 「联系卖家」或订单详情页的「联系对方」,由服务端按 (双方, 资源)
 * 复用已有会话。这样同一个人围绕同一件东西反复聊,始终只有一个会话 ——
 * 而如果允许凭空新建,同一个人会在列表里出现七八次,历史消息也散成几摊。
 *
 * 【敏感词的处理在这一页是可解释的】
 * 命中"替换"类词的消息,服务端存的是原文、返回的是打码后的内容,
 * 气泡下方会显示「内容已过滤」。不显示的话,用户会以为对方打错了字,
 * 反而去追问 —— 把过滤这件事说破,比藏起来更不容易引起误会。
 * 命中"拦截"类词的消息根本发不出去,会直接弹出错误提示。
 */
import { ref, computed, nextTick, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Promotion } from '@element-plus/icons-vue'
import {
  getConversations, getMessages, sendMessage,
  markConversationRead, startChat
} from '@/api/chat'
import { formatDateTime, formatTimeOnly } from '@/utils/format'

/** 轮询间隔。没有引入 WebSocket:单机毕设的并发量用不上长连接,
 *  而长连接要额外处理断线重连、心跳、连接与登录态的同步 —— 复杂度
 *  远超它带来的收益。10 秒轮询在这个规模下已经够用。 */
const POLL_INTERVAL = 10000

const route = useRoute()

const conversations = ref([])
const listLoading = ref(false)

const activeId = ref(null)
const messages = ref([])
const msgLoading = ref(false)

const draft = ref('')
const sending = ref(false)

const scrollerRef = ref()
const inputRef = ref()

const activeConversation = computed(
  () => conversations.value.find((c) => c.id === activeId.value) || null
)

const canSend = computed(() => draft.value.trim().length > 0 && !sending.value)

// ── 列表 ──────────────────────────────────────────────────────

async function loadConversations(silent = false) {
  if (!silent) listLoading.value = true
  try {
    conversations.value = await getConversations() || []
  } catch (e) {
    if (!silent) conversations.value = []
  } finally {
    listLoading.value = false
  }
}

// ── 消息 ──────────────────────────────────────────────────────

async function loadMessages(conversationId, { silent = false } = {}) {
  if (!silent) msgLoading.value = true
  try {
    const list = await getMessages(conversationId, 100)
    const wasAtBottom = isNearBottom()
    messages.value = list || []
    if (!silent || wasAtBottom) scrollToBottom()
  } catch (e) {
    if (!silent) messages.value = []
  } finally {
    msgLoading.value = false
  }
}

function isNearBottom() {
  const el = scrollerRef.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

function scrollToBottom() {
  nextTick(() => {
    const el = scrollerRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

/**
 * 打开一个会话。
 *
 * 打开即标记已读 —— 未读数的意义是"有我没看过的",点进去就算看过了,
 * 让用户再手动点一次"标记已读"没有任何道理。
 */
async function openConversation(id) {
  if (activeId.value === id) return
  activeId.value = id
  messages.value = []
  await loadMessages(id)
  inputRef.value?.focus()
  try {
    await markConversationRead(id)
    await loadConversations(true)
  } catch (e) {
    // 标记已读失败不影响看消息,不打断用户
  }
}

// ── 发送 ──────────────────────────────────────────────────────

async function handleSend() {
  const content = draft.value.trim()
  if (!content || !activeId.value) return

  sending.value = true
  try {
    await sendMessage({ conversationId: activeId.value, content })
    // 先清空输入框再拉消息:发送成功是确定的事,而网络往返有延迟,
    // 等着回来再清会让用户以为没发出去,又按一次
    draft.value = ''
    await loadMessages(activeId.value)
    scrollToBottom()
    await loadConversations(true)
  } catch (e) {
    // 敏感词拦截、未登录等由拦截器提示。草稿**不清空** ——
    // 内容被拦下来还把用户写的话弄丢,是最让人恼火的一种失败
  } finally {
    sending.value = false
  }
}

/** Enter 发送,Shift+Enter 换行 —— 输入法组合中的 Enter 不算发送 */
function onKeydown(e) {
  if (e.key !== 'Enter' || e.shiftKey || e.isComposing) return
  e.preventDefault()
  if (canSend.value) handleSend()
}

// ── 轮询 ──────────────────────────────────────────────────────
let timer = null
/**
 * 组件是否已经卸载。
 *
 * onMounted 里有 await(要先 startChat 再拉列表),如果用户在这中间就离开了
 * 这一页,onBeforeUnmount 早就跑完了、清了一个还是 null 的 timer,
 * 之后 onMounted 里 setInterval 才把定时器建起来 —— 它再也没人清,
 * 每次离开这一页就漏一个,轮询会一直在后台跑。
 * 定时器不是"设了就能在卸载时清掉"的,创建时机跨了 await 就得自己防。
 */
let destroyed = false

async function poll() {
  await loadConversations(true)
  const current = conversations.value.find((c) => c.id === activeId.value)
  if (current && Number(current.unread) > 0) {
    await loadMessages(activeId.value, { silent: true })
    try {
      await markConversationRead(activeId.value)
      await loadConversations(true)
    } catch (e) {
      // 忽略,下一轮再试
    }
  }
}

/** 对方的头像兜底:没有头像时用名字首字 */
function initialOf(name) {
  return name ? name.trim().charAt(0) : '?'
}

onMounted(async () => {
  // 从资源详情页/订单详情页跳过来时带 ?to=&resourceId= / ?orderId=,
  // 这时先让服务端把会话找出来或建出来,再落到 ?c=
  const { to, resourceId, orderId, c } = route.query
  let targetId = c ? Number(c) : null

  if (!targetId && to) {
    try {
      targetId = await startChat({
        targetUserId: Number(to),
        resourceId: resourceId ? Number(resourceId) : undefined,
        orderId: orderId ? Number(orderId) : undefined
      })
    } catch (e) {
      ElMessage.error('无法打开会话')
    }
  }

  await loadConversations()
  if (targetId) {
    const exists = conversations.value.some((x) => x.id === targetId)
    if (exists) {
      await openConversation(targetId)
    } else {
      ElMessage.warning('会话不存在或已被清理')
    }
  }

  if (destroyed) return
  timer = setInterval(poll, POLL_INTERVAL)
})

onBeforeUnmount(() => {
  destroyed = true
  if (timer) clearInterval(timer)
})

// 会话被删/被清空时把右侧也清掉,避免左右不一致
watch(conversations, (list) => {
  if (activeId.value && !list.some((c) => c.id === activeId.value)) {
    activeId.value = null
    messages.value = []
  }
})
</script>

<template>
  <div class="page-container messages-page">
    <header class="page-head">
      <h1>消息</h1>
      <p class="text-secondary text-sm">
        围绕一件资源或一笔订单沟通。聊天内容在纠纷调解时可被管理员调取 ——
        这是为了在双方各执一词时能看清经过。
      </p>
    </header>

    <div class="chat-layout card">
      <!-- ── 会话列表 ──────────────────────────────────────── -->
      <aside v-loading="listLoading" class="conv-panel">
        <div
          v-for="conv in conversations"
          :key="conv.id"
          :class="['conv-item', { active: conv.id === activeId }]"
          @click="openConversation(conv.id)"
        >
          <el-avatar :size="38" :src="conv.peerAvatar" class="conv-avatar">
            {{ initialOf(conv.peerName) }}
          </el-avatar>

          <div class="conv-body">
            <div class="conv-top">
              <span class="conv-name">{{ conv.peerName }}</span>
              <span class="text-xs text-secondary">
                {{ conv.lastTime ? formatDateTime(conv.lastTime) : '' }}
              </span>
            </div>
            <div class="conv-bottom">
              <span class="conv-preview">{{ conv.lastMessage || '暂无消息' }}</span>
              <el-badge
                v-if="Number(conv.unread) > 0"
                :value="conv.unread"
                :max="99"
                class="conv-badge"
              />
            </div>
          </div>
        </div>

        <el-empty
          v-if="!listLoading && !conversations.length"
          description="还没有会话 —— 去资源详情页点「联系卖家」"
          :image-size="70"
        />
      </aside>

      <!-- ── 聊天窗 ────────────────────────────────────────── -->
      <section class="chat-panel">
        <template v-if="activeConversation">
          <header class="chat-head">
            <div>
              <span class="chat-peer">{{ activeConversation.peerName }}</span>
              <el-tag
                v-if="activeConversation.resourceTitle"
                size="small"
                effect="plain"
                class="chat-context"
              >
                关于「{{ activeConversation.resourceTitle }}」
              </el-tag>
              <el-tag
                v-else-if="activeConversation.orderNo"
                size="small"
                effect="plain"
                class="chat-context"
              >
                订单 {{ activeConversation.orderNo }}
              </el-tag>
            </div>
          </header>

          <div ref="scrollerRef" v-loading="msgLoading" class="msg-scroller">
            <div
              v-for="msg in messages"
              :key="msg.id"
              :class="['msg-row', msg.isMine ? 'is-mine' : 'is-peer']"
            >
              <el-avatar
                v-if="!msg.isMine"
                :size="32"
                :src="activeConversation.peerAvatar"
                class="msg-avatar"
              >
                {{ initialOf(activeConversation.peerName) }}
              </el-avatar>

              <div class="msg-main">
                <div class="bubble">{{ msg.content }}</div>
                <div class="msg-meta">
                  <span v-if="msg.isFiltered === 1" class="filtered-tip">
                    内容已过滤
                  </span>
                  <span class="text-xs text-secondary">{{ formatTimeOnly(msg.createTime) }}</span>
                </div>
              </div>
            </div>

            <el-empty
              v-if="!msgLoading && !messages.length"
              description="还没有消息,打个招呼吧"
              :image-size="70"
            />
          </div>

          <footer class="chat-input">
            <el-input
              ref="inputRef"
              v-model="draft"
              type="textarea"
              :rows="3"
              resize="none"
              maxlength="1000"
              show-word-limit
              placeholder="Enter 发送,Shift + Enter 换行"
              @keydown="onKeydown"
            />
            <el-button
              type="primary"
              :icon="Promotion"
              :loading="sending"
              :disabled="!canSend"
              class="send-btn"
              @click="handleSend"
            >
              发送
            </el-button>
          </footer>
        </template>

        <el-empty
          v-else
          description="从左侧选择一个会话"
          :image-size="90"
        >
          <template #image>
            <el-icon :size="70" class="empty-icon"><ChatDotRound /></el-icon>
          </template>
        </el-empty>
      </section>
    </div>
  </div>
</template>

<style scoped>
.messages-page {
  padding-top: var(--space-xl);
  padding-bottom: var(--space-2xl);
}

.page-head {
  margin-bottom: var(--space-md);
}

.page-head h1 {
  font-size: var(--font-size-xl);
  margin-bottom: var(--space-xs);
}

.chat-layout {
  display: flex;
  height: 620px;
  padding: 0;
  overflow: hidden;
}

/* ── 左:会话列表 ─────────────────────────────────────────── */
.conv-panel {
  width: 280px;
  flex-shrink: 0;
  overflow-y: auto;
  border-right: 1px solid var(--color-border-light);
  background: var(--color-bg-card);
}

.conv-item {
  display: flex;
  gap: var(--space-sm);
  padding: var(--space-md);
  cursor: pointer;
  border-bottom: 1px solid var(--color-border-light);
  transition: background var(--duration-fast);
}

.conv-item:hover {
  background: var(--color-bg-page);
}

.conv-item.active {
  background: var(--color-primary-light);
  box-shadow: inset 3px 0 0 var(--color-primary);
}

.conv-avatar {
  flex-shrink: 0;
  background: var(--color-primary-border);
  color: var(--color-primary);
  font-weight: var(--font-weight-semibold);
}

.conv-body {
  flex: 1;
  min-width: 0;
}

.conv-top,
.conv-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-xs);
}

.conv-name {
  font-weight: var(--font-weight-medium);
  font-size: var(--font-size-sm);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-preview {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-badge {
  flex-shrink: 0;
}

/* ── 右:聊天窗 ───────────────────────────────────────────── */
.chat-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-md) var(--space-lg);
  border-bottom: 1px solid var(--color-border-light);
}

.chat-peer {
  font-weight: var(--font-weight-semibold);
}

.chat-context {
  margin-left: var(--space-sm);
}

.msg-scroller {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-lg);
  background: var(--color-bg-page);
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.msg-row {
  display: flex;
  align-items: flex-start;
  gap: var(--space-sm);
  max-width: 78%;
}

.msg-row.is-mine {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.msg-avatar {
  flex-shrink: 0;
  background: var(--color-primary-border);
  color: var(--color-primary);
  font-size: var(--font-size-sm);
}

.msg-main {
  min-width: 0;
}

.bubble {
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
}

.is-peer .bubble {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-top-left-radius: 2px;
}

.is-mine .bubble {
  background: var(--color-primary);
  color: #fff;
  border-top-right-radius: 2px;
}

.msg-meta {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin-top: 2px;
}

.is-mine .msg-meta {
  justify-content: flex-end;
}

.filtered-tip {
  font-size: var(--font-size-xs);
  color: var(--color-warning);
}

/* ── 输入区 ───────────────────────────────────────────────── */
.chat-input {
  display: flex;
  align-items: flex-end;
  gap: var(--space-sm);
  padding: var(--space-md) var(--space-lg);
  border-top: 1px solid var(--color-border-light);
}

.send-btn {
  flex-shrink: 0;
  height: 62px;
}

.empty-icon {
  color: var(--color-text-disabled);
}

@media (max-width: 900px) {
  .chat-layout {
    flex-direction: column;
    height: auto;
  }

  .conv-panel {
    width: 100%;
    max-height: 240px;
    border-right: none;
    border-bottom: 1px solid var(--color-border-light);
  }

  .msg-scroller {
    height: 380px;
    flex: none;
  }

  .msg-row {
    max-width: 92%;
  }
}
</style>
