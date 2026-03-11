plugins {
    // Kotlin 和 KMP 版本
    kotlin("jvm") version "1.9.20" apply false
    kotlin("multiplatform") version "1.9.20" apply false
    kotlin("android") version "1.9.20" apply false
    kotlin("plugin.serialization") version "1.9.20" apply false
    
    // Android
    id("com.android.application") version "8.1.4" apply false
    id("com.android.library") version "8.1.4" apply false
    
    // Compose
    id("org.jetbrains.compose") version "1.5.11" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

tasks.register("cleanAll", Delete::class) {
    delete(rootProject.buildDir)
}

