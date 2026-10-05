import { useUserStore } from '@/stores/user'

/**
 * 按钮级权限指令。
 *
 * 用法:
 *   &lt;el-button v-hasPerm="'resource:audit'"&gt;审核通过&lt;/el-button&gt;
 *   &lt;el-button v-hasPerm="['user:ban', 'user:edit']"&gt;编辑&lt;/el-button&gt;   // 满足其一即可
 *
 * 【为什么用"移除 DOM"而不是 display:none】
 *
 * display:none 只是把元素藏起来,它仍然在 DOM 里。任何人按 F12 把样式改回来
 * 就能看到并使用这个按钮。虽然服务端还有一层校验兜底,但那样会变成一个
 * "看得见、点得动、点了报 403"的糟糕体验。
 * 直接把节点从 DOM 里摘掉,界面上就是干净的。
 *
 * 【这只是体验优化,不是安全边界】
 *
 * 前端隐藏挡不住直接用 Postman 发请求。真正的安全边界是后端的
 * @RequiresPerm 校验。两者缺一不可,但职责不同:
 *   前端负责"别让用户看见用不了的按钮"
 *   后端负责"就算看见了也点不动"
 */
export const hasPerm = {
  mounted(el, binding) {
    const userStore = useUserStore()
    const required = binding.value

    if (!required || required.length === 0) {
      return
    }

    const permList = Array.isArray(required) ? required : [required]
    if (!userStore.hasAnyPerm(permList)) {
      // 从 DOM 中移除,而不是隐藏
      el.parentNode?.removeChild(el)
    }
  }
}

/**
 * 角色指令 —— 按角色编码控制显隐。
 *
 * 用法:&lt;div v-hasRole="'SUPER_ADMIN'"&gt;仅超管可见&lt;/div&gt;
 */
export const hasRole = {
  mounted(el, binding) {
    const userStore = useUserStore()
    const required = binding.value

    if (!required || required.length === 0) {
      return
    }

    const roleList = Array.isArray(required) ? required : [required]
    const ok = roleList.some((r) => userStore.roles.has(r))
    if (!ok) {
      el.parentNode?.removeChild(el)
    }
  }
}

/** 一次性注册所有指令 */
export default {
  install(app) {
    app.directive('hasPerm', hasPerm)
    app.directive('hasRole', hasRole)
  }
}
