package ro.ak.vodunify.ui.navigation

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import ro.ak.vodunify.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.playback.PosterBadge
import ro.ak.vodunify.ui.components.LocalPosterBadge
import ro.ak.vodunify.ui.components.LocalImdbRating
import ro.ak.vodunify.ui.components.LocalServiceLogos
import ro.ak.vodunify.ui.components.ServiceLogoLookup
import ro.ak.vodunify.ui.components.TitleLookup
import ro.ak.vodunify.ui.components.KeyLookup
import androidx.activity.compose.BackHandler
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
import androidx.hilt.navigation.compose.hiltViewModel
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
import ro.ak.vodunify.ui.discover.DiscoverScreen
import ro.ak.vodunify.ui.settings.SettingsScreen
import ro.ak.vodunify.ui.studio.StudioScreen
import ro.ak.vodunify.ui.theme.FocusFill
import ro.ak.vodunify.ui.theme.GlassActive
import ro.ak.vodunify.ui.theme.GlassBar
import ro.ak.vodunify.ui.theme.GlassIdle
import ro.ak.vodunify.ui.theme.OnFocusFill
import ro.ak.vodunify.ui.theme.PillShape
import androidx.compose.ui.graphics.Brush
import ro.ak.vodunify.ui.components.TitleMenuHost
import ro.ak.vodunify.ui.components.TitleMenuViewModel

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val isTopLevel = current == null || TopDestination.entries.any { it.route == current }

    val titleMenu: TitleMenuViewModel = hiltViewModel()
    val actions = remember(nav) {
        CatalogActions(
            titleMenu = titleMenu::open,
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

    TitleMenuHost(titleMenu, onOpen = { kind, id -> nav.navigate(Routes.title(kind, id)) })

    val badgesModel: PosterBadgesViewModel = hiltViewModel()
    val posterBadge = remember(badgesModel) {
        TitleLookup(peek = badgesModel::peekBadge, load = { kind, id -> badgesModel.badge(kind, id) })
    }
    val imdbRating = remember(badgesModel) {
        TitleLookup(peek = badgesModel::peekImdbRating, load = { kind, id -> badgesModel.imdbRating(kind, id) })
    }
    val serviceLogos = remember(badgesModel) {
        ServiceLogoLookup(
            byPackage = KeyLookup(peek = badgesModel::peekServiceLogoForPackage, load = { badgesModel.serviceLogoForPackage(it) }),
            byService = KeyLookup(peek = badgesModel::peekServiceLogo, load = { badgesModel.serviceLogo(it) }),
        )
    }

    CompositionLocalProvider(
        LocalPosterBadge provides posterBadge,
        LocalImdbRating provides imdbRating,
        LocalServiceLogos provides serviceLogos,
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
                    composable(TopDestination.DISCOVER.route) { DiscoverScreen() }
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
 * Meniul de sus: marca AK VOD în stânga, apoi o singură capsulă din „sticlă”, simetrică: Caută, filele, Setări. Fila curentă = sticlă mai deschisă, fila cu focus = alb plin cu text închis
 * (aceeași identitate ca toate butoanele). Sus din conținut intră direct în fila curentă.
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
    val textTabs = TopDestination.entries.filter { !it.iconTab }
    val pad = if (spec.isCompact) 3.dp else 4.dp
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
            .padding(horizontal = if (spec.isCompact) 8.dp else 24.dp, vertical = if (spec.isTv) 10.dp else 6.dp)
            .focusProperties { enter = { tabs[active ?: TopDestination.HOME] ?: FocusRequester.Default } }
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi")
            }
            Spacer(Modifier.width(8.dp))
        }
        if (!spec.isCompact || !showBack) {
            Brand(compact = spec.isCompact)
            Spacer(Modifier.width(if (spec.isCompact) 8.dp else 24.dp))
        }
        // O singură capsulă, simetrică: Caută la stânga, filele, Setări la dreapta.
        Box(Modifier.weight(1f), contentAlignment = if (spec.isCompact) Alignment.CenterStart else Alignment.Center) {
            Row(
                Modifier
                    .horizontalScroll(scroll)
                    .background(GlassBar, PillShape)
                    .padding(pad),
                horizontalArrangement = Arrangement.spacedBy(if (spec.isCompact) 2.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavTab(
                    dest = TopDestination.SEARCH,
                    active = active == TopDestination.SEARCH,
                    iconOnly = true,
                    modifier = Modifier.focusRequester(tabs.getValue(TopDestination.SEARCH)),
                    onClick = { onSelect(TopDestination.SEARCH) },
                )
                textTabs.forEach { dest ->
                    NavTab(
                        dest = dest,
                        active = dest == active,
                        iconOnly = spec.isCompact && dest != active,
                        modifier = Modifier.focusRequester(tabs.getValue(dest)),
                        onClick = { onSelect(dest) },
                    )
                }
                NavTab(
                    dest = TopDestination.SETTINGS,
                    active = active == TopDestination.SETTINGS,
                    iconOnly = true,
                    modifier = Modifier.focusRequester(tabs.getValue(TopDestination.SETTINGS)),
                    onClick = { onSelect(TopDestination.SETTINGS) },
                )
            }
        }
        if (!spec.isCompact) {
            // Echilibrează marca din stânga, ca bara să rămână centrată pe ecran.
            Spacer(Modifier.width(if (showBack) 24.dp else 155.dp))
        }
    }
}

/** Sigla AK VOD Unify: triunghiul din A și K într-un cerc bleumarin, apoi „AK VOD Unify”. */
@Composable
private fun Brand(compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(if (compact) 28.dp else 32.dp).clip(CircleShape).background(Color(0xFF0D2340)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_brand_mark),
                contentDescription = "AK VOD Unify",
                modifier = Modifier.size(if (compact) 16.dp else 19.dp).offset(x = 1.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "AK VOD",
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = Color.White,
        )
        if (!compact) {
            Spacer(Modifier.width(6.dp))
            Text(
                "Unify",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF00B6F9),
            )
        }
    }
}

/**
 * O filă din meniu. În repaus: doar text alb îngroșat (iconițele: cerc de sticlă); fila curentă: capsulă de
 * sticlă mai deschisă; cu focus: capsulă albă plină, text bleumarin. Fără linie sub fila curentă.
 */
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
    val spec = layoutSpec()
    Surface(
        onClick = onClick,
        modifier = modifier.semantics {
            selected = active
            role = Role.Tab
        },
        shape = PillShape,
        color = when {
            focused -> FocusFill
            active -> GlassActive
            iconOnly -> GlassIdle
            else -> Color.Transparent
        },
        contentColor = if (focused) OnFocusFill else Color.White,
        interactionSource = interaction,
    ) {
        if (iconOnly) {
            Box(Modifier.size(if (spec.isTv) 36.dp else 34.dp), contentAlignment = Alignment.Center) {
                Icon(dest.icon, contentDescription = dest.label, modifier = Modifier.size(if (spec.isTv) 20.dp else 18.dp))
            }
        } else {
            Box(
                Modifier.height(if (spec.isTv) 36.dp else 34.dp).padding(horizontal = if (spec.isTv) 18.dp else 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    dest.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (focused) FontWeight.Medium else FontWeight.Bold,
                )
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
