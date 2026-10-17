// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException

/** One playback stream. No listening socket or local HTTP service. */
class RemoteVideo(private val http: OkHttpClient, val info: StreamInfo): Closeable {
    /** DASH 分离流时可选的独立音频轨；null 表示音视频在同一文件内。 */
    val audio: RemoteVideo? = info.audioUrl?.takeIf { it.isNotBlank() }?.let {
        RemoteVideo(http,StreamInfo(it,null,info.quality,initRange=info.audioInitRange ?: info.initRange,indexRange=info.audioIndexRange ?: info.indexRange))
    }
    @Volatile private var closed=false
    private val calls=mutableSetOf<Call>()
    data class Range(val bytes: ByteArray,val total: Long)
    var total: Long=0; private set
    private lateinit var prepared: MediaCrypto.Prepared
    @Volatile private var prefix=byteArrayOf()
    private fun range(start: Long,end: Long): Range {
        if(closed) throw IOException("播放请求已取消")
        require(start>=0 && end>=start && end-start<8*1024*1024)
        val request=Request.Builder().url(info.url).header("User-Agent",VendorConstants.VIDEO_UA).header("Referer","https://novel.snssdk.com/").header("Range","bytes=$start-$end").header("Accept-Encoding","identity").build()
        val call=http.newCall(request)
        synchronized(calls) { if(closed) throw IOException("播放请求已取消"); calls+=call }
        try {
            return RequestScope.execute(call) { response ->
                val body=response.body ?: throw IOException("视频分段为空")
                if(response.code==200) { // CDN 忽略 Range 时退化为整段读取并切片（与服务端 hongguo.cjs 行为一致）
                    val bytes=body.source().readByteArray()
                    val full=bytes.size.toLong()
                    if(full<=0) throw IOException("视频分段为空")
                    if(full>64L*1024*1024) throw IOException("视频服务器不支持分段读取（HTTP 200，资源过大）")
                    if(total!=0L && full!=total) throw IOException("视频资源已变化，请重试")
                    total=full
                    val last=minOf(end,full-1)
                    val n=(last-start+1).toInt()
                    return@execute Range(bytes.copyOfRange(start.toInt(),(start+n).toInt().coerceAtMost(bytes.size)),full)
                }
                if(response.code!=206) throw IOException("视频服务器不支持分段读取（HTTP ${response.code}）")
                val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty()) ?: throw IOException("视频分段响应异常")
                val (begin,finish,size)=match.destructured
                val full=size.toLong(); val last=minOf(end,full-1)
                if(begin.toLong()!=start || finish.toLong()!=last || full<=0) throw IOException("视频分段范围不匹配")
                if(total!=0L && full!=total) throw IOException("视频资源已变化，请重试")
                val n=(last-start+1).toInt()
                val bytes=body.source().readByteArray(n.toLong())
                Range(bytes,full)
            }
        } finally { synchronized(calls) { calls-=call } }
    }
    @Synchronized fun prepare(): RemoteVideo {
        if(::prepared.isInitialized) return this
        val declared=info.initRange
        if(declared != null) { // DASH 分片：init 段自带 ftyp+moov，sidx 在紧随其后的索引段里
            // 一次性把 init + index 两段取回来拼成"伪头部"：偏移与真实文件一致，
            // 因此样本索引里的绝对偏移可以直接用于后续分段读取。
            val index=info.indexRange
            val wanted=index?.last ?: declared.last
            // init+index 通常只有 1~2KB，留足 512KB 余量但仍要保证覆盖声明区间。
            if(wanted>=512*1024L) throw IOException("视频分片索引过大")
            val head=range(0,wanted)
            total=head.total
            prefix=head.bytes
            if(prefix.size<8 || String(prefix,4,4,Charsets.US_ASCII)!="ftyp") throw IOException("视频容器格式异常")
            // moov 必须完整落在已取回的头部里，否则样本表会被截断。
            if(prefix.size.toLong()<=declared.last) throw IOException("视频索引不完整")
            if(index != null && prefix.size.toLong()<=index.last) throw IOException("视频分片索引不完整")
            // 无内容密钥时 MediaCrypto 返回空样本表，读取层原样透传（明文分片）。
            prepared=MediaCrypto.prepare(prefix,info.key)
            if(prepared.samples.any { it.end>total }) throw IOException("视频样本超出资源大小")
            return this
        }
        val first=range(0,256*1024-1); total=first.total
        prefix=first.bytes
        val end=MediaCrypto.headerEnd(first.bytes,total)
        val header=if(end>first.bytes.size) first.bytes+range(first.bytes.size.toLong(),end-1L).bytes else first.bytes.copyOf(end)
        // 明文 DASH/MP4 无内容密钥，prepare 会返回空样本表，decrypt 原样透传。
        prepared=MediaCrypto.prepare(header,info.key)
        if(prepared.samples.any { it.end>total }) throw IOException("视频样本超出资源大小")
        return this
    }
    /** Bounded warm-up. Retains raw bytes so decryption still uses the absolute sample offset. */
    @Synchronized fun warm(): RemoteVideo {
        prepare()
        val size=minOf(total,1024*1024L).toInt()
        if(prefix.size<size) prefix=prefix+range(prefix.size.toLong(),size-1L).bytes
        return this
    }
    fun read(start: Long,length: Int): ByteArray {
        require(length in 1..1024*1024 && start>=0)
        if(closed) throw IOException("播放请求已取消")
        if(start>=total) return byteArrayOf()
        val cached=prefix; val end=minOf(total,start+length)
        val available=(minOf(end,cached.size.toLong())-start).coerceAtLeast(0).toInt()
        val first=if(available>0) cached.copyOfRange(start.toInt(),start.toInt()+available) else byteArrayOf()
        val raw=if(start+available<end) first+range(start+available,end-1).bytes else first
        return MediaCrypto.decrypt(raw,start,info.key,prepared)
    }
    override fun close() { synchronized(calls) { closed=true; calls.toList().forEach { it.cancel() }; calls.clear() } }
}
