package com.hgtv.app.net

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 红果 App 主链客户端（JVM/Android 通用，HttpURLConnection 零依赖）。 */
class HongguoApi(
    val identity: HongguoIdentity = HongguoIdentity.random(),
    private val baseURL: String = "https://api5-normal-sinfonlineb.fqnovel.com",
    private val withLadonArgus: Boolean = true,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    companion object {
        const val UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

        val NUMERIC_ID = Regex("^\\d{10,25}$")
        private val QUALITY_NUMBER = Regex("\\d+")

        fun decodeBase64(value: String): ByteArray? {
            val v = value.trim()
            return try {
                java.util.Base64.getDecoder().decode(v)
            } catch (_: IllegalArgumentException) {
                try {
                    java.util.Base64.getDecoder().decode(v + "=".repeat((4 - v.length % 4) % 4))
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }

        /** 翻译自 guoapp hongguoContentKey：spade_a → AES-128 密钥 */
        fun contentKey(value: String): ByteArray {
            require(value.length <= 1024) { "红果媒体密钥数据过长" }
            val raw = decodeBase64(value) ?: throw IllegalArgumentException("红果媒体密钥编码无效")
            require(raw.size >= 3) { "红果媒体密钥编码无效" }
            val tagLength = ((raw[0].toInt() and 0xff) xor (raw[1].toInt() and 0xff) xor (raw[2].toInt() and 0xff)) - 48
            val contentLength = raw.size - tagLength - 1
            require(tagLength >= 1 && contentLength >= 33 && contentLength < raw.size) { "红果媒体密钥结构无效" }
            val seed = ((raw[raw.size - tagLength - 2].toInt() and 0xff) xor (raw[raw.size - tagLength - 1].toInt() and 0xff)).toByte()
            val tag = ByteArray(tagLength)
            for (i in 0 until tagLength) {
                tag[i] = (raw[raw.size - tagLength + i].toInt() and 0xff xor (seed.toInt() and 0xff)).toByte()
            }
            val tagText = String(tag, Charsets.US_ASCII)
            if (tagText == "app_v2" || tagText == "web_v2") throw IllegalArgumentException("红果媒体密钥版本暂不支持")
            val decoded = ByteArray(contentLength)
            var previousEven = 250.toByte()
            var previousOdd = 85.toByte()
            for (index in 0 until contentLength) {
                val current = raw[1 + index]
                var previous: Byte
                if (index % 2 == 0) {
                    previous = previousEven
                    previousEven = current
                } else {
                    previous = previousOdd
                    previousOdd = current
                }
                val bitCount = Integer.bitCount(index)
                decoded[index] = (((previous.toInt() and 0xff) xor (current.toInt() and 0xff)) - 21 - bitCount and 0xff).toByte()
            }
            val padding = String(decoded, 0, 1, Charsets.US_ASCII).toIntOrNull(36)
                ?: throw IllegalArgumentException("红果媒体密钥内容无效")
            if (contentLength - padding - 1 != 32) throw IllegalArgumentException("红果媒体密钥内容无效")
            val keyHex = String(decoded, 1, 32, Charsets.US_ASCII)
            val key = hexBytes(keyHex) ?: throw IllegalArgumentException("红果媒体密钥不是有效的 AES-128 密钥")
            require(key.size == 16) { "红果媒体密钥不是有效的 AES-128 密钥" }
            return key
        }

        fun hexBytes(hex: String): ByteArray? {
            if (hex.length % 2 != 0 || hex.isEmpty()) return null
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }

    // ---------- 模型 ----------

    data class Episode(val videoId: String, val index: Int)

    data class SeriesDetail(
        val seriesId: String,
        val title: String,
        val cover: String,
        val intro: String,
        val episodeCnt: Int,
        val remark: String,
        val releaseStatus: String, // finished / ongoing
        val categories: List<String>,
        val heat: String,
        val episodes: List<Episode>,
    )

    data class StreamOption(
        val name: String,
        val quality: Int,
        val codec: String,
        val urls: List<String>,
        val cencKey: ByteArray?,
    )

    class ApiException(message: String) : Exception(message)

    // ---------- 请求 ----------

    fun commonQuery(): Map<String, String> = mapOf(
        "aid" to "8662",
        "app_name" to "novelread",
        "version_code" to "73532",
        "version_name" to "7.3.5.32",
        "manifest_version_code" to "73532",
        "update_version_code" to "73532",
        "channel" to "update_64",
        "device_platform" to "android",
        "os" to "android",
        "ssmix" to "a",
        "device_type" to "25053RT47C",
        "device_brand" to "Redmi",
        "language" to "zh",
        "os_api" to "36",
        "os_version" to "16",
        "resolution" to "1280*2772",
        "dpi" to "520",
        "ac" to "wifi",
        "device_id" to identity.deviceId,
        "iid" to identity.installId,
    )

    /** 签名 + 发送；返回 (status, headers, body)。skipLadonArgus 供消融探针使用。 */
    fun request(
        path: String,
        body: JVal?,
        timeoutMs: Int = 20_000,
        skipLadonArgus: Boolean = false,
    ): Triple<Int, Map<String, List<String>>, ByteArray> {
        val now = nowMs()
        val ts = now / 1000
        val query = commonQuery().toMutableMap()
        query["_rticket"] = now.toString() // 生产链 query 不带 ts（guoapp hongguoAppRequest）
        val rawQuery = GoUrl.encode(query)
        val bodyBytes = body?.serialize()?.toByteArray(Charsets.UTF_8)

        val conn = URL("$baseURL$path?$rawQuery").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.doOutput = bodyBytes != null
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-XS-From-Web", "0")
        conn.setRequestProperty("Sdk-Version", "2")
        if (bodyBytes != null) conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (withLadonArgus && !skipLadonArgus) {
            conn.setRequestProperty("X-Ladon", HongguoSign.ladon(ts))
            conn.setRequestProperty("X-Argus", HongguoSign.argus(ts))
            conn.setRequestProperty("X-Tt-Request-Tag", "t=0;n=0")
            conn.setRequestProperty("Passport-Sdk-Version", "50561")
            conn.setRequestProperty("X-VC-Bdturing-Sdk-Version", "3.7.2.cn")
        }
        for ((k, v) in HongguoSign.signHeaders(rawQuery, bodyBytes, now)) conn.setRequestProperty(k, v)

        if (bodyBytes != null) {
            conn.outputStream.use { it.write(bodyBytes) }
        }
        val status = conn.responseCode
        val content = (if (status in 200..399) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            ?: ByteArray(0)
        val headers = conn.headerFields.entries
            .filter { it.key != null }
            .associate { (k, v) -> k to v }
        conn.disconnect()
        return Triple(status, headers, content)
    }

    // ---------- 业务：详情 ----------

    fun fetchDetail(seriesId: String): SeriesDetail {
        val (status, _, body) = request(
            "/novel/player/video_detail/v1/",
            JVal.obj("series_id" to JVal.str(seriesId)),
        )
        if (status != 200 || body.isEmpty()) {
            throw ApiException("详情请求失败 HTTP $status ${body.size}B")
        }
        val root = JsonParser.parse(String(body, Charsets.UTF_8))
            as? Map<String, Any?> ?: throw ApiException("详情响应非 JSON")
        val detail = JsonParser.nested(root, "data", "video_data")
            ?: throw ApiException("详情响应缺少 video_data")
        return parseDetail(detail, seriesId)
    }

    private fun parseDetail(detail: Map<String, Any?>, seriesId: String): SeriesDetail {
        val returnedId = JsonParser.str(detail, "series_id_str", "series_id")
        if (returnedId != seriesId) throw ApiException("红果 App 未返回所请求的剧集")
        val title = JsonParser.str(detail, "series_title", "series_name", "title").ifEmpty { seriesId }
        val cover = JsonParser.str(detail, "series_cover", "cover")
        val intro = JsonParser.str(detail, "series_intro", "video_desc")
        val countText = JsonParser.str(detail, "episode_cnt")
        var remark = JsonParser.str(detail, "episode_right_text")
        var releaseStatus = when (remark) {
            "已完结" -> "finished"
            else -> ""
        }
        when (JsonParser.str(detail, "series_status")) {
            "1" -> releaseStatus = "finished"
            "0" -> if (releaseStatus.isEmpty()) releaseStatus = "ongoing"
        }
        if (remark.isEmpty() && countText.isNotEmpty()) remark = "共${countText}集"
        val categories = ArrayList<String>()
        (JsonParser.arr(detail["category_list"]) ?: emptyList()).forEach {
            val name = JsonParser.str(JsonParser.obj(it), "name")
            if (name.isNotEmpty() && !categories.contains(name)) categories.add(name)
        }
        JsonParser.str(detail, "category_schema").takeIf { it.isNotEmpty() }?.let { schema ->
            runCatching {
                (JsonParser.parse(schema) as? List<Any?>)?.forEach {
                    val name = JsonParser.str(JsonParser.obj(it), "name")
                    if (name.isNotEmpty() && !categories.contains(name)) categories.add(name)
                }
            }
        }
        val heat = JsonParser.str(detail, "series_play_cnt", "play_cnt")

        val episodes = ArrayList<Episode>()
        val seenIds = HashSet<String>()
        val seenIndex = HashSet<Int>()
        for (row in JsonParser.arr(detail["video_list"]) ?: emptyList()) {
            val video = JsonParser.obj(row) ?: continue
            val videoId = JsonParser.str(video, "vid")
            val indexText = JsonParser.str(video, "vid_index")
            val index = indexText.toIntOrNull()
            if (index == null || index < 1 || !NUMERIC_ID.matches(videoId)) {
                throw ApiException("红果 App 分集编号或视频 ID 无效")
            }
            val owner = JsonParser.str(video, "series_id")
            if (owner.isNotEmpty() && owner != seriesId) throw ApiException("红果 App 返回了其他剧集的分集")
            if (seenIds.contains(videoId) || seenIndex.contains(index)) throw ApiException("红果 App 返回了重复分集")
            seenIds.add(videoId)
            seenIndex.add(index)
            episodes.add(Episode(videoId, index))
        }
        episodes.sortBy { it.index }
        val total = countText.toIntOrNull() ?: 0
        if (episodes.isEmpty() || total > episodes.size) throw ApiException("红果 App 未返回完整分集")
        episodes.forEachIndexed { i, ep ->
            if (ep.index != i + 1) throw ApiException("红果 App 分集列表不连续")
        }
        return SeriesDetail(
            seriesId = seriesId,
            title = title,
            cover = cover,
            intro = intro,
            episodeCnt = total,
            remark = remark,
            releaseStatus = releaseStatus,
            categories = categories,
            heat = heat,
            episodes = episodes,
        )
    }

    // ---------- 业务：取流 ----------

    /** 返回按质量降序的全部可选流；首选 = 第一项。 */
    fun fetchStreams(videoId: String): List<StreamOption> {
        if (!NUMERIC_ID.matches(videoId)) throw ApiException("红果视频 ID 无效")
        val body = JVal.obj(
            "video_id" to JVal.str(videoId),
            "content_type" to JVal.num(1),
            "biz_param" to JVal.obj(
                "need_all_video_definition" to JVal.bool(true),
                "video_platform" to JVal.num(3),
            ),
        )
        val (status, _, content) = request("/novel/player/video_model/v1/", body)
        if (status != 200 || content.isEmpty()) {
            throw ApiException("取流请求失败 HTTP $status ${content.size}B")
        }
        val root = JsonParser.parse(String(content, Charsets.UTF_8)) as? Map<String, Any?>
            ?: throw ApiException("取流响应非 JSON")
        val data = JsonParser.nested(root, "data") ?: throw ApiException("取流响应缺少 data")
        var model = JsonParser.obj(data["video_model"])
        if (model == null) {
            val encoded = JsonParser.str(data, "video_model")
            if (encoded.isNotEmpty()) model = JsonParser.parse(encoded) as? Map<String, Any?>
        }
        return parseStreams(model ?: throw ApiException("红果 App 播放信息格式异常"))
    }

    private fun parseStreams(model: Map<String, Any?>): List<StreamOption> {
        data class Scored(val option: StreamOption, val score: Int)
        val choices = ArrayList<Scored>()
        var keyErr: Exception? = null
        var variants = JsonParser.arr(model["video_list"]) ?: emptyList()
        if (variants.isEmpty()) {
            val rows = JsonParser.obj(model["video_list"])
            if (rows != null) variants = rows.keys.sorted().map { rows[it] }
        }
        for (row in variants) {
            val variant = JsonParser.obj(row) ?: continue
            val meta = JsonParser.nested(variant, "video_meta")
            val codec = JsonParser.str(meta, "codec_type").lowercase()
            val gearKey = JsonParser.str(variant, "gear_des_key").lowercase()
            if (codec == "bytevc2" || gearKey.contains("bytevc2")) continue
            val addresses = mediaAddresses(variant)
            if (addresses.isEmpty()) continue

            var key: ByteArray? = null
            val encryption = JsonParser.nested(variant, "encrypt_info")
            val spade = JsonParser.str(encryption, "spade_a")
            if (spade.isNotEmpty() || encryption?.get("encrypt") == true ||
                JsonParser.str(encryption, "encryption_method") == "cenc-aes-ctr"
            ) {
                try {
                    key = contentKey(spade)
                } catch (e: Exception) {
                    keyErr = e
                    continue
                }
            }
            var height = JsonParser.str(meta, "vheight").toIntOrNull() ?: 0
            val definition = QUALITY_NUMBER.find(JsonParser.str(meta, "definition"))?.value?.toIntOrNull() ?: 0
            if (definition > 0) height = definition
            else {
                val width = JsonParser.str(meta, "vwidth").toIntOrNull() ?: 0
                if (width > 0 && (height == 0 || width < height)) height = width
            }
            var score = height * 10
            if (codec == "h264" || codec == "avc1") score++
            choices.add(
                Scored(
                    StreamOption(
                        name = JsonParser.str(meta, "definition", "gear_name").ifEmpty { "${height}p" },
                        quality = height,
                        codec = codec,
                        urls = addresses,
                        cencKey = key,
                    ),
                    score,
                )
            )
        }
        choices.sortByDescending { it.score }
        if (choices.isEmpty()) {
            throw keyErr ?: ApiException("红果 App 未返回兼容的媒体，已跳过不支持的编码")
        }
        return choices.map { it.option }
    }

    private fun mediaAddresses(info: Map<String, Any?>): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        fun add(value: Any?) {
            when (value) {
                is String -> {
                    var address = value.trim()
                    if (address.length > 8192) return
                    if (!address.startsWith("http://") && !address.startsWith("https://")) {
                        val decoded = decodeBase64(address) ?: return
                        address = String(decoded, Charsets.UTF_8).trim()
                    }
                    if ((address.startsWith("http://") || address.startsWith("https://")) && seen.add(address)) {
                        out.add(address)
                    }
                }
                is List<*> -> value.forEach { add(it) }
            }
        }
        for (k in listOf("main_url", "backup_url", "backup_url_1", "backup_url_2", "backup_urls", "url_list")) {
            add(info[k])
        }
        return out
    }
}
