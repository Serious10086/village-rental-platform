<script setup>
/**
 * 资源列表 / 搜索页。
 *
 * 【筛选状态放在 URL 里】
 * 所有筛选条件都同步到 query string,而不是只存在组件内部。
 * 这样有三个好处:
 *   1. 筛选结果可以直接分享给别人(发个链接就是同一批资源)
 *   2. 浏览器前进后退能正确回到上一个筛选状态
 *   3. 刷新页面不丢筛选条件 —— 用 ref 存的话刷新就清空了
 */
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getCategoryTree, searchResources } from '@/api/resource'
import ResourceCard from '@/components/ResourceCard.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const categories = ref([])

/** 从 URL 初始化筛选条件 —— 这是唯一的数据来源 */
const query = reactive({
  keyword: route.query.keyword || '',
  categoryId: route.query.categoryId ? Number(route.query.categoryId) : null,
  tradeType: route.query.tradeType ? Number(route.query.tradeType) : null,
  minPrice: route.query.minPrice ? Number(route.query.minPrice) : null,
  maxPrice: route.query.maxPrice ? Number(route.query.maxPrice) : null,
  availableStartDate: route.query.availableStartDate || null,
  availableEndDate: route.query.availableEndDate || null,
  villageId: route.query.villageId ? Number(route.query.villageId) : null,
  sortBy: route.query.sortBy || 'newest',
  pageNum: route.query.pageNum ? Number(route.query.pageNum) : 1,
  pageSize: 12
})

const dateRange = ref(
  query.availableStartDate && query.availableEndDate
    ? [query.availableStartDate, query.availableEndDate]
    : null
)

/** 已选条件的数量,用于"清空筛选"按钮的显隐 */
const activeFilterCount = computed(() =>
  [query.categoryId, query.tradeType, query.minPrice, query.maxPrice,
   query.availableStartDate, query.villageId].filter((v) => v != null).length
)

/** 价格快捷区间 —— 比让用户手填两个输入框快得多 */
const priceRanges = [
  { label: '100 以下', min: null, max: 100 },
  { label: '100 - 300', min: 100, max: 300 },
  { label: '300 - 1000', min: 300, max: 1000 },
  { label: '1000 以上', min: 1000, max: null }
]

const sortOptions = [
  { label: '最新发布', value: 'newest' },
  { label: '价格从低到高', value: 'priceAsc' },
  { label: '价格从高到低', value: 'priceDesc' },
  { label: '最多人看', value: 'popular' }
]

/** 当前选中的价格区间(用于高亮按钮) */
const activePriceRange = computed(() =>
  priceRanges.findIndex((r) => r.min === query.minPrice && r.max === query.maxPrice)
)

/** 扁平化的分类选项(一级不可选,它是分组) */
const categoryOptions = computed(() =>
  categories.value.flatMap((parent) =>
    (parent.children || []).map((c) => ({
      id: c.id,
      name: c.categoryName,
      parentName: parent.categoryName
    }))
  )
)

// ── 数据加载 ──────────────────────────────────────────────────

async function fetchList() {
  loading.value = true
  try {
    const data = await searchResources({
      keyword: query.keyword || undefined,
      categoryId: query.categoryId || undefined,
      tradeType: query.tradeType || undefined,
      minPrice: query.minPrice ?? undefined,
      maxPrice: query.maxPrice ?? undefined,
      availableStartDate: query.availableStartDate || undefined,
      availableEndDate: query.availableEndDate || undefined,
      villageIds: query.villageId ? [query.villageId] : undefined,
      sortBy: query.sortBy,
      pageNum: query.pageNum,
      pageSize: query.pageSize
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

/**
 * 把筛选条件同步到 URL,然后重新查询。
 *
 * 用 replace 而不是 push:用户连续调筛选时不该在历史记录里堆一串中间状态,
 * 否则点一次"后退"要退好几步才回到上一页。
 */
function applyFilters(resetPage = true) {
  if (resetPage) query.pageNum = 1

  const q = {}
  if (query.keyword) q.keyword = query.keyword
  if (query.categoryId) q.categoryId = query.categoryId
  if (query.tradeType) q.tradeType = query.tradeType
  if (query.minPrice != null) q.minPrice = query.minPrice
  if (query.maxPrice != null) q.maxPrice = query.maxPrice
  if (query.availableStartDate) q.availableStartDate = query.availableStartDate
  if (query.availableEndDate) q.availableEndDate = query.availableEndDate
  if (query.villageId) q.villageId = query.villageId
  if (query.sortBy && query.sortBy !== 'newest') q.sortBy = query.sortBy
  if (query.pageNum > 1) q.pageNum = query.pageNum

  router.replace({ path: '/resource', query: q })
}

function selectPriceRange(range, index) {
  if (activePriceRange.value === index) {
    // 再点一次取消选择
    query.minPrice = null
    query.maxPrice = null
  } else {
    query.minPrice = range.min
    query.maxPrice = range.max
  }
  applyFilters()
}

function handleDateChange(val) {
  query.availableStartDate = val ? val[0] : null
  query.availableEndDate = val ? val[1] : null
  applyFilters()
}

function resetFilters() {
  query.keyword = ''
  query.categoryId = null
  query.tradeType = null
  query.minPrice = null
  query.maxPrice = null
  query.availableStartDate = null
  query.availableEndDate = null
  query.villageId = null
  dateRange.value = null
  query.sortBy = 'newest'
  applyFilters()
}

function handlePageChange(page) {
  query.pageNum = page
  applyFilters(false)
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

// 监听 URL 变化(比如用户点了浏览器后退),重新初始化并查询
watch(
  () => route.query,
  (newQuery) => {
    if (route.path !== '/resource') return
    query.keyword = newQuery.keyword || ''
    query.categoryId = newQuery.categoryId ? Number(newQuery.categoryId) : null
    query.tradeType = newQuery.tradeType ? Number(newQuery.tradeType) : null
    query.minPrice = newQuery.minPrice ? Number(newQuery.minPrice) : null
    query.maxPrice = newQuery.maxPrice ? Number(newQuery.maxPrice) : null
    query.availableStartDate = newQuery.availableStartDate || null
    query.availableEndDate = newQuery.availableEndDate || null
    query.villageId = newQuery.villageId ? Number(newQuery.villageId) : null
    query.sortBy = newQuery.sortBy || 'newest'
    query.pageNum = newQuery.pageNum ? Number(newQuery.pageNum) : 1
    dateRange.value = query.availableStartDate && query.availableEndDate
      ? [query.availableStartDate, query.availableEndDate]
      : null
    fetchList()
  }
)

onMounted(async () => {
  try {
    categories.value = await getCategoryTree()
  } catch (e) {
    console.warn('分类加载失败', e)
  }
  fetchList()
})
</script>

<template>
  <div class="page-container">
    <!-- ── 搜索栏 ──────────────────────────────────────────── -->
    <div class="search-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索农机、场地、工具……(支持搜描述和标签里的词)"
        size="large"
        clearable
        @keyup.enter="applyFilters()"
        @clear="applyFilters()"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
        <template #append>
          <el-button type="primary" :icon="Search" @click="applyFilters()">搜索</el-button>
        </template>
      </el-input>
    </div>

    <!-- ── 筛选区 ──────────────────────────────────────────── -->
    <div class="filter-panel">
      <!-- 交易方式:两个大按钮,是最常用的筛选 -->
      <div class="filter-row">
        <span class="filter-label">交易方式</span>
        <div class="filter-options">
          <button
            class="chip"
            :class="{ 'is-active': query.tradeType === null }"
            @click="query.tradeType = null; applyFilters()"
          >不限</button>
          <button
            class="chip"
            :class="{ 'is-active': query.tradeType === 1 }"
            @click="query.tradeType = 1; applyFilters()"
          >
            <el-icon :size="13"><Van /></el-icon> 出租
          </button>
          <button
            class="chip"
            :class="{ 'is-active': query.tradeType === 2 }"
            @click="query.tradeType = 2; applyFilters()"
          >
            <el-icon :size="13"><ShoppingCart /></el-icon> 出售
          </button>
        </div>
      </div>

      <!-- 分类 -->
      <div class="filter-row">
        <span class="filter-label">资源分类</span>
        <div class="filter-options">
          <button
            class="chip"
            :class="{ 'is-active': query.categoryId === null }"
            @click="query.categoryId = null; applyFilters()"
          >不限</button>
          <button
            v-for="opt in categoryOptions"
            :key="opt.id"
            class="chip"
            :class="{ 'is-active': query.categoryId === opt.id }"
            @click="query.categoryId = opt.id; applyFilters()"
          >{{ opt.name }}</button>
        </div>
      </div>

      <!-- 价格 -->
      <div class="filter-row">
        <span class="filter-label">价格区间</span>
        <div class="filter-options">
          <button
            v-for="(range, idx) in priceRanges"
            :key="idx"
            class="chip"
            :class="{ 'is-active': activePriceRange === idx }"
            @click="selectPriceRange(range, idx)"
          >{{ range.label }}</button>
        </div>
      </div>

      <!-- 可用日期:只在看出租类时有意义 -->
      <div class="filter-row">
        <span class="filter-label">可用日期</span>
        <div class="filter-options">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            size="default"
            @change="handleDateChange"
          />
          <span class="filter-hint">筛出这段时间内可租的资源</span>
        </div>
      </div>
    </div>

    <!-- ── 结果区 ──────────────────────────────────────────── -->
    <div class="result-bar">
      <div class="result-count">
        共找到 <b class="num">{{ total }}</b> 个资源
        <el-button
          v-if="activeFilterCount > 0"
          link
          type="primary"
          size="small"
          @click="resetFilters"
        >清空筛选({{ activeFilterCount }})</el-button>
      </div>

      <el-radio-group v-model="query.sortBy" size="small" @change="applyFilters()">
        <el-radio-button v-for="s in sortOptions" :key="s.value" :value="s.value">
          {{ s.label }}
        </el-radio-button>
      </el-radio-group>
    </div>

    <!-- ── 列表 ────────────────────────────────────────────── -->
    <div v-loading="loading" class="list-wrap">
      <div v-if="rows.length" class="resource-grid">
        <ResourceCard v-for="item in rows" :key="item.id" :resource="item" />
      </div>

      <el-empty
        v-else-if="!loading"
        description="没有找到符合条件的资源,换个条件试试"
      />

      <el-pagination
        v-if="total > query.pageSize"
        :current-page="query.pageNum"
        :page-size="query.pageSize"
        :total="total"
        layout="prev, pager, next, jumper"
        class="pagination"
        @current-change="handlePageChange"
      />
    </div>
  </div>
</template>

<style scoped>
/* ── 搜索栏 ──────────────────────────────────────────────── */
.search-bar {
  margin-bottom: var(--space-lg);
}

/*
  让"搜索"按钮正确地贴在输入框右侧。

  【踩过的坑】只写 `padding: 0; background: transparent` 会让按钮变透明 ——
  Element Plus 在 input-group 的 append 槽里默认会把 el-button 的背景和边框
  都去掉,靠 append 容器的背景来呈现。把容器也改透明之后,按钮就只剩一行字了。
  所以这里必须把按钮的样式显式补回来。
*/
.search-bar :deep(.el-input-group__append) {
  padding: 0;
  border: none;
  background: transparent;
  box-shadow: none;
}

.search-bar :deep(.el-input-group__append .el-button) {
  margin: 0;
  height: 100%;
  padding: 0 var(--space-lg);
  border-radius: 0 var(--radius-md) var(--radius-md) 0;
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
  box-shadow: none;
}

.search-bar :deep(.el-input-group__append .el-button:hover) {
  background: var(--color-primary-hover);
  border-color: var(--color-primary-hover);
}

/* ── 筛选面板 ────────────────────────────────────────────── */
.filter-panel {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-md) var(--space-lg);
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  margin-bottom: var(--space-md);
}

.filter-row {
  display: flex;
  gap: var(--space-md);
  align-items: flex-start;
}

.filter-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  width: 64px;
  flex-shrink: 0;
  padding-top: 6px;
}

.filter-options {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  align-items: center;
}

.filter-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-placeholder);
}

/* 筛选用的胶囊按钮 —— 比 el-radio-group 更紧凑,一屏能放下更多选项 */
.chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 5px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-full);
  background: var(--color-bg-card);
  color: var(--color-text-regular);
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-out);
  font-family: inherit;
}

.chip:hover {
  border-color: var(--color-primary-border);
  color: var(--color-primary);
}

.chip.is-active {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
  font-weight: var(--font-weight-medium);
}

/* ── 结果栏 ──────────────────────────────────────────────── */
.result-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-md);
  flex-wrap: wrap;
}

.result-count {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.result-count b {
  color: var(--color-primary);
  font-size: var(--font-size-md);
}

/* ── 列表 ────────────────────────────────────────────────── */
.list-wrap {
  min-height: 320px;
}

/* 自适应列数:窄屏 1 列,平板 2 列,桌面 3-4 列 */
.resource-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: var(--space-md);
}

.pagination {
  margin-top: var(--space-xl);
  justify-content: center;
}

/* ── 响应式 ──────────────────────────────────────────────── */
@media (max-width: 768px) {
  .filter-row {
    flex-direction: column;
    gap: var(--space-sm);
  }

  .filter-label {
    width: auto;
    padding-top: 0;
  }

  .result-bar {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
