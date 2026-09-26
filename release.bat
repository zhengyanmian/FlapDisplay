@echo off
REM ===========================================================================
REM  release.bat —— 双击即可构建并同步代码到 GitHub
REM
REM  这是本项目的长期约定：每次构建出一个版本后，都把当前版本的代码
REM  上传到 GitHub (https://github.com/zhengyanmian/FlapDisplay)。
REM
REM  可选参数（在命令行传入）：
REM    release.bat "自定义提交信息"
REM    release.bat --no-push      只构建+提交，不推送
REM    release.bat --no-build     跳过构建，只提交推送
REM ===========================================================================

setlocal

cd /d "%~dp0"

REM ===== 环境准备 =====
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM PortableGit（git 不在系统 PATH 时的兜底）
if exist "C:\Users\24508\.workbuddy\binaries\PortableGit\versions\1.2.0\cmd\git.exe" (
    set "PATH=C:\Users\24508\.workbuddy\binaries\PortableGit\versions\1.2.0\cmd;%PATH%"
)

where git >nul 2>nul
if errorlevel 1 (
    echo [错误] 找不到 git，请确认已安装 Git 或 PortableGit 路径正确。
    pause
    exit /b 1
)

echo ==============================================
echo  翻牌万象 FlapDisplayPlus —— 构建并同步 GitHub
echo ==============================================
echo.

REM ===== 参数解析 =====
set "DO_BUILD=1"
set "DO_PUSH=1"
set "COMMIT_MSG="

:parse_args
if "%~1"=="" goto args_done
if /i "%~1"=="--no-build" (
    set "DO_BUILD=0"
) else if /i "%~1"=="--no-push" (
    set "DO_PUSH=0"
) else (
    set "COMMIT_MSG=%~1"
)
shift
goto parse_args
:args_done

REM ===== 1. 构建 =====
if "%DO_BUILD%"=="1" (
    echo [1/4] 构建中 ^(gradlew build -x test^) ...
    echo.
    call gradlew.bat build -x test
    if errorlevel 1 (
        echo.
        echo [错误] 构建失败，已中止，代码未提交。
        echo        常见原因：maven.neoforged.net 网络抖动，重试即可。
        pause
        exit /b 1
    )
    echo.
    for /f "delims=" %%f in ('dir /b /o-d "build\libs\*.jar" 2^>nul') do (
        echo       产物: build\libs\%%f
        goto jar_found
    )
    echo       [!] 未找到构建产物
    :jar_found
) else (
    echo [1/4] 跳过构建 ^(--no-build^)
)
echo.

REM ===== 2. 检查改动 =====
echo [2/4] 检查工作区改动 ...
for /f "delims=" %%s in ('git status --porcelain') do goto has_changes
echo       工作区干净，无新改动需要提交。
if "%DO_PUSH%"=="1" (
    echo       仍然尝试推送 ^(以防本地领先远程^)...
    git push origin main
)
echo.
echo 完成。
pause
exit /b 0

:has_changes
git status --short
echo.

REM ===== 3. 提交 =====
echo [3/4] 提交 ...
git add -A
if "%COMMIT_MSG%"=="" (
    for /f "tokens=2 delims==" %%v in ('findstr /b /c:"version = " build.gradle') do set "VER=%%v"
    set "VER=%VER: =%"
    set "VER=%VER:"=%"
    set "COMMIT_MSG=build: 构建 v%VER%"
)
git commit -m "%COMMIT_MSG%"
if errorlevel 1 (
    echo.
    echo [错误] 提交失败。
    pause
    exit /b 1
)
echo.

REM ===== 4. 推送 =====
if "%DO_PUSH%"=="1" (
    echo [4/4] 推送到 GitHub ^(SSH^) ...

    REM 确保远程走 SSH（HTTPS 在本机经代理会 502）
    git remote get-url origin | findstr /b /c:"https://" >nul
    if not errorlevel 1 (
        echo       远程是 HTTPS，切换为 SSH ...
        git remote set-url origin git@github.com:zhengyanmian/FlapDisplay.git
    )

    REM 检测 SSH 认证
    REM 不要匹配 "successfully authenticated" —— GitHub 返回
    REM   "Hi <user>! You've successfully authenticated, ..."
    REM 完整短语匹配会假阴性。改为匹配 "Hi " 前缀。
    ssh -o StrictHostKeyChecking=accept-new -T git@github.com 2>&1 | findstr /c:"Hi " >nul
    if errorlevel 1 (
        echo.
        echo       [!] SSH 认证未通过 —— 公钥可能还没添加到 GitHub。
        echo.
        echo       请完成一次 ^(只需一次^):
        echo         1. 打开 https://github.com/settings/keys
        echo         2. 点 "New SSH key"，Title 随意
        echo         3. Key 粘贴下面这一整行:
        echo.
        type "%USERPROFILE%\.ssh\id_ed25519.pub"
        echo.
        echo       保存后重新运行本脚本即可。
        pause
        exit /b 1
    )

    REM 推送（失败重试 3 次）
    set "PUSHED=0"
    for /L %%i in (1,1,3) do (
        if not "%PUSHED%"=="1" (
            echo       --- 第 %%i 次尝试 ---
            git push origin main
            if not errorlevel 1 set "PUSHED=1"
            if not "%PUSHED%"=="1" (
                echo       失败，5 秒后重试...
                timeout /t 5 /nobreak >nul
            )
        )
    )

    echo.
    if "%PUSHED%"=="1" (
        echo ==============================================
        echo  完成！代码已同步到 GitHub。
        echo  仓库: https://github.com/zhengyanmian/FlapDisplay
        echo ==============================================
    ) else (
        echo ==============================================
        echo  [!] 推送未成功。代码已在本地提交，未丢失。
        echo      稍后重试: git push origin main
        echo      或双击运行: push-now.bat
        echo ==============================================
        pause
        exit /b 1
    )
) else (
    echo [4/4] 跳过推送 ^(--no-push^)
    echo.
    echo 代码已提交到本地，稍后手动推送: git push origin main
)

echo.
pause
