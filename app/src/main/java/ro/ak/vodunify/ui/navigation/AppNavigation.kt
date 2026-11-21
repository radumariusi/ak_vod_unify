package ro.ak.vodunify.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import ro.ak.vodunify.ui.components.BackdropState
import ro.ak.vodunify.ui.components.Button
import ro.ak.vodunify.ui.components.ExpireFocusRestore
import ro.ak.vodunify.ui.components.FocusBorderWidth
import ro.ak.vodunify.ui.components.FocusRestore
import ro.ak.vodunify.ui.components.IconButton
import ro.ak.vodunify.ui.components.LocalBackdrop
import ro.ak.vodunify.ui.components.LocalFocusRestore
import ro.ak.vodunify.ui.components.OutlinedButton
import ro.ak.vodunify.ui.components.TitleBackdrop
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.details.DetailsScreen
import ro.ak.vodunify.ui.diagnostics.DiagnosticsScreen
import ro.ak.vodunify.ui.home.HomeScreen
import ro.ak.vodunify.ui.library.LibraryScreen
import ro.ak.vodunify.ui.person.PersonScreen
import ro.ak.vodunify.ui.search.SearchScreen
import ro.ak.vodunify.ui.settings.SettingsScreen
import ro.ak.vodunify.ui.studio.StudioScreen
import ro.ak.vodunify.ui.theme.FocusBorder

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
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

    val backdrop = remember { BackdropState() }
    val restore = remember { FocusRestore() }
    var exitPrompt by remember { mutableStateOf(false) }
    val onHome = current == TopDestination.HOME.route
    SideEffect { restore.current = current }
    ExpireFocusRestore(restore)

    // Înapoi din Acasă cere confirmare (ca în AK VOD Hub); din celelalte file duce în Acasă.
    BackHandler(enabled = onHome) { exitPrompt = true }
    if (exitPrompt) {
        AlertDialog(
            onDismissRequest = { exitPrompt = false },
            title = { Text("Ieși din AK VOD Unify?") },
            confirmButton = { Button(onClick = { exitPrompt = false; activity?.finish() }) { Text("Ieși") } },
            dismissButton = { OutlinedButton(onClick = { exitPrompt = false }) { Text("Rămâi") } },
        )
    }

    CompositionLocalProvider(
        LocalCatalogActions provides actions,
        LocalBackdrop provides backdrop,
        LocalFocusRestore provides restore,
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // Fundalul panoramic: titlul cu focus din Acasă, peste tot ecranul, sub meniu.
            if (onHome) TitleBackdrop(backdrop.url, Modifier.matchParentSize())
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    current = current,
                    showBack = !isTopLevel,
                    onBack = { nav.popBackStack() },
                    onSelect = { nav.navigateTop(it) },
                )
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
}

/**
 * Meniul de sus, ca în AK VOD Hub: marca AK VOD în stânga, filele centrate (text, cu un singur indicator roșu
 * sub fila activă) și focus distinct (contur alb). Sus din conținut intră direct în fila activă.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TopBar(
    current: String?,
    showBack: Boolean,
    onBack: () -> Unit,
    onSelect: (TopDestination) -> Unit,
) {
    val spec = layoutSpec()
    val tabs = remember { TopDestination.entries.associateWith { FocusRequester() } }
    val active = TopDestination.entries.firstOrNull { it.route == current }
    val scroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(horizontal = if (spec.isCompact) 8.dp else 20.dp, vertical = if (spec.isTv) 10.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi")
            }
            Spacer(Modifier.width(4.dp))
        }
        if (!spec.isCompact || !showBack) {
            Brand(compact = spec.isCompact)
            Spacer(Modifier.width(if (spec.isCompact) 8.dp else 24.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = if (spec.isCompact) Alignment.CenterStart else Alignment.Center) {
            Row(
                Modifier
                    .horizontalScroll(scroll)
                    .focusProperties {
                        enter = { tabs[active ?: TopDestination.HOME] ?: FocusRequester.Default }
                    }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(if (spec.isCompact) 4.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopDestination.entries.forEach { dest ->
                    NavTab(
                        dest = dest,
                        active = dest == active,
                        iconOnly = spec.isCompact && dest != active,
                        modifier = Modifier.focusRequester(tabs.getValue(dest)),
                        onClick = { onSelect(dest) },
                    )
                }
            }
        }
        if (!spec.isCompact) {
            // Echilibrează marca din stânga, ca filele să rămână centrate pe ecran.
            Spacer(Modifier.width(if (showBack) 64.dp else 140.dp))
        }
    }
}

@Composable
private fun Brand(compact: Boolean) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            "AK VOD",
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
        )
        if (!compact) {
            Spacer(Modifier.width(6.dp))
            Text(
                "Unify",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NavTab(
    dest: TopDestination,
    active: Boolean,
    iconOnly: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.semantics {
            selected = active
            role = Role.Tab
        },
        shape = MaterialTheme.shapes.medium,
        color = if (focused) c.surfaceVariant else Color.Transparent,
        contentColor = if (active || focused) c.onSurface else c.onSurfaceVariant,
        border = if (focused) BorderStroke(FocusBorderWidth, FocusBorder) else null,
        interactionSource = interaction,
    ) {
        Column(
            Modifier.padding(horizontal = if (iconOnly) 10.dp else 15.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (iconOnly) {
                Icon(dest.icon, contentDescription = dest.label, modifier = Modifier.size(22.dp))
            } else {
                Text(
                    dest.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(4.dp))
            // Un singur indicator: doar pe fila activă (focusul are conturul lui).
            Box(
                Modifier
                    .width(if (iconOnly) 18.dp else 32.dp)
                    .height(3.dp)
                    .background(if (active) c.primary else Color.Transparent, MaterialTheme.shapes.extraSmall),
            )
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
