package app.sterna.ui.settings

import app.sterna.core.data.account.MailProtocol

/**
 * The "Conversations" settings (conversation view, unarchive-on-reply) act on server-side threads.
 * IMAP has none: every IMAP message is stored with a null threadId (ImapMailService), so each is a
 * one-message conversation and both switches are inert on an IMAP account (issue A6/A7). We do not
 * build an IMAP threading engine — we just state the limit — so when EVERY configured account is
 * IMAP the section is shown disabled with a note. A single JMAP account is enough to make it live
 * again, so the section stays active as long as one is present.
 *
 * Pure, so the "greyed out?" decision is unit-tested apart from the composable. An empty list (no
 * accounts yet) is not IMAP-only: there is nothing to disable.
 */
internal fun isImapOnly(protocols: List<MailProtocol>): Boolean =
    protocols.isNotEmpty() && protocols.all { it == MailProtocol.IMAP }
