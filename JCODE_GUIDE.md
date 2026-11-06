# JCode Build, Deploy & Push Guide

This guide provides step-by-step instructions for building, deploying to the virtual device, and pushing code to GitHub within the **JCode** environment for the **Forex & Crypto Technical Analyst** Android app.

---

## 1. ⚙️ JCode Environment Setup & Paths

JCode runs a Linux container (PRoot on Android) with pre-configured Android SDK and tools.

| Component | Path / Value |
|---|---|
| **Project Root** | `/workspace/forex-analyst/` |
| **App Module** | `/workspace/forex-analyst/app/` |
| **Gradle Wrapper** | `/workspace/forex-analyst/gradlew` |
| **Android SDK Root** | `/home/jcode/android-sdk/` |
| **Build Tools** | `37.0.0` (configured in `app/build.gradle.kts`) |
| **Attached Device** | `JCode vDevice` (`mucbxh3v`, Android 13 / API 33) |

> [!IMPORTANT]
> When opening a new terminal tab in JCode, the current working directory often defaults to `/workspace/forex-analyst/app`. 
> - If you are in `/workspace/forex-analyst/`, run `./gradlew <task>`
> - If you are in `/workspace/forex-analyst/app/`, run `../gradlew <task>` or switch first: `cd /workspace/forex-analyst`

---

## 2. 🔑 Environment & Secrets Configuration

Create or update your `.env` file in the project root:

```bash
cd /workspace/forex-analyst
cp -n .env.example .env
```

Ensure the following keys are set in `/workspace/forex-analyst/.env`:
```env
# FCS API Key for live forex rates and intraday candles
FCS_API_KEY=your_fcs_api_key_here

# OpenRouter API Key for AI technical breakdown reports
OPENROUTER_API_KEY=your_openrouter_api_key_here

# Alpha Vantage API Key for real market spot quotes and daily candles
ALPHAVANTAGE_API_KEY=your_alphavantage_api_key_here
```

---

## 3. 🔨 Building the Application

### A. Build Debug APK
To compile and assemble the debug APK:
```bash
cd /workspace/forex-analyst
./gradlew assembleDebug
```
The output APK is generated at:
```
/workspace/forex-analyst/app/build/outputs/apk/debug/app-debug.apk
```

### B. Run Unit Tests
```bash
cd /workspace/forex-analyst
./gradlew :app:testDebugUnitTest
```

### C. Build Production Release (AAB & APK)
```bash
cd /workspace/forex-analyst
export KEYSTORE_PATH="/workspace/forex-analyst/my-upload-key.jks"
export STORE_PASSWORD="your_keystore_password"
export KEY_PASSWORD="your_key_alias_password"

# Build App Bundle for Google Play Store
./gradlew bundleRelease

# Build standalone signed Release APK
./gradlew assembleRelease
```
Outputs:
- AAB: `app/build/outputs/bundle/release/app-release.aab`
- APK: `app/build/outputs/apk/release/app-release.apk`

---

## 4. 📱 Deploying & Launching on JCode Virtual Device

JCode provides an attached Android device accessible via `adb`.

### Step 1: Verify the Connected Device
```bash
adb devices
```
Expected output:
```text
List of devices attached
mucbxh3v	device
```

### Step 2: Install the Debug APK
```bash
adb install -r /workspace/forex-analyst/app/build/outputs/apk/debug/app-debug.apk
```
> [!NOTE]
> The `-r` flag allows reinstalling over an existing version while preserving app data and Room database state.

### Step 3: Launch the Main Activity
The app's package is `com.devcode.forexapp` and entry activity is `com.example.MainActivity`:
```bash
adb shell am start -n com.devcode.forexapp/com.example.MainActivity
```

### ⚡ All-in-One Shortcut (Build, Deploy & Launch)
Run this single command from the project root:
```bash
cd /workspace/forex-analyst && \
  ./gradlew assembleDebug && \
  adb install -r app/build/outputs/apk/debug/app-debug.apk && \
  adb shell am start -n com.devcode.forexapp/com.example.MainActivity
```

### Step 4: Inspect Live Logs (Logcat)
Filter logs from the app's components (Repository, ViewModel, Background Worker):
```bash
adb logcat -v time -s ForexRepository:V ForexViewModel:V SignalWorker:V
```

To stop viewing logs, press `Ctrl + C`.

---

## 5. 🚀 Pushing Changes to GitHub

### Step 1: Check Working Tree & Stage Changes
```bash
cd /workspace/forex-analyst
git status
git add .
git commit -m "feat: description of changes"
```

### Step 2: Handle GitHub Authentication (Avoid HTTP 403)
In JCode, `git push origin main` can fail with `Permission denied to <user> (403)` if the environment's cached Git credentials do not match the repository owner (`Devcode940`).

To push successfully using your **GitHub Personal Access Token (PAT)**:

#### Method A: Direct One-Time Push (Recommended)
```bash
git push https://<YOUR_GITHUB_PAT>@github.com/Devcode940/Forex-Analyst.git main
```
*(Replace `<YOUR_GITHUB_PAT>` with your token starting with `ghp_`)*

#### Method B: Set Remote URL with Token
```bash
cd /workspace/forex-analyst
git remote set-url origin https://<YOUR_GITHUB_PAT>@github.com/Devcode940/Forex-Analyst.git
git push origin main
```

#### Method C: Store Credentials in Git Helper
```bash
git config --global credential.helper store
git push origin main
# Username: Devcode940
# Password: <YOUR_GITHUB_PAT>
```

---

## 6. 🛠️ Troubleshooting Common Issues

| Issue | Cause | Fix |
|---|---|---|
| `bash: ./gradlew: No such file or directory` | Current terminal working directory is `app/` instead of root | Run `cd /workspace/forex-analyst` or use `../gradlew` |
| `SDK build-tools 36.0.0 missing / downloading` | Missing explicit `buildToolsVersion` | Ensure `buildToolsVersion = "37.0.0"` is present inside `android { ... }` in `app/build.gradle.kts` |
| `remote: Permission to Devcode940/Forex-Analyst.git denied (403)` | Outdated or mismatched Git credentials | Use your GitHub PAT as demonstrated in Section 5 |
| `adb: no devices/emulators found` | adb server hung or restart required | Run `adb kill-server && adb devices` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Existing APK signed with different keystore | Uninstall first: `adb uninstall com.devcode.forexapp` then reinstall |
