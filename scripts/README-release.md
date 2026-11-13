# 发布与更新通道（票08，SPEC §8：侧载 APK + GitHub Releases）

## 一次性：生成发布密钥

```bash
mkdir -p keystore
keytool -genkeypair -v -keystore keystore/hgtv.jks -alias hgtv -keyalg RSA -keysize 2048 -validity 10950
```

然后写 `keystore/hgtv-release.properties`（gitignore，永不入库）：

```properties
storeFile=hgtv.jks
storePassword=<你的密钥库口令>
keyAlias=hgtv
keyPassword=<你的密钥口令>
```

`keystore/`、`dist/` 均已 gitignore；密钥丢失将无法给已装用户推同签名升级，务必备份。

## 出包

```bash
./scripts/release.sh            # 本地出包 → dist/hgtv-v<版本>+<build>-g<SHA>.apk
./scripts/release.sh --publish  # 出包 + git tag + gh release create 上传（需 gh CLI 登录）
```

- 未配置密钥时 release 构建自动回落 debug 章签名（仅自装可用，**不可当正式发布**）。
- APK 文件名含 `versionName`、`versionCode`、git 短 SHA（工作区不干净加 `-dirty`），版本可追溯。
- 版本号改 `app/build.gradle.kts` 的 `versionCode` / `versionName` 后跑脚本；同一 `versionName` 不能重复 `--publish`（tag 冲突即拦截）。

## 用户侧升级

GitHub Releases 页直接下载最新 APK 侧载安装；同签名覆盖安装保留 DataStore 数据（观看历史/搜索历史/断点）。

## 真机数据保护（2026-11-13 事故后立规）

**数据何时会丢**：手动 `adb install -r` 遇签名冲突只会报错失败、不动已装包，是安全的；真正会清数据的是 **gradle 设备级任务**（`connectedDebugAndroidTest`、`installDebug` 等）——它们在所有在线设备上执行，且 AGP 遇签名冲突会自动 `uninstall` 再装，真机上的正式包连同 DataStore 数据一起被清。任何显式 `adb uninstall` 同样清数据。

**两条铁律**：

1. **真机装包只走 `./scripts/tv-install.sh`**（同签名 `install -r` 覆盖，数据保留；`--backup` 可先 `adb backup` 留底，电视端遥控器确认）。debug 包只进模拟器。
2. **gradle 设备级任务必须 `ANDROID_SERIAL=emulator-5554 ./gradlew ...` 限定模拟器**。`app/build.gradle.kts` 末尾已加保护闸：非模拟器设备在线且未设 `ANDROID_SERIAL` 时，`connected*`/`install*` 任务直接拒绝执行——闸是兜底，纪律是主线。

另：`versionCode` 只升不降（降版本覆盖装会被系统拒绝）；真机 IP 由 DHCP 分配，变了用 `TV_SERIAL=<新IP>:5555 ./scripts/tv-install.sh` 覆盖。
