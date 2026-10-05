<script setup>
/**
 * 后台 —— 留言管理。
 *
 * 【这一页和前台列表的三个差别,每一个都有原因】
 *   1. 能看到<b>已隐藏的</b>留言。隐藏是为了让前台不再显示,
 *      但管理员还得能找回来 —— 否则"隐藏"就成了不可逆的删除。
 *   2. 能看到<b>回复</b>。只巡查主帖会漏掉一半违规内容,
 *      而回复恰恰是更容易失控的地方(短、情绪化、发得快)。
 *   3. 受<b>数据级权限</b>约束。村管只看得到本村 ——
 *      这条不是前端筛的,是后端的 DataScopeHelper 把 villageIds
 *      注进 SQL 的,前端改参数也越不过去。
 *
 * 【为什么有"只看主帖"和"只看命中敏感词"两个开关】
 * 留言板的数据量大而真正需要处理的少。默认全量翻页是巡查的方式,
 * 但日常真正要看的是这两类:主帖(可能是纠纷源头)和
 * 命中过敏感词的(已经被系统标记过一次)。这两个开关是把
 * 巡查范围收窄的手段,不是筛选项。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { getAdminBoards, adminDeleteBoard } from '@/api/board'
import { useUserStore } from '@/stores/user'
import { BOARD_STATUS, FILTERED_FLAG } from '@/utils/interactDict'
import { formatDateTime } from '@/utils/format'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  status: null,
  onlyRoot: false,
  onlyFiltered: false
})

const scopeText = {
  1: '全部村组的留言',
  2: '本镇范围内的留言',
  3: `本村(${userStore.userInfo?.villageName || ''})的留言`,
  4: '你本人发布的留言'
}[userStore.userInfo?.dataScope] || ''

const deleting = ref(null)

async function fetchList() {
  loading.value = true
  try {
    const data = await getAdminBoards({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      status: query.status ?? undefined,
      // 传 false 等于不传(<if test="q.onlyRoot"> 只在为真时拼条件),
      // 所以这里可以放心地把开关原样送出去
      onlyRoot: query.onlyRoot || undefined,
      onlyFiltered: query.onlyFiltered || undefined
    })
    rows.value = data.rows || []
    total.value = data.total || 0
  } catch (e) {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.keyword = ''
  query.status = null
  query.onlyRoot = false
  query.onlyFiltered = false
  handleSearch()
}

/**
 * 删除。
 *
 * 【为什么必须带二次确认,而且要说清楚后果】
 * 主帖的逻辑删除会<b>级联</b>它的全部回复(后端 softDelete 里做的)。
 * 管理员以为只删了一条,实际上可能连带着删掉二十条 ——
 * 不把这件事说在前面,就是在让人误操作。
 */
async function handleDelete(row) {
  const isRoot = row.parentId == null
  const tip = isRoot
    ? `该留言下的 ${row.replyCount || 0} 条回复会一并删除。`
    : '该回复将从列表中移除。'

  try {
    await ElMessageBox.confirm(
      `${tip}内容是 "${abbreviate(row.content)}"`,
      isRoot ? '删除主帖' : '删除回复',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: false
      }
    )
  } catch {
    return
  }

  deleting.value = row.id
  try {
    await adminDeleteBoard(row.id)
    ElMessage.success('已删除 —— 数据库里仍可追溯')
    fetchList()
  } catch (e) {
    fetchList()
  } finally {
    deleting.value = null
  }
}

const detail = ref(null)
const dialogVisible = ref(false)

/** 表格里只给个预览,完整内容点开弹窗看 */
function abbreviate(text) {
  if (!text) return ''
  return text.length > 40 ? `${text.slice(0, 40)}…` : text
}

/** 表格里截断的内容点开看全文 —— 要不要删,得看到完整的才能定 */
function openDetail(row) {
  detail.value = row
  dialogVisible.value = true
}

onMounted(fetchList)
</script>

<template>
  <div class="admin-board">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        当前数据范围:<b>{{ scopeText }}</b>。
        这一页能看到前台<b>已隐藏</b>的留言和<b>回复</b>(前台列表都不显示),
        删除是<b>逻辑删除</b> —— 前台不再显示,库里仍可追溯,
        因为留言可能是纠纷处理时的证据。
      </template>
    </el-alert>

    <div class="card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索留言内容"
        clearable
        style="width: 200px"
        @keyup.enter="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.status" placeholder="留言状态" clearable style="width: 130px">
        <el-option
          v-for="(o, v) in BOARD_STATUS"
          :key="v"
          :label="o.label"
          :value="Number(v)"
        />
      </el-select>

      <el-checkbox v-model="query.onlyRoot" @change="handleSearch">只看主帖</el-checkbox>
      <el-checkbox v-model="query.onlyFiltered" @change="handleSearch">
        只看命中敏感词
      </el-checkbox>

      <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>

      <div class="spacer" />
      <span class="text-sm text-secondary">共 <b class="num">{{ total }}</b> 条</span>
    </div>

    <div class="card table-card">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="内容" min-width="320">
          <template #default="{ row }">
            <div class="content-line">
              <el-tag v-if="row.parentId != null" size="small" effect="plain" type="info">
                回复
              </el-tag>
              <el-tag v-else size="small" effect="plain">主帖</el-tag>
              <el-tag
                v-if="row.isFiltered === 1"
                size="small"
                :type="FILTERED_FLAG[1].type"
                effect="plain"
              >
                {{ FILTERED_FLAG[1].label }}
              </el-tag>
            </div>
            <div class="text-body" @click="openDetail(row)">{{ abbreviate(row.content) }}</div>
            <div
              v-if="row.images?.length"
              class="text-xs text-secondary"
            >
              含 {{ row.images.length }} 张图片
            </div>
          </template>
        </el-table-column>

        <el-table-column label="发布人" min-width="130">
          <template #default="{ row }">
            <div>{{ row.userName }}</div>
            <div class="text-xs text-secondary">{{ formatDateTime(row.createTime) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="所属村组" width="110">
          <template #default="{ row }">
            <span class="text-sm">{{ row.villageName || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="互动" width="110" align="center">
          <template #default="{ row }">
            <span class="num-text">赞 {{ row.likeCount || 0 }}</span>
            <span class="text-secondary"> / </span>
            <span class="num-text">回 {{ row.replyCount || 0 }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="BOARD_STATUS[row.status]?.type" size="small" effect="dark">
              {{ BOARD_STATUS[row.status]?.label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <!--
              前端隐藏挡不住直接发 HTTP 请求 ——
              接口上还有 @RequiresPerm("board:delete") 独立校验一次
            -->
            <el-button
              v-hasPerm="'board:delete'"
              link
              type="danger"
              size="small"
              :loading="deleting === row.id"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

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

    <!-- 完整内容弹窗。表格里截断显示是为了让一屏装下更多行,
         但管理员判断要不要删,必须看到全文 -->
    <el-dialog v-model="dialogVisible" title="留言全文" width="560px">
      <template v-if="detail">
        <div class="dialog-meta">
          {{ detail.userName }} · {{ detail.villageName }} ·
          {{ formatDateTime(detail.createTime) }}
        </div>
        <p class="dialog-content">{{ detail.content }}</p>
        <div v-if="detail.images?.length" class="post-images">
          <el-image
            v-for="img in detail.images"
            :key="img"
            :src="img"
            :preview-src-list="detail.images"
            fit="cover"
            class="post-image"
            preview-teleported
          />
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.scope-alert {
  margin-bottom: var(--space-md);
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-md) var(--space-lg);
  margin-bottom: var(--space-md);
  flex-wrap: wrap;
}

.spacer {
  flex: 1;
}

.table-card {
  padding: var(--space-md) var(--space-lg);
}

.content-line {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.text-body {
  color: var(--color-text-regular);
  cursor: pointer;
  line-height: 1.6;
}

.text-body:hover {
  color: var(--color-primary);
}

.num-text {
  font-family: var(--font-family-number);
  font-size: var(--font-size-xs);
}

.num {
  font-family: var(--font-family-number);
}

.pager {
  margin-top: var(--space-md);
  justify-content: flex-end;
}

/* ── 弹窗 ──────────────────────────────────────────────────── */
.dialog-meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.dialog-content {
  margin-top: var(--space-sm);
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.post-images {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
}

.post-image {
  width: 96px;
  height: 96px;
  border-radius: var(--radius-sm);
}
</style>
