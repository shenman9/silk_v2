plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"  // 添加 ShadowJar 插件
}

group = "com.silk"
version = "1.0.0"

application {
    mainClass.set("com.silk.backend.ApplicationKt")
}

// ShadowJar 配置
tasks.shadowJar {
    archiveClassifier.set("all")
    manifest {
        attributes(mapOf("Main-Class" to "com.silk.backend.ApplicationKt"))
    }
    mergeServiceFiles()  // 合并服务文件，修复 Exposed JDBC Provider 加载问题
    // minimize() 已移除 - 它会误删 Exposed JDBC provider 实现类
}

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core:2.3.6")
    implementation("io.ktor:ktor-server-netty:2.3.6")
    implementation("io.ktor:ktor-server-websockets:2.3.6")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.6")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.6")
    implementation("io.ktor:ktor-server-cors:2.3.6")
    implementation("io.ktor:ktor-server-compression:2.3.6")
    implementation("io.ktor:ktor-server-call-logging:2.3.6")
    
    // Ktor Client (用于调用外部 AI API)
    implementation("io.ktor:ktor-client-core:2.3.6")
    implementation("io.ktor:ktor-client-cio:2.3.6")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.6")
    
    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    // PDF 生成库
    implementation("com.itextpdf:itext7-core:7.2.5")
    implementation("com.itextpdf:layout:7.2.5")
    implementation("org.apache.pdfbox:pdfbox:2.0.29")
    
    // HTML解析（用于网页内容提取）
    implementation("org.jsoup:jsoup:1.17.2")
    
    // Playwright（无头浏览器，用于 JavaScript 渲染）
    implementation("com.microsoft.playwright:playwright:1.41.0")
    
    // Database (Exposed ORM + SQLite)
    implementation("org.jetbrains.exposed:exposed-core:0.44.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.44.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.44.1")
    implementation("org.jetbrains.exposed:exposed-java-time:0.44.1")
    implementation("org.xerial:sqlite-jdbc:3.43.0.0")
    
    // Password hashing (BCrypt)
    implementation("org.mindrot:jbcrypt:0.4")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.11")
    
    // Testing
    testImplementation("io.ktor:ktor-server-test-host:2.3.6")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
}

