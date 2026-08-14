package com.ccarpo.syncbook

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "syncbook-session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var token: String?
        get() = preferences.getString("token", null)
        set(value) {
            preferences.edit().apply {
                if (value == null) remove("token") else putString("token", value)
            }.apply()
        }
}
