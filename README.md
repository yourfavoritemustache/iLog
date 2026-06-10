# iLog - Personal Notification Assistant

iLog is an Android application designed to capture, process, and synchronize notifications from selected apps directly to a Supabase database. It is particularly useful for tracking transactions, logging messages, or automating data entry from app alerts.

## 🚀 Features

- **Automated Notification Capture**: Monitors notifications from specific apps (e.g., Revolut, Nordea, etc.) even when the device is locked.
- **Dynamic Data Extraction**: Define custom regular expression (Regex) rules to extract specific values (like amounts, merchants, or dates) from notification titles and text.
- **Flexible Database Mapping**: Map extracted variables to specific columns in your Supabase tables.
- **Notification History**: View a local history of recently captured notifications and current active ones in the system tray.
- **Database Synchronization**: Automatically POSTs processed data to your Supabase instance.
- **Backup & Restore**: Securely save and retrieve your extraction rules and mappings using a unique backup key stored in Supabase.
- **Reliability Dashboard**: Built-in guides to ensure background processing is never interrupted by Android's battery optimization.

## 🛠️ Setup Instructions

### 1. Supabase Configuration
1. Go to the **Database** tab.
2. Enter your **Supabase URL** and **Service Role Key**.
3. Select your target **Table Name**.
4. Tap **Save Configuration**.

### 2. App Tracking
1. Navigate to the **App Config** tab.
2. Tap **Select Apps to Track** and choose the apps you want to monitor (e.g., your banking app).
3. For each app, add **Extraction Rules**:
   - Define a variable name (e.g., `amount`).
   - Provide a Regex pattern (e.g., `(\d+[\.,]\d{2})`).
   - Select the source (Title or Text).
4. Define **Body Mappings**:
   - Map your Supabase columns to system tags (like `{date}`, `{app}`, `{raw}`) or your custom variables (like `{amount}`).

### 3. Reliability Settings (Crucial)
To ensure iLog works in the background and while the screen is locked:
1. Go to the **Home** tab.
2. Ensure **Notification Access** is enabled.
3. Set Battery to **Unrestricted** (via the provided shortcut).
4. (Optional) Disable **Permission Hibernation**.

## 📝 How it Works

1. **Detection**: When a notification arrives from a selected app, the `NotificationService` triggers.
2. **Extraction**: The service runs your Regex rules against the notification content.
3. **Transformation**: Extracted values are cleaned (e.g., parsing currency amounts) and prepared for transport.
4. **Synchronization**: A JSON payload is constructed based on your mappings and sent to your Supabase table via the Postgrest API.
5. **Logging**: Detailed logs are available in the **Debug Logs** tab for troubleshooting extraction or sync issues.

## 🔒 Security & Privacy

- **Local Processing**: Notification content is processed locally on your device.
- **Encrypted Preferences**: Sensitive keys (Supabase URL/Key) are stored using Android's `EncryptedSharedPreferences`.
- **Private History**: Local history is stored in private app storage and is not accessible by other applications.

## 🛠 Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Database Client**: Supabase Kotlin SDK
- **Serialization**: Kotlinx Serialization
- **Service**: NotificationListenerService

---
*Note: This app is intended for personal automation and requires explicit Notification Access permissions to function.*
