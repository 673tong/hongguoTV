// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException

/** One playback stream. No listening socket or local HTTP service. */
class RemoteVideo(private val http: OkHttpClient, val info: StreamInfo): Closeable {
    /**
     * CENC 解密钩子：`info.key` 为 null 但分片是加密的（720p DASH）时，
     * 由外层注入一个按 (密文, IV) 解明的实现。
     *
     * 为什么不用 `info.key`：那条流的内容密钥封在站点播放器的 aw.wasm 里，
     * 解封后只存在于 wasm 线性内存，导不出 16 字节密钥，只能把数据交给它算。
     * 见 app 模块的 CencKeyBridge。
     */
    fun interface BlockDecryptor { fun decrypt(data: ByteArray, iv: ByteArray): ByteArray }
    var decryptor: BlockDecryptor? = null
    /** 非 0 表示分片流：样本表按 moof 逐片解析，[prepared] 只用来透传 init 段的明文头部。 */
    private var fragmentLength = 0L
    /** DASH 分离流时可选的独立音频轨；null 表示音视频在同一文件内。 */
    val audio: RemoteVideo? = info.audioUrl?.takeIf { it.isNotBlank() }?.let {
        RemoteVideo(http,StreamInfo(
            it,null,info.quality,
            initRange=info.audioInitRange ?: info.initRange,
            indexRange=info.audioIndexRange ?: info.indexRange,
            // 音轨同样是 CENC：共享同一个信封凭证（play-info 的 audio 节点通常不再单独给）。
            accessKey=info.accessKey.takeIf { info.audioEncrypted },
            audioEncrypted=info.audioEncrypted,
        ))
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
            // 分片流（DASH）：mtail 模式没有全局样本表，样本表按 moof 逐片解析，
            // 这里只保留 ftyp+moov 作为明文头部（读 0 偏移时透传）。
            // 有 info.key 的整段加密流仍走 MediaCrypto.prepare 的全局表。
            prepared=if(info.accessKey!=null && info.key==null) {
                fragmentLength=(index?.last ?: declared.last)+1L
                // 分片样本由外部解密器处理；init 段要先把 sinf/tenc 清理成明文轨道，
                // 否则解码器会把整条流当加密流拒绝（见 MediaCrypto.sanitizeHeader）。
                val clean=MediaCrypto.sanitizeHeader(prefix)
                if(clean.size!=prefix.size) throw IOException("视频索引清理异常")
                prefix=clean
                MediaCrypto.Prepared(prefix.copyOf(),emptyList())
            } else MediaCrypto.prepare(prefix,info.key)
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
        // 外部解密器优先：它对应"密钥在 wasm 里"的 CENC 流，此时 info.key 必为 null。
        decryptor?.let { return decryptWith(it,raw,start) }
        return MediaCrypto.decrypt(raw,start,info.key,prepared)
    }
    /**
     * 用外部解密器处理一段读取。
     *
     * 两条分支：
     * - 分片流（[fragmentLength] 非空）：每次读先把当前 `moof` 解析成样本表，再按需解密；
     * - 整段流：用 [MediaCrypto.prepareEncrypted] 建好的全局样本表。
     *
     * CTR 是流密码，只要保证"相对样本起点的字节偏移"不变，窗口裁剪不影响结果。
     */
    private fun decryptWith(engine: BlockDecryptor, data: ByteArray, start: Long): ByteArray {
        val result=data.copyOf(); val end=start+data.size
        val samples=if(fragmentLength>0L) fragmentSamples(start,end) else prepared.samples
        if(samples.isEmpty()) return result
        var low=0; var high=samples.size
        while(low<high) { val middle=(low+high) ushr 1; if(samples[middle].end<=start) low=middle+1 else high=middle }
        for(i in low until samples.size) {
            val sample=samples[i]; if(sample.start>=end) break
            val begin=maxOf(start,sample.start); val stop=minOf(end,sample.end); if(begin>=stop) continue
            // 落在本样本内的相对偏移（含子样本前缀的明文字节），用于对齐 CTR 计数器。
            val relative=begin-sample.start+sample.encryptedOffset
            if(relative%16!=0L) throw IOException("加密样本未按 16 字节对齐")
            val chunk=result.copyOfRange((begin-start).toInt(),(stop-start).toInt())
            val iv=sample.iv.copyOf(16)
            val plain=engine.decrypt(chunk,iv)
            if(plain.size!=chunk.size) throw IOException("解密块长度不一致")
            plain.copyInto(result,(begin-start).toInt())
        }
        return result
    }
    /** 分片模式下按需解析当前 `moof`；只缓存最近一片，避免长片堆积。 */
    private var cachedSamples: List<MediaCrypto.Sample> = emptyList()
    private fun fragmentSamples(start: Long, end: Long): List<MediaCrypto.Sample> {
        cachedSamples.firstOrNull { it.end>start }?.let { if(it.start<end) return cachedSamples }
        if(cachedSamples.any { it.start<=start && it.end>start }) return cachedSamples
        // 向前找最近的 moof 起点；分片头的 moof 通常就在 start 之前不到 16KB 处。
        val probeFrom=maxOf(0L,start-16384)
        val head=readRaw(probeFrom,minOf(total,start+16384)-probeFrom)
        var at=-1
        var cursor=0
        while(cursor+8<=head.size) {
            val size=head.u32(cursor).toLong()
            val type=String(head,cursor+4,4,Charsets.US_ASCII)
            if(size<8 || cursor+size>head.size) { cursor++; continue }
            if(type=="moof") { at=cursor; break }
            cursor+=size.toInt()
        }
        if(at<0) return emptyList()
        // moof 可能跨过探测窗口，按声明大小补足。
        val moofSize=head.u32(at).toInt()
        val absolute=probeFrom+at
        val bytes=if(at+moofSize<=head.size) head.copyOfRange(at,at+moofSize)
                  else head.copyOfRange(at,head.size)+readRaw(absolute+head.size-at,(moofSize-(head.size-at)).toLong())
        cachedSamples=MediaCrypto.prepareFragment(bytes,absolute)
        return cachedSamples
    }
    private fun readRaw(start: Long,length: Long): ByteArray {
        if(length<=0) return byteArrayOf()
        val capped=minOf(length,8L*1024*1024)
        val cached=prefix
        val available=(minOf(start+capped,cached.size.toLong())-start).coerceAtLeast(0).toInt()
        val first=if(available>0) cached.copyOfRange(start.toInt(),start.toInt()+available) else byteArrayOf()
        return if(available<capped) first+range(start+available,minOf(total,start+capped)-1).bytes else first
    }
    override fun close() { synchronized(calls) { closed=true; calls.toList().forEach { it.cancel() }; calls.clear() } }
}
