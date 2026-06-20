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

    fun saveFeishuTokens(accessToken: String, refreshToken: String?) {
        prefs.edit()
            .putString("feishu_access_token", accessToken)
            .putString("feishu_refresh_token", refreshToken)
            .apply()
    }

    fun clearFeishu() {
        prefs.edit()
            .remove("feishu_app_secret")
            .remove("feishu_access_token")
            .remove("feishu_refresh_token")
            .apply()
    }
}
