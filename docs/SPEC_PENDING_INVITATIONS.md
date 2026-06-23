# SPEC: Pending Invitation Management

**Status:** DRAFT — Aurora, 2026-06-03
**Owner:** Chad (implementation)

## Problem

Invitation links live on the relay forever. Once generated,
there is no way to see, revoke, or expire them. A link
shared months ago can still be used by anyone who finds it.

## Solution

### Pending invitations list

A section in the Comms tab (above the contact list) showing
all unused invitation links. Each entry shows:

- Creation timestamp
- Temporary label (the "pending-XXXXX" already generated)
- "Revoke" button

### Revoke

Tapping Revoke deletes the pending connection on the
SimpleX agent (the agent connection created during
`generateInvitation`). The relay discards the pending
handshake. The link becomes dead — anyone who tries to
use it gets a connection error.

No confirmation dialog. Revoking a pending invitation
is low-risk and should be frictionless.

### Auto-expire

Pending invitations auto-expire after 24 hours (default).
Configurable: 1 hour, 6 hours, 24 hours, 7 days, never.
Setting lives in Opsec → Invitation expiry.

Expiry is a WorkManager periodic job. On each run, it
revokes any pending invitation older than the configured
period. Runs every hour (batched with other periodic
work).

### Transport

The SimpleX agent API should expose a delete/cancel for
pending connections. Investigate:
- `apiDeleteChat` with the pending connection's chat ID
- Direct agent connection deletion
- If no API exists, file upstream or track the agent
  connection ID and delete at the agent level

### Edge case: link used during countdown

If the peer connects 1 second before auto-expire fires,
the connection completes normally. Auto-expire only
revokes unused links. A connected link is no longer
pending — it becomes a contact.

## Hidden on empty

If there are zero pending invitations, the section does
not appear. No empty state, no Cyan — just absent.
