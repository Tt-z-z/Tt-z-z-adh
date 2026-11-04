import hashlib
import struct
import zipfile
from pathlib import Path


def field(data, pos):
    size = struct.unpack_from('<I', data, pos)[0]
    end = pos + 4 + size
    if end > len(data):
        raise ValueError('Truncated signing field')
    return data[pos + 4:end], end


def certificates(path):
    data = Path(path).read_bytes()
    with zipfile.ZipFile(path) as archive:
        offset = archive.start_dir
    if data[offset - 16:offset] != b'APK Sig Block 42':
        raise ValueError('Missing APK signing block')
    size = struct.unpack_from('<Q', data, offset - 24)[0]
    start = offset - size - 8
    if struct.unpack_from('<Q', data, start)[0] != size:
        raise ValueError('Invalid signing block size')
    pos = start + 8
    result = set()
    while pos < offset - 24:
        length = struct.unpack_from('<Q', data, pos)[0]
        ident = struct.unpack_from('<I', data, pos + 8)[0]
        value = data[pos + 12:pos + 8 + length]
        pos += 8 + length
        if ident not in (0x7109871a, 0xf05368c0):
            continue
        signers, _ = field(value, 0)
        cursor = 0
        while cursor < len(signers):
            signer, cursor = field(signers, cursor)
            signed, _ = field(signer, 0)
            _, after_digests = field(signed, 0)
            certs, _ = field(signed, after_digests)
            cert, _ = field(certs, 0)
            result.add(hashlib.sha256(cert).hexdigest())
    if not result:
        raise ValueError('No v2/v3 signer certificates')
    return result


root = Path(__file__).parent
old = certificates(root / 'installed-1.6.6.apk')
new = certificates(root / 'TurboMacro-v1.6.7-diagnostic.apk')
print('Installed signer:', sorted(old))
print('New signer:', sorted(new))
print('Same signer:', old == new)
print('Certificate extraction only; not a cryptographic APK signature verification.')
