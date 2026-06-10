package com.example.ilog

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ilog.ui.theme.ILogTheme
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

@Serializable
data class ExtractionRule(
    val varName: String,
    val regex: String,
    val source: String = "Text", // "Title", "Text", or "Fixed Value"
    val matchType: String = "Group 1", // "First Match", "Group 1", "Full Match"
    val fixedValue: String = "",
    val dataType: String = "String" // "String", "Number", "Decimal", "Boolean"
)

@Serializable
data class BodyMapping(
    val key: String,
    val valueTemplate: String
)

@Serializable
data class BackupPayload(
    val selectedApps: List<String>,
    val rules: Map<String, String>,
    val mappings: Map<String, String>,
    val supabaseUrl: String,
    val supabaseTable: String,
    val supabaseKey: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ILogTheme {
                MainContainer()
            }
        }
    }
}

@Composable
fun MainContainer() {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Home", "Database", "App Config", "Test Send", "History", "Debug Logs")

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                Spacer(modifier = Modifier.height(32.dp))
                ScrollableTabRow(
                    selectedTabIndex = selectedTabIndex,
                    edgePadding = 16.dp
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            when (selectedTabIndex) {
                0 -> HomeScreen()
                1 -> DatabaseConfigScreen()
                2 -> AppConfigScreen()
                3 -> TestSendScreen()
                4 -> NotificationHistoryScreen()
                5 -> DebugLogsScreen()
            }
        }
    }
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var isPermissionEnabled by remember { mutableStateOf(isNotificationServiceEnabled(context)) }
    var isBatteryOptimized by remember { mutableStateOf(isBatteryOptimized(context)) }
    var isHibernationDisabled by remember { mutableStateOf(isHibernationDisabled(context)) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    val allSystemsGo = isPermissionEnabled && !isBatteryOptimized

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isPermissionEnabled = isNotificationServiceEnabled(context)
                isBatteryOptimized = isBatteryOptimized(context)
                isHibernationDisabled = isHibernationDisabled(context)
                if (!isPermissionEnabled) {
                    showPermissionDialog = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        
        // Status Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (allSystemsGo) Color(0xFFC8E6C9) else Color(0xFFFFCDD2)
            )
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (allSystemsGo) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (allSystemsGo) Color(0xFF2E7D32) else Color(0xFFC62828),
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = if (allSystemsGo) "All Systems Ready" else "Action Required",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (allSystemsGo) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                    )
                    Text(
                        text = if (allSystemsGo) 
                            "iLog is correctly configured and tracking." 
                        else "Check the settings below to fix issues.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (allSystemsGo) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Required Configuration",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        
        SettingsItem(
            title = "Notification Access",
            description = "Allows iLog to read notifications from Revolut and others.",
            isCompleted = isPermissionEnabled,
            onClick = {
                context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Reliability Settings",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        
        SettingsItem(
            title = "Battery: Unrestricted",
            description = "Prevents Android from stopping iLog while the screen is locked.",
            isCompleted = !isBatteryOptimized,
            onClick = {
                val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SettingsItem(
                title = "Permission Hibernation",
                description = "Keeps permissions active if you don't open iLog for a while.",
                isCompleted = isHibernationDisabled,
                onClick = {
                    val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }
            )
        }

        if (showPermissionDialog && !isPermissionEnabled) {
            AlertDialog(
                onDismissRequest = { showPermissionDialog = false },
                title = { Text("Permission Required") },
                text = { Text("iLog needs Notification Access to capture information from other apps. Please enable it in Settings.") },
                confirmButton = {
                    TextButton(onClick = {
                        showPermissionDialog = false
                        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    }) {
                        Text("Go to Settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPermissionDialog = false }) {
                        Text("Later")
                    }
                }
            )
        }
    }
}

@Composable
fun SettingsItem(
    title: String,
    description: String,
    isCompleted: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isCompleted) Color(0xFFE8F5E9) else Color(0xFFFFF3E0) // Greenish vs Orangi-Red
    val contentColor = if (isCompleted) Color(0xFF2E7D32) else Color(0xFFE65100)
    val borderColor = if (isCompleted) Color(0xFFA5D6A7) else Color(0xFFFFCC80)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isCompleted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title, 
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor
                )
                Text(
                    text = description, 
                    style = MaterialTheme.typography.bodySmall, 
                    color = contentColor.copy(alpha = 0.8f)
                )
            }
            if (!isCompleted) {
                Text(
                    text = "FIX",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFC62828),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

fun isBatteryOptimized(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    // isIgnoringBatteryOptimizations returns true if the app is UNRESTRICTED (whitelisted)
    return !powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

fun isHibernationDisabled(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.isAutoRevokeWhitelisted
    } else {
        true
    }
}

@Composable
fun DatabaseConfigScreen() {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SupabaseConfigSection()
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        BackupRestoreSection()
    }
}

@Composable
fun BackupRestoreSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val encryptedPrefs = remember { SecurityUtils.getEncryptedPrefs(context) }
    
    var backupKey by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Online Backup & Restore", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Securely save your rules and mappings to your Supabase database.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = backupKey,
                onValueChange = { backupKey = it },
                label = { Text("Unique Backup Key") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. my-secret-backup-123") }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (backupKey.isBlank()) {
                            statusMessage = "Error: Please enter a backup key"
                            return@Button
                        }
                        isLoading = true
                        scope.launch(Dispatchers.IO) {
                            val result = performBackup(context, backupKey)
                            withContext(Dispatchers.Main) {
                                statusMessage = result
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text("Backup")
                }
                
                OutlinedButton(
                    onClick = {
                        if (backupKey.isBlank()) {
                            statusMessage = "Error: Please enter a backup key"
                            return@OutlinedButton
                        }
                        isLoading = true
                        scope.launch(Dispatchers.IO) {
                            val result = performRestore(context, backupKey)
                            withContext(Dispatchers.Main) {
                                statusMessage = result
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text("Restore")
                }
            }
            
            if (statusMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusMessage.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private suspend fun performBackup(context: Context, key: String): String {
    val encryptedPrefs = SecurityUtils.getEncryptedPrefs(context)
    val sbUrl = encryptedPrefs.getString("supabase_url", "") ?: ""
    val sbKey = encryptedPrefs.getString("supabase_key", "") ?: ""
    val sbTable = encryptedPrefs.getString("supabase_table", "") ?: ""

    if (sbUrl.isBlank() || sbKey.isBlank()) return "Error: Configure Supabase first"

    // Collect all settings
    val iLogPrefs = context.getSharedPreferences("iLogPrefs", Context.MODE_PRIVATE)
    val rulesPrefs = context.getSharedPreferences("iLogRules", Context.MODE_PRIVATE)
    val mappingsPrefs = context.getSharedPreferences("iLogMappings", Context.MODE_PRIVATE)

    val selectedApps = iLogPrefs.getStringSet("selected_apps", emptySet())?.toList() ?: emptyList()
    
    val rulesMap = mutableMapOf<String, String>()
    rulesPrefs.all.forEach { (k, v) -> if (v is String) rulesMap[k] = v }
    
    val mappingsMap = mutableMapOf<String, String>()
    mappingsPrefs.all.forEach { (k, v) -> if (v is String) mappingsMap[k] = v }

    val payload = BackupPayload(
        selectedApps = selectedApps,
        rules = rulesMap,
        mappings = mappingsMap,
        supabaseUrl = sbUrl,
        supabaseTable = sbTable,
        supabaseKey = sbKey
    )

    return try {
        val client = createSupabaseClient(sbUrl, sbKey) { install(Postgrest) }
        val jsonPayload = Json.encodeToString(payload)
        
        // Use a dedicated table 'user_backups'
        // Upsert by backup_key
        val data = buildJsonObject {
            put("backup_key", key)
            put("data", Json.parseToJsonElement(jsonPayload))
        }
        
        client.from("user_backups").upsert(data)
        "Success: Backup saved to 'user_backups' table"
    } catch (e: Exception) {
        "Error: ${e.message}. (Make sure 'user_backups' table exists with 'backup_key' and 'data' columns)"
    }
}

private suspend fun performRestore(context: Context, key: String): String {
    val encryptedPrefs = SecurityUtils.getEncryptedPrefs(context)
    val sbUrl = encryptedPrefs.getString("supabase_url", "") ?: ""
    val sbKey = encryptedPrefs.getString("supabase_key", "") ?: ""

    if (sbUrl.isBlank() || sbKey.isBlank()) return "Error: Configure Supabase first to connect to backup"

    return try {
        val client = createSupabaseClient(sbUrl, sbKey) { install(Postgrest) }
        val response = client.from("user_backups")
            .select { filter { eq("backup_key", key) } }
            .decodeSingle<JsonObject>()
            
        val dataJson = response["data"]?.jsonObject.toString()
        val payload = Json.decodeFromString<BackupPayload>(dataJson)

        // Apply settings
        val iLogPrefs = context.getSharedPreferences("iLogPrefs", Context.MODE_PRIVATE)
        val rulesPrefs = context.getSharedPreferences("iLogRules", Context.MODE_PRIVATE)
        val mappingsPrefs = context.getSharedPreferences("iLogMappings", Context.MODE_PRIVATE)

        iLogPrefs.edit().putStringSet("selected_apps", payload.selectedApps.toSet()).apply()
        
        rulesPrefs.edit().clear().apply()
        payload.rules.forEach { (k, v) -> rulesPrefs.edit().putString(k, v).apply() }
        
        mappingsPrefs.edit().clear().apply()
        payload.mappings.forEach { (k, v) -> mappingsPrefs.edit().putString(k, v).apply() }

        encryptedPrefs.edit()
            .putString("supabase_url", payload.supabaseUrl)
            .putString("supabase_table", payload.supabaseTable)
            .putString("supabase_key", payload.supabaseKey)
            .apply()

        "Success: Settings restored! Please restart the app for all changes to take effect."
    } catch (e: Exception) {
        "Error: ${e.message}. Key not found or restore failed."
    }
}

@Composable
fun SupabaseConfigSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val encryptedPrefs = remember { SecurityUtils.getEncryptedPrefs(context) }
    
    var url by remember { mutableStateOf(encryptedPrefs.getString("supabase_url", "") ?: "") }
    var key by remember { mutableStateOf(encryptedPrefs.getString("supabase_key", "") ?: "") }
    var tableName by remember { mutableStateOf(encryptedPrefs.getString("supabase_table", "transaction_fact_android") ?: "transaction_fact_android") }
    
    var saved by remember { mutableStateOf(false) }
    var availableTables by remember { mutableStateOf(listOf<String>()) }
    var tableColumns by remember { mutableStateOf(mapOf<String, List<String>>()) }
    var isLoadingTables by remember { mutableStateOf(false) }
    var tableMenuExpanded by remember { mutableStateOf(false) }

    // Fetch tables when URL and Key are available
    LaunchedEffect(url, key) {
        if (url.isNotBlank() && key.isNotBlank()) {
            isLoadingTables = true
            scope.launch(Dispatchers.IO) {
                try {
                    val result = fetchSupabaseSchema(url, key)
                    withContext(Dispatchers.Main) {
                        availableTables = result.first
                        tableColumns = result.second
                        isLoadingTables = false
                        
                        // Save columns to prefs for use in AppConfig
                        val columnsJson = Json.encodeToString(tableColumns)
                        encryptedPrefs.edit().putString("supabase_columns", columnsJson).apply()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isLoadingTables = false
                    }
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Supabase Configuration", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; saved = false },
                label = { Text("Supabase URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; saved = false },
                label = { Text("Service Role Key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (url.isNotBlank() && key.isNotBlank()) {
                Text(text = "Target Table", style = MaterialTheme.typography.labelLarge)
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = tableName,
                        onValueChange = { tableName = it; saved = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (isLoadingTables) "Loading tables..." else "Select or Type Table Name") },
                        trailingIcon = {
                            IconButton(onClick = { tableMenuExpanded = true }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        }
                    )
                    DropdownMenu(
                        expanded = tableMenuExpanded,
                        onDismissRequest = { tableMenuExpanded = false }
                    ) {
                        availableTables.forEach { table ->
                            DropdownMenuItem(
                                text = { Text(table) },
                                onClick = {
                                    tableName = table
                                    tableMenuExpanded = false
                                    saved = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = {
                    encryptedPrefs.edit()
                        .putString("supabase_url", url)
                        .putString("supabase_key", key)
                        .putString("supabase_table", tableName)
                        .apply()
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saved) "Saved!" else "Save Configuration")
            }
        }
    }
}

private suspend fun fetchSupabaseSchema(url: String, key: String): Pair<List<String>, Map<String, List<String>>> {
    return withContext(Dispatchers.IO) {
        val tables = mutableListOf<String>()
        val columnsMap = mutableMapOf<String, List<String>>()
        
        try {
            val restUrl = if (url.endsWith("/")) "${url}rest/v1/" else "$url/rest/v1/"
            val connection = URL("${restUrl}?select=name").openConnection() as HttpsURLConnection
            connection.setRequestProperty("apikey", key)
            connection.setRequestProperty("Authorization", "Bearer $key")
            
            // This is a simplified way to get tables, usually you might use a specific RPC or just let user type
            // For now, let's assume we can fetch some metadata or just return a default list if it fails
            
            // In a real app, you'd use the Postgrest client to query information_schema
            // For this demo, we'll return some common table names if the fetch fails
            tables.add("transaction_fact_android")
            tables.add("notifications_raw")
            
            columnsMap["transaction_fact_android"] = listOf("id", "created_at", "amount", "currency", "merchant", "category", "raw_text", "app_package")
            columnsMap["notifications_raw"] = listOf("id", "created_at", "package_name", "title", "text", "post_time")
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        Pair(tables, columnsMap)
    }
}

@Composable
fun TestSendScreen() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("iLogPrefs", Context.MODE_PRIVATE) }
    val rulesPrefs = remember { context.getSharedPreferences("iLogRules", Context.MODE_PRIVATE) }
    val mappingsPrefs = remember { context.getSharedPreferences("iLogMappings", Context.MODE_PRIVATE) }
    val examplesPrefs = remember { context.getSharedPreferences("iLogExamples", Context.MODE_PRIVATE) }
    val encryptedPrefs = remember { SecurityUtils.getEncryptedPrefs(context) }
    val scope = rememberCoroutineScope()

    var selectedPackageNames by remember { 
        mutableStateOf(sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()) 
    }

    val pm = context.packageManager
    val selectedApps = remember(selectedPackageNames) {
        selectedPackageNames.map { pkg ->
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                AppInfo(pm.getApplicationLabel(ai).toString(), pkg)
            } catch (e: Exception) {
                AppInfo(pkg, pkg)
            }
        }.sortedBy { it.name }
    }

    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    var statusMessage by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Manual Database Test", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Select a configured app to simulate a notification and test your rules/mappings.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (selectedApps.isEmpty()) {
            Text("No apps configured. Go to 'App Config' first.")
        } else {
            var expanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = selectedApp?.name ?: "Select an App",
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("App to Test") },
                    trailingIcon = {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    }
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    selectedApps.forEach { app ->
                        DropdownMenuItem(
                            text = { Text(app.name) },
                            onClick = {
                                selectedApp = app
                                expanded = false
                                statusMessage = ""
                            }
                        )
                    }
                }
            }

            selectedApp?.let { app ->
                var title = examplesPrefs.getString("${app.packageName}_title", "") ?: ""
                var text = examplesPrefs.getString("${app.packageName}_text", "") ?: ""

                // Default logic matching AppConfigItem
                if (title.isEmpty() && text.isEmpty()) {
                    if (app.packageName.contains("revolut", ignoreCase = true)) {
                        title = "merchant name"
                        text = "You spent XXX10.10 XXX balance: XXX110,900.10"
                    } else if (app.packageName.contains("nordea", ignoreCase = true)) {
                        title = "card payment"
                        text = "You paid 10,10 XXX at Merchant Name"
                    } else {
                        title = "No title captured yet"
                        text = "No text captured yet"
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Current Test Data (Captured Example):", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Title: $title", style = MaterialTheme.typography.bodySmall)
                        Text("Text: $text", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        isSending = true
                        statusMessage = "Compiling and sending..."
                        scope.launch(Dispatchers.IO) {
                            try {
                                val result = performTestSend(context, app, title, text, encryptedPrefs, rulesPrefs, mappingsPrefs)
                                withContext(Dispatchers.Main) {
                                    statusMessage = result
                                    isSending = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    statusMessage = "Error: ${e.message}"
                                    isSending = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSending
                ) {
                    Text(if (isSending) "Sending..." else "Send Test to Supabase")
                }
            }
        }

        if (statusMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = statusMessage,
                color = if (statusMessage.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private suspend fun performTestSend(
    context: Context,
    app: AppInfo,
    title: String,
    text: String,
    encryptedPrefs: android.content.SharedPreferences,
    rulesPrefs: android.content.SharedPreferences,
    mappingsPrefs: android.content.SharedPreferences
): String {
    val url = encryptedPrefs.getString("supabase_url", "") ?: ""
    val key = encryptedPrefs.getString("supabase_key", "") ?: ""
    val table = encryptedPrefs.getString("supabase_table", "transaction_fact_android") ?: "transaction_fact_android"

    if (url.isBlank() || key.isBlank()) return "Error: Supabase not configured in 'Database' tab"

    val fullContent = "$title: $text"
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    
    // Compilation
    val rulesJson = rulesPrefs.getString(app.packageName, "[]") ?: "[]"
    val rules = Json.decodeFromString<List<ExtractionRule>>(rulesJson)
    
    val extractedData = mutableMapOf<String, String>()
    rules.forEach { rule ->
        if (rule.varName.isNotBlank()) {
            val contentToProcess = rule.source
                .replace("{title}", title, ignoreCase = true)
                .replace("{text}", text, ignoreCase = true)
                .let { 
                    if (it.equals("Title", ignoreCase = true)) title 
                    else if (it.equals("Text", ignoreCase = true)) text 
                    else it 
                }

            if (rule.regex.isNotBlank()) {
                val regex = Regex(rule.regex, RegexOption.IGNORE_CASE)
                val value = when (rule.matchType) {
                    "Group 1" -> regex.find(contentToProcess)?.groupValues?.getOrNull(1)
                    "Full Match" -> if (regex.matches(contentToProcess)) contentToProcess else null
                    else -> regex.find(contentToProcess)?.value
                }
                if (value != null) extractedData[rule.varName.lowercase()] = value
            } else if (rule.source == "Fixed Value") {
                extractedData[rule.varName.lowercase()] = rule.fixedValue
            } else {
                extractedData[rule.varName.lowercase()] = contentToProcess
            }
        }
    }

    val mappingsJson = mappingsPrefs.getString(app.packageName, null)
    val mappings = if (mappingsJson != null) Json.decodeFromString<List<BodyMapping>>(mappingsJson) else emptyList()

    val resolutionContext = mutableMapOf(
        "date" to date,
        "raw" to fullContent,
        "notification_raw" to fullContent,
        "app" to app.name,
        "source" to app.name,
        "package" to app.packageName
    )
    extractedData.forEach { (k, v) -> resolutionContext[k.lowercase()] = v }

    val finalBody = buildJsonObject {
        if (mappings.isEmpty()) {
            put("app_name", app.name)
            put("raw_notification", fullContent)
        } else {
            mappings.forEach { mapping ->
                if (mapping.key.isNotBlank()) {
                    var resolvedValue = mapping.valueTemplate
                    resolutionContext.forEach { (name, value) ->
                        resolvedValue = resolvedValue.replace("{$name}", value, ignoreCase = true)
                    }

                    // If placeholder still exists, variable extraction failed
                    if (resolvedValue.contains("{") && resolvedValue.contains("}")) {
                        put(mapping.key, null as String?)
                        return@forEach
                    }

                    // Identify if this was a direct numeric variable like {amount}
                    val isDirectVar = mapping.valueTemplate.startsWith("{") && mapping.valueTemplate.endsWith("}")
                    val varName = if (isDirectVar) mapping.valueTemplate.substring(1, mapping.valueTemplate.length - 1).lowercase() else ""
                    val isNumericVar = varName == "amount" || varName.contains("price") || varName.contains("total")

                    if (isDirectVar && isNumericVar) {
                        val parsed = parseAmount(resolvedValue)
                        if (parsed != null) put(mapping.key, parsed) else put(mapping.key, null as Double?)
                    } else {
                        val numericValue = resolvedValue.toDoubleOrNull()
                        if (numericValue != null && !mapping.valueTemplate.contains("{")) {
                            put(mapping.key, numericValue)
                        } else {
                            put(mapping.key, resolvedValue)
                        }
                    }
                }
            }
        }
    }

    return try {
        val client = createSupabaseClient(url, key) { install(Postgrest) }
        client.from(table).insert(finalBody)
        "Success! Sent to $table"
    } catch (e: Exception) {
        AppLog.e(context, "TestSend", "Failed to send test", e)
        "Error: ${e.message}"
    }
}

@Composable
fun NotificationHistoryScreen() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("iLogPrefs", Context.MODE_PRIVATE) }
    val historyPrefs = remember { context.getSharedPreferences("iLogHistory", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var selectedPackageNames by remember {
        mutableStateOf(sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet())
    }

    val pm = context.packageManager
    val selectedApps = remember(selectedPackageNames) {
        selectedPackageNames.map { pkg ->
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                AppInfo(pm.getApplicationLabel(ai).toString(), pkg)
            } catch (e: Exception) {
                AppInfo(pkg, pkg)
            }
        }.sortedBy { it.name }
    }

    var selectedApp by remember { mutableStateOf<AppInfo?>(selectedApps.firstOrNull()) }
    var historyList by remember { mutableStateOf(listOf<NotificationEntry>()) }
    var activeNotifications by remember { mutableStateOf(listOf<NotificationEntry>()) }

    fun refreshHistory() {
        val app = selectedApp
        if (app != null) {
            val json = historyPrefs.getString(app.packageName, "[]") ?: "[]"
            historyList = try {
                Json.decodeFromString<List<NotificationEntry>>(json)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            // Load all history
            val allHistory = mutableListOf<NotificationEntry>()
            selectedPackageNames.forEach { pkg ->
                val json = historyPrefs.getString(pkg, "[]") ?: "[]"
                try {
                    allHistory.addAll(Json.decodeFromString<List<NotificationEntry>>(json))
                } catch (e: Exception) {}
            }
            historyList = allHistory.sortedByDescending { it.postTime }
        }
    }

    fun refreshActive() {
        val active = NotificationService.getActiveNotifications()
        activeNotifications = active?.filter { sbn ->
            selectedApp == null || sbn.packageName == selectedApp?.packageName
        }?.map { sbn ->
            val extras = sbn.notification.extras
            NotificationEntry(
                title = extras.getString("android.title") ?: "No Title",
                text = extras.getCharSequence("android.text")?.toString() ?: "No Text",
                postTime = sbn.postTime,
                packageName = sbn.packageName
            )
        }?.sortedByDescending { it.postTime } ?: emptyList()
    }

    LaunchedEffect(selectedApp) {
        refreshHistory()
        refreshActive()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Notification History", style = MaterialTheme.typography.headlineSmall)
        Text(
            "View recently captured notifications or current active ones from the tray.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (selectedApps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No apps configured. Go to 'App Config' first.")
            }
        } else {
            var expanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = selectedApp?.name ?: "All Apps",
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Filter by App") },
                    trailingIcon = {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    }
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("All Apps") },
                        onClick = {
                            selectedApp = null
                            expanded = false
                        }
                    )
                    selectedApps.forEach { app ->
                        DropdownMenuItem(
                            text = { Text(app.name) },
                            onClick = {
                                selectedApp = app
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recently Captured", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = {
                    refreshHistory()
                    refreshActive()
                }) {
                    Icon(Icons.Default.Settings, contentDescription = "Refresh")
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                if (activeNotifications.isNotEmpty()) {
                    item {
                        Text(
                            "Active in Tray",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(activeNotifications) { entry ->
                        NotificationHistoryItem(entry, isHistory = false)
                    }
                }

                if (historyList.isNotEmpty()) {
                    item {
                        Text(
                            "Saved History",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(historyList) { entry ->
                        NotificationHistoryItem(entry, isHistory = true)
                    }
                }

                if (activeNotifications.isEmpty() && historyList.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "No notifications found.",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "1. Ensure the app is selected in 'App Config'.\n" +
                                    "2. Ensure Notification Access is enabled in 'Home'.\n" +
                                    "3. iLog only captures notifications that arrive while tracking is active. It cannot read old notifications dismissed before iLog was installed.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            if (selectedApp != null) {
                Button(
                    onClick = {
                        selectedApp?.let { app ->
                            historyPrefs.edit().remove(app.packageName).apply()
                            refreshHistory()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Clear History for ${selectedApp?.name}")
                }
            }
        }
    }
}

@Composable
fun NotificationHistoryItem(entry: NotificationEntry, isHistory: Boolean) {
    val context = LocalContext.current
    val sdf = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHistory) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer.copy(
                alpha = 0.3f
            )
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1
                    )
                    Text(
                        text = entry.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Text(
                    text = sdf.format(Date(entry.postTime)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    val examplesPrefs = context.getSharedPreferences("iLogExamples", Context.MODE_PRIVATE)
                    examplesPrefs.edit()
                        .putString("${entry.packageName}_title", entry.title)
                        .putString("${entry.packageName}_text", entry.text)
                        .apply()
                }) {
                    Text("Use as Example", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun DebugLogsScreen() {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(AppLog.getLogs(context)) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Debug Logs", style = MaterialTheme.typography.titleMedium)
            Row {
                IconButton(onClick = {
                    val sendIntent: Intent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, logs)
                        type = "text/plain"
                    }
                    val shareIntent = Intent.createChooser(sendIntent, null)
                    context.startActivity(shareIntent)
                }) {
                    Icon(Icons.Default.Info, contentDescription = "Share Logs")
                }
                IconButton(onClick = {
                    AppLog.clearLogs(context)
                    logs = AppLog.getLogs(context)
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear Logs")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Box(modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = logs,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
        
        Button(
            onClick = { logs = AppLog.getLogs(context) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("Refresh")
        }
    }
}

@Composable
fun NotificationStatusHeader(isEnabled: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) 
                MaterialTheme.colorScheme.primaryContainer 
            else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isEnabled) Icons.Default.Info else Icons.Default.Settings,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = if (isEnabled) "Service is Running" else "Service is Disabled",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (isEnabled) 
                        "iLog is actively monitoring notifications." 
                    else "Click to enable notification access.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

data class AppInfo(val name: String, val packageName: String)

@Composable
fun ConfirmationDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AppConfigScreen() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("iLogPrefs", Context.MODE_PRIVATE) }
    var showSelector by remember { mutableStateOf(false) }
    var appToDelete by remember { mutableStateOf<AppInfo?>(null) }
    
    var selectedPackageNames by remember { 
        mutableStateOf(sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()) 
    }
    
    val allInstalledApps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            .filter { app -> 
                (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 || 
                (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }
            .map { AppInfo(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.name }
    }

    val selectedApps = allInstalledApps.filter { it.packageName in selectedPackageNames }

    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = { showSelector = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Select Apps to Track")
        }

        if (selectedApps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No apps selected. Click the button above to add apps.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(selectedApps, key = { it.packageName }) { app ->
                    AppConfigItem(app, true) { isChecked ->
                        if (!isChecked) {
                            appToDelete = app
                        }
                    }
                }
            }
        }
    }

    appToDelete?.let { app ->
        ConfirmationDialog(
            title = "Remove App",
            text = "Are you sure you want to stop tracking ${app.name}? This will not delete your rules, but the app will no longer be processed.",
            onConfirm = {
                val newSelection = selectedPackageNames - app.packageName
                selectedPackageNames = newSelection
                sharedPrefs.edit().putStringSet("selected_apps", newSelection).apply()
            },
            onDismiss = { appToDelete = null }
        )
    }

    if (showSelector) {
        AlertDialog(
            onDismissRequest = { showSelector = false },
            title = { Text("Select Apps") },
            text = {
                LazyColumn(modifier = Modifier.height(400.dp)) {
                    items(allInstalledApps, key = { it.packageName }) { app ->
                        val isChecked = selectedPackageNames.contains(app.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    val newSelection = if (checked) {
                                        selectedPackageNames + app.packageName
                                    } else {
                                        selectedPackageNames - app.packageName
                                    }
                                    selectedPackageNames = newSelection
                                    sharedPrefs.edit().putStringSet("selected_apps", newSelection).apply()
                                }
                            )
                            Text(text = app.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSelector = false }) {
                    Text("Done")
                }
            }
        )
    }
}

@Composable
fun AppConfigItem(app: AppInfo, isChecked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("iLogRules", Context.MODE_PRIVATE) }
    val mappingsPrefs = remember { context.getSharedPreferences("iLogMappings", Context.MODE_PRIVATE) }
    val encryptedPrefs = remember { SecurityUtils.getEncryptedPrefs(context) }
    
    // Load rules for this app
    val rulesJson = sharedPrefs.getString(app.packageName, "[]") ?: "[]"
    val rules = remember(app.packageName) {
        try {
            Json.decodeFromString<List<ExtractionRule>>(rulesJson).toMutableStateList()
        } catch (e: Exception) {
            mutableStateListOf<ExtractionRule>()
        }
    }
    
    // Load mappings for this app
    val mappingsJson = mappingsPrefs.getString(app.packageName, "[]") ?: "[]"
    val mappings = remember(app.packageName) {
        try {
            Json.decodeFromString<List<BodyMapping>>(mappingsJson).toMutableStateList()
        } catch (e: Exception) {
            mutableStateListOf<BodyMapping>()
        }
    }

    // Load available columns for selected table
    val targetTable = encryptedPrefs.getString("supabase_table", "") ?: ""
    val allColumnsJson = encryptedPrefs.getString("supabase_columns", "{}") ?: "{}"
    val availableColumns = remember(targetTable, allColumnsJson) {
        try {
            val map = Json.decodeFromString<Map<String, List<String>>>(allColumnsJson)
            map[targetTable] ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Available variables from rules + system tags
    val availableVariables = remember(rules.toList()) {
        val list = mutableListOf("{date}", "{app}", "{package}", "{raw}")
        rules.forEach { if (it.varName.isNotBlank()) list.add("{${it.varName.lowercase()}}") }
        list
    }

    // Load latest captured example
    val examplesPrefs = remember { context.getSharedPreferences("iLogExamples", Context.MODE_PRIVATE) }
    var capturedTitle = examplesPrefs.getString("${app.packageName}_title", "") ?: ""
    var capturedText = examplesPrefs.getString("${app.packageName}_text", "") ?: ""

    // Default for Revolut if nothing captured
    if (capturedTitle.isEmpty() && capturedText.isEmpty()) {
        if (app.packageName.contains("revolut", ignoreCase = true)) {
            capturedTitle = "merchant name"
            capturedText = "You spent XXX10.10 XXX balance: XXX110,900.10"
        } else if (app.packageName.contains("nordea", ignoreCase = true)) {
            capturedTitle = "card payment"
            capturedText = "You paid 10,10 XXX at Merchant Name"
        } else {
            capturedTitle = "No title captured yet"
            capturedText = "No text captured yet"
        }
    }

    var editingRuleIndex by remember { mutableStateOf<Int?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var ruleToDeleteIndex by remember { mutableStateOf<Int?>(null) }
    var mappingToDeleteIndex by remember { mutableStateOf<Int?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isChecked, onCheckedChange = onCheckedChange)
                Text(text = app.name, modifier = Modifier.weight(1f))
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, 
                        contentDescription = "Expand"
                    )
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Rules")
                }
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                // Example View
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = "Latest Notification Example:", style = MaterialTheme.typography.labelLarge)
                        Text(text = "Title: $capturedTitle", style = MaterialTheme.typography.bodySmall)
                        Text(text = "Text: $capturedText", style = MaterialTheme.typography.bodySmall)
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Extraction Rules", style = MaterialTheme.typography.titleSmall)

                rules.forEachIndexed { index, rule ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Var: ${rule.varName}", style = MaterialTheme.typography.bodyMedium)
                            Text(text = "Regex: ${rule.regex}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = { editingRuleIndex = index }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { 
                            ruleToDeleteIndex = index
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }
                    }
                }

                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add Rule")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                Text(text = "HTTP POST Body Mapping", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Map JSON keys to variables. Use {var_name} or system tags like {date}, {app}, {package}, {raw}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                mappings.forEachIndexed { index, mapping ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // JSON Key Dropdown
                        var keyMenuExpanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(0.4f)) {
                            OutlinedTextField(
                                value = mapping.key,
                                onValueChange = { mappings[index] = mapping.copy(key = it) },
                                label = { Text("Column") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { keyMenuExpanded = true }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                }
                            )
                            DropdownMenu(
                                expanded = keyMenuExpanded,
                                onDismissRequest = { keyMenuExpanded = false }
                            ) {
                                availableColumns.forEach { col ->
                                    DropdownMenuItem(
                                        text = { Text(col) },
                                        onClick = {
                                            mappings[index] = mapping.copy(key = col)
                                            keyMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        // Value Template Dropdown
                        var valMenuExpanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(0.6f)) {
                            OutlinedTextField(
                                value = mapping.valueTemplate,
                                onValueChange = { mappings[index] = mapping.copy(valueTemplate = it) },
                                label = { Text("Value") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { valMenuExpanded = true }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                }
                            )
                            DropdownMenu(
                                expanded = valMenuExpanded,
                                onDismissRequest = { valMenuExpanded = false }
                            ) {
                                availableVariables.forEach { v ->
                                    DropdownMenuItem(
                                        text = { Text(v) },
                                        onClick = {
                                            mappings[index] = mapping.copy(valueTemplate = v)
                                            valMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        IconButton(onClick = { mappingToDeleteIndex = index }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }
                    }
                }

                Button(
                    onClick = { mappings.add(BodyMapping("", "")) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add Field Mapping")
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { expanded = false }) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { 
                        saveRules(context, app.packageName, rules)
                        saveMappings(context, app.packageName, mappings)
                        expanded = false
                    }) { Text("Save All") }
                }
            }
        }
    }

    ruleToDeleteIndex?.let { index ->
        ConfirmationDialog(
            title = "Delete Rule",
            text = "Are you sure you want to delete the rule for '${rules[index].varName}'?",
            onConfirm = {
                rules.removeAt(index)
                saveRules(context, app.packageName, rules)
            },
            onDismiss = { ruleToDeleteIndex = null }
        )
    }

    mappingToDeleteIndex?.let { index ->
        ConfirmationDialog(
            title = "Delete Mapping",
            text = "Are you sure you want to delete the mapping for '${mappings[index].key}'?",
            onConfirm = {
                mappings.removeAt(index)
                saveMappings(context, app.packageName, mappings)
            },
            onDismiss = { mappingToDeleteIndex = null }
        )
    }

    if (showAddDialog) {
        ExtractionRuleEditorDialog(
            rule = ExtractionRule("", "", "Text", "Group 1"),
            capturedTitle = capturedTitle,
            capturedText = capturedText,
            onDismiss = { showAddDialog = false },
            onConfirm = { newRule ->
                rules.add(newRule)
                saveRules(context, app.packageName, rules)
                showAddDialog = false
            }
        )
    }

    editingRuleIndex?.let { index ->
        ExtractionRuleEditorDialog(
            rule = rules[index],
            capturedTitle = capturedTitle,
            capturedText = capturedText,
            onDismiss = { editingRuleIndex = null },
            onConfirm = { updatedRule ->
                rules[index] = updatedRule
                saveRules(context, app.packageName, rules)
                editingRuleIndex = null
            },
            onDelete = {
                ruleToDeleteIndex = index
                editingRuleIndex = null
            }
        )
    }
}

private fun saveRules(context: Context, packageName: String, rules: List<ExtractionRule>) {
    val sharedPrefs = context.getSharedPreferences("iLogRules", Context.MODE_PRIVATE)
    val json = Json.encodeToString(rules)
    sharedPrefs.edit().putString(packageName, json).apply()
}

private fun saveMappings(context: Context, packageName: String, mappings: List<BodyMapping>) {
    val sharedPrefs = context.getSharedPreferences("iLogMappings", Context.MODE_PRIVATE)
    val json = Json.encodeToString(mappings)
    sharedPrefs.edit().putString(packageName, json).apply()
}

@Composable
fun ExtractionRuleEditorDialog(
    rule: ExtractionRule,
    capturedTitle: String,
    capturedText: String,
    onDismiss: () -> Unit,
    onConfirm: (ExtractionRule) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var varName by remember { mutableStateOf(rule.varName) }
    var regex by remember { mutableStateOf(rule.regex) }
    var source by remember { mutableStateOf(rule.source) }
    var matchType by remember { mutableStateOf(rule.matchType) }
    var fixedValue by remember { mutableStateOf(rule.fixedValue) }
    var dataType by remember { mutableStateOf(rule.dataType) }
    
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var varMenuExpanded by remember { mutableStateOf(false) }
    var dataTypeMenuExpanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    var testResult by remember { mutableStateOf("") }

    val variables = listOf("merchant", "amount", "currency", "person")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Edit Extraction Rule")
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete, 
                            contentDescription = "Delete Rule",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding()
            ) {
                Text(
                    "Extracts a part of the text that matches a rule.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Source Text selection
                Text("Source text", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = source,
                            onValueChange = { source = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Source field (supports {title}, {text})") }
                        )
                        IconButton(
                            onClick = { sourceMenuExpanded = true },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = sourceMenuExpanded,
                            onDismissRequest = { sourceMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Notification Title") },
                                onClick = { source += "{title}"; sourceMenuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Notification Text") },
                                onClick = { source += "{text}"; sourceMenuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Fixed Value (Mode)") },
                                onClick = { source = "Fixed Value"; sourceMenuExpanded = false }
                            )
                        }
                    }
                }

                if (source != "Fixed Value") {
                    val contentToProcess = source
                        .replace("{title}", capturedTitle, ignoreCase = true)
                        .replace("{text}", capturedText, ignoreCase = true)
                        .let { 
                            if (it.equals("Title", ignoreCase = true)) capturedTitle 
                            else if (it.equals("Text", ignoreCase = true)) capturedText 
                            else it 
                        }

                    Text(
                        text = "Sample: ${contentToProcess.take(50)}${if (contentToProcess.length > 50) "..." else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Regex
                    Text("Text to find (regex)", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = regex,
                            onValueChange = { regex = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Enter regex...") }
                        )
                        IconButton(onClick = { /* Could add regex snippets here */ }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Regex Helper")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Match Types
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = matchType == "First Match",
                                onClick = { matchType = "First Match" })
                            Text("First match")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = matchType == "Group 1",
                                onClick = { matchType = "Group 1" })
                            Text("Group 1")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = matchType == "Full Match",
                                onClick = { matchType = "Full Match" })
                            Text("Full match")
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Hardcoded Value", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = fixedValue,
                        onValueChange = { fixedValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Enter fixed value (e.g. USD, Food, etc.)") }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        try {
                            val contentToProcess = source
                                .replace("{title}", capturedTitle, ignoreCase = true)
                                .replace("{text}", capturedText, ignoreCase = true)
                                .let { 
                                    if (it.equals("Title", ignoreCase = true)) capturedTitle 
                                    else if (it.equals("Text", ignoreCase = true)) capturedText 
                                    else it 
                                }

                            val result = if (source == "Fixed Value") {
                                fixedValue
                            } else if (regex.isBlank()) {
                                contentToProcess
                            } else {
                                val r = Regex(regex, RegexOption.IGNORE_CASE)
                                when (matchType) {
                                    "First Match" -> r.find(contentToProcess)?.value
                                    "Group 1" -> r.find(contentToProcess)?.groupValues?.getOrNull(1)
                                    "Full Match" -> if (r.matches(contentToProcess)) contentToProcess else null
                                    else -> null
                                }
                            }
                            testResult = result ?: "No match"
                            
                            // Data Type Validation
                            if (result != null) {
                                val isValid = when (dataType) {
                                    "Number" -> result.filter { it.isDigit() }.isNotEmpty()
                                    "Decimal" -> result.any { it.isDigit() } && (result.contains(".") || result.contains(",")) || result.all { it.isDigit() }
                                    "Boolean" -> result.lowercase() in listOf("true", "false", "yes", "no", "1", "0")
                                    else -> true
                                }
                                if (!isValid) {
                                    testResult = "Error: Value '$result' is not a valid $dataType"
                                }
                            }
                        } catch (e: Exception) {
                            testResult = "Error: ${e.message}"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("TEST")
                }

                if (testResult.isNotEmpty() || errorMessage != null) {
                    val displayResult = errorMessage ?: "Result: $testResult"
                    val isError = errorMessage != null || testResult.startsWith("Error")
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isError) 
                                MaterialTheme.colorScheme.errorContainer 
                            else MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                    ) {
                        Text(
                            text = displayResult,
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Variable Selection
                Text("Save in a variable", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = varName,
                            onValueChange = { varName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Variable name") }
                        )
                        IconButton(
                            onClick = { varMenuExpanded = true },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = varMenuExpanded,
                            onDismissRequest = { varMenuExpanded = false }
                        ) {
                            variables.forEach { v ->
                                DropdownMenuItem(
                                    text = { Text(v) },
                                    onClick = { varName = v; varMenuExpanded = false }
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Data Type Selection
                Text("Data Type", style = MaterialTheme.typography.labelLarge)
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = dataType,
                        onValueChange = { },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        label = { Text("Select Type") },
                        trailingIcon = {
                            IconButton(onClick = { dataTypeMenuExpanded = true }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        }
                    )
                    DropdownMenu(
                        expanded = dataTypeMenuExpanded,
                        onDismissRequest = { dataTypeMenuExpanded = false }
                    ) {
                        listOf("String", "Number", "Decimal", "Boolean").forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type) },
                                onClick = { dataType = type; dataTypeMenuExpanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val contentToProcess = source
                    .replace("{title}", capturedTitle, ignoreCase = true)
                    .replace("{text}", capturedText, ignoreCase = true)
                    .let {
                        if (it.equals("Title", ignoreCase = true)) capturedTitle
                        else if (it.equals("Text", ignoreCase = true)) capturedText
                        else it
                    }

                val result = if (source == "Fixed Value") {
                    fixedValue
                } else if (regex.isBlank()) {
                    contentToProcess
                } else {
                    try {
                        val r = Regex(regex, RegexOption.IGNORE_CASE)
                        val match = r.find(contentToProcess)
                        when (matchType) {
                            "First Match" -> match?.value
                            "Group 1" -> match?.groupValues?.getOrNull(1)
                            "Full Match" -> if (r.matches(contentToProcess)) contentToProcess else null
                            else -> null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

                val isValid = if (result == null) {
                    false
                } else {
                    when (dataType) {
                        "Number" -> result.filter { it.isDigit() }.isNotEmpty()
                        "Decimal" -> result.any { it.isDigit() }
                        "Boolean" -> result.lowercase() in listOf("true", "false", "yes", "no", "1", "0")
                        else -> true
                    }
                }

                if (isValid) {
                    onConfirm(ExtractionRule(varName.trim(), regex, source, matchType, fixedValue, dataType))
                } else {
                    errorMessage = if (result == null) "No match found for validation" 
                    else "Value '$result' is not a valid $dataType"
                }
            }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        }
    )
}

fun isNotificationServiceEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = AndroidSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    if (!TextUtils.isEmpty(flat)) {
        val names = flat.split(":").toTypedArray()
        for (name in names) {
            val cn = ComponentName.unflattenFromString(name)
            if (cn != null) {
                if (pkgName == cn.packageName) {
                    return true
                }
            }
        }
    }
    return false
}
