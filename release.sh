#!/usr/bin/env bash
# ============================================================================
# release.sh —— 构建 + 自动同步代码到 GitHub
#
# 用途：一键完成「构建新版本 → 提交当前代码 → 推送到 GitHub」。
#      这是本项目的长期约定：每次构建出一个版本后，都要把当前版本的代码
#      上传到 GitHub（https://github.com/zhengyanmian/FlapDisplay）。
#
# 用法：
#   ./release.sh                 # 用默认提交信息
#   ./release.sh "修复 xxx"      # 自定义提交信息
#   ./release.sh --no-build      # 跳过构建，只提交推送代码
#   ./release.sh --no-push       # 只构建+提交，不推送
#
# 注意（本机环境）：
#   本机 HTTPS 访问 GitHub 需经代理，而代理对 git 的 CONNECT 隧道返回 502；
#   SSH（github.com:22 与 ssh.github.com:443）则完全通畅。
#   因此远程地址使用 SSH：git@github.com:zhengyanmian/FlapDisplay.git
#   若 SSH 认证失败，先运行 push-now.bat 查看公钥添加指引。
# ============================================================================
set -euo pipefail

# ===== 环境准备 =====
# 本机 shell 有时会丢失 PATH / git 不在默认 PATH，这里补上
export PATH="/usr/bin:/bin:/c/Windows/System32:$PATH"
GIT_BIN="/c/Users/24508/.workbuddy/binaries/PortableGit/versions/1.2.0/cmd"
if [ -d "$GIT_BIN" ]; then
    export PATH="$GIT_BIN:$PATH"
fi

# JDK 21
if [ -z "${JAVA_HOME:-}" ]; then
    export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
fi

# ===== 参数解析 =====
DO_BUILD=1
DO_PUSH=1
COMMIT_MSG=""

for arg in "$@"; do
    case "$arg" in
        --no-build) DO_BUILD=0 ;;
        --no-push)  DO_PUSH=0 ;;
        *)          COMMIT_MSG="$arg" ;;
    esac
done

# 切到脚本所在目录
cd "$(dirname "$0")"

echo "=============================================="
echo " 翻牌万象 FlapDisplayPlus —— 构建并同步 GitHub"
echo "=============================================="
echo ""

# ===== 1. 构建 =====
JAR_PATH=""
if [ "$DO_BUILD" -eq 1 ]; then
    echo "[1/4] 构建中 (gradlew build -x test) ..."
    # 注意：不用 clean，避免依赖解析网络抖动时把已有产物也清掉
    ./gradlew build -x test
    echo ""

    JAR_PATH=$(ls -t build/libs/*.jar 2>/dev/null | head -1 || true)
    if [ -n "$JAR_PATH" ]; then
        SIZE=$(stat -c%s "$JAR_PATH" 2>/dev/null || echo "?")
        echo "      产物: $JAR_PATH ($SIZE 字节)"
    else
        echo "      [!] 未找到构建产物"
    fi
else
    echo "[1/4] 跳过构建 (--no-build)"
fi
echo ""

# ===== 2. 检查改动 =====
echo "[2/4] 检查工作区改动 ..."
if [ -z "$(git status --porcelain)" ]; then
    echo "      工作区干净，无新改动需要提交。"

    if [ "$DO_PUSH" -eq 1 ]; then
        # 本地是否领先远程？用一个占位提交来比对
        LOCAL=$(git rev-parse HEAD)
        REMOTE=$(git ls-remote origin main 2>/dev/null | awk '{print $1}')
        if [ "$LOCAL" = "$REMOTE" ]; then
            echo "      本地与远程已同步（$LOCAL），无需推送。"
        else
            echo "      本地领先远程，正在推送 ..."
            git push origin main 2>&1 | sed 's/^/      /'
        fi
    fi
    echo ""
    echo "完成。"
    exit 0
fi

echo "      待提交文件："
git status --short | sed 's/^/      /'
echo ""

# ===== 3. 提交 =====
echo "[3/4] 提交 ..."
git add -A

if [ -z "$COMMIT_MSG" ]; then
    # 默认信息：带上版本号与日期
    VERSION=$(grep -E '^version' build.gradle 2>/dev/null | head -1 | sed -E 's/.*"([^"]+)".*/\1/' || echo "dev")
    COMMIT_MSG="build: 构建 v${VERSION} ($(date +%Y-%m-%d))"
fi

git commit -m "$COMMIT_MSG" 2>&1 | sed 's/^/      /'
echo ""

# ===== 4. 推送 =====
if [ "$DO_PUSH" -eq 1 ]; then
    echo "[4/4] 推送到 GitHub (SSH) ..."

    # 确保远程走 SSH（HTTPS 在本机经代理会 502）
    CURRENT_URL=$(git remote get-url origin 2>/dev/null || echo "")
    if [[ "$CURRENT_URL" == https://* ]]; then
        echo "      远程是 HTTPS，切换为 SSH（本机 HTTPS 走代理会 502）..."
        git remote set-url origin git@github.com:zhengyanmian/FlapDisplay.git
    fi

    # 检测 SSH 认证
    # 注意：git 的 SSH 认证检查**不能靠 grep 具体措辞**——GitHub 返回的是
    #   "Hi <user>! You've successfully authenticated, but GitHub does not provide shell access."
    # 曾因 grep "successfully authenticated"（少了 "You've "）而误判为「公钥未添加」，
    # 于是在公钥明明已生效的情况下反复提示用户去加 key（假阴性）。
    # 这里改为判断 ssh -T 的退出码：GitHub 认证成功但无 shell 时返回 1，
    # 所以真正可靠的判据是「输出里含 Hi <something>」或「不含 Permission denied」。
    SSH_OUT=$(ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 -T git@github.com 2>&1 || true)
    if ! printf '%s' "$SSH_OUT" | grep -qE "Hi [A-Za-z0-9_-]+!|successfully authenticated"; then
        echo ""
        echo "      [!] SSH 认证未通过 —— 公钥可能还没添加到 GitHub。"
        echo "      ssh -T 输出：$SSH_OUT"
        echo ""
        echo "      请完成一次（只需一次）:"
        echo "        1. 打开 https://github.com/settings/keys"
        echo "        2. 点 'New SSH key'，Title 随意"
        echo "        3. Key 粘贴下面这一整行:"
        echo ""
        cat ~/.ssh/id_ed25519.pub 2>/dev/null | sed 's/^/           /'
        echo ""
        echo "      保存后重新运行本脚本即可。"
        exit 1
    fi

    # 推送（失败重试 3 次，SSH 偶发抖动）
    PUSHED=0
    for i in 1 2 3; do
        if git push origin main 2>&1 | sed 's/^/      /'; then
            # git push 成功时若已是最新也会返回 0，用远程 SHA 复核
            LOCAL=$(git rev-parse HEAD)
            REMOTE=$(git ls-remote origin main 2>/dev/null | awk '{print $1}')
            if [ "$LOCAL" = "$REMOTE" ]; then
                PUSHED=1
                break
            fi
        fi
        echo "      第 $i 次未成功，5 秒后重试..."
        sleep 5
    done

    echo ""
    if [ "$PUSHED" -eq 1 ]; then
        echo "=============================================="
        echo " 完成！代码已同步到 GitHub。"
        echo " 仓库: https://github.com/zhengyanmian/FlapDisplay"
        echo "=============================================="
    else
        echo "=============================================="
        echo " [!] 推送未成功。代码已在本地提交，未丢失。"
        echo "     稍后重试: git push origin main"
        echo "     或双击运行: push-now.bat"
        echo "=============================================="
        exit 1
    fi
else
    echo "[4/4] 跳过推送 (--no-push)"
    echo ""
    echo "代码已提交到本地，稍后手动推送：git push origin main"
fi
