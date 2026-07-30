package com.example.ilog

import android.app.Notification
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.edit
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
data class NotificationEntry(
    val title: String,
    val text: String,
    val postTime: Long,
    val packageName: String = "",
    val userIdentifier: Int = 0,
    val isPrivateSpace: Boolean = false,
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
        AppLog.d(this, tag, "Notification Listener Connected")
        
        // Backfill history with currently active notifications for selected apps
        try {
            val sharedPrefs = getSharedPreferences("iLogPrefs", MODE_PRIVATE)
            val selectedApps = sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
            val userManager = getSystemService(USER_SERVICE) as UserManager
            
            activeNotifications?.forEach { sbn ->
                val userHash = userManager.getSerialNumberForUser(sbn.user).toInt()
                val appKey = "${sbn.packageName}_$userHash"
                
                if (appKey in selectedApps) {
                    val extras = sbn.notification.extras
                    val title = (extras.getString("android.title") ?: "").replace("\n", " ")
                    val text = (extras.getCharSequence("android.text")?.toString() ?: "").replace("\n", " ")
                    saveToHistory(sbn.packageName, title, text, sbn.postTime)
                }
            }
        } catch (e: Exception) {
            AppLog.e(this, tag, "Failed to backfill history", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationHelper.createNotificationChannel(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val tag = "NotificationService"

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
            AppLog.e(this, tag, "Supabase URL or Key not configured")
            return false
        }

        if ((supabase != null) && (url == supabaseUrl) && (key == supabaseKey)) {
            return true
        }

        return try {
            supabaseUrl = url
            supabaseKey = key
            supabase = createSupabaseClient(url, key) {
                install(Postgrest)
            }
            AppLog.d(this, tag, "Supabase client initialized: $url")
            true
        } catch (e: Exception) {
            AppLog.e(this, tag, "Supabase initialization failed", e)
            false
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val userManager = getSystemService(USER_SERVICE) as UserManager
        val userHash = userManager.getSerialNumberForUser(sbn.user).toInt()
        val appKey = "${packageName}_$userHash"
        
        val sharedPrefs = getSharedPreferences("iLogPrefs", MODE_PRIVATE)
        val selectedApps = sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
        
        if (appKey !in selectedApps) return

        AppLog.d(this, tag, "Notification received from: $appKey")

        if (!initSupabase()) return

        val extras = sbn.notification.extras
        val title = (extras.getString("android.title") ?: "").replace("\n", " ")
        var text = (extras.getCharSequence("android.text")?.toString() ?: "").replace("\n", " ")
        
        // Fallback to bigText if text is empty
        if (text.isBlank()) {
            val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
            if (bigText.isNotBlank()) {
                text = bigText.replace("\n", " ")
            }
        }

        // Ignore empty notifications
        if (title.isBlank() && text.isBlank()) {
            AppLog.d(this, tag, "Skipping notification with empty title and text")
            return
        }

        // Ignore group summaries and ongoing notifications that usually don't contain transaction data
        val isGroupSummary = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) {
            AppLog.d(this, tag, "Skipping group summary notification")
            return
        }

        val fullContent = "$title: $text"
        
        getSharedPreferences("iLogExamples", MODE_PRIVATE).edit {
            putString("${packageName}_title", title)
            putString("${packageName}_text", text)
        }

        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(sbn.postTime))
        
        val appName = try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
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
            AppLog.e(this, tag, "Failed to parse rules for $packageName", e)
            emptyList()
        }

        scope.launch {
            // Capture location
            var locationString: String? = null
            val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(this@NotificationService, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(this@NotificationService, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (hasFine || hasCoarse) {
                try {
                    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this@NotificationService)
                    var location = Tasks.await(fusedLocationClient.lastLocation, 1, TimeUnit.SECONDS)
                    
                    if (location == null) {
                        AppLog.d(this@NotificationService, tag, "Last location null, requesting fresh location...")
                        location = Tasks.await(
                            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null),
                            3, TimeUnit.SECONDS
                        )
                    }

                    if (location != null) {
                        // PostGIS geography(POINT, 4326) format: SRID=4326;POINT(${location.longitude} ${location.latitude})
                        locationString = "SRID=4326;POINT(${location.longitude} ${location.latitude})"
                        AppLog.d(this@NotificationService, tag, "Captured location: $locationString")
                    } else {
                        AppLog.d(this@NotificationService, tag, "Location is still null after fresh request")
                    }
                } catch (e: Exception) {
                    AppLog.e(this@NotificationService, tag, "Location capture failed: ${e.message}")
                }
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
                        AppLog.e(this@NotificationService, tag, "Extraction error for $varName", e)
                    }
                }
            }

            val mappingsPrefs = getSharedPreferences("iLogMappings", MODE_PRIVATE)
            val mappingsJson = mappingsPrefs.getString(packageName, null)
            val mappings = try {
                if (mappingsJson != null) Json.decodeFromString<List<BodyMapping>>(mappingsJson)
                else emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            val resolutionContext = mutableMapOf(
                "date" to date,
                "raw" to fullContent,
                "notification_raw" to fullContent,
                "app" to appName,
                "source" to appName,
                "package" to packageName,
                "location" to (locationString ?: ""),
            )
            extractedData.forEach { (k, v) -> resolutionContext[k.lowercase()] = v }

            val finalBody = buildJsonObject {
                if (mappings.isEmpty()) {
                    put("app_name", appName)
                    put("raw_notification", fullContent)
                    if (locationString != null) put("location", locationString)
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
                            put(key, parseAmount(resolvedValue))
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

            AppLog.d(this@NotificationService, tag, "Attempting POST to $supabaseTable with body: $finalBody")

            // Validation: Check if the body contains useful information
            val hasContent = if (mappings.isNotEmpty()) {
                finalBody.values.any { it !is kotlinx.serialization.json.JsonNull }
            } else {
                true // Default app_name/raw_notification always present
            }

            if (!hasContent) {
                AppLog.e(this@NotificationService, tag, "Extraction failed: All mapped fields are null")
                NotificationHelper.showErrorNotification(
                    this@NotificationService, title, text, 
                    "Extraction failed: Could not resolve any variables for the configured mappings."
                )
                return@launch
            }

            supabase?.let { client ->
                try {
                    client.from(supabaseTable).insert(finalBody)
                    AppLog.d(this@NotificationService, tag, "Successfully sent to Supabase")
                    NotificationHelper.showSuccessNotification(this@NotificationService, title, text)
                } catch (e: Exception) {
                    val errorMsg = e.message ?: "Unknown error"
                    AppLog.e(this@NotificationService, tag, "Primary request failed: $errorMsg", e)
                    
                    // Fallback: Try sending only notification info
                    try {
                        val fallbackBody = buildJsonObject {
                            put("app_name", appName)
                            put("raw_notification", fullContent)
                            if (locationString != null) put("location", locationString)
                        }
                        AppLog.d(this@NotificationService, tag, "Attempting clean fallback request")
                        client.from(supabaseTable).insert(fallbackBody)
                        AppLog.d(this@NotificationService, tag, "Fallback request successful")
                        NotificationHelper.showSuccessNotification(this@NotificationService, title, text)
                    } catch (e2: Exception) {
                        AppLog.e(this@NotificationService, tag, "Fallback request also failed: ${e2.message}", e2)
                        NotificationHelper.showErrorNotification(this@NotificationService, title, text, e2.message ?: "Unknown error")
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
            historyPrefs.edit { putString(packageName, Json.encodeToString(limitedHistory)) }
        } catch (e: Exception) {
            AppLog.e(this, tag, "Failed to save history for $packageName", e)
            // If it failed to parse, start fresh
            val newHistory = listOf(NotificationEntry(title, text, postTime, packageName))
            historyPrefs.edit { putString(packageName, Json.encodeToString(newHistory)) }
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
