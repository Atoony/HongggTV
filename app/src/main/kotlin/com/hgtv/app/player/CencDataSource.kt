package com.hgtv.app.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CENC 整流解密 DataSource（已退出播放链，保留供探针/形态验证用）。
 *
 * 2026-10-22 票 04 改道：红果流实测为 sample 级 CENC（moov/senc/saio），
 * 全流 CTR 变换会破坏明文 box 头，本类不再用于播放；样本级解密改由
 * [LocalClearKeyCallback] + media3 ClearKey DRM session 完成。
 * zeroIv 模式仅当流为"整文件连续 CTR"时正确（探针用其验证形态假设）。
 */
class CencDataSource(
    private val upstream: HttpDataSource,
    private val key: ByteArray?,
    private val zeroIv: Boolean = true,
) : DataSource {

    class Factory(
        private val upstreamFactory: HttpDataSource.Factory,
        private val keyProvider: () -> ByteArray?,
    ) : DataSource.Factory {
        override fun createDataSource(): CencDataSource =
            CencDataSource(upstreamFactory.createDataSource(), keyProvider())
    }

    private var cipher: Cipher? = null
    private var positionInStream = 0L

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        positionInStream = dataSpec.position
        val len = upstream.open(dataSpec)
        key?.let { k ->
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(ByteArray(16)))
            // 跳过 dataSpec.position 字节的 keystream（保持 CTR 计数器与文件偏移对齐）
            var skip = positionInStream
            val dump = ByteArray(64 * 1024)
            while (skip > 0) {
                val n = minOf(skip, dump.size.toLong()).toInt()
                c.update(dump, 0, n)
                skip -= n
            }
            cipher = c
        }
        return len
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n > 0) {
            cipher?.let { c ->
                val transformed = c.update(buffer, offset, n)
                System.arraycopy(transformed, 0, buffer, offset, n)
            }
            positionInStream += n
        }
        return n
    }

    override fun getUri(): android.net.Uri? = upstream.uri

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun close() {
        upstream.close()
        cipher = null
    }
}
