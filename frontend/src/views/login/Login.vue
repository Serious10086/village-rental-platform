<script setup>
/**
 * 登录 / 注册页。
 *
 * 左侧是品牌区,右侧是表单。窄屏时左侧收起,只留表单 ——
 * 这样手机上也用得起来("响应式设计"是验收项之一)。
 */
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Iphone, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { usePermissionStore } from '@/stores/permission'
import { register as registerApi, getVillageList } from '@/api/auth'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const permissionStore = usePermissionStore()

/** 当前是登录还是注册 */
const activeTab = ref('login')
const loading = ref(false)

const loginForm = reactive({
  phone: '',
  password: ''
})

const registerForm = reactive({
  phone: '',
  password: '',
  confirmPassword: '',
  realName: '',
  villageId: null,
  userType: 2
})

const villages = ref([])

// 注册时的表单校验规则
const registerRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  realName: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { max: 20, message: '姓名不能超过 20 个字', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度需在 6-20 位之间', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== registerForm.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

const registerFormRef = ref(null)

// 只把村组(二级节点)做成可选项 —— 乡镇是行政单位,用户应归属到村
const villageOptions = computed(() =>
  villages.value.filter((v) => v.parentId !== null)
)

onMounted(async () => {
  try {
    villages.value = await getVillageList()
  } catch (e) {
    console.warn('村组列表加载失败,注册功能将不可用', e)
  }
})

/** 登录 */
async function handleLogin() {
  if (!loginForm.phone || !loginForm.password) {
    ElMessage.warning('请输入手机号和密码')
    return
  }
  loading.value = true
  try {
    const info = await userStore.login(loginForm)
    ElMessage.success(`欢迎回来,${info.realName || info.phone}`)

    // 登录后清掉权限路由的加载标记,让守卫重新按新身份生成
    permissionStore.reset()

    // 支持登录后跳回原来想访问的页面
    const redirect = route.query.redirect
    if (redirect) {
      await router.replace(String(redirect))
    } else {
      await router.replace(info.superAdmin || info.roles?.includes('VILLAGE_ADMIN')
        ? '/admin/dashboard'
        : '/')
    }
  } catch (e) {
    // 错误提示已经由 axios 拦截器统一弹过了,这里不用重复弹
  } finally {
    loading.value = false
  }
}

/** 注册 */
async function handleRegister() {
  if (!registerFormRef.value) return
  try {
    await registerFormRef.value.validate()
  } catch (e) {
    return // 校验不通过,el-form 会自己标红
  }

  loading.value = true
  try {
    await registerApi({
      phone: registerForm.phone,
      password: registerForm.password,
      realName: registerForm.realName,
      villageId: registerForm.villageId,
      userType: registerForm.userType
    })
    ElMessage.success('注册成功,请使用新账号登录')

    // 注册成功后自动切到登录页并带上手机号,省得用户再输一遍
    loginForm.phone = registerForm.phone
    loginForm.password = ''
    activeTab.value = 'login'
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

/** 一键填入测试账号(答辩演示时省时间) */
function fillAccount(phone) {
  activeTab.value = 'login'
  loginForm.phone = phone
  loginForm.password = '123456'
}
</script>

<template>
  <div class="login-page">
    <!-- ── 左侧品牌区 ──────────────────────────────────────── -->
    <aside class="brand-panel">
      <div class="brand-inner">
        <div class="brand-mark">
          <el-icon :size="30"><OfficeBuilding /></el-icon>
        </div>
        <h1 class="brand-title">乡村闲置资源租售平台</h1>
        <p class="brand-sub">让村里的农机、场地、工具流转起来</p>

        <ul class="feature-list">
          <li>
            <el-icon><Goods /></el-icon>
            <span>农用机械、交通工具、房屋场地、工具设备</span>
          </li>
          <li>
            <el-icon><DocumentChecked /></el-icon>
            <span>村级管理员审核把关,资源真实可信</span>
          </li>
          <li>
            <el-icon><Medal /></el-icon>
            <span>交易履约沉淀信用分,守信的人更好办事</span>
          </li>
          <li>
            <el-icon><MagicStick /></el-icon>
            <span>AI 智能客服,随时解答租售疑问</span>
          </li>
        </ul>
      </div>
    </aside>

    <!-- ── 右侧表单区 ──────────────────────────────────────── -->
    <main class="form-panel">
      <div class="form-card">
        <el-tabs v-model="activeTab" class="auth-tabs">
          <!-- 登录 -->
          <el-tab-pane label="登录" name="login">
            <el-form label-position="top" @submit.prevent="handleLogin">
              <el-form-item label="手机号">
                <el-input
                  v-model="loginForm.phone"
                  placeholder="请输入手机号"
                  size="large"
                  maxlength="11"
                  :prefix-icon="Iphone"
                  @keyup.enter="handleLogin"
                />
              </el-form-item>
              <el-form-item label="密码">
                <el-input
                  v-model="loginForm.password"
                  type="password"
                  placeholder="请输入密码"
                  size="large"
                  show-password
                  :prefix-icon="Lock"
                  @keyup.enter="handleLogin"
                />
              </el-form-item>
              <el-button
                type="primary"
                size="large"
                class="submit-btn"
                :loading="loading"
                @click="handleLogin"
              >
                登录
              </el-button>
            </el-form>

            <!-- 测试账号快捷入口:答辩演示时很有用 -->
            <div class="quick-login">
              <div class="quick-title">演示账号(密码均为 123456)</div>
              <div class="quick-btns">
                <el-button size="small" plain @click="fillAccount('13800000000')">
                  平台管理员
                </el-button>
                <el-button size="small" plain @click="fillAccount('13800000001')">
                  战旗村支书
                </el-button>
                <el-button size="small" plain @click="fillAccount('13800000002')">
                  先锋村主任
                </el-button>
                <el-button size="small" plain @click="fillAccount('13800000003')">
                  村民
                </el-button>
              </div>
            </div>
          </el-tab-pane>

          <!-- 注册 -->
          <el-tab-pane label="注册" name="register">
            <el-form
              ref="registerFormRef"
              :model="registerForm"
              :rules="registerRules"
              label-position="top"
            >
              <el-form-item label="手机号" prop="phone">
                <el-input
                  v-model="registerForm.phone"
                  placeholder="请输入手机号"
                  size="large"
                  maxlength="11"
                />
              </el-form-item>

              <el-form-item label="姓名" prop="realName">
                <el-input
                  v-model="registerForm.realName"
                  placeholder="请输入真实姓名"
                  size="large"
                />
              </el-form-item>

              <el-form-item label="所属村组" prop="villageId">
                <el-select
                  v-model="registerForm.villageId"
                  placeholder="请选择所属村组"
                  size="large"
                  style="width: 100%"
                  clearable
                >
                  <el-option
                    v-for="v in villageOptions"
                    :key="v.id"
                    :label="v.villageName"
                    :value="v.id"
                  />
                </el-select>
              </el-form-item>

              <el-form-item label="注册身份" prop="userType">
                <el-radio-group v-model="registerForm.userType">
                  <el-radio :value="1">我想出租/出售闲置资源</el-radio>
                  <el-radio :value="2">我想租用/购买资源</el-radio>
                </el-radio-group>
              </el-form-item>

              <el-form-item label="密码" prop="password">
                <el-input
                  v-model="registerForm.password"
                  type="password"
                  placeholder="6-20 位密码"
                  size="large"
                  show-password
                />
              </el-form-item>

              <el-form-item label="确认密码" prop="confirmPassword">
                <el-input
                  v-model="registerForm.confirmPassword"
                  type="password"
                  placeholder="请再次输入密码"
                  size="large"
                  show-password
                />
              </el-form-item>

              <el-button
                type="primary"
                size="large"
                class="submit-btn"
                :loading="loading"
                @click="handleRegister"
              >
                注册
              </el-button>

              <p class="form-tip">
                注册后即可浏览资源。发布资源或下单前,需要先在个人中心提交实名认证。
              </p>
            </el-form>
          </el-tab-pane>
        </el-tabs>
      </div>
    </main>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100%;
  display: grid;
  grid-template-columns: 1.1fr 1fr;
}

/* ── 左侧品牌区 ──────────────────────────────────────────── */
.brand-panel {
  background: linear-gradient(150deg, #1e3a8a 0%, #2563eb 55%, #3b82f6 100%);
  color: #fff;
  display: flex;
  align-items: center;
  padding: var(--space-2xl);
  position: relative;
  overflow: hidden;
}

/* 一层很淡的斜纹肌理,避免大面积纯色显得单调 */
.brand-panel::after {
  content: '';
  position: absolute;
  inset: 0;
  background-image: repeating-linear-gradient(
    45deg,
    rgba(255, 255, 255, 0.04) 0px,
    rgba(255, 255, 255, 0.04) 1px,
    transparent 1px,
    transparent 12px
  );
  pointer-events: none;
}

.brand-inner {
  position: relative;
  z-index: 1;
  max-width: 460px;
}

.brand-mark {
  width: 60px;
  height: 60px;
  border-radius: var(--radius-lg);
  background: rgba(255, 255, 255, 0.18);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: var(--space-lg);
}

.brand-title {
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.25;
}

.brand-sub {
  margin-top: var(--space-sm);
  font-size: var(--font-size-md);
  color: rgba(255, 255, 255, 0.82);
}

.feature-list {
  margin-top: var(--space-2xl);
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.feature-list li {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: var(--font-size-sm);
  color: rgba(255, 255, 255, 0.9);
}

/* ── 右侧表单区 ──────────────────────────────────────────── */
.form-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-xl);
  background: var(--color-bg-card);
  overflow-y: auto;
}

.form-card {
  width: 100%;
  max-width: 400px;
}

.auth-tabs :deep(.el-tabs__header) {
  margin-bottom: var(--space-lg);
}

.auth-tabs :deep(.el-tabs__item) {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
}

.submit-btn {
  width: 100%;
  margin-top: var(--space-sm);
  font-weight: var(--font-weight-medium);
}

/* ── 演示账号快捷区 ──────────────────────────────────────── */
.quick-login {
  margin-top: var(--space-xl);
  padding-top: var(--space-md);
  border-top: 1px dashed var(--color-border);
}

.quick-title {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  margin-bottom: var(--space-sm);
}

.quick-btns {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.form-tip {
  margin-top: var(--space-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

/* ── 响应式:窄屏收起品牌区 ──────────────────────────────── */
@media (max-width: 900px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .brand-panel {
    padding: var(--space-xl);
  }

  .brand-title {
    font-size: 24px;
  }

  .feature-list {
    display: none;
  }
}
</style>
