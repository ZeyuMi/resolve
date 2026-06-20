package ai.tiiny.resolve

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureVault(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "resolve_secure_vault",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveFeishuSecret(appSecret: String) {
        prefs.edit().putString("feishu_app_secret", appSecret).apply()
    }

    fun loadFeishuSecret(): String? = prefs.getString("feishu_app_secret", null)

    fun saveFeishuTokens(accessToken: String, refreshToken: String?, expiresAtEpochMillis: Long?) {
        prefs.edit()
            .putString("feishu_access_token", accessToken)
            .putString("feishu_refresh_token", refreshToken)
            .putLong("feishu_expires_at", expiresAtEpochMillis ?: 0L)
            .apply()
    }

    fun loadFeishuTokens(): FeishuTokenSet? {
        val accessToken = prefs.getString("feishu_access_token", null) ?: return null
        return FeishuTokenSet(
            accessToken = accessToken,
            refreshToken = prefs.getString("feishu_refresh_token", null),
            expiresAtEpochMillis = prefs.getLong("feishu_expires_at", 0L).takeIf { it > 0L }
        )
    }

    fun clearFeishu() {
        prefs.edit()
            .remove("feishu_app_secret")
            .remove("feishu_access_token")
            .remove("feishu_refresh_token")
            .remove("feishu_expires_at")
            .apply()
    }

    fun saveBackendSession(accessToken: String, refreshToken: String?, expiresAtEpochMillis: Long?) {
        prefs.edit()
            .putString("backend_access_token", accessToken)
            .putString("backend_refresh_token", refreshToken)
            .putLong("backend_expires_at", expiresAtEpochMillis ?: 0L)
            .apply()
    }

    fun loadBackendSession(): BackendSession? {
        val accessToken = prefs.getString("backend_access_token", null) ?: return null
        return BackendSession(
            accessToken = accessToken,
            refreshToken = prefs.getString("backend_refresh_token", null),
            expiresAtEpochMillis = prefs.getLong("backend_expires_at", 0L).takeIf { it > 0L }
        )
    }

    fun clearBackendSession() {
        prefs.edit()
            .remove("backend_access_token")
            .remove("backend_refresh_token")
            .remove("backend_expires_at")
            .apply()
    }
}
