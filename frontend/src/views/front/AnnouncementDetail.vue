<script setup>
/**
 * 前台 —— 公告详情。
 *
 * 【正文为什么不用 v-html 渲染】
 * 数据库里这一列是 LONGTEXT(建表注释写的是"富文本"),但这一版
 * 后台发布用的是普通 textarea,内容是纯文本 + 换行。所以这里
 * 用 white-space: pre-wrap 原样渲染,<b>不</b>走 v-html。
 *
 * 理由有二,第二条更要紧:
 *   1. v-html 会引入 XSS 面。哪怕发布者只有管理员,管理员账号被盗
 *      或被内部人滥用时,存进去的 <script> 会打到每一个来访的村民脸上。
 *      "信任发布者"是这类漏洞最常见的借口,而它每次都错。
 *   2. 要安全地支持富文本,得在后端引入 HTML 白名单净化(jsoup 之类)
 *      并对发布端做内容校验 —— 为了一段实际以段落文字为主的公告,
 *      多引一个依赖、多一层可能出错的过滤,收益不成立。
 *
 * 后端 snippet() 里那段去 HTML 标签的逻辑因此成了一个安全网:
 * 万一将来真换了富文本编辑器,通知摘要也不会把标签带出去。
 *
 * 【浏览量在这里 +1】
 * 打开详情才计数,列表页不算 —— 否则翻一页就刷高几十次浏览量,
 * 那个数字立刻失去意义。
 */
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, View } from '@element-plus/icons-vue'
import { getAnnouncementDetail } from '@/api/announcement'
import { ANNOUNCEMENT_TYPE } from '@/utils/interactDict'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const data = ref(null)

const typeMeta = computed(() => ANNOUNCEMENT_TYPE[data.value?.noticeType] || {})

async function fetchDetail() {
  loading.value = true
  try {
    data.value = await getAnnouncementDetail(route.params.id)
  } catch (e) {
    data.value = null
  } finally {
    loading.value = false
  }
}

/**
 * 返回。
 *
 * 用 router.back() 而不是 push('/announcement'):用户可能是从站内通知
 * 直接点进来的,这时"返回"应该回他原来的地方,而不是被硬塞到公告列表。
 * 但直接开的新标签页里没有上一页,back() 会走出站点 —— 所以加个兜底判断。
 */
function goBack() {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/announcement')
  }
}

onMounted(fetchDetail)
</script>

<template>
  <div class="page-container detail-page">
    <el-button link :icon="ArrowLeft" class="back" @click="goBack">返回</el-button>

    <div v-loading="loading" class="card article">
      <template v-if="data">
        <div class="article-head" :style="{ borderTopColor: typeMeta.color }">
          <div class="tag-row">
            <el-tag size="small" effect="dark" :type="typeMeta.type">
              {{ typeMeta.label || '公告' }}
            </el-tag>
            <el-tag v-if="data.isTop === 1" size="small" type="danger" effect="plain">
              置顶
            </el-tag>
            <el-tag v-if="!data.villageId" size="small" type="info" effect="plain">
              全平台
            </el-tag>
            <el-tag v-else size="small" effect="plain">{{ data.villageName }}</el-tag>
          </div>

          <h1 class="article-title">{{ data.title }}</h1>

          <div class="article-meta">
            <span>{{ data.publisherName }}</span>
            <span class="dot">·</span>
            <span>{{ formatDateTime(data.publishTime || data.createTime) }}</span>
            <span class="dot">·</span>
            <span class="views">
              <el-icon><View /></el-icon>
              {{ data.viewCount || 0 }} 次浏览
            </span>
          </div>
        </div>

        <el-image
          v-if="data.coverUrl"
          :src="data.coverUrl"
          fit="cover"
          class="cover"
          :preview-src-list="[data.coverUrl]"
          preview-teleported
        />

        <!-- 纯文本渲染:pre-wrap 保住换行,不解释 HTML(见文件头注释) -->
        <div class="article-body">{{ data.content }}</div>
      </template>

      <el-empty v-else-if="!loading" description="公告不存在或已下架" />
    </div>
  </div>
</template>

<style scoped>
.detail-page {
  padding-bottom: var(--space-2xl);
}

.back {
  margin-bottom: var(--space-sm);
}

.article {
  max-width: 820px;
  margin: 0 auto;
  padding: var(--space-xl) var(--space-2xl);
  /* 顶部一道类型色,和列表页的色条呼应起来 —— 点进来之后
     还能看出这条公告属于哪一类 */
  border-top: 4px solid var(--color-border);
}

.article-head {
  padding-bottom: var(--space-md);
  border-bottom: 1px solid var(--color-border-light);
}

.tag-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.article-title {
  margin-top: var(--space-md);
  font-size: var(--font-size-2xl);
  font-weight: var(--font-weight-bold);
  line-height: 1.35;
  letter-spacing: -0.02em;
  color: var(--color-text-primary);
}

.article-meta {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-top: var(--space-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.dot {
  color: var(--color-text-disabled);
}

.views {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.cover {
  display: block;
  width: 100%;
  max-height: 340px;
  margin-top: var(--space-lg);
  border-radius: var(--radius-md);
}

/* 行高 1.9 比正文默认的 1.6 宽松:公告是要逐行读的公文,
   宽松一点能显著减少串行 */
.article-body {
  margin-top: var(--space-lg);
  font-size: var(--font-size-md);
  line-height: 1.9;
  color: var(--color-text-regular);
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 700px) {
  .article {
    padding: var(--space-lg) var(--space-md);
  }

  .article-title {
    font-size: var(--font-size-xl);
  }
}
</style>
