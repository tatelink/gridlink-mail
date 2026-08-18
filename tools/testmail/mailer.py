"""Turning the corpus into RFC 5322 messages, and those messages into IMAP APPENDs.

Threading is done properly rather than by subject matching: every turn gets a stable
Message-ID, replies carry In-Reply-To and a full References chain, and Avery's own turns
are appended to Sent Items with the same chain. A client that threads correctly will show
one conversation spanning two folders, which is the interesting case and the one that a
subject-only threader gets wrong.
"""

from __future__ import annotations

import imaplib
import ssl
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage
from email.utils import format_datetime, make_msgid

import assets


@dataclass
class Msg:
    """One message, ready to be appended."""

    folder: str
    flags: list[str]
    when: datetime
    raw: bytes
    subject: str = ""
    extra: dict = field(default_factory=dict)


def _attach(message: EmailMessage, spec: tuple) -> None:
    kind = spec[0]
    if kind == "pdf":
        _, name, title, lines = spec
        message.add_attachment(
            assets.pdf(title, lines), maintype="application", subtype="pdf", filename=name
        )
    elif kind == "chart":
        _, name, title, bars = spec
        message.add_attachment(
            assets.chart_png(title, bars), maintype="image", subtype="png", filename=name
        )
    elif kind == "csv":
        _, name, header, rows = spec
        message.add_attachment(
            assets.csv_bytes(header, rows), maintype="text", subtype="csv", filename=name
        )
    else:
        raise ValueError(f"unknown attachment kind {kind!r}")


def build(
    *,
    sender: tuple[str, str],
    to: list[tuple[str, str]],
    subject: str,
    body: str,
    when: datetime,
    cc: list[tuple[str, str]] | None = None,
    message_id: str | None = None,
    in_reply_to: str | None = None,
    references: list[str] | None = None,
    attachments: list[tuple] | None = None,
    html: str | None = None,
    calendar: tuple[str, bytes] | None = None,
    headers: dict[str, str] | None = None,
) -> bytes:
    """Assemble one message. `calendar` is (METHOD, ics bytes) for an iTIP part."""
    message = EmailMessage()
    message["From"] = _address(sender)
    message["To"] = ", ".join(_address(entry) for entry in to)
    if cc:
        message["Cc"] = ", ".join(_address(entry) for entry in cc)
    message["Subject"] = subject
    message["Date"] = format_datetime(when)
    message["Message-ID"] = message_id or make_msgid(domain="gridlink.me")
    if in_reply_to:
        message["In-Reply-To"] = in_reply_to
    if references:
        message["References"] = " ".join(references)
    message["MIME-Version"] = "1.0"
    for key, value in (headers or {}).items():
        message[key] = value

    message.set_content(body)
    if html:
        message.add_alternative(html, subtype="html")
    if calendar:
        method, ics = calendar
        message.add_alternative(
            ics.decode("utf-8"), subtype="calendar", params={"method": method, "component": "VEVENT"}
        )
        message.add_attachment(
            ics, maintype="text", subtype="calendar", filename="invite.ics"
        )
    for spec in attachments or []:
        _attach(message, spec)
    return message.as_bytes()


def _address(entry: tuple[str, str]) -> str:
    name, address = entry
    if not name:
        return address
    safe = name.replace('"', "")
    return f'"{safe}" <{address}>'


def newsletter_html(headline: str, items: list[tuple[str, str]], sender_name: str) -> str:
    """Table-based HTML, because that is what real bulk mail still looks like."""
    rows = "".join(
        "<tr><td style=\"padding:14px 0;border-bottom:1px solid #e4e4e0;\">"
        f"<div style=\"font:600 15px/1.3 Helvetica,Arial,sans-serif;color:#1d1f1e;\">{title}</div>"
        f"<div style=\"font:400 14px/1.55 Helvetica,Arial,sans-serif;color:#4a4d4b;"
        f"padding-top:5px;\">{text}</div></td></tr>"
        for title, text in items
    )
    return (
        "<html><body style=\"margin:0;padding:0;background:#f4f4f1;\">"
        "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">"
        "<tr><td align=\"center\" style=\"padding:26px 12px;\">"
        "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" "
        "style=\"max-width:600px;background:#ffffff;border:1px solid #e0e0dc;\">"
        "<tr><td style=\"padding:22px 26px 6px;\">"
        f"<div style=\"font:700 12px/1 Helvetica,Arial,sans-serif;letter-spacing:.13em;"
        f"text-transform:uppercase;color:#7a7d7a;\">{sender_name}</div>"
        f"<h1 style=\"font:600 22px/1.25 Helvetica,Arial,sans-serif;color:#15211f;"
        f"margin:12px 0 0;\">{headline}</h1></td></tr>"
        f"<tr><td style=\"padding:4px 26px 20px;\"><table role=\"presentation\" width=\"100%\" "
        f"cellpadding=\"0\" cellspacing=\"0\">{rows}</table></td></tr>"
        "<tr><td style=\"padding:16px 26px 24px;font:400 12px/1.5 Helvetica,Arial,sans-serif;"
        "color:#8a8d8a;\">You are receiving this because you subscribed. "
        "<a href=\"https://example.invalid/unsubscribe\" style=\"color:#8a8d8a;\">Unsubscribe</a>"
        "</td></tr></table></td></tr></table></body></html>"
    )


def newsletter_text(headline: str, items: list[tuple[str, str]]) -> str:
    parts = [headline, "=" * len(headline), ""]
    for title, text in items:
        parts += [title, "-" * len(title), text, ""]
    parts.append("Unsubscribe: https://example.invalid/unsubscribe")
    return "\n".join(parts)


class Mailbox:
    """An authenticated IMAP session with the handful of operations seeding needs."""

    def __init__(
        self, host: str, port: int, user: str, password: str, insecure: bool = False
    ) -> None:
        context = ssl._create_unverified_context() if insecure else ssl.create_default_context()
        self.imap = imaplib.IMAP4_SSL(host, port, ssl_context=context)
        self.imap.login(user, password)

    def folders(self) -> list[str]:
        status, rows = self.imap.list()
        if status != "OK":
            raise RuntimeError("LIST failed")
        names = []
        for row in rows:
            text = row.decode("utf-8", "replace")
            # ... (flags) "delim" name   with name optionally quoted
            tail = text.split(" ", 2)[-1]
            parts = tail.split(" ", 1)
            name = parts[1] if len(parts) > 1 else parts[0]
            names.append(name.strip().strip('"'))
        return names

    def ensure(self, folder: str) -> None:
        if folder in self.folders():
            return
        self.imap.create(_quote(folder))

    def append(self, message: Msg) -> None:
        stamp = imaplib.Time2Internaldate(message.when.timestamp())
        flags = "(" + " ".join(message.flags) + ")" if message.flags else "()"
        status, detail = self.imap.append(_quote(message.folder), flags, stamp, message.raw)
        if status != "OK":
            raise RuntimeError(f"APPEND to {message.folder} failed: {detail!r}")

    def empty(self, folder: str) -> int:
        status, _ = self.imap.select(_quote(folder))
        if status != "OK":
            return 0
        status, data = self.imap.search(None, "ALL")
        if status != "OK" or not data or not data[0]:
            return 0
        ids = data[0].split()
        self.imap.store(b",".join(ids), "+FLAGS", r"(\Deleted)")
        self.imap.expunge()
        return len(ids)

    def count(self, folder: str) -> int:
        status, data = self.imap.select(_quote(folder), readonly=True)
        if status != "OK":
            return 0
        return int(data[0])

    def close(self) -> None:
        try:
            self.imap.logout()
        except Exception:
            pass


def _quote(folder: str) -> str:
    return '"' + folder.replace("\\", "\\\\").replace('"', '\\"') + '"'


def spread(base: datetime, days_ago: int, minutes: int = 0, jitter: int = 0) -> datetime:
    """A timestamp `days_ago` before `base`, nudged by minutes and a deterministic jitter."""
    return base - timedelta(days=days_ago) + timedelta(minutes=minutes + jitter)


def utc(moment: datetime) -> datetime:
    return moment.astimezone(timezone.utc)
