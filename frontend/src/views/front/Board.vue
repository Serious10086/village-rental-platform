<script setup>
/**
 * 前台 —— 留言板。
 *
 * 【这一页的核心是一对多,不是列表】
 * 主帖列表只是骨架,真正的信息在"点开之后"。所以回复默认折叠、
 * 需要时才拉取 —— 一页 10 条主帖如果连回复一起查出来,
 * 那是 11 次查询(1 次列表 + 10 次回复),而用户可能一条都不点开。
 *
 * 【为什么主帖能配图、回复不能】
 * 回复输入框长在每条主帖下面,一页 10 条就是 10 个文件上传控件。
 * 配图是"发布"动作的一部分,而回复是快速响应 ——
 * 想看东西可以自己发一条主帖。这一层是界面上的取舍,不动数据结构。
 *
 * 【留言板不要求实名】
 * 只要求"属于某个村组"。村内讨论区的门槛是该村的村民身份,
 * 不是实名认证 —— 实名是交易域的要求(要签合同、要担责),
 * 拿它来卡闲聊会让留言板没人用。后端 requireMyVillage 就是按这个尺度写的。
 */
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotSquare, Delete, Plus, Star, StarFilled } from '@element-plus/icons-vue'
import {
  getBoardList, getBoardReplies, postBoard, replyBoard,
  toggleBoardLike, deleteBoard
} from '@/api/board'
import { getVillageList } from '@/api/auth'
import { uploadImage } from '@/api/resource'
import { useUserStore } from '@/stores/user'
import { FILTERED_FLAG } from '@/utils/interactDict'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const MAX_IMAGES = 9

// ── 村组切换 ──────────────────────────────────────────────────
// 留言板按村隔离,所以"看哪个村"是这一页的第一个参数,
// 而不是一个可选的筛选项。游客必须自己选,登录用户默认落在本村。
const villages = ref([])
const villageId = ref(null)

/**
 * 默认村组。
 *
 * 登录用户默认落在<b>本村</b> —— 这是他会关心的那个村。
 * 游客没有本村,退化成列表第一个,总比空白页好。
 * 但要求本村的 id 确实在启用列表里:村里的账号被停用后,
 * userInfo 里还留着旧 villageId,直接用它查会得到一条空列表,
 * 用户看到的是"这个村没人留言"而不是"这个村关了"。
 */
function pickDefaultVillage() {
  const mine = userStore.userInfo?.villageId
  if (mine && villages.value.some((v) => v.id === mine)) return mine
  return villages.value[0]?.id ?? null
}

async function fetchVillages() {
  try {
    const list = await getVillageList()
    // 只留村级。flat 列表里乡镇和村是混着的,乡镇没有留言板
    villages.value = (list || []).filter((v) => v.parentId != null)
  } catch (e) {
    villages.value = []
  }
  villageId.value = pickDefaultVillage()
}

// ── 主帖列表 ──────────────────────────────────────────────────
const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({ pageNum: 1, pageSize: 10 })

async function fetchList() {
  if (!villageId.value) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const data = await getBoardList({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      villageId: villageId.value
    })
    rows.value = data.rows || []
    total.value = data.total || 0
  } catch (e) {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
  applyRoutePostId()
}

watch(villageId, () => {
  query.pageNum = 1
  // 换村了,之前展开的回复全部作废 —— 那些帖子和新村的列表没关系了
  resetReplies()
  fetchList()
})

// ── 回复(按需加载)────────────────────────────────────────────

/**
 * 每条主帖的回复状态,按主帖 id 存。
 *
 * 【为什么用 reactive 对象而不是给 row 挂字段】
 * rows 会被整个替换(翻页/刷新),挂在 row 上的展开状态会跟着丢。
 * 放在外面按 id 索引,翻页回来还是展开的。
 */
const replies = reactive({})

function resetReplies() {
  Object.keys(replies).forEach((k) => delete replies[k])
}

function replyState(id) {
  if (!replies[id]) {
    replies[id] = {
      expanded: false, loaded: false, loading: false,
      rows: [], total: 0, pageNum: 1, pageSize: 5, expandedAll: false
    }
  }
  return replies[id]
}

async function fetchReplies(id) {
  const st = replyState(id)
  st.loading = true
  try {
    const data = await getBoardReplies(id, { pageNum: st.pageNum, pageSize: st.pageSize })
    st.rows = data.rows || []
    st.total = data.total || 0
    st.loaded = true
  } catch (e) {
    st.rows = []
    st.total = 0
  } finally {
    st.loading = false
  }
}

async function toggleReplies(row) {
  const st = replyState(row.id)
  st.expanded = !st.expanded
  if (st.expanded && !st.loaded) {
    await fetchReplies(row.id)
  }
}

/** 展开全部回复(后端把 pageSize 上限卡在 50,不会拉出一个无底洞) */
async function expandAllReplies(row) {
  const st = replyState(row.id)
  st.pageSize = 50
  st.pageNum = 1
  st.expandedAll = true
  await fetchReplies(row.id)
}

// ── 发主帖 ────────────────────────────────────────────────────
const composing = reactive({ content: '', images: [] })
const submitting = ref(false)
const uploading = ref(false)

/**
 * 我所属的村组。
 *
 * 【必须从 villages 里反查,不能信 userInfo.villageName】
 * villageName 后端也返回了,但它不区分乡镇和村 —— 演示数据里平台管理员
 * 的 village_id 是 1(唐昌镇),直接用 villageName 会得到一个乡镇名,
 * 而这里要判断的是"你有没有一个村级留言板"。反查一次,乡镇账号自然落空。
 */
const myVillageId = computed(() => userStore.userInfo?.villageId ?? null)

const myVillage = computed(() =>
  villages.value.find((v) => v.id === myVillageId.value) || null
)

/** 超管不属于任何一个村,后端会拒绝发帖 —— 那前端就别给输入框 */
const canPost = computed(() =>
  userStore.isLoggedIn &&
  myVillageId.value != null &&
  villageId.value === myVillageId.value
)

/**
 * 不能发言时给一句<说到点上>的解释。
 *
 * 【为什么要区分三种情况而不是统一说"不能发帖"】
 * 后端 post() 永远把留言写进<b>发帖人自己的村</b>(用 me.getVillageId(),
 * 不接受前端传入)。所以"在金星村看战旗村的留言板"这件事是合法的,
 * 但在这个页面上发言不合法 —— 说成"你不能发帖"会让人以为账号有问题,
 * 实际只是站错了村。三种情况的下一步动作完全不同:去登录 / 回本村 / 找管理员绑村组。
 *
 * 【乡镇账号为什么也发不了】
 * 演示数据里平台管理员的 village_id 是 1(唐昌镇,乡镇级而非村级)。
 * 村组选择器只列村级,所以他永远站不到"自己的村"上。
 * 与其让他把留言写进一个乡镇 id,不如明确告诉他这里没有他的留言板。
 */
const composeHint = computed(() => {
  if (!userStore.isLoggedIn) {
    return { text: '登录后即可在本村留言板发言 —— 游客可以浏览', action: 'login' }
  }
  if (myVillageId.value == null) {
    return { text: '你的账号还没有绑定村组,暂时不能发布留言', action: null }
  }
  if (!myVillage.value) {
    return { text: '你的账号挂在乡镇一级,没有对应的村级留言板', action: null }
  }
  return {
    text: `你只能在本村(${myVillage.value.villageName})的留言板上发言 —— 现在看的是别村的`,
    action: 'back'
  }
})

async function handleUpload(options) {
  uploading.value = true
  try {
    const url = await uploadImage(options.file)
    composing.images.push(url)
    options.onSuccess?.(url)
  } catch (e) {
    options.onError?.(e)
  } finally {
    uploading.value = false
  }
}

function removeImage(index) {
  composing.images.splice(index, 1)
}

function ensureLogin(action) {
  if (userStore.isLoggedIn) return true
  ElMessage.warning(`请先登录再${action}`)
  router.push({ path: '/login', query: { redirect: route.fullPath } })
  return false
}

async function submitPost() {
  const content = composing.content.trim()
  if (!content) {
    ElMessage.warning('说点什么吧')
    return
  }
  submitting.value = true
  try {
    await postBoard({ content, images: composing.images })
    composing.content = ''
    composing.images = []
    ElMessage.success('已发布')
    query.pageNum = 1
    await fetchList()
  } finally {
    submitting.value = false
  }
}

// ── 回复 ──────────────────────────────────────────────────────
// 回复草稿也按主帖 id 存:在 A 帖里打了一半字又去 B 帖看看,
// 回来时草稿还在
const replyDrafts = reactive({})
const replyingId = ref(null)

async function submitReply(row) {
  const content = (replyDrafts[row.id] || '').trim()
  if (!content) {
    ElMessage.warning('回复内容不能为空')
    return
  }
  replyingId.value = row.id
  try {
    await replyBoard({ parentId: row.id, content })
    replyDrafts[row.id] = ''
    ElMessage.success('已回复')

    const st = replyState(row.id)
    st.expanded = true
    // 回复按时间正序,新回复在最后一页 —— 所以直接跳最后一页,
    // 而不是回到第一页让用户自己翻
    st.pageNum = Math.max(1, Math.ceil((st.total + 1) / st.pageSize))
    await fetchReplies(row.id)

    // 列表上的回复数要跟着涨。整表刷新太重(会把展开状态和草稿都冲掉),
    // 而且 replyCount 是后端维护的冗余计数,这里只做就地 +1
    row.replyCount = (row.replyCount || 0) + 1
  } finally {
    replyingId.value = null
  }
}

// ── 点赞 ──────────────────────────────────────────────────────

/**
 * 点赞 / 取消点赞。
 *
 * 【先改界面,再发请求】
 * 点赞是最高频的动作,等一个网络往返再变色会让人以为没点上、
 * 于是再点一次 —— 两次请求互相抵消,最后什么也没发生。
 * 所以本地先翻,失败再回滚到快照。
 *
 * 【以服务端返回为准】
 * 快速连点两次时,本地推断出来的状态和服务端的最终状态可能相反。
 * 响应里的布尔值才是事实,不一致就按它纠正,而不是硬信自己。
 */
async function handleLike(row) {
  if (!ensureLogin('点赞')) return

  const snapshot = { liked: row.liked, likeCount: row.likeCount || 0 }
  const next = !row.liked
  row.liked = next
  row.likeCount = snapshot.likeCount + (next ? 1 : -1)

  try {
    const liked = await toggleBoardLike(row.id)
    if (liked !== row.liked) {
      row.likeCount += liked ? 1 : -1
      row.liked = liked
    }
  } catch (e) {
    row.liked = snapshot.liked
    row.likeCount = snapshot.likeCount
  }
}

// ── 删除 ──────────────────────────────────────────────────────
// 主帖只有作者本人能删(后台管理员走 /api/admin/board,是另一条路)

function canDelete(item) {
  return userStore.isLoggedIn && userStore.userInfo?.userId === item.userId
}

async function handleDelete(row, isReply) {
  try {
    await ElMessageBox.confirm(
      isReply
        ? '删除这条回复?'
        : `删除这条留言?该留言下的 ${row.replyCount || 0} 条回复会一并删除。`,
      '确认删除',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteBoard(row.id)
    ElMessage.success('已删除')
    if (isReply) {
      const st = replyState(row.parentId)
      st.pageNum = 1
      await fetchReplies(row.parentId)
      const parent = rows.value.find((r) => r.id === row.parentId)
      if (parent) parent.replyCount = Math.max(0, (parent.replyCount || 0) - 1)
    } else {
      await fetchList()
    }
  } catch (e) {
    /* 后端已给出原因(非本人 / 不存在),这里不再重复弹一次 */
  }
}

// ── 从通知跳进来 ──────────────────────────────────────────────

/**
 * 通知里的"有人回复了你的留言"带着 ?postId= 过来,
 * 直接展开那一条,省得用户在列表里翻找。
 *
 * 【找不到就什么都不做】
 * postId 可能已经翻到第 3 页去了,而这里只有第 1 页的数据。
 * 为此专门加一个"按 id 查单条"的接口,只为了省用户一次翻页,
 * 不划算 —— 留言板本来就是按时间倒序,近期被回复的多半就在前几页。
 */
async function applyRoutePostId() {
  const pid = Number(route.query.postId)
  if (!pid || !rows.value.length) return
  const target = rows.value.find((r) => r.id === pid)
  if (!target) return

  const st = replyState(pid)
  if (!st.loaded) {
    st.expanded = true
    await fetchReplies(pid)
  }
  await nextTick()
  document.getElementById(`board-post-${pid}`)?.scrollIntoView({
    behavior: 'smooth', block: 'center'
  })
}

watch(() => route.query.postId, applyRoutePostId)

onMounted(async () => {
  await fetchVillages()
  await fetchList()
})
</script>

<template>
  <div class="page-container board-page">
    <!-- ── 页头:村组切换 ─────────────────────────────────── -->
    <header class="page-head">
      <div>
        <h1 class="page-title">村民留言板</h1>
        <p class="page-sub">
          {{ villages.find((v) => v.id === villageId)?.villageName || '请选择村组' }}
          的公共讨论区 —— 换工、拼单、打听事,都可以在这儿说
        </p>
      </div>

      <el-select
        v-model="villageId"
        placeholder="选择村组"
        filterable
        style="width: 190px"
      >
        <el-option
          v-for="v in villages"
          :key="v.id"
          :label="v.villageName"
          :value="v.id"
        />
      </el-select>
    </header>

    <!-- ── 发帖框 ─────────────────────────────────────────── -->
    <section class="card composer">
      <template v-if="canPost">
        <el-input
          v-model="composing.content"
          type="textarea"
          :rows="3"
          maxlength="1000"
          show-word-limit
          resize="none"
          placeholder="想跟村里人聊点什么?"
        />

        <div v-if="composing.images.length" class="thumb-row">
          <div v-for="(img, i) in composing.images" :key="img" class="thumb">
            <img :src="img" alt="">
            <el-icon class="thumb-del" @click="removeImage(i)"><Delete /></el-icon>
          </div>
        </div>

        <div class="composer-foot">
          <el-upload
            :show-file-list="false"
            accept="image/*"
            :http-request="handleUpload"
            :disabled="composing.images.length >= MAX_IMAGES || uploading"
          >
            <el-button :icon="Plus" text :loading="uploading">
              {{ uploading ? '上传中…' : `配图(${composing.images.length}/${MAX_IMAGES})` }}
            </el-button>
          </el-upload>

          <div class="spacer" />
          <el-button
            type="primary"
            :loading="submitting"
            :disabled="!composing.content.trim()"
            @click="submitPost"
          >
            发布
          </el-button>
        </div>
      </template>

      <!-- 不能发言的理由有三种,对应的下一步动作完全不同,所以分开说 -->
      <div v-else class="compose-empty">
        <span class="hint-text">{{ composeHint.text }}</span>
        <el-button
          v-if="composeHint.action === 'login'"
          type="primary"
          @click="ensureLogin('发帖')"
        >
          去登录
        </el-button>
        <el-button
          v-else-if="composeHint.action === 'back'"
          type="primary"
          plain
          @click="villageId = myVillageId"
        >
          回到本村
        </el-button>
      </div>
    </section>

    <!-- ── 主帖列表 ───────────────────────────────────────── -->
    <div v-loading="loading" class="feed">
      <el-empty
        v-if="!loading && !rows.length"
        description="这个村还没有人留言,你可以开个头"
      />

      <article
        v-for="row in rows"
        :id="`board-post-${row.id}`"
        :key="row.id"
        class="card post"
      >
        <!-- 作者行 -->
        <div class="post-head">
          <el-avatar :size="38" :src="row.userAvatar">
            {{ (row.userName || '?').charAt(0) }}
          </el-avatar>
          <div class="who">
            <div class="name-line">
              <span class="name">{{ row.userName }}</span>
              <el-tag v-if="row.villageName" size="small" effect="plain">
                {{ row.villageName }}
              </el-tag>
            </div>
            <div class="time">{{ formatDateTime(row.createTime) }}</div>
          </div>

          <el-button
            v-if="canDelete(row)"
            link
            type="danger"
            size="small"
            :icon="Delete"
            @click="handleDelete(row, false)"
          >
            删除
          </el-button>
        </div>

        <!-- 正文 -->
        <p class="content">{{ row.content }}</p>
        <el-tag
          v-if="row.isFiltered === 1"
          :type="FILTERED_FLAG[1].type"
          size="small"
          effect="plain"
          class="filtered-tag"
        >
          {{ FILTERED_FLAG[1].label }},内容已打码
        </el-tag>

        <div v-if="row.images?.length" class="post-images">
          <el-image
            v-for="img in row.images"
            :key="img"
            :src="img"
            :preview-src-list="row.images"
            fit="cover"
            class="post-image"
            preview-teleported
          />
        </div>

        <!-- 操作条 -->
        <div class="post-actions">
          <button
            class="act"
            :class="{ 'is-on': row.liked }"
            @click="handleLike(row)"
          >
            <el-icon>
              <StarFilled v-if="row.liked" />
              <Star v-else />
            </el-icon>
            {{ row.likeCount || 0 }}
          </button>

          <button
            class="act"
            :class="{ 'is-on': replyState(row.id).expanded }"
            @click="toggleReplies(row)"
          >
            <el-icon><ChatDotSquare /></el-icon>
            {{ row.replyCount || 0 }} 条回复
          </button>
        </div>

        <!-- ── 展开的回复区 ─────────────────────────────── -->
        <div v-if="replyState(row.id).expanded" class="reply-zone">
          <div v-loading="replyState(row.id).loading" class="reply-list">
            <el-empty
              v-if="!replyState(row.id).loading && !replyState(row.id).rows.length"
              :image-size="40"
              description="还没有人回复"
            />

            <div
              v-for="rep in replyState(row.id).rows"
              :key="rep.id"
              class="reply"
            >
              <el-avatar :size="28" :src="rep.userAvatar">
                {{ (rep.userName || '?').charAt(0) }}
              </el-avatar>
              <div class="reply-body">
                <div class="name-line">
                  <span class="name sm">{{ rep.userName }}</span>
                  <span class="time">{{ formatDateTime(rep.createTime) }}</span>
                  <el-tag
                    v-if="rep.isFiltered === 1"
                    :type="FILTERED_FLAG[1].type"
                    size="small"
                    effect="plain"
                  >
                    已打码
                  </el-tag>
                </div>
                <p class="reply-text">{{ rep.content }}</p>
              </div>
              <el-button
                v-if="canDelete(rep)"
                link
                type="danger"
                size="small"
                @click="handleDelete(rep, true)"
              >
                删除
              </el-button>
            </div>
          </div>

          <!-- 回复数超过一页时给出"看全部" -->
          <div
            v-if="!replyState(row.id).expandedAll && replyState(row.id).total > replyState(row.id).rows.length"
            class="more-replies"
          >
            <el-button link type="primary" @click="expandAllReplies(row)">
              查看全部 {{ replyState(row.id).total }} 条回复
            </el-button>
          </div>

          <!-- 回复框 -->
          <div v-if="canPost" class="reply-box">
            <el-input
              v-model="replyDrafts[row.id]"
              type="textarea"
              :rows="2"
              maxlength="1000"
              resize="none"
              :placeholder="`回复 ${row.userName}…`"
            />
            <el-button
              type="primary"
              size="small"
              :loading="replyingId === row.id"
              :disabled="!(replyDrafts[row.id] || '').trim()"
              @click="submitReply(row)"
            >
              回复
            </el-button>
          </div>
          <!-- 理由同上:登录/本村这两件事分开说,别让人以为是账号坏了 -->
          <div v-else class="reply-box guest">
            <span class="hint-text">
              {{ userStore.isLoggedIn ? '只能在本村留言板回复' : '登录后即可回复' }}
            </span>
            <el-button
              v-if="!userStore.isLoggedIn"
              link
              type="primary"
              @click="ensureLogin('回复')"
            >
              去登录
            </el-button>
          </div>
        </div>
      </article>
    </div>

    <el-pagination
      v-if="total > query.pageSize"
      v-model:current-page="query.pageNum"
      :page-size="query.pageSize"
      :total="total"
      layout="total, prev, pager, next"
      class="pager"
      @current-change="fetchList"
    />
  </div>
</template>

<style scoped>
.board-page {
  padding-bottom: var(--space-2xl);
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
}

.page-title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  letter-spacing: -0.02em;
}

.page-sub {
  margin-top: 4px;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

/* ── 发帖框 ────────────────────────────────────────────────── */
.composer {
  padding: var(--space-md) var(--space-lg);
  margin-bottom: var(--space-lg);
  border-left: 3px solid var(--color-primary);
}

.composer-foot {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
}

.spacer {
  flex: 1;
}

.thumb-row {
  display: flex;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
}

.thumb {
  position: relative;
  width: 68px;
  height: 68px;
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-del {
  position: absolute;
  top: 2px;
  right: 2px;
  padding: 2px;
  color: #fff;
  background: rgba(15, 23, 42, 0.55);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 12px;
}

/* ── 主帖 ──────────────────────────────────────────────────── */
.feed {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.post {
  padding: var(--space-md) var(--space-lg);
}

.post-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.who {
  flex: 1;
  min-width: 0;
}

.name-line {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.name {
  font-weight: var(--font-weight-medium);
}

.name.sm {
  font-size: var(--font-size-sm);
}

.time {
  font-size: var(--font-size-xs);
  color: var(--color-text-placeholder);
}

.content {
  margin-top: var(--space-sm);
  color: var(--color-text-primary);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.filtered-tag {
  margin-top: var(--space-sm);
}

.post-images {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
}

.post-image {
  width: 108px;
  height: 108px;
  border-radius: var(--radius-sm);
  cursor: zoom-in;
}

/* ── 操作条 ────────────────────────────────────────────────── */
/* 用原生 button 而不是 el-button:这两个是"计数式"操作,
   需要图标和数字贴在一起,并排两个 el-button 的间距会把它们推得很开 */
.post-actions {
  display: flex;
  gap: var(--space-lg);
  margin-top: var(--space-md);
  padding-top: var(--space-sm);
  border-top: 1px solid var(--color-border-light);
}

.act {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 8px;
  font-size: var(--font-size-sm);
  font-family: inherit;
  color: var(--color-text-secondary);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: color var(--duration-fast), background var(--duration-fast);
}

.act:hover {
  color: var(--color-primary);
  background: var(--color-primary-light);
}

/* 已点赞:实心星 + 主色。填充与否本身就是状态,不用再看数字 */
.act.is-on {
  color: var(--color-primary);
  font-weight: var(--font-weight-medium);
}

/* ── 回复区 ────────────────────────────────────────────────── */
.reply-zone {
  margin-top: var(--space-md);
  padding: var(--space-md) var(--space-md) var(--space-sm);
  background: var(--color-bg-page);
  border-radius: var(--radius-md);
}

.reply {
  display: flex;
  align-items: flex-start;
  gap: var(--space-sm);
  padding: var(--space-sm) 0;
}

.reply + .reply {
  border-top: 1px solid var(--color-border-light);
}

.reply-body {
  flex: 1;
  min-width: 0;
}

.reply-text {
  margin-top: 2px;
  font-size: var(--font-size-sm);
  color: var(--color-text-regular);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.more-replies {
  padding: var(--space-xs) 0 var(--space-sm);
  text-align: center;
}

.reply-box {
  display: flex;
  align-items: flex-end;
  gap: var(--space-sm);
  padding-top: var(--space-sm);
  border-top: 1px solid var(--color-border);
}

.reply-box.guest {
  justify-content: center;
  align-items: center;
  gap: var(--space-sm);
}

/* ── 不能发言时的提示 ──────────────────────────────────────── */
/* 不用 el-empty:一个 60px 的空状态插图 + 居中大段文字,
   在发帖框那么窄的条里显得过重。一行说明 + 一个动作就够了 */
.compose-empty {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  padding: var(--space-sm) 0;
}

.hint-text {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

/* ── 分页 ──────────────────────────────────────────────────── */
.pager {
  margin-top: var(--space-lg);
  justify-content: center;
}

@media (max-width: 700px) {
  .page-head {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
