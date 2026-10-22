package com.smsforwarder.app.ui.components

/**
 * Legacy top-bar + overflow menu replaced by [AppShell] (hamburger + sidebar drawer).
 * Kept as a package marker so older imports fail loudly if reintroduced.
 */
@Deprecated("Use AppShell instead", ReplaceWith("AppShell"))
object ScreenTopBarLegacy
