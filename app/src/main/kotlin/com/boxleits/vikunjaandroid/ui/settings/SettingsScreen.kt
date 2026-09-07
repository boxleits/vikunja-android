package com.boxleits.vikunjaandroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lastSyncedAt by viewModel.lastSyncedAt.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val pendingEditCount by viewModel.pendingEditCount.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        ) {
            Text(text = "Instance", style = MaterialTheme.typography.labelLarge)
            Text(
                text = settings?.baseUrl ?: "Not connected",
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Last synced", style = MaterialTheme.typography.labelLarge)
            Text(
                text = lastSyncedAt?.toString() ?: "Never",
                style = MaterialTheme.typography.bodyLarge,
            )

            if (pendingEditCount > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Waiting to sync", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = if (pendingEditCount == 1) {
                        "1 change made on this device"
                    } else {
                        "$pendingEditCount changes made on this device"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = message, color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = viewModel::syncNow,
                enabled = !isSyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isSyncing) "Syncing…" else "Sync now")
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { viewModel.logOut(onDone = onBack) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Log out")
            }
        }
    }
}
