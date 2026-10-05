<script setup>
/**
 * 消息通知。
 *
 * 通知的价值在于"点进去能做点什么",所以每条通知都带一个跳转路径
 * (由后端算好)—— 审核通过的通知点进去直达那个资源,而不是让用户自己去翻。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check } from '@element-plus/icons-vue'
import { useNoticeStore } from '@/stores/notice'
import { NOTICE_TYPE } from '@/utils/auditDict'
import { formatDateTime } from '@/utils/format'

const router = useRouter()
const noticeStore = useNoticeStore()

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  isRead: null
})

const total = ref(0)

const filterTabs = [
  { label: '全部', value: null },
  { label: '未读', value: 0 },
  { label: '已读', value: 1 }
]

/** 列表数据 */
const notices = computed(() => noticeStore.notices)
const loading = computed(() => noticeStore.loading)

const unreadCount = computed(() => noticeStore.unreadCount)

async function fetchList() {
  const data = await noticeStore.fetchList({
    pageNum: query.pageNum,
    pageSize: query.pageSize,
    isRead: query.isRead ?? undefined
  })
  total.value = data?.total || 0
}

function handleTabChange() {
  query.pageNum = 1
  fetchList()
}

/** 点击通知:标已读 + 跳转 */
async function handleClick(item) {
  if (!item.isRead) {
    await noticeStore.markRead(item.id).catch(() => {})
  }
  if (item.linkPath) {
    router.push(item.linkPath)
  }
}

async function handleMarkAllRead() {
  if (unreadCount.value === 0) {
    ElMessage.info('没有未读消息')
    return
  }
  await noticeStore.markAllRead()
  ElMessage.success('已全部标记为已读')
  fetchList()
}

async function handleDelete(item) {
  try {
    await ElMessageBox.confirm('确定删除这条通知吗?', '提示', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  await noticeStore.remove(item.id)
  ElMessage.success('已删除')
  fetchList()
}

function typeInfo(type) {
  return NOTICE_TYPE[type] || { label: '系统消息', type: 'info', icon: 'Bell' }
}

onMounted(() => {
  fetchList()
  noticeStore.fetchUnread()
})
</script>

<template>
  <div class="page-container">
    <div class="notice-wrap">
      <header class="page-head">
        <div>
          <h1 class="page-title">消息通知</h1>
          <p class="page-sub">
            审核结果、订单动态、到期提醒都会在这里
            <span v-if="unreadCount > 0" class="unread-badge">
              {{ unreadCount }} 条未读
            </span>
          </p>
        </div>
        <el-button
          :icon="Check"
          :disabled="unreadCount === 0"
          @click="handleMarkAllRead"
        >
          全部已读
        </el-button>
      </header>

      <el-tabs v-model="query.isRead" class="filter-tabs" @tab-change="handleTabChange">
        <el-tab-pane
          v-for="tab in filterTabs"
          :key="String(tab.value)"
          :name="tab.value"
          :label="tab.label"
        />
      </el-tabs>

      <div v-loading="loading" class="list-wrap">
        <template v-if="notices.length">
          <article
            v-for="item in notices"
            :key="item.id"
            class="notice-item"
            :class="{ 'is-unread': !item.isRead }"
            @click="handleClick(item)"
          >
            <div class="notice-icon" :class="`is-${typeInfo(item.noticeType).type}`">
              <el-icon :size="18">
                <component :is="typeInfo(item.noticeType).icon" />
              </el-icon>
            </div>

            <div class="notice-body">
              <div class="notice-head">
                <span class="notice-title">{{ item.title }}</span>
                <el-tag size="small" effect="plain" class="type-tag">
                  {{ item.noticeTypeName }}
                </el-tag>
                <span v-if="!item.isRead" class="dot-unread" />
              </div>
              <p class="notice-content">{{ item.content }}</p>
              <div class="notice-foot">
                <span class="num time">{{ formatDateTime(item.createTime) }}</span>
                <span v-if="item.linkPath" class="link-hint">
                  点击查看详情 →
                </span>
              </div>
            </div>

            <el-icon class="delete-btn" @click.stop="handleDelete(item)"><Close /></el-icon>
          </article>

          <el-pagination
            v-if="total > query.pageSize"
            v-model:current-page="query.pageNum"
            :page-size="query.pageSize"
            :total="total"
            layout="prev, pager, next"
            class="pagination"
            @current-change="fetchList"
          />
        </template>

        <el-empty
          v-else-if="!loading"
          :description="query.isRead === 0 ? '没有未读消息' : '暂时没有消息'"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.notice-wrap {
  max-width: 800px;
  margin: 0 auto;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
  flex-wrap: wrap;
}

.page-title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-sub {
  margin-top: var(--space-xs);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.unread-badge {
  display: inline-block;
  margin-left: var(--space-sm);
  padding: 1px 8px;
  border-radius: var(--radius-full);
  background: var(--color-danger-light);
  color: var(--color-danger);
  font-size: var(--font-size-xs);
}

.filter-tabs :deep(.el-tabs__header) {
  margin-bottom: var(--space-md);
}

.list-wrap {
  min-height: 300px;
}

/* ── 通知条目 ────────────────────────────────────────────── */
.notice-item {
  position: relative;
  display: flex;
  gap: var(--space-md);
  padding: var(--space-md) var(--space-lg);
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-sm);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-out);
}

.notice-item:hover {
  border-color: var(--color-primary-border);
  box-shadow: var(--shadow-sm);
}

/* 未读的左边加一条色条 —— 比整块染色克制,又能一眼看出来 */
.notice-item.is-unread {
  border-left: 3px solid var(--color-primary);
}

.notice-icon {
  width: 38px;
  height: 38px;
  border-radius: var(--radius-full);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.notice-icon.is-primary { background: var(--color-primary-light); color: var(--color-primary); }
.notice-icon.is-success { background: var(--color-success-light); color: var(--color-success); }
.notice-icon.is-warning { background: var(--color-warning-light); color: var(--color-warning); }
.notice-icon.is-danger  { background: var(--color-danger-light);  color: var(--color-danger); }
.notice-icon.is-info    { background: var(--color-info-light);    color: var(--color-info); }

.notice-body {
  flex: 1;
  min-width: 0;
}

.notice-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.notice-title {
  font-weight: var(--font-weight-bold);
  font-size: var(--font-size-base);
}

.notice-item:not(.is-unread) .notice-title {
  font-weight: var(--font-weight-medium);
  color: var(--color-text-regular);
}

.dot-unread {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-danger);
}

.type-tag {
  font-size: 11px;
}

.notice-content {
  margin-top: 6px;
  font-size: var(--font-size-sm);
  color: var(--color-text-regular);
  line-height: 1.7;
}

.notice-foot {
  margin-top: var(--space-sm);
  display: flex;
  align-items: center;
  gap: var(--space-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-placeholder);
}

.link-hint {
  color: var(--color-primary);
}

.delete-btn {
  position: absolute;
  top: 10px;
  right: 12px;
  color: var(--color-text-disabled);
  opacity: 0;
  transition: all var(--duration-fast);
}

.notice-item:hover .delete-btn {
  opacity: 1;
}

.delete-btn:hover {
  color: var(--color-danger);
}

.pagination {
  margin-top: var(--space-lg);
  justify-content: center;
}
</style>
