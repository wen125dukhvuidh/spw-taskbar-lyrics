"""
扫描音乐库：统计格式分布，并检测内嵌歌词的覆盖率、标签名与编码。

用途：为「插件自己解析内嵌歌词」这条路径提供实证依据，
而不是靠猜哪些容器和标签需要支持。

只读文件头，不修改任何文件。
"""

import os
import struct
import sys
from collections import Counter, defaultdict

MUSIC_ROOT = r"D:\Music"
AUDIO_EXT = {".flac", ".mp3", ".m4a", ".aac", ".ape", ".wv", ".ogg", ".opus", ".wav", ".aiff", ".aif", ".dsf", ".dff"}

LYRIC_KEYS = ("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS", "LYRIC", "UNSYNCED LYRICS", "SYNCED LYRICS")

ENCODINGS = {0: "ISO-8859-1", 1: "UTF-16(BOM)", 2: "UTF-16BE", 3: "UTF-8"}


def decode_flac_vorbis_comment(data: bytes):
    """返回 {KEY: value} 字典；FLAC 的 Vorbis Comment 规范要求 UTF-8。"""
    out = {}
    try:
        off = 0
        (vlen,) = struct.unpack_from("<I", data, off)
        off += 4 + vlen
        (count,) = struct.unpack_from("<I", data, off)
        off += 4
        for _ in range(count):
            (l,) = struct.unpack_from("<I", data, off)
            off += 4
            item = data[off:off + l]
            off += l
            if b"=" in item:
                k, v = item.split(b"=", 1)
                out[k.decode("ascii", "replace").upper()] = v.decode("utf-8", "replace")
    except Exception:
        pass
    return out


def probe_flac(path):
    """返回 (标签字典 or None, 是否有 picture 块)"""
    with open(path, "rb") as f:
        if f.read(4) != b"fLaC":
            return None, False
        tags = None
        has_picture = False
        for _ in range(64):
            hdr = f.read(4)
            if len(hdr) < 4:
                break
            last = hdr[0] & 0x80
            btype = hdr[0] & 0x7F
            length = int.from_bytes(hdr[1:4], "big")
            if btype == 4:  # VORBIS_COMMENT
                tags = decode_flac_vorbis_comment(f.read(length))
            elif btype == 6:  # PICTURE
                has_picture = True
                f.seek(length, 1)
            else:
                f.seek(length, 1)
            if last:
                break
        return tags, has_picture


def decode_id3_text(raw: bytes) -> str:
    if not raw:
        return ""
    enc = raw[0]
    body = raw[1:]
    try:
        if enc == 0:
            return body.split(b"\x00")[0].decode("latin-1", "replace")
        if enc == 1:
            return body.decode("utf-16", "replace").split("\x00")[0]
        if enc == 2:
            return body.decode("utf-16-be", "replace").split("\x00")[0]
        return body.decode("utf-8", "replace").split("\x00")[0]
    except Exception:
        return ""


def probe_id3(path):
    """返回 {frame_id: [解码文本...], '_enc': set(编码号), '_desc': set(描述)}"""
    found = defaultdict(list)
    encs = set()
    descs = set()
    with open(path, "rb") as f:
        head = f.read(10)
        if len(head) < 10 or head[:3] != b"ID3":
            return None, encs, descs
        major = head[3]
        size = (head[6] & 0x7F) << 21 | (head[7] & 0x7F) << 14 | (head[8] & 0x7F) << 7 | (head[9] & 0x7F)
        data = f.read(min(size, 4 * 1024 * 1024))

    off = 0
    while off + 10 <= len(data):
        fid = data[off:off + 4]
        if fid[:1] == b"\x00":
            break
        if major == 4:
            fsize = (data[off + 4] & 0x7F) << 21 | (data[off + 5] & 0x7F) << 14 | (data[off + 6] & 0x7F) << 7 | (data[off + 7] & 0x7F)
        else:
            fsize = struct.unpack_from(">I", data, off + 4)[0]
        body = data[off + 10:off + 10 + fsize]
        off += 10 + fsize
        if fsize <= 0:
            break
        try:
            tag = fid.decode("ascii", "replace")
        except Exception:
            break
        if tag in ("USLT", "SYLT", "TXXX"):
            if body:
                encs.add(body[0])
            if tag == "TXXX":
                # 编码(1) + 描述 + 0 + 值
                enc = body[0]
                rest = body[1:]
                if enc in (1, 2):
                    sep = rest.find(b"\x00\x00")
                    desc = rest[:sep].decode("utf-16", "replace") if sep > 0 else ""
                    val = rest[sep + 2:]
                else:
                    sep = rest.find(b"\x00")
                    desc = rest[:sep].decode("latin-1", "replace") if sep > 0 else ""
                    val = rest[sep + 1:]
                descs.add(desc[:40])
                text = decode_id3_text(bytes([enc]) + val) if val else ""
            else:
                text = decode_id3_text(body)
            found[tag].append(text)
    return found, encs, descs


def probe_mp4(path):
    """粗略查找 moov/udta/meta/ilst 下的 ©lyr"""
    with open(path, "rb") as f:
        data = f.read(8 * 1024 * 1024)
    idx = data.find(b"\xa9lyr")
    if idx < 0:
        return None
    # ©lyr 后：size(4) + 'data' + type(4) + locale(4) + payload
    tail = data[idx + 4:idx + 4 + 4096]
    d = tail.find(b"data")
    if d < 0:
        return None
    payload = tail[d + 12:]
    return payload[:4000].decode("utf-8", "replace")


def main():
    fmt = Counter()
    with_lyrics = Counter()
    total_by_fmt = Counter()
    lyric_keys = Counter()
    enc_counter = Counter()
    picture_by_fmt = Counter()
    sample = []
    scanned_for_lyrics = 0
    MAX_LYRIC_SCAN = 600

    files = []
    for root, _dirs, names in os.walk(MUSIC_ROOT):
        for n in names:
            ext = os.path.splitext(n)[1].lower()
            if ext in AUDIO_EXT:
                files.append(os.path.join(root, n))

    print(f"音乐库: {MUSIC_ROOT}")
    print(f"音频文件总数: {len(files)}")
    print()

    for p in files:
        ext = os.path.splitext(p)[1].lower()
        total_by_fmt[ext] += 1

    print("=== 格式分布 ===")
    for ext, c in total_by_fmt.most_common():
        print(f"  {ext:<8} {c:>6}  ({c * 100.0 / max(1, len(files)):.1f}%)")
    print()

    for p in files:
        ext = os.path.splitext(p)[1].lower()
        if scanned_for_lyrics >= MAX_LYRIC_SCAN:
            break
        scanned_for_lyrics += 1
        try:
            if ext == ".flac":
                tags, has_pic = probe_flac(p)
                if has_pic:
                    picture_by_fmt[ext] += 1
                if tags:
                    hits = [k for k in tags if k in LYRIC_KEYS]
                    if hits:
                        with_lyrics[ext] += 1
                        for k in hits:
                            lyric_keys[k] += 1
                        if len(sample) < 6 and hits:
                            v = tags[hits[0]]
                            sample.append((os.path.basename(p), hits, len(v), v[:60].replace("\n", "\\n")))
            elif ext == ".mp3":
                found, encs, descs = probe_id3(p)
                if found:
                    if found:
                        with_lyrics[ext] += 1
                    for k in found:
                        lyric_keys[k] += 1
                    for e in encs:
                        enc_counter[ENCODINGS.get(e, str(e))] += 1
                    if len(sample) < 12:
                        first = list(found.items())[0]
                        sample.append((os.path.basename(p), list(found.keys()), len(first[1][0]), first[1][0][:60].replace("\n", "\\n")))
            elif ext == ".m4a":
                text = probe_mp4(p)
                if text:
                    with_lyrics[ext] += 1
                    lyric_keys["\xa9lyr"] += 1
                    if len(sample) < 16:
                        sample.append((os.path.basename(p), ["\xa9lyr"], len(text), text[:60].replace("\n", "\\n")))
        except Exception as e:
            pass

    print(f"=== 内嵌歌词检测（抽样 {scanned_for_lyrics} 个文件）===")
    for ext in total_by_fmt:
        if ext in (".flac", ".mp3", ".m4a"):
            tot = min(total_by_fmt[ext], scanned_for_lyrics)
            print(f"  {ext:<8} 带歌词 {with_lyrics.get(ext, 0)} / 已扫 {total_by_fmt[ext]}")
    print()
    print("=== 命中的歌词标签名 ===")
    for k, c in lyric_keys.most_common():
        print(f"  {k:<20} {c}")
    print()
    print("=== ID3 歌词文本编码分布（MP3）===")
    for k, c in enc_counter.most_common():
        print(f"  {k:<16} {c}")
    print()
    print("=== FLAC 带内嵌封面(PICTURE 块)数 ===")
    for k, c in picture_by_fmt.most_common():
        print(f"  {k:<8} {c}")
    print()
    print("=== 样本 ===")
    for name, keys, length, preview in sample:
        print(f"  {name[:48]}")
        print(f"     标签={keys} 长度={length}")
        print(f"     开头={preview!r}")


if __name__ == "__main__":
    main()
