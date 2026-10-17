@echo off
chcp 65001 >nul
REM 启动 RocketMQ Broker（本地开发用），NameServer 要先起来
REM 把 ROCKETMQ_HOME 改成你解压 RocketMQ 后的目录

set "ROCKETMQ_HOME=D:\tools\rocketmq\rocketmq-all-4.9.7-bin-release"
set "NAMESRV_ADDR=127.0.0.1:9876"

if not exist "%ROCKETMQ_HOME%\bin\mqbroker.cmd" (
    echo [ERROR] 找不到 %ROCKETMQ_HOME%\bin\mqbroker.cmd
    echo         请修改本脚本顶部的 ROCKETMQ_HOME
    pause
    exit /b 1
)

cd /d "%ROCKETMQ_HOME%\bin"
findstr /C:"-Xms8g" runbroker.cmd >nul 2>&1
if not errorlevel 1 (
    echo [WARN] runbroker.cmd 仍是默认 8g 堆内存，建议改成 1g，否则可能启动失败：
    echo        set "JAVA_OPT=%%JAVA_OPT%% -server -Xms1g -Xmx1g -Xmn512m"
)

echo [INFO] 正在启动 Broker，读取 ..\..\local-perks\deploy\rocketmq\broker.conf ...
call mqbroker.cmd -c "%~dp0broker.conf"
pause
