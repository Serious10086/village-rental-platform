import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
import '@/styles/tokens.css'
import '@/styles/global.css'

import App from './App.vue'
import router from './router'
import permissionDirectives from '@/directives/hasPerm'

const app = createApp(App)

// 全局注册 Element Plus 图标。
//
// 为什么用全局注册而不是按需引入:毕设项目里图标用得散(菜单、按钮、状态标签
// 到处都是),逐个 import 会让每个组件顶部堆一长串图标导入,反而不好读。
// 全量注册的体积代价(约 100KB)对毕设可以接受。
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 注意顺序:Pinia 必须在 router 之前注册。
// 路由守卫里要用 store,如果 Pinia 还没装好,守卫执行时会报
// "getActivePinia() was called without an active Pinia"
app.use(createPinia())
app.use(router)
app.use(permissionDirectives)
// 中文语言包:Element Plus 默认是英文,分页器、日期选择器都会显示英文
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
