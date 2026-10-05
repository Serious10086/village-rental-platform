<script setup>
/**
 * 后台 —— 用户管理。
 *
 * 【这个页面的主要作用是可视化数据级权限】
 *
 * 同一个页面,不同角色登录看到的数据行数完全不同:
 *   · 平台管理员 → 全部 10 个用户,横跨 4 个村组
 *   · 战旗村支书 → 只有战旗村的 5 个用户
 *
 * 更关键的是:即使有人在 URL 上手动加上 ?villageId=3 想偷看别的村,
 * 服务端也会把这个参数覆盖掉 —— 前端过滤挡不住人,只有服务端过滤才算数。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import request from '@/api/request'
import { useUserStore } from '@/stores/user'
import { formatMoney } from '@/utils/format'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  userType: null,
  realnameStatus: null
})

/** 状态字典 —— 集中定义,多处复用 */
const USER_TYPE_MAP = { 1: '村民', 2: '租户/买家', 3: '管理员' }
const REALNAME_MAP = {
  0: { text: '未提交', type: 'info' },
  1: { text: '待审核', type: 'warning' },
  2: { text: '已认证', type: 'success' },
  3: { text: '已驳回', type: 'danger' }
}
const ACCOUNT_MAP = {
  0: { text: '禁用', type: 'info' },
  1: { text: '正常', type: 'success' },
  2: { text: '封禁', type: 'danger' }
}

async function fetchList() {
  loading.value = true
  try {
    const data = await request.get('/admin/user/list', { params: query })
    rows.value = data.rows || []
    total.value = data.total || 0
  } catch (e) {
    // 拦截器已提示
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
  query.userType = null
  query.realnameStatus = null
  handleSearch()
}

/**
 * 封禁/解封 —— 按钮级权限的演示点。
 *
 * 按钮上挂了 v-hasPerm="'user:ban'",没有该权限的角色根本看不到它。
 * 即使绕过界面直接发请求,后端的 @RequiresPerm 也会拦下并返回 403。
 */
function handleToggleBan(row) {
  const action = row.accountStatus === 2 ? '解封' : '封禁'
  ElMessage.info(`「${action}」功能将在后续批次实现(当前批次只做权限验证)`)
}

onMounted(fetchList)
</script>

<template>
  <div class="user-page">
    <!-- ── 数据范围提示 ────────────────────────────────────── -->
    <el-alert
      v-if="userStore.userInfo?.dataScope === 3"
      type="info"
      :closable="false"
      show-icon
      class="scope-alert"
    >
      <template #title>
        你的数据范围是「本村({{ userStore.userInfo?.villageName }})」,
        所以这里只能看到本村用户。即使手动在地址栏加上
        <code>?villageId=3</code>,服务端也会覆盖成你自己的村 —— 试一下就知道了。
      </template>
    </el-alert>

    <!-- ── 筛选条件 ────────────────────────────────────────── -->
    <div class="card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索手机号或姓名"
        clearable
        style="width: 220px"
        @keyup.enter="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.userType" placeholder="用户类型" clearable style="width: 140px">
        <el-option label="村民" :value="1" />
        <el-option label="租户/买家" :value="2" />
      </el-select>

      <el-select v-model="query.realnameStatus" placeholder="实名状态" clearable style="width: 140px">
        <el-option label="未提交" :value="0" />
        <el-option label="待审核" :value="1" />
        <el-option label="已认证" :value="2" />
        <el-option label="已驳回" :value="3" />
      </el-select>

      <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>

      <div class="filter-spacer" />

      <span class="text-sm text-secondary">
        共 <b class="num">{{ total }}</b> 条
      </span>
    </div>

    <!-- ── 表格 ────────────────────────────────────────────── -->
    <div class="card table-card">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />

        <el-table-column label="用户" min-width="160">
          <template #default="{ row }">
            <div class="user-cell">
              <el-avatar :size="32" :src="row.avatar">
                {{ (row.realName || row.phone).charAt(0) }}
              </el-avatar>
              <div>
                <div class="user-name">{{ row.realName || '未填写姓名' }}</div>
                <div class="text-xs text-secondary num">{{ row.phone }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="villageName" label="所属村组" width="110">
          <template #default="{ row }">
            <span v-if="row.villageName">{{ row.villageName }}</span>
            <span v-else class="text-secondary">—</span>
          </template>
        </el-table-column>

        <el-table-column label="角色" min-width="150">
          <template #default="{ row }">
            <el-tag
              v-for="r in row.roleNames"
              :key="r"
              size="small"
              effect="plain"
              class="role-tag"
            >
              {{ r }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="类型" width="140">
          <template #default="{ row }">
            {{ USER_TYPE_MAP[row.userType] || '未知' }}
            <!--
              外来用户:管理员在备案页代建的账号。
              备案通过后才会写上归属村组,所以这里和「所属村组」列
              合起来看,才能分辨"外来且还没备案"和"外来但已备案"
            -->
            <el-tag
              v-if="row.isOutside === 1"
              type="warning"
              size="small"
              effect="plain"
              class="outside-tag"
            >
              外来
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="实名" width="90">
          <template #default="{ row }">
            <el-tag :type="REALNAME_MAP[row.realnameStatus]?.type" size="small">
              {{ REALNAME_MAP[row.realnameStatus]?.text }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="信用分" width="90" align="center">
          <template #default="{ row }">
            <span class="num" :class="{ 'text-danger': row.creditScore < 60 }">
              {{ row.creditScore }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="余额" width="110" align="right">
          <template #default="{ row }">
            <span class="num">¥{{ formatMoney(row.balance) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="ACCOUNT_MAP[row.accountStatus]?.type" size="small" effect="dark">
              {{ ACCOUNT_MAP[row.accountStatus]?.text }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <!--
              按钮级权限演示:这一列在村级管理员那里会是空白 ——
              因为 VILLAGE_ADMIN 角色没有被授予 user:ban 权限,
              v-hasPerm 指令会把按钮直接从 DOM 里移除。
            -->
            <el-button
              v-hasPerm="'user:ban'"
              link
              type="danger"
              size="small"
              @click="handleToggleBan(row)"
            >
              {{ row.accountStatus === 2 ? '解封' : '封禁' }}
            </el-button>
            <span v-if="!userStore.hasPerm('user:ban')" class="text-xs text-secondary">
              无操作权限
            </span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pagination"
        @size-change="handleSearch"
        @current-change="fetchList"
      />
    </div>
  </div>
</template>

<style scoped>
.user-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.scope-alert :deep(.el-alert__title) {
  line-height: 1.7;
  font-size: var(--font-size-sm);
}

.scope-alert code {
  font-family: var(--font-family-number);
  background: rgba(0, 0, 0, 0.06);
  padding: 1px 5px;
  border-radius: var(--radius-sm);
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
  padding: var(--space-md) var(--space-lg);
}

.filter-spacer {
  flex: 1;
}

.table-card {
  padding: var(--space-md) var(--space-lg) var(--space-lg);
}

.user-cell {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.user-name {
  font-weight: var(--font-weight-medium);
  line-height: 1.3;
}

.role-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}

.outside-tag {
  margin-left: 4px;
}

.pagination {
  margin-top: var(--space-md);
  justify-content: flex-end;
}
</style>
