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

package it.allard.simcountry.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.allard.simcountry.data.AppContainer
import it.allard.simcountry.rules.AspectRules
import it.allard.simcountry.rules.CountryRule
import it.allard.simcountry.rules.SimRef
import it.allard.simcountry.telephony.Mcc
import it.allard.simcountry.telephony.SimRegistry

@Composable
fun RulesScreen(container: AppContainer, onEdit: (Int?) -> Unit) {
    val doc by container.rulesStore.doc.collectAsState()
    val allSims by container.simRegistry.subs.collectAsState()
    val sims = allSims.filter { it.hasIccid }
    val labelByIccid = sims.associateBy({ it.iccid }, { it.label })

    Box(Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            item(key = "daemon-banner") {
                DaemonRequiredBanner(allSims = allSims, pickerReady = sims)
            }
            item(key = "defaults") {
                DefaultsCard(
                    defaults = doc.defaults,
                    sims = sims,
                    onChange = { next ->
                        container.rulesStore.update { it.copy(defaults = next) }
                    },
                )
            }
            if (doc.rules.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No country overrides yet.", style = MaterialTheme.typography.titleMedium)
                        Text("Tap + to add one.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                itemsIndexed(
                    items = doc.rules,
                    key = { idx, r -> "$idx-${r.iso}-${r.mcc.orEmpty()}-${r.mnc.orEmpty()}" },
                ) { idx, rule ->
                    RuleCard(
                        rule = rule,
                        labels = labelByIccid,
                        onEdit = { onEdit(idx) },
                        onDelete = {
                            container.rulesStore.update { it.copy(rules = it.rules - rule) }
                        },
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { onEdit(null) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "Add country override")
        }
    }
}

@Composable
private fun RuleCard(
    rule: CountryRule,
    labels: Map<String, String>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${Mcc.nameOf(rule.iso)} (${rule.iso})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    val narrowing = listOfNotNull(
                        rule.mcc?.let { "MCC $it" },
                        rule.mnc?.let { "MNC $it" },
                    ).joinToString(", ")
                    if (narrowing.isNotEmpty()) {
                        Text(
                            "only when $narrowing",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    AspectLine("Data", rule.aspects.data?.iccid, labels)
                    AspectLine("Voice", rule.aspects.voice?.iccid, labels)
                    AspectLine("SMS", rule.aspects.sms?.iccid, labels)
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

@Composable
private fun AspectLine(name: String, iccid: String?, labels: Map<String, String>) {
    val display = when {
        iccid == null -> "leave alone"
        else -> labels[iccid] ?: iccid.takeLast(6)
    }
    Text("$name: $display", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun DefaultsCard(
    defaults: AspectRules,
    sims: List<SimRegistry.RegisteredSub>,
    onChange: (AspectRules) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Default SIMs", style = MaterialTheme.typography.titleMedium)
            Text(
                "Applied everywhere unless a country override below matches.",
                style = MaterialTheme.typography.bodySmall,
            )
            SimPicker("Data", defaults.data?.iccid, sims, leaveAloneText = "No default") { iccid ->
                onChange(defaults.copy(data = iccid?.let { SimRef(it) }))
            }
            SimPicker("Voice", defaults.voice?.iccid, sims, leaveAloneText = "No default") { iccid ->
                onChange(defaults.copy(voice = iccid?.let { SimRef(it) }))
            }
            SimPicker("SMS", defaults.sms?.iccid, sims, leaveAloneText = "No default") { iccid ->
                onChange(defaults.copy(sms = iccid?.let { SimRef(it) }))
            }
        }
    }
}
