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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.allard.simcountry.data.AppContainer
import it.allard.simcountry.rules.AspectRules
import it.allard.simcountry.rules.CountryRule
import it.allard.simcountry.rules.SimRef
import it.allard.simcountry.telephony.Mcc

@Composable
fun RuleEditScreen(
    container: AppContainer,
    index: Int?,
    onDone: () -> Unit,
) {
    val doc by container.rulesStore.doc.collectAsState()
    val allSims by container.simRegistry.subs.collectAsState()
    val sims = allSims.filter { it.hasIccid }
    LaunchedEffect(Unit) { container.simRegistry.refresh() }
    val existing = index?.let { doc.rules.getOrNull(it) }

    var iso by remember { mutableStateOf(existing?.iso ?: "") }
    var mccNarrowing by remember { mutableStateOf(existing?.mcc) }
    var mnc by remember { mutableStateOf(existing?.mnc ?: "") }
    var data by remember { mutableStateOf(existing?.aspects?.data?.iccid) }
    var voice by remember { mutableStateOf(existing?.aspects?.voice?.iccid) }
    var sms by remember { mutableStateOf(existing?.aspects?.sms?.iccid) }
    var pickerOpen by remember { mutableStateOf(false) }

    val country = Mcc.byIso[iso]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (existing == null) "New rule" else "Edit rule",
            style = MaterialTheme.typography.titleLarge,
        )

        DaemonRequiredBanner(allSims = allSims, pickerReady = sims)

        OutlinedTextField(
            value = country?.let { "${it.name} (${it.iso})" } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Country") },
            placeholder = { Text("Tap to choose") },
            modifier = Modifier.fillMaxWidth().clickable { pickerOpen = true },
        )

        if (country != null && country.mccs.size > 1) {
            McNarrowingPicker(country.mccs, mccNarrowing) { mccNarrowing = it }
        }

        OutlinedTextField(
            value = mnc,
            onValueChange = { mnc = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("Operator MNC narrowing (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Subscriptions", style = MaterialTheme.typography.titleMedium)
                SimPicker("Data", data, sims) { data = it }
                SimPicker("Voice", voice, sims) { voice = it }
                SimPicker("SMS", sms, sims) { sms = it }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = iso.length == 2,
                onClick = {
                    val rule = CountryRule(
                        iso = iso,
                        mcc = mccNarrowing,
                        mnc = mnc.ifBlank { null },
                        aspects = AspectRules(
                            data = data?.let { SimRef(it) },
                            voice = voice?.let { SimRef(it) },
                            sms = sms?.let { SimRef(it) },
                        ),
                    )
                    container.rulesStore.update { current ->
                        val without = if (existing != null) {
                            current.rules.filterIndexed { i, _ -> i != index }
                        } else {
                            current.rules
                        }
                        val noCollisions = without.filterNot {
                            it.iso == rule.iso && it.mcc == rule.mcc && it.mnc == rule.mnc
                        }
                        current.copy(rules = noCollisions + rule)
                    }
                    onDone()
                },
            ) { Text(if (existing == null) "Add" else "Save") }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
    }

    if (pickerOpen) {
        CountryPickerSheet(
            onPick = { c ->
                iso = c.iso
                mccNarrowing = null
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McNarrowingPicker(
    available: List<String>,
    selected: String?,
    onChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = selected ?: "Any MCC"
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("MCC narrowing (optional)") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Any MCC") },
                onClick = { onChange(null); expanded = false },
            )
            available.forEach { mcc ->
                DropdownMenuItem(
                    text = { Text("Only MCC $mcc") },
                    onClick = { onChange(mcc); expanded = false },
                )
            }
        }
    }
}

