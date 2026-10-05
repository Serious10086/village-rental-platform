<script setup>
/**
 * 前台 —— 公告列表。
 *
 * 【排版为什么是"公告栏"而不是"卡片网格"】
 * 公告是一份份公文,读者是来"看通知"的:先扫标题,再决定点哪条。
 * 卡片网格适合浏览商品(每张卡片都有图,靠图吸引注意力),
 * 但公告没有图、标题才是唯一线索 —— 排成一行行,扫视效率最高。
 * 列表左侧那道色条按公告类型着色,是为了在几十条标题里
 * 一眼分出"哪条是政策、哪条是活动"。
 *
 * 【免登录】
 * 这条路在白名单里。访客先看到这个村在做什么,而不是一个登录框。
 * 未登录时只看得到全平台公告,登录后会多出本村的那部分 ——
 * 这个差别不在前端做分支,而是后端按 token 里的村组 id 自然筛出来的。
 */
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { View } from '@element-plus/icons-vue'
import { getAnnouncements } from '@/api/announcement'
import { ANNOUNCEMENT_TYPE } from '@/utils/interactDict'
import { formatDateTime } from '@/utils/format'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({ pageNum: 1, pageSize: 10, noticeType: null })

const typeOptions = Object.entries(ANNOUNCEMENT_TYPE).map(([v, o]) => ({
  value: Number(v), label: o.label
}))

async function fetchList() {
  loading.value = true
  try {
    const data = await getAnnouncements({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      noticeType: query.noticeType ?? undefined
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

function handleFilter() {
  query.pageNum = 1
  fetchList()
}

function openDetail(row) {
  router.push(`/announcement/${row.id}`)
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container notice-page">
    <header class="page-head">
      <div>
        <h1 class="page-title">村务公告</h1>
        <p class="page-sub">
          补贴政策、安全须知、节庆活动 —— 村里的事都在这儿
          <template v-if="!userStore.isLoggedIn">
            (未登录只显示全平台公告,登录后可看到本村公告)
          </template>
        </p>
      </div>

      <el-select
        v-model="query.noticeType"
        placeholder="全部类型"
        clearable
        style="width: 170px"
        @change="handleFilter"
      >
        <el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
    </header>

    <div v-loading="loading" class="card list-card">
      <el-empty v-if="!loading && !rows.length" description="暂时还没有公告" />

      <ul class="notice-list">
        <li
          v-for="row in rows"
          :key="row.id"
          class="notice-item"
          :style="{ borderLeftColor: ANNOUNCEMENT_TYPE[row.noticeType]?.color }"
          @click="openDetail(row)"
        >
          <div class="item-main">
            <h2 class="item-title">
              <el-tag v-if="row.isTop === 1" type="danger" size="small" effect="dark" class="top-tag">
                置顶
              </el-tag>
              {{ row.title }}
            </h2>

            <div class="item-meta">
              <el-tag
                size="small"
                effect="plain"
                :type="ANNOUNCEMENT_TYPE[row.noticeType]?.type"
              >
                {{ ANNOUNCEMENT_TYPE[row.noticeType]?.label || '公告' }}
              </el-tag>
              <!-- 没有 villageId 就是全平台公告 —— 这个区分对读者有意义:
                   全平台公告是平台发的,村级公告是村干部发的 -->
              <el-tag v-if="!row.villageId" size="small" effect="plain" type="info">
                全平台
              </el-tag>
              <span class="meta-text">{{ row.publisherName }}</span>
              <span class="meta-text">{{ formatDateTime(row.publishTime || row.createTime) }}</span>
            </div>
          </div>

          <div class="item-side">
            <el-icon><View /></el-icon>
            <span class="num">{{ row.viewCount || 0 }}</span>
          </div>
        </li>
      </ul>

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
  </div>
</template>

<style scoped>
.notice-page {
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

.list-card {
  padding: var(--space-sm) var(--space-lg) var(--space-md);
}

.notice-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

/* 左侧色条:类型靠颜色区分,扫一眼就知道哪条跟自己有关 */
.notice-item {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-md) var(--space-md) var(--space-md) var(--space-lg);
  border-left: 4px solid var(--color-border);
  border-bottom: 1px solid var(--color-border-light);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out),
              transform var(--duration-fast) var(--ease-out);
}

.notice-item:last-child {
  border-bottom: none;
}

.notice-item:hover {
  background: var(--color-primary-light);
  /* 悬停时向右挪 2px:比单纯变色更能说明"这一整行都能点" */
  transform: translateX(2px);
}

.item-main {
  flex: 1;
  min-width: 0;
}

.item-title {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  line-height: 1.5;
}

.top-tag {
  flex-shrink: 0;
}

.item-meta {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-top: 6px;
  flex-wrap: wrap;
}

.meta-text {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.item-side {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  color: var(--color-text-placeholder);
  font-size: var(--font-size-sm);
}

.num {
  font-family: var(--font-family-number);
}

.pager {
  margin-top: var(--space-md);
  justify-content: flex-end;
}

@media (max-width: 700px) {
  .page-head {
    flex-direction: column;
    align-items: stretch;
  }

  .item-side {
    display: none;
  }
}
</style>
