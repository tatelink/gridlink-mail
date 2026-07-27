package app.sterna.push

import app.sterna.core.data.settings.NotificationContent

/**
 * What a mail notification is allowed to put on screen, per the notification-content
 * privacy setting (Codeberg #25). Pure and resource-free — the strings are resolved by
 * the caller — so the "what does this reveal?" rule is unit-testable on its own.
 *
 * Every path that posts a mail notification goes through here: live arrivals and the
 * fallback fetch ([NewMailNotifier]) as well as a snooze waking up (Codeberg #84), which
 * used to post with the defaults and so showed sender and subject even when the user had
 * asked for neither.
 */
object MailNotificationText {

    /**
     * [title] is always shown; [text] is the collapsed line (null shows nothing);
     * [bigText] is the expanded (shade / lock-screen) line (null keeps the notification
     * unexpanded — never a body preview, Codeberg #57).
     */
    data class Reveal(val title: String, val text: String?, val bigText: String?)

    /**
     * [sender] and [subject] are the mail's own strings, already defaulted by the caller;
     * [generic] is the identity-free stand-in ("New message").
     *
     *  - SENDER_AND_SUBJECT: sender as title, subject collapsed and expanded;
     *  - SENDER_ONLY: sender as title, the generic line instead of the subject;
     *  - NONE: the generic line as title and nothing else — nothing identifying.
     */
    fun resolve(
        content: NotificationContent,
        sender: String,
        subject: String,
        generic: String,
    ): Reveal = Reveal(
        title = if (content == NotificationContent.NONE) generic else sender,
        text = when (content) {
            NotificationContent.SENDER_AND_SUBJECT -> subject
            NotificationContent.SENDER_ONLY -> generic
            NotificationContent.NONE -> null
        },
        bigText = subject.takeIf { content == NotificationContent.SENDER_AND_SUBJECT },
    )
}
