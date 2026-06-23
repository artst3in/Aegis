package app.aether.aegis.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavController

/**
 * Process-wide handle to the app's NavController, provided once at the NavHost
 * root (MainActivity). Lets shared chrome — the [ActionCluster] — navigate
 * without every header call site threading a NavController through, which is
 * what makes it droppable into a Material TopAppBar's `actions` slot on ~40
 * screens with zero per-screen wiring. Null until provided (defensive).
 */
val LocalNavController = staticCompositionLocalOf<NavController?> { null }
