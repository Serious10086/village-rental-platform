<script setup>
/**
 * 评价对话框 —— 订单完成后由买卖双方各自发起。
 *
 * 【为什么三个评分维度有一个是选填的】
 * 综合评分必填,另外两个(资源相符度、服务态度)选填。
 * 强制打分只会逼出一堆敷衍的中间值 —— 用户想快点提交,
 * 就会在三颗星上随便点一下了事,那个数字反而比不填更误导人。
 * 让愿意细评的人细评,不愿意的人留空,统计口径才干净。
 *
 * 【为什么"评价对象"是算出来的】
 * 同一笔订单,买家打开这个框评的是卖家,卖家打开评的是买家。
 * 前端按 myRole 判断一下就能显示对,但真正决定 toUserId 的是后端 ——
 * 前端这里只是文案。
 */
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createEvaluation } from '@/api/evaluation'
import { uploadImage } from '@/api/resource'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  order: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'success'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const submitting = ref(false)
const form = ref(emptyForm())

/** 评分档位文案 —— 只在悬停/选中时显示,让用户知道 3 星意味着什么 */
const SCORE_TEXTS = ['很差', '不满意', '一般', '满意', '非常满意']

function emptyForm() {
  return {
    score: 0,
    resourceScore: 0,
    serviceScore: 0,
    content: '',
    images: [],
    anonymous: false
  }
}

/** 评价对象:我是买家就评卖家,反之亦然 */
const targetLabel = computed(() => {
  if (!props.order) return ''
  return props.order.myRole === 'buyer'
    ? `卖家 ${props.order.sellerName}`
    : `买家 ${props.order.buyerName}`
})

/** 资源相符度只有买家评才有意义 —— 卖家手里的是钱,不是被租的东西 */
const showResourceScore = computed(() => props.order?.myRole === 'buyer')

const canSubmit = computed(() => form.value.score > 0 && !submitting.value)

watch(visible, (v) => {
  if (v) {
    form.value = emptyForm()
  }
})

/** el-rate 允许点第二次取消选择(变成 0),但 0 分提交不上去,这里兜住 */
function onScoreChange(v) {
  if (!v) form.value.score = 0
}

async function handleUpload(options) {
  try {
    const url = await uploadImage(options.file)
    form.value.images.push(url)
    options.onSuccess(url)
  } catch (e) {
    options.onError(e)
  }
}

function removeImage(index) {
  form.value.images.splice(index, 1)
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    await createEvaluation({
      orderId: props.order.id,
      score: form.value.score,
      resourceScore: form.value.resourceScore || null,
      serviceScore: form.value.serviceScore || null,
      content: form.value.content || null,
      images: form.value.images.length ? form.value.images : null,
      anonymous: form.value.anonymous
    })
    ElMessage.success('评价提交成功,感谢你的反馈')
    visible.value = false
    emit('success')
  } catch (e) {
    // 业务错误已由 axios 拦截器统一提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="发表评价"
    width="520px"
    :close-on-click-modal="false"
  >
    <div v-if="order" class="eval-form">
      <div class="target-hint">
        正在评价:{{ targetLabel }}
        <div class="text-secondary text-sm">{{ order.resourceName }}</div>
      </div>

      <div class="rate-row">
        <span class="rate-label">综合评分<em>*</em></span>
        <el-rate
          v-model="form.score"
          :texts="SCORE_TEXTS"
          show-text
          size="large"
          @change="onScoreChange"
        />
      </div>

      <div v-if="showResourceScore" class="rate-row">
        <span class="rate-label">资源相符度</span>
        <!-- 不开半星:数据库里 score 是 TINYINT,存不下 4.5 -->
        <el-rate v-model="form.resourceScore" />
        <span class="rate-tip">实物和描述一致吗?这一项会计入对方的资源完好率</span>
      </div>

      <div class="rate-row">
        <span class="rate-label">服务态度</span>
        <el-rate v-model="form.serviceScore" />
      </div>

      <div class="field">
        <span class="rate-label">评价内容</span>
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
          placeholder="说说这次交易的真实感受,给后面的人做个参考(选填)"
        />
      </div>

      <div class="field">
        <span class="rate-label">上传图片</span>
        <div class="upload-row">
          <div v-for="(img, i) in form.images" :key="i" class="upload-item">
            <img :src="img" alt="评价图片" />
            <el-icon class="remove-btn" @click="removeImage(i)"><Close /></el-icon>
          </div>
          <el-upload
            v-if="form.images.length < 6"
            :show-file-list="false"
            :http-request="handleUpload"
            accept="image/*"
            class="upload-trigger"
          >
            <div class="upload-box">
              <el-icon><UploadFilled /></el-icon>
              <span>{{ form.images.length }}/6</span>
            </div>
          </el-upload>
        </div>
      </div>

      <el-checkbox v-model="form.anonymous" class="anon-check">
        匿名评价(对方看不到你的名字)
      </el-checkbox>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!canSubmit" :loading="submitting" @click="submit">
        提交评价
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.eval-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.target-hint {
  background: var(--color-primary-light);
  border-radius: var(--radius-md);
  padding: var(--space-sm) var(--space-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-regular);
}

.rate-row {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  flex-wrap: wrap;
}

.rate-label {
  width: 80px;
  flex-shrink: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.rate-label em {
  color: var(--color-danger);
  font-style: normal;
  margin-left: 2px;
}

.rate-tip {
  width: 100%;
  padding-left: 96px;
  font-size: var(--font-size-xs);
  color: var(--color-text-placeholder);
}

.field {
  display: flex;
  gap: var(--space-md);
}

.field .rate-label {
  padding-top: 6px;
}

.field :deep(.el-textarea),
.field .upload-row {
  flex: 1;
  min-width: 0;
}

.upload-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.upload-item {
  position: relative;
  width: 72px;
  height: 72px;
}

.upload-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
}

.remove-btn {
  position: absolute;
  top: -6px;
  right: -6px;
  background: var(--color-danger);
  color: #fff;
  border-radius: var(--radius-full);
  padding: 2px;
  cursor: pointer;
  font-size: 12px;
}

.upload-box {
  width: 72px;
  height: 72px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-sm);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: var(--color-text-placeholder);
  font-size: var(--font-size-xs);
  cursor: pointer;
  transition: border-color var(--duration-fast);
}

.upload-box:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.anon-check {
  padding-left: 96px;
}
</style>
