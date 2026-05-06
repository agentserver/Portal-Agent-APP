#!/data/data/com.termux/files/usr/bin/bash
# snapshot_ubuntu.sh — 清理并打包当前 Ubuntu proot 环境
# 在 Termux 中运行：bash ~/snapshot_ubuntu.sh
#
# 输出：~/ubuntu-snapshot/ubuntu_<timestamp>.tar.xz（及可选的 .tar.zst）

set -e

ROOTFS="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"
SNAP_DIR="$HOME/ubuntu-snapshot"
TS=$(date +%Y%m%d_%H%M%S)
OUT_XZ="$SNAP_DIR/ubuntu_${TS}.tar.xz"

# ── 颜色输出 ─────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[ OK ]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }

# ── 前置检查 ──────────────────────────────────────────────
if [ ! -d "$ROOTFS" ]; then
    echo "错误：找不到 Ubuntu rootfs：$ROOTFS"
    exit 1
fi

mkdir -p "$SNAP_DIR"

# ══════════════════════════════════════════════════════════
# Step 1: 在 proot 内清理缓存与冗余文件
# ══════════════════════════════════════════════════════════
info "Step 1/3 — 清理 Ubuntu 内部缓存（在 proot 内运行）"

proot-distro login ubuntu -- bash -c '
set -e

echo "  · apt 包缓存 + 索引..."
apt-get clean -y 2>/dev/null || true
rm -rf /var/lib/apt/lists/*

echo "  · npm / pnpm 缓存..."
npm cache clean --force 2>/dev/null || true
rm -rf /root/.npm ~/.npm /home/*/.npm 2>/dev/null || true
rm -rf /root/.pnpm-store /home/*/.pnpm-store 2>/dev/null || true

echo "  · pip / uv 缓存..."
rm -rf /root/.cache/pip /home/*/.cache/pip 2>/dev/null || true
rm -rf /root/.cache/uv  /home/*/.cache/uv  2>/dev/null || true

echo "  · 通用 .cache 目录..."
rm -rf /root/.cache /home/*/.cache 2>/dev/null || true

echo "  · man 手册 / 文档 / locale..."
rm -rf /usr/share/man/*
rm -rf /usr/share/doc/*
rm -rf /usr/share/info/*
# 保留 C / POSIX / en / en_US，删其余 locale
find /usr/share/locale -mindepth 1 -maxdepth 1 \
    ! -name "C" ! -name "POSIX" ! -name "en" ! -name "en_US" \
    -exec rm -rf {} + 2>/dev/null || true

echo "  · /tmp /var/tmp..."
rm -rf /tmp/* /var/tmp/* 2>/dev/null || true

echo "  · bash / python 历史..."
rm -f /root/.bash_history /home/*/.bash_history 2>/dev/null || true
find / -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
find / -name "*.pyc" -delete 2>/dev/null || true

echo "  完成"
'

ok "proot 内清理完成"

# ══════════════════════════════════════════════════════════
# Step 2: 统计清理后大小
# ══════════════════════════════════════════════════════════
info "Step 2/3 — 统计 rootfs 大小"
RAW_SIZE=$(du -sh "$ROOTFS" | cut -f1)
RAW_BYTES=$(du -sb "$ROOTFS" | cut -f1)
echo "  清理后原始大小：$RAW_SIZE"

# ══════════════════════════════════════════════════════════
# Step 3: 打包压缩
# ══════════════════════════════════════════════════════════
info "Step 3/3 — 打包压缩"
echo "  输出路径：$OUT_XZ"
echo "  开始：$(date '+%H:%M:%S')"

# 排除 proc / sys / dev / run / tmp（proot 启动时动态挂载，不打包）
EXCLUDES=(
    --exclude="ubuntu/proc"
    --exclude="ubuntu/sys"
    --exclude="ubuntu/dev"
    --exclude="ubuntu/run"
    --exclude="ubuntu/tmp"
    --exclude="ubuntu/var/tmp"
)

# xz 多线程（-T0 = 用全部核心，-9 最高压缩率）
tar -c "${EXCLUDES[@]}" \
    -C "$(dirname "$ROOTFS")" \
    "$(basename "$ROOTFS")" \
    | xz -9 -T0 -v > "$OUT_XZ"

echo "  结束：$(date '+%H:%M:%S')"

# ── 可选：同时生成 zstd 版本（如果有 zstd） ──────────────
if command -v zstd &>/dev/null; then
    OUT_ZST="${OUT_XZ%.tar.xz}.tar.zst"
    info "  检测到 zstd，额外生成对比包..."
    tar -c "${EXCLUDES[@]}" \
        -C "$(dirname "$ROOTFS")" \
        "$(basename "$ROOTFS")" \
        | zstd --ultra -22 -T0 -v > "$OUT_ZST"
    ZST_SIZE=$(du -sh "$OUT_ZST" | cut -f1)
    echo "  zstd 压缩后：$ZST_SIZE  →  $OUT_ZST"
fi

# ══════════════════════════════════════════════════════════
# 汇总
# ══════════════════════════════════════════════════════════
XZ_SIZE=$(du -sh "$OUT_XZ" | cut -f1)
XZ_BYTES=$(du -sb "$OUT_XZ" | cut -f1)
RATIO=$(awk "BEGIN{printf \"%.1f\", $XZ_BYTES/$RAW_BYTES*100}")

echo ""
echo "══════════════════════════════════════"
ok "打包完成"
echo "  原始大小：$RAW_SIZE"
echo "  xz 压缩后：$XZ_SIZE  （压缩率 ${RATIO}%）"
echo "  文件：$OUT_XZ"
echo "══════════════════════════════════════"
echo "提示：可用 adb pull 将文件拉到电脑："
echo "  adb pull \"$OUT_XZ\" ."
