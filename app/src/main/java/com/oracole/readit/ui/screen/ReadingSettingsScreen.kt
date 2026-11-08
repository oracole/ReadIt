package com.oracole.readit.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oracole.readit.ui.reader.ReaderSettings
import com.oracole.readit.ui.reader.ReaderSettingsContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsScreen(settings: ReaderSettings, onBack: () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text("阅读设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            ReaderSettingsContent(settings, useReaderColors = false)
            Spacer(Modifier.height(24.dp))
        }
    }
}
