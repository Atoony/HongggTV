# hg-tv

在 Android TV 上观看红果短剧的个人自用应用。UI 与播放交互自研；一切取数委托给 openhongguo SDK，应用不直接与红果接口打交道。

## Language

### 内容

**短剧（Series）**：一部完整的剧，以红果 seriesId 标识。电影也按短剧处理。
_Avoid_: 剧、剧卡、专辑

**分集（Episode）**：短剧中的一集，以 episodeId 标识，与红果 videoId 同一 ID 空间。
_Avoid_: 视频（UI 层禁说 videoId）

**季（Season）**：多季短剧中独立成部的一季；每一季本身就是一部短剧。
_Avoid_: 部、卷

**合集（Album）**：一部多季短剧的各季构成的整体；各季以合集 ID 关联，季序号从 1 起。
_Avoid_: 系列剧（红果类目 series_album 的官方叫法，口语里易与"短剧"本身混淆；UI 文案统一用"查看合集"）

**继续观看（Watch Entry）**：本机观看进度记录，按短剧存取。

**热播榜 / 热门词**：首页的榜单行 / 搜索页的热词行，均由数据源层供给。

**分类榜（Category Rank）**：榜单页按内容类型分列的榜单（全部 / 真人剧 / 漫剧 / AI 剧 / 系列剧），类目目录由数据源层直出；热播榜即其中"全部"一档。
_Avoid_: 题材分类（都市 / 古装 / 逆袭等是筛选维度，不是榜单）

### 数据与播放

**数据源层（Data Source）**：openhongguo SDK 及其在本应用的适配，是一切红果数据的唯一入口。
_Avoid_: 网络层、net 包（旧自研逆向链，已退役）

**domain 垫层（Domain Layer）**：本应用自己的短剧/分集值类型；UI 与测试只消费它，不直接消费 SDK 类型。
_Avoid_: 直接把 SDK 类型当模型用

**匿名设备（Anonymous Device）**：数据源层向红果注册的虚拟设备，所有请求以它的名义发送。
_Avoid_: 设备身份（旧自研 HongguoIdentity 概念）

**设备档案（Device Profile）**：匿名设备信息的落盘文件，不参与系统备份。

**播放模型（Playback）**：一个分集的播放信息：各清晰度的地址、编码与码率。
_Avoid_: 取流结果、流选项

**内容密钥（Content Key）**：解密加密分集（cenc-aes-ctr）的 AES 密钥，由数据源层本地计算。
_Avoid_: ClearKey（那是播放器消费密钥的 DRM 机制名，不是密钥本身）
