package app.jmail.core.data.account

import kotlinx.serialization.Serializable

/** Which mail protocol an account speaks. */
@Serializable
enum class MailProtocol { JMAP, IMAP }

/** Transport security for an IMAP/SMTP endpoint (persisted; maps to core:imap's MailSecurity). */
@Serializable
enum class ConnectionSecurity { TLS, STARTTLS, NONE }
