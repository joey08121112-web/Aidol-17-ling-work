"""Companion assets must match the packed RGB/alpha Android renderer contract."""
from pathlib import Path
import struct


RES = Path(__file__).resolve().parents[1] / 'android-app/app/src/main/res'
VIDEOS = ('companion_life_loop', 'companion_commute_loop', 'companion_rest_once')
POSTERS = ('companion_life_poster', 'companion_commute_poster',
           'companion_rest_poster', 'companion_rest_end')


def _boxes(data):
    """Read ISO BMFF boxes, including extended-size and final size-zero boxes."""
    offset = 0
    while offset < len(data):
        assert len(data) - offset >= 8, 'Truncated MP4 box header'
        size, kind = struct.unpack_from('>I4s', data, offset)
        header = 8
        if size == 1:
            assert len(data) - offset >= 16, 'Truncated extended MP4 box header'
            size = struct.unpack_from('>Q', data, offset + 8)[0]
            header = 16
        elif size == 0:
            size = len(data) - offset
        assert header <= size <= len(data) - offset, f'Invalid MP4 box {kind!r}'
        yield kind, data[offset + header:offset + size]
        offset += size


def _only_box(data, kind):
    matches = [body for name, body in _boxes(data) if name == kind]
    assert len(matches) == 1, f'Expected exactly one MP4 {kind!r} box'
    return matches[0]


def _video_tracks(data):
    handlers, dimensions = [], []
    for kind, track in _boxes(_only_box(data, b'moov')):
        if kind != b'trak':
            continue
        media = _only_box(track, b'mdia')
        handler = _only_box(media, b'hdlr')
        assert len(handler) >= 12, 'Truncated MP4 handler'
        handlers.append(bytes(handler[8:12]))
        if handlers[-1] != b'vide':
            continue
        table = _only_box(_only_box(media, b'minf'), b'stbl')
        descriptions = _only_box(table, b'stsd')
        assert len(descriptions) >= 8, 'Truncated MP4 sample descriptions'
        entries = list(_boxes(descriptions[8:]))
        assert len(entries) == struct.unpack_from('>I', descriptions, 4)[0]
        for codec, entry in entries:
            assert codec == b'avc1', f'Expected H.264 avc1, got {codec!r}'
            assert len(entry) >= 28, 'Truncated MP4 visual sample entry'
            dimensions.append(struct.unpack_from('>HH', entry, 24))
    return handlers, dimensions


def _webp_info(data):
    assert data[:4] == b'RIFF' and data[8:12] == b'WEBP', 'Invalid WebP header'
    assert len(data) == struct.unpack_from('<I', data, 4)[0] + 8
    offset = 12
    while offset < len(data):
        assert len(data) - offset >= 8, 'Truncated WebP chunk header'
        kind, size = struct.unpack_from('<4sI', data, offset)
        start, end = offset + 8, offset + 8 + size
        assert end <= len(data), 'Truncated WebP chunk'
        chunk = data[start:end]
        if kind == b'VP8X':
            assert len(chunk) == 10, 'Invalid extended WebP header'
            width = 1 + int.from_bytes(chunk[4:7], 'little')
            height = 1 + int.from_bytes(chunk[7:10], 'little')
            return width, height, bool(chunk[0] & 0x10)
        if kind == b'VP8L':
            assert len(chunk) >= 5 and chunk[0] == 0x2f, 'Invalid lossless WebP header'
            bits = int.from_bytes(chunk[1:5], 'little')
            assert bits >> 29 == 0, 'Unsupported lossless WebP version'
            return 1 + (bits & 0x3fff), 1 + ((bits >> 14) & 0x3fff), bool(bits & (1 << 28))
        offset = end + (size & 1)
    raise AssertionError('WebP must contain an alpha-capable VP8X or VP8L header')


def test_companion_videos_are_packed_rgb_alpha_without_audio():
    for name in VIDEOS:
        path = RES / 'raw' / f'{name}.mp4'
        assert path.is_file(), f'Missing companion video: {path.name}'
        handlers, dimensions = _video_tracks(memoryview(path.read_bytes()))
        assert b'soun' not in handlers, f'{path.name} must not contain an audio track'
        assert handlers.count(b'vide') == 1, f'{path.name} must contain one video track'
        assert dimensions == [(960, 480)], f'{path.name}: expected packed RGB/alpha 960x480, got {dimensions}'


def test_companion_posters_are_square_with_alpha():
    for name in POSTERS:
        path = RES / 'drawable-nodpi' / f'{name}.webp'
        assert path.is_file(), f'Missing companion poster: {path.name}'
        width, height, alpha = _webp_info(path.read_bytes())
        assert (width, height) == (480, 480), f'{path.name}: expected 480x480, got {width}x{height}'
        assert alpha, f'{path.name} must contain an alpha channel'
