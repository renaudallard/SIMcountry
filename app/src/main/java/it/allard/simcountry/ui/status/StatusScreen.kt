/*
 * Copyright (c) 2026 Renaud Allard <renaud@allard.it>
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGES.
 */

package it.allard.simcountry.ui.status

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.allard.simcountry.daemon.autorestart.AutostartCoordinator
import it.allard.simcountry.data.AppContainer
import it.allard.simcountry.ipc.SimControlSocketClient
import it.allard.simcountry.ui.DaemonBanner
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatusScreen(container: AppContainer, onPair: () -> Unit) {
    val socketClient = container.simControlSocketClient
    val rulesDoc by container.rulesStore.doc.collectAsState()
    val suppressions by container.overrideDetector.suppressedUntil.collectAsState()
    val autostartState by container.autostart.state.collectAsState()
    val socketClientState by socketClient.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmForget by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DaemonBanner(socketClient)

        Card(modifier = Modifier.padding(horizontal = 8.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Autostart", style = MaterialTheme.typography.titleMedium)
                when (val s = autostartState) {
                    is AutostartCoordinator.State.Unpaired -> {
                        Text("Not paired. Pair once with Wireless ADB and the daemon will come back on every boot.")
                        if (socketClientState is SimControlSocketClient.State.Connected) {
                            Text(
                                "A previously started daemon is still attached for this session. It will stop at the next reboot unless you pair again.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = onPair) { Text("Pair Wireless ADB") }
                    }
                    is AutostartCoordinator.State.Paired -> {
                        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        Text("Paired ${fmt.format(Date(s.pairedAt))}.")
                        s.lastConnectAt?.let { Text("Last reconnect: ${fmt.format(Date(it))}") }
                        s.lastError?.let { Text("Last error: $it", style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = {
                            scope.launch { container.autostart.reconnectDaemon() }
                        }) { Text("Reconnect daemon now") }
                        TextButton(onClick = onPair) { Text("Re-pair") }
                        TextButton(onClick = { confirmForget = true }) { Text("Forget pairing") }
                        TextButton(onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        }) { Text("Open Developer options") }
                    }
                }
            }
        }

        Card(modifier = Modifier.padding(horizontal = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Policy", style = MaterialTheme.typography.titleMedium)
                Text("stability: ${rulesDoc.policy.stabilitySec}s")
                Text("reverse hysteresis: ${rulesDoc.policy.reverseHysteresisSec}s")
                Text("min switch interval: ${rulesDoc.policy.minSwitchIntervalSec}s")
                Text("override suppression: ${rulesDoc.policy.overrideSuppressionSec}s")
            }
        }

        DefaultDataSubCard(socketClient, enabled = socketClientState is SimControlSocketClient.State.Connected)

        if (suppressions.isNotEmpty()) {
            Card(modifier = Modifier.padding(horizontal = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Suppressed countries", style = MaterialTheme.typography.titleMedium)
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    suppressions.forEach { (mcc, until) ->
                        Column {
                            Text("MCC $mcc until ${fmt.format(Date(until))}")
                            TextButton(onClick = {
                                scope.launch { container.overrideDetector.clearSuppression(mcc) }
                            }) { Text("Clear") }
                        }
                    }
                }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget pairing?") },
            text = {
                Text(
                    "The daemon's authentication key will be deleted and a fresh one generated. " +
                        "You'll need to re-pair Wireless ADB before autostart works again. " +
                        "The device still trusts the old key in its Wireless Debugging list; " +
                        "open Developer options to revoke it manually if you want a full cleanup.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    scope.launch { container.autostart.forgetPairing() }
                }) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DefaultDataSubCard(client: SimControlSocketClient, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }

    Card(modifier = Modifier.padding(horizontal = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Default data sub-id (debug)", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Current: ${current?.toString() ?: "?"}",
                    modifier = Modifier.padding(end = 12.dp),
                )
                Button(
                    enabled = enabled,
                    onClick = {
                        scope.launch {
                            error = null
                            runCatching { client.getDefaultDataSubId() }
                                .onSuccess { current = it }
                                .onFailure { error = it.message ?: it::class.simpleName }
                        }
                    },
                ) { Text("Refresh") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it.filter { c -> c.isDigit() } },
                    label = { Text("sub id") },
                    singleLine = true,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Button(
                    enabled = enabled && inputText.toIntOrNull() != null,
                    onClick = {
                        val id = inputText.toInt()
                        scope.launch {
                            error = null
                            runCatching { client.setDefaultDataSubId(id) }
                                .onSuccess { current = id }
                                .onFailure { error = it.message ?: it::class.simpleName }
                        }
                    },
                ) { Text("Set") }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
