@echo off
REM ===========================================================================
REM  push-now.bat —— 把本地已提交的代码推送到 GitHub
REM
REM  用途：release.bat 因网络失败、代码已提交但未推送时，事后补推。
REM        走 SSH（本机 HTTPS 走代理会 502，SSH 端口通畅）。
REM ===========================================================================

setlocal
cd /d "%~dp0"

set "PATH=C:\Users\24508\.workbuddy\binaries\PortableGit\versions\1.2.0\cmd;%PATH%"

where git >nul 2>nul
if errorlevel 1 (
    echo [错误] 找不到 git。
    pause
    exit /b 1
)

echo ==============================================
echo  推送代码到 GitHub (SSH)
echo ==============================================
echo.
echo 远程地址: 
git remote get-url origin
echo.

REM 先检查 SSH 认证是否就绪
REM 注意：不要 grep "successfully authenticated" —— GitHub 实际返回
REM   "Hi <user>! You've successfully authenticated, but GitHub does not provide shell access."
REM 用完整短语匹配会假阴性（公钥已生效却提示去添加）。改为匹配 "Hi " 前缀。
echo [检查] 测试 SSH 认证 ...
ssh -o StrictHostKeyChecking=accept-new -T git@github.com 2>&1 | findstr /c:"Hi " >nul
if errorlevel 1 (
    echo.
    echo [提示] SSH 认证未通过，公钥可能还没添加到 GitHub。
    echo.
    echo 请先完成以下操作 ^(只需一次^):
    echo   1. 打开 https://github.com/settings/keys
    echo   2. 点击 "New SSH key"
    echo   3. Title 随便填，Key 粘贴下面这一整行:
    echo.
    type "%USERPROFILE%\.ssh\id_ed25519.pub"
    echo.
    echo   4. 保存后再运行本脚本
    echo.
    pause
    exit /b 1
)

echo        OK
echo.

echo [推送] git push origin main ...
for /L %%i in (1,1,3) do (
    echo       --- 第 %%i 次尝试 ---
    git push origin main
    if not errorlevel 1 goto pushed
    echo       失败，5 秒后重试...
    timeout /t 5 /nobreak >nul
)

echo.
echo [失败] 推送未成功，请检查网络后重试。
pause
exit /b 1

:pushed
echo.
echo ==============================================
echo  完成！
echo  仓库: https://github.com/zhengyanmian/FlapDisplay
echo ==============================================
pause
