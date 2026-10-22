#!/usr/bin/env bash
# 票08：GitHub Releases 侧载更新通道（SPEC §8）
# 用法：
#   ./scripts/release.sh            构建 release APK → dist/（文件名含版本号+git SHA，可追溯）
#   ./scripts/release.sh --publish  追加 git tag + gh release create 上传（需 gh CLI 已登录）
set -euo pipefail
cd "$(dirname "$0")/.."

VER=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
SHA=$(git rev-parse --short HEAD)
if [[ -n $(git status --porcelain) ]]; then DIRTY="-dirty"; else DIRTY=""; fi
APK_NAME="hgtv-v${VER}+${CODE}-g${SHA}${DIRTY}.apk"
TAG="v${VER}"

if [[ ! -f keystore/hgtv-release.properties ]]; then
    echo "!! 缺 keystore/hgtv-release.properties —— 将以 debug 签名出包（仅自装可用）"
    echo "   正式发布请先按 scripts/README-release.md 生成密钥"
    read -r -p "仍继续? [y/N] " ans
    [[ "${ans:-n}" == "y" ]] || exit 1
fi

echo "== 构建 release（v${VER} / ${CODE} / g${SHA}${DIRTY}）"
./gradlew --no-daemon assembleRelease

mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/${APK_NAME}"
echo "== 已产出 dist/${APK_NAME}"

if [[ "${1:-}" == "--publish" ]]; then
    command -v gh >/dev/null 2>&1 || { echo "!! 缺 gh CLI（https://cli.github.com/），请安装后重试或手动上传 dist/${APK_NAME}"; exit 1; }
    if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
        echo "!! tag ${TAG} 已存在，先删除或改版本号"; exit 1
    fi
    git tag "${TAG}"
    git push origin "${TAG}"
    gh release create "${TAG}" "dist/${APK_NAME}" \
        --title "红果果短剧 ${TAG}" \
        --notes "版本 ${VER}（build ${CODE}，commit ${SHA}${DIRTY}）。Android TV 侧载 APK，minSdk 21。"
    echo "== 已发布 GitHub Releases ${TAG}"
fi
