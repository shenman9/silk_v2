rootProject.name = "Silk"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":backend")
include(":frontend:shared")
include(":frontend:androidApp")  // Android 应用
include(":frontend:desktopApp")
include(":frontend:webApp")  // Web 应用已启用

