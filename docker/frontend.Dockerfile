# syntax=docker/dockerfile:1
# ============================================================================
#  前端镜像 —— 两阶段构建(Node 编译 → Nginx 托管)
#
#  【为什么最终是 Nginx,而不是 node 起一个 server】
#  `npm run build` 出来的是**一堆静态文件**,不需要 Node 运行时。
#  用 node 起服务等于为了托管几个 html/js 多带一个上百兆的运行时,
#  而且生产环境的静态托管该交给专业的:gzip、缓存头、try_files 回落,
#  Nginx 一个配置文件就够,自己写要写一堆。
#
#  构建(由 docker compose 自动完成,手动跑是这个样子):
#    docker build -f docker/frontend.Dockerfile -t village-frontend .
# ============================================================================

# ── 阶段一:编译 ──────────────────────────────────────────────
FROM node:20-alpine AS build

WORKDIR /build

# 【先 COPY package*.json,再 COPY 源码】—— 和 backend.Dockerfile 同一个理由:
# 依赖只由 package.json / package-lock.json 决定,单独放一层,
# 改业务代码时这一层命中缓存,npm ci 不用重跑。
#
# 用 npm ci 而不是 npm install:ci 严格按 lock 文件装,
# 装出来的版本和本机开发时一致。install 会顺手把 lock 里的小版本升上去,
# "我这儿能跑"的差异多半就是这么来的。
COPY frontend/package.json frontend/package-lock.json ./

# 走国内镜像。官方源在国内不是连不上,是慢 —— 装一次依赖要等好几分钟,
# 而这个阶段每次改前端代码都要重来。
RUN npm ci --registry=https://registry.npmmirror.com

COPY frontend/ .

# .dockerignore 已经把 node_modules / dist 挡在外面了 ——
# 不挡的话本机那份 node_modules 会覆盖掉容器里刚装好的(平台还不一样),
# 表现是构建时报"找不到某个原生模块"。
RUN npm run build

# ── 阶段二:托管 ──────────────────────────────────────────────
FROM nginx:alpine

# 站点配置:静态文件 + /api 与 /upload 反代到后端
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf

# 只把编译产物拿过来,node_modules 和源码不进最终镜像
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
