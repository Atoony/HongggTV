package com.hgtv.app.net

import java.io.File
import java.security.SecureRandom

/** 设备身份：device_id / iid 每次安装随机生成后持久化（无需设备注册）。算法同 guoapp newHongguoDeviceID。 */
object HongguoDevice {
    private val random = SecureRandom()
    private val ID_19 = Regex("^\\d{19}$")

    /** 1e18 + rand64 % 8e18 → 19 位十进制正数字符串，BigInt ∈ [1e18, 9e18)（票09 第 7 项） */
    fun newDeviceId(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        var v = 0L
        for (b in bytes) v = (v shl 8) or (b.toLong() and 0xff)
        val u = v and Long.MAX_VALUE // 丢弃符号位，保证非负
        return (1_000_000_000_000_000_000L + (u % 8_000_000_000_000_000_000L)).toString()
    }

    // ---------- 持久化（票09 第 7 项：每次安装固定，重启不换身份） ----------

    /** 两行文本：deviceId / installId。 */
    fun encode(identity: HongguoIdentity): String = identity.deviceId + "\n" + identity.installId

    /** 严格校验（两位均 19 位正数）后解码；坏数据返回 null 走重新生成。 */
    fun decode(text: String): HongguoIdentity? {
        val parts = text.trim().split("\n")
        if (parts.size != 2) return null
        val (deviceId, installId) = parts
        if (!ID_19.matches(deviceId) || !ID_19.matches(installId)) return null
        if (deviceId.toLong() >= 9_000_000_000_000_000_000L) return null
        if (installId.toLong() >= 9_000_000_000_000_000_000L) return null
        return HongguoIdentity(deviceId, installId)
    }

    /** 读 dir/hg_identity.txt；无文件/坏数据则生成新身份并异步落盘（主线程免写盘）。 */
    fun loadOrNew(dir: File): HongguoIdentity {
        val file = File(dir, "hg_identity.txt")
        if (file.isFile) {
            runCatching { decode(file.readText()) }.getOrNull()?.let { return it }
        }
        val identity = HongguoIdentity.random()
        Thread {
            runCatching {
                dir.mkdirs()
                file.writeText(encode(identity))
            } // 写盘失败可容忍：下次启动重新生成，仅损风控画像连续性
        }.start()
        return identity
    }
}

data class HongguoIdentity(val deviceId: String, val installId: String) {
    companion object {
        fun random(): HongguoIdentity = HongguoIdentity(HongguoDevice.newDeviceId(), HongguoDevice.newDeviceId())
    }
}
