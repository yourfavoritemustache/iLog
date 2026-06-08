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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class NotificationService : NotificationListenerService() {

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
            if (rule.varName.isNotBlank()) {
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
                        extractedData[rule.varName.lowercase()] = value
                    }
                } catch (e: Exception) {
                    AppLog.e(this, TAG, "Extraction error for ${rule.varName}", e)
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
                    if (mapping.key.isNotBlank()) {
                        var resolvedValue = mapping.valueTemplate
                        resolutionContext.forEach { (name, value) ->
                            resolvedValue = resolvedValue.replace("{$name}", value, ignoreCase = true)
                        }
                        
                        val numericValue = resolvedValue.toDoubleOrNull()
                        if (numericValue != null && !mapping.valueTemplate.contains("{")) {
                            put(mapping.key, numericValue)
                        } else {
                            put(mapping.key, resolvedValue)
                        }
                        
                        if (mapping.valueTemplate.startsWith("{") && mapping.valueTemplate.endsWith("}")) {
                            val varName = mapping.valueTemplate.substring(1, mapping.valueTemplate.length - 1).lowercase()
                            if (varName == "amount" || varName.contains("price") || varName.contains("total")) {
                                val parsed = parseAmount(resolutionContext[varName])
                                if (parsed != null) put(mapping.key, parsed)
                            }
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
                    
                    // Fallback: Try sending only error info to a basic field
                    try {
                        val fallbackBody = buildJsonObject {
                            put("app_name", appName)
                            put("raw_notification", "ERROR_POST: $errorMsg | DATA: $finalBody")
                        }
                        AppLog.d(this@NotificationService, TAG, "Attempting fallback request")
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

    private fun parseAmount(input: String?): Double? {
        if (input == null) return null
        val clean = input.filter { it.isDigit() || it == '.' || it == ',' }
        if (clean.isEmpty()) return null

        val lastDot = clean.lastIndexOf('.')
        val lastComma = clean.lastIndexOf(',')
        val lastSeparatorIndex = maxOf(lastDot, lastComma)

        return if (lastSeparatorIndex == -1) {
            clean.toDoubleOrNull()
        } else {
            val integerPart = clean.substring(0, lastSeparatorIndex).filter { it.isDigit() }
            val decimalPart = clean.substring(lastSeparatorIndex + 1).filter { it.isDigit() }
            "$integerPart.$decimalPart".toDoubleOrNull()
        }
    }
}
