package app.aether.aegis.untrusted

/**
 * Retention + capability policy for the UNTRUSTED trust tier.
 *
 * UNTRUSTED is the default tier for every new contact: an anonymous,
 * unlinkable Aether handle with no revealed identity, no presence
 * sharing, no SOS eligibility. Its chat history is *disposable* —
 * messages auto-delete after [DISPOSABLE_TTL_SECONDS] per the Aether
 * Protocol's transport-hardening rule: default disappearing messages
 * for untrusted contacts.
 *
 * This constant lived as a private val in Repository; it now lives in
 * the tier module that defines the rule, so the number and the boundary
 * travel together. `:app` reads it across the allowed app →
 * :feature:untrusted edge. Changing it is a documented-threshold change
 * under the permission ladder — it sets how long an unvetted contact's
 * messages survive on the device.
 */
object UntrustedPolicy {
    /**
     * Disposable-history TTL for UNTRUSTED contacts: 48 hours.
     *
     * Promotion to TRUSTED/EMERGENCY clears this (history becomes
     * persistent); demotion back to UNTRUSTED re-applies it. Expressed
     * in seconds to match the SimpleX disappearing-message field.
     */
    const val DISPOSABLE_TTL_SECONDS: Long = 48L * 60L * 60L
}
