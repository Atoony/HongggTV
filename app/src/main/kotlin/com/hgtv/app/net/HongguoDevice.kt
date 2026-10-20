package com.hgtv.app.net

import java.security.SecureRandom

/** 设备身份：device_id / iid 每次安装随机生成（无需设备注册）。算法同 guoapp newHongguoDeviceID。 */
object HongguoDevice {
    private val random = SecureRandom()

    /** 1e18 + rand64 % 8e18 → 19 位十进制字符串 */
    fun newDeviceId(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        var v = 0L
        for (b in bytes) v = (v shl 8) or (b.toLong() and 0xff)
        val u = v and Long.MAX_VALUE // 丢弃符号位，保证非负
        return (1_000_000_000_000_000_000L + (u % 8_000_000_000_000_000_000L)).toString()
    }
}

data class HongguoIdentity(val deviceId: String, val installId: String) {
    companion object {
        fun random(): HongguoIdentity = HongguoIdentity(HongguoDevice.newDeviceId(), HongguoDevice.newDeviceId())
    }
}
