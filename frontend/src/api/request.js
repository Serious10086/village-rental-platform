import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * Axios 实例 —— 全站唯一的 HTTP 出口。
 *
 * 统一在这里处理两件事,业务代码里就不用管:
 *   1. 请求拦截:自动带上 JWT token
 *   2. 响应拦截:统一判断业务 code,失败时弹提示
 */
const request = axios.create({
  // 走 Vite 的代理,/api 会被转发到 http://localhost:8080
  // 这样开发期不用拼绝对地址,也就没有跨域问题
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json;charset=UTF-8'
  }
})

// ── 请求拦截器 ──────────────────────────────────────────────
request.interceptors.request.use(
  (config) => {
    // token 从 localStorage 取(而不是从 Pinia store),
    // 避免拦截器里引用 store 导致的循环依赖
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    console.error('请求发送失败:', error)
    return Promise.reject(error)
  }
)

// ── 响应拦截器 ──────────────────────────────────────────────
request.interceptors.response.use(
  (response) => {
    const res = response.data

    // 后端统一返回 { code, message, data }
    // code === 200 才算成功
    if (res.code === 200) {
      // 直接把 data 抛出去,业务代码里写 const list = await getResourceList()
      // 而不用写 res.data.data
      return res.data
    }

    // ── 业务失败 ────────────────────────────────────────────
    // 401 需要特殊处理:登录态失效,清掉 token 并跳登录页
    if (res.code === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      ElMessage.warning(res.message || '登录已失效,请重新登录')
      // 这里用 location 而不是 router,是为了避开循环依赖:
      // router → stores/permission → api/auth → api/request,
      // 如果 request 再 import router,就形成了环。
      //
      // 判断当前是否已在登录页要用 pathname —— 本项目用的是 history 模式,
      // location.hash 永远是空字符串,拿它判断会永远为真(每次都强制刷新跳转)。
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login'
      }
      return Promise.reject(new Error(res.message || '登录已失效'))
    }

    // 其他业务错误统一弹提示
    //
    // silent:调用方在 config 里传了 silent: true 就不弹。给的是**用户没主动
    // 发起的请求**用的 —— 比如浏览行为上报:用户只是点开一个资源看,
    // 后台顺手记一笔"他看过什么",这一笔失败了弹一句"操作失败",
    // 用户会莫名其妙(他没做任何操作)。旁路请求就该悄悄地失败。
    if (!response.config?.silent) {
      ElMessage.error(res.message || '操作失败')
    }
    return Promise.reject(new Error(res.message || '操作失败'))
  },
  (error) => {
    // ── HTTP 层面的错误(网络断开、超时、5xx) ────────────────
    let message = '网络异常,请稍后重试'

    if (error.code === 'ECONNABORTED') {
      message = '请求超时,请检查网络后重试'
    } else if (error.response) {
      const status = error.response.status
      if (status === 401) {
        message = '登录已失效,请重新登录'
        localStorage.removeItem('token')
        localStorage.removeItem('userInfo')
      } else if (status === 403) {
        message = '没有该操作权限'
      } else if (status === 404) {
        message = '请求的接口不存在'
      } else if (status >= 500) {
        message = '服务器异常,请稍后重试'
      }
    } else if (error.request) {
      message = '无法连接到服务器,请确认后端已启动'
    }

    // silent 的处理同上:旁路请求失败不打扰用户。
    // 401 那一条**即使是 silent 也要清 token** —— 登录确实失效了,
    // 不清的话后续每个请求都带着一个废 token,而用户不知道自己已经掉线
    if (!error.config?.silent) {
      ElMessage.error(message)
    }
    return Promise.reject(error)
  }
)

export default request
