"""Minimal dependency-free PNG reader (8-bit RGB/RGBA, non-interlaced).

Chrome's `--screenshot` writes exactly that flavour, and this box has neither
Pillow nor numpy, so the twenty lines of un-filtering live here instead of in a
wheel. Returns (width, height, rowbytes) with 3 or 4 channels per pixel.
"""

import struct
import zlib


def read_png(path):
    with open(path, 'rb') as fh:
        data = fh.read()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('not a PNG: %s' % path)

    pos = 8
    width = height = depth = ctype = None
    idat = []
    while pos < len(data):
        (length,) = struct.unpack('>I', data[pos:pos + 4])
        ctag = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length  # length + tag + body + crc
        if ctag == b'IHDR':
            width, height, depth, ctype, _comp, _filt, interlace = struct.unpack('>IIBBBBB', body)
            if depth != 8 or ctype not in (2, 6) or interlace != 0:
                raise ValueError('unsupported PNG (depth=%s ctype=%s interlace=%s)'
                                 % (depth, ctype, interlace))
        elif ctag == b'IDAT':
            idat.append(body)
        elif ctag == b'IEND':
            break

    nch = 3 if ctype == 2 else 4
    raw = zlib.decompress(b''.join(idat))
    stride = width * nch
    out = bytearray(height * stride)
    prev = bytearray(stride)
    rp = 0
    for y in range(height):
        ftype = raw[rp]
        rp += 1
        line = bytearray(raw[rp:rp + stride])
        rp += stride
        if ftype == 1:  # Sub
            for i in range(nch, stride):
                line[i] = (line[i] + line[i - nch]) & 0xFF
        elif ftype == 2:  # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:  # Average
            for i in range(stride):
                left = line[i - nch] if i >= nch else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:  # Paeth
            for i in range(stride):
                a = line[i - nch] if i >= nch else 0
                b = prev[i]
                c = prev[i - nch] if i >= nch else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        elif ftype != 0:
            raise ValueError('bad filter %d' % ftype)
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return width, height, nch, bytes(out)


_LIN = [((c / 255.0) / 12.92) if (c / 255.0) <= 0.04045
        else (((c / 255.0) + 0.055) / 1.055) ** 2.4 for c in range(256)]


def luminance(r, g, b):
    """WCAG 2.1 relative luminance from 8-bit sRGB."""
    return 0.2126 * _LIN[r] + 0.7152 * _LIN[g] + 0.0722 * _LIN[b]


def contrast(l1, l2):
    hi, lo = (l1, l2) if l1 >= l2 else (l2, l1)
    return (hi + 0.05) / (lo + 0.05)
