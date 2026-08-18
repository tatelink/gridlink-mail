#!/usr/bin/env python3
"""Seed a disposable Gridlink mailbox with realistic mail, contacts and calendar entries.

The point of this tool is that testing the app should never require Tate's real
mailbox. Everything it writes is synthetic, every address it uses is on a reserved or
project-owned domain, and the whole account can be wiped and re-seeded in about a minute.

    python seed.py --user avery@gridlink.me --password '...' --wipe

Nothing is hardcoded: host, account, volumes and the reference date are all flags, so the
same script seeds a second account or a different server without editing.

Re-running without --wipe adds a second copy of everything. That is deliberate: --wipe is
destructive and should have to be typed.
"""

from __future__ import annotations

import argparse
import getpass
import hashlib
import os
import random
import sys
import urllib.parse
from datetime import datetime, timedelta, timezone

import assets
import content
import dav as davmod
import events as eventdata
import mailer
import people

OWNER_DEFAULT = "avery@gridlink.me"
FOLDERS = ("INBOX", "Archive", "Sent Items", "Junk Mail", "Drafts")


# --------------------------------------------------------------------------------------
# Address resolution
# --------------------------------------------------------------------------------------


def resolver(owner_address: str) -> dict[str, tuple[str, str]]:
    table = {
        handle: (people.display(contact), people.primary_email(contact))
        for handle, contact in people.by_handle().items()
    }
    table["me"] = (people.OWNER_NAME, owner_address)
    for key, value in people.ROBOTS.items():
        table[key] = value
    return table


def slug(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]


def domain_of(address: str) -> str:
    return address.split("@", 1)[1]


# --------------------------------------------------------------------------------------
# Message generation
# --------------------------------------------------------------------------------------


def generate_messages(now: datetime, owner: tuple[str, str], rng: random.Random) -> list[mailer.Msg]:
    who = resolver(owner[1])
    out: list[mailer.Msg] = []

    # --- threads --------------------------------------------------------------------
    for thread in content.THREADS:
        base = now - timedelta(days=thread["days_ago"])
        chain: list[str] = []
        elapsed = 0
        partner = thread["with"]
        cc_handles = thread.get("cc", [])
        unread_tail = thread["folder"] == "INBOX" and rng.random() < 0.45
        turns = thread["turns"]

        for index, (speaker, gap, body, attachment) in enumerate(turns):
            elapsed += gap
            when = base + timedelta(minutes=elapsed)
            sender = who[speaker] if speaker != "me" else owner
            message_id = f"<{slug(thread['subject'])}-{index}@{domain_of(sender[1])}>"

            if speaker == "me":
                recipients = [who[partner]]
                copies = [who[handle] for handle in cc_handles]
                folder = "Sent Items"
                flags = [r"\Seen"]
            else:
                recipients = [owner]
                copies = [who[handle] for handle in cc_handles if handle != speaker]
                folder = thread["folder"]
                last_incoming = index == len(turns) - 1
                flags = [] if (unread_tail and last_incoming) else [r"\Seen"]
                # Anything Avery answered is marked answered, which is what a real
                # client would have set when the reply was sent.
                if any(turn[0] == "me" for turn in turns[index + 1 :]):
                    flags.append(r"\Answered")

            subject = thread["subject"] if index == 0 else f"Re: {thread['subject']}"
            raw = mailer.build(
                sender=sender,
                to=recipients,
                cc=copies or None,
                subject=subject,
                body=body,
                when=when,
                message_id=message_id,
                in_reply_to=chain[-1] if chain else None,
                references=chain or None,
                attachments=[attachment] if attachment else None,
            )
            out.append(mailer.Msg(folder, flags, when, raw, subject))
            chain.append(message_id)

    # --- one-off human mail ---------------------------------------------------------
    for single in content.SINGLES:
        sender = who[single["from"]]
        when = now - timedelta(days=single["days_ago"], minutes=rng.randint(0, 900))
        flags = [] if (single["folder"] == "INBOX" and rng.random() < 0.4) else [r"\Seen"]
        raw = mailer.build(
            sender=sender,
            to=[owner],
            subject=single["subject"],
            body=single["body"],
            when=when,
            message_id=f"<{slug(single['subject'])}@{domain_of(sender[1])}>",
        )
        out.append(mailer.Msg(single["folder"], flags, when, raw, single["subject"]))

    # --- newsletters ----------------------------------------------------------------
    for letter in content.NEWSLETTERS:
        sender = who[letter["robot"]]
        when = now - timedelta(days=letter["days_ago"], minutes=rng.randint(60, 800))
        flags = [] if letter["days_ago"] <= 3 and rng.random() < 0.7 else [r"\Seen"]
        raw = mailer.build(
            sender=sender,
            to=[owner],
            subject=letter["subject"],
            body=mailer.newsletter_text(letter["headline"], letter["items"]),
            html=mailer.newsletter_html(letter["headline"], letter["items"], sender[0]),
            when=when,
            message_id=f"<{slug(letter['subject'])}@{domain_of(sender[1])}>",
            headers={
                "List-Id": f"{sender[0]} <list.{domain_of(sender[1])}>",
                "List-Unsubscribe": "<https://example.invalid/unsubscribe>",
                "Precedence": "bulk",
            },
        )
        out.append(mailer.Msg("INBOX" if letter["days_ago"] < 10 else "Archive", flags, when, raw,
                              letter["subject"]))

    # --- machine notices ------------------------------------------------------------
    for robot, subject, body, days_ago, folder in content.NOTICES:
        sender = who[robot]
        when = now - timedelta(days=days_ago, minutes=rng.randint(0, 1200))
        flags = [] if (folder == "INBOX" and days_ago <= 5 and rng.random() < 0.55) else [r"\Seen"]
        raw = mailer.build(
            sender=sender,
            to=[owner],
            subject=subject,
            body=body,
            when=when,
            message_id=f"<{slug(subject + str(days_ago))}@{domain_of(sender[1])}>",
            headers={"Auto-Submitted": "auto-generated", "Precedence": "bulk"},
        )
        out.append(mailer.Msg(folder, flags, when, raw, subject))

    # --- junk -----------------------------------------------------------------------
    for spammer_index, subject, body, days_ago in content.JUNK:
        sender = people.SPAMMERS[spammer_index]
        when = now - timedelta(days=days_ago, minutes=rng.randint(0, 1400))
        raw = mailer.build(
            sender=sender,
            to=[owner],
            subject=subject,
            body=body,
            when=when,
            message_id=f"<{slug(subject)}@{domain_of(sender[1])}>",
        )
        out.append(mailer.Msg("Junk Mail", [], when, raw, subject))

    # --- drafts ---------------------------------------------------------------------
    for draft in content.DRAFTS:
        when = now - timedelta(days=draft["days_ago"], minutes=rng.randint(0, 700))
        raw = mailer.build(
            sender=owner,
            to=[who[handle] for handle in draft["to"]],
            subject=draft["subject"],
            body=draft["body"],
            when=when,
            message_id=f"<{slug(draft['subject'] + 'draft')}@gridlink.me>",
        )
        out.append(mailer.Msg("Drafts", [r"\Draft", r"\Seen"], when, raw, draft["subject"]))

    # --- iTIP: invitations that match real calendar entries -------------------------
    out += generate_itip(now, owner, who, rng)

    return out


def generate_itip(
    now: datetime, owner: tuple[str, str], who: dict, rng: random.Random
) -> list[mailer.Msg]:
    """Invitation mail built from the same rows as the calendar, so the two agree."""
    today = now.date()
    out: list[mailer.Msg] = []
    for spec in eventdata.EVENTS:
        kind = spec.get("itip")
        if not kind:
            continue
        organizer = who[spec["organizer"]]
        method = "CANCEL" if kind == "cancel" else "REQUEST"
        ics = davmod.ical(spec, today, now, owner[1], people.OWNER_NAME, who, method=method)

        if kind == "request":
            subject = f"Invitation: {spec['summary']}"
            body = (
                f"{organizer[0]} has invited you to {spec['summary']}.\n\n"
                f"When: {_readable(spec, today)}\n"
                f"Where: {spec.get('location', 'not specified')}\n\n"
                "This invitation was sent by the calendar server. Accepting it will add the "
                "event to your calendar."
            )
        elif kind == "reschedule":
            subject = f"Updated invitation: {spec['summary']}"
            body = (
                f"{organizer[0]} has changed the time of {spec['summary']}.\n\n"
                f"Was: {spec.get('was', 'an earlier time')}\n"
                f"Now: {_readable(spec, today)}\n"
                f"Where: {spec.get('location', 'not specified')}\n\n"
                "Your acceptance carries over. Nothing further is needed unless the new time "
                "does not work."
            )
        else:
            subject = f"Cancelled: {spec['summary']}"
            body = (
                f"{organizer[0]} has cancelled {spec['summary']}, which was scheduled for "
                f"{_readable(spec, today)}.\n\nThe entry has been removed from your calendar."
            )

        when = now - timedelta(days=rng.randint(1, 4), minutes=rng.randint(0, 600))
        raw = mailer.build(
            sender=organizer,
            to=[owner],
            cc=[who[handle] for handle in spec.get("attendees", []) if handle != spec["organizer"]],
            subject=subject,
            body=body,
            when=when,
            message_id=f"<{spec['uid']}-{kind}@{domain_of(organizer[1])}>",
            calendar=(method, ics),
        )
        flags = [] if kind != "request" else [r"\Seen"]
        out.append(mailer.Msg("INBOX", flags, when, raw, subject))
    return out


def _readable(spec: dict, today) -> str:
    day = today + timedelta(days=spec["offset"])
    if spec.get("all_day"):
        return day.strftime("%A %d %B, all day")
    hour, minute = spec["start"]
    start = datetime(day.year, day.month, day.day, hour, minute, tzinfo=timezone.utc)
    end = start + timedelta(minutes=spec["minutes"])
    return start.strftime("%A %d %B, %H:%M") + end.strftime(" to %H:%M UTC")


# --------------------------------------------------------------------------------------
# Filler, used only to reach a requested volume that the hand-written corpus is short of
# --------------------------------------------------------------------------------------

FILLER_MERCHANTS = [
    ("Ridgeline Outfitters", "orders@ridgeline.example", "Order {ref} confirmed",
     "Thank you for your order. Total {amount}."),
    ("Corner Coffee Roasters", "hello@cornercoffee.example", "Your order {ref} shipped",
     "Your monthly bag is on its way. Total {amount}."),
    ("Piedmont Power", "billing@piedmontpower.example", "Your bill is ready: {amount}",
     "Billing period closed. Payment due in 21 days."),
    ("Parcelway Tracking", "tracking@parcelway.example", "Parcel {ref} delivered",
     "Delivered and signed for."),
    ("Meridian Credit Union", "alerts@meridiancu.example", "Card ending 4417: {amount}",
     "A card transaction of {amount} was authorised."),
    ("Lumen Play", "billing@lumenplay.example", "Receipt for {amount}",
     "Your subscription renewed. Receipt reference {ref}."),
    ("Northwind Status", "status@northwind.example", "Resolved: brief degradation in {ref}",
     "The issue is resolved and services are operating normally."),
    ("Halesworth County Library", "notices@halesworthlibrary.example", "Hold ready: {ref}",
     "Your hold is ready for collection and will be held for seven days."),
]


def generate_filler(
    count: int, now: datetime, owner: tuple[str, str], rng: random.Random
) -> list[mailer.Msg]:
    """Routine traffic to top the mailbox up to the requested size. Archive only."""
    out: list[mailer.Msg] = []
    for index in range(count):
        name, address, subject_template, body_template = FILLER_MERCHANTS[
            index % len(FILLER_MERCHANTS)
        ]
        ref = f"{rng.choice(['RG', 'PW', 'CC', 'LP', 'HL'])}-{rng.randint(10000, 99999)}"
        amount = f"${rng.randint(6, 240)}.{rng.randint(0, 99):02d}"
        subject = subject_template.format(ref=ref, amount=amount)
        body = body_template.format(ref=ref, amount=amount)
        when = now - timedelta(
            days=rng.randint(35, 210), minutes=rng.randint(0, 1439)
        )
        raw = mailer.build(
            sender=(name, address),
            to=[owner],
            subject=subject,
            body=body,
            when=when,
            message_id=f"<filler-{index}-{slug(subject)}@{domain_of(address)}>",
            headers={"Auto-Submitted": "auto-generated", "Precedence": "bulk"},
        )
        out.append(mailer.Msg("Archive", [r"\Seen"], when, raw, subject))
    return out


# --------------------------------------------------------------------------------------
# Contacts and calendar
# --------------------------------------------------------------------------------------


def seed_contacts(client: davmod.Dav, home: str, limit: int, now: datetime, verbose: bool) -> int:
    written = 0
    for contact in people.CONTACTS[:limit]:
        photo = assets.monogram(people.initials(contact)) if contact.get("photo") else None
        uid = slug("contact:" + contact["handle"]) + "-0000-4000-8000-" + slug(
            contact["handle"] + "x"
        )
        card = davmod.vcard(contact, uid, photo, now)
        client.put(f"{home}{contact['handle']}.vcf", card, "text/vcard; charset=utf-8")
        written += 1
        if verbose:
            print(f"  contact  {people.display(contact)}")
    return written


def seed_events(
    client: davmod.Dav, home: str, limit: int, now: datetime, owner: tuple[str, str], verbose: bool
) -> int:
    who = resolver(owner[1])
    today = now.date()
    written = 0
    for spec in eventdata.EVENTS[:limit]:
        ics = davmod.ical(spec, today, now, owner[1], people.OWNER_NAME, who)
        client.put(f"{home}{spec['uid']}.ics", ics, "text/calendar; charset=utf-8")
        written += 1
        if verbose:
            print(f"  event    {spec['summary']}")
    return written


def wipe_collection(client: davmod.Dav, home: str, label: str) -> int:
    removed = 0
    for href in client.hrefs(home):
        if client.delete(href) in (200, 204):
            removed += 1
    print(f"  wiped {removed} {label}")
    return removed


# --------------------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Seed a disposable Gridlink mailbox with synthetic mail, contacts and events."
    )
    parser.add_argument("--user", default=OWNER_DEFAULT, help="full mailbox address")
    parser.add_argument(
        "--password",
        default=os.environ.get("GRIDLINK_TEST_PASSWORD"),
        help="mailbox password (or set GRIDLINK_TEST_PASSWORD, or be prompted)",
    )
    parser.add_argument("--imap-host", default="192.168.50.48")
    parser.add_argument("--imap-port", type=int, default=993)
    parser.add_argument(
        "--dav-base",
        default="https://next.gridlink.me",
        help="base URL for CalDAV and CardDAV. Must be a name the server has a certificate "
        "for: the reverse proxy in front of Stalwart selects on SNI and a bare IP fails.",
    )
    parser.add_argument("--mail", type=int, default=200, help="target message count")
    parser.add_argument("--contacts", type=int, default=40)
    parser.add_argument("--events", type=int, default=30)
    parser.add_argument("--seed", type=int, default=20260818, help="RNG seed, for repeatability")
    parser.add_argument(
        "--now",
        default=None,
        help="reference instant as ISO 8601, default is the current time. Everything is "
        "positioned relative to this, so a fixed value gives a byte-stable mailbox.",
    )
    parser.add_argument("--wipe", action="store_true", help="delete existing content first")
    parser.add_argument("--insecure", action="store_true", default=True,
                        help="do not verify TLS certificates (default on: this is a LAN server)")
    parser.add_argument("--verify-tls", dest="insecure", action="store_false")
    parser.add_argument("--skip-mail", action="store_true")
    parser.add_argument("--skip-contacts", action="store_true")
    parser.add_argument("--skip-events", action="store_true")
    parser.add_argument("--dry-run", action="store_true", help="build everything, write nothing")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv)

    password = args.password or getpass.getpass(f"Password for {args.user}: ")
    now = (
        datetime.fromisoformat(args.now).astimezone(timezone.utc)
        if args.now
        else datetime.now(timezone.utc)
    )
    rng = random.Random(args.seed)
    owner = (people.OWNER_NAME, args.user)

    print(f"Seeding {args.user}")
    print(f"  reference time  {now.isoformat()}")
    print(f"  target          {args.mail} mail, {args.contacts} contacts, {args.events} events")

    messages = generate_messages(now, owner, rng)
    hand_written = len(messages)
    if len(messages) < args.mail:
        messages += generate_filler(args.mail - len(messages), now, owner, rng)
    elif len(messages) > args.mail:
        messages.sort(key=lambda m: m.when, reverse=True)
        messages = messages[: args.mail]
    messages.sort(key=lambda m: m.when)

    tally: dict[str, int] = {}
    unread = 0
    for message in messages:
        tally[message.folder] = tally.get(message.folder, 0) + 1
        if r"\Seen" not in message.flags:
            unread += 1
    print(f"  built           {len(messages)} messages ({hand_written} hand written, "
          f"{len(messages) - hand_written} filler), {unread} unread")
    for folder in FOLDERS:
        print(f"    {folder:<12} {tally.get(folder, 0)}")

    if args.dry_run:
        print("dry run, nothing written")
        return 0

    # --- mail -----------------------------------------------------------------------
    if not args.skip_mail:
        box = mailer.Mailbox(args.imap_host, args.imap_port, args.user, password, args.insecure)
        try:
            for folder in FOLDERS:
                box.ensure(folder)
            if args.wipe:
                for folder in FOLDERS:
                    removed = box.empty(folder)
                    if removed:
                        print(f"  wiped {removed} from {folder}")
            for index, message in enumerate(messages, start=1):
                box.append(message)
                if args.verbose:
                    print(f"  [{index}/{len(messages)}] {message.folder}: {message.subject}")
                elif index % 25 == 0:
                    print(f"  appended {index}/{len(messages)}")
            print("  mail written")
            for folder in FOLDERS:
                print(f"    {folder:<12} now holds {box.count(folder)}")
        finally:
            box.close()

    # --- contacts and calendar ------------------------------------------------------
    if not (args.skip_contacts and args.skip_events):
        client = davmod.Dav(args.dav_base, args.user, password, args.insecure)
        quoted = urllib.parse.quote(args.user, safe="")
        card_home = f"/dav/card/{quoted}/default/"
        cal_home = f"/dav/cal/{quoted}/default/"

        if not args.skip_contacts:
            if args.wipe:
                wipe_collection(client, card_home, "contacts")
            written = seed_contacts(client, card_home, args.contacts, now, args.verbose)
            print(f"  contacts written: {written}")

        if not args.skip_events:
            if args.wipe:
                wipe_collection(client, cal_home, "events")
            written = seed_events(client, cal_home, args.events, now, owner, args.verbose)
            print(f"  events written: {written}")

    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
