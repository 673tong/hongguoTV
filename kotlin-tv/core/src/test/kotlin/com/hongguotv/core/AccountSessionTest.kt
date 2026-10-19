package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class AccountSessionTest {
    @Test fun `every passport status maps to a terminal or waiting state`() {
        assertEquals(AccountSession.Status.WAITING, AccountSession.mapStatus("new"))
        assertEquals(AccountSession.Status.SCANNED, AccountSession.mapStatus("scanned"))
        assertEquals(AccountSession.Status.SCANNED, AccountSession.mapStatus("SCANNING"))
        assertEquals(AccountSession.Status.CONFIRMED, AccountSession.mapStatus("confirmed"))
        assertEquals(AccountSession.Status.EXPIRED, AccountSession.mapStatus("expired"))
        assertEquals(AccountSession.Status.CANCELLED, AccountSession.mapStatus("canceled"))
        // 未知值不能当成成功，否则会在没有会话时显示"已登录"。
        assertEquals(AccountSession.Status.UNKNOWN, AccountSession.mapStatus("weird"))
        assertEquals(AccountSession.Status.UNKNOWN, AccountSession.mapStatus(""))
    }

    @Test fun `set-cookie merge keeps session and honours deletion`() {
        val initial = mapOf("passport_csrf_token" to "abc", "sessionid" to "old")
        val merged = AccountSession.mergeCookies(initial, listOf(
            "sessionid=new_value; Path=/; Domain=.fanqienovel.com; HttpOnly",
            "sid_guard=moredata%3D; Path=/",
            "temporary=; Path=/",                                  // 空值 = 删除
            "gone=whatever; Path=/; Max-Age=0",                    // 显式过期 = 删除
            "malformed",                                            // 没有 '=' 必须忽略
        ))
        assertEquals("new_value", merged["sessionid"])
        assertEquals("abc", merged["passport_csrf_token"])
        assertEquals("moredata%3D", merged["sid_guard"])
        assertFalse(merged.containsKey("temporary"))
        assertFalse(merged.containsKey("gone"))
        // 原表不能被就地修改，否则失败的请求会污染已保存的会话。
        assertEquals("old", initial["sessionid"])
    }

    @Test fun `empty set-cookie leaves session untouched`() {
        val initial = mapOf("sessionid" to "keep")
        assertSame(initial, AccountSession.mergeCookies(initial, emptyList()))
    }

    @Test fun `qr png base64 decodes to a real png`() {
        // 1x1 透明 PNG
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val bytes = AccountSession.decodePng(png)
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals("PNG".toByteArray().toList(), bytes.sliceArray(1..3).toList())
    }
}
