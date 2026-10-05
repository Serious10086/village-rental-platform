<script setup>
/**
 * 系统管理 —— 敏感词库。
 *
 * 页面上有个「试一试」输入框 —— 加它是因为"词库里加了词"和
 * "加了词真的生效"是两件事。管理员改完能立刻验证,不用等有人发内容才发现没生效。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Upload, Plus } from '@element-plus/icons-vue'
import {
  getSensitiveWordList, addSensitiveWord, updateSensitiveWord,
  deleteSensitiveWord, batchImportSensitiveWords, testSensitiveWords,
  getSensitiveWordSize
} from '@/api/audit'
import { WORD_ACTION, WORD_CATEGORY } from '@/utils/auditDict'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const wordSize = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  category: null,
  status: null
})

// ── 新增 / 编辑弹窗 ──────────────────────────────────────────
const dialogVisible = ref(false)
const dialogMode = ref('add')   // add | edit
const submitting = ref(false)
const formRef = ref(null)

const form = reactive({
  id: null,
  word: '',
  category: '广告',
  action: 1,
  status: 1
})

const rules = {
  word: [
    { required: true, message: '请输入敏感词', trigger: 'blur' },
    { max: 50, message: '不能超过 50 个字', trigger: 'blur' }
  ]
}

// ── 批量导入 ────────────────────────────────────────────────
const importVisible = ref(false)
const importing = ref(false)
const importForm = reactive({
  text: '',
  category: '广告',
  action: 1
})

// ── 试一试 ──────────────────────────────────────────────────
const testText = ref('')
const testResult = ref(null)
const testing = ref(false)

// ── 列表 ────────────────────────────────────────────────────

async function fetchList() {
  loading.value = true
  try {
    const data = await getSensitiveWordList({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      category: query.category || undefined,
      status: query.status ?? undefined
    })
    rows.value = data.rows || []
    total.value = data.total || 0
    wordSize.value = await getSensitiveWordSize()
  } catch (e) {
    rows.value = []
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
  query.category = null
  query.status = null
  handleSearch()
}

// ── 增删改 ──────────────────────────────────────────────────

function openAdd() {
  dialogMode.value = 'add'
  Object.assign(form, { id: null, word: '', category: '广告', action: 1, status: 1 })
  dialogVisible.value = true
}

function openEdit(row) {
  dialogMode.value = 'edit'
  Object.assign(form, {
    id: row.id,
    word: row.word,
    category: row.category,
    action: row.action,
    status: row.status
  })
  dialogVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    if (dialogMode.value === 'add') {
      await addSensitiveWord({ ...form })
      ElMessage.success('添加成功,已立即生效')
    } else {
      await updateSensitiveWord({ ...form })
      ElMessage.success('保存成功,已立即生效')
    }
    dialogVisible.value = false
    fetchList()
  } catch (e) {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除敏感词「${row.word}」吗?删除后该词不再被过滤。`,
      '确认删除',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  await deleteSensitiveWord(row.id)
  ElMessage.success('已删除')
  fetchList()
}

/** 快速启用/停用 */
async function toggleStatus(row) {
  await updateSensitiveWord({
    id: row.id, word: row.word, category: row.category,
    action: row.action, status: row.status === 1 ? 0 : 1
  })
  ElMessage.success(row.status === 1 ? '已停用' : '已启用')
  fetchList()
}

// ── 批量导入 ────────────────────────────────────────────────

function openImport() {
  importForm.text = ''
  importForm.category = '广告'
  importForm.action = 1
  importVisible.value = true
}

async function submitImport() {
  if (!importForm.text.trim()) {
    ElMessage.warning('请输入要导入的敏感词')
    return
  }
  importing.value = true
  try {
    const data = await batchImportSensitiveWords({ ...importForm })
    ElMessage.success(`成功导入 ${data.inserted} 个词,词库共 ${data.total} 个`)
    importVisible.value = false
    fetchList()
  } catch (e) {
    // 拦截器已提示
  } finally {
    importing.value = false
  }
}

// ── 试一试 ──────────────────────────────────────────────────

async function runTest() {
  if (!testText.value.trim()) {
    ElMessage.warning('请输入要测试的文字')
    return
  }
  testing.value = true
  try {
    testResult.value = await testSensitiveWords(testText.value)
  } catch (e) {
    testResult.value = null
  } finally {
    testing.value = false
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="sensitive-page">
    <el-alert type="info" :closable="false" show-icon class="intro-alert">
      <template #title>
        词库全平台共用,增删改<b>立即生效</b>(服务会自动重载内存中的过滤树,不用重启)。
        当前已加载 <b class="num">{{ wordSize }}</b> 个词。
      </template>
    </el-alert>

    <!-- ── 试一试 ────────────────────────────────────────── -->
    <div class="card test-card">
      <h3 class="card-title">
        <el-icon><MagicStick /></el-icon> 试一试
      </h3>
      <p class="card-desc">输入一段文字,看看会被怎么处理 —— 改完词库可以立刻验证效果</p>

      <div class="test-input">
        <el-input
          v-model="testText"
          placeholder="例如:有需要的加微信联系我,或者这是赌博网站"
          @keyup.enter="runTest"
        >
          <template #append>
            <el-button type="primary" :loading="testing" @click="runTest">测试</el-button>
          </template>
        </el-input>
      </div>

      <div v-if="testResult" class="test-result">
        <div class="result-row">
          <span class="result-label">处理结果</span>
          <el-tag :type="testResult.blocked ? 'danger' : 'success'" size="small" effect="dark">
            {{ testResult.blocked ? '整条拦截' : '允许发布' }}
          </el-tag>
        </div>

        <div class="result-row">
          <span class="result-label">命中词</span>
          <template v-if="testResult.hits?.length">
            <el-tag
              v-for="h in testResult.hits"
              :key="h"
              type="danger"
              size="small"
              effect="plain"
              class="hit-tag"
            >{{ h }}</el-tag>
          </template>
          <span v-else class="text-secondary text-sm">无命中</span>
        </div>

        <div class="result-row">
          <span class="result-label">打码后</span>
          <span class="result-text">{{ testResult.filtered || '—' }}</span>
        </div>
      </div>
    </div>

    <!-- ── 筛选 ──────────────────────────────────────────── -->
    <div class="card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索敏感词"
        clearable
        style="width: 180px"
        @keyup.enter="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.category" placeholder="分类" clearable style="width: 120px">
        <el-option v-for="c in WORD_CATEGORY" :key="c" :label="c" :value="c" />
      </el-select>

      <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px">
        <el-option label="启用" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>

      <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>

      <div class="spacer" />

      <el-button type="primary" plain :icon="Upload" @click="openImport">批量导入</el-button>
      <el-button type="primary" :icon="Plus" @click="openAdd">新增敏感词</el-button>
    </div>

    <!-- ── 表格 ──────────────────────────────────────────── -->
    <div class="card table-card">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />

        <el-table-column label="敏感词" min-width="180">
          <template #default="{ row }">
            <span class="word">{{ row.word }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="category" label="分类" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.category }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="处理方式" width="140">
          <template #default="{ row }">
            <el-tag :type="WORD_ACTION[row.action]?.type" size="small">
              {{ WORD_ACTION[row.action]?.label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="说明" min-width="160">
          <template #default="{ row }">
            <span class="text-xs text-secondary">{{ WORD_ACTION[row.action]?.desc }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="dark">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button
              link
              :type="row.status === 1 ? 'warning' : 'success'"
              size="small"
              @click="toggleStatus(row)"
            >{{ row.status === 1 ? '停用' : '启用' }}</el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pagination"
        @size-change="handleSearch"
        @current-change="fetchList"
      />
    </div>

    <!-- ── 新增/编辑弹窗 ─────────────────────────────────── -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'add' ? '新增敏感词' : '编辑敏感词'"
      width="480px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="敏感词" prop="word">
          <el-input v-model="form.word" placeholder="要过滤的词" maxlength="50" />
        </el-form-item>

        <el-form-item label="分类">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="c in WORD_CATEGORY" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>

        <el-form-item label="处理方式">
          <el-radio-group v-model="form.action">
            <el-radio :value="1">替换为 *(内容可发布)</el-radio>
            <el-radio :value="2">直接拦截(整条拒绝)</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
          <span class="text-xs text-secondary switch-hint">
            停用后该词不再参与过滤,但保留在词库里
          </span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- ── 批量导入弹窗 ──────────────────────────────────── -->
    <el-dialog v-model="importVisible" title="批量导入敏感词" width="520px">
      <el-form label-position="top">
        <el-form-item label="敏感词列表">
          <el-input
            v-model="importForm.text"
            type="textarea"
            :rows="8"
            placeholder="每行一个词,也可以用逗号分隔。例如:&#10;加微信&#10;私聊我,扫码&#10;刷单"
          />
          <div class="field-hint">
            已存在的词会自动跳过,不会因为个别重复导致整批失败
          </div>
        </el-form-item>

        <el-form-item label="统一分类">
          <el-select v-model="importForm.category" style="width: 100%">
            <el-option v-for="c in WORD_CATEGORY" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>

        <el-form-item label="统一处理方式">
          <el-radio-group v-model="importForm.action">
            <el-radio :value="1">替换为 *</el-radio>
            <el-radio :value="2">直接拦截</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="submitImport">
          导入
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sensitive-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.intro-alert :deep(.el-alert__title) {
  line-height: 1.7;
  font-size: var(--font-size-sm);
}

.card {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

/* ── 试一试 ──────────────────────────────────────────────── */
.test-card {
  padding: var(--space-lg);
}

.card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
}

.card-desc {
  margin-top: var(--space-xs);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.test-input {
  margin-top: var(--space-md);
}

.test-input :deep(.el-input-group__append) {
  padding: 0;
  border: none;
  background: transparent;
}

.test-input :deep(.el-input-group__append .el-button) {
  margin: 0;
  height: 100%;
  border-radius: 0 var(--radius-md) var(--radius-md) 0;
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
}

.test-result {
  margin-top: var(--space-md);
  padding: var(--space-md);
  background: var(--color-info-light);
  border-radius: var(--radius-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.result-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.result-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  width: 64px;
  flex-shrink: 0;
}

.hit-tag {
  margin-right: 4px;
}

.result-text {
  font-size: var(--font-size-sm);
  color: var(--color-text-regular);
}

/* ── 筛选 ────────────────────────────────────────────────── */
.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
  padding: var(--space-md) var(--space-lg);
}

.spacer {
  flex: 1;
}

.table-card {
  padding: var(--space-md) var(--space-lg) var(--space-lg);
}

.word {
  font-weight: var(--font-weight-medium);
}

.pagination {
  margin-top: var(--space-md);
  justify-content: flex-end;
}

.field-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-placeholder);
  margin-top: 4px;
}

.switch-hint {
  margin-left: var(--space-sm);
}
</style>
