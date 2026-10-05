<script setup>
/**
 * 个人中心 —— 实名认证。
 *
 * 实名是这个平台的门槛:不实名不能发布资源、不能下单。
 * 所以这个页面要把"为什么要实名"和"当前卡在哪一步"讲清楚,
 * 而不是只丢一个表单给用户填。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getProfile, submitRealname } from '@/api/audit'
import { changePassword } from '@/api/auth'
import { uploadImage } from '@/api/resource'
import { useUserStore } from '@/stores/user'
import { formatMoney } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(true)
const submitting = ref(false)
const uploading = ref('')   // 记录正在上传哪一面:'front' | 'back' | ''

const profile = ref(null)
const formRef = ref(null)

const form = reactive({
  realName: '',
  idCard: '',
  idCardFront: '',
  idCardBack: ''
})

const rules = {
  realName: [
    { required: true, message: '请输入真实姓名', trigger: 'blur' },
    { max: 20, message: '姓名不能超过 20 个字', trigger: 'blur' }
  ],
  idCard: [
    { required: true, message: '请输入身份证号', trigger: 'blur' },
    {
      pattern: /^[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[0-9Xx]$/,
      message: '身份证号格式不正确',
      trigger: 'blur'
    }
  ],
  idCardFront: [{ required: true, message: '请上传身份证正面照', trigger: 'change' }],
  idCardBack: [{ required: true, message: '请上传身份证反面照', trigger: 'change' }]
}

// ── 修改密码 ──────────────────────────────────────────────
const pwdFormRef = ref(null)
const pwdSubmitting = ref(false)

const pwdForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const pwdRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  // 长度规则与后端 ChangePasswordDTO、注册页保持一致
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度需在 6-20 位之间', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== pwdForm.newPassword) {
          callback(new Error('两次输入的新密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

/** 实名状态:0未提交 1待审核 2已认证 3已驳回 */
const realnameStatus = computed(() => profile.value?.realnameStatus ?? 0)

/** 只有「未提交」和「已驳回」才需要显示表单 */
const needSubmit = computed(() => realnameStatus.value === 0 || realnameStatus.value === 3)

const statusInfo = computed(() => ({
  0: {
    type: 'info',
    title: '尚未实名认证',
    desc: '完成实名认证后才能发布资源和下单。认证信息由你所属村组的村级管理员审核。'
  },
  1: {
    type: 'warning',
    title: '实名认证审核中',
    desc: '已提交,正在等待村级管理员审核。通常 1-2 个工作日内完成,请耐心等待。'
  },
  2: {
    type: 'success',
    title: '已完成实名认证',
    desc: '你现在可以发布闲置资源、下单租用或购买了。'
  },
  3: {
    type: 'danger',
    title: '实名认证未通过',
    desc: '请根据下方的原因修改后重新提交。'
  }
}[realnameStatus.value]))

async function loadProfile() {
  loading.value = true
  try {
    profile.value = await getProfile()
    // 已填过的姓名带出来,减少重复输入
    if (profile.value?.realName) {
      form.realName = profile.value.realName
    }
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

/** 上传身份证照片 */
async function handleUpload(options, side) {
  uploading.value = side
  try {
    const data = await uploadImage(options.file)
    if (side === 'front') {
      form.idCardFront = data.url
    } else {
      form.idCardBack = data.url
    }
    // 手动触发校验,否则「上传了但表单还标红」会让人困惑
    formRef.value?.validateField(side === 'front' ? 'idCardFront' : 'idCardBack')
    ElMessage.success('上传成功')
  } catch (e) {
    // 拦截器已提示
  } finally {
    uploading.value = ''
  }
}

function beforeUpload(file) {
  const allowed = ['image/jpeg', 'image/png', 'image/webp']
  if (!allowed.includes(file.type)) {
    ElMessage.error('证件照请上传 jpg / png / webp 格式')
    return false
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('图片不能超过 5MB')
    return false
  }
  return true
}

async function handleSubmit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    ElMessage.warning('请检查表单中标红的内容')
    return
  }

  submitting.value = true
  try {
    await submitRealname({ ...form })
    ElMessage.success('已提交,请等待村级管理员审核')
    await loadProfile()
    // 实名状态变了,刷新一下用户信息(顶栏的"未实名"角标要消失)
    await userStore.fetchUserInfo().catch(() => {})
  } catch (e) {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

/**
 * 身份证号已经是后端脱敏后的值(形如 510124********1234)。
 *
 * 脱敏放在后端而不是前端:前端脱敏意味着完整号码已经下发到了浏览器、
 * 存进了 localStorage,只是"没显示出来"而已 —— 那不算保护。
 */
function displayIdCard() {
  return profile.value?.idCard || '—'
}

/** 身份证号输入时自动大写 X */
function normalizeIdCard(value) {
  form.idCard = value.trim().toUpperCase()
}

/**
 * 修改密码。
 *
 * 改完清掉本地登录态跳回登录页 —— 但这只是"提醒本人换新密码重新进",
 * 不是安全边界:站点是纯 JWT 无状态鉴权,旧 token 在过期前依然有效,
 * 服务端没有会话或黑名单可以作废它。要真正把别人踢下线,
 * 得引入 token 版本号或黑名单,不在本页范围内。
 */
async function handleChangePassword() {
  if (!pwdFormRef.value) return
  try {
    await pwdFormRef.value.validate()
  } catch {
    ElMessage.warning('请检查表单中标红的内容')
    return
  }

  pwdSubmitting.value = true
  try {
    // 只传原密码和新密码:改哪个账号由 token 决定,前端给不了 userId
    await changePassword({
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword
    })
    ElMessage.success('密码已修改,请用新密码重新登录')
    userStore.clearAuth()
    router.push('/login')
  } catch (e) {
    // 拦截器已提示
  } finally {
    pwdSubmitting.value = false
  }
}

onMounted(loadProfile)
</script>

<template>
  <div class="page-container">
    <div v-loading="loading" class="profile-wrap">
      <header class="page-head">
        <h1 class="page-title">个人中心</h1>
        <p class="page-sub">管理你的账号信息与实名认证</p>
      </header>

      <!-- ── 账号概览 ──────────────────────────────────────── -->
      <div class="card account-card">
        <el-avatar :size="56" :src="profile?.avatar">
          {{ (profile?.realName || profile?.phone || '?').charAt(0) }}
        </el-avatar>
        <div class="account-info">
          <div class="account-name">
            {{ profile?.realName || '未填写姓名' }}
            <el-tag v-if="profile?.superAdmin" size="small" type="danger" effect="dark">
              超级管理员
            </el-tag>
          </div>
          <div class="account-meta num">{{ profile?.phone }}</div>
          <div class="account-meta">
            {{ profile?.townName }} {{ profile?.villageName || '未绑定村组' }}
          </div>
        </div>
        <div class="account-stats">
          <div class="stat">
            <div class="stat-value num" :class="{ 'is-low': (profile?.creditScore ?? 100) < 60 }">
              {{ profile?.creditScore ?? '—' }}
            </div>
            <div class="stat-label">信用分</div>
          </div>
          <div class="stat">
            <div class="stat-value num">¥{{ formatMoney(profile?.balance) }}</div>
            <div class="stat-label">账户余额</div>
          </div>
        </div>
      </div>

      <!-- ── 实名认证 ──────────────────────────────────────── -->
      <div class="card">
        <h2 class="section-title">实名认证</h2>

        <el-alert
          :type="statusInfo.type"
          :closable="false"
          show-icon
          class="status-alert"
        >
          <template #title>
            <b>{{ statusInfo.title }}</b>
          </template>
          <template #default>
            <div>{{ statusInfo.desc }}</div>

            <!-- 已认证的显示脱敏信息 -->
            <div v-if="realnameStatus === 2" class="verified-info">
              <span>姓名:<b>{{ profile?.realName }}</b></span>
              <span>身份证号:<b class="num">{{ displayIdCard() }}</b></span>
            </div>
          </template>
        </el-alert>

        <!-- 表单:仅未提交/已驳回时显示 -->
        <el-form
          v-if="needSubmit"
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          class="realname-form"
        >
          <div class="form-row">
            <el-form-item label="真实姓名" prop="realName">
              <el-input v-model="form.realName" placeholder="与身份证上一致" maxlength="20" />
            </el-form-item>

            <el-form-item label="身份证号" prop="idCard">
              <el-input
                v-model="form.idCard"
                placeholder="18 位身份证号"
                maxlength="18"
                @blur="normalizeIdCard(form.idCard)"
              />
            </el-form-item>
          </div>

          <div class="form-row">
            <el-form-item label="身份证正面照(人像面)" prop="idCardFront">
              <el-upload
                class="id-uploader"
                :show-file-list="false"
                :before-upload="beforeUpload"
                :http-request="(o) => handleUpload(o, 'front')"
                accept="image/*"
              >
                <div class="upload-box" :class="{ 'has-image': form.idCardFront }">
                  <img v-if="form.idCardFront" :src="form.idCardFront" alt="身份证正面" />
                  <div v-else class="upload-placeholder">
                    <el-icon :size="22">
                      <Loading v-if="uploading === 'front'" class="is-spinning" />
                      <Plus v-else />
                    </el-icon>
                    <span>上传正面照</span>
                  </div>
                </div>
              </el-upload>
            </el-form-item>

            <el-form-item label="身份证反面照(国徽面)" prop="idCardBack">
              <el-upload
                class="id-uploader"
                :show-file-list="false"
                :before-upload="beforeUpload"
                :http-request="(o) => handleUpload(o, 'back')"
                accept="image/*"
              >
                <div class="upload-box" :class="{ 'has-image': form.idCardBack }">
                  <img v-if="form.idCardBack" :src="form.idCardBack" alt="身份证反面" />
                  <div v-else class="upload-placeholder">
                    <el-icon :size="22">
                      <Loading v-if="uploading === 'back'" class="is-spinning" />
                      <Plus v-else />
                    </el-icon>
                    <span>上传反面照</span>
                  </div>
                </div>
              </el-upload>
            </el-form-item>
          </div>

          <el-button
            type="primary"
            size="large"
            :loading="submitting"
            class="submit-btn"
            @click="handleSubmit"
          >
            提交实名认证
          </el-button>

          <p class="form-tip">
            提交后由你所属村组的村级管理员审核。你的身份信息仅用于实名核验,不会对外公开展示。
          </p>
        </el-form>

        <!-- 审核中:给一个"等待"的视觉状态,而不是空白 -->
        <div v-else-if="realnameStatus === 1" class="waiting-block">
          <el-icon :size="32" class="waiting-icon"><Clock /></el-icon>
          <p>资料已提交,等待管理员审核</p>
          <el-button link type="primary" @click="router.push('/notice')">
            去消息中心看看 →
          </el-button>
        </div>
      </div>

      <!-- ── 修改密码 ──────────────────────────────────────── -->
      <div class="card">
        <h2 class="section-title">修改密码</h2>

        <el-form
          ref="pwdFormRef"
          :model="pwdForm"
          :rules="pwdRules"
          label-position="top"
          class="pwd-form"
        >
          <el-form-item label="原密码" prop="oldPassword">
            <el-input
              v-model="pwdForm.oldPassword"
              type="password"
              show-password
              placeholder="请输入当前密码"
              autocomplete="current-password"
            />
          </el-form-item>

          <div class="form-row">
            <el-form-item label="新密码" prop="newPassword">
              <el-input
                v-model="pwdForm.newPassword"
                type="password"
                show-password
                placeholder="6-20 位"
                maxlength="20"
                autocomplete="new-password"
              />
            </el-form-item>

            <el-form-item label="确认新密码" prop="confirmPassword">
              <el-input
                v-model="pwdForm.confirmPassword"
                type="password"
                show-password
                placeholder="再输入一次"
                maxlength="20"
                autocomplete="new-password"
              />
            </el-form-item>
          </div>

          <el-button
            type="primary"
            size="large"
            :loading="pwdSubmitting"
            class="submit-btn"
            @click="handleChangePassword"
          >
            确认修改
          </el-button>

          <p class="form-tip">
            修改成功后需要用新密码重新登录。如果密码是管理员代设的初始密码,建议尽早改掉。
          </p>
        </el-form>
      </div>
    </div>
  </div>
</template>

<style scoped>
.profile-wrap {
  max-width: 720px;
  margin: 0 auto;
  min-height: 400px;
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.page-head {
  margin-bottom: var(--space-xs);
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

.card {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-lg);
}

/* ── 账号概览 ────────────────────────────────────────────── */
.account-card {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  flex-wrap: wrap;
}

.account-info {
  flex: 1;
  min-width: 160px;
}

.account-name {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.account-meta {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  margin-top: 2px;
}

.account-stats {
  display: flex;
  gap: var(--space-xl);
}

.stat {
  text-align: right;
}

.stat-value {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-success);
}

.stat-value.is-low {
  color: var(--color-danger);
}

.stat-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* ── 实名认证 ────────────────────────────────────────────── */
.section-title {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--space-md);
}

.status-alert {
  margin-bottom: var(--space-lg);
}

.status-alert :deep(.el-alert__title) {
  font-size: var(--font-size-base);
}

.verified-info {
  margin-top: var(--space-sm);
  display: flex;
  gap: var(--space-lg);
  flex-wrap: wrap;
  font-size: var(--font-size-sm);
}

.realname-form {
  margin-top: var(--space-md);
}

.pwd-form {
  margin-top: var(--space-md);
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-md);
}

.id-uploader :deep(.el-upload) {
  width: 100%;
}

.upload-box {
  width: 100%;
  aspect-ratio: 8 / 5;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-info-light);
  transition: border-color var(--duration-fast);
}

.upload-box:hover {
  border-color: var(--color-primary);
}

.upload-box.has-image {
  border-style: solid;
  border-color: var(--color-border);
}

.upload-box img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.upload-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.is-spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.submit-btn {
  width: 100%;
  margin-top: var(--space-sm);
}

.form-tip {
  margin-top: var(--space-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: 1.7;
}

/* ── 等待审核 ────────────────────────────────────────────── */
.waiting-block {
  text-align: center;
  padding: var(--space-xl) 0;
  color: var(--color-text-secondary);
}

.waiting-icon {
  color: var(--color-warning);
  margin-bottom: var(--space-sm);
}

.waiting-block p {
  margin-bottom: var(--space-sm);
}

/* ── 响应式 ──────────────────────────────────────────────── */
@media (max-width: 640px) {
  .form-row {
    grid-template-columns: 1fr;
  }

  .account-stats {
    gap: var(--space-lg);
  }
}
</style>
