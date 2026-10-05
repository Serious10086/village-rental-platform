<script setup>
/**
 * 前台首页。
 *
 * 结构:搜索区 → 分类入口 → 最新上架 → 平台说明。
 *
 * 首页刻意不放"全部资源"——那是列表页的职责。这里只放最新 8 条,
 * 目的是让访客快速判断"这个平台上有我想要的东西吗",
 * 想细看自然会去列表页筛选。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getCategoryTree, searchResources } from '@/api/resource'
import { getRecommendations } from '@/api/recommend'
import ResourceCard from '@/components/ResourceCard.vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const keyword = ref('')
const categories = ref([])
const latest = ref([])
const recommended = ref([])
const loading = ref(true)

/**
 * 推荐位的标题。
 *
 * 登录用户才叫「猜你喜欢」—— 游客没有任何行为可供个性化,
 * 给他的就是全平台热门,标题也照实写。这不是谦虚:
 * 标题写着"猜你喜欢"而内容其实是排行榜,用户点两次就发现是糊弄。
 */
const recommendTitle = computed(() =>
  userStore.isLoggedIn ? '猜你喜欢' : '热门推荐'
)

/**
 * 推荐位的副标题。
 *
 * 同样要跟着登录态变 —— 对游客说"根据浏览和收藏记录推荐"是**撒谎**:
 * 他没有任何记录,给他的就是全平台热门。标题和副标题一起改,
 * 是为了别出现"标题诚实、副标题吹牛"这种半吊子说法。
 */
const recommendSub = computed(() =>
  userStore.isLoggedIn ? '根据你最近浏览和收藏的类别推荐' : '全平台热度较高的资源'
)

/** 首页统计:用真实数据,不写死。数字能建立信任感 */
const stats = ref({ total: 0, rent: 0, sell: 0 })

async function loadData() {
  loading.value = true
  try {
    // 三个请求互相独立,并发发出去 —— 串行等待会让首屏明显变慢
    const [cats, latestRes, rentRes, sellRes] = await Promise.all([
      getCategoryTree(),
      searchResources({ sortBy: 'newest', pageNum: 1, pageSize: 8 }),
      searchResources({ tradeType: 1, pageNum: 1, pageSize: 1 }),
      searchResources({ tradeType: 2, pageNum: 1, pageSize: 1 })
    ])

    categories.value = cats || []
    latest.value = latestRes.rows || []
    stats.value = {
      total: latestRes.total || 0,
      rent: rentRes.total || 0,
      sell: sellRes.total || 0
    }
  } catch (e) {
    console.warn('首页数据加载失败', e)
  } finally {
    loading.value = false
  }

  // 推荐单独取、单独兜底:它挂了首页照样能用,只是推荐位不显示。
  // 放在 Promise.all 里面的话,推荐一失败整个首页的统计数字也跟着没了
  try {
    recommended.value = (await getRecommendations(8)) || []
  } catch (e) {
    console.warn('推荐加载失败,首页继续显示', e)
  }
}

function handleSearch() {
  router.push({ path: '/resource', query: keyword.value ? { keyword: keyword.value } : {} })
}

function goCategory(id) {
  router.push({ path: '/resource', query: { categoryId: id } })
}

function goTrade(type) {
  router.push({ path: '/resource', query: { tradeType: type } })
}

onMounted(loadData)
</script>

<template>
  <div class="home">
    <!-- ── 搜索区 ──────────────────────────────────────────── -->
    <section class="hero">
      <div class="page-container hero-inner">
        <h1 class="hero-title">让村里的闲置资源流转起来</h1>
        <p class="hero-sub">
          农机、场地、工具 —— 你家闲置的,正是别人急需的
        </p>

        <div class="hero-search">
          <el-input
            v-model="keyword"
            placeholder="搜一搜:拖拉机、仓库、水泵……"
            size="large"
            @keyup.enter="handleSearch"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
            <template #append>
              <el-button type="primary" size="large" :icon="Search" @click="handleSearch">
                搜索
              </el-button>
            </template>
          </el-input>
        </div>

        <!-- 数据统计 -->
        <div class="hero-stats">
          <div class="stat" @click="router.push('/resource')">
            <div class="stat-value num">{{ stats.total }}</div>
            <div class="stat-label">在架资源</div>
          </div>
          <div class="stat-divider" />
          <div class="stat" @click="goTrade(1)">
            <div class="stat-value num">{{ stats.rent }}</div>
            <div class="stat-label">可租资源</div>
          </div>
          <div class="stat-divider" />
          <div class="stat" @click="goTrade(2)">
            <div class="stat-value num">{{ stats.sell }}</div>
            <div class="stat-label">在售资源</div>
          </div>
        </div>
      </div>
    </section>

    <div class="page-container main">
      <!-- ── 分类入口 ──────────────────────────────────────── -->
      <section class="section">
        <h2 class="section-title">按分类找</h2>
        <div class="category-grid">
          <div
            v-for="cat in categories"
            :key="cat.id"
            class="category-card"
            @click="goCategory(cat.id)"
          >
            <div class="cat-head">
              <span class="cat-name">{{ cat.categoryName }}</span>
              <el-icon class="cat-arrow"><ArrowRight /></el-icon>
            </div>
            <div class="cat-items">
              <span
                v-for="child in (cat.children || []).slice(0, 4)"
                :key="child.id"
                class="cat-item"
                @click.stop="goCategory(child.id)"
              >{{ child.categoryName }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- ── 猜你喜欢(批次 8) ─────────────────────────────── -->
      <!--
        排在"最新上架"前面,因为"你可能想要的"是用户视角,
        而"最新的"是平台视角。有推荐可给的时候先给推荐。

        每张卡片上面挂一句推荐理由 —— 一排没有理由的推荐是黑箱,
        用户不知道它凭什么出现在那儿。理由由后端算好返回
        (内容是"你看过「农机具」",冷启动是"大家都在看"),
        前端只负责显示,不自己编。
      -->
      <section v-if="recommended.length" class="section">
        <div class="section-head">
          <h2 class="section-title">{{ recommendTitle }}</h2>
          <span class="section-sub">{{ recommendSub }}</span>
        </div>

        <div class="resource-grid">
          <div v-for="item in recommended" :key="item.id" class="rec-item">
            <div class="rec-reason">{{ item.reason }}</div>
            <!-- from="rec" 会写进路由 query,详情页据此知道
                 "这次浏览来自推荐位" —— 后台的点击率统计就靠它 -->
            <ResourceCard :resource="item" from="rec" />
          </div>
        </div>
      </section>

      <!-- ── 最新上架 ──────────────────────────────────────── -->
      <section class="section">
        <div class="section-head">
          <h2 class="section-title">最新上架</h2>
          <el-button link type="primary" @click="router.push('/resource')">
            查看全部 <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>

        <div v-loading="loading" class="latest-wrap">
          <div v-if="latest.length" class="resource-grid">
            <ResourceCard v-for="item in latest" :key="item.id" :resource="item" from="list" />
          </div>
          <el-empty v-else-if="!loading" description="还没有资源上架" />
        </div>
      </section>

      <!-- ── 平台说明 ──────────────────────────────────────── -->
      <section class="section">
        <h2 class="section-title">怎么用</h2>
        <div class="how-grid">
          <div class="how-card">
            <div class="how-step num">1</div>
            <h3>实名注册</h3>
            <p>手机号注册后提交实名认证,由本村管理员审核。认证通过才能发布和下单</p>
          </div>
          <div class="how-card">
            <div class="how-step num">2</div>
            <h3>发布或浏览</h3>
            <p>有闲置资源就发布出来,需要什么就搜一搜。资源经管理员审核后上架</p>
          </div>
          <div class="how-card">
            <div class="how-step num">3</div>
            <h3>在线交易</h3>
            <p>预约或购买,平台内支付。租期、押金、规则都写清楚,免得事后扯皮</p>
          </div>
          <div class="how-card">
            <div class="how-step num">4</div>
            <h3>评价与信用</h3>
            <p>完成后互相评价,信用分随履约记录累积。守信的人下次更容易租到东西</p>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ── 搜索区 ──────────────────────────────────────────────── */
.hero {
  background: linear-gradient(140deg, #1e3a8a 0%, #2563eb 55%, #3b82f6 100%);
  color: #fff;
  padding: var(--space-2xl) 0;
  position: relative;
  overflow: hidden;
}

/* 一层很淡的斜纹肌理,避免大面积纯色单调 */
.hero::after {
  content: '';
  position: absolute;
  inset: 0;
  background-image: repeating-linear-gradient(
    45deg,
    rgba(255, 255, 255, 0.035) 0px,
    rgba(255, 255, 255, 0.035) 1px,
    transparent 1px,
    transparent 14px
  );
  pointer-events: none;
}

.hero-inner {
  position: relative;
  z-index: 1;
  text-align: center;
  max-width: 720px;
}

.hero-title {
  font-size: 34px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.3;
}

.hero-sub {
  margin-top: var(--space-sm);
  font-size: var(--font-size-md);
  color: rgba(255, 255, 255, 0.85);
}

.hero-search {
  margin-top: var(--space-xl);
}

.hero-search :deep(.el-input__wrapper) {
  border-radius: var(--radius-md) 0 0 var(--radius-md);
}

/*
  与资源列表页同样的处理:Element Plus 在 input-group 的 append 槽里
  会把 el-button 的背景和边框去掉,靠容器背景呈现。容器改透明后
  必须把按钮样式显式补回来,否则按钮就只剩一行字。
*/
.hero-search :deep(.el-input-group__append) {
  padding: 0;
  border: none;
  background: transparent;
  box-shadow: none;
}

.hero-search :deep(.el-input-group__append .el-button) {
  margin: 0;
  height: 100%;
  padding: 0 var(--space-xl);
  border-radius: 0 var(--radius-md) var(--radius-md) 0;
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
  box-shadow: none;
}

.hero-search :deep(.el-input-group__append .el-button:hover) {
  background: var(--color-primary-hover);
  border-color: var(--color-primary-hover);
}

/* ── 统计 ────────────────────────────────────────────────── */
.hero-stats {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xl);
  margin-top: var(--space-xl);
}

.stat {
  cursor: pointer;
  transition: transform var(--duration-fast);
}

.stat:hover {
  transform: translateY(-2px);
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.2;
}

.stat-label {
  font-size: var(--font-size-xs);
  color: rgba(255, 255, 255, 0.75);
}

.stat-divider {
  width: 1px;
  height: 32px;
  background: rgba(255, 255, 255, 0.22);
}

/* ── 主体 ────────────────────────────────────────────────── */
.main {
  padding-top: var(--space-xl);
  display: flex;
  flex-direction: column;
  gap: var(--space-2xl);
}

.section-title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--space-md);
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-md);
}

.section-head .section-title {
  margin-bottom: 0;
}

/* ── 分类卡片 ────────────────────────────────────────────── */
.category-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--space-md);
}

.category-card {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-md) var(--space-lg);
  cursor: pointer;
  transition: all var(--duration-normal) var(--ease-out);
  /* 左侧色条区分分类,比整块染色克制 */
  border-left: 3px solid var(--color-primary);
}

.category-card:hover {
  border-color: var(--color-primary);
  border-left-color: var(--color-primary);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}

.cat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.cat-name {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
}

.cat-arrow {
  color: var(--color-text-placeholder);
  transition: transform var(--duration-fast);
}

.category-card:hover .cat-arrow {
  transform: translateX(3px);
  color: var(--color-primary);
}

.cat-items {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: var(--space-sm);
}

.cat-item {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  background: var(--color-info-light);
  padding: 2px 8px;
  border-radius: var(--radius-full);
  transition: all var(--duration-fast);
}

.cat-item:hover {
  background: var(--color-primary-light);
  color: var(--color-primary);
}

/* ── 最新上架 ────────────────────────────────────────────── */
.latest-wrap {
  min-height: 200px;
}

.resource-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: var(--space-md);
}

/* ── 猜你喜欢 ──────────────────────────────────────────────── */
/*
  推荐理由挂在卡片**上面**而不是下面:它是"为什么你会看到这张卡片"的
  铺垫,先看到理由再看卡片才顺。放下面的话,用户已经划过去了才读到解释。
*/
.rec-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.rec-reason {
  font-size: var(--font-size-xs);
  color: var(--color-primary);
  /* 理由只有一行,长了省略 —— 分类名最长也就几个字,不会真的截断 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.section-sub {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* ── 使用说明 ────────────────────────────────────────────── */
.how-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--space-md);
}

.how-card {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-lg);
}

.how-step {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-full);
  background: var(--color-primary-light);
  color: var(--color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--space-sm);
}

.how-card h3 {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--space-xs);
}

.how-card p {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: 1.7;
}

/* ── 响应式 ──────────────────────────────────────────────── */
@media (max-width: 768px) {
  .hero {
    padding: var(--space-xl) 0;
  }

  .hero-title {
    font-size: 24px;
  }

  .hero-stats {
    gap: var(--space-lg);
  }

  .stat-value {
    font-size: 22px;
  }
}
</style>
