<script setup>
/**
 * 系统管理 —— 村组。
 *
 * 村组不只是注册页的一个下拉选项:用户的 village_id、资源的 village_id 都指向它,
 * 村级管理员「只看得到本村数据」就是靠它划的界。所以这个页面改的是权限的边界,
 * 不是一份普通的基础字典 —— 表头特意把"这个村组下挂了多少数据"的后果说明白。
 *
 * 树只有两级(乡镇 → 村)。后端没有硬性限制层级,但业务模型就是两级,
 * 所以界面上只给出"在乡镇下挂村"这一个入口,不提供第三级。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Plus } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import {
  getVillageManageTree, createVillage, updateVillage, deleteVillage
} from '@/api/village'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref([])

/** 乡镇节点 —— 即树的顶层。新增时的"上级"下拉只用它,以此保证只有两级 */
const townOptions = computed(() => rows.value.filter((v) => v.parentId == null))

/**
 * 增删改的权限开关。
 *
 * <p>用 v-if 而不是 v-hasPerm 指令:指令只有 mounted 钩子(见 directives/hasPerm.js),
 * 它是在挂载那一刻把节点从 DOM 里摘掉的。这个页面里"新增村"的显隐还跟着 row.parentId 走,
 * 用指令的话节点被摘掉就再也回不来了。资源管理页、公告页都是同样的写法。
 */
const canEdit = computed(() => userStore.hasPerm('village:edit'))

// ── 新增 / 编辑弹窗 ──────────────────────────────────────────
const dialogVisible = ref(false)
const dialogMode = ref('add')   // add | edit
const submitting = ref(false)
const formRef = ref(null)

const form = reactive({
  id: null,
  parentId: null,
  villageName: '',
  villageCode: '',
  townName: '',
  regionCode: '',
  sortOrder: 99,
  status: 1
})

const rules = {
  villageName: [
    { required: true, message: '请输入村组名称', trigger: 'blur' },
    { max: 50, message: '不能超过 50 个字', trigger: 'blur' }
  ],
  villageCode: [
    { required: true, message: '请输入村组编码', trigger: 'blur' },
    { max: 20, message: '不能超过 20 个字', trigger: 'blur' }
  ]
}

const isTown = computed(() => form.parentId == null)

const dialogTitle = computed(() => {
  if (dialogMode.value === 'edit') return isTown.value ? '编辑乡镇' : '编辑村组'
  return isTown.value ? '新增乡镇' : '新增村组'
})

/**
 * 编辑顶级节点时不给出"上级"下拉。
 *
 * <p>下拉里只有乡镇,所以拿它当上级就等于凭空造出第三级 ——
 * 而两级是这个页面的契约(townOptions 只认 parentId == null)。
 * 村的上级要能改(换乡镇是正常操作),乡镇的上级不能改。
 */
const showParentSelect = computed(() => !(dialogMode.value === 'edit' && isTown.value))

// ── 列表 ────────────────────────────────────────────────────

async function fetchList() {
  loading.value = true
  try {
    rows.value = (await getVillageManageTree()) || []
  } catch (e) {
    rows.value = []
  } finally {
    loading.value = false
  }
}

// ── 增删改 ──────────────────────────────────────────────────

/** 新增乡镇(顶级节点) */
function openAddTown() {
  dialogMode.value = 'add'
  Object.assign(form, {
    id: null, parentId: null, villageName: '', villageCode: '',
    townName: '', regionCode: '', sortOrder: 99, status: 1
  })
  dialogVisible.value = true
}

/** 在某个乡镇下新增村,上级已由所在行确定 */
function openAddChild(row) {
  dialogMode.value = 'add'
  Object.assign(form, {
    id: null, parentId: row.id, villageName: '', villageCode: '',
    // 乡镇名和区划代码从上级带过来 —— 这两项子节点基本和父节点一致,
    // 省得每加一个村就要手抄一遍
    townName: row.villageName, regionCode: row.regionCode,
    sortOrder: 99, status: 1
  })
  dialogVisible.value = true
}

function openEdit(row) {
  dialogMode.value = 'edit'
  Object.assign(form, {
    id: row.id,
    parentId: row.parentId,
    villageName: row.villageName,
    villageCode: row.villageCode,
    townName: row.townName,
    regionCode: row.regionCode,
    sortOrder: row.sortOrder,
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
      await createVillage({ ...form })
      ElMessage.success('添加成功')
    } else {
      await updateVillage({ ...form })
      ElMessage.success('保存成功')
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
  const hasChildren = row.children && row.children.length > 0
  // 有下级时后端必定拒绝,这里就别给"删除"按钮了 —— 让用户白点一次不如直接说清楚。
  // 用 alert 而不是去掉确认按钮的 confirm:后者会留一个空壳弹窗,只有取消能点。
  if (hasChildren) {
    await ElMessageBox.alert(
      `「${row.villageName}」下还有 ${row.children.length} 个村,请先删掉它们。`,
      '无法删除',
      { type: 'warning', confirmButtonText: '知道了' }
    ).catch(() => {})
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.villageName}」吗?如果还有用户或资源挂在这个村组下,系统会拒绝删除。`,
      '确认删除',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteVillage(row.id)
    ElMessage.success('已删除')
    fetchList()
  } catch (e) {
    // 拦截器已提示(比如"该村组下还有 N 个用户")
  }
}

/** 快速启用/停用。停用后注册页的下拉里就不再出现这个村组 */
async function toggleStatus(row) {
  const next = row.status === 1 ? 0 : 1
  try {
    await updateVillage({
      id: row.id,
      parentId: row.parentId,
      villageName: row.villageName,
      villageCode: row.villageCode,
      townName: row.townName,
      regionCode: row.regionCode,
      sortOrder: row.sortOrder,
      status: next
    })
    ElMessage.success(next === 1 ? '已启用' : '已停用')
    fetchList()
  } catch (e) {
    // 拦截器已提示
  }
}

onMounted(fetchList)
</script>

<template>
  <div>
    <el-alert type="info" :closable="false" show-icon class="intro-alert">
      <template #title>
        村组是<b>数据级权限的锚点</b> —— 用户和资源都挂在某个村组下,
        村级管理员能看到哪些数据就是按它划分的。
        停用的村组不会出现在注册页的下拉里,但已有数据不受影响。
      </template>
    </el-alert>

    <!-- ── 工具栏 ────────────────────────────────────────── -->
    <div class="card filter-bar">
      <el-button v-if="canEdit" type="primary" :icon="Plus" @click="openAddTown">新增乡镇</el-button>
      <el-button :icon="Refresh" @click="fetchList">刷新</el-button>
      <span class="text-secondary text-xs toolbar-hint">
        共 {{ townOptions.length }} 个乡镇
      </span>
    </div>

    <!-- ── 村组树 ────────────────────────────────────────── -->
    <div class="card table-card">
      <el-table
        v-loading="loading"
        :data="rows"
        row-key="id"
        default-expand-all
        :tree-props="{ children: 'children' }"
        :indent="24"
      >
        <el-table-column label="村组名称" min-width="220">
          <template #default="{ row }">
            <span :class="row.parentId == null ? 'node-town' : 'node-village'">
              {{ row.villageName }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="编码" width="150">
          <template #default="{ row }">
            <span class="mono">{{ row.villageCode || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="所属乡镇" width="130">
          <template #default="{ row }">
            <span class="text-sm">{{ row.townName || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="区划代码" width="120">
          <template #default="{ row }">
            <span class="mono">{{ row.regionCode || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="排序" width="80" align="center">
          <template #default="{ row }">
            <span class="num text-xs">{{ row.sortOrder }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="dark" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column v-if="canEdit" label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <!-- 只给乡镇开"新增下级"的口子,树就锁死在两级 -->
            <el-button
              v-if="row.parentId == null"
              link
              type="primary"
              size="small"
              :icon="Plus"
              @click="openAddChild(row)"
            >新增村</el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="toggleStatus(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- ── 新增 / 编辑弹窗 ───────────────────────────────── -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item v-if="showParentSelect" label="上级">
          <el-select
            v-model="form.parentId"
            placeholder="留空表示这是一个乡镇(顶级节点)"
            clearable
            style="width: 100%"
          >
            <el-option
              v-for="t in townOptions"
              :key="t.id"
              :label="t.villageName"
              :value="t.id"
              :disabled="t.id === form.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item :label="isTown ? '乡镇名称' : '村组名称'" prop="villageName">
          <el-input v-model="form.villageName" placeholder="例如:战旗村" />
        </el-form-item>

        <el-form-item label="编码" prop="villageCode">
          <el-input v-model="form.villageCode" placeholder="例如:CD-PD-TC-ZQ" />
        </el-form-item>

        <el-form-item label="所属乡镇">
          <el-input v-model="form.townName" placeholder="例如:唐昌镇" />
        </el-form-item>

        <el-form-item label="区划代码">
          <el-input v-model="form.regionCode" placeholder="例如:510124" />
        </el-form-item>

        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="9999" controls-position="right" />
        </el-form-item>

        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.intro-alert {
  margin-bottom: var(--space-md);
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-md) var(--space-lg);
  margin-bottom: var(--space-md);
  flex-wrap: wrap;
}

.toolbar-hint {
  margin-left: auto;
}

.table-card {
  padding: var(--space-md) var(--space-lg);
}

.mono {
  font-family: var(--font-family-number);
  font-size: var(--font-size-xs);
}

/* 乡镇是分组,村是组内的项 —— 靠字重和颜色分层,不用加图标去挤那一列 */
.node-town {
  font-weight: 600;
  color: var(--color-text-primary);
}

.node-village {
  color: var(--color-text-secondary);
}
</style>
