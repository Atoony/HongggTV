#!/usr/bin/env bash
# 真机电视装包统一入口（数据安全）：
#   - 只做同签名 adb install -r 覆盖安装，绝不 uninstall——签名不符时 adb 只报错、不动已装包
#   - --backup 先 adb backup 留底（allowBackup=true，电视端会弹窗，需遥控器选"备份我的数据"）
# 用法：
#   ./scripts/tv-install.sh                     装 dist/ 下最新 APK（按文件名排序）
#   ./scripts/tv-install.sh path/to/app.apk     装指定 APK（须 release 正式签名）
#   ./scripts/tv-install.sh --backup [apk]      装前先备份 com.hgtv.app 数据
#   TV_SERIAL=192.168.5.6:5555 ./scripts/tv-install.sh   DHCP 换 IP 时覆盖
set -euo pipefail
cd "$(dirname "$0")/.."

TV_SERIAL="${TV_SERIAL:-192.168.5.5:5555}"
ADB="${ADB:-/d/android-env/sdk/platform-tools/adb}"

BACKUP=0
APK=""
for arg in "$@"; do
    case "$arg" in
        --backup) BACKUP=1 ;;
        *) APK="$arg" ;;
    esac
done
if [[ -z "$APK" ]]; then
    APK=$(ls -1 dist/*.apk 2>/dev/null | sort | tail -1) || true
    [[ -n "$APK" ]] || { echo "!! dist/ 下没有 APK，先跑 ./scripts/release.sh 或传入 APK 路径"; exit 1; }
fi
[[ -f "$APK" ]] || { echo "!! 找不到 $APK"; exit 1; }

echo "== 目标设备 $TV_SERIAL / APK $APK"
"$ADB" connect "$TV_SERIAL"

if [[ "$BACKUP" == 1 ]]; then
    BK="dist/hgtv-backup-$(date +%Y%m%d-%H%M%S).ab"
    echo "== 备份 com.hgtv.app → $BK（电视端弹窗：请用遥控器选『备份我的数据』）"
    "$ADB" -s "$TV_SERIAL" backup -f "$BK" com.hgtv.app
    [[ -s "$BK" ]] || { echo "!! 备份文件为空（可能被取消），中止安装"; exit 1; }
fi

echo "== 覆盖安装（install -r，保留数据）"
if ! "$ADB" -s "$TV_SERIAL" install -r "$APK"; then
    echo "!! 安装失败。若报 INSTALL_FAILED_UPDATE_INCOMPATIBLE：本脚本只装 release 正式签名包，"
    echo "   当前真机上可能是 debug 包（历史上被装过 debug 签名）。此时只能卸载重装且数据会丢，"
    echo "   请先人工确认后再 adb uninstall com.hgtv.app。"
    exit 1
fi
echo "== 完成。数据（观看历史/断点/设置）已保留"
