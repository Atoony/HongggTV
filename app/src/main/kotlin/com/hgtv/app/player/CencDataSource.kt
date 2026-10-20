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
 * CENC 解密 DataSource（SPEC §5.3）：边读 CDN 流边 AES-CTR 解密，不建本地代理。
 *
 * 红果 CDN 流为 CENC-AES-CTR 加密的 MP4：mp4 box 头与部分轨道数据明文、
 * sample 数据密文，IV 存于 mp4 box（tenc/senc）。本 DataSource 实现
 * 全流 AES-CTR 变换（CTR 模式对明文字节区解密=再加密不破坏？
 * ——否：CTR 是异或流，明文区经过会变成密文）。因此仅当实测流为
 * "整文件连续 CTR" 时可用 zeroIv 模式；否则由 [CencMp4DataSource] 做 sample 级解密。
 *
 * 当前实现：zeroIv 模式（整文件 CTR，IV=0）。实测形态若为 sample 级 CENC，
 * 在此扩展 mp4 box 解析（moov/tenc 默认 IV + moof/senc 每 sample IV）。
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
