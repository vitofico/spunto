"""Bounded text and identities shared by the capture plugins; no network or SQL."""

import hashlib
import io
import json
import re
import struct
import wave
from html.parser import HTMLParser
from urllib.parse import quote, urlsplit


def text(value):
    return {"type": "text", "text": value}


def identity(*parts):
    return hashlib.sha256("\0".join(str(part) for part in parts).encode()).hexdigest()


def clean(value, maximum=4000):
    return "".join(
        char
        for char in value
        if char in "\n\t" or 32 <= ord(char) != 127 and not 0xD800 <= ord(char) <= 0xDFFF
    )[:maximum]


def bounded(value, maximum=12000):
    """The storage cap counts UTF-8 bytes, including four-byte characters."""
    return clean(value, maximum).encode()[:maximum].decode(errors="ignore")


def markdown_output(value):
    """Respect annotation JSON byte limits even when Markdown needs escaping."""
    value = clean(value)
    low, high = 0, len(value)
    while low < high:
        middle = (low + high + 1) // 2
        candidate = {"type": "markdown", "text": value[:middle]}
        if len(json.dumps(candidate, ensure_ascii=False).encode()) <= 3800:
            low = middle
        else:
            high = middle - 1
    return {"type": "markdown", "text": value[:low]}


def audio_duration(data, mime):
    """Read container duration without decoding audio or trusting a filename."""
    try:
        if data.startswith(b"RIFF") and data[8:12] == b"WAVE":
            with wave.open(io.BytesIO(data)) as audio:
                return audio.getnframes() / audio.getframerate()
        if data.startswith(b"OggS"):
            return _ogg_opus_duration(data)
    except wave.Error, EOFError, ZeroDivisionError, struct.error:
        return None
    return None


def _ogg_crc_entry(value):
    value <<= 24
    for _ in range(8):
        value = ((value << 1) ^ (0x04C11DB7 if value & 0x80000000 else 0)) & 0xFFFFFFFF
    return value


_OGG_CRC_TABLE = tuple(_ogg_crc_entry(value) for value in range(256))
_OGG_UNKNOWN_GRANULE = 0xFFFFFFFFFFFFFFFF


def _ogg_crc(page):
    checksum = 0
    for index, value in enumerate(page):
        if 22 <= index < 26:
            value = 0
        checksum = ((checksum << 8) & 0xFFFFFFFF) ^ _OGG_CRC_TABLE[(checksum >> 24) ^ value]
    return checksum


def _opus_tags(packet):
    if len(packet) < 16 or packet[:8] != b"OpusTags":
        return False
    vendor = struct.unpack_from("<I", packet, 8)[0]
    if vendor > len(packet) - 16:
        return False
    offset = 12 + vendor
    comments = struct.unpack_from("<I", packet, offset)[0]
    offset += 4
    if comments > (len(packet) - offset) // 4:
        return False
    for _ in range(comments):
        if offset + 4 > len(packet):
            return False
        length = struct.unpack_from("<I", packet, offset)[0]
        offset += 4
        if length > len(packet) - offset:
            return False
        offset += length
    return True


def _ogg_opus_duration(data):
    # The attachment facade enforces the configured payload cap. Lacing bounds
    # each page, and only the two header packets need bounded accumulation.
    data = memoryview(data)
    offset, sequence, serial, last, skip = 0, 0, None, 0, 0
    packet_index, packet_bytes, audio_packets = 0, 0, 0
    pending, eos = False, False
    header_packet = bytearray()
    while offset < len(data):
        if eos or offset + 27 > len(data) or data[offset : offset + 5] != b"OggS\0":
            return None
        flags = data[offset + 5]
        if flags & 0xF8 or bool(flags & 1) != pending:
            return None
        if struct.unpack_from("<I", data, offset + 18)[0] != sequence:
            return None
        stream = struct.unpack_from("<I", data, offset + 14)[0]
        if serial is None:
            if not flags & 2:
                return None
            serial = stream
        elif stream != serial or flags & 2:
            return None
        segments = data[offset + 26]
        start = offset + 27 + segments
        if start > len(data):
            return None
        lacing = data[offset + 27 : start]
        end = start + sum(lacing)
        if end > len(data):
            return None
        checksum = struct.unpack_from("<I", data, offset + 22)[0]
        if _ogg_crc(data[offset:end]) != checksum:
            return None
        used, page_audio = start, 0
        for size in lacing:
            packet_bytes += size
            if packet_bytes > 65536:
                return None
            if packet_index < 2:
                header_packet.extend(data[used : used + size])
            used += size
            pending = size == 255
            if pending:
                continue
            if packet_index == 0:
                if (
                    sequence != 0
                    or len(header_packet) != 19
                    or header_packet[:10] != b"OpusHead\x01\x01"
                    or header_packet[18] != 0
                ):
                    return None
                skip = struct.unpack_from("<H", header_packet, 10)[0]
            elif packet_index == 1:
                if not _opus_tags(header_packet):
                    return None
            else:
                if not packet_bytes:
                    return None
                audio_packets += 1
                page_audio += 1
            packet_index += 1
            packet_bytes = 0
            header_packet.clear()
        if sequence == 0 and (packet_index != 1 or pending):
            return None
        granule = struct.unpack_from("<Q", data, offset + 6)[0]
        unknown = granule == _OGG_UNKNOWN_GRANULE
        if not unknown and granule > 0x7FFFFFFFFFFFFFFF:
            return None
        if packet_index <= 2 and not unknown and granule != 0:
            return None
        if not page_audio and packet_index > 2 and pending and not unknown:
            return None
        if not unknown:
            if granule < last or granule - skip > 3600 * 48000:
                return None
            last = granule
        eos = bool(flags & 4)
        if eos and (not audio_packets or pending or unknown or granule <= skip):
            return None
        offset = end
        sequence += 1
    if eos and audio_packets and not pending and 0 < last - skip <= 3600 * 48000:
        return (last - skip) / 48000
    return None


def markdown(value):
    return re.sub(r"([\\`*_{}\[\]<>])", r"\\\1", clean(value))


def link(label, url):
    target = quote(url, safe=":/?#[]=;%&+,-._~")
    if len(target.encode()) > 2000:
        return markdown(label) + " (source URL is too long to display)"
    return "[" + markdown(label) + "](" + target + ")"


def source_url(note):
    value = note.get("source", {}).get("url")
    if not value:
        found = re.search(r"https?://[^\s<>\]\)]+", note["body"])
        value = found[0] if found else None
    if value and len(value) <= 2048:
        parsed = urlsplit(value)
        if parsed.scheme in {"http", "https"} and parsed.hostname:
            return value
    return None


class Page(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.title = []
        self.parts = []
        self.count = 0
        self.words = 0
        self.in_title = False
        self.hidden = 0

    def handle_starttag(self, tag, attrs):
        if tag in {"script", "style", "noscript", "template"}:
            self.hidden += 1
        if tag == "title":
            self.in_title = True

    def handle_endtag(self, tag):
        if tag in {"script", "style", "noscript", "template"}:
            self.hidden = max(0, self.hidden - 1)
        if tag == "title":
            self.in_title = False

    def handle_data(self, data):
        if self.hidden:
            return
        if self.in_title:
            if sum(map(len, self.title)) < 500:
                self.title.append(data[:500])
            return
        self.words += len(data.split())
        if self.count < 20_000:
            part = data[: 20_000 - self.count]
            self.parts.append(part)
            self.count += len(part) + 1


def page_details(data):
    page = Page()
    page.feed(data.decode("utf-8", errors="replace"))
    return (
        clean(" ".join(" ".join(page.title).split()), 80),
        clean(" ".join(page.parts), 20_000),
        page.words,
    )


async def current(ctx, note, attachment=None):
    live = await ctx.capture.note(note["id"], note_version=note["version"])
    if live is None:
        return False
    if attachment is not None:
        return (
            await ctx.attachments.info(
                attachment["id"], note_id=note["id"], note_version=note["version"]
            )
            is not None
        )
    return True
