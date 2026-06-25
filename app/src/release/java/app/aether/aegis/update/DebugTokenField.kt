package app.aether.aegis.update

import androidx.compose.runtime.Composable

/**
 * GitHub PAT field — release source-set variant (no-op stub).
 *
 * Release builds don't show a token-entry surface — the public
 * Aegis repo doesn't need auth,
 * and shipping a PAT entry path in release would create a
 * screenshot- / accessibility-service- / backup-exfiltration risk
 * for any user who pasted one in anyway.
 *
 * Renders nothing. The OutlinedTextField composable, the
 * PasswordVisualTransformation, and the Save-button click handler
 * from the debug variant don't enter the release DEX through this
 * call site. The [SecretsStore] parameter is accepted but
 * unread — keeping the signature symmetric with the debug variant
 * means UpdateSettingsScreen can call this unconditionally with no
 * BuildConfig branch at the call site.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun DebugTokenField(secrets: SecretsStore) {
    // Intentionally empty. Release builds don't render the token field.
}
