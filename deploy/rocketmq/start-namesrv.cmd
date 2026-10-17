@echo off
chcp 65001 >nul
REM 启动 RocketMQ NameServer（本地开发用）
REM 把 ROCKETMQ_HOME 改成你解压 RocketMQ 后的目录

set "ROCKETMQ_HOME=D:\tools\rocketmq\rocketmq-all-4.9.7-bin-release"

if not exist "%ROCKETMQ_HOME%\bin\mqnamesrv.cmd" (
    echo [ERROR] 找不到 %ROCKETMQ_HOME%\bin\mqnamesrv.cmd
    echo         请修改本脚本顶部的 ROCKETMQ_HOME
    pause
    exit /b 1
)

cd /d "%ROCKETMQ_HOME%\bin"
findstr /C:"-Xms4g" runserver.cmd >nul 2>&1
if not errorlevel 1 (
    echo [WARN] runserver.cmd 仍是默认 4g 堆内存，建议改成 512m，否则可能启动失败：
    echo        set "JAVA_OPT=%%JAVA_OPT%% -server -Xms512m -Xmx512m -Xmn256m"
)

echo [INFO] 正在启动 NameServer，监听 9876 ...
call mqnamesrv.cmd
pause
