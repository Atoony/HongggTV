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
