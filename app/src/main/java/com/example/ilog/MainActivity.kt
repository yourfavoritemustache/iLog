package com.example.ilog

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings as AndroidSettings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ilog.ui.theme.ILogTheme
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
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
    val dataType: String = "String", // "String", "Number", "Decimal", "Boolean"
)

@Serializable
data class BodyMapping(
    val key: String,
    val valueTemplate: String,
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
        NotificationHelper.createNotificationChannel(this)
        setContent {
            ILogTheme {
                MainContainer()
            }
        }
    }
}

@Composable
fun MainContainer() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("iLogPrefs", Context.MODE_PRIVATE) }
    
    // Migrate old selected_apps format to new pkg_userHash format
    LaunchedEffect(Unit) {
        val current = sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
                    val migrated = current.map { key ->
                        if (!key.contains("_")) "${key}_0" else key
                    }.toSet()
        if (migrated != current) {
            sharedPrefs.edit { putStringSet("selected_apps", migrated) }
        }
    }

    val tabs = listOf("Home", "Database", "App Config", "Test Send", "History", "Debug Logs")
    val pagerState = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                Spacer(modifier = Modifier.height(32.dp))
                PrimaryScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    edgePadding = 16.dp
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            verticalAlignment = Alignment.Top,
            beyondViewportPageCount = 1
        ) { page ->
            when (page) {
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
    var isPostNotificationGranted by remember { 
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var isBatteryOptimized by remember { mutableStateOf(isBatteryOptimized(context)) }
    var isHibernationDisabled by remember { mutableStateOf(isHibernationDisabled(context)) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    val allSystemsGo = isPermissionEnabled && isPostNotificationGranted && !isBatteryOptimized && isHibernationDisabled

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isPostNotificationGranted = isGranted
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isPermissionEnabled = isNotificationServiceEnabled(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    isPostNotificationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(modifier = Modifier.height(16.dp))
            SettingsItem(
                title = "Success Notifications",
                description = "Shows a status alert when data is successfully saved to Supabase.",
                isCompleted = isPostNotificationGranted,
                onClick = {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            )
        }
        
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // 1. Whitelisted by user
        if (context.packageManager.isAutoRevokeWhitelisted) return true

        // 2. Improved Detection: If we have no dangerous permissions,
        // Android disables the hibernation toggle anyway (nothing to revoke).
        if (!hasDangerousPermissions(context)) return true

        // 3. Simplified UI: If battery is unrestricted, we treat the reliability
        // section as successful as it's the most critical factor.
        if (!isBatteryOptimized(context)) return true

        return false
    }
    return true
}

fun hasDangerousPermissions(context: Context): Boolean {
    return try {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requestedPermissions = info.requestedPermissions ?: return false

        requestedPermissions.any { permission ->
            try {
                val pInfo = pm.getPermissionInfo(permission, 0)
                val protection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.protection
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
                }
                protection == PermissionInfo.PROTECTION_DANGEROUS
            } catch (_: Exception) {
                false
            }
        }
    } catch (_: Exception) {
        false
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

        iLogPrefs.edit { putStringSet("selected_apps", payload.selectedApps.toSet()) }
        
        rulesPrefs.edit {
            clear()
            payload.rules.forEach { (k, v) -> putString(k, v) }
        }
        
        mappingsPrefs.edit {
            clear()
            payload.mappings.forEach { (k, v) -> putString(k, v) }
        }

        encryptedPrefs.edit {
            putString("supabase_url", payload.supabaseUrl)
            putString("supabase_table", payload.supabaseTable)
            putString("supabase_key", payload.supabaseKey)
        }

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

    fun refreshTables() {
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
                        encryptedPrefs.edit { putString("supabase_columns", columnsJson) }
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        isLoadingTables = false
                    }
                }
            }
        }
    }

    // Fetch tables when URL and Key are available
    LaunchedEffect(url, key) {
        refreshTables()
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
                            Row {
                                IconButton(onClick = { refreshTables() }, enabled = !isLoadingTables) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Tables")
                                }
                                IconButton(onClick = { tableMenuExpanded = true }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
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
                    encryptedPrefs.edit {
                        putString("supabase_url", url)
                        putString("supabase_key", key)
                        putString("supabase_table", tableName)
                    }
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
            val connection = URL(restUrl).openConnection() as HttpsURLConnection
            connection.setRequestProperty("apikey", key)
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = Json.decodeFromString<JsonObject>(response)
                val definitions = json["definitions"]?.jsonObject
                
                definitions?.forEach { (tableName, definition) ->
                    tables.add(tableName)
                    val properties = definition.jsonObject["properties"]?.jsonObject
                    val columns = properties?.keys?.toList() ?: emptyList()
                    columnsMap[tableName] = columns
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // If we failed to get anything from the API, we keep the default expected tables
        // so the user has something to work with if their API is restricted
        if (tables.isEmpty()) {
            tables.add("transaction_fact_android")
            tables.add("notifications_raw")
            tables.add("user_backups")
            
            columnsMap["transaction_fact_android"] = listOf("id", "created_at", "amount", "currency", "merchant", "category", "raw_text", "app_package")
            columnsMap["notifications_raw"] = listOf("id", "created_at", "package_name", "title", "text", "post_time")
            columnsMap["user_backups"] = listOf("backup_key", "data", "created_at")
        }
        
        Pair(tables.sorted(), columnsMap)
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

    val lifecycleOwner = LocalLifecycleOwner.current
    var selectedPackageNames by remember { 
        mutableStateOf(sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()) 
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                selectedPackageNames = sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var selectedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isResolvingApps by remember { mutableStateOf(true) }

    LaunchedEffect(selectedPackageNames) {
        val apps = withContext(Dispatchers.IO) {
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val pm = context.packageManager

            selectedPackageNames.map { key ->
                val parts = key.split("_")
                val pkg = parts[0]
                val userHash = parts.getOrNull(1)?.toIntOrNull() ?: 0
                
                val user = userManager.userProfiles.find { 
                    userManager.getSerialNumberForUser(it).toInt() == userHash 
                } ?: android.os.Process.myUserHandle()

                try {
                    val launcherActivities = launcherApps.getActivityList(pkg, user)
                    if (launcherActivities.isNotEmpty()) {
                        val ai = launcherActivities[0].applicationInfo
                        val name = pm.getApplicationLabel(ai).toString()
                        val isPrivate = if (Build.VERSION.SDK_INT >= 35) {
                            try {
                                val method = UserManager::class.java.getMethod("isPrivateProfile")
                                method.invoke(userManager) as Boolean
                            } catch (_: Exception) { false }
                        } else user != android.os.Process.myUserHandle()

                        AppInfo(name, pkg, isPrivate, userHash)
                    } else {
                        AppInfo(pkg, pkg, userHash != 0, userHash)
                    }
                } catch (_: Exception) {
                    AppInfo(pkg, pkg, userHash != 0, userHash)
                }
            }.distinctBy { "${it.packageName}_${it.userIdentifier}" }.sortedBy { it.name }
        }
        selectedApps = apps
        isResolvingApps = false
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
                val displayText = selectedApp?.let { 
                    if (it.isPrivateSpace) "${it.name} (Private Space)" else it.name 
                } ?: "Select an App"
                
                OutlinedTextField(
                    value = displayText,
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
                            text = { 
                                Column {
                                    Text(app.name)
                                    if (app.isPrivateSpace) {
                                        Text(
                                            "Private Space",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            },
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
                    val isNumericVar = (varName == "amount") || varName.contains("price") || varName.contains("total")

                    if (isDirectVar && isNumericVar) {
                        val parsed = parseAmount(resolvedValue)
                        if (parsed != null) put(mapping.key, parsed) else put(mapping.key, null as Double?)
                    } else {
                        val numericValue = resolvedValue.toDoubleOrNull()
                        if ((numericValue != null) && !mapping.valueTemplate.contains("{")) {
                            put(mapping.key, numericValue)
                        } else {
                            put(mapping.key, resolvedValue)
                        }
                    }
                }
            }
        }
    }

    // Validation
    val hasContent = if (mappings.isNotEmpty()) {
        finalBody.values.any { it !is kotlinx.serialization.json.JsonNull }
    } else {
        true
    }

    if (!hasContent) {
        val errorMsg = "Extraction failed: Could not resolve any variables for mappings."
        NotificationHelper.showErrorNotification(context, title, text, errorMsg)
        return "Error: $errorMsg"
    }

    return try {
        val client = createSupabaseClient(url, key) { install(Postgrest) }
        client.from(table).insert(finalBody)
        NotificationHelper.showSuccessNotification(context, title, text)
        "Success! Sent to $table"
    } catch (e: Exception) {
        val errorMsg = e.message ?: "Unknown error"
        AppLog.e(context, "TestSend", "Failed to send test: $errorMsg", e)
        NotificationHelper.showErrorNotification(context, title, text, errorMsg)
        "Error: $errorMsg"
    }
}

@Composable
fun NotificationHistoryScreen() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("iLogPrefs", Context.MODE_PRIVATE) }
    val historyPrefs = remember { context.getSharedPreferences("iLogHistory", Context.MODE_PRIVATE) }

    val lifecycleOwner = LocalLifecycleOwner.current
    var selectedPackageNames by remember {
        mutableStateOf(sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet())
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                selectedPackageNames = sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var selectedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isResolvingApps by remember { mutableStateOf(true) }

    LaunchedEffect(selectedPackageNames) {
        val apps = withContext(Dispatchers.IO) {
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val pm = context.packageManager

            selectedPackageNames.map { key ->
                val parts = key.split("_")
                val pkg = parts[0]
                val userHash = parts.getOrNull(1)?.toIntOrNull() ?: 0
                
                val user = userManager.userProfiles.find { 
                    userManager.getSerialNumberForUser(it).toInt() == userHash 
                } ?: android.os.Process.myUserHandle()

                try {
                    val launcherActivities = launcherApps.getActivityList(pkg, user)
                    if (launcherActivities.isNotEmpty()) {
                        val ai = launcherActivities[0].applicationInfo
                        val name = pm.getApplicationLabel(ai).toString()
                        val isPrivate = if (Build.VERSION.SDK_INT >= 35) {
                            try {
                                val method = UserManager::class.java.getMethod("isPrivateProfile")
                                method.invoke(userManager) as Boolean
                            } catch (_: Exception) { false }
                        } else user != android.os.Process.myUserHandle()

                        AppInfo(name, pkg, isPrivate, userHash)
                    } else {
                        AppInfo(pkg, pkg, userHash != 0, userHash)
                    }
                } catch (_: Exception) {
                    AppInfo(pkg, pkg, userHash != 0, userHash)
                }
            }.distinctBy { "${it.packageName}_${it.userIdentifier}" }.sortedBy { it.name }
        }
        selectedApps = apps
        isResolvingApps = false
    }

    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    var historyList by remember { mutableStateOf(listOf<NotificationEntry>()) }
    var activeNotifications by remember { mutableStateOf(listOf<NotificationEntry>()) }
    
    // Auto-select first app once resolved
    LaunchedEffect(selectedApps) {
        if (selectedApp == null && selectedApps.isNotEmpty()) {
            selectedApp = selectedApps.firstOrNull()
        }
    }

    fun refreshHistory() {
        val app = selectedApp
        if (app != null) {
            val json = historyPrefs.getString(app.packageName, "[]") ?: "[]"
            historyList = try {
                Json.decodeFromString<List<NotificationEntry>>(json)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            // Load all history
            val allHistory = mutableListOf<NotificationEntry>()
            val processedPackages = mutableSetOf<String>()
            selectedPackageNames.forEach { key ->
                val pkg = key.split("_")[0]
                if (pkg !in processedPackages) {
                    val json = historyPrefs.getString(pkg, "[]") ?: "[]"
                    try {
                        allHistory.addAll(Json.decodeFromString<List<NotificationEntry>>(json))
                    } catch (_: Exception) {}
                    processedPackages.add(pkg)
                }
            }
            historyList = allHistory.sortedByDescending { it.postTime }
        }
    }

    fun refreshActive() {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val active = NotificationService.getActiveNotifications()
        activeNotifications = active?.map { sbn ->
            val extras = sbn.notification.extras
            val userHash = userManager.getSerialNumberForUser(sbn.user).toInt()
            
            // Determine if this is a private space notification
            var isPrivate = false
            if (Build.VERSION.SDK_INT >= 35) {
                try {
                    val method = UserManager::class.java.getMethod("isPrivateProfile")
                    isPrivate = method.invoke(userManager) as Boolean
                } catch (_: Exception) {}
            }
            if (!isPrivate && userHash != 0) isPrivate = true

            NotificationEntry(
                title = extras.getString("android.title") ?: "No Title",
                text = extras.getCharSequence("android.text")?.toString() ?: "No Text",
                postTime = sbn.postTime,
                packageName = sbn.packageName,
                userIdentifier = userHash,
                isPrivateSpace = isPrivate
            )
        }?.filter { entry ->
            val appKey = "${entry.packageName}_${entry.userIdentifier}"
            if (selectedApp == null) {
                // If "All Apps" is selected, only show apps tracked in App Config
                selectedPackageNames.contains(appKey)
            } else {
                // If a specific app is selected, only show that one
                entry.packageName == selectedApp?.packageName && entry.userIdentifier == selectedApp?.userIdentifier
            }
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
                val displayText = selectedApp?.let { 
                    if (it.isPrivateSpace) "${it.name} (Private Space)" else it.name 
                } ?: "All Apps"

                OutlinedTextField(
                    value = displayText,
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
                            text = { 
                                Column {
                                    Text(app.name)
                                    if (app.isPrivateSpace) {
                                        Text(
                                            "Private Space",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            },
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
                            historyPrefs.edit { remove(app.packageName) }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (entry.isPrivateSpace) {
                            Text(
                                text = " • Private Space",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
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
                    examplesPrefs.edit {
                        putString("${entry.packageName}_title", entry.title)
                        putString("${entry.packageName}_text", entry.text)
                    }
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
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Debug Logs", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = {
                val sendIntent: Intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, logs)
                    type = "text/plain"
                }
                val shareIntent = Intent.createChooser(sendIntent, null)
                context.startActivity(shareIntent)
            }) {
                Icon(Icons.Default.Share, contentDescription = "Share Logs")
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Box(modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                if (logs.isEmpty()) {
                    Text(
                        text = "No logs yet.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    Text(
                        text = logs,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { logs = AppLog.getLogs(context) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Refresh")
            }
            
            OutlinedButton(
                onClick = { showClearConfirm = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Clear Logs")
            }
        }
    }

    if (showClearConfirm) {
        ConfirmationDialog(
            title = "Clear Logs",
            text = "Are you sure you want to delete all debug logs? This action cannot be undone.",
            onConfirm = {
                AppLog.clearLogs(context)
                logs = AppLog.getLogs(context)
            },
            onDismiss = { showClearConfirm = false }
        )
    }
}


data class AppInfo(
    val name: String, 
    val packageName: String,
    val isPrivateSpace: Boolean = false,
    val userIdentifier: Int = 0
)

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
    
    var allInstalledApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        isLoadingApps = true
        val apps = withContext(Dispatchers.IO) {
            val list = mutableListOf<AppInfo>()
            val pm = context.packageManager
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            
            // Try getting profiles from both sources to be as exhaustive as possible
            val profilesFromManager = userManager.userProfiles
            val profilesFromLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                launcherApps.profiles
            } else emptyList()
            val allProfiles = (profilesFromManager + profilesFromLauncher).distinctBy { it.hashCode() }
            
            AppLog.d(context, "AppConfig", "Found ${allProfiles.size} user profiles")
            
            val myUserHandle = android.os.Process.myUserHandle()
            
            for (user in allProfiles) {
                val isMainUser = user == myUserHandle
                val userHash = userManager.getSerialNumberForUser(user).toInt()
                
                val isQuiet = try { userManager.isQuietModeEnabled(user) } catch (_: Exception) { false }

                AppLog.d(context, "AppConfig", "Processing profile: $userHash (Main: $isMainUser, Quiet: $isQuiet)")

                // Improved detection for Private Space / Work Profile
                var isPrivate = false
                if (Build.VERSION.SDK_INT >= 35) {
                    try {
                        val method = UserManager::class.java.getMethod("isPrivateProfile")
                        isPrivate = method.invoke(userManager) as Boolean
                    } catch (_: Exception) {}
                }
                
                val isSecondarySpace = !isMainUser

                try {
                    // 1. Standard Launcher activities
                    val launcherActivities = try {
                        launcherApps.getActivityList(null, user)
                    } catch (e: Exception) {
                        AppLog.e(context, "AppConfig", "  Failed to get activity list for $userHash: ${e.message}")
                        emptyList()
                    }
                    
                    AppLog.d(context, "AppConfig", "  Found ${launcherActivities.size} launcher activities for user $userHash")
                    
                    launcherActivities.forEach { activity ->
                        val appInfo = activity.applicationInfo
                        val name = pm.getApplicationLabel(appInfo).toString()
                        val pkg = appInfo.packageName
                        
                        if (list.none { it.packageName == pkg && it.userIdentifier == userHash }) {
                            list.add(AppInfo(
                                name = name,
                                packageName = pkg,
                                isPrivateSpace = isPrivate || isSecondarySpace,
                                userIdentifier = userHash
                            ))
                        }
                    }
                    
                    // 2. Scan for specific packages if they were missed (sometimes hidden from launcher)
                    val commonPackages = listOf(
                        "com.revolut.revolut", "com.revolut.revolut.business", "com.revolut.business", 
                        "com.binance.dev", "com.cryptocom.app", "com.coinbase.android",
                        "com.google.android.apps.nbu.paisa.user", // GPay
                        "com.paypal.android.p2pmobile", "com.venmo", "com.squareup.cash"
                    )
                    commonPackages.forEach { pkg ->
                        if (list.none { it.packageName == pkg && it.userIdentifier == userHash }) {
                            try {
                                // Try to get AppInfo directly - this is often more reliable than isPackageEnabled
                                // which sometimes requires the app to have a Category Launcher.
                                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    try {
                                        launcherApps.getApplicationInfo(pkg, 0, user)
                                    } catch (_: Exception) { null }
                                } else null

                                if (appInfo != null || launcherApps.isPackageEnabled(pkg, user)) {
                                    val name = if (appInfo != null) {
                                        pm.getApplicationLabel(appInfo).toString()
                                    } else {
                                        val activities = launcherApps.getActivityList(pkg, user)
                                        if (activities.isNotEmpty()) {
                                            pm.getApplicationLabel(activities[0].applicationInfo).toString()
                                        } else {
                                            if (pkg.contains("revolut")) "Revolut" else pkg
                                        }
                                    }
                                    
                                    AppLog.d(context, "AppConfig", "  Manually verified package: $pkg for user $userHash")
                                    list.add(AppInfo(
                                        name = name,
                                        packageName = pkg,
                                        isPrivateSpace = isPrivate || isSecondarySpace,
                                        userIdentifier = userHash
                                    ))
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    AppLog.e(context, "AppConfig", "  Error fetching apps for user $userHash", e)
                }
            }
            
            // 3. Fallback: Add already selected apps even if they are not currently visible 
            // (e.g. if the space was locked during scan but we have them in prefs)
            selectedPackageNames.forEach { key ->
                val parts = key.split("_")
                val pkg = parts[0]
                val userHash = parts.getOrNull(1)?.toIntOrNull() ?: 0
                
                if (list.none { it.packageName == pkg && it.userIdentifier == userHash }) {
                    // Try to resolve name from existing list or use package
                    list.add(AppInfo(pkg, pkg, userHash != 0, userHash))
                }
            }

            list.sortedWith(compareBy({ it.name }, { it.isPrivateSpace }))
        }
        allInstalledApps = apps
        isLoadingApps = false
    }

    val selectedApps = remember(allInstalledApps, selectedPackageNames) {
        allInstalledApps.filter { 
            "${it.packageName}_${it.userIdentifier}" in selectedPackageNames 
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showSelector = true },
                modifier = Modifier.weight(1f),
                enabled = !isLoadingApps
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isLoadingApps) "Loading..." else "Add Apps")
            }
            
            OutlinedButton(
                onClick = { refreshTrigger++ },
                modifier = Modifier.weight(0.5f),
                enabled = !isLoadingApps
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                Text("Refresh")
            }
        }

        if (isLoadingApps) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        } else if (selectedApps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No apps selected. Click the button above to add apps.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(selectedApps, key = { "${it.packageName}_${it.userIdentifier}" }) { app ->
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
            text = "Are you sure you want to stop tracking ${app.name}${if (app.isPrivateSpace) " (Private Space)" else ""}? This will not delete your rules, but the app will no longer be processed.",
            onConfirm = {
                val appKey = "${app.packageName}_${app.userIdentifier}"
                val newSelection = selectedPackageNames - appKey
                selectedPackageNames = newSelection
                sharedPrefs.edit { putStringSet("selected_apps", newSelection) }
            },
            onDismiss = { appToDelete = null }
        )
    }

    if (showSelector) {
        var manualPkg by remember { mutableStateOf("") }
        var manualUserHash by remember { mutableIntStateOf(0) }
        var showManualEntry by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showSelector = false },
            title = { 
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Select Apps")
                    TextButton(onClick = { showManualEntry = !showManualEntry }) {
                        Text(if (showManualEntry) "Show List" else "Manual Entry")
                    }
                }
            },
            text = {
                Column {
                    if (showManualEntry) {
                        Text(
                            "Manually add a package name if it's hidden from the list.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = manualPkg,
                            onValueChange = { manualPkg = it },
                            label = { Text("Package Name") },
                            placeholder = { Text("e.g. com.revolut.revolut") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Target Space:", style = MaterialTheme.typography.labelMedium)
                        
                        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
                        userManager.userProfiles.forEach { profile ->
                            val hash = userManager.getSerialNumberForUser(profile).toInt()
                            val isMain = profile == android.os.Process.myUserHandle()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { manualUserHash = hash }
                            ) {
                                RadioButton(selected = manualUserHash == hash, onClick = { manualUserHash = hash })
                                Text(if (isMain) "Main Space" else "Private Space (User $hash)")
                            }
                        }
                        
                        Button(
                            onClick = {
                                if (manualPkg.isNotBlank()) {
                                    val appKey = "${manualPkg.trim()}_$manualUserHash"
                                    val newSelection = selectedPackageNames + appKey
                                    selectedPackageNames = newSelection
                                    sharedPrefs.edit { putStringSet("selected_apps", newSelection) }
                                    manualPkg = ""
                                    showSelector = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Text("Add Manually")
                        }
                    } else {
                        LazyColumn(modifier = Modifier.height(400.dp)) {
                            items(allInstalledApps, key = { "${it.packageName}_${it.userIdentifier}" }) { app ->
                                val appKey = "${app.packageName}_${app.userIdentifier}"
                                val isChecked = selectedPackageNames.contains(appKey)
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
                                                selectedPackageNames + appKey
                                            } else {
                                                selectedPackageNames - appKey
                                            }
                                            selectedPackageNames = newSelection
                                            sharedPrefs.edit { putStringSet("selected_apps", newSelection) }
                                        }
                                    )
                                    Column(modifier = Modifier.padding(start = 8.dp)) {
                                        Text(text = app.name)
                                        if (app.isPrivateSpace) {
                                            Text(
                                                text = "Private Space (User ${app.userIdentifier})",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                    }
                                }
                            }
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
    
    // Use packageName as configuration key (sharing rules between spaces)
    val configKey = app.packageName
    
    // Load rules for this app
    val rulesJson = sharedPrefs.getString(configKey, "[]") ?: "[]"
    val rules = remember(app.packageName) {
        try {
            Json.decodeFromString<List<ExtractionRule>>(rulesJson).toMutableStateList()
        } catch (_: Exception) {
            mutableStateListOf()
        }
    }
    
    // Load mappings for this app
    val mappingsJson = mappingsPrefs.getString(app.packageName, "[]") ?: "[]"
    val mappings = remember(app.packageName) {
        try {
            Json.decodeFromString<List<BodyMapping>>(mappingsJson).toMutableStateList()
        } catch (_: Exception) {
            mutableStateListOf()
        }
    }

    // Load available columns for selected table
    val targetTable = encryptedPrefs.getString("supabase_table", "") ?: ""
    val allColumnsJson = encryptedPrefs.getString("supabase_columns", "{}") ?: "{}"
    val availableColumns = remember(targetTable, allColumnsJson) {
        try {
            val map = Json.decodeFromString<Map<String, List<String>>>(allColumnsJson)
            map[targetTable] ?: emptyList()
        } catch (_: Exception) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = app.name)
                    if (app.isPrivateSpace) {
                        Text(
                            text = "Private Space",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
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
    sharedPrefs.edit { putString(packageName, json) }
}

private fun saveMappings(context: Context, packageName: String, mappings: List<BodyMapping>) {
    val sharedPrefs = context.getSharedPreferences("iLogMappings", Context.MODE_PRIVATE)
    val json = Json.encodeToString(mappings)
    sharedPrefs.edit { putString(packageName, json) }
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
                    } catch (_: Exception) {
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
