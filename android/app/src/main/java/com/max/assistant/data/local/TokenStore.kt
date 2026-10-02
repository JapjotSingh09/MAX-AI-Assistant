package com.max.assistant.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// Stores the session token encrypted. The encryption key lives in the Android Keystore,
// so the token can't be read from a backup or by other apps.
class TokenStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "max_secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit().apply { if (value == null) remove("token") else putString("token", value) }.apply()

    var displayName: String
        get() = prefs.getString("name", "") ?: ""
        set(value) = prefs.edit().putString("name", value).apply()

    fun hasToken() = token != null
    fun clear() = prefs.edit().clear().apply()
}
