package ro.ak.vodunify.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Destinațiile de top. Funcționalitățile noi (ex. Calendar din AK VOD Calendar)
 * se adaugă ca o nouă intrare aici + un ecran în propriul pachet `ui/<feature>`.
 */
enum class TopDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Acasă", Icons.Filled.Home),
    SEARCH("search", "Caută", Icons.Filled.Search),
    CALENDAR("calendar", "Calendar", Icons.Filled.DateRange),
    LIBRARY("library", "Bibliotecă", Icons.AutoMirrored.Filled.List),
    SETTINGS("settings", "Setări", Icons.Filled.Settings),
}
