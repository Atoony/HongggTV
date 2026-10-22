package com.hgtv.app.player

import androidx.media3.common.C
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import java.io.IOException
import java.util.UUID

/**
 * 本地 ClearKey DRM 回调（票 04）：把 ClearKey CDM 请求中的 kids 与内存中的
 * 内容密钥（spade_a 解出）配对成 W3C 响应，全程无网络请求、密钥不落盘。
 * 样本级 CENC 解密由 media3 + MediaDrm(ClearKey) 完成。
 */
class LocalClearKeyCallback(private val contentKey: ByteArray) : MediaDrmCallback {

    @Throws(IOException::class)
    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
        val response = ClearKeyLicense.buildResponse(String(request.data, Charsets.UTF_8), contentKey)
            ?: throw IOException("ClearKey 请求不含 kids，无法配对密钥（流内可能缺 pssh）")
        return response.toByteArray(Charsets.UTF_8)
    }

    @Throws(IOException::class)
    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray = ByteArray(0)

    companion object {
        /** 每次 prepare 新建：密钥只进当次会话，open 即重设、无跨集残留。 */
        fun sessionManager(contentKey: ByteArray): DefaultDrmSessionManager =
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(LocalClearKeyCallback(contentKey))
    }
}
