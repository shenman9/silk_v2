package com.silk.web

import androidx.compose.runtime.*
import com.silk.shared.i18n.*
import com.silk.shared.models.*
import kotlinx.coroutines.launch
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun SettingsScene(appState: WebAppState) {
    val scope = rememberCoroutineScope()
    val user = appState.currentUser ?: return
    
    var settings by remember { mutableStateOf<UserSettings?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    
    // Local state for editing
    var selectedLanguage by remember { mutableStateOf<Language>(Language.CHINESE) }
    var defaultInstruction by remember { mutableStateOf("") }
    
    // Load settings on mount
    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            try {
                val response = ApiClient.getUserSettings(user.id)
                console.log("Settings response:", response)
                if (response.success && response.settings != null) {
                    val loadedSettings = response.settings!!
                    console.log("Loaded language:", loadedSettings.language)
                    settings = loadedSettings
                    selectedLanguage = loadedSettings.language
                    defaultInstruction = loadedSettings.defaultAgentInstruction
                } else {
                    // Use defaults
                    console.log("No settings found, using default CHINESE")
                    selectedLanguage = Language.CHINESE
                    defaultInstruction = "You are a helpful technical research assistant. "
                }
            } catch (e: Exception) {
                console.error("加载设置失败:", e)
                // Use defaults on error
                selectedLanguage = Language.CHINESE
                defaultInstruction = "You are a helpful technical research assistant. "
            } finally {
                isLoading = false
            }
        }
    }
    
    // Get strings based on selected language
    val strings = getStrings(selectedLanguage)
    
    // Get English strings for the language dropdown (to show bilingual names)
    val englishStrings = getStrings(Language.ENGLISH)
    val chineseStrings = getStrings(Language.CHINESE)
    
    Div({
        style {
            minHeight(100.vh)
            background("linear-gradient(135deg, ${SilkColors.background} 0%, ${SilkColors.surfaceElevated} 100%)")
            padding(0.px)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
        }
    }) {
        // Header
        Div({
            style {
                background("linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                color(Color.white)
                padding(16.px, 24.px)
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                justifyContent(JustifyContent.SpaceBetween)
                property("box-shadow", "0 2px 12px rgba(169, 137, 77, 0.2)")
            }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    gap(16.px)
                }
            }) {
                Button({
                    style {
                        backgroundColor(Color.transparent)
                        color(Color.white)
                        border { style(LineStyle.None) }
                        padding(8.px, 12.px)
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        fontSize(14.px)
                    }
                    onClick { appState.navigateBack() }
                }) {
                    Text("← ${strings.backButton}")
                }
                
                Span({
                    style {
                        color(Color.white)
                        fontSize(20.px)
                        property("font-weight", "600")
                        property("letter-spacing", "1px")
                    }
                }) {
                    Text(strings.settingsTitle)
                }
            }
        }
        
        // Content
        Div({
            style {
                flex(1)
                padding(32.px)
                maxWidth(800.px)
                width(100.percent)
                property("margin", "0 auto")
            }
        }) {
            if (isLoading) {
                Div({
                    style {
                        textAlign("center")
                        padding(60.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text("加载中...")
                }
            } else {
                // Language selector
                Div({ style { marginBottom(32.px) } }) {
                    Label {
                        Span({
                            style {
                                display(DisplayStyle.Block)
                                marginBottom(12.px)
                                color(Color(SilkColors.textPrimary))
                                fontSize(14.px)
                                property("font-weight", "600")
                            }
                        }) {
                            Text(strings.languageLabel)
                        }
                    }
                    
                    Select({
                        id("language-select")
                        style {
                            width(100.percent)
                            padding(12.px)
                            fontSize(14.px)
                            border {
                                width(1.px)
                                style(LineStyle.Solid)
                                color(Color(SilkColors.border))
                            }
                            borderRadius(8.px)
                            backgroundColor(Color(SilkColors.surfaceElevated))
                            property("box-sizing", "border-box")
                        }
                        attr("value", selectedLanguage.name)
                        onChange { event ->
                            val newValue = event.value ?: return@onChange
                            selectedLanguage = Language.valueOf(newValue)
                        }
                    }) {
                        Option(Language.ENGLISH.name) {
                            Text("${englishStrings.languageEnglish} - ${englishStrings.languageEnglishNative}")
                        }
                        Option(Language.CHINESE.name) {
                            Text("${englishStrings.languageChinese} - ${chineseStrings.languageChineseNative}")
                        }
                    }
                    
                    // Use LaunchedEffect to manually set the select value when selectedLanguage changes or after settings load
                    LaunchedEffect(selectedLanguage, isLoading) {
                        if (!isLoading) {
                            // Wait for DOM to be ready
                            kotlinx.coroutines.delay(50)
                            val select = document.getElementById("language-select")
                            if (select != null) {
                                select.asDynamic().value = selectedLanguage.name
                                console.log("Set select value to:", selectedLanguage.name)
                            }
                        }
                    }
                }
                
                // Default agent instruction
                Div({ style { marginBottom(32.px) } }) {
                    Label {
                        Span({
                            style {
                                display(DisplayStyle.Block)
                                marginBottom(12.px)
                                color(Color(SilkColors.textPrimary))
                                fontSize(14.px)
                                property("font-weight", "600")
                            }
                        }) {
                            Text(strings.defaultAgentInstructionLabel)
                        }
                    }
                    
                    TextArea {
                        style {
                            width(100.percent)
                            padding(12.px)
                            fontSize(14.px)
                            minHeight(120.px)
                            property("border", "1px solid ${SilkColors.border}")
                            borderRadius(8.px)
                            backgroundColor(Color(SilkColors.surfaceElevated))
                            property("box-sizing", "border-box")
                            property("resize", "vertical")
                            fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
                        }
                        value(defaultInstruction)
                        onInput { event -> defaultInstruction = event.value }
                    }
                }
                
                // Save message
                if (saveMessage != null) {
                    Div({
                        style {
                            padding(12.px, 16.px)
                            marginBottom(24.px)
                            borderRadius(8.px)
                            backgroundColor(
                                if (saveMessage?.contains("成功") == true || saveMessage?.contains("success") == true)
                                    Color("#E8F5E9")
                                else
                                    Color("#FFEBEE")
                            )
                            color(
                                if (saveMessage?.contains("成功") == true || saveMessage?.contains("success") == true)
                                    Color("#2E7D32")
                                else
                                    Color("#C62828")
                            )
                            fontSize(14.px)
                        }
                    }) {
                        Text(saveMessage ?: "")
                    }
                }
                
                // Buttons
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        gap(12.px)
                        justifyContent(JustifyContent.FlexEnd)
                    }
                }) {
                    Button({
                        style {
                            padding(12.px, 24.px)
                            backgroundColor(Color(SilkColors.secondary))
                            color(Color(SilkColors.textPrimary))
                            border { width(0.px) }
                            borderRadius(8.px)
                            property("cursor", "pointer")
                            fontSize(14.px)
                            property("font-weight", "500")
                        }
                        onClick { appState.navigateBack() }
                    }) {
                        Text(strings.cancelButton)
                    }
                    
                    Button({
                        style {
                            padding(12.px, 24.px)
                            background("linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                            color(Color.white)
                            border { width(0.px) }
                            borderRadius(8.px)
                            property("cursor", if (isSaving) "not-allowed" else "pointer")
                            property("opacity", if (isSaving) "0.6" else "1")
                            fontSize(14.px)
                            property("font-weight", "600")
                        }
                        onClick {
                            if (!isSaving) {
                                scope.launch {
                                    isSaving = true
                                    saveMessage = null
                                    try {
                                        val response = ApiClient.updateUserSettings(
                                            userId = user.id,
                                            language = selectedLanguage,
                                            defaultAgentInstruction = defaultInstruction
                                        )
                                        if (response.success && response.settings != null) {
                                            val savedSettings = response.settings!!
                                            settings = savedSettings
                                            selectedLanguage = savedSettings.language
                                            defaultInstruction = savedSettings.defaultAgentInstruction
                                            saveMessage = strings.settingsSaved
                                        } else {
                                            saveMessage = strings.settingsSaveError
                                        }
                                    } catch (e: Exception) {
                                        console.error("保存设置失败:", e)
                                        saveMessage = strings.settingsSaveError
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            }
                        }
                    }) {
                        Text(if (isSaving) "保存中..." else strings.saveButton)
                    }
                }
            }
        }
    }
}
