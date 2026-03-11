# Building Android APK for Silk

This guide explains how to build an Android APK from the Silk project.

## Prerequisites

You need to have the Android SDK installed. There are several ways to do this:

### Option 1: Easy Installation Script (Ubuntu/Linux - Recommended)

The easiest way on Ubuntu is to use the provided installation script:

```bash
./install-android-sdk.sh
```

This script will:
- Download and install the Android SDK command-line tools to `~/Android/Sdk`
- Install required components (platform-tools, Android 34, build-tools)
- Create `local.properties` automatically
- Only requires sudo for installing `unzip` and `java` if not already installed

### Option 2: Install Android Studio (Recommended for GUI users)

1. Download and install [Android Studio](https://developer.android.com/studio)
2. Open Android Studio and go through the setup wizard
3. The SDK will be installed automatically, typically at:
   - Linux: `~/Android/Sdk`
   - macOS: `~/Library/Android/sdk`
   - Windows: `%LOCALAPPDATA%\Android\Sdk`

### Option 3: Install Command Line Tools Manually

1. Download the [Android SDK Command Line Tools](https://developer.android.com/studio#command-tools)
2. Extract to a location like `~/android-sdk`
3. Run:
   ```bash
   cd ~/android-sdk/cmdline-tools/latest/bin
   ./sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```

## Configure SDK Location

After installing the Android SDK, you need to tell Gradle where it is. Create a `local.properties` file in the project root:

```bash
# If SDK is in default location (Android Studio)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# OR if SDK is in a custom location
echo "sdk.dir=/path/to/your/android/sdk" > local.properties
```

Alternatively, you can set the `ANDROID_HOME` environment variable:

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools
```

Add this to your `~/.bashrc` or `~/.zshrc` to make it permanent.

## Build the APK

### Debug APK (for testing)

```bash
./gradlew :frontend:androidApp:assembleDebug
```

The APK will be generated at:
```
frontend/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

### Release APK (unsigned)

```bash
./gradlew :frontend:androidApp:assembleRelease
```

The APK will be generated at:
```
frontend/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk
```

**Note:** The release APK will be unsigned. To install it, you'll need to enable "Install from Unknown Sources" on your device.

### Signing the Release APK (Optional)

For distribution, you should sign the release APK:

1. Generate a keystore (if you don't have one):
   ```bash
   keytool -genkey -v -keystore silk-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias silk
   ```

2. Create `frontend/androidApp/keystore.properties`:
   ```properties
   storeFile=../silk-release-key.jks
   storePassword=your-store-password
   keyAlias=silk
   keyPassword=your-key-password
   ```

3. Update `frontend/androidApp/build.gradle.kts` to add signing configuration:
   ```kotlin
   android {
       // ... existing config ...
       
       signingConfigs {
           create("release") {
               val keystorePropertiesFile = rootProject.file("keystore.properties")
               if (keystorePropertiesFile.exists()) {
                   val keystoreProperties = java.util.Properties()
                   keystoreProperties.load(java.io.FileInputStream(keystorePropertiesFile))
                   storeFile = file(keystoreProperties["storeFile"] as String)
                   storePassword = keystoreProperties["storePassword"] as String
                   keyAlias = keystoreProperties["keyAlias"] as String
                   keyPassword = keystoreProperties["keyPassword"] as String
               }
           }
       }
       
       buildTypes {
           getByName("release") {
               isMinifyEnabled = false
               signingConfig = signingConfigs.getByName("release")
           }
       }
   }
   ```

4. Build the signed release APK:
   ```bash
   ./gradlew :frontend:androidApp:assembleRelease
   ```

## Install the APK

### Using ADB (Android Debug Bridge)

1. Enable USB debugging on your Android device
2. Connect your device via USB
3. Install the APK:
   ```bash
   adb install frontend/androidApp/build/outputs/apk/debug/androidApp-debug.apk
   ```

### Manual Installation

1. Transfer the APK file to your Android device
2. On your device, enable "Install from Unknown Sources" in Settings
3. Open the APK file and follow the installation prompts

## Troubleshooting

### "SDK location not found" error

- Make sure `local.properties` exists in the project root with the correct `sdk.dir` path
- Or set the `ANDROID_HOME` environment variable

### "SDK platform not found" error

- Install the required SDK platform (API 34):
  ```bash
  sdkmanager "platforms;android-34"
  ```

### Build fails with dependency errors

- Sync Gradle dependencies:
  ```bash
  ./gradlew --refresh-dependencies
  ```

## Current App Configuration

- **Application ID:** `com.silk.android`
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34 (Android 14)
- **Version:** 1.0.12-robust-reconnect
- **Version Code:** 12
