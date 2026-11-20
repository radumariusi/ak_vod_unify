package ro.ak.vodunify.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.home.HomeScreen
import ro.ak.vodunify.ui.settings.SettingsScreen

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val spec = layoutSpec()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spec.screenPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "AK VOD Unify",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            TopDestination.entries.forEach { dest ->
                val selected = current == dest.route
                TextButton(
                    onClick = {
                        nav.navigate(dest.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(dest.icon, contentDescription = null)
                    if (!spec.isCompact || selected) {
                        Spacer(Modifier.width(6.dp))
                        Text(dest.label)
                    }
                }
            }
        }
        NavHost(nav, startDestination = TopDestination.HOME.route, modifier = Modifier.fillMaxSize()) {
            composable(TopDestination.HOME.route) { HomeScreen() }
            composable(TopDestination.SETTINGS.route) { SettingsScreen() }
        }
    }
}
