#!/usr/bin/env bash
# 构建 Linux 版 webview host（动态链接系统 WebKitGTK，不打包内核）
set -euo pipefail

cd "$(dirname "$0")"
OUT=${OUT:-build/linux}
mkdir -p "$OUT"

pkg-config --exists webkit2gtk-4.1 || {
  echo "缺少 webkit2gtk-4.1 开发包。Debian/Ubuntu: sudo apt install libwebkit2gtk-4.1-dev" >&2
  exit 1
}

gcc -O2 -Wall -Wextra -Wno-unused-parameter \
  src/host_gtk.c \
  -o "$OUT/kurisa-webview-host" \
  $(pkg-config --cflags --libs gtk+-3.0 webkit2gtk-4.1) \
  -lm

echo "built: $OUT/kurisa-webview-host"
ls -lh "$OUT/kurisa-webview-host" | awk '{print "size:", $5}'
