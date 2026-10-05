#!/usr/bin/env bash
# ===========================================================================
#  本项目专用 Maven 启动脚本(Git Bash / WSL 用)
#
#  为什么需要它:
#    1. 系统 PATH 上的 mvn 是 3.6.1,Spring Boot 3.x 要求 >= 3.6.3
#    2. 本地仓库和镜像限定在本项目内,不改动全局 Maven 配置
#
#  用法(在项目根目录):
#      ./scripts/mvn.sh clean package
#      ./scripts/mvn.sh spring-boot:run
# ===========================================================================
set -e

MVN_HOME="D:/JAVAEE/soft/apache-maven-3.9.5-bin/apache-maven-3.9.5"
PROJ_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SETTINGS="$PROJ_ROOT/maven-settings.xml"

if [ ! -f "$MVN_HOME/bin/mvn" ]; then
    echo "[错误] 找不到 Maven: $MVN_HOME" >&2
    echo "        请确认该路径存在,或修改本脚本中的 MVN_HOME" >&2
    exit 1
fi

if [ ! -f "$SETTINGS" ]; then
    echo "[错误] 找不到配置文件: $SETTINGS" >&2
    exit 1
fi

# 强制 UTF-8,否则中文日志/测试输出会乱码(本机 Maven 平台编码是 GBK)
export MAVEN_OPTS="-Dfile.encoding=UTF-8 -Duser.language=zh -Duser.country=CN"

exec "$MVN_HOME/bin/mvn" -s "$SETTINGS" "$@"
