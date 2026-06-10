package com.example.ilog

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class NotificationEntry(
    val title: String,
    val text: String,
    val postTime: Long,
    val packageName: String = ""
)

class NotificationService : NotificationListenerService() {

    companion object {
        private var instance: NotificationService? = null
        fun getActiveNotifications(): Array<StatusBarNotification>? {
            return instance?.activeNotifications
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        AppLog.d(this, TAG, "Notification Listener Connected")
        
        // Backfill history with currently active notifications for selected apps
        try {
            val sharedPrefs = getSharedPreferences("iLogPrefs", MODE_PRIVATE)
            val selectedApps = sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
            
            activeNotifications?.forEach { sbn ->
                if (sbn.packageName in selectedApps) {
                    val extras = sbn.notification.extras
                    val title = (extras.getString("android.title") ?: "").replace("\n", " ")
                    val text = (extras.getCharSequence("android.text")?.toString() ?: "").replace("\n", " ")
                    saveToHistory(sbn.packageName, title, text, sbn.postTime)
                }
            }
        } catch (e: Exception) {
            AppLog.e(this, TAG, "Failed to backfill history", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val TAG = "NotificationService"

    private var supabaseUrl: String? = null
    private var supabaseKey: String? = null
    private var supabaseTable: String = "transaction_fact_android"
    private var supabase: SupabaseClient? = null

    private fun initSupabase(): Boolean {
        val prefs = SecurityUtils.getEncryptedPrefs(this)
        val url = prefs.getString("supabase_url", null)
        val key = prefs.getString("supabase_key", null)
        supabaseTable = prefs.getString("supabase_table", "transaction_fact_android") ?: "transaction_fact_android"

        if (url.isNullOrBlank() || key.isNullOrBlank()) {
            AppLog.e(this, TAG, "Supabase URL or Key not configured")
            return false
        }

        if (supabase != null && url == supabaseUrl && key == supabaseKey) {
            return true
        }

        return try {
            supabaseUrl = url
            supabaseKey = key
            supabase = createSupabaseClient(url, key) {
                install(Postgrest)
            }
            AppLog.d(this, TAG, "Supabase client initialized: $url")
            true
        } catch (e: Exception) {
            AppLog.e(this, TAG, "Supabase initialization failed", e)
            false
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        
        val sharedPrefs = getSharedPreferences("iLogPrefs", MODE_PRIVATE)
        val selectedApps = sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
        
        if (packageName !in selectedApps) return

        AppLog.d(this, TAG, "Notification received from: $packageName")

        if (!initSupabase()) return

        val extras = sbn.notification.extras
        val title = (extras.getString("android.title") ?: "").replace("\n", " ")
        val text = (extras.getCharSequence("android.text")?.toString() ?: "").replace("\n", " ")
        val fullContent = "$title: $text"
        
        getSharedPreferences("iLogExamples", MODE_PRIVATE).edit()
            .putString("${packageName}_title", title)
            .putString("${packageName}_text", text)
            .apply()

        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(sbn.postTime))
        
        val appName = try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            packageName
        }

        // Save to local history
        saveToHistory(packageName, title, text, sbn.postTime)

        // Compilation of variables
        val rulesPrefs = getSharedPreferences("iLogRules", MODE_PRIVATE)
        val rulesJson = rulesPrefs.getString(packageName, "[]") ?: "[]"
        val rules = try {
            Json.decodeFromString<List<ExtractionRule>>(rulesJson)
        } catch (e: Exception) {
            AppLog.e(this, TAG, "Failed to parse rules for $packageName", e)
            emptyList<ExtractionRule>()
        }

        val extractedData = mutableMapOf<String, String>()
        rules.forEach { rule ->
            val varName = rule.varName.trim()
            if (varName.isNotBlank()) {
                try {
                    val value = if (rule.source == "Fixed Value") {
                        rule.fixedValue
                    } else {
                        val contentToProcess = rule.source
                            .replace("{title}", title, ignoreCase = true)
                            .replace("{text}", text, ignoreCase = true)
                            .let { 
                                if (it.equals("Title", ignoreCase = true)) title 
                                else if (it.equals("Text", ignoreCase = true)) text 
                                else it 
                            }

                        if (rule.regex.isBlank()) {
                            contentToProcess
                        } else {
                            val regex = Regex(rule.regex, RegexOption.IGNORE_CASE)
                            when (rule.matchType) {
                                "Group 1" -> regex.find(contentToProcess)?.groupValues?.getOrNull(1)
                                "Full Match" -> if (regex.matches(contentToProcess)) contentToProcess else null
                                "First Match" -> regex.find(contentToProcess)?.value
                                else -> regex.find(contentToProcess)?.value
                            }
                        }
                    }
                    
                    if (value != null) {
                        val processedValue = if (rule.dataType == "Number" || rule.dataType == "Decimal") {
                            parseAmount(value)?.toString() ?: value
                        } else {
                            value
                        }
                        extractedData[varName.lowercase()] = processedValue
                    }
                } catch (e: Exception) {
                    AppLog.e(this, TAG, "Extraction error for $varName", e)
                }
            }
        }

        val mappingsPrefs = getSharedPreferences("iLogMappings", MODE_PRIVATE)
        val mappingsJson = mappingsPrefs.getString(packageName, null)
        val mappings = try {
            if (mappingsJson != null) Json.decodeFromString<List<BodyMapping>>(mappingsJson)
            else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val resolutionContext = mutableMapOf(
            "date" to date,
            "raw" to fullContent,
            "notification_raw" to fullContent,
            "app" to appName,
            "source" to appName,
            "package" to packageName
        )
        extractedData.forEach { (k, v) -> resolutionContext[k.lowercase()] = v }

        val finalBody = buildJsonObject {
            if (mappings.isEmpty()) {
                put("app_name", appName)
                put("raw_notification", fullContent)
            } else {
                mappings.forEach { mapping ->
                    val key = mapping.key
                    if (key.isBlank()) return@forEach

                    var resolvedValue = mapping.valueTemplate
                    resolutionContext.forEach { (name, value) ->
                        resolvedValue = resolvedValue.replace("{$name}", value, ignoreCase = true)
                    }
                    
                    // Identify if this is intended to be a numeric field based on key name or template
                    val isNumericField = key.lowercase().let {
                        it == "amount" || it.contains("price") || it.contains("total") || it.contains("value")
                    } || mapping.valueTemplate.lowercase().let {
                        it.contains("amount") || it.contains("price") || it.contains("total")
                    }

                    // If it still contains placeholders, it failed to resolve
                    if (resolvedValue.contains("{") || resolvedValue.contains("}")) {
                        if (isNumericField) {
                            put(key, null as Double?)
                        } else {
                            put(key, null as String?)
                        }
                        return@forEach
                    }

                    if (isNumericField) {
                        val parsed = parseAmount(resolvedValue)
                        if (parsed != null) {
                            put(key, parsed)
                        } else {
                            put(key, null as Double?)
                        }
                    } else {
                        // For non-explicitly numeric fields, try to see if it's a number anyway
                        // but only if it's a clean number (no extra text)
                        val numericValue = resolvedValue.toDoubleOrNull()
                        if (numericValue != null && !mapping.valueTemplate.contains("{")) {
                            put(key, numericValue)
                        } else {
                            put(key, resolvedValue)
                        }
                    }
                }
            }
        }

        AppLog.d(this, TAG, "Attempting POST to $supabaseTable with body: $finalBody")

        supabase?.let { client ->
            scope.launch {
                try {
                    client.from(supabaseTable).insert(finalBody)
                    AppLog.d(this@NotificationService, TAG, "Successfully sent to Supabase")
                } catch (e: Exception) {
                    val errorMsg = e.message ?: "Unknown error"
                    AppLog.e(this@NotificationService, TAG, "Primary request failed: $errorMsg", e)
                    
                    // Fallback: Try sending only notification info
                    try {
                        val fallbackBody = buildJsonObject {
                            put("app_name", appName)
                            put("raw_notification", fullContent)
                        }
                        AppLog.d(this@NotificationService, TAG, "Attempting clean fallback request")
                        client.from(supabaseTable).insert(fallbackBody)
                        AppLog.d(this@NotificationService, TAG, "Fallback request successful")
                    } catch (e2: Exception) {
                        AppLog.e(this@NotificationService, TAG, "Fallback request also failed: ${e2.message}", e2)
                    }
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
    }

    private fun saveToHistory(packageName: String, title: String, text: String, postTime: Long) {
        val historyPrefs = getSharedPreferences("iLogHistory", MODE_PRIVATE)
        val historyJson = historyPrefs.getString(packageName, "[]") ?: "[]"
        try {
            val history = Json.decodeFromString<List<NotificationEntry>>(historyJson).toMutableList()
            
            // Avoid duplicates (same package and time)
            if (history.any { it.packageName == packageName && it.postTime == postTime }) {
                return
            }

            history.add(0, NotificationEntry(title, text, postTime, packageName))
            // Keep last 100 notifications per app
            val limitedHistory = if (history.size > 100) history.take(100) else history
            historyPrefs.edit().putString(packageName, Json.encodeToString(limitedHistory)).apply()
        } catch (e: Exception) {
            AppLog.e(this, TAG, "Failed to save history for $packageName", e)
            // If it failed to parse, start fresh
            val newHistory = listOf(NotificationEntry(title, text, postTime, packageName))
            historyPrefs.edit().putString(packageName, Json.encodeToString(newHistory)).apply()
        }
    }
}

fun parseAmount(input: String?): Double? {
    if (input == null) return null
    val clean = input.filter { it.isDigit() || it == '.' || it == ',' }
    if (clean.isEmpty()) return null

    val lastDot = clean.lastIndexOf('.')
    val lastComma = clean.lastIndexOf(',')

    return when {
        // Both separators exist: the last one is the decimal separator
        lastDot != -1 && lastComma != -1 -> {
            val decimalIndex = maxOf(lastDot, lastComma)
            val integerPart = clean.substring(0, decimalIndex).filter { it.isDigit() }
            val decimalPart = clean.substring(decimalIndex + 1).filter { it.isDigit() }
            "$integerPart.$decimalPart".toDoubleOrNull()
        }
        // Only dots exist
        lastDot != -1 -> {
            val parts = clean.split('.')
            // If multiple dots or exactly 3 digits after the dot, likely a thousand separator
            if (parts.size > 2 || (parts.size == 2 && parts[1].length == 3)) {
                clean.filter { it.isDigit() }.toDoubleOrNull()
            } else {
                clean.toDoubleOrNull()
            }
        }
        // Only commas exist
        lastComma != -1 -> {
            val parts = clean.split(',')
            // If multiple commas or exactly 3 digits after the comma, likely a thousand separator
            if (parts.size > 2 || (parts.size == 2 && parts[1].length == 3)) {
                clean.filter { it.isDigit() }.toDoubleOrNull()
            } else {
                // Otherwise treat comma as decimal separator
                clean.replace(',', '.').toDoubleOrNull()
            }
        }
        else -> clean.toDoubleOrNull()
    }
}
