package app.gridlink.ui.home

import app.gridlink.core.data.account.StoredAccount
import app.gridlink.ui.gridlink.GridlinkFromAccount
import app.gridlink.ui.gridlink.GridlinkFromIdentity

/**
 * The composer's From choices, built from the account store: every signed-in account with the
 * addresses it can send as, the active account FIRST because that order is the composer's default.
 *
 * [signedIn] is the drawer's own test (credentials present), for the drawer's reason: an imported
 * account with no credentials cannot send, and offering it would only produce a refusal at the tap.
 * The identities are [StoredAccount.resolvedIdentities], the same merged manual and server list the
 * Settings identity editor shows, so the picker offers exactly what the user set up there. A blank
 * identity signature falls back to the account-level one, matching what the composer always seeded.
 */
internal fun gridlinkFromChoices(
    accounts: List<StoredAccount>,
    activeId: String?,
    signedIn: (String) -> Boolean,
): List<GridlinkFromAccount> =
    accounts
        .filter { signedIn(it.id) }
        .sortedBy { if (it.id == activeId) 0 else 1 }
        .mapNotNull { account ->
            val identities = account.resolvedIdentities()
                .filter { it.email.isNotBlank() }
                .map { identity ->
                    GridlinkFromIdentity(
                        name = identity.name,
                        email = identity.email,
                        signature = identity.signature.ifBlank { account.signature },
                    )
                }
            if (identities.isEmpty()) return@mapNotNull null
            GridlinkFromAccount(
                accountId = account.id,
                label = account.label(),
                identities = identities,
                defaultEmail = account.defaultIdentity()?.email ?: identities.first().email,
            )
        }
