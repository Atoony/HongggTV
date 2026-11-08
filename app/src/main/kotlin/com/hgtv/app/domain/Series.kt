package com.hgtv.app.domain

/**
 * 元数据垫层（CONTEXT.md，票14）：本应用自己的短剧/分集值类型，SDK→domain 只在数据源层
 * （OpenhongguoSource）一处映射，UI 与测试不咬 SDK 类型。
 */

/** 短剧列表卡（榜单行 / 搜索结果 / 直达候选）：列表页用的轻量形态。 */
data class SeriesCard(
    val seriesId: String,
    val title: String,
    val cover: String,
    /** 总集数；0 = 未知（不显示集数角标）。 */
    val episodeCount: Int = 0,
    /** 榜单名次（1 起）；非榜单行为 0（不显示 NO.x 角标）。 */
    val rank: Int = 0,
)

/** 搜索联想词：[text] 展示、[query] 提交搜索。 */
data class Suggestion(val text: String, val query: String)

/** 分集：number 从 1 起连续（选集网格/续播定位用）。ID 空间注记见 OpenhongguoSource.playback。 */
data class Episode(val episodeId: String, val number: Int)

/** 短剧详情 + 选集（详情页数据）。 */
data class SeriesDetail(
    val seriesId: String,
    val title: String,
    val cover: String,
    val intro: String,
    /** 总集数（详情页"共 N 集"）。 */
    val episodeCnt: Int,
    /** 集数旁角标文案（"全2集"/"更新至12集"）。 */
    val remark: String,
    /** 已完结 = true；连载中（含停更/今日更新）= false。 */
    val finished: Boolean,
    val categories: List<String>,
    /** 热度展示文案（"红果热度值12万"）。 */
    val heat: String,
    /** 属于多季合集 = true（详情页"查看合集"按钮门控，D6-A：单季剧零打扰零配额）。 */
    val isMultiSeason: Boolean = false,
    /** 本季在合集内的季序号（1 起；0 = 单季/未知）。仅用于合集页当前季高亮（Q7-B：详情页标题不标季角标）。 */
    val seasonIndex: Int = 0,
    /** 按 number 排序去重后 1..n 连续重编号的选集。 */
    val episodes: List<Episode>,
)

/** 合集（CONTEXT.md）：多季短剧各季构成的整体；简介只在合集级，季无每季简介。 */
data class Album(
    val title: String,
    val description: String,
    val seasons: List<Season>,
)

/** 季（CONTEXT.md）：合集内的一项，本身即一部短剧（以 seriesId 打开详情）。 */
data class Season(
    val seriesId: String,
    /** 季序号，从 1 起。 */
    val index: Int,
    /** 总集数；0 = 未上线季（合集页置灰不可点）。 */
    val episodeCount: Int,
    /** 已完结 = true。注意：SDK seasons() 主路径只有当前请求季携带真实 status，其余季为默认值——文案可能偏保守。 */
    val finished: Boolean,
) {
    /** 已上线 = 有分集可看。 */
    val released: Boolean get() = episodeCount > 0
}

/** 季卡状态文案（合集页）：未上线优先于一切（episodeCount=0 的季即使带完结状态也是未上线）。 */
fun seasonStatusLabel(season: Season): String = when {
    !season.released -> "未上线"
    season.finished -> "已完结"
    else -> "连载中"
}
