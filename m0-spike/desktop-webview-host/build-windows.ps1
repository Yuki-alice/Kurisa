# 构建 Windows 版 webview host（系统 WebView2 Runtime，不打包内核）
# 要求：VS 2022 + MSVC x64 + NuGet + WebView2 SDK
#   nuget install Microsoft.Web.WebView2 -Version 1.0.2903.40 -OutputDirectory packages
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $root "build/windows"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$pkg = Get-ChildItem (Join-Path $root "packages/Microsoft.Web.WebView2.*") -Directory | Select-Object -First 1
if (-not $pkg) {
  Write-Host "缺少 WebView2 NuGet 包，先执行：" -ForegroundColor Yellow
  Write-Host "  nuget install Microsoft.Web.WebView2 -Version 1.0.2903.40 -OutputDirectory packages"
  exit 1
}
$inc = Join-Path $pkg.FullName "build/native/include"
# MSVC 编译（x64，UNICODE，子系统 WINDOWS，入口 wmain）
cl /EHsc /std:c++17 /DUNICODE /D_UNICODE `
  /I $inc `
  (Join-Path $root "src/host_win.cc") `
  /Fe:(Join-Path $out "kurisa-webview-host.exe") `
  /link /SUBSYSTEM:WINDOWS user32.lib ole32.lib shlwapi.lib
Write-Host ("built: " + (Join-Path $out "kurisa-webview-host.exe"))
Get-Item (Join-Path $out "kurisa-webview-host.exe") | Select-Object Length
# 烟测（与 Linux/macOS 同断言）：需在有 GUI 的 Windows 跑
#   python tests/linux_smoke.py --bin build/windows/kurisa-webview-host.exe [--real-site]
