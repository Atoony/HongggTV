# 2026-11-26 · v0.0.5 发布与 CI 搭建：踩坑实录

本次任务：openhongguo SDK 应急重编（v0.4.1 + 上游 PR #25）→ 真机验证 → GitHub Release 发布 → 首建 GitHub Actions 发布管线。以下按时间序收录碰到的问题与解法，供复用。

## 1. GitHub API 创建 Release 反复 422：`tag_name is not a valid tag` / `target_commitish invalid`

- **现象**：`POST /repos/.../releases` 带 `tag_name=v0.0.5`、`target_commitish=<短SHA>`，连续两次 422。第一次时短 SHA 尚未 push（根因成立）；但 push 之后短 SHA 明明远程可查证（`GET /commits/00b9d7e` → 200），依然 422。
- **解法**：放弃让 API 代建 tag，走经典路径——本地 `git tag v0.0.5 <SHA>` + `git push origin v0.0.5`，然后 API 只挂 body（`tag_name` 已存在时 `target_commitish` 被忽略）。一次成功。
- **教训**：API 代建 tag 不可靠且报错误导性强；**发 Release 的稳定姿势 = git push tag（与 `scripts/release.sh --publish` 同路径）+ API/CLI 挂 Release 元数据**。

## 2. Git Bash 里 `curl -d '...多行 JSON...'` 报 400 `Problems parsing JSON`

- **现象**：内联 JSON 的 `\n` 经 Git Bash 传参后 JSON 结构被破坏。
- **解法**：python `json.dumps` 写临时文件（UTF-8），`curl --data-binary @file.json` 发送。
- **教训**：curl 传复杂 JSON 一律走文件，不内联。

## 3. GitHub Secrets API 三个小坑

- **public-key 响应字段名是 `key`**，不是 `public_key`（按文档记忆写字段名直接 KeyError）。
- 加密用 **libsodium sealed box**（`pynacl`：`SealedBox(PublicKey(key, Base64Encoder())).encrypt(value)` → base64），不是普通 AES。
- `PUT /actions/secrets/{name}` 成功返回**空 body**（204），脚本不能按"响应里有 message"判断成败——**验证用 `GET /actions/secrets` 列 `total_count` 与 `updated_at`**。

## 4. Release asset 文件名里的 `+` 必须编码为 `%2B`

- **现象**：`?name=hgtv-v0.0.5+5-g00b9d7e.apk` 直接放 query，`+` 会被解析成空格，asset 名变成 `hgtv-v0.0.5 5-...`。
- **解法**：name 参数 URL 编码：`name=hgtv-v0.0.5%2B5-g00b9d7e.apk`。

## 5. 连续帧目检失效：Read→CDN→analyze_image 链路的 URL 恒定

- **现象**：同一会话内连续三次 `Read` 不同截图，返回**同一个** CDN URL，喂给图像分析永远拿到第一张的缓存（三帧描述一字不差）。
- **解法**：连续帧验证改用不依赖视觉的证据链——双帧截图 md5 差异 + logcat（解码器 frameIndex 增长、AudioTrack delivered、零 PlaybackException/crypto 错误）。
- **教训**：图像分析链路只适合**单张静态图**（本次真机首页目检即用它，正常）。

## 6. `uiautomator dump` 在详情页恒 2194 字节截断

- **现象**：首页 dump 正常（几十节点）；进详情页后 dump 恒 2194B，只有 3 层空壳节点。
- **推测**：详情页 accessibility 树过大（合集页选集行等），dump 序列化中断。
- **解法**：页面状态判定改走 logcat/前台 Activity（`dumpsys activity activities | grep topResumedActivity`）+ 截图 md5。

## 7. MSYS 路径转换复发：adb shell 内的 `/sdcard/...`

- **现象**：`adb shell uiautomator dump /sdcard/ui.xml` 报 `UI hierchary dumped to: /Files/Git/sdcard/ui.xml`。
- **解法**：设备端路径必须裹在整条引号命令里：`adb shell "uiautomator dump /sdcard/ui.xml && ..."`（引号内不做 MSYS 转换）。

## 8. 本地 gomobile 重编 SDK 的 Windows 坑族（收编自本会话前段）

- JDK GBK 读 UTF-8 中文注释 → `javac.bat` shim（`-encoding UTF-8`，用 heredoc 写入防 printf 吃转义）。
- build.sh 里 `unzip 'jni/*'` 通配符在 MSYS 失效 → 手动解包 AAR（classes.jar→libs/core.jar、jni/→jniLibs/）。
- gradle 自动装 SDK 组件失败留下**空残骸目录**（仅 .installer）→ `rm -rf` 被安全分类器拒绝时，改 compileSdk/显式 buildToolsVersion 绕开，不强删。
- 绑定工程 gradle 直连 google() TLS 握手失败 → 抄主工程的阿里云镜像 settings 配置。

## 9. CI 首跑 1 分钟失败：`./gradlew: Permission denied`（exit 126）

- **现象**：dispatch 验证构建，checkout/setup-java/keystore 还原全绿，`./gradlew` 步骤秒挂，exit 126。
- **根因**：Windows 下 `git core.fileMode=false`，历史上提交的 `gradlew` 文件模式是 **100644**（无执行位）；Linux runner checkout 后无执行权限。本地跑得欢是因为 Windows 根本不校验这一位——坑只在 Linux CI 上爆。
- **解法（双保险）**：`git update-index --chmod=+x gradlew` 治本（100644→100755），同时 workflow 构建步骤前加 `chmod +x gradlew` 防御未来 Windows 提交再丢位。
- **教训**：Windows 主机的仓库给跨平台仓库提交可执行脚本时，提交后务必 `git ls-files -s <file>` 核对模式。

## CI 发布管线（本次落成）

- `.github/workflows/release.yml`：`push tag v*` → 自动构建正式签名 APK → 建Release 传产物；`workflow_dispatch` 仅构建验证不发布。
- 密钥注入：4 个 Secrets（`HGTV_KEYSTORE_B64` / `HGTV_STORE_PASSWORD` / `HGTV_KEY_ALIAS` / `HGTV_KEY_PASSWORD`），keystore.jks 走 base64，properties 四键拆值。
- 验证：dispatch 构建绿 = 签名链可用（真机/侧载升级依赖签名一致）。
