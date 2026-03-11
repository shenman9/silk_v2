#!/bin/bash
# Playwright 浏览器安装脚本
# 
# Playwright 需要下载 Chromium 浏览器才能工作
# 此脚本会自动下载所需的浏览器

echo "🎭 安装 Playwright 浏览器..."

# 检查 Java 是否可用
if ! command -v java &> /dev/null; then
    echo "❌ Java 未安装，请先安装 JDK"
    exit 1
fi

# 使用 Playwright CLI 安装浏览器
# 方法1: 使用 npx (如果有 Node.js)
if command -v npx &> /dev/null; then
    echo "📦 使用 npx 安装 Playwright 浏览器..."
    npx playwright install chromium
    npx playwright install-deps chromium
    echo "✅ Playwright 浏览器安装完成"
    exit 0
fi

# 方法2: 手动设置环境变量让 Playwright Java 自动下载
echo "📦 设置 Playwright 自动下载..."
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=0

# 创建一个简单的 Java 程序来触发浏览器下载
cat > /tmp/PlaywrightInstall.java << 'EOF'
import com.microsoft.playwright.*;

public class PlaywrightInstall {
    public static void main(String[] args) {
        System.out.println("正在下载 Playwright 浏览器...");
        try (Playwright playwright = Playwright.create()) {
            System.out.println("Playwright 初始化成功");
            try (Browser browser = playwright.chromium().launch()) {
                System.out.println("Chromium 浏览器可用");
            }
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        }
        System.out.println("✅ 安装完成");
    }
}
EOF

echo "💡 提示: Playwright Java 版会在首次运行时自动下载浏览器"
echo "   如果下载失败，请手动安装 Node.js 并运行:"
echo "   npx playwright install chromium"
echo ""
echo "   或者在有网络的环境下首次启动后端服务"

# 安装系统依赖（Debian/Ubuntu）
if command -v apt-get &> /dev/null; then
    echo ""
    echo "📦 安装系统依赖..."
    sudo apt-get update
    sudo apt-get install -y \
        libnss3 \
        libnspr4 \
        libatk1.0-0 \
        libatk-bridge2.0-0 \
        libcups2 \
        libdrm2 \
        libdbus-1-3 \
        libxkbcommon0 \
        libxcomposite1 \
        libxdamage1 \
        libxfixes3 \
        libxrandr2 \
        libgbm1 \
        libasound2 \
        libpango-1.0-0 \
        libcairo2 \
        libatspi2.0-0 \
        libgtk-3-0 \
        2>/dev/null || echo "⚠️ 部分依赖安装失败，可能需要手动安装"
fi

# 安装系统依赖（CentOS/RHEL/Alibaba Cloud Linux）
if command -v yum &> /dev/null; then
    echo ""
    echo "📦 安装系统依赖 (yum)..."
    sudo yum install -y \
        nss \
        nspr \
        atk \
        at-spi2-atk \
        cups-libs \
        libdrm \
        dbus-libs \
        libxkbcommon \
        libXcomposite \
        libXdamage \
        libXfixes \
        libXrandr \
        mesa-libgbm \
        alsa-lib \
        pango \
        cairo \
        at-spi2-core \
        gtk3 \
        2>/dev/null || echo "⚠️ 部分依赖安装失败，可能需要手动安装"
fi

echo ""
echo "✅ 安装脚本执行完成"
echo "   首次启动后端时，Playwright 会自动下载浏览器"
