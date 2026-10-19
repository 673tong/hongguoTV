// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.AccountSession
import org.json.JSONObject

/**
 * 扫码登录的会话存储。
 *
 * 只保存 passport 下发的 Cookie（name=value）与昵称，放在应用私有的
 * SharedPreferences 里，不写日志、不进备份包，避免账号凭证随备份外流。
 */
class AccountStore(context: Context) : AccountSession.SessionStore {
    private val prefs = context.applicationContext.getSharedPreferences("hongguotv-account", Context.MODE_PRIVATE)

    override fun read(): Map<String, String> {
        val raw = prefs.getString(KEY_COOKIES, "") ?: return emptyMap()
        if (raw.isEmpty()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optString(it, "") }.filterValues { it.isNotEmpty() }
        }.getOrDefault(emptyMap())
    }

    override fun write(cookies: Map<String, String>) {
        val json = JSONObject()
        cookies.forEach { (name, value) -> if (name.isNotEmpty()) json.put(name, value) }
        prefs.edit().apply {
            if (cookies.isEmpty()) remove(KEY_COOKIES) else putString(KEY_COOKIES, json.toString())
        }.apply()
    }

    /** 上次成功登录时记下的昵称，用于在设置页秒开显示，避免每次进设置都请求网络。 */
    var nickname: String
        get() = prefs.getString(KEY_NICKNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NICKNAME, value).apply()

    fun clearNickname() { prefs.edit().remove(KEY_NICKNAME).apply() }

    private companion object {
        const val KEY_COOKIES = "cookies"
        const val KEY_NICKNAME = "nickname"
    }
}
