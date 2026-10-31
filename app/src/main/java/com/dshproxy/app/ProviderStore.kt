package com.dshproxy.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 加密保存供应商配置。 */
class ProviderStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        // EncryptedSharedPreferences 依赖 Keystore，个别 ROM/刷机后 Keystore 异常会抛异常，
        // 若不兜底会直接崩溃在启动阶段。这里失败则退化为普通 SharedPreferences（仍可用）。
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "dsh_proxy_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Throwable) {
            ProxyLog.add("加密存储不可用，退化为普通存储：${e.message}")
            try {
                // 加密库彻底坏掉时，删掉坏的加密文件再建普通存储
                context.deleteSharedPreferences("dsh_proxy_secure")
            } catch (_: Throwable) {}
            context.getSharedPreferences("dsh_proxy_plain", Context.MODE_PRIVATE)
        }
    }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null) ?: ProxyConfig().baseUrl
        set(v) = prefs.edit().putString(KEY_BASE_URL, v).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, null) ?: ProxyConfig().model
        set(v) = prefs.edit().putString(KEY_MODEL, v).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, null) ?: ""
        set(v) = prefs.edit().putString(KEY_API_KEY, v).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_START, v).apply()

    fun load(): ProxyConfig = ProxyConfig(
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
    )

    fun save(cfg: ProxyConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, cfg.baseUrl)
            .putString(KEY_MODEL, cfg.model)
            .putString(KEY_API_KEY, cfg.apiKey)
            .apply()
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_AUTO_START = "auto_start"
    }
}
