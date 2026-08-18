"""A very small CalDAV and CardDAV client, plus the serialisers that feed it.

There is no dependency here beyond the standard library, which rules out the usual DAV
packages. That is fine: seeding needs exactly four verbs (PROPFIND, PUT, DELETE, and a
HEAD-ish GET) and none of the hard parts of DAV such as locking or sync-collection.

Two details that bite if they are skipped, and are therefore not skipped:
  * both formats fold long lines at 75 octets, and a base64 photo is one very long line
  * text values escape backslash, comma, semicolon and newline, in that order
"""

from __future__ import annotations

import base64
import re
import ssl
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta, timezone


class Dav:
    """One authenticated connection's worth of state. Not thread safe, does not need to be."""

    def __init__(self, base: str, user: str, password: str, insecure: bool = False) -> None:
        self.base = base.rstrip("/")
        token = base64.b64encode(f"{user}:{password}".encode("utf-8")).decode("ascii")
        self.auth = f"Basic {token}"
        self.context = ssl._create_unverified_context() if insecure else ssl.create_default_context()

    def request(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, bytes]:
        url = path if path.startswith("http") else self.base + path
        request = urllib.request.Request(url, data=body, method=method)
        request.add_header("Authorization", self.auth)
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        try:
            with urllib.request.urlopen(request, context=self.context, timeout=30) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def hrefs(self, path: str) -> list[str]:
        """Child resource hrefs of a collection, the collection itself excluded."""
        query = (
            b'<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>'
        )
        status, body = self.request(
            "PROPFIND", path, query, {"Depth": "1", "Content-Type": "application/xml"}
        )
        if status not in (200, 207):
            raise RuntimeError(f"PROPFIND {path} returned {status}: {body[:200]!r}")
        found = re.findall(rb"<[A-Za-z]+:href>([^<]+)</[A-Za-z]+:href>", body)
        out = []
        for raw in found:
            href = raw.decode("utf-8")
            if href.rstrip("/") != path.rstrip("/") and not href.endswith("/"):
                out.append(href)
        return out

    def put(self, path: str, payload: bytes, content_type: str) -> int:
        status, body = self.request(
            "PUT", path, payload, {"Content-Type": content_type}
        )
        if status not in (200, 201, 204):
            raise RuntimeError(f"PUT {path} returned {status}: {body[:300]!r}")
        return status

    def delete(self, path: str) -> int:
        status, _ = self.request("DELETE", path)
        return status


# --------------------------------------------------------------------------------------
# Serialisation shared by both formats
# --------------------------------------------------------------------------------------


def fold(line: str) -> str:
    """Fold to 75 octets per RFC 5545 / 6350, continuing with a single leading space."""
    raw = line.encode("utf-8")
    if len(raw) <= 75:
        return line
    pieces = [raw[:75]]
    rest = raw[75:]
    while rest:
        pieces.append(rest[:74])
        rest = rest[74:]
    head = pieces[0].decode("utf-8", "ignore")
    tail = [" " + piece.decode("utf-8", "ignore") for piece in pieces[1:]]
    return "\r\n".join([head] + tail)


def escape(value: str) -> str:
    out = value.replace(chr(92), chr(92) * 2)
    out = out.replace(";", chr(92) + ";").replace(",", chr(92) + ",")
    return out.replace("\r\n", chr(92) + "n").replace("\n", chr(92) + "n")


def serialise(lines: list[str]) -> bytes:
    return ("\r\n".join(fold(line) for line in lines) + "\r\n").encode("utf-8")


def stamp(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


# --------------------------------------------------------------------------------------
# vCard 4.0
# --------------------------------------------------------------------------------------


def vcard(contact: dict, uid: str, photo: bytes | None, now: datetime) -> bytes:
    first, last = contact["first"], contact["last"]
    lines = [
        "BEGIN:VCARD",
        "VERSION:4.0",
        f"UID:urn:uuid:{uid}",
        f"FN:{escape(f'{first} {last}'.strip())}",
        f"N:{escape(last)};{escape(first)};;;",
    ]
    if contact.get("nickname"):
        lines.append(f"NICKNAME:{escape(contact['nickname'])}")
    if contact.get("org"):
        lines.append(f"ORG:{escape(contact['org'])}")
    if contact.get("title"):
        lines.append(f"TITLE:{escape(contact['title'])}")

    for index, (kind, address) in enumerate(contact.get("emails", [])):
        pref = ";PREF=1" if index == 0 else ""
        lines.append(f"EMAIL;TYPE={kind}{pref}:{address}")

    for index, (kind, number) in enumerate(contact.get("phones", [])):
        pref = ";PREF=1" if index == 0 else ""
        types = "voice,cell" if kind == "cell" else f"voice,{kind}"
        lines.append(f'TEL;TYPE="{types}"{pref};VALUE=text:{number}')

    if contact.get("address"):
        kind, street, city, region, code, country = contact["address"]
        parts = ";".join(
            ["", "", escape(street), escape(city), escape(region), escape(code), escape(country)]
        )
        lines.append(f"ADR;TYPE={kind}:{parts}")

    if contact.get("birthday"):
        # Basic ISO 8601 only: RFC 6350 does not accept the hyphenated form, and a server
        # that half-parses it stores a birthday with no day in it.
        lines.append(f"BDAY:{contact['birthday']}")
    if contact.get("note"):
        lines.append(f"NOTE:{escape(contact['note'])}")
    if contact.get("categories"):
        lines.append("CATEGORIES:" + ",".join(escape(c) for c in contact["categories"]))
    if photo:
        encoded = base64.b64encode(photo).decode("ascii")
        lines.append(f"PHOTO:data:image/png;base64,{encoded}")

    lines.append(f"REV:{stamp(now)}")
    lines.append("END:VCARD")
    return serialise(lines)


# --------------------------------------------------------------------------------------
# iCalendar
# --------------------------------------------------------------------------------------

PRODID = "-//Gridlink Mail//testmail seeder//EN"


def _event_lines(
    spec: dict,
    today: date,
    now: datetime,
    owner_email: str,
    owner_name: str,
    resolve: dict[str, tuple[str, str]],
    method: str | None = None,
) -> list[str]:
    """The VEVENT body, shared by the stored .ics and by any iTIP message."""
    lines = [f"UID:{spec['uid']}@gridlink.me", f"DTSTAMP:{stamp(now)}"]

    if spec.get("all_day"):
        start = today + timedelta(days=spec["offset"])
        end = start + timedelta(days=spec.get("days", 1))
        lines.append("DTSTART;VALUE=DATE:" + start.strftime("%Y%m%d"))
        lines.append("DTEND;VALUE=DATE:" + end.strftime("%Y%m%d"))
    else:
        hour, minute = spec["start"]
        start = datetime.combine(
            today + timedelta(days=spec["offset"]),
            datetime.min.time(),
            tzinfo=timezone.utc,
        ).replace(hour=hour, minute=minute)
        end = start + timedelta(minutes=spec["minutes"])
        lines.append("DTSTART:" + stamp(start))
        lines.append("DTEND:" + stamp(end))

    lines.append(f"SUMMARY:{escape(spec['summary'])}")
    if spec.get("location"):
        lines.append(f"LOCATION:{escape(spec['location'])}")
    if spec.get("description"):
        lines.append(f"DESCRIPTION:{escape(spec['description'])}")
    if spec.get("rrule"):
        lines.append(f"RRULE:{spec['rrule']}")
    if spec.get("categories"):
        lines.append("CATEGORIES:" + ",".join(escape(c) for c in spec["categories"]))
    if spec.get("transparent"):
        lines.append("TRANSP:TRANSPARENT")
    lines.append(f"STATUS:{spec.get('status', 'CONFIRMED')}")
    lines.append(f"SEQUENCE:{spec.get('sequence', 0)}")

    if spec.get("organizer") or spec.get("attendees"):
        organizer = spec.get("organizer")
        if organizer:
            name, address = resolve[organizer]
            lines.append(f'ORGANIZER;CN="{name}":mailto:{address}')
        else:
            lines.append(f'ORGANIZER;CN="{owner_name}":mailto:{owner_email}')
        # The owner is always an attendee of anything they were invited to. Even a
        # cancelled event keeps PARTSTAT=ACCEPTED: the cancellation is carried by
        # STATUS, not by pretending the invitation was never accepted.
        lines.append(
            f'ATTENDEE;CN="{owner_name}";ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED'
            f":mailto:{owner_email}"
        )
        for handle in spec.get("attendees", []):
            name, address = resolve[handle]
            if address == owner_email:
                continue
            lines.append(
                f'ATTENDEE;CN="{name}";ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:{address}'
            )

    if spec.get("alarm") and method is None:
        lines += [
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            f"DESCRIPTION:{escape(spec['summary'])}",
            f"TRIGGER:-PT{spec['alarm']}M",
            "END:VALARM",
        ]
    return lines


def ical(
    spec: dict,
    today: date,
    now: datetime,
    owner_email: str,
    owner_name: str,
    resolve: dict[str, tuple[str, str]],
    method: str | None = None,
) -> bytes:
    head = ["BEGIN:VCALENDAR", f"PRODID:{PRODID}", "VERSION:2.0", "CALSCALE:GREGORIAN"]
    if method:
        head.append(f"METHOD:{method}")
    body = _event_lines(spec, today, now, owner_email, owner_name, resolve, method)
    return serialise(head + ["BEGIN:VEVENT"] + body + ["END:VEVENT", "END:VCALENDAR"])
