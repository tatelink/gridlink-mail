#!/usr/bin/env python3
"""Generate the sternamail.org brochure — one static page, nine languages.

Standard library only, on purpose: this script has to run in any bare Python
container today and in two years, without a single `pip install`.

What it does, and nothing more:

  * reads the texts that already exist and are already translated
    (`fastlane/metadata/android/<locale>/{short,full}_description.txt`),
  * reads the page's own chrome strings (`site/i18n/<lang>.txt`),
  * fills `site/template.html` and writes one page per language into
    `site/_out/`,
  * copies `site/style.css`, the logo and the screenshots as they are,
  * emits `sitemap.xml`, `robots.txt`, `.well-known/security.txt`,
  * regenerates `site/nginx-snippet.conf` from the same link table as the
    page, so the redirects can never drift from the hrefs.

It never resizes, converts or draws an image, and it never writes a version
number: both age on their own. See `site/README.md`.

    python3 site/build.py            # build into site/_out/
    python3 site/build.py --check    # parse and validate only, write nothing
"""

from __future__ import annotations

import html
import json
import re
import shutil
import sys
from collections import namedtuple
from datetime import datetime, timedelta, timezone
from pathlib import Path

# --------------------------------------------------------------------------
# Configuration — everything that a human may want to change lives here.
# --------------------------------------------------------------------------

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
OUT = SITE / "_out"

SITE_URL = "https://sternamail.org"
APP_NAME = "Sterna Mail"

# The app's signing certificate. Printed as-is on the page so a direct APK
# download can be checked; it changes only if the release key changes.
CERT_SHA256 = "cf2d007f7bfa44e08e20943de4fe17fb0873707fd759d6db9cadb1f4b2aaedc4"

Locale = namedtuple("Locale", "fastlane lang path")

# `path` is the URL folder; the empty one is the site root and the x-default.
LOCALES = [
    Locale("en-US", "en", ""),
    Locale("fr-FR", "fr", "fr"),
    Locale("de", "de", "de"),
    Locale("es", "es", "es"),
    Locale("it", "it", "it"),
    Locale("nl", "nl", "nl"),
    Locale("pl", "pl", "pl"),
    Locale("pt", "pt", "pt"),
    Locale("ru", "ru", "ru"),
]

# Rule of the site: the page never names a forge. Every outgoing link goes
# through the domain, so moving the code is one nginx line, not a rebuild of
# every listing that points here. `nginx-snippet.conf` is generated from this.
REDIRECTS = {
    "/download": "https://f-droid.org/packages/app.sterna/",
    "/source": "https://codeberg.org/emon/sterna-mail",
    "/issues": "https://codeberg.org/emon/sterna-mail/issues",
    "/apk": "https://codeberg.org/emon/sterna-mail/releases/latest",
    "/obtainium": (
        "https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/"
        "%7B%22id%22%3A%22app.sterna%22%2C%22url%22%3A%22https%3A%2F%2F"
        "codeberg.org%2Femon%2Fsterna-mail%22%2C%22author%22%3A%22emon%22"
        "%2C%22name%22%3A%22Sterna%20Mail%22%7D"
    ),
    "/licence": "https://codeberg.org/emon/sterna-mail/src/branch/main/LICENSE",
    "/privacy": "https://codeberg.org/emon/sterna-mail/src/branch/main/PRIVACY.md",
}

# Names used in the template as {{u.<name>}}.
#
# `mastodon` is the one link that does NOT go through the domain, and it must
# stay that way: Mastodon proves ownership of a site by comparing the address
# written in the page with the profile's own address. A redirect through
# /mastodon would never match, and the verified tick would never appear.
URLS = {
    "home": "/",
    "mastodon": "https://masto.top/@emon",
    "download": "/download",
    "source": "/source",
    "issues": "/issues",
    "apk": "/apk",
    "obtainium": "/obtainium",
    "licence": "/licence",
    "privacy": "/privacy",
}

# Asset paths, root-relative so all nine pages share one copy.
ASSETS = {
    "css": "/style.css",
    # The logo is the site's icon too: one mark, one file, nothing to redraw.
    "logo": "/img/logo.png",
    # Social previews need an absolute URL, unlike everything else on the page.
    "logo_absolute": f"{SITE_URL}/img/logo.png",
}

# The four blocks of `full_description.txt`, in file order, and the icon each
# one gets from the template. Headings themselves come translated from the
# file — they are never written here.
FEATURE_ICONS = ["inbox", "pen", "key", "server"]

# The four guarantees, in `i18n` key order (promise_1_title, promise_1_body…).
PROMISE_ICONS = ["nogoogle", "notrack", "noremote", "reproducible"]

# Screenshots: file stem (in site/assets/screens/, else docs/screenshots/)
# and the i18n key holding its caption.
SCREENS = [
    ("inbox-light", "shot_inbox_light"),
    ("inbox-dark", "shot_inbox_dark"),
    ("message", "shot_message"),
    ("compose", "shot_compose"),
    ("settings", "shot_settings"),
]

# The hero phone follows the visitor's theme, like the app follows the phone's.
HERO_SHOT_LIGHT = "inbox-light"
HERO_SHOT_DARK = "inbox-dark"

SCREENS_SRC_WEB = SITE / "assets" / "screens"   # web-sized, committed
SCREENS_SRC_FULL = ROOT / "docs" / "screenshots"  # 1440×2560 originals
LOGO_SRC = ROOT / "docs" / "logo.png"

# --------------------------------------------------------------------------
# Tiny template engine: {{ns.key}} markers, and named blocks for repetition.
# All markup lives in template.html; this file contains no HTML.
# --------------------------------------------------------------------------

MARKER = re.compile(r"\{\{([a-z]+)\.([a-z0-9_]+)\}\}")
BLOCK = re.compile(
    r"[ \t]*<!-- @block (?P<name>[A-Za-z0-9_]+) -->\n"
    r"(?P<body>.*?)"
    r"[ \t]*<!-- @end (?P=name) -->\n",
    re.DOTALL,
)


class BuildError(Exception):
    """Anything that must stop the build with a readable message."""


def split_blocks(template: str) -> tuple[str, dict[str, str]]:
    """Pull `<!-- @block NAME -->…<!-- @end NAME -->` out of the template."""
    blocks: dict[str, str] = {}

    def take(match: re.Match) -> str:
        name = match.group("name")
        if name in blocks:
            raise BuildError(f"template: block {name} is defined twice")
        blocks[name] = match.group("body")
        return ""

    page = BLOCK.sub(take, template)
    # The template documents itself in a comment, and declares its blocks after
    # the document. Neither belongs in the page a visitor downloads.
    page = re.sub(r"<!--.*?-->\n", "", page, count=1, flags=re.DOTALL)
    end = page.find("</html>")
    if end == -1:
        raise BuildError("template: no </html> in the page")
    return page[: end + len("</html>")] + "\n", blocks


def render(template: str, where: str, **namespaces: dict) -> str:
    """Replace every {{ns.key}}. An unknown key is a build failure, not a hole."""

    def one(match: re.Match) -> str:
        ns, key = match.group(1), match.group(2)
        table = namespaces.get(ns)
        if table is None:
            raise BuildError(f"{where}: unknown namespace {{{{{ns}.{key}}}}}")
        if key not in table:
            raise BuildError(f"{where}: no value for {{{{{ns}.{key}}}}}")
        return table[key]

    out = MARKER.sub(one, template)
    if "{{" in out:
        stray = out[out.index("{{"): out.index("{{") + 40]
        raise BuildError(f"{where}: marker left unfilled near {stray!r}")
    return out


def esc(text: str) -> str:
    """Everything read from a text file is escaped once, on the way in."""
    return html.escape(text, quote=True)


# --------------------------------------------------------------------------
# Reading the sources
# --------------------------------------------------------------------------

def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError as exc:
        raise BuildError(f"missing source file: {path}") from exc


def read_strings(path: Path) -> dict[str, str]:
    """`key = value`, one per line. Blank lines and # comments are ignored."""
    table: dict[str, str] = {}
    for number, line in enumerate(read_text(path).splitlines(), start=1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise BuildError(f"{path}:{number}: expected `key = value`")
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if not key or not value:
            raise BuildError(f"{path}:{number}: empty key or value")
        if key in table:
            raise BuildError(f"{path}:{number}: duplicate key {key}")
        table[key] = value
    return table


def parse_description(text: str, where: str) -> tuple[str, list[dict]]:
    """Split a fastlane full description into its intro and its four blocks.

    The nine files share one shape — two paragraphs, then `Heading:` followed
    by `* bullet` lines, then a closing paragraph. Anything else means a
    translation drifted, and that must be loud rather than silently rendered.
    """
    intro: list[str] = []
    sections: list[dict] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("* "):
            if not sections:
                raise BuildError(f"{where}: bullet before any heading")
            sections[-1]["bullets"].append(line[2:].strip())
        elif line.rstrip("  ").endswith(":"):
            sections.append({"title": line.rstrip("  :").strip(), "bullets": []})
        elif sections:
            continue  # closing paragraph: it repeats the promises block
        else:
            intro.append(line)

    if len(sections) != len(FEATURE_ICONS):
        raise BuildError(
            f"{where}: found {len(sections)} feature blocks, expected "
            f"{len(FEATURE_ICONS)} — the translation no longer matches en-US"
        )
    empty = [s["title"] for s in sections if not s["bullets"]]
    if empty:
        raise BuildError(f"{where}: feature block without bullets: {empty}")
    if not intro:
        raise BuildError(f"{where}: no intro paragraph")
    return intro[0], sections


# --------------------------------------------------------------------------
# Building one page
# --------------------------------------------------------------------------

def screenshot_source(stem: str) -> Path:
    web = SCREENS_SRC_WEB / f"{stem}.png"
    if web.exists():
        return web
    full = SCREENS_SRC_FULL / f"{stem}.png"
    if not full.exists():
        raise BuildError(f"no screenshot for {stem} in {SCREENS_SRC_WEB} nor {SCREENS_SRC_FULL}")
    return full


def page_url(locale: Locale) -> str:
    return f"{SITE_URL}/" if not locale.path else f"{SITE_URL}/{locale.path}/"


def build_page(locale: Locale, page_tpl: str, blocks: dict[str, str],
               tables: dict[str, dict[str, str]]) -> str:
    strings = tables[locale.lang]
    meta = ROOT / "fastlane" / "metadata" / "android" / locale.fastlane
    short = read_text(meta / "short_description.txt").strip()
    intro, sections = parse_description(
        read_text(meta / "full_description.txt"), f"{locale.fastlane}/full_description.txt"
    )

    t = {key: esc(value) for key, value in strings.items()}

    # Feature blocks — translated headings and bullets, straight from fastlane.
    features = []
    for icon, section in zip(FEATURE_ICONS, sections):
        bullets = "".join(
            render(blocks["FEATURE_BULLET"], "block FEATURE_BULLET", b={"text": esc(bullet)})
            for bullet in section["bullets"]
        )
        features.append(render(blocks["FEATURE_SECTION"], "block FEATURE_SECTION", b={
            "icon": blocks[f"ICON_{icon}"].strip(),
            "title": esc(section["title"]),
            "bullets": bullets,
        }))

    promises = []
    for number, icon in enumerate(PROMISE_ICONS, start=1):
        promises.append(render(blocks["PROMISE"], "block PROMISE", b={
            "icon": blocks[f"ICON_{icon}"].strip(),
            "title": t[f"promise_{number}_title"],
            "body": t[f"promise_{number}_body"],
        }))

    screens = []
    for stem, caption_key in SCREENS:
        screens.append(render(blocks["SCREEN"], "block SCREEN", b={
            "src": f"/img/{stem}.png",
            "caption": t[caption_key],
        }))

    lang_links, hreflangs = [], []
    for other in LOCALES:
        lang_links.append(render(blocks["LANG_LINK"], "block LANG_LINK", b={
            "href": "/" if not other.path else f"/{other.path}/",
            "lang": other.lang,
            "name": esc(tables[other.lang]["language_name"]),
            "current": ' aria-current="true"' if other.lang == locale.lang else "",
        }))
        hreflangs.append(render(blocks["HREFLANG"], "block HREFLANG", b={
            "lang": other.lang,
            "href": page_url(other),
        }))
    hreflangs.append(render(blocks["HREFLANG"], "block HREFLANG", b={
        "lang": "x-default",
        "href": f"{SITE_URL}/",
    }))

    jsonld = json.dumps({
        "@context": "https://schema.org",
        "@type": "SoftwareApplication",
        "name": APP_NAME,
        "applicationCategory": "CommunicationApplication",
        "operatingSystem": "Android",
        "url": page_url(locale),
        "inLanguage": locale.lang,
        "description": short,
        "license": "https://www.gnu.org/licenses/gpl-3.0.html",
        "isAccessibleForFree": True,
        "offers": {"@type": "Offer", "price": "0", "priceCurrency": "EUR"},
        "downloadUrl": f"{SITE_URL}/download",
        "softwareHelp": f"{SITE_URL}/issues",
    }, ensure_ascii=False, indent=2).replace("<", "\\u003c")

    derived = {
        "lang": locale.lang,
        "title": f"{esc(strings['page_title'])}",
        "description": esc(short),
        "canonical": page_url(locale),
        "hreflangs": "".join(hreflangs),
        "jsonld": jsonld,
        "lede": esc(short),
        "intro": esc(intro),
        "features": "".join(features),
        "promises": "".join(promises),
        "screens": "".join(screens),
        "lang_links": "".join(lang_links),
        "hero_light": f"/img/{HERO_SHOT_LIGHT}.png",
        "hero_dark": f"/img/{HERO_SHOT_DARK}.png",
        "fingerprint": CERT_SHA256,
    }

    return render(page_tpl, f"page {locale.lang}", t=t, d=derived, u=URLS, a=ASSETS)


# --------------------------------------------------------------------------
# Side files
# --------------------------------------------------------------------------

def sitemap() -> str:
    # Each entry lists every translation of the page, so a crawler that finds one
    # language knows about the eight others without visiting them first.
    alternates = "".join(
        f'    <xhtml:link rel="alternate" hreflang="{loc.lang}" href="{page_url(loc)}"/>\n'
        for loc in LOCALES
    ) + f'    <xhtml:link rel="alternate" hreflang="x-default" href="{SITE_URL}/"/>\n'
    entries = "".join(
        f"  <url>\n    <loc>{page_url(loc)}</loc>\n{alternates}  </url>\n"
        for loc in LOCALES
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"\n'
        '        xmlns:xhtml="http://www.w3.org/1999/xhtml">\n'
        f"{entries}"
        "</urlset>\n"
    )


def robots() -> str:
    return f"User-agent: *\nAllow: /\n\nSitemap: {SITE_URL}/sitemap.xml\n"


def security_txt() -> str:
    # No mailbox exists on this domain (null MX, SPF -all): the contact is the
    # issue tracker, reached through the domain like every other outgoing link.
    # RFC 9116 wants an expiry; a year from this build is the honest one, and
    # rebuilding the site is what refreshes it.
    expires = datetime.now(timezone.utc).replace(microsecond=0) + timedelta(days=365)
    return (
        f"Contact: {SITE_URL}/issues\n"
        f"Expires: {expires.isoformat().replace('+00:00', 'Z')}\n"
        f"Preferred-Languages: en, fr\n"
        f"Canonical: {SITE_URL}/.well-known/security.txt\n"
    )


def nginx_snippet() -> str:
    width = max(len(path) for path in REDIRECTS)
    lines = [
        "# Generated by site/build.py — do not edit by hand.",
        "#",
        "# Outgoing links of sternamail.org, so that the page itself never names",
        "# a forge. Paste inside the `server { }` that serves the site, leaving",
        "# that block's own `listen` directives untouched.",
        "",
    ]
    for path, target in REDIRECTS.items():
        lines.append(f"location = {path.ljust(width)} {{ return 302 {target}; }}")
    lines.append("")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------

def check_strings() -> dict[str, dict[str, str]]:
    """Every language must carry exactly the keys of the English file."""
    reference = read_strings(SITE / "i18n" / "en.txt")
    tables = {"en": reference}
    for locale in LOCALES:
        if locale.lang == "en":
            continue
        table = read_strings(SITE / "i18n" / f"{locale.lang}.txt")
        missing = sorted(set(reference) - set(table))
        extra = sorted(set(table) - set(reference))
        if missing or extra:
            raise BuildError(
                f"i18n/{locale.lang}.txt: "
                + (f"missing {missing} " if missing else "")
                + (f"unknown {extra}" if extra else "")
            )
        tables[locale.lang] = table
    return tables


def main(argv: list[str]) -> int:
    check_only = "--check" in argv[1:]

    template = read_text(SITE / "template.html")
    page_tpl, blocks = split_blocks(template)
    tables = check_strings()

    pages = {loc: build_page(loc, page_tpl, blocks, tables) for loc in LOCALES}

    if check_only:
        print(f"ok — {len(pages)} pages parse, {len(tables['en'])} strings per language")
        return 0

    # Empty the folder rather than replace it: a preview server, or anything
    # else holding it open, would otherwise keep pointing at a directory that
    # no longer exists and serve nothing at all.
    OUT.mkdir(parents=True, exist_ok=True)
    for entry in OUT.iterdir():
        if entry.is_dir():
            shutil.rmtree(entry)
        else:
            entry.unlink()
    (OUT / "img").mkdir()
    (OUT / ".well-known").mkdir()

    for locale, page in pages.items():
        folder = OUT if not locale.path else OUT / locale.path
        folder.mkdir(exist_ok=True)
        (folder / "index.html").write_text(page, encoding="utf-8")

    shutil.copy2(SITE / "style.css", OUT / "style.css")
    shutil.copy2(LOGO_SRC, OUT / "img" / "logo.png")

    heavy = []
    for stem, _ in SCREENS:
        source = screenshot_source(stem)
        shutil.copy2(source, OUT / "img" / f"{stem}.png")
        if source.parent == SCREENS_SRC_FULL:
            heavy.append(f"{stem}.png ({source.stat().st_size // 1024} kB)")

    (OUT / "sitemap.xml").write_text(sitemap(), encoding="utf-8")
    (OUT / "robots.txt").write_text(robots(), encoding="utf-8")
    (OUT / ".well-known" / "security.txt").write_text(security_txt(), encoding="utf-8")
    (SITE / "nginx-snippet.conf").write_text(nginx_snippet(), encoding="utf-8")

    total = sum(f.stat().st_size for f in OUT.rglob("*") if f.is_file())
    print(f"built {len(pages)} pages into {OUT} ({total // 1024} kB total)")
    print(f"wrote {SITE / 'nginx-snippet.conf'} ({len(REDIRECTS)} redirects)")
    if heavy:
        print("warning: full-size screenshots copied — resize them once into "
              f"site/assets/screens/ (see site/README.md): {', '.join(heavy)}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except BuildError as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
