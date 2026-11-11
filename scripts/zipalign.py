#!/usr/bin/env python3
"""Termux için minimal zipalign alternatifi.

- resources.arsc dosyasını sıkıştırılmamış (stored) hale getirir,
- sıkıştırılmamış tüm girdileri 4 bayt sınıra hizalar.

Android 11+ üzerinde targetSdk 30+ olan uygulamalar için resources.arsc
sıkıştırılmamış ve hizalı olmalıdır; Termux depolarında zipalign aracı
bulunmadığından bu betik derleme sürecinde onun yerine geçer.

Kullanım: python3 scripts/zipalign.py <girdi.apk> <çıktı.apk>
"""
import struct
import sys
import zlib

EOCD_SIG = b"PK\x05\x06"
CDH_SIG = b"PK\x01\x02"
LFH_SIG = b"PK\x03\x04"


def parse_central_directory(data: bytes):
    eocd = data.rfind(EOCD_SIG)
    if eocd < 0:
        raise ValueError("EOCD bulunamadı")
    cd_size, cd_offset = struct.unpack_from("<II", data, eocd + 12)

    entries = []
    pos = cd_offset
    while pos < cd_offset + cd_size:
        if data[pos : pos + 4] != CDH_SIG:
            raise ValueError("merkezi dizin bozuk")
        (
            version_made, version_needed, flags, method, mtime, mdate,
            crc, csize, usize, name_len, extra_len, comment_len,
            disk_start, internal_attr, external_attr, local_offset,
        ) = struct.unpack_from("<HHHHHHIIIHHHHHII", data, pos + 4)
        name = data[pos + 46 : pos + 46 + name_len]
        entries.append(
            {
                "version_made": version_made,
                "flags": flags & ~0x8,  # veri tanımlayıcı bitini temizle
                "method": method,
                "mtime": mtime,
                "mdate": mdate,
                "crc": crc,
                "csize": csize,
                "usize": usize,
                "name": name,
                "disk_start": disk_start,
                "internal_attr": internal_attr,
                "external_attr": external_attr,
                "local_offset": local_offset,
            }
        )
        pos += 46 + name_len + extra_len + comment_len
    return entries


def read_local_data(data: bytes, entry: dict) -> bytes:
    offset = entry["local_offset"]
    if data[offset : offset + 4] != LFH_SIG:
        raise ValueError(f"yerel başlık bozuk: {entry['name']!r}")
    name_len, extra_len = struct.unpack_from("<HH", data, offset + 26)
    data_start = offset + 30 + name_len + extra_len
    return data[data_start : data_start + entry["csize"]]


def align(src: str, dst: str) -> None:
    with open(src, "rb") as handle:
        data = handle.read()

    entries = parse_central_directory(data)
    out = bytearray()
    offsets = {}

    for entry in entries:
        payload = read_local_data(data, entry)
        method = entry["method"]
        if entry["name"] == b"resources.arsc" and method == 8:
            # Android 11+ (targetSdk 30+) için arsc sıkıştırılmamış olmalı.
            payload = zlib.decompress(payload, -15)
            method = 0
            entry["crc"] = zlib.crc32(payload) & 0xFFFFFFFF
            entry["csize"] = len(payload)
            entry["usize"] = len(payload)
        entry["payload"] = payload
        entry["method"] = method

        if method == 0:  # stored -> 4 bayta hizala
            while len(out) % 4:
                out += b"\x00"
        offsets[entry["name"]] = len(out)
        out += LFH_SIG
        out += struct.pack(
            "<HHHHHIIIHH",
            20, entry["flags"], method, entry["mtime"], entry["mdate"],
            entry["crc"], len(payload), entry["usize"], len(entry["name"]), 0,
        )
        out += entry["name"] + payload

    cd_start = len(out)
    for entry in entries:
        out += CDH_SIG
        out += struct.pack(
            "<HHHHHHIIIHHHHHII",
            entry["version_made"], 20, entry["flags"], entry["method"],
            entry["mtime"], entry["mdate"], entry["crc"], len(entry["payload"]),
            entry["usize"], len(entry["name"]), 0, 0,
            entry["disk_start"], entry["internal_attr"], entry["external_attr"],
            offsets[entry["name"]],
        )
        out += entry["name"]
    cd_size = len(out) - cd_start

    out += EOCD_SIG
    out += struct.pack(
        "<HHHHIIH", 0, 0, len(entries), len(entries), cd_size, cd_start, 0
    )

    with open(dst, "wb") as handle:
        handle.write(bytes(out))

    stored = [e["name"].decode() for e in entries if e["method"] == 0]
    arsc = [e for e in entries if e["name"] == b"resources.arsc"]
    print(f"  ✔ {dst}: {len(entries)} girdi, {len(stored)} tanesi hizalandı")
    if arsc:
        entry = arsc[0]
        status = "sıkıştırılmamış ✔" if entry["method"] == 0 else "SIKIŞTIRILMIŞ ✘"
        print(f"  ✔ resources.arsc: {status}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    align(sys.argv[1], sys.argv[2])
