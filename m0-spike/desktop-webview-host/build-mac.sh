#!/usr/bin/env bash
# 构建 macOS 版 webview host（系统 WKWebView，不打包内核）
set -euo pipefail
cd "$(dirname "$0")"
OUT=${OUT:-build/macos}
mkdir -p "$OUT"
swiftc -O -framework AppKit -framework WebKit -framework Foundation \
  src/host_mac.swift -o "$OUT/kurisa-webview-host"
echo "built: $OUT/kurisa-webview-host"
ls -lh "$OUT/kurisa-webview-host" | awk '{print "size:", $5}'
