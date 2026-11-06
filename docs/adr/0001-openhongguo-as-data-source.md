# 数据源整体委托 openhongguo SDK

自研逆向链（HongguoApi/HongguoSign/HongguoWeb 等）在与红果服务端收紧的长期赛跑中疲于奔命：签名接口全灭、直链降预览、CENC 内容密钥推导被频控卡死至今未验证。决定整体换芯：数据层全面切换到 openhongguo（Maven Central `io.github.beilunyang:openhongguo`，锁 0.2.0），自研逆向链退役删除；UI 与 ClearKey 播放路径全部保留，内容密钥直接采用 SDK 本地计算的结果。应用维持个人自用学习定位，不上架、不分发、不商用（与上游免责声明对齐）。

## Considered Options

- 只借鉴思路修自研链：维护责任仍在自己身上，服务端每次改版继续人工跟。
- 半芯（只换播放链，元数据留 web 解析）：双链并行，长期养两套。
- Flutter/RN 平台跃迁：11 张票攒下的 TV 焦点工程全部作废。

## Consequences

- 上游 <1.0 可能有破坏性变更：锁版本 + watch 上游每日契约检查 workflow 兜底；断供时的逃生门是 fork 自构建 aar（依赖形态决策可逆）。
- APK 每 ABI 约 +8MB（内嵌 Go 运行时）。
- 红果接口再改版时本仓库无修复能力，只能等上游或 fork——这是用维护责任换稳定性的核心交换。
