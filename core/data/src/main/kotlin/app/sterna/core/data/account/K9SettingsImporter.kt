package app.sterna.core.data.account

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Imports account setup from a K-9 Mail / Thunderbird-for-Android `.k9s` settings
 * export (Apache-2.0). The file is pure configuration: it carries NO passwords, NO
 * OAuth tokens, NO secrets of any kind. We map each usable IMAP account onto a
 * [StoredAccount] with the password/refresh-token slot left empty, so the account is
 * inert until the user authenticates through Sterna's own client. OAuth (XOAUTH2)
 * accounts are recognised and flagged [AuthType.OAUTH] but MUST be re-signed-in via
 * Sterna afterwards — the export contains nothing to authenticate with.
 *
 * Only IMAP accounts are supported; POP3, WebDAV and client-cert (EXTERNAL) accounts
 * are reported as skips rather than silently dropped. Parsing is best-effort and
 * defensive: unknown elements/keys are ignored, missing optionals are defaulted, and
 * a single malformed account never aborts the whole import.
 */
object K9SettingsImporter {

    /** Parse a .k9s settings export. Throws [IllegalArgumentException] if the root element is not <k9settings>. */
    fun parse(input: InputStream): K9ImportResult {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Harden against XXE / entity-expansion — a settings file needs none of it.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
        val document = factory.newDocumentBuilder().parse(input)
        val root = document.documentElement
        require(root != null && root.tagName == "k9settings") { "Not a K-9 settings file" }

        val accounts = mutableListOf<StoredAccount>()
        val skipped = mutableListOf<K9Skip>()

        val accountsNode = root.childElements().firstOrNull { it.tagName == "accounts" }
        for (accountEl in accountsNode?.childElements()?.filter { it.tagName == "account" }.orEmpty()) {
            val label = accountLabel(accountEl)
            runCatching { parseAccount(accountEl) }
                .onSuccess { outcome ->
                    when (outcome) {
                        is AccountOutcome.Imported -> accounts += outcome.account
                        is AccountOutcome.Skipped -> skipped += K9Skip(label, outcome.reason)
                    }
                }
                .onFailure { skipped += K9Skip(label, K9SkipReason.MALFORMED) }
        }
        return K9ImportResult(accounts, skipped)
    }

    private sealed interface AccountOutcome {
        data class Imported(val account: StoredAccount) : AccountOutcome
        data class Skipped(val reason: K9SkipReason) : AccountOutcome
    }

    private fun parseAccount(account: Element): AccountOutcome {
        val accountName = account.childText("name")

        val incoming = account.childElements().firstOrNull { it.tagName == "incoming-server" }
            ?: return AccountOutcome.Skipped(K9SkipReason.MALFORMED)

        when (incoming.getAttribute("type").uppercase()) {
            "IMAP" -> Unit
            "POP3" -> return AccountOutcome.Skipped(K9SkipReason.POP3)
            else -> return AccountOutcome.Skipped(K9SkipReason.UNSUPPORTED)
        }

        val authType = when (incoming.childText("authentication-type").uppercase()) {
            "XOAUTH2" -> AuthType.OAUTH
            "EXTERNAL" -> return AccountOutcome.Skipped(K9SkipReason.EXTERNAL_AUTH)
            else -> AuthType.BASIC // PLAIN, CRAM_MD5, LOGIN, AUTOMATIC, missing…
        }

        val username = incoming.childText("username")
        val imapHost = incoming.childText("host")
        val imapPort = incoming.childText("port").toIntOrNull() ?: 993
        val imapSecurity = connectionSecurity(incoming.childText("connection-security"))

        if (username.isBlank() || imapHost.isBlank()) {
            return AccountOutcome.Skipped(K9SkipReason.MALFORMED)
        }

        val outgoing = account.childElements().firstOrNull { it.tagName == "outgoing-server" }
        val smtpHost = outgoing?.childText("host").orEmpty()
        val smtpPort = outgoing?.childText("port")?.toIntOrNull() ?: 587
        val smtpSecurity = outgoing
            ?.let { connectionSecurity(it.childText("connection-security")) }
            ?: ConnectionSecurity.STARTTLS

        return AccountOutcome.Imported(
            StoredAccount(
                id = "",
                server = "",
                username = username,
                accountName = accountName,
                protocol = MailProtocol.IMAP,
                authType = authType,
                imapHost = imapHost,
                imapPort = imapPort,
                imapSecurity = imapSecurity,
                smtpHost = smtpHost,
                smtpPort = smtpPort,
                smtpSecurity = smtpSecurity,
                identities = parseIdentities(account),
            ),
        )
    }

    private fun parseIdentities(account: Element): List<StoredIdentity> {
        val identitiesNode = account.childElements().firstOrNull { it.tagName == "identities" }
            ?: return emptyList()
        return identitiesNode.childElements()
            .filter { it.tagName == "identity" }
            .mapIndexed { i, identity ->
                val settings = identity.childElements().firstOrNull { it.tagName == "settings" }
                val signature = if (settings.valueForKey("signatureUse").equals("true", ignoreCase = true)) {
                    settings.valueForKey("signature")
                } else {
                    ""
                }
                StoredIdentity(
                    id = "identity-$i",
                    name = identity.childText("name"),
                    email = identity.childText("email"),
                    signature = signature,
                )
            }
    }

    private fun connectionSecurity(raw: String): ConnectionSecurity = when (raw.uppercase()) {
        "SSL_TLS_REQUIRED" -> ConnectionSecurity.TLS
        "STARTTLS_REQUIRED" -> ConnectionSecurity.STARTTLS
        else -> ConnectionSecurity.NONE
    }

    /** Best-effort label for skip reporting: the account's <name>, else its uuid, else "(unknown)". */
    private fun accountLabel(account: Element): String {
        val name = account.childText("name")
        if (name.isNotBlank()) return name
        val uuid = account.getAttribute("uuid")
        return uuid.ifBlank { "(unknown)" }
    }

    // ---- DOM helpers ----

    private fun Element.childElements(): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) out += node as Element
        }
        return out
    }

    /** Trimmed text of the first direct child element named [tag], or "" if absent. */
    private fun Element.childText(tag: String): String =
        childElements().firstOrNull { it.tagName == tag }?.textContent?.trim().orEmpty()

    /** Trimmed text of the `<value key="...">` child with the given key, or "" if absent. */
    private fun Element?.valueForKey(key: String): String =
        this?.childElements()
            ?.firstOrNull { it.tagName == "value" && it.getAttribute("key") == key }
            ?.textContent?.trim()
            .orEmpty()
}

data class K9ImportResult(
    val accounts: List<StoredAccount>,
    val skipped: List<K9Skip>,
)

data class K9Skip(val account: String, val reason: K9SkipReason)

enum class K9SkipReason { POP3, EXTERNAL_AUTH, UNSUPPORTED, MALFORMED }
