package com.hgtv.app.domain

/**
 * Domain 垫层（CONTEXT.md）：本应用自己的播放模型值类型，SDK→domain 只在数据源层
 * （OpenhongguoSource）一处映射，UI 与测试不咬 SDK 类型。
 */

/**
 * 取流错误（E-1xxx 取流 / E-2xxx 播放器，SPEC §5.2 错误页契约）。
 * 重试与匿名设备轮换全在数据源层 SDK 内建（Q7 共识），本异常只在重试耗尽后抛给 UI。
 */
class StreamException(val code: String, message: String) : Exception("[$code] $message") {
    companion object {
        fun fetch(message: String) = StreamException("E-1001", message)

        /** 无兼容线路（如仅剩 dash/hls 分段流、或无地址）。 */
        fun noLine(reason: String) = StreamException("E-1003", "无兼容播放线路：$reason")

        /** 加密分集拿不到可用内容密钥（受保护流无法解密，直接失败不进播放器）。 */
        fun keyUnavailable(detail: String) = StreamException("E-1004", "内容密钥不可用：$detail")
    }
}

/**
 * 播放模型（CONTEXT.md）：一个分集的播放信息。密钥每集独立、集内跨线路共享（票12 实测），
 * 故 [contentKey] 挂播放级而非线路级。
 */
data class Playback(
    val episodeId: String,
    /** CENC 加密分集的 AES-128 内容密钥（16 字节，[contentKeyFromHex] 还原）；null = 明文流。 */
    val contentKey: ByteArray?,
    /** 线路按质量降序，首选 = 第一项；urls = 主线路 + 备线路（播放中断时逐一切换）。 */
    val lines: List<Line>,
) {
    data class Line(
        val name: String, // "1080p"
        val quality: Int, // 高度像素（1080）
        val codec: String, // "bytevc1"
        val urls: List<String>,
    )
}

/**
 * SDK 内容密钥形态：16 字节 AES 密钥的小写十六进制串（与 ffmpeg -decryption_key 相同）
 * → 字节。非偶数长度或含非 hex 字符时返回 null。
 */
fun contentKeyFromHex(hex: String): ByteArray? {
    if (hex.isEmpty() || hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = hex[i * 2].digitToIntOrNull(16) ?: return null
        val lo = hex[i * 2 + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
