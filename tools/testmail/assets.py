"""Binary fixtures generated from nothing but the standard library.

The seeder has to attach real files and real avatar images, and it has to do it on a
machine where pip install may never have been run. So everything here is hand-built:
PNGs are assembled chunk by chunk, PDFs are written as literal PDF syntax, and the
monogram letters come from a 5x7 bitmap font defined below. No third-party imports.
"""

from __future__ import annotations

import hashlib
import struct
import zlib

# 5x7 uppercase glyphs, one string per row, "1" = ink. Enough for monogram avatars,
# which is the only text that is ever drawn as pixels.
FONT: dict[str, tuple[str, ...]] = {
    "A": ("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
    "B": ("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
    "C": ("01110", "10001", "10000", "10000", "10000", "10001", "01110"),
    "D": ("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
    "E": ("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    "F": ("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
    "G": ("01110", "10001", "10000", "10111", "10001", "10001", "01111"),
    "H": ("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
    "I": ("11111", "00100", "00100", "00100", "00100", "00100", "11111"),
    "J": ("00111", "00010", "00010", "00010", "00010", "10010", "01100"),
    "K": ("10001", "10010", "10100", "11000", "10100", "10010", "10001"),
    "L": ("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
    "M": ("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
    "N": ("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
    "O": ("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
    "P": ("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
    "Q": ("01110", "10001", "10001", "10001", "10101", "10010", "01101"),
    "R": ("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
    "S": ("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
    "T": ("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
    "U": ("10001", "10001", "10001", "10001", "10001", "10001", "01110"),
    "V": ("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
    "W": ("10001", "10001", "10001", "10101", "10101", "11011", "10001"),
    "X": ("10001", "10001", "01010", "00100", "01010", "10001", "10001"),
    "Y": ("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
    "Z": ("11111", "00001", "00010", "00100", "01000", "10000", "11111"),
    "?": ("01110", "10001", "00001", "00010", "00100", "00000", "00100"),
}

# Muted, desaturated avatar backgrounds. Deliberately no purple: house style.
AVATAR_COLOURS: tuple[tuple[int, int, int], ...] = (
    (37, 78, 92),
    (61, 90, 64),
    (122, 84, 48),
    (94, 48, 52),
    (46, 62, 100),
    (88, 92, 44),
    (44, 96, 88),
    (110, 70, 84),
    (58, 72, 78),
    (100, 96, 60),
)


def png(width: int, height: int, rows: list[list[tuple[int, int, int]]]) -> bytes:
    """Encode 8-bit truecolour RGB as a PNG. rows is height lists of width RGB triples."""
    raw = b"".join(
        b"\x00" + bytes(channel for pixel in row for channel in pixel) for row in rows
    )

    def chunk(kind: bytes, payload: bytes) -> bytes:
        body = kind + payload
        return (
            struct.pack(">I", len(payload))
            + body
            + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def _stable_index(key: str, modulo: int) -> int:
    """A hash that does not move between Python runs, unlike the builtin hash()."""
    digest = hashlib.sha256(key.encode("utf-8")).digest()
    return digest[0] % modulo


def monogram(initials: str, size: int = 192) -> bytes:
    """A square avatar: flat background, initials in near-white, gentle corner vignette."""
    letters = [c for c in initials.upper() if c in FONT][:2] or ["?"]
    background = AVATAR_COLOURS[_stable_index(initials or "?", len(AVATAR_COLOURS))]
    ink = (238, 240, 242)

    # Lay the glyphs out on a virtual grid first, then blow that grid up to size.
    gap = 1
    grid_w = len(letters) * 5 + gap * (len(letters) - 1)
    grid_h = 7
    cells: dict[tuple[int, int], bool] = {}
    for index, letter in enumerate(letters):
        x0 = index * (5 + gap)
        for y, line in enumerate(FONT[letter]):
            for x, bit in enumerate(line):
                if bit == "1":
                    cells[(x0 + x, y)] = True

    scale = max(1, int(size * 0.42) // grid_h)
    text_w, text_h = grid_w * scale, grid_h * scale
    left, top = (size - text_w) // 2, (size - text_h) // 2

    rows: list[list[tuple[int, int, int]]] = []
    for y in range(size):
        row: list[tuple[int, int, int]] = []
        for x in range(size):
            inside = left <= x < left + text_w and top <= y < top + text_h
            if inside and cells.get(((x - left) // scale, (y - top) // scale)):
                row.append(ink)
                continue
            # Very slight radial darkening so the avatar is not a dead flat square.
            dx, dy = (x - size / 2) / (size / 2), (y - size / 2) / (size / 2)
            fade = 1.0 - 0.16 * min(1.0, dx * dx + dy * dy)
            row.append(
                (
                    max(0, min(255, int(background[0] * fade))),
                    max(0, min(255, int(background[1] * fade))),
                    max(0, min(255, int(background[2] * fade))),
                )
            )
        rows.append(row)
    return png(size, size, rows)


def chart_png(title: str, bars: list[int], width: int = 640, height: int = 360) -> bytes:
    """A plain bar chart, used as a believable image attachment."""
    background = (247, 247, 245)
    axis = (170, 172, 168)
    bar_colour = AVATAR_COLOURS[_stable_index(title, len(AVATAR_COLOURS))]
    peak = max(bars) if bars else 1
    margin = 40
    plot_h = height - margin * 2
    slot = (width - margin * 2) // max(1, len(bars))

    rows = [[background for _ in range(width)] for _ in range(height)]
    for x in range(margin, width - margin):
        rows[height - margin][x] = axis
    for y in range(margin, height - margin + 1):
        rows[y][margin] = axis
    for index, value in enumerate(bars):
        bar_h = int(plot_h * (value / peak)) if peak else 0
        x0 = margin + index * slot + slot // 6
        x1 = margin + (index + 1) * slot - slot // 6
        for y in range(height - margin - bar_h, height - margin):
            for x in range(x0, min(x1, width - margin)):
                rows[y][x] = bar_colour
    return png(width, height, rows)


def _pdf_escape(text: str) -> str:
    out = text.replace(chr(92), chr(92) * 2)
    out = out.replace("(", chr(92) + "(")
    return out.replace(")", chr(92) + ")")


def pdf(title: str, lines: list[str]) -> bytes:
    """A minimal but genuinely valid single-page PDF: catalog, pages, page, font, content."""
    body_lines = ["BT /F1 18 Tf 62 742 Td (" + _pdf_escape(title) + ") Tj ET"]
    y = 706
    for line in lines:
        body_lines.append(f"BT /F1 11 Tf 62 {y} Td (" + _pdf_escape(line) + ") Tj ET")
        y -= 18
    stream = "\n".join(body_lines).encode("latin-1", "replace")

    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length "
        + str(len(stream)).encode()
        + b" >>\nstream\n"
        + stream
        + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]

    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for number, payload in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{number} 0 obj\n".encode() + payload + b"\nendobj\n"
    xref_at = len(out)
    out += f"xref\n0 {len(objects) + 1}\n".encode()
    out += b"0000000000 65535 f \n"
    for offset in offsets[1:]:
        out += f"{offset:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n".encode()
    out += f"startxref\n{xref_at}\n".encode() + b"%%EOF\n"
    return bytes(out)


def csv_bytes(header: list[str], rows: list[list[str]]) -> bytes:
    body = [",".join(header)] + [",".join(cell for cell in row) for row in rows]
    return ("\r\n".join(body) + "\r\n").encode("utf-8")
