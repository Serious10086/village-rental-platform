# syntax=docker/dockerfile:1
# ============================================================================
#  后端镜像 —— 两阶段构建
#
#  【为什么用两阶段】
#  编译需要 JDK + Maven(加起来 700MB 上下),运行只需要一个 JRE。
#  单阶段的话,最终镜像里会带着一整套 Maven 和源码 —— 交付物比它要跑的东西
#  大好几倍。第二阶段只从第一阶段 COPY 一个 jar 出来,其余全部丢掉。
#
#  【构建上下文是仓库根目录,不是 backend/】
#  compose 里写的是 context: .. —— 因为构建要用到 docker/ 下的
#  maven-settings-docker.xml。COPY 的路径因此都从根目录起算。
#
#  构建(由 docker compose 自动完成,手动跑是这个样子):
#    docker build -f docker/backend.Dockerfile -t village-backend .
# ============================================================================

# ── 阶段一:编译 ──────────────────────────────────────────────
# 【tag 为什么是 3- 而不是 3.9-】
# 这是国内拉取实测的结果,不是随手写的。`maven:3.9-eclipse-temurin-17`
# 这个精确 tag 在国内常用的几个镜像源上都取不到(404),能取到的只有
# `maven:3-eclipse-temurin-17`。
# 这是个浮动 tag:现在解析到 3.9.9,以后可能变成 3.10。本项目要求
# Maven ≥ 3.6.3,大版本 3 之内都满足。真要严格锁版本,应当把它换成
# 带摘要的形式(maven@sha256:...),或者把镜像推进自己的仓库。
FROM maven:3-eclipse-temurin-17 AS build

WORKDIR /build

# 先把容器用的 Maven 配置放好(阿里云源,理由见文件内注释)
COPY docker/maven-settings-docker.xml /root/.m2/settings.xml

# 【先只 COPY pom,再 COPY 源码 —— 这一步是刻意的】
# Docker 的层缓存按"这一层的输入有没有变"来命中。依赖只由 pom 决定,
# 所以把 pom 单独放一层:改了一个 Java 文件之后,pom 没变 → 这一层
# 直接命中缓存,只重跑后面的编译。把 src 和 pom 一起 COPY 的话,
# 改任何一行代码都会让依赖层失效。
COPY backend/pom.xml ./

# 预热依赖。go-offline 不保证把插件也拉全(它自己也这么说),
# 所以后面 package 时还会补拉一次 —— 那一次是增量的,很快。
# cache mount 让 /root/.m2 在多次构建之间保留,重建不用重下 200MB。
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B dependency:go-offline -DskipTests

COPY backend/src ./src

# 【必须删掉 application-dev.yml】
# 那个文件里有本机的数据库密码和**真实的 DeepSeek 密钥**。
# 它被 .gitignore 挡着,所以仓库里没有 —— 但 docker build 的上下文是
# 工作目录,不是 git 的索引,untracked 的文件照样会被 COPY 进来。
# 不删的话,密钥就固化进了镜像层:镜像推到哪,密钥跟到哪,
# 而且 docker history 能翻出来。部署期的配置一律由环境变量注入。
RUN rm -f src/main/resources/application-dev.yml

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B clean package -DskipTests

# ── 阶段二:运行 ──────────────────────────────────────────────
FROM eclipse-temurin:17-jre

WORKDIR /app

# 【为什么要装 curl】
# docker-compose 里给 backend 配了 healthcheck,靠它探测 /api/ping。
# 但 JRE 镜像里没有任何 HTTP 客户端(curl / wget 都没有),健康检查
# 会永远失败,容器状态卡在 starting,frontend 也就一直不启动。
#
# 代价是十几兆。换来的不只是健康检查:容器里出问题时,
# `docker compose exec backend curl ...` 是排查最快的一条路 ——
# 没有它就只能退到宿主机上去猜。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 只把 jar 拿过来。target/ 下还有 classes、test-classes、maven-archiver 等,
# 这里取的是 Spring Boot 打出来的可执行 jar
COPY --from=build /build/target/*.jar /app/app.jar

# 上传的文件写这里,compose 里挂成 volume。
# 不挂的话容器一删,用户传过的图就没了 —— 而数据库里还留着指向它们的 URL。
RUN mkdir -p /app/upload

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

EXPOSE 8080

# 【为什么用 sh -c 而不是 exec 形式】
# JAVA_OPTS 需要展开成多个参数,exec 形式不会经过 shell,展开不了。
# 代价是 java 是 sh 的子进程,收不到 Docker 发的 SIGTERM ——
# 对毕设这个规模无所谓;要较真的话应当改用 exec java ...
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
