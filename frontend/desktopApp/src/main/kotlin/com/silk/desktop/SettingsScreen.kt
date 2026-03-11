package com.silk.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silk.shared.i18n.*
import com.silk.shared.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(appState: AppState) {
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
                val response = withContext(Dispatchers.IO) {
                    ApiClient.getUserSettings(user.id)
                }
                if (response.success && response.settings != null) {
                    settings = response.settings
                    selectedLanguage = response.settings.language
                    defaultInstruction = response.settings.defaultAgentInstruction
                } else {
                    // Use defaults
                    selectedLanguage = Language.CHINESE
                    defaultInstruction = "You are a helpful technical research assistant. "
                }
            } catch (e: Exception) {
                println("加载设置失败: $e")
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
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        IconButton(
                            onClick = { appState.navigateBack() }
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                        Text(strings.settingsTitle)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Language selector
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = strings.languageLabel,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            FilterChip(
                                selected = selectedLanguage == Language.ENGLISH,
                                onClick = { selectedLanguage = Language.ENGLISH },
                                label = { Text(strings.languageEnglish) },
                                modifier = Modifier.weight(1f)
                            )
                            
                            FilterChip(
                                selected = selectedLanguage == Language.CHINESE,
                                onClick = { selectedLanguage = Language.CHINESE },
                                label = { Text(strings.languageChinese) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    
                    // Default agent instruction
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = strings.defaultAgentInstructionLabel,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        
                        OutlinedTextField(
                            value = defaultInstruction,
                            onValueChange = { defaultInstruction = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 5,
                            maxLines = 10,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                    
                    // Save message
                    if (saveMessage != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (saveMessage?.contains("成功") == true || saveMessage?.contains("success") == true)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = saveMessage ?: "",
                                modifier = Modifier.padding(16.dp),
                                color = if (saveMessage?.contains("成功") == true || saveMessage?.contains("success") == true)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    
                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { appState.navigateBack() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(strings.cancelButton)
                        }
                        
                        Button(
                            onClick = {
                                if (!isSaving) {
                                    scope.launch {
                                        isSaving = true
                                        saveMessage = null
                                        try {
                                            val response = withContext(Dispatchers.IO) {
                                                ApiClient.updateUserSettings(
                                                    userId = user.id,
                                                    language = selectedLanguage,
                                                    defaultAgentInstruction = defaultInstruction
                                                )
                                            }
                                            if (response.success) {
                                                settings = response.settings
                                                saveMessage = strings.settingsSaved
                                            } else {
                                                saveMessage = strings.settingsSaveError
                                            }
                                        } catch (e: Exception) {
                                            println("保存设置失败: $e")
                                            saveMessage = strings.settingsSaveError
                                        } finally {
                                            isSaving = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isSaving
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(strings.saveButton)
                        }
                    }
                }
            }
        }
    }
}
