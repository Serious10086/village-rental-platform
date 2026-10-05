<script setup>
/**
 * 发布 / 编辑资源。
 *
 * 【这个页面的核心难点:一套表单要装下两种业务】
 *
 * 出租和出售的字段差别很大 ——
 *   出租:可租时段、可租星期、押金、使用规则
 *   出售:新旧程度、交货方式(没有押金和时段)
 *
 * 处理方式不是"两套表单堆在一个页面里用 v-if 切换"(那样重复度极高、
 * 校验逻辑要写两遍),而是:
 *   · 共用字段只写一份(名称、分类、价格、图片、位置、描述)
 *   · 差异化字段各自成组,用 v-if 切换
 *   · 提交时 DTO 是同一个,后端按 tradeType 做条件校验
 * 这样"一个接口、一个 DTO、一套校验入口",和后端的设计是对齐的。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getCategoryTree, publishResource, updateResource,
  getResourceDetail, uploadImage
} from '@/api/resource'
import { useUserStore } from '@/stores/user'
import { WEEKDAYS } from '@/utils/resourceDict'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 编辑模式:路由带 id */
const editId = computed(() => route.params.id ? Number(route.params.id) : null)
const isEdit = computed(() => editId.value != null)

const formRef = ref(null)
const submitting = ref(false)
const uploading = ref(false)
const categories = ref([])

const form = reactive({
  resourceName: '',
  categoryId: null,
  tradeType: 1,
  price: null,
  priceUnit: 1,
  deposit: 0,
  rentStartDate: null,
  rentEndDate: null,
  rentableWeekdays: [1, 2, 3, 4, 5, 6, 7],
  useRule: '',
  oldDegree: null,
  deliveryType: null,
  description: '',
  location: '',
  coverUrl: '',
  images: [],
  tags: []
})

/** 标签输入用的临时值 */
const tagInput = ref('')

// ── 校验规则 ──────────────────────────────────────────────────
const rules = {
  resourceName: [
    { required: true, message: '请填写资源名称', trigger: 'blur' },
    { max: 100, message: '名称不能超过 100 个字', trigger: 'blur' }
  ],
  categoryId: [{ required: true, message: '请选择资源分类', trigger: 'change' }],
  price: [
    { required: true, message: '请填写价格', trigger: 'blur' },
    {
      validator: (rule, value, cb) => {
        if (value == null || value === '') return cb(new Error('请填写价格'))
        if (Number(value) <= 0) return cb(new Error('价格必须大于 0'))
        cb()
      },
      trigger: 'blur'
    }
  ],
  location: [
    { required: true, message: '请填写资源所在位置', trigger: 'blur' },
    { max: 200, message: '位置描述不能超过 200 个字', trigger: 'blur' }
  ],
  rentStartDate: [{ required: true, message: '请选择可租起始日', trigger: 'change' }],
  rentEndDate: [{ required: true, message: '请选择可租截止日', trigger: 'change' }],
  useRule: [{ required: true, message: '请填写使用规则', trigger: 'blur' }],
  oldDegree: [{ required: true, message: '请选择新旧程度', trigger: 'change' }],
  deliveryType: [{ required: true, message: '请选择交货方式', trigger: 'change' }]
}

/**
 * 动态校验规则。
 *
 * Element Plus 的 rules 支持函数形式,可以按当前表单状态返回不同的规则集。
 * 这样出租模式下"新旧程度"不会拦提交,出售模式下"可租时段"也不会。
 */
const activeRules = computed(() => {
  const base = {
    resourceName: rules.resourceName,
    categoryId: rules.categoryId,
    price: rules.price,
    location: rules.location
  }
  if (form.tradeType === 1) {
    return {
      ...base,
      rentStartDate: rules.rentStartDate,
      rentEndDate: rules.rentEndDate,
      useRule: rules.useRule
    }
  }
  return {
    ...base,
    oldDegree: rules.oldDegree,
    deliveryType: rules.deliveryType
  }
})

/** 扁平化分类选项 */
const categoryOptions = computed(() =>
  categories.value.flatMap((parent) =>
    (parent.children || []).map((c) => ({
      id: c.id,
      name: c.categoryName,
      parentName: parent.categoryName,
      riskLevel: c.riskLevel
    }))
  )
)

/** 当前选中分类是否高风险 —— 用于实时提示"这个资源需要培训" */
const selectedCategoryRisk = computed(() =>
  categoryOptions.value.find((c) => c.id === form.categoryId)?.riskLevel
)

const priceUnitOptions = [
  { label: '元/天', value: 1 },
  { label: '元/次', value: 2 },
  { label: '元/件', value: 3 }
]

// ── 实名认证前置检查 ──────────────────────────────────────────

const needRealname = computed(() => !userStore.isRealnameVerified)

// ── 图片上传 ──────────────────────────────────────────────────

async function handleUpload(options) {
  const file = options.file
  uploading.value = true
  try {
    const data = await uploadImage(file)
    const url = data.url
    if (!form.coverUrl) form.coverUrl = url
    form.images.push(url)
    ElMessage.success('上传成功')
    options.onSuccess?.(data)
  } catch (e) {
    options.onError?.(e)
  } finally {
    uploading.value = false
  }
}

function removeImage(index) {
  const removed = form.images[index]
  form.images.splice(index, 1)
  // 删掉的如果正好是封面,自动把第一张顶上来 —— 否则封面会指向一张已删除的图
  if (form.coverUrl === removed) {
    form.coverUrl = form.images[0] || ''
  }
}

/** 上传前校验:类型和大小。服务端也会校验一遍,前端这层是为了少一次无用请求 */
function beforeUpload(file) {
  const allowed = ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/bmp']
  if (!allowed.includes(file.type)) {
    ElMessage.error('只支持 jpg / png / gif / webp / bmp 格式的图片')
    return false
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('图片不能超过 10MB')
    return false
  }
  return true
}

// ── 标签 ──────────────────────────────────────────────────────

function addTag() {
  const t = tagInput.value.trim()
  if (!t) return
  if (form.tags.length >= 6) {
    ElMessage.warning('最多添加 6 个标签')
    return
  }
  if (form.tags.includes(t)) {
    ElMessage.warning('这个标签已经加过了')
    return
  }
  form.tags.push(t)
  tagInput.value = ''
}

function removeTag(index) {
  form.tags.splice(index, 1)
}

// ── 提交 ──────────────────────────────────────────────────────

async function handleSubmit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    ElMessage.warning('请检查表单中标红的内容')
    return
  }

  if (form.images.length === 0) {
    ElMessage.warning('请至少上传一张资源图片')
    return
  }

  submitting.value = true
  try {
    const payload = {
      ...form,
      id: editId.value || undefined,
      // 后端要的是逗号拼接,这里传数组即可(用 join 在前端做反而少了服务端校验的机会)
      images: form.images,
      tags: form.tags
    }

    if (isEdit.value) {
      await updateResource(payload)
      ElMessage.success('保存成功')
    } else {
      await publishResource(payload)
      ElMessage.success('发布成功,等待村级管理员审核')
    }
    router.push('/my/resource')
  } catch (e) {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

// ── 初始化 ────────────────────────────────────────────────────

onMounted(async () => {
  try {
    categories.value = await getCategoryTree()
  } catch (e) {
    console.warn('分类加载失败', e)
  }

  if (isEdit.value) {
    try {
      const d = await getResourceDetail(editId.value)
      Object.assign(form, {
        resourceName: d.resourceName,
        categoryId: d.categoryId,
        tradeType: d.tradeType,
        price: d.price,
        priceUnit: d.priceUnit,
        deposit: d.deposit,
        rentStartDate: d.rentStartDate,
        rentEndDate: d.rentEndDate,
        rentableWeekdays: d.rentableWeekdays || [],
        useRule: d.useRule,
        oldDegree: d.oldDegree,
        deliveryType: d.deliveryType,
        description: d.description,
        location: d.location,
        coverUrl: d.coverUrl,
        images: d.images || [],
        tags: d.tags || []
      })
    } catch (e) {
      ElMessage.error('资源加载失败')
      router.push('/my/resource')
    }
  }
})
</script>

<template>
  <div class="page-container">
    <div class="publish-wrap">
      <header class="page-head">
        <h1 class="page-title">{{ isEdit ? '编辑资源' : '发布闲置资源' }}</h1>
        <p class="page-sub">
          把家里闲置的农机、场地、工具挂上来,让需要的人找到它
        </p>
      </header>

      <!-- 未实名提示:提前拦住,而不是让用户填完表单再报错 -->
      <el-alert
        v-if="needRealname"
        type="warning"
        :closable="false"
        show-icon
        class="realname-alert"
      >
        <template #title>
          你还没有完成实名认证,暂时无法发布资源。
          <router-link to="/user/profile" class="link">前往个人中心提交实名认证</router-link>
        </template>
      </el-alert>

      <el-form
        ref="formRef"
        :model="form"
        :rules="activeRules"
        label-position="top"
        :disabled="needRealname"
      >
        <!-- ══ 交易方式:放在最前面,因为它决定后面显示哪些字段 ══ -->
        <div class="card">
          <h2 class="section-title">第一步:选择交易方式</h2>
          <div class="trade-picker">
            <button
              type="button"
              class="trade-option"
              :class="{ 'is-active': form.tradeType === 1 }"
              @click="form.tradeType = 1"
            >
              <el-icon :size="26"><Van /></el-icon>
              <span class="trade-name">出租</span>
              <span class="trade-desc">按天收费,到期归还。适合农机、场地、工具等还能继续用的东西</span>
            </button>

            <button
              type="button"
              class="trade-option"
              :class="{ 'is-active': form.tradeType === 2 }"
              @click="form.tradeType = 2"
            >
              <el-icon :size="26"><ShoppingCart /></el-icon>
              <span class="trade-name">出售</span>
              <span class="trade-desc">一次性转让,不再收回。适合用不上、想出手的东西</span>
            </button>
          </div>
        </div>

        <!-- ══ 基本信息 ══ -->
        <div class="card">
          <h2 class="section-title">第二步:填写资源信息</h2>

          <el-form-item label="资源名称" prop="resourceName">
            <el-input
              v-model="form.resourceName"
              placeholder="例如:东方红 904 拖拉机 / 200 平米标准仓库"
              maxlength="100"
              show-word-limit
            />
          </el-form-item>

          <div class="form-row">
            <el-form-item label="资源分类" prop="categoryId">
              <el-select
                v-model="form.categoryId"
                placeholder="请选择分类"
                style="width: 100%"
                filterable
              >
                <el-option-group
                  v-for="parent in categories"
                  :key="parent.id"
                  :label="parent.categoryName"
                >
                  <el-option
                    v-for="c in parent.children || []"
                    :key="c.id"
                    :label="c.categoryName"
                    :value="c.id"
                  >
                    <span>{{ c.categoryName }}</span>
                    <el-tag
                      v-if="c.riskLevel === 3"
                      size="small"
                      type="danger"
                      effect="plain"
                      class="option-tag"
                    >高风险</el-tag>
                  </el-option>
                </el-option-group>
              </el-select>
            </el-form-item>

            <el-form-item label="资源位置" prop="location">
              <el-input
                v-model="form.location"
                placeholder="例如:战旗村3组农机库"
                maxlength="200"
              />
            </el-form-item>
          </div>

          <!-- 高风险分类的实时提示 -->
          <el-alert
            v-if="selectedCategoryRisk === 3"
            type="warning"
            :closable="false"
            show-icon
            class="risk-hint"
            title="这个分类属于高风险资源,发布后会要求租用方确认安全须知"
          />

          <el-form-item label="资源描述">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="4"
              maxlength="2000"
              show-word-limit
              placeholder="介绍一下资源的新旧程度、使用状况、注意事项等,写得越清楚越容易租出去"
            />
          </el-form-item>

          <el-form-item label="标签(选填,最多 6 个)">
            <div class="tag-input">
              <el-tag
                v-for="(t, idx) in form.tags"
                :key="t"
                closable
                class="tag-item"
                @close="removeTag(idx)"
              >{{ t }}</el-tag>
              <el-input
                v-model="tagInput"
                size="small"
                placeholder="输入后回车添加,如:春耕、低押金"
                class="tag-field"
                @keyup.enter="addTag"
              />
            </div>
            <div class="field-hint">
              标签会用于搜索匹配,好的标签能让资源更容易被找到
            </div>
          </el-form-item>
        </div>

        <!-- ══ 价格与交易条件 ══ -->
        <div class="card">
          <h2 class="section-title">第三步:{{ form.tradeType === 1 ? '租金与出租规则' : '价格与交货方式' }}</h2>

          <div class="form-row">
            <el-form-item label="价格" prop="price">
              <el-input-number
                v-model="form.price"
                :min="0.01"
                :max="9999999"
                :precision="2"
                :step="10"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>

            <el-form-item v-if="form.tradeType === 1" label="计价单位">
              <el-select v-model="form.priceUnit" style="width: 100%">
                <el-option
                  v-for="u in priceUnitOptions"
                  :key="u.value"
                  :label="u.label"
                  :value="u.value"
                />
              </el-select>
            </el-form-item>
          </div>

          <!-- ── 出租专属 ── -->
          <template v-if="form.tradeType === 1">
            <div class="form-row">
              <el-form-item label="可租起始日" prop="rentStartDate">
                <el-date-picker
                  v-model="form.rentStartDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="选择起始日期"
                  style="width: 100%"
                />
              </el-form-item>

              <el-form-item label="可租截止日" prop="rentEndDate">
                <el-date-picker
                  v-model="form.rentEndDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="选择截止日期"
                  style="width: 100%"
                />
              </el-form-item>
            </div>

            <el-form-item label="可租星期">
              <el-checkbox-group v-model="form.rentableWeekdays">
                <el-checkbox v-for="w in WEEKDAYS" :key="w.value" :value="w.value">
                  {{ w.label }}
                </el-checkbox>
              </el-checkbox-group>
            </el-form-item>

            <el-form-item label="押金(元)">
              <el-input-number
                v-model="form.deposit"
                :min="0"
                :max="9999999"
                :precision="2"
                :step="100"
                controls-position="right"
                style="width: 220px"
              />
              <span class="field-hint inline">押金为 0 表示不收押金</span>
            </el-form-item>

            <el-form-item label="使用规则" prop="useRule">
              <el-input
                v-model="form.useRule"
                type="textarea"
                :rows="4"
                maxlength="1000"
                show-word-limit
                placeholder="例如:&#10;1. 需持有拖拉机驾驶证&#10;2. 提前 2 天预约&#10;3. 作业期间柴油由承租方承担"
              />
            </el-form-item>
          </template>

          <!-- ── 出售专属 ── -->
          <template v-else>
            <div class="form-row">
              <el-form-item label="新旧程度" prop="oldDegree">
                <el-select v-model="form.oldDegree" placeholder="请选择" style="width: 100%">
                  <el-option label="全新" :value="1" />
                  <el-option label="九成新" :value="2" />
                  <el-option label="七成新" :value="3" />
                  <el-option label="五成新及以下" :value="4" />
                </el-select>
              </el-form-item>

              <el-form-item label="交货方式" prop="deliveryType">
                <el-select v-model="form.deliveryType" placeholder="请选择" style="width: 100%">
                  <el-option label="自提" :value="1" />
                  <el-option label="协商" :value="2" />
                </el-select>
              </el-form-item>
            </div>
          </template>
        </div>

        <!-- ══ 图片 ══ -->
        <div class="card">
          <h2 class="section-title">第四步:上传图片</h2>
          <p class="section-desc">第一张会作为封面。图片越清晰,越容易被人看中</p>

          <div class="image-list">
            <div v-for="(img, idx) in form.images" :key="img" class="image-item">
              <img :src="img" :alt="`图片 ${idx + 1}`" />
              <span v-if="form.coverUrl === img" class="cover-badge">封面</span>
              <div class="image-actions">
                <el-button
                  v-if="form.coverUrl !== img"
                  link
                  size="small"
                  @click="form.coverUrl = img"
                >设为封面</el-button>
                <el-button link type="danger" size="small" @click="removeImage(idx)">
                  删除
                </el-button>
              </div>
            </div>

            <el-upload
              v-if="form.images.length < 9"
              class="image-uploader"
              :show-file-list="false"
              :before-upload="beforeUpload"
              :http-request="handleUpload"
              accept="image/*"
            >
              <div class="upload-trigger" :class="{ 'is-loading': uploading }">
                <el-icon :size="24">
                  <Loading v-if="uploading" class="is-spinning" />
                  <Plus v-else />
                </el-icon>
                <span>{{ uploading ? '上传中…' : '添加图片' }}</span>
              </div>
            </el-upload>
          </div>

          <div class="field-hint">
            最多 9 张,单张不超过 10MB,支持 jpg / png / gif / webp / bmp
          </div>
        </div>

        <!-- ══ 提交 ══ -->
        <div class="submit-bar">
          <el-button size="large" @click="router.back()">取消</el-button>
          <el-button
            type="primary"
            size="large"
            :loading="submitting"
            :disabled="needRealname"
            @click="handleSubmit"
          >
            {{ isEdit ? '保存修改' : '提交发布' }}
          </el-button>
          <span class="submit-hint">
            {{ isEdit
              ? '已上架的资源修改后会重新进入审核'
              : '提交后需经村级管理员审核,通过后就会出现在前台' }}
          </span>
        </div>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.publish-wrap {
  max-width: 880px;
  margin: 0 auto;
}

.page-head {
  margin-bottom: var(--space-lg);
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

.realname-alert {
  margin-bottom: var(--space-md);
}

.link {
  text-decoration: underline;
}

.card {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-lg);
  margin-bottom: var(--space-md);
}

.section-title {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--space-md);
}

.section-desc {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  margin-top: calc(var(--space-md) * -1 + 4px);
  margin-bottom: var(--space-md);
}

/* ── 交易方式选择器 ──────────────────────────────────────── */
.trade-picker {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-md);
}

.trade-option {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-lg);
  border: 2px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-card);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-out);
  font-family: inherit;
  text-align: center;
}

.trade-option:hover {
  border-color: var(--color-primary-border);
}

.trade-option.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary-light);
  color: var(--color-primary);
}

.trade-name {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
}

.trade-desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.trade-option.is-active .trade-desc {
  color: var(--color-primary);
  opacity: 0.85;
}

/* ── 表单 ────────────────────────────────────────────────── */
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-md);
}

.risk-hint {
  margin-bottom: var(--space-md);
}

.option-tag {
  margin-left: var(--space-sm);
}

.field-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-placeholder);
  line-height: 1.6;
}

.field-hint.inline {
  margin-left: var(--space-sm);
}

/* ── 标签输入 ────────────────────────────────────────────── */
.tag-input {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  align-items: center;
  width: 100%;
}

.tag-item {
  margin: 0;
}

.tag-field {
  width: 200px;
}

/* ── 图片 ────────────────────────────────────────────────── */
.image-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: var(--space-md);
  margin-bottom: var(--space-sm);
}

.image-item {
  position: relative;
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid var(--color-border);
}

.image-item img {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
}

.cover-badge {
  position: absolute;
  top: 6px;
  left: 6px;
  background: var(--color-primary);
  color: #fff;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: var(--radius-sm);
}

.image-actions {
  display: flex;
  justify-content: space-between;
  padding: 2px 6px;
  background: var(--color-info-light);
}

.upload-trigger {
  aspect-ratio: 4 / 3;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: all var(--duration-fast);
}

.upload-trigger:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.upload-trigger.is-loading {
  pointer-events: none;
  opacity: 0.7;
}

.is-spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ── 提交栏 ──────────────────────────────────────────────── */
.submit-bar {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-lg);
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  position: sticky;
  bottom: 0;
  flex-wrap: wrap;
}

.submit-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* ── 响应式 ──────────────────────────────────────────────── */
@media (max-width: 768px) {
  .trade-picker,
  .form-row {
    grid-template-columns: 1fr;
  }
}
</style>
