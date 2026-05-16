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

package it.allard.simcountry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.allard.simcountry.data.AppContainer
import it.allard.simcountry.ui.rules.RuleEditScreen
import it.allard.simcountry.ui.rules.RulesScreen
import it.allard.simcountry.ui.sims.SimsScreen
import it.allard.simcountry.ui.status.StatusScreen

private sealed class Dest(val route: String, val label: String) {
    data object Status : Dest("status", "Status")
    data object Rules : Dest("rules", "Rules")
    data object Sims : Dest("sims", "SIMs")
    data object RuleEdit : Dest("ruleEdit/{mcc}", "Edit rule") {
        fun route(mcc: String?) = "ruleEdit/${mcc ?: NEW}"
        const val NEW = "_new"
    }
}

private val bottomDests = listOf(Dest.Status, Dest.Rules, Dest.Sims)

private fun parentTabRoute(currentRoute: String?): String? {
    if (currentRoute == null) return null
    return when {
        currentRoute.startsWith("ruleEdit/") -> Dest.Rules.route
        else -> currentRoute
    }
}

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val selectedTab = parentTabRoute(currentRoute)
    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomDests.forEach { d ->
                    NavigationBarItem(
                        selected = selectedTab == d.route,
                        onClick = {
                            nav.navigate(d.route) {
                                popUpTo(Dest.Status.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (d) {
                                    Dest.Status -> Icons.Outlined.Info
                                    Dest.Rules -> Icons.AutoMirrored.Outlined.Rule
                                    Dest.Sims -> Icons.Outlined.SimCard
                                    else -> Icons.Outlined.Info
                                },
                                contentDescription = d.label,
                            )
                        },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = nav, startDestination = Dest.Status.route) {
                composable(Dest.Status.route) {
                    StatusScreen(container = container)
                }
                composable(Dest.Rules.route) {
                    RulesScreen(
                        container = container,
                        onEdit = { mcc -> nav.navigate(Dest.RuleEdit.route(mcc)) },
                    )
                }
                composable(Dest.Sims.route) {
                    SimsScreen(container = container)
                }
                composable(
                    route = Dest.RuleEdit.route,
                    arguments = listOf(navArgument("mcc") { type = NavType.StringType }),
                ) { entry ->
                    val arg = entry.arguments?.getString("mcc")
                    val initialMcc = if (arg == Dest.RuleEdit.NEW) null else arg
                    RuleEditScreen(
                        container = container,
                        initialMcc = initialMcc,
                        onDone = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}
