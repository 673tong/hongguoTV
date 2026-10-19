package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class MediaCryptoTest {
    private val key=hex("2b7e151628aed2a6abf7158809cf4f3c")
    private val iv=hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
    private val plain=hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710")
    private val encrypted=hex("874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff5ae4df3edbd5d35e5b4f09020db03eab1e031dda2fbe03d1792170a0f3009cee")
    @Test fun nistCtrVectorsAcrossEveryUnalignedRange() {
        val prefix=ByteArray(23){0x66}; val source=prefix+encrypted+ByteArray(12){0x77}
        val expected=prefix+plain+ByteArray(12){0x77}
        val prepared=MediaCrypto.Prepared(byteArrayOf(),listOf(MediaCrypto.Sample(23,87,iv)))
        for(start in source.indices) for(length in 1..minOf(29,source.size-start)) assertArrayEquals("$start+$length",expected.copyOfRange(start,start+length),MediaCrypto.decrypt(source.copyOfRange(start,start+length),start.toLong(),key,prepared))
    }
    @Test fun subsamplesPreserveClearBytesAndContinueCtrOffset() {
        val source=byteArrayOf(1,2,3)+encrypted.copyOfRange(0,21)+byteArrayOf(4,5)+encrypted.copyOfRange(21,64)
        val expected=byteArrayOf(1,2,3)+plain.copyOfRange(0,21)+byteArrayOf(4,5)+plain.copyOfRange(21,64)
        val prepared=MediaCrypto.Prepared(byteArrayOf(),listOf(MediaCrypto.Sample(3,24,iv),MediaCrypto.Sample(26,69,iv,21)))
        assertArrayEquals(expected,MediaCrypto.decrypt(source,0,key,prepared))
        assertArrayEquals(expected.copyOfRange(22,47),MediaCrypto.decrypt(source.copyOfRange(22,47),22,key,prepared))
    }
    private fun box(type: String,body: ByteArray)=be32(body.size+8)+type.toByteArray()+body
    private fun fixture(codec: String="avc1",auxSize: Int=8): ByteArray {
        val ftyp=box("ftyp","isom0000".toByteArray())
        fun moov(offset: Int): ByteArray {
            val entry=box("encv",ByteArray(78)+box("sinf",box("frma",codec.toByteArray())))
            val stsd=box("stsd",ByteArray(4)+be32(1)+entry)
            val stsz=box("stsz",ByteArray(4)+be32(16)+be32(1))
            val stsc=box("stsc",ByteArray(4)+be32(1)+be32(1)+be32(1)+be32(1))
            val stco=box("stco",ByteArray(4)+be32(1)+be32(4096))
            val saiz=box("saiz",ByteArray(4)+byteArrayOf(auxSize.toByte())+be32(1))
            val saio=box("saio",ByteArray(4)+be32(1)+be32(offset))
            return box("moov",box("trak",box("mdia",box("minf",box("stbl",stsd+stsz+stsc+stco+saiz+saio))))+box("free",iv.copyOf(8)))
        }
        val initial=ftyp+moov(0)
        return ftyp+moov(initial.size-8)
    }
    @Test fun headerRestoresOriginalAvcCodecAndSampleIndex() {
        val data=fixture(); val prepared=MediaCrypto.prepare(data,key)
        assertTrue(String(prepared.header).contains("avc1")); assertFalse(String(prepared.header).contains("encv")); assertFalse(String(prepared.header).contains("sinf"))
        assertEquals(1,prepared.samples.size); assertEquals(4096L,prepared.samples[0].start); assertEquals(4112L,prepared.samples[0].end)
        assertEquals(data.size,MediaCrypto.headerEnd(data,5000))
    }
    @Test(expected=java.io.IOException::class) fun truncatedAuxiliaryDataFailsClosed() { MediaCrypto.prepare(fixture(auxSize=16),key) }
    @Test fun sanitizeHeaderRestoresCodecWithoutTouchingPlainTracks() {
        // 加密 init 段：encv+sinft 被还原为 avc1，sinf 内容清零
        val encryptedHeader=fixture()
        val clean=MediaCrypto.sanitizeHeader(encryptedHeader)
        assertEquals(encryptedHeader.size,clean.size)
        assertTrue(String(clean).contains("avc1")); assertFalse(String(clean).contains("encv"))
        // sinf 的 body 已清零，只剩占位 free
        val sinfAt=String(clean).indexOf("free",String(clean).indexOf("stsd"))
        assertTrue(sinfAt>0)
        // 明文 init 段（无 sinf）原样返回
        val plainHeader=box("ftyp","isom0000".toByteArray())+
            box("moov",box("trak",box("mdia",box("minf",box("stbl",
                box("stsd",ByteArray(4)+be32(1)+box("avc1",ByteArray(20))))))))
        assertSame(plainHeader,MediaCrypto.sanitizeHeader(plainHeader))
    }
    @Test(expected=java.io.IOException::class) fun oversizedMoovRejected() { MediaCrypto.headerEnd(box("ftyp","isom0000".toByteArray())+be32(9000000)+"moov".toByteArray(),10000000) }
    @Test fun routerParserHandlesEscapedQuotesAndNestedBraces() {
        val data=ContentRepository.extractRouter("<script>window._ROUTER_DATA = {\"loaderData\":{\"x\":\"a}\\\"b\",\"list\":[1,2]}};</script>")
        assertEquals("a}\"b",data.getJSONObject("loaderData").getString("x"))
    }
    /** DASH 分片：traf 里的 trun(0xa05) + senc(flags=2, ivSize=16) 决定样本与 IV。 */
    private fun fragment(sampleSizes: List<Int>, clears: List<Int>, protected: List<Int>,
                         ivs: List<String>, dataOffset: Int = 150): ByteArray {
        // trun flags=0xa05 与站点真实分片一致：
        // 0x1(data_offset) | 0x4(first_sample_flags) | 0x200(sample_size) | 0x800(composition_offset)。
        // 因此布局是 count + data_offset + first_sample_flags + 每样本 (size, cto)。
        val trunBody=be32(0x00000a05)+be32(sampleSizes.size)+be32(dataOffset)+be32(0x02000000)+
            sampleSizes.flatMap { listOf(be32(it),be32(0)) }.fold(byteArrayOf()) { a,b -> a+b }
        val sencBody=be32(2)+be32(ivs.size)+ivs.indices.map { i ->
            hex(ivs[i])+byteArrayOf(0,1)+byteArrayOf((clears[i] shr 8).toByte(),(clears[i] and 255).toByte())+be32(protected[i])
        }.fold(byteArrayOf()) { a,b -> a+b }
        val traf=box("traf",box("tfhd",be32(0x020000)+be32(1))+box("trun",trunBody)+box("senc",sencBody))
        return box("moof",box("mfhd",be32(1))+traf)
    }
    @Test fun fragmentSamplesCarryAbsoluteOffsetsIvAndSubsampleSplit() {
        val moof=fragment(listOf(12525,1284,874),listOf(109,100,106),listOf(12416,1184,768),
            listOf("278cc9a2372e2d310000000000000000","278cc9a2372e2d310000000000000308","278cc9a2372e2d310000000000000352"))
        val base=1687L
        val samples=MediaCrypto.prepareFragment(moof,base)
        assertEquals(3,samples.size)
        // 每个样本只把 protected 段计入解密范围，clear 前缀跳过。
        assertEquals(base+150+109,samples[0].start); assertEquals(base+150+109+12416,samples[0].end)
        assertEquals("278cc9a2372e2d310000000000000000",samples[0].iv.hex())
        assertEquals("278cc9a2372e2d310000000000000308",samples[1].iv.hex())
        // 第二样本起点 = 首样本全长(12525) + dataOffset + clear(100)
        assertEquals(base+150+12525+100,samples[1].start)
        assertEquals(0L,samples[0].encryptedOffset)
    }
    @Test fun fragmentWithoutSencFailsClosed() {
        val traf=box("traf",box("trun",be32(0x000300)+be32(1)+be32(150)))
        val moof=box("moof",box("mfhd",be32(1))+traf)
        assertThrows(java.io.IOException::class.java) { MediaCrypto.prepareFragment(moof,0) }
    }
    @Test fun fragmentRespectsLittleEndianIvSizeFromSencEntries() {
        // 两个样本、单子样本各一场：验证 IV 递增与偏移累积不串位
        val moof=fragment(listOf(2000,3000),listOf(50,60),listOf(1950,2940),
            listOf("000102030405060708090a0b0c0d0e0f","101112131415161718191a1b1c1d1e1f"),dataOffset=200)
        val samples=MediaCrypto.prepareFragment(moof,0L)
        assertEquals(2,samples.size)
        assertEquals(200L+50,samples[0].start)
        assertEquals(200L+2000+60,samples[1].start)
        assertEquals("101112131415161718191a1b1c1d1e1f",samples[1].iv.hex())
    }
}
