package app.aether.aegis.update

import app.aether.aegis.ui.components.AegisOutlinedButton

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * GitHub PAT field for the private-repo update channel — debug
 * source-set variant (real implementation).
 *
 * The GitHub token field exists in debug builds only. Release
 * builds poll the public Aegis repo
 * which doesn't need authentication, so the field doesn't need to
 * be there. More importantly: shipping a PAT entry surface in
 * release would let a user enter a token that could leak via
 * screenshots, accessibility services, or backup exfiltration.
 * Compile-time omission is the cleaner story.
 *
 * The release stub at
 * `app/src/release/java/app/aether/aegis/update/DebugTokenField.kt`
 * renders nothing and short-circuits without even instantiating
 * the local field state, so the OutlinedTextField composable and
 * the PasswordVisualTransformation it depends on don't enter the
 * release DEX through this call site.
 *
 * Self-contained — owns its own tokenInput / tokenSaved state read
 * from [SecretsStore] so the surrounding UpdateActionPanel doesn't
 * have to thread callbacks through a release path that wouldn't
 * use them. The Save button mutates `secrets.githubToken` directly;
 * the rest of UpdateActionPanel re-reads from secrets when it
 * needs the token for the actual update poll.
 *
 * Layout: includes a leading 12.dp spacer above and a trailing
 * 12.dp spacer below so the surrounding UpdateActionPanel can call
 * us unconditionally without ending up with two adjacent spacers
 * collapsing to 24.dp of gap in release builds.
 */
@Composable
fun DebugTokenField(secrets: SecretsStore) {
    var tokenInput by remember { mutableStateOf(secrets.githubToken.orEmpty()) }
    var tokenSaved by remember { mutableStateOf(secrets.githubToken != null) }
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = tokenInput,
        onValueChange = { tokenInput = it },
        label = { Text("GitHub token (private repo)") },
        placeholder = { Text("github_pat_…") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        supportingText = {
            Text(
                if (tokenSaved) "Saved · auto-update will authenticate against the private repo."
                else "Without a token, the app can't see the private builds/ folder. " +
                    "Generate a fine-grained PAT with Contents: Read on this repo.",
                fontSize = 11.sp,
            )
        },
    )
    Spacer(modifier = Modifier.height(4.dp))
    AegisOutlinedButton(onClick = {
        secrets.githubToken = tokenInput.takeIf { it.isNotBlank() }
        tokenSaved = secrets.githubToken != null
    }) { Text(if (tokenInput.isNotBlank()) "Save token" else "Clear token") }
    Spacer(modifier = Modifier.height(12.dp))
}
