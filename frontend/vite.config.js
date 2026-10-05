import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],

  resolve: {
    alias: {
      // 用 @ 指向 src,避免 ../../../ 这种相对路径地狱
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },

  server: {
    port: 5173,
    // 允许局域网访问,方便用手机测响应式布局(答辩时也可以这么演示)
    host: true,

    // 开发代理:前端请求 /api/** 转发到后端 8080
    //
    // 有了它,开发期前端代码里直接写 request.get('/api/ping') 即可,
    // 不用拼 http://localhost:8080,也就不会遇到跨域问题。
    // 后端那边仍然配了 CORS,是为了应对不走代理的场景。
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // 上传文件的访问路径也代理过去
      '/upload': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },

  build: {
    // 浏览器兼容目标的默认值已是较新的浏览器,不用改
    // 生产构建产物目录
    outDir: 'dist',
    // 打包体积分析:超过 1000KB 才警告(Element Plus + ECharts 本就较大)
    chunkSizeWarningLimit: 1000
  }
})
