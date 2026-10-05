<script setup>
/**
 * 评价列表 —— 资源详情页和"我的信用"页共用。
 *
 * 抽成组件是因为两个地方的展示需求完全一样(星级、内容、图片、
 * 卖家回复),而它们的取数接口不同 —— 组件只负责渲染,
 * 数据从哪来由调用方决定。
 */
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { replyEvaluation } from '@/api/evaluation'
import { formatDateTime } from '@/utils/format'

const props = defineProps({
  /** 评价数组 */
  evaluations: { type: Array, default: () => [] },
  /** 空状态文案 */
  emptyText: { type: String, default: '还没有评价' }
})
const emit = defineEmits(['refresh'])

const replyingId = ref(null)
const replyText = ref('')

function startReply(item) {
  replyingId.value = item.id
  replyText.value = ''
}

async function submitReply(item) {
  const text = replyText.value.trim()
  if (!text) {
    ElMessage.warning('请填写回复内容')
    return
  }
  try {
    await replyEvaluation(item.id, text)
    ElMessage.success('回复成功')
    replyingId.value = null
    emit('refresh')
  } catch (e) {
    // 拦截器已提示
  }
}

function starText(score) {
  return ['很差', '不满意', '一般', '满意', '非常满意'][score - 1] || ''
}
</script>

<template>
  <div class="eval-list">
    <el-empty v-if="!evaluations.length" :description="emptyText" :image-size="80" />

    <article v-for="item in evaluations" :key="item.id" class="eval-item">
      <div class="item-head">
        <el-avatar :size="34" :src="item.fromUserAvatar">
          {{ (item.fromUserName || '匿').charAt(0) }}
        </el-avatar>
        <div class="who">
          <span class="name">
            {{ item.fromUserName || '匿名用户' }}
            <el-tag v-if="item.isAnonymous === 1" size="small" type="info" effect="plain">
              匿名
            </el-tag>
          </span>
          <span class="text-xs text-secondary">{{ formatDateTime(item.createTime) }}</span>
        </div>
        <div class="score">
          <el-rate :model-value="item.score" disabled size="small" />
          <span class="score-text">{{ starText(item.score) }}</span>
        </div>
      </div>

      <p v-if="item.content" class="item-content">{{ item.content }}</p>

      <div v-if="item.images?.length" class="item-images">
        <el-image
          v-for="(img, i) in item.images"
          :key="i"
          :src="img"
          :preview-src-list="item.images"
          :initial-index="i"
          fit="cover"
          class="eval-img"
        />
      </div>

      <div class="item-meta">
        <span class="text-xs text-secondary">{{ item.resourceName }}</span>
        <span v-if="item.resourceScore" class="text-xs text-secondary">
          · 资源相符度 {{ item.resourceScore }} 星
        </span>
        <span v-if="item.serviceScore" class="text-xs text-secondary">
          · 服务态度 {{ item.serviceScore }} 星
        </span>
        <el-tag v-if="item.status === 2" size="small" type="danger" effect="plain">
          已被管理员隐藏
        </el-tag>
      </div>

      <!-- 被评价人的回复:差评的申诉通道 -->
      <div v-if="item.replyContent" class="item-reply">
        <strong>对方回复:</strong>{{ item.replyContent }}
        <div class="text-xs text-secondary">{{ formatDateTime(item.replyTime) }}</div>
      </div>

      <div v-else-if="item.canReply" class="reply-box">
        <template v-if="replyingId === item.id">
          <el-input
            v-model="replyText"
            type="textarea"
            :rows="2"
            maxlength="500"
            placeholder="说明你的情况 —— 后来的租户会看到双方的说法"
          />
          <div class="reply-actions">
            <el-button size="small" @click="replyingId = null">取消</el-button>
            <el-button size="small" type="primary" @click="submitReply(item)">提交回复</el-button>
          </div>
        </template>
        <el-button v-else link type="primary" size="small" @click="startReply(item)">
          回复这条评价
        </el-button>
      </div>
    </article>
  </div>
</template>

<style scoped>
.eval-list {
  display: flex;
  flex-direction: column;
}

.eval-item {
  padding: var(--space-md) 0;
  border-bottom: 1px solid var(--color-border-light);
}

.eval-item:last-child {
  border-bottom: none;
}

.item-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.who {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.score {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-shrink: 0;
}

.score-text {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.item-content {
  margin-top: var(--space-sm);
  font-size: var(--font-size-sm);
  line-height: 1.7;
  color: var(--color-text-regular);
  white-space: pre-wrap;
}

.item-images {
  display: flex;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
}

.eval-img {
  width: 72px;
  height: 72px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
}

.item-meta {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
  margin-top: var(--space-sm);
}

.item-reply {
  margin-top: var(--space-sm);
  padding: var(--space-sm) var(--space-md);
  background: var(--color-info-light);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-regular);
  line-height: 1.7;
}

.item-reply strong {
  color: var(--color-text-primary);
}

.reply-box {
  margin-top: var(--space-sm);
}

.reply-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
}
</style>
