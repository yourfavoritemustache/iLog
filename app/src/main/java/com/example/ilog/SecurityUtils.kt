package com.example.ilog

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurityUtils {
    private const val PREFS_NAME = "secret_prefs"

    fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return try {
            createPrefs(context, masterKey)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                context.deleteSharedPreferences(PREFS_NAME)
            } catch (deleteError: Exception) {
                deleteError.printStackTrace()
            }
            
            try {
                createPrefs(context, masterKey)
            } catch (e2: Exception) {
                e2.printStackTrace()
                // Fallback to regular SharedPreferences to prevent crashing
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    private fun createPrefs(context: Context, masterKey: MasterKey): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
