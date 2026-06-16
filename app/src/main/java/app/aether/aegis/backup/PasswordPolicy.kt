package app.aether.aegis.backup

/**
 * Strength gate for the **backup passphrase**.
 *
 * ## Length beats complexity
 *
 * The backup is encrypted with a key derived from this passphrase via
 * Argon2id (MODERATE). Against a memory-hard KDF, LENGTH is what wins —
 * not character-class theatre. A 12-character all-lowercase sentence like
 * `mymomlovesme` survives every attacker from one GPU to every GPU ever
 * built (see the bundled "Why your password is uncrackable" guide),
 * while forcing a symbol just trains users into `P@ssw0rd1` — shorter,
 * more memorable to a cracker, harder for a human.
 *
 * So the rule is deliberately ONE line: **at least [MIN_LENGTH]
 * characters.** No required uppercase, digits, or symbols. Empty and
 * whitespace-only are rejected because they are not secrets at all.
 *
 * [MIN_LENGTH] is a security parameter fixed by review, so don't
 * quietly change it.
 */
object PasswordPolicy {

    /** The only requirement: minimum length. Review fixed this
     *  at 12 — the floor at which Argon2id makes even an all-lowercase
     *  sentence uncrackable. */
    const val MIN_LENGTH = 12

    /** Outcome of [validate]. [reason] is a short, user-facing string when
     *  [ok] is false — shown live under the passphrase field — and null
     *  when [ok] is true. */
    data class Result(val ok: Boolean, val reason: String?)

    /**
     * Validate [pw]. Pure function over the char array; does not retain
     * or copy the secret. Callers own the array's lifecycle (wipe after).
     *
     * Length-only, by design — see the class KDoc. A passphrase of spaces
     * is rejected (no entropy); otherwise the single gate is [MIN_LENGTH].
     */
    fun validate(pw: CharArray): Result {
        if (pw.isEmpty()) return Result(false, "Enter a passphrase.")
        // All-whitespace is no secret at all.
        if (pw.all { it.isWhitespace() }) {
            return Result(false, "Passphrase can't be only spaces.")
        }
        if (pw.size < MIN_LENGTH) {
            return Result(
                false,
                "Use at least $MIN_LENGTH characters. A short sentence " +
                    "(like four random words) is perfect — no symbols needed.",
            )
        }
        return Result(true, null)
    }
}
