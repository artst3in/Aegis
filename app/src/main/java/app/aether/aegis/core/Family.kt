package app.aether.aegis.core

/** Stable identifier for a [FamilyMember] in storage and UI keys.
 *  Pubkey if known, mesh IP otherwise. */
val FamilyMember.identifier: String
    get() = publicKey.ifEmpty { meshIp }
