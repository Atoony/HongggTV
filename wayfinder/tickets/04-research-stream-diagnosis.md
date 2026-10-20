---
id: 04
title: 研究：红果 App 取流诊断（video_model/v1 挂因）
type: research
status: closed
assignee: research-agent-04
blocked-by: []
---

## Question

红果 App 取流接口 `POST /novel/player/video_model/v1/` 为何返回"未返回有效数据"？根因是参数、风控还是接口变更？能否找到可用参数组合拿到播放地址？

## 背景（2026-10-18 首轮探针结论）

- 同签名链路的详情（`/novel/player/video_detail/v1/`）、弹幕接口都通 → **签名本身没问题**。
- 取流响应为空或超限，需打印**原始响应**（状态码、headers、body 前若干字节）对比诊断。
- 待试参数维度：`content_type`、`video_platform`、`biz_param` 等；参考真机抓包（若网上有公开抓包样本可搜）。
- 探针：`guoapp/native/core/zz_live_probe_test.go`，在 `guoapp/native/` 下运行：
  `HONGGUO_LIVE_PROBE=1 go test ./core -run TestLiveHongguoProbe -v -count=1`
- 测试样本：series_id=7688352674999176254（总裁是我前女友，95 集），video_id=7688396347107445822（第 1 集）。

## 要求

- 可以修改/新增探针测试文件（`*_test.go`），**不得改动 guoapp 生产代码**；不要执行 `scripts/finish_task.py` 收尾同步。
- 若改动了 guoapp 内文件，Resolution 中列出完整改动清单。
- 遵守 guoapp/AGENTS.md：不看、不下载、不分析站源图片。
- Resolution 产出：根因或排除项、证据（原始响应摘录）、可用参数组合（若有）、以及"下一步是否需要真机抓包"的判断。

## Resolution（2026-10-19）

### 根因（已确凿定位并在线复现）

`POST /novel/player/video_model/v1/` 服务端要求请求携带 **`X-Ladon` 与 `X-Argus` 两个风控头**；缺失时不报错、直接回 **HTTP 200 + `Content-Length: 0`（空 body 的静默拒绝）**。生产代码 `hongguoAppRequest`（`guoapp/native/core/provider_hongguo_app.go`）只设置 X-Khronos/X-Gorgon/X-SS-STUB/X-SS-Req-Ticket，缺这两个头，因此取流挂。详情/弹幕等低敏接口不校验这两个头，所以同签名链路是通的——这就是"详情通、取流挂"的原因。

### 证据链（全部为线上实测原始响应）

1. **原始响应对比**（探针 `TestLiveHongguoVideoModelRaw`）：
   - 详情 `/novel/player/video_detail/v1/`：HTTP 200，body 81033 字节，`X-Tt-Trace-Tag: id=11;cdn-cache=miss;type=dyn`，正常 JSON（code=0）
   - 取流 `/novel/player/video_model/v1/`（生产基线 body）：HTTP 200，**body 0 字节，`Content-Length: 0`**，`Server-Timing: inner; dur=49,tt_agw; dur=42; origin;dur=97`——请求到达源站并被业务层处理后主动返回空，非 CDN 拦截、非网关超限。
2. **body 参数维度整体排除**：9 组参数网格（无 biz_param / video_platform=0,2,3,4 / content_type=0,1,2 / 带 series_id / 仅 video_id）全部 200+0 字节。
3. **设备维度**：换用 HGTV 仓库内置已注册 device_id/iid + ts + 播放器版本 71332 + ladon/argus + helios 仍 200+0（D1–D5）——该公开设备身份已被平台拉黑（与 HGTV 文档"内置第三方旧值已被拉黑"一致）；**随机生成的 device_id/iid 反而直接可用**。
4. **消融矩阵**（探针 `TestLiveHongguoVideoModelHgtvSign`，以 HGTV 完整签名链+随机设备为基线）：
   - F1 去 branch==0 枚举：成功 → 非必要
   - F2 再去 X-Helios：成功 → 非必要
   - F3 再去 X-Ladon/X-Argus：**200+0 失败 → 唯一必要要素**
   - G1 只换回生产版 X-Gorgon（带 ladon/argus）：成功 → 生产 gorgon 算法可用
   - G2 只去 query `ts`：成功 → 非必要；G3 只去附加头（X-Tt-Request-Tag 等）：成功 → 非必要
   - G4/G6 换生产 body / 仅 video_id：成功（仅 video_id 时只回 1 档清晰度，6.9KB）
   - G5 换 73532 版本号+生产 UA：成功 → 版本/UA 无关
5. **最小修复验证**（探针 H1/H2）：完全复刻生产请求链（生产 query 无 ts、73532、生产 UA、生产 gorgon、生产 body、随机设备）**仅添加 X-Ladon/X-Argus** → 成功（23630 字节 code=0）；不加 → 200+0。
6. **稳定性/泛化/端到端**：同参数连跑 3 次全部成功（H3）；换第 2 集 video_id 成功（H4）；成功响应喂给生产解析函数 `selectHongguoAppMedia` 产出 `host=v5-gzb-bjtc-reading-video.qznovelvod.com 清晰度=1080 密钥=CENC 16字节 变体=2 时长=1m17s`（J1）——CDN 直链+CENC 密钥全部就绪。

### 可用参数组合（最小修复，约 6 行）

在 `signHongguoRequest`（provider_hongguo_sign.go）里，用与 X-Khronos 相同的秒级 ts 追加两个头：

```go
ladon := make([]byte, 4)
binary.BigEndian.PutUint32(ladon, timestamp)
argus := make([]byte, 4)
binary.LittleEndian.PutUint32(argus, timestamp)
request.Header.Set("X-Ladon", base64.StdEncoding.EncodeToString(ladon))
request.Header.Set("X-Argus", base64.StdEncoding.EncodeToString(argus))
```

即 `X-Ladon = base64(BE32(ts))`、`X-Argus = base64(LE32(ts))`（HGTV 项目的简版实现，当前服务端只做存在性校验）。**不需要**：设备注册、branch 枚举、X-Helios、X-Medusa、更换 gorgon 算法、修改 body、更换版本号/UA。修复后建议回归详情/弹幕/目录接口确认加头无副作用（真机本就带这些头，预期无害）。

### 排除项汇总

签名链（X-Gorgon/X-Khronos 版本）、body 参数（content_type/video_platform/biz_param）、设备未注册（随机 id 即可）、版本号 73532/71332、UA、query `ts` 参数、branch 枚举、X-Helios/X-Medusa——均与取流失败无关。

### 是否需要真机抓包

**当前不需要**。修复面已由消融实验完全定位并在线验证（含稳定性与泛化）。仅当未来服务端升级校验（例如开始校验 X-Ladon 的真实算法——真机版是 protobuf+AesV3 复杂结构，而非现在的 base64(ts) 简版）导致再次出现 200+0 时，才需真机抓包对照；建议实施票里附带"空响应计数监控"以便及时发现该情形。

### guoapp 文件改动清单（均为临时探针测试文件，未动生产代码、未动 go.mod、未执行 finish_task.py）

- 新增 `guoapp/native/core/zz_video_model_probe_test.go`：原始响应打印 + body 参数网格（`TestLiveHongguoVideoModelRaw`）
- 新增 `guoapp/native/core/zz_registered_device_probe_test.go`：设备层判别（D 系列）、HGTV 签名链移植与消融矩阵（E/F/G 系列）、最小修复验证（H 系列）、端到端解析（J1）；内含 branch 枚举用的 SM3 实现一份——**该 SM3 未通过官方测试向量自检**（输出 d106dd47…，官方为 66c7f0f4…；与 tjfoc/gmsm 权威源码逐行人工比对未发现文本差异，本地运行外部代码副本被权限系统拒绝，未能最终定位），但 branch 枚举已被 F1 证明非必要、修复方案也不依赖 SM3，故不影响本结论。两个探针文件保留供实施票复验，验证完成后按文件头注释删除。

### 参考资料来源

- HGTV 仓库 `docs/HONGGUO_API.md`、`Signer.kt`、`VendorConstants.kt`、`DeviceRegister.kt`（GPL-3.0，GitHub pan8664716/HGTV）——提供签名头全集、branch 枚举、设备注册协议与"200+0 须当失败处理"的旁证
- tjfoc/gmsm `sm3.go`（Apache-2.0）——SM3 权威实现对照
- GB/T 32905-2016 SM3 官方测试向量（"abc" → 66c7f0f4…）
