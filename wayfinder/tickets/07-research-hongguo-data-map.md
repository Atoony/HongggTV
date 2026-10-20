---
id: 07
title: 研究：红果数据模型与接口对接清单
type: research
status: closed
assignee: research-agent-07
blocked-by: []
---

## Question

guoapp 已封装的红果接口，对照 TV App v1 功能（浏览/分类、搜索、详情+选集、播放、追剧+历史、收藏），数据覆盖如何？"系列/季"在红果数据模型里如何表达（支撑"系列剧集搜索"）？

## 调研点

1. 盘点 `guoapp/native/core/` 中红果相关封装（app_catalog、app_categories、app_rankings、app_recommendations、app_metadata、app_playback、app_danmaku、搜索相关等）：每接口的 endpoint、关键输入/输出字段。
2. **系列/季机制**：guoapp README 提到"自然季号"排序与"追剧发现新季后进入有更新"——找出其数据来源字段（series 元数据里的关联字段？还是靠名称匹配？），判断"按系列聚合展示/搜索"的数据可行性。
3. 对照 v1 功能清单标出：每功能的数据已覆盖 / 缺口（如收藏是否纯本地、追剧/历史本地存储结构）。
4. 产出**页面↔接口对接表草稿（事实部分）**：每个功能页需要调哪些接口、拿到什么字段。

## 要求

- 只读 guoapp 代码，不修改任何文件；遵守 guoapp/AGENTS.md（不看站源图片）。
- Resolution 写回本文件。

## Resolution

调研时间 2026-10-19，全部结论直接读自 `D:\CC-project\hg-tv\guoapp\native\core\` 与 `D:\CC-project\hg-tv\guoapp\lib\` 源码（未访问任何站源图片）。

### 1. 红果接口清单（endpoint / 输入 / 输出）

**App 侧 API**（host `https://api5-normal-sinfonlineb.fqnovel.com`，`provider_hongguo_app.go`；公共 query：`aid=8662, app_name=novelread, version_code=73532, device_id, iid` 等，UA 仿 `com.phoenix.read/73532`，请求经 `signHongguoRequest` 签名）：

| 用途 | endpoint | 关键输入 | 关键输出 |
| --- | --- | --- | --- |
| 目录/推荐 feed | `POST /reading/distribution/category/landpage/v/` | body：`req_scene`(default/comic_series/ai_series)、`offset`、`limit:18`、`session_id`、`select_items.genre=[short_play|comic_series|ai_series]`；目录固定 `sort=[online_time]`，推荐 `sort=[]` 且 `filter_ids`=已看 ID（≤540，逗号分隔） | `data.video_data[]`（每项含 `series_id_str, series_title, series_cover, series_intro, episode_cnt, episode_right_text, series_status(0连载/1完结), tags, category_list/category_schema, score, series_play_cnt, hot_score_data.score, first_visible_time, sub_title_list`）；分页 `data.next_offset/has_more/session_id` |
| 详情+选集 | `POST /novel/player/video_detail/v1/` | body：`{series_id}` | `data.video_data`（字段同上）+ `video_list[]{vid, vid_index, series_id}` → 每集生成 `Chapter{Title:"第N集", VideoURL:"hongguo-cenc://<vid>"}`；解析处强校验 ID 回显、集号连续、`episode_cnt` 齐全（`provider_hongguo_detail.go`） |
| 取流（第一级） | `POST /novel/player/video_model/v1/` | body：`{video_id, content_type:1, biz_param:{need_all_video_definition:true, video_platform:3}}` | `data.video_model`(对象或 JSON 字符串).`video_list[]`：`video_meta{codec_type,vheight,vwidth,definition}`、`encrypt_info{spade_a,encrypt,encryption_method:"cenc-aes-ctr"}`、`main_url/backup_url*/url_list`（可能 base64）→ 选清晰度最高、跳过 bytevc2 编码；`spade_a` 经 `hongguoContentKey` 解出 AES-128 CENC 密钥（`provider_hongguo_native_media.go`、`provider_hongguo_playback.go`） |
| 弹幕 | `POST /novel/commentapi/comment/list/{vid}/v1/` | 需额外头 `Comment-Source:601, Server-Channel:1000` + 评论签名；body：`{group_id:vid, group_type:30, comment_type:20, sort:1, count:90, aid:8662, business_param:{book_id:series_id, start_offset_time, playlet_item_duration}}` | `data.data_list[].comment{common.content.text, common.group_id/status, expand.offset_time, comment_id}`、`data.extra.next_query_danmaku_list_time`（下窗游标）、`common_list_info.cursor.danmaku_count`；按 30 秒窗口取、每窗 ≤90 条（`ui_playback_danmaku.go`） |

**Web 侧**（host `https://hongguoduanju.com`，页面内嵌 `_ROUTER_DATA` JSON，`provider_hongguo.go`）：

| 用途 | endpoint | 输出 |
| --- | --- | --- |
| 分类（兜底） | `GET /category/{real-drama|comic-drama|ai-drama|comic}?page=N` | `loaderData.category_page$.recommendList`（项内 `video_data` 字段同上）+ `pagination.totalPages`；page=1 会被 301 剥参数（记忆：2026-10-18 验证） |
| 详情（兜底） | `GET /detail?series_id=` | `detail_page$.seriesDetail{series_name, vid_list[]}` → 同样生成 `hongguo-cenc://` 分集 |
| 播放页（第二级取流） | `GET /player/{series_id}/{video_id}` | `player_page$.video_player_info{main_url/backup_url*, duration}` |
| 搜索 | `GET /search/{keyword}` | `search_(keyword)/page.searchList[]`（含 `video_data`）+ `totalCount` |
| 搜索联想/名称索引 | `GET /incent_resource/suggestion?app_id=8662&query=&count=` | `suggest_list[]{name, word_type, keyword, video_data}`；`word_type=short_play_name` 的项被当作"名称索引"直接转成 Drama 补进搜索结果（`provider_hongguo_search.go:fetchHongguoSearchNames`）；联想展示白名单 `{short_play_name, short_play_category, common_query, actor_name, short_play_actor}`，≤10 条、TTL 2 分钟 |
| 榜单 | `GET /rank/{hot-drama|hot-real-drama|hot-comic-drama|hot-ai-drama}?page=N` | 页面 JSON-LD `ItemList` 或 HTML `ol>article`；字段：排名、`/detail?series_id=` 回链取 ID、标题、封面、热度文本（`N[万亿]热度`）、`评分X`、标签（`provider_rankings_hongguo.go`）；4 块榜单定义在 `provider_rankings.go` |

**第三方备用取流（第三级）**：`GET https://djapi.999888456.xyz/api/hongguo/play?id=base64({content_type:1004, series_id, vid, video_platform:3})` → `v2.` 前缀加密响应（自定义 XOR/AES-CBC 解壳）→ `key_urls[]{name(含清晰度数字), src, kid, spade_a}`（`provider_hongguo_playback.go`）。

**补充元数据**：`app_metadata.go` 在 `OnlineDate` 缺失时抓 Web `/detail?series_id=` 解析上线日期（`provider_sort_metadata.go`：`first_visible_time` 时间戳，或 `sub_title_list` 的"今日/昨日/N天上新"文本）。热度取 `hot_score_data.score`（数值优先，退回 `.text`）。

**给 Flutter 的入口**（`app_runtime.go` nativeInput action）：`catalog`（浏览/搜索统一入口，红果带 query 时走搜索）、`cached`、`categories`、`recommendations`/`cachedRecommendations`、`rankingBoards`/`rankings`、`suggestions`、`detail`、`metadata`、`resolve`/`fallback`（取流+本地流服务）、`danmaku`、`cover`/`prepareCover`、下载类。目录缓存落盘 `catalogs.json`（TTL 15 分钟），榜单缓存 `rankings.json`（TTL 5 分钟，24 小时内可降级展示）。

**数据模型**：Go `Drama`（`models.go`）与传给 UI 的 `nativeDrama`（`app_runtime.go`：id/source/sourceId/title/description/cover/episodes/category/vip/heat/views/onlineDate/tags/releaseStatus）**均无季/系列字段，也无 score**——`Drama.Score` 在 `nativeNormalize` 时被丢弃，评分目前只有榜单 HTML 解析路径会填。Dart `models.dart` 的 `Drama` 同样无 score。

### 2. 系列/季机制（核心结论：纯标题启发式，无服务端字段）

- **数据来源是标题正则，不是元数据**：Go 端 `provider_hongguo_search_seasons.go` 与 Dart 端 `lib/hongguo_series.dart` 各自实现同一正则——标题末尾 `第\s*([0-9零〇一二两兩三四五六七八九十百]+)\s*([季部])` + 可选标点收尾（中文/阿拉伯数字，1–200）。红果任何接口响应中都没有 series 分组 ID 或季号字段。
- **三个用途**：
  1. **浏览排序"自然季号"**：`lib/catalog_sort.dart` 的 `naturalTitleCompare`——先把 `第N季` 归一化为阿拉伯数字再做数字感知自然排序（`第二季 < 第十季`）。
  2. **搜索缺季补全**：`completeHongguoSearchSeasons`（Go）在搜索结果里按"去后缀标题 key + 单位(季/部)"聚组；发现"已知 ≥2 季且中间/结尾缺季"的组时，构造 `标题+第X季`（中文数字优先，已知标签是阿拉伯数字则反转）补充查询名称索引，预算 32 次请求 / 25 秒；结果里同系列按季号升序排相邻。
  3. **追剧新季提醒**：`observeHongguoSeriesSeasons`（`lib/hongguo_series.dart`，`lib/local_store.dart:806` 调用）——对每部收藏（追剧）剧，在**本地候选池**（观看历史 + 收藏 + `seriesCandidates` 缓存 ≤2000 条，由目录刷新 `refreshDramas` 喂入）里找同 key 同 unit 且季号大于当前剧的条目，生成 `SeriesSeasonNotice{id,title,season,unit,read}` 存进 `FollowState.seriesSeasons`；`newSeasons>0 || newEpisodes>0` 即"有更新"筛选（`lib/follow_state.dart`）。
- **附带 UI**：详情页"相关推荐"季选择器 `hongguoSeriesEntries`（`lib/detail_screen.dart:780`），同一候选池。
- **"按系列聚合展示/搜索"的数据可行性**：**可行，但只能是客户端启发式**。(a) 搜索场景已天然可用：双通道搜索 + 缺季补全会把同系列各季拉齐并按季号排序，TV 端可直接按 base+unit 分组渲染成"系列卡片"；(b) 局限：非 `第N季/部` 命名的续作（如改名、加"番外"、纯数字尾缀）无法归组；anchor 无季后缀时按"整标题=base、季=1"处理并要求候选池里存在显式后缀同名剧才成组；(c) 若 TV 端不做全量目录缓存，详情页/追剧的系列发现需要即时调一次搜索（Go 端已有完整实现可复用）。

### 3. 对照 TV App v1 功能清单

| v1 功能 | 数据覆盖 | 依据 / 缺口 |
| --- | --- | --- |
| 浏览/分类首页 | ✅ 已覆盖 | App feed（3 分类 + 全部）、Web 分类兜底、4 个红果榜单、推荐 feed（genre 三选）；分页游标 offset+session_id，落盘可续 |
| 搜索 | ✅ 已覆盖 | 官网页 + 名称索引双通道合并、缺季补全、联想（≤10 条，word_type 过滤）；已知 quirk：Web 搜索 page=1 301 剥参（首轮验证记录） |
| 详情+选集 | ✅ 已覆盖 | App 详情接口给全量 `video_list`（第N集 + vid），完整性校验严格；Web 详情兜底 |
| 播放器 | ⚠️ 链路齐但线上全挂 | 三级取流（App video_model → Web player 页 → djapi 备用）封装完整（多清晰度/线路切换/CENC 密钥/本地流服务），但 2026-10-18 线上探针（`zz_live_probe_test.go`，记忆 `hongguo-api-verification.md`）三级全部失败：App 返回空、Web 页无 `video_player_info`（疑需登录）、djapi 解密后无可用媒体+密钥。**播放是当前关键阻塞**；弹幕接口本身通 |
| 追剧+历史 | ✅（纯本地） | SharedPreferences 按用户分键：`history`=`Map<id,WatchEntry{drama,episode,position,duration,updatedAt}>`、`followStates`=`Map<id,FollowState{status(想看/在看/已看),manuallyWatched,knownEpisodes,readEpisodes,seriesSeasons}>`、`mediaHistory`（≤300）；播放每 5 秒及退出写进度；"有更新"=新集+新季；无任何服务端账号（仅可选 LAN 点对点同步） |
| 收藏 | ✅（纯本地） | `favorites`=`Map<id,Drama>`（本地整卡快照 + 刷新时 merge），无服务端；卡片"更多"菜单入口 |
| 海报 | ✅ | `nativeDrama.cover` 直链可用；另有本地封面缓存 `covers-v1/`（sha256 文件名，256MB/2000 条 LRU，30 天） |

### 4. 页面↔接口对接表草稿（事实部分）

| TV 页面 | 需要的调用 | 用到的字段 |
| --- | --- | --- |
| 首页/分类浏览 | `categories`（红果为静态 3 分类）→ `catalog`（action，无 query；翻页由内核游标推进）→ 兜底 `cached` | `items[]{id,title,cover,episodes,category,heat,views,onlineDate,tags,releaseStatus}`、`hasMore`；排序/连载筛选在 Dart 端本地做（`catalog_sort.dart`） |
| 推荐 tab | `recommendations`（genre=short_play/comic_series/ai_series，内核自动带 seen 去重） | 同上；`hasMore` |
| 榜单页 | `rankingBoards` → `rankings`（boardId+page） | `items[]{rank,drama{id,title,cover,heat,tags},metric}`、`hasMore/totalPages/updatedText` |
| 搜索页 | 输入停顿 → `suggestions`（query） | `string[]`（≤10） |
| 搜索结果页 | `catalog`（source=hongguo & query=关键词；内核合并官网页+名称索引+季补全） | 结果按标题相关度+季号排序；`warning`（部分通道失败提示） |
| 详情页 | `detail`（drama.id） | `drama{title,description,cover,episodes,heat,views,onlineDate,tags,releaseStatus}` + `chapters[]{id,title,videoUrl(hongguo-cenc://),currentEpisode}`；系列"相关推荐"需候选池（复用 guoapp 本地池，或即时搜索同 key 结果） |
| 播放器 | `resolve`/`fallback`（drama+chapter）→ 失败切线路 `nativeNextPlayback` | `nativePlan{url(本地流服务地址),headers,key(AES CENC),quality,qualities[],session,routeIndex/routeCount,danmakuId}` |
| 弹幕 | `danmaku`（playbackSession+startMs+durationMs，30 秒窗滚动） | `items[]{id,text,timeMs}`、`nextMs/total` |
| 追剧/历史/收藏 | 无网络接口；LocalStore 本地读写（结构见上表） | `WatchEntry.episode/position/duration`、`FollowState.status/seriesSeasons` |
| 新季检测 | 依赖目录/搜索刷新喂 `seriesCandidates`（`refreshDramas`）→ `observeHongguoSeriesSeasons` | `SeriesSeasonNotice{id,title,season,unit,read}` |

**风险与注意**：1) 取流链线上不可用（见上），TV v1 若锁定红果播放需先解决或换播放站源；2) `nativeDrama` 无 score 字段，要显示评分需扩模型（数据源仅榜单 HTML 与 feed 的 `score` 原始字段）；3) 系列聚合是标题启发式，命名不规范的续作会漏；4) 弹幕接口走独立评论签名链路（`provider_hongguo_comment_sign.go`），复用时需一并迁移；5) App 接口依赖设备注册（device_id/iid 本地生成）与请求签名，签名实现位于 `provider_hongguo_sign.go`。
