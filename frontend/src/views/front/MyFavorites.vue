<script setup>
/**
 * 前台 —— 我的收藏(批次 8)。
 *
 * 【这一页最要紧的一件事:已下架的收藏不能消失】
 * 收藏列表直接查 biz_resource 而不是视图 v_resource_list,后者写死了
 * "只看已上架"。沿用视图的话,收藏过的资源一旦下架、售出或被驳回,
 * 就会从列表里静默消失 —— 用户只会觉得"平台把我的收藏弄丢了",
 * 而且他连"它还在,只是不能租了"这个信息都拿不到。
 *
 * 所以每条都带资源当前状态,下架的显示成灰色卡片 + 「已下架」标签。
 * 取消收藏的入口也保留着:那些已经下架的东西,用户更需要能把它清掉。
 */
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, StarFilled } from '@element-plus/icons-vue'
import { getMyFavorites, toggleFavorite } from '@/api/favorite'
import { RESOURCE_STATUS, TRADE_TYPE, formatPrice } from '@/utils/resourceDict'
import { formatDateTime } from '@/utils/format'

const router = useRouter()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 12,
  keyword: ''
})

async function fetchList() {
  loading.value = true
  try {
    const data = await getMyFavorites({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined
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
  handleSearch()
}

function goDetail(row) {
  router.push({ path: `/resource/${row.id}` })
}

/** 资源还挂着的时候才谈得上"能不能租",下架的只显示状态 */
function isAvailable(row) {
  return row.resourceStatus === 1
}

async function handleRemove(row) {
  try {
    await ElMessageBox.confirm(
      `「${row.resourceName}」会从收藏列表里移除。${
        isAvailable(row) ? '资源本身不受影响。' : '(这个资源已经下架,移除后列表里就看不到它了)'
      }`,
      '取消收藏',
      { confirmButtonText: '取消收藏', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await toggleFavorite(row.id)
    ElMessage.success('已取消收藏')
    await fetchList()
  } catch (e) {
    // 提示由 request.js 统一弹
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container favorites-page">
    <header class="page-head">
      <div>
        <h1 class="page-title">我的收藏</h1>
        <p class="page-sub">
          收藏是把"还想再看看"的东西记下来 —— 资源下架之后它仍然留在这儿,可以随时清掉
        </p>
      </div>
    </header>

    <div class="card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索收藏的资源"
        clearable
        style="width: 220px"
        @keyup.enter="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>
      <div class="spacer" />
      <span class="count text-secondary text-xs">共 {{ total }} 条收藏</span>
    </div>

    <div v-loading="loading" class="fav-wrap">
      <div v-if="rows.length" class="fav-grid">
        <article
          v-for="row in rows"
          :key="row.id"
          class="fav-card card"
          :class="{ 'is-offline': !isAvailable(row) }"
        >
          <div class="cover" @click="goDetail(row)">
            <img v-if="row.coverUrl" :src="row.coverUrl" :alt="row.resourceName" loading="lazy" />
            <div v-else class="cover-placeholder">
              {{ (row.resourceName || '?').charAt(0) }}
            </div>
            <!-- 下架的盖一层遮罩 —— 比只改字色更直观 -->
            <div v-if="!isAvailable(row)" class="offline-mask">
              {{ RESOURCE_STATUS[row.resourceStatus]?.label || '已下架' }}
            </div>
          </div>

          <div class="body">
            <h3 class="name" :title="row.resourceName" @click="goDetail(row)">
              {{ row.resourceName }}
            </h3>
            <div class="meta">
              <span class="price num">{{ formatPrice(row) }}</span>
              <span class="trade">{{ TRADE_TYPE[row.tradeType]?.label }}</span>
            </div>
            <div class="sub text-xs text-secondary">
              <span>{{ row.categoryName || '未分类' }}</span>
              <span v-if="row.villageName">· {{ row.villageName }}</span>
            </div>
            <div class="foot">
              <span class="fav-time text-xs text-secondary">
                收藏于 {{ formatDateTime(row.favoriteTime) }}
              </span>
              <el-button
                link
                type="danger"
                size="small"
                :icon="StarFilled"
                @click="handleRemove(row)"
              >
                取消收藏
              </el-button>
            </div>
          </div>
        </article>
      </div>

      <el-empty
        v-else-if="!loading"
        description="还没有收藏任何资源"
      >
        <el-button type="primary" @click="router.push('/resource')">去逛逛</el-button>
      </el-empty>
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
.favorites-page {
  padding-bottom: var(--space-xl);
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-md) var(--space-lg);
  margin-bottom: var(--space-md);
}

.spacer {
  flex: 1;
}

.fav-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: var(--space-md);
}

.fav-card {
  overflow: hidden;
  display: flex;
  flex-direction: column;
  transition: box-shadow var(--duration-fast);
}

.fav-card:hover {
  box-shadow: var(--shadow-md);
}

/* 下架的整张卡片降饱和 —— 一眼能看出"这个不能租了",
   但它仍然在列表里,而不是被藏掉 */
.fav-card.is-offline {
  opacity: 0.68;
}

.cover {
  position: relative;
  aspect-ratio: 4 / 3;
  background: var(--color-info-light);
  cursor: pointer;
}

.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  color: var(--color-text-disabled);
}

.offline-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(15, 23, 42, 0.55);
  color: #fff;
  font-size: var(--font-size-sm);
  letter-spacing: 1px;
}

.body {
  padding: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
}

.name {
  margin: 0;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  cursor: pointer;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.name:hover {
  color: var(--color-primary);
}

.meta {
  display: flex;
  align-items: baseline;
  gap: var(--space-sm);
}

.price {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
  color: var(--color-danger);
}

.trade {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.sub {
  display: flex;
  gap: 4px;
}

.foot {
  margin-top: auto;
  padding-top: var(--space-sm);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  border-top: 1px solid var(--color-border-light);
}

.pager {
  margin-top: var(--space-lg);
  justify-content: flex-end;
}
</style>
