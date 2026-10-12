#!/usr/bin/env bash
# CI 里安装锁定版本的 Android SDK 组件（platforms / build-tools / ndk）。
#
# 为什么不能直接写裸 `sdkmanager ...`：GitHub 托管 runner 不保证 cmdline-tools 的 bin 在 PATH
# 上。2026-10-12 的 Source checks（run 38227580566）就死在 `sdkmanager: command not found`
# （exit 127），排在这一步之后的「契约检查」「双 flavor 单测」全部被跳过 —— 一次定位失败把
# 整条流水线的证据变成「没跑」。这里只做一件事：按「SDK 根目录下的 cmdline-tools 布局 →
# PATH」的顺序解析出真正可执行的那个 sdkmanager，并让调用方拿到本次实际使用的 SDK 根目录。
# 不猜版本号、不改 SDK 配置、不在失败后改走别的通道。
#
# 用法（在 step 内 source，解析出的 SDK 根目录才会留在当前 shell；也可以直接 bash 执行）：
#   source tools/ci-android-sdk.sh "platforms;$platform" "build-tools;$buildtools" "ndk;$ndk"
set -euo pipefail

ci_android_sdk_install() {
  if [ "$#" -eq 0 ]; then
    echo '用法：source tools/ci-android-sdk.sh "platforms;android-37.0" ["build-tools;36.0.0" ...]' >&2
    return 2
  fi
  local root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  local candidate="" bin=""
  if [ -n "$root" ]; then
    # latest 是当前布局；cmdline-tools/bin 与 tools/bin 是历史布局，只读不迁移。
    for candidate in \
      "$root/cmdline-tools/latest/bin/sdkmanager" \
      "$root/cmdline-tools/bin/sdkmanager" \
      "$root/tools/bin/sdkmanager"; do
      if [ -x "$candidate" ]; then
        bin="$candidate"
        break
      fi
    done
  fi
  if [ -z "$bin" ]; then
    # PATH 兜底；找不到时用 `|| true` 保住 set -e 语义，由下面那条显式失败说清原因，
    # 而不是让脚本静默地以 127 结束。
    bin="$(command -v sdkmanager 2>/dev/null || true)"
  fi
  if [ -z "$bin" ]; then
    echo "找不到 sdkmanager（ANDROID_HOME=${ANDROID_HOME:-<未设置>} ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-<未设置>}）" >&2
    echo "先安装 Android command-line tools，或把 ANDROID_HOME 指向已有 SDK。" >&2
    return 1
  fi
  if [ -z "$root" ]; then
    # cmdline-tools/<version>/bin/sdkmanager 相对 SDK 根目录正好三层。
    root="$(cd -- "$(dirname -- "$bin")/../../.." && pwd -P)"
  fi
  export ANDROID_HOME="$root" ANDROID_SDK_ROOT="$root"
  echo "==> sdkmanager=$bin"
  echo "==> ANDROID_HOME=$root"
  "$bin" "$@"
}

ci_android_sdk_install "$@"
