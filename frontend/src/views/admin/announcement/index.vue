<script setup>
/**
 * 后台 —— 公告管理。
 *
 * 【这一页有五个能力】
 * 看列表(announcement:list)、发布新建(announcement:add)、
 * 编辑(announcement:edit)、发布已有(announcement:add)、
 * 下架(announcement:offline)。
 *
 * 【为什么"发布已有"复用了 announcement:add 这个码】
 * 新建一条和把一条草稿/已下架的发出去,在业务上是同一件事 ——
 * 让公告进入可见状态。单独再造一个码,权限表里只会多一条永远和
 * announcement:add 一起授予的记录。评价那个页面也是这么做的:
 * evaluation:hide 一个码同时管「隐藏」和「恢复」两个方向。
 *
 * 【"编辑"和"下架"是两个独立动作,不是状态机的两个阶段】
 * 已发布的公告也能直接编辑(改错字不必先下架),编辑不重发通知;
 * 下架是"撤回",下架之后前台读不到、后台还看得到,可以再编辑后重新发布。
 * 重新发布**会**重发通知 —— 一条公告重新可见这件事本身就值得再推一次,
 * 沉默地让它复活,村民会以为它一直是可见的。
 *
 * 【编辑弹窗要多发一次请求拿正文】
 * 列表接口刻意不返回 content(一条公告正文是 LONGTEXT,十条就是几十 KB,
 * 而列表页一个字都不显示),所以点"编辑"时单独取一次详情。
 * 注意取的是后台详情接口 —— 前台那个详情接口只认「已发布」,
 * 拿草稿和下架的公告去问它是 404,编辑就无从谈起了。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, View } from '@element-plus/icons-vue'
import {
  getAdminAnnouncements, getAdminAnnouncementDetail,
  publishAnnouncement, updateAnnouncement,
  publishExistingAnnouncement, offlineAnnouncement
} from '@/api/announcement'
import { uploadImage } from '@/api/resource'
import { useUserStore } from '@/stores/user'
import { ANNOUNCEMENT_TYPE, ANNOUNCEMENT_STATUS } from '@/utils/interactDict'
import { formatDateTime } from '@/utils/format'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  noticeType: null,
  status: null
})

const typeOptions = Object.entries(ANNOUNCEMENT_TYPE).map(([v, o]) => ({
  value: Number(v), label: o.label
}))

const statusOptions = Object.entries(ANNOUNCEMENT_STATUS).map(([v, o]) => ({
  value: Number(v), label: o.label
}))

const scopeText = {
  1: '全部村组的公告',
  2: '本镇范围内的公告',
  3: `本村(${userStore.userInfo?.villageName || ''})的公告`,
  4: '你发布的公告'
}[userStore.userInfo?.dataScope] || ''

const isSuperAdmin = computed(() => userStore.userInfo?.superAdmin === true)

async function fetchList() {
  loading.value = true
  try {
    const data = await getAdminAnnouncements({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      noticeType: query.noticeType ?? undefined,
      status: query.status ?? undefined
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

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.keyword = ''
  query.noticeType = null
  query.status = null
  handleSearch()
}

// ── 新建 / 编辑 ───────────────────────────────────────────────
const dialogVisible = ref(false)
/**
 * 弹窗正在提交哪一种动作 —— `'draft'` / `'publish'` / `'save'`,闲时为 null。
 *
 * 记的是"哪一个按钮在跑",不是简单的 true/false:新建弹窗底下有两个按钮
 * (存草稿、确认发布),用布尔量的话两个按钮会同时转圈,
 * 用户看不出点中的是哪一个。
 */
const submitting = ref(null)
/** 某一行正在发布 / 下架中,记的是这一行的 id —— 只锁那一行的按钮 */
const publishing = ref(null)
const uploading = ref(false)

const formRef = ref(null)

const form = reactive({
  title: '',
  content: '',
  coverUrl: '',
  noticeType: null,
  isTop: 0
})

const rules = {
  title: [
    { required: true, message: '请填写公告标题', trigger: 'blur' },
    { max: 100, message: '标题最多 100 字', trigger: 'blur' }
  ],
  noticeType: [{ required: true, message: '请选择公告类型', trigger: 'change' }],
  content: [{ required: true, message: '请填写公告正文', trigger: 'blur' }]
}

/**
 * 可选类型。
 *
 * 「系统公告」只有超级管理员能发 —— 后端 publish() 里也校验了这一条,
 * 前端这层过滤只是不让普通村管选到一个注定失败的类型。
 * 村级管理员选它 → 后端 403「「系统公告」只能由平台管理员发布」。
 */
const availableTypes = computed(() =>
  typeOptions.filter((t) => isSuperAdmin.value || t.value !== 5)
)

/**
 * 这条公告会挂到哪儿 —— 弹窗上必须写清楚。
 *
 * 由后端的 resolvePublishVillage 决定:超管发的一律是全平台
 * (villageId = null),村级管理员发的一律挂本村。前端不参与决定,
 * 这里只是把结果<b>提前告知</b>,免得管理员发完才发现受众不对。
 */
const audienceText = computed(() => {
  if (isSuperAdmin.value) return '全平台公告 —— 所有村组的用户都能看到'
  const name = userStore.userInfo?.villageName || '本村'
  return `本村公告 —— 只有 ${name} 的用户能看到`
})

/** 正在编辑的公告 id;null 表示这个弹窗是"新建"模式 */
const editingId = ref(null)
/** 正在编辑的公告当前状态 —— 决定弹窗上那句提示怎么写 */
const editingStatus = ref(null)
const isEditing = computed(() => editingId.value !== null)

function resetForm() {
  form.title = ''
  form.content = ''
  form.coverUrl = ''
  form.noticeType = null
  form.isTop = 0
}

function openCreate() {
  editingId.value = null
  editingStatus.value = null
  resetForm()
  dialogVisible.value = true
}

/**
 * 打开编辑弹窗。
 *
 * 要单独取一次详情:列表接口不带正文(见文件头注释)。
 * 详情接口返回的 status 也一并带过来,因为弹窗上的提示语要分
 * "这条已经发出去了"和"这条还没发出去"两种说法。
 *
 * 请求失败时**不打开弹窗**:后端返回 404 的场合(比如这条刚被别人删了)
 * data 是 null,硬往下走会在 detail.status 上抛 TypeError ——
 * 那个异常会被这个 catch 吞掉,用户看到的是"点了编辑没反应",
 * 比一条明确的提示更糟。错误提示本身由 request.js 统一弹,
 * 这里只负责别把失败状态当成成功状态用。
 */
async function openEdit(row) {
  try {
    const detail = await getAdminAnnouncementDetail(row.id)
    if (!detail) {
      ElMessage.warning('这条公告已经不存在了,请刷新列表')
      return
    }
    editingId.value = row.id
    editingStatus.value = detail.status
    form.title = detail.title ?? ''
    form.content = detail.content ?? ''
    form.coverUrl = detail.coverUrl ?? ''
    form.noticeType = detail.noticeType
    form.isTop = detail.isTop ?? 0
    dialogVisible.value = true
  } catch (e) {
    // 请求失败时 request.js 已经弹过错误提示,这里不再重复提示
  }
}

/**
 * 把一条草稿 / 已下架的公告发出去。
 *
 * 与"新建发布"共用一个权限码(announcement:add),但**会重发通知** ——
 * 确认框上必须写清楚,不能让管理员以为只是改了个状态。
 *
 * 请求放在 try/catch 里、列表刷新放在 finally 里,和后台留言巡查那一页
 * 是同一个写法:不 catch 的话失败会变成一条未处理的 Promise 拒绝
 * (控制台里一个红字,页面上什么都没变);不放 finally 的话,
 * 失败之后列表就停在旧数据上,管理员会以为操作没生效而再点一次。
 */
async function handlePublish(row) {
  const audience = row.villageId
    ? `${row.villageName || '本村'}的用户`
    : '所有村组的用户'
  try {
    await ElMessageBox.confirm(
      `发布后 ${audience}都能看到,并且会给每个人各发一条站内通知。`,
      '确认发布这条公告?',
      { confirmButtonText: '确认发布', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  publishing.value = row.id
  try {
    await publishExistingAnnouncement(row.id)
    ElMessage.success('公告已发布')
  } catch (e) {
    // 提示由 request.js 统一弹
  } finally {
    publishing.value = null
    await fetchList()
  }
}

/** 下架。不发通知 —— 已经推出去的通知收不回来 */
async function handleOffline(row) {
  try {
    await ElMessageBox.confirm(
      '下架后前台立刻看不到这条公告,后台仍然查得到,可以再编辑后重新发布。不会给已收到的用户补发通知。',
      '确认下架?',
      { confirmButtonText: '确认下架', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  publishing.value = row.id
  try {
    await offlineAnnouncement(row.id)
    ElMessage.success('公告已下架')
  } catch (e) {
    // 提示由 request.js 统一弹
  } finally {
    publishing.value = null
    await fetchList()
  }
}

async function handleCoverUpload(options) {
  uploading.value = true
  try {
    form.coverUrl = await uploadImage(options.file)
    options.onSuccess?.(form.coverUrl)
  } catch (e) {
    options.onError?.(e)
  } finally {
    uploading.value = false
  }
}

/**
 * 提交弹窗。
 *
 * @param action 新建时是 `'draft'`(存草稿)或 `'publish'`(直接发布);
 *               编辑时固定是 `'save'` —— 编辑不改状态,
 *               "把一条草稿发出去"是列表上「发布」按钮的事。
 *
 * 两种失败的处理方式不同,这点是刻意的:
 *   校验不过 → 静默返回,表单自己会把红字标在出问题的那一栏上;
 *   请求失败 → 也静默关闭 loading,但不关弹窗 ——
 *              用户填的内容还在里面,改一下(比如换个标题)就能再试一次。
 */
async function submit(action) {
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  submitting.value = action
  try {
    const payload = {
      title: form.title.trim(),
      content: form.content,
      // 传 undefined 表示"这一列要清空":JSON 里不出现这个字段,
      // 后端拿到的就是 null,再逐列 set 进去覆盖掉原来的封面。
      // 若写成空字符串,后端存的是 '' 而不是 NULL,封面列上就有了两种"没有值"。
      coverUrl: form.coverUrl || undefined,
      noticeType: form.noticeType,
      isTop: form.isTop
    }
    if (isEditing.value) {
      await updateAnnouncement(editingId.value, payload)
      ElMessage.success('公告已更新')
    } else {
      await publishAnnouncement({ ...payload, asDraft: action === 'draft' })
      ElMessage.success(action === 'draft' ? '已存为草稿,前台还看不到' : '公告已发布')
    }
    dialogVisible.value = false
    query.pageNum = 1
    await fetchList()
  } catch (e) {
    // 提示由 request.js 统一弹;这里只保证弹窗不关、按钮能再点
  } finally {
    submitting.value = null
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="admin-announcement">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        当前数据范围:<b>{{ scopeText }}</b>。
        <b>超级管理员发布的公告是全平台的,村级管理员发布的挂本村</b> ——
        这个归属由服务端决定,不受前端参数影响。
        正文按<b>纯文本</b>保存和展示(保留换行,不解释 HTML),
        发布前会过一次敏感词检查,命中拦截类词会被整条拒绝。
        <b>已发布</b>和<b>已下架</b>的公告都能编辑;<b>下架</b>只是让它从前台消失,
        后台仍然查得到,改完可以重新发布。
      </template>
    </el-alert>

    <div class="card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索公告标题"
        clearable
        style="width: 200px"
        @keyup.enter="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.noticeType" placeholder="公告类型" clearable style="width: 160px">
        <el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>

      <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px">
        <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>

      <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>

      <div class="spacer" />

      <!-- 权限码来自 sys_menu:announcement:add -->
      <el-button
        v-hasPerm="'announcement:add'"
        type="primary"
        :icon="Plus"
        @click="openCreate"
      >
        发布公告
      </el-button>
    </div>

    <div class="card table-card">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="标题" min-width="260">
          <template #default="{ row }">
            <div class="title-line">
              <el-tag v-if="row.isTop === 1" size="small" type="danger" effect="dark">置顶</el-tag>
              <span class="title-text">{{ row.title }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="类型" width="140">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="ANNOUNCEMENT_TYPE[row.noticeType]?.type">
              {{ ANNOUNCEMENT_TYPE[row.noticeType]?.label || '—' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="归属" width="120">
          <template #default="{ row }">
            <el-tag v-if="!row.villageId" size="small" type="info" effect="plain">全平台</el-tag>
            <span v-else class="text-sm">{{ row.villageName || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="发布人" min-width="130">
          <template #default="{ row }">
            <div>{{ row.publisherName }}</div>
            <div class="text-xs text-secondary">
              {{ formatDateTime(row.publishTime || row.createTime) }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="浏览量" width="100" align="center">
          <template #default="{ row }">
            <span class="views">
              <el-icon><View /></el-icon>
              <span class="num">{{ row.viewCount || 0 }}</span>
            </span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="ANNOUNCEMENT_STATUS[row.status]?.type" size="small" effect="dark">
              {{ ANNOUNCEMENT_STATUS[row.status]?.label }}
            </el-tag>
          </template>
        </el-table-column>

        <!--
          操作列。

          「编辑」和权限判断都用 v-if + userStore.hasPerm,而不是 v-hasPerm 指令 ——
          指令只有 mounted 钩子(见 directives/hasPerm.js),它会在挂载时
          <b>把节点从 DOM 里摘掉</b>。这里两个按钮的显隐还跟着 row.status 走,
          用指令的话状态一变节点就找不回来了;资源管理页的下架按钮
          也是同样的写法。
        -->
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="userStore.hasPerm('announcement:edit')"
              link
              type="primary"
              size="small"
              @click="openEdit(row)"
            >编辑</el-button>

            <!--
              已发布 → 下架;草稿 / 已下架 → 发布。

              状态先分叉、权限再分叉,顺序不能反过来:写成
              v-if="status===1 && hasPerm(offline)" + v-else-if="hasPerm(add)"
              的话,一个没有 offline 权限的人看已发布的公告时,
              第一个分支不成立就会掉进第二个分支,看到并点到一个
              "发布"按钮 —— 而那条本来就已发布,点下去后端只会回 400。
              先按状态把两个互斥的动作挑出来,再看有没有资格做。
            -->
            <template v-if="row.status === 1">
              <el-button
                v-if="userStore.hasPerm('announcement:offline')"
                link
                type="warning"
                size="small"
                :loading="publishing === row.id"
                @click="handleOffline(row)"
              >下架</el-button>
              <span v-else class="text-xs text-secondary">—</span>
            </template>
            <template v-else>
              <el-button
                v-if="userStore.hasPerm('announcement:add')"
                link
                type="success"
                size="small"
                :loading="publishing === row.id"
                @click="handlePublish(row)"
              >发布</el-button>
              <span v-else class="text-xs text-secondary">—</span>
            </template>
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

    <!-- ── 新建 / 编辑弹窗(同一个表单,靠 editingId 区分) ── -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEditing ? '编辑公告' : '发布公告'"
      width="680px"
    >
      <!--
        弹窗上那句话必须区分四种情况,因为它回答的是同一个问题:
        "我按下这个按钮之后,会发生什么?"
          新建       —— 两个按钮:直接发出去并通知所有人,或者先存成草稿
          编辑已发布 —— 只是改了内容,已经收到通知的人不会收到第二次
          编辑未发布 —— 还是没发出去,要再点列表上的「发布」才会通知
        四种说成一样,管理员就会误以为编辑也会重发通知(或者误以为
        新建只能直接发),两种误会都会导致"该通知的没通知"。
      -->
      <el-alert type="warning" :closable="false" show-icon class="publish-tip">
        <template #title>
          <span v-if="!isEditing">
            <b>{{ audienceText }}</b>。
            「确认发布」立即生效并给这些用户各发一条站内通知;
            「存草稿」只落库、不发通知,前台也看不到,
            之后可以在列表上点「发布」再发出去。
          </span>
          <span v-else-if="editingStatus === 1">
            这条公告<b>已经发布过</b>。保存只改内容、改完立刻生效,
            <b>不会重新给用户发通知</b>。要让所有人再看一遍,
            应当先下架、改完再发布。
          </span>
          <span v-else>
            这条公告<b>还没有发布</b>,前台看不到。保存后它仍然是未发布状态,
            要回到列表上点「发布」才会推送给用户。
          </span>
        </template>
      </el-alert>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" maxlength="100" show-word-limit
                    placeholder="例如:2026 年农机购置补贴申报开始了" />
        </el-form-item>

        <el-form-item label="类型" prop="noticeType">
          <el-select v-model="form.noticeType" placeholder="请选择" style="width: 220px">
            <el-option
              v-for="t in availableTypes"
              :key="t.value"
              :label="t.label"
              :value="t.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="封面图">
          <div class="cover-row">
            <el-image
              v-if="form.coverUrl"
              :src="form.coverUrl"
              fit="cover"
              class="cover-preview"
            />
            <el-upload
              :show-file-list="false"
              accept="image/*"
              :http-request="handleCoverUpload"
            >
              <el-button :icon="Plus" :loading="uploading">
                {{ uploading ? '上传中…' : (form.coverUrl ? '换一张' : '上传封面') }}
              </el-button>
            </el-upload>
            <el-button v-if="form.coverUrl" link type="danger" @click="form.coverUrl = ''">
              移除
            </el-button>
          </div>
        </el-form-item>

        <el-form-item label="置顶">
          <el-switch v-model="form.isTop" :active-value="1" :inactive-value="0" />
          <span class="form-hint">置顶的公告会排在列表最前面</span>
        </el-form-item>

        <el-form-item label="正文" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="10"
            resize="none"
            placeholder="正文按纯文本保存,换行会原样保留。&#10;不要粘贴 HTML 标签 —— 它们会被当成普通文字显示出来。"
          />
        </el-form-item>
      </el-form>

      <!--
        新建时两个出口,编辑时一个。
        「存草稿」不是次等动作,所以摆在主按钮左边而不是做成一个链接 ——
        两个按钮都会改变数据库,差别只在"要不要现在就让人看见",
        这个差别由按钮上的字说清楚就够了,不需要靠视觉权重再暗示一遍。
      -->
      <template #footer>
        <el-button
          :disabled="submitting !== null"
          @click="dialogVisible = false"
        >取消</el-button>

        <el-button
          v-if="!isEditing"
          :loading="submitting === 'draft'"
          :disabled="submitting === 'publish'"
          @click="submit('draft')"
        >存草稿</el-button>

        <el-button
          type="primary"
          :loading="submitting === (isEditing ? 'save' : 'publish')"
          :disabled="submitting === 'draft'"
          @click="submit(isEditing ? 'save' : 'publish')"
        >
          {{ isEditing ? '保存' : '确认发布' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.scope-alert {
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

.spacer {
  flex: 1;
}

.table-card {
  padding: var(--space-md) var(--space-lg);
}

.title-line {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.title-text {
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}

.views {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--color-text-secondary);
}

.num {
  font-family: var(--font-family-number);
  font-size: var(--font-size-sm);
}

.pager {
  margin-top: var(--space-md);
  justify-content: flex-end;
}

/* ── 弹窗 ──────────────────────────────────────────────────── */
.publish-tip {
  margin-bottom: var(--space-md);
}

.cover-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.cover-preview {
  width: 96px;
  height: 60px;
  border-radius: var(--radius-sm);
}

.form-hint {
  margin-left: var(--space-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}
</style>
