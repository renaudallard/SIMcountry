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

package it.allard.simcountry.ui.pair

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.allard.simcountry.data.AppContainer
import it.allard.simcountry.daemon.autorestart.AutostartCoordinator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PairingScreen(container: AppContainer, onDone: () -> Unit) {
    val state by container.autostart.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var code by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Wireless ADB pairing", style = MaterialTheme.typography.titleLarge)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pair once; the daemon then restarts itself on every boot.", style = MaterialTheme.typography.bodyMedium)
                Text("1. Enable Wireless debugging in Developer options.", style = MaterialTheme.typography.bodyMedium)
                Text("2. Tap Pair device with pairing code; Android shows a six-digit code.", style = MaterialTheme.typography.bodyMedium)
                Text("3. Type the code below and tap Pair.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                    ) { Text("Open Developer options") }
                }
            }
        }

        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
            label = { Text("Pairing code (6 digits)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = code.length == 6 && !pairing,
                onClick = {
                    pairing = true
                    lastResult = null
                    scope.launch {
                        val r = container.autostart.pair(code)
                        pairing = false
                        lastResult = r.fold(
                            onSuccess = { "Pairing succeeded." },
                            onFailure = { "Pairing failed: ${it.message ?: it::class.simpleName}" },
                        )
                        if (r.isSuccess) onDone()
                    }
                },
            ) { Text(if (pairing) "Pairing..." else "Pair") }
            TextButton(onClick = onDone) { Text("Close") }
            if (pairing) CircularProgressIndicator()
        }

        lastResult?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodyMedium)
        }

        val s = state
        if (s is AutostartCoordinator.State.Paired) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Already paired", style = MaterialTheme.typography.titleMedium)
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    Text("First paired: ${fmt.format(Date(s.pairedAt))}", style = MaterialTheme.typography.bodySmall)
                    s.lastConnectAt?.let { Text("Last reconnect: ${fmt.format(Date(it))}", style = MaterialTheme.typography.bodySmall) }
                    s.lastError?.let { Text("Last error: $it", style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = {
                        scope.launch {
                            val r = container.autostart.reconnectDaemon()
                            lastResult = r.fold(
                                onSuccess = { "Reconnect requested." },
                                onFailure = { "Reconnect failed: ${it.message ?: it::class.simpleName}" },
                            )
                        }
                    }) { Text("Reconnect daemon now") }
                }
            }
        }
    }
}
