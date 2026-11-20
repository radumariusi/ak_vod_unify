package ro.ak.vodunify.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ro.ak.vodunify.core.calendar.android.CalendarWork
import ro.ak.vodunify.ui.calendar.CalendarScreen
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.details.DetailsScreen
import ro.ak.vodunify.ui.diagnostics.DiagnosticsScreen
import ro.ak.vodunify.ui.home.HomeScreen
import ro.ak.vodunify.ui.library.LibraryScreen
import ro.ak.vodunify.ui.person.PersonScreen
import ro.ak.vodunify.ui.search.SearchScreen
import ro.ak.vodunify.ui.settings.SettingsScreen
import ro.ak.vodunify.ui.studio.StudioScreen

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val spec = layoutSpec()
    val isTopLevel = current == null || TopDestination.entries.any { it.route == current }

    val actions = remember(nav) {
        CatalogActions(
            openTitle = { kind, id -> nav.navigate(Routes.title(kind, id)) },
            openPerson = { id -> nav.navigate(Routes.person(id)) },
            openStudio = { kind, id -> nav.navigate(Routes.studio(kind, id)) },
            openSettings = { nav.navigateTop(TopDestination.SETTINGS) },
            openDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
        )
    }

    // Notificarea / canalul „Azi apare” deschid direct Calendarul.
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(activity) {
        val intent = activity?.intent
        if (intent?.getBooleanExtra(CalendarWork.EXTRA_OPEN_CALENDAR, false) == true) {
            intent.removeExtra(CalendarWork.EXTRA_OPEN_CALENDAR)
            nav.navigateTop(TopDestination.CALENDAR)
        }
    }

    CompositionLocalProvider(LocalCatalogActions provides actions) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spec.screenPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!isTopLevel) {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi")
                    }
                }
                if (!spec.isCompact || isTopLevel) {
                    Text(
                        "AK VOD Unify",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(16.dp))
                }
                TopDestination.entries.forEach { dest ->
                    val selected = current == dest.route
                    TextButton(
                        onClick = { nav.navigateTop(dest) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (selected) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Icon(dest.icon, contentDescription = dest.label)
                        if (!spec.isCompact || selected) {
                            Spacer(Modifier.width(6.dp))
                            Text(dest.label)
                        }
                    }
                }
            }
            NavHost(nav, startDestination = TopDestination.HOME.route, modifier = Modifier.fillMaxSize()) {
                composable(TopDestination.HOME.route) { HomeScreen() }
                composable(TopDestination.SEARCH.route) { SearchScreen() }
                composable(TopDestination.CALENDAR.route) { CalendarScreen() }
                composable(TopDestination.LIBRARY.route) { LibraryScreen() }
                composable(TopDestination.SETTINGS.route) { SettingsScreen() }
                composable(Routes.DIAGNOSTICS) { DiagnosticsScreen() }
                composable(
                    Routes.TITLE,
                    arguments = listOf(
                        navArgument("kind") { type = NavType.StringType },
                        navArgument("id") { type = NavType.LongType },
                    ),
                ) { DetailsScreen() }
                composable(
                    Routes.PERSON,
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { PersonScreen() }
                composable(
                    Routes.STUDIO,
                    arguments = listOf(
                        navArgument("kind") { type = NavType.StringType },
                        navArgument("id") { type = NavType.LongType },
                    ),
                ) { StudioScreen() }
            }
        }
    }
}

private fun NavHostController.navigateTop(dest: TopDestination) {
    navigate(dest.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
