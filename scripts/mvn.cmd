@echo off
REM ===========================================================================
REM  本项目专用 Maven 启动脚本
REM
REM  为什么需要它:
REM    1. 系统 PATH 上的 mvn 是 3.6.1(D:\shixun\apache-maven-3.6.1),
REM       Spring Boot 3.x 要求 Maven >= 3.6.3,直接用会构建失败
REM    2. 把本地仓库和镜像限定在本项目内,不改动全局 Maven 配置
REM
REM  用法:在项目根目录执行
REM      scripts\mvn.cmd clean package
REM      scripts\mvn.cmd spring-boot:run
REM ===========================================================================

setlocal

set "MVN_HOME=D:\JAVAEE\soft\apache-maven-3.9.5-bin\apache-maven-3.9.5"
set "PROJ_ROOT=%~dp0.."
set "SETTINGS=%PROJ_ROOT%\maven-settings.xml"

if not exist "%MVN_HOME%\bin\mvn.cmd" (
    echo [错误] 找不到 Maven: %MVN_HOME%
    echo         请确认该路径存在,或修改本脚本中的 MVN_HOME
    exit /b 1
)

if not exist "%SETTINGS%" (
    echo [错误] 找不到配置文件: %SETTINGS%
    exit /b 1
)

REM MAVEN_OPTS 里强制 UTF-8,否则中文日志/测试输出会乱码
set "MAVEN_OPTS=-Dfile.encoding=UTF-8 -Duser.language=zh -Duser.country=CN"

call "%MVN_HOME%\bin\mvn.cmd" -s "%SETTINGS%" %*

endlocal
