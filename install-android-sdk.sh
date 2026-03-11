#!/bin/bash

# Easy Android SDK installation for Ubuntu
# Installs SDK to ~/Android/Sdk (no sudo required for SDK itself)

set -e

ANDROID_HOME="$HOME/Android/Sdk"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "🚀 Installing Android SDK to $ANDROID_HOME"
echo ""

# Check for required tools
if ! command -v unzip &> /dev/null; then
    echo "⚠️  'unzip' is required. Installing..."
    sudo apt-get update && sudo apt-get install -y unzip
fi

if ! command -v java &> /dev/null; then
    echo "⚠️  Java is required. Installing OpenJDK 17..."
    sudo apt-get update && sudo apt-get install -y openjdk-17-jdk
fi

# Create SDK directory
mkdir -p "$ANDROID_HOME/cmdline-tools"

# Download and install command-line tools
if [ ! -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "📥 Downloading Android SDK command-line tools..."
    cd "$ANDROID_HOME/cmdline-tools"
    
    # Get latest version URL (this is the latest as of 2024)
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
    
    echo "📦 Extracting..."
    unzip -q cmdline-tools.zip
    mkdir -p latest
    mv cmdline-tools/* latest/ 2>/dev/null || true
    rm -rf cmdline-tools cmdline-tools.zip
    
    echo "✅ Command-line tools installed"
else
    echo "✅ Command-line tools already installed"
fi

# Set up environment
export ANDROID_HOME
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

# Accept licenses
echo ""
echo "📝 Accepting Android SDK licenses..."
yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || echo "Note: Some licenses may need manual acceptance"

# Install required components
echo ""
echo "📦 Installing required SDK components..."
echo "  - Platform tools"
"$SDKMANAGER" "platform-tools" > /dev/null

echo "  - Android Platform API 34"
"$SDKMANAGER" "platforms;android-34" > /dev/null

echo "  - Build tools 34.0.0"
"$SDKMANAGER" "build-tools;34.0.0" > /dev/null

# Create local.properties for the project
echo ""
echo "📝 Creating local.properties..."
echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_DIR/local.properties"
echo "✅ Created $PROJECT_DIR/local.properties"

echo ""
echo "✅ Android SDK installation complete!"
echo ""
echo "SDK Location: $ANDROID_HOME"
echo ""
echo "To make this permanent, add to your ~/.bashrc or ~/.zshrc:"
echo "  export ANDROID_HOME=$ANDROID_HOME"
echo "  export PATH=\$PATH:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin"
echo ""
echo "Now you can build the APK with:"
echo "  cd $PROJECT_DIR"
echo "  ./gradlew :frontend:androidApp:assembleDebug"
echo ""
