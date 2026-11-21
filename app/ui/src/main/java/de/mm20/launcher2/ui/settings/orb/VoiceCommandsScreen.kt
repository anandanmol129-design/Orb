package de.mm20.launcher2.ui.settings.orb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.voice.VoiceSettings
import de.mm20.launcher2.voice.voiceCommandGroups
import org.koin.compose.koinInject

/**
 * Full-screen dialog with the voice tutorial and voice-command settings. A dialog keeps
 * back handling self-contained, so it works wherever the host screen ends up.
 */
@Composable
fun VoiceCommandsDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            VoiceCommandsScreen(onBack = onDismiss)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCommandsScreen(onBack: () -> Unit) {
    val settings: VoiceSettings = koinInject()
    var allowCalls by remember { mutableStateOf(settings.allowPhoneCalls) }
    var allowWhatsApp by remember { mutableStateOf(settings.allowWhatsApp) }
    var callDirectly by remember { mutableStateOf(settings.callDirectly) }
    var timeoutMs by remember { mutableStateOf(settings.silenceTimeoutMs) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice commands") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.arrow_back_24px), contentDescription = "Back")
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { VoiceSectionTitle("How to use") }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Say \"${settings.wakePhrase}\" on the home screen (if the wake word is on), " +
                                "or use the orb's Voice gesture or pie slice.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Then just speak. Orb closes by itself after a few seconds of silence, " +
                                "and Back cancels listening.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item { VoiceSectionTitle("What you can say") }
            voiceCommandGroups.forEach { group ->
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(group.title, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.size(4.dp))
                            Text(
                                group.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(8.dp))
                            group.examples.forEach { example ->
                                Text(
                                    "“${example.phrase}”",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            item { VoiceSectionTitle("Picking the right contact") }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Text(
                        "If the name you say matches exactly one contact, or the first two names match " +
                            "exactly one contact, Orb acts right away. Otherwise it opens search with " +
                            "the matching contacts so you can tap the right one. Saying the full name " +
                            "is the surest way.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            item { VoiceSectionTitle("Command settings") }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SwitchRow(
                            "Phone calls",
                            "Allow \"call X\" by voice.",
                            allowCalls,
                        ) { allowCalls = it; settings.allowPhoneCalls = it }
                        SwitchRow(
                            "WhatsApp actions",
                            "Allow WhatsApp calls, video calls and messages by voice.",
                            allowWhatsApp,
                        ) { allowWhatsApp = it; settings.allowWhatsApp = it }
                        SwitchRow(
                            "Call directly",
                            "Place the call right away when Phone permission is granted. " +
                                "Off opens the dialer with the number filled in.",
                            callDirectly,
                        ) { callDirectly = it; settings.callDirectly = it }
                    }
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Silence timeout", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "How long Orb waits for you to start speaking before it closes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VoiceSettings.SILENCE_TIMEOUT_CHOICES_MS.forEach { ms ->
                                FilterChip(
                                    selected = timeoutMs == ms,
                                    onClick = { timeoutMs = ms; settings.silenceTimeoutMs = ms },
                                    label = { Text("${ms / 1000} s") },
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun VoiceSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}
