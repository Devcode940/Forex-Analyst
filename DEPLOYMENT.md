# Android App Deployment, Java Version & CI/CD Guide

This guide covers the build environment, Java/JDK requirements, deployment processes, and CI/CD pipelines for this Android project.

---

## 1. Environment & Java Requirements

### Required Tooling
- **Java / JDK Version**: **JDK 17** (or **JDK 21**)
  - *Note*: Android Gradle Plugin (AGP 8.x / 9.x) requires **Java 17+** to execute the Gradle build daemon.
  - In `app/build.gradle.kts`, `sourceCompatibility` and `targetCompatibility` are set to `JavaVersion.VERSION_11` (or `VERSION_17`) for runtime bytecode compatibility.
- **Android SDK**:
  - `compileSdk`: **36** (Android 15 / 16 preview)
  - `targetSdk`: **36**
  - `minSdk`: **24** (Android 7.0 Nougat and above)
- **Build System**: Gradle with Kotlin DSL (`build.gradle.kts`) and Version Catalog (`gradle/libs.versions.toml`).

---

## 2. How to Build & Deploy

### A. Local Development Builds

#### 1. Build Debug APK
To assemble a debug APK for testing or installation:
```bash
./gradlew assembleDebug
```
Output location:
`app/build/outputs/apk/debug/app-debug.apk`

#### 2. Run Unit & Screenshot Tests
```bash
# Run unit and JVM Robolectric tests
./gradlew :app:testDebugUnitTest

# Verify Roborazzi screenshot tests
./gradlew :app:verifyRoborazziDebug
```

---

### B. Production Release Build (APK & AAB)

#### 1. Keystore & Signing Configuration
The project is pre-configured in `app/build.gradle.kts` to look for signing credentials via environment variables:
- `KEYSTORE_PATH`: Path to your `.jks` or `.keystore` file (default: `${rootDir}/my-upload-key.jks`)
- `STORE_PASSWORD`: Keystore password
- `KEY_PASSWORD`: Key alias password

To generate a new release keystore:
```bash
keytool -genkey -v -keystore my-upload-key.jks \
  -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

#### 2. Build Release Android App Bundle (AAB for Google Play)
```bash
export KEYSTORE_PATH="/path/to/my-upload-key.jks"
export STORE_PASSWORD="your_store_password"
export KEY_PASSWORD="your_key_password"

./gradlew bundleRelease
```
Output location:
`app/build/outputs/bundle/release/app-release.aab`

#### 3. Build Release APK (Direct Sideloading)
```bash
./gradlew assembleRelease
```
Output location:
`app/build/outputs/apk/release/app-release.apk`

---

### C. Secrets & API Keys
Secrets like API keys (`GEMINI_API_KEY`, Firebase configuration) are managed via the Secrets Gradle plugin:
1. Create a `.env` file in the root project (or copy from `.env.example`):
   ```env
   GEMINI_API_KEY=your_gemini_api_key_here
   ```
2. For Firebase integration, place your official `google-services.json` inside the `app/` directory.

---

## 3. GitHub Actions CI/CD Workflow

A complete GitHub Actions CI/CD configuration is available at `.github/workflows/android_ci.yml`.

### Workflow Capabilities:
1. **Continuous Integration (CI)**: Runs on every `push` and `pull_request` to `main`:
   - Checks out repository.
   - Sets up **JDK 17** with Temurin distribution.
   - Configures Gradle caching.
   - Runs unit tests (`:app:testDebugUnitTest`).
   - Builds Debug APK and uploads it as a workflow artifact.
2. **Release / Deployment (CD)**: Runs when a new tag is pushed (e.g. `v1.0.0`) or via manual trigger (`workflow_dispatch`):
   - Decodes release keystore from GitHub Secrets.
   - Builds Release Android App Bundle (`.aab`) and Release APK (`.apk`).
   - Uploads build outputs as release assets.

### Required GitHub Secrets for CD:
Add these in your GitHub repository under **Settings > Secrets and variables > Actions**:
- `RELEASE_KEYSTORE_BASE64`: Base64 encoded `.jks` file (`base64 -w 0 my-upload-key.jks`)
- `STORE_PASSWORD`: Password for keystore
- `KEY_PASSWORD`: Password for upload key alias
- `GEMINI_API_KEY`: Google Gemini API key
