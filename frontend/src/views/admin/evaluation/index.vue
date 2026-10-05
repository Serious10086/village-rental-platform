<script setup>
/**
 * 后台 —— 评价管理。
 *
 * 【为什么后台能看到被隐藏的评价,前台看不到】
 * 前台的 status = 1 是硬编码的,后端不接受前端传这个参数 ——
 * 否则把 status 设成 2 就能把别人的违规评价翻出来。
 * 后台则相反:隐藏了还要能恢复,所以必须看得到全部的。
 *
 * 隐藏一条评价不只是"不显示":它会同时从"评价均分"和"资源完好率"
 * 两个信用口径里消失 —— 这正是 v_user_credit_summary 视图里
 * status = 1 那行条件的意义。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { getAdminEvaluations, hideEvaluation, showEvaluation } from '@/api/evaluation'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  onlyBad: false
})

const scopeText = {
  1: '全部村组的评价',
  2: '本镇范围内的评价',
  3: `本村(${userStore.userInfo?.villageName || ''})的评价`,
  4: '你本人相关的评价'
}[userStore.userInfo?.dataScope] || ''

async function fetchList() {
  loading.value = true
  try {
    const data = await getAdminEvaluations({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      onlyBad: query.onlyBad || undefined
    })
    rows.value = data.rows || []
    total.value = data.total || 0
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

async function toggleStatus(row) {
  const hiding = row.status === 1
  try {
    await ElMessageBox.confirm(
      hiding
        ? `隐藏后这条评价不再计入对方的信用分口径,对方也看不到它。确定隐藏吗?`
        : '恢复后这条评价会重新出现在资源页面上,并重新计入信用分口径。',
      hiding ? '隐藏违规评价' : '恢复评价',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    if (hiding) {
      await hideEvaluation(row.id)
    } else {
      await showEvaluation(row.id)
    }
    ElMessage.success(hiding ? '评价已隐藏' : '评价已恢复')
    fetchList()
  } catch (e) {
    // 拦截器已提示
  }
}

function starText(score) {
  return ['很差', '不满意', '一般', '满意', '非常满意'][score - 1] || ''
}

onMounted(fetchList)
</script>

<template>
  <div class="admin-evaluation">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        当前数据范围:<b>{{ scopeText }}</b>。
        被隐藏的评价在这里仍可见 —— 隐藏了还要能恢复,看不到就没法恢复。
      </template>
    </el-alert>

    <div class="card filter-bar">
      <el-checkbox v-model="query.onlyBad" @change="handleSearch">
        只看差评(≤ 2 星)
      </el-checkbox>

      <el-button :icon="Refresh" @click="handleSearch">刷新</el-button>

      <div class="spacer" />
      <span class="text-sm text-secondary">共 <b class="num">{{ total }}</b> 条</span>
    </div>

    <div class="card table-card">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="评价" min-width="320">
          <template #default="{ row }">
            <div class="eval-main">
              <div class="eval-head">
                <el-rate :model-value="row.score" disabled size="small" />
                <span class="text-xs text-secondary">{{ starText(row.score) }}</span>
                <el-tag v-if="row.status === 2" type="danger" size="small" effect="plain">
                  已隐藏
                </el-tag>
              </div>
              <div class="eval-content">{{ row.content || '(未填写内容)' }}</div>
              <div class="text-xs text-secondary">
                {{ row.fromUserName || '匿名用户' }} → {{ row.toUserName }}
                <span v-if="row.isAnonymous === 1"> · 匿名</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="资源 / 订单" min-width="180">
          <template #default="{ row }">
            <div class="res-cell">
              <img v-if="row.resourceCover" :src="row.resourceCover" class="thumb" alt="" />
              <div>
                <div class="text-sm">{{ row.resourceName || '资源已删除' }}</div>
                <div class="num-text">{{ row.orderNo }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="评分明细" width="140" align="center">
          <template #default="{ row }">
            <div class="text-xs text-secondary">
              资源相符度:{{ row.resourceScore ?? '—' }}
            </div>
            <div class="text-xs text-secondary">
              服务态度:{{ row.serviceScore ?? '—' }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="评价时间" width="150">
          <template #default="{ row }">
            <span class="num-text">{{ formatDateTime(row.createTime) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <!-- 按钮级权限:没有 evaluation:hide 的角色看不到 -->
            <el-button
              v-hasPerm="'evaluation:hide'"
              link
              :type="row.status === 1 ? 'danger' : 'primary'"
              size="small"
              @click="toggleStatus(row)"
            >
              {{ row.status === 1 ? '隐藏' : '恢复' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

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
.scope-alert {
  margin-bottom: var(--space-md);
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-md) var(--space-lg);
  margin-bottom: var(--space-md);
}

.spacer {
  flex: 1;
}

.table-card {
  padding: var(--space-md) var(--space-lg);
}

.eval-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.eval-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.eval-content {
  font-size: var(--font-size-sm);
  color: var(--color-text-regular);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.res-cell {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.thumb {
  width: 40px;
  height: 30px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  flex-shrink: 0;
}

.num-text {
  font-family: var(--font-family-number);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.pager {
  margin-top: var(--space-md);
  justify-content: flex-end;
}
</style>
