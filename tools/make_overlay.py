"""Build and v1-sign a static resource overlay APK (no code), like the repo's own overlays.

usage: make_overlay.py <src_dir> <out.apk>
src_dir holds AndroidManifest.xml and res/. Signing key lives in work/keys (private, not in git).
"""
import base64, hashlib, subprocess, sys, tempfile, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AAPT2 = ROOT / 'work/tools/aapt2'
FW = ROOT / 'work/overlays/ref/framework-res.apk'
KEYS = ROOT / 'work/keys'

def keys():
    KEYS.mkdir(parents=True, exist_ok=True)
    k, c = KEYS / 'overlay.key', KEYS / 'overlay.crt'
    if not k.exists():
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '10950', '-sha256',
                        '-subj', '/CN=CPH1923 local overlays', '-keyout', str(k), '-out', str(c)], check=True, capture_output=True)
        k.chmod(0o600)
    return k, c

def b64(d): return base64.b64encode(hashlib.sha256(d).digest()).decode()

def main(src, out):
    src, out = Path(src), Path(out)
    with tempfile.TemporaryDirectory() as t:
        t = Path(t)
        subprocess.run([str(AAPT2), 'compile', '--dir', str(src / 'res'), '-o', str(t / 'res.zip')], check=True)
        subprocess.run([str(AAPT2), 'link', '-o', str(t / 'u.apk'), '--manifest', str(src / 'AndroidManifest.xml'),
                        '-I', str(FW), str(t / 'res.zip'), '--min-sdk-version', '28', '--target-sdk-version', '30',
                        '--no-resource-deduping'], check=True)   # keep qualified copies: they must beat the target's own variants
        files = {}
        with zipfile.ZipFile(t / 'u.apk') as z:
            for n in z.namelist(): files[n] = z.read(n)
        # v1 signature: MANIFEST.MF -> CERT.SF -> PKCS#7 (detached, SHA-256)
        mf = 'Manifest-Version: 1.0\r\nCreated-By: make_overlay\r\n\r\n'
        sections = {}
        for n, d in files.items():
            s = f'Name: {n}\r\nSHA-256-Digest: {b64(d)}\r\n\r\n'; sections[n] = s; mf += s
        sf = f'Signature-Version: 1.0\r\nCreated-By: make_overlay\r\nSHA-256-Digest-Manifest: {b64(mf.encode())}\r\n\r\n'
        for n, s in sections.items(): sf += f'Name: {n}\r\nSHA-256-Digest: {b64(s.encode())}\r\n\r\n'
        (t / 'CERT.SF').write_bytes(sf.encode())
        k, c = keys()
        subprocess.run(['openssl', 'cms', '-sign', '-binary', '-noattr', '-nosmimecap', '-md', 'sha256', '-outform', 'DER',
                        '-in', str(t / 'CERT.SF'), '-signer', str(c), '-inkey', str(k), '-out', str(t / 'CERT.RSA')], check=True)
        entries = [('AndroidManifest.xml', files['AndroidManifest.xml'])]
        entries += [(n, d) for n, d in files.items() if n != 'AndroidManifest.xml']
        entries += [('META-INF/MANIFEST.MF', mf.encode()), ('META-INF/CERT.SF', sf.encode()),
                    ('META-INF/CERT.RSA', (t / 'CERT.RSA').read_bytes())]
        # store everything uncompressed and 4-byte aligned (Android 11 needs aligned, stored resources.arsc)
        with open(out, 'wb') as fh, zipfile.ZipFile(fh, 'w', zipfile.ZIP_STORED) as z:
            for n, d in entries:
                zi = zipfile.ZipInfo(n, (1980, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_STORED
                hdr = 30 + len(n.encode())
                pad = (-(fh.tell() + hdr)) % 4
                zi.extra = b'\0' * pad
                z.writestr(zi, d)
    # self-check: alignment and that openssl verifies the signature over CERT.SF
    with zipfile.ZipFile(out) as z:
        for i in z.infolist():
            data_off = i.header_offset + 30 + len(i.filename.encode()) + len(i.extra)
            assert i.compress_type == 0 and data_off % 4 == 0, i.filename
    print(out, out.stat().st_size)

if __name__ == '__main__':
    main(*sys.argv[1:3])
