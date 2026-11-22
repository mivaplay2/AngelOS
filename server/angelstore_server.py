#!/usr/bin/env python3
"""Angel Store API (stdlib only).

GET /api/search?q=...   Google Play search proxy (the phone can't reach play.google.com)
GET /api/apps           Angel OS app catalog (repo/index.json)
GET /apk/<file>.apk     APKs listed in the catalog
GET /icon/<pkg>.png     catalog icons
TLS cert/key come from systemd credentials (LoadCredential=cert, key).
"""
import hashlib, html, json, os, re, shutil, ssl, threading, time, urllib.parse, urllib.request
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

REPO = os.environ.get('ANGEL_REPO', '/opt/angelstore/repo')
PORT = int(os.environ.get('ANGEL_PORT', '8787'))
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36'
CACHE, CACHE_TTL, CACHE_MAX = {}, 600, 300
HITS, HIT_LIMIT = {}, 40            # requests per minute per IP
LOCK = threading.Lock()

ANCHOR = re.compile(r'/store/apps/details\?id=([\w.]+)')
ICON = re.compile(r'src="(https://play-lh\.googleusercontent\.com/[^"]+?=s\d+[^"]*)"')
LABEL = re.compile(r'\A[^>]*?aria-label="([^"]+)"')
SPAN = re.compile(r'<span[^>]*>([^<]{2,})</span>')
RATING = re.compile(r'aria-label="[^"]*?(\d[,.]\d)[^"]*?5"')

def play_search(q):
    url = 'https://play.google.com/store/search?c=apps&hl=ru&q=' + urllib.parse.quote(q)
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept-Language': 'ru-RU,ru;q=0.9'})
    s = urllib.request.urlopen(req, timeout=10).read().decode('utf-8', 'replace')
    first = {}
    for m in ANCHOR.finditer(s):
        first.setdefault(m.group(1), m.start())
    pkgs = list(first); out = []
    for i, pkg in enumerate(pkgs):
        if len(out) >= 20: break
        start = first[pkg]; end = first[pkgs[i + 1]] if i + 1 < len(pkgs) else start + 8000
        if end <= start: end = start + 8000
        seg = s[start:min(end, start + 12000)]
        a = {'pkg': pkg, 'title': None, 'subtitle': '', 'icon': None, 'rating': None}
        lm = LABEL.search(seg[:400])
        if lm and not lm.group(1).startswith('Смотреть'): a['title'] = html.unescape(lm.group(1))
        im = ICON.search(seg); after = 0
        if im: a['icon'] = re.sub(r'=s\d+', '=s128', im.group(1)); after = im.end()
        spans = []
        for sm in SPAN.finditer(seg, after):
            t = html.unescape(sm.group(1).strip())
            if t and not re.fullmatch(r'[\d,.]+', t): spans.append(t)
            if len(spans) >= 3: break
        if a['title'] is None and spans: a['title'] = spans.pop(0)
        elif spans and spans[0] == a['title']: spans.pop(0)
        if spans: a['subtitle'] = spans[0]
        rm = RATING.search(seg)
        if rm: a['rating'] = rm.group(1).replace('.', ',')
        a['title'] = a['title'] or pkg
        if a['icon']: out.append(a)
    return out

def cached_search(q):
    key = q.lower().strip()[:80]
    now = time.time()
    with LOCK:
        hit = CACHE.get(key)
        if hit and now - hit[0] < CACHE_TTL: return hit[1]
    res = play_search(key)
    with LOCK:
        if len(CACHE) >= CACHE_MAX: CACHE.pop(next(iter(CACHE)))
        CACHE[key] = (now, res)
    return res

FCACHE = os.environ.get('CACHE_DIRECTORY', '/var/cache/angelstore')
FCACHE_MAX = 2 * 1024 ** 3
FETCH_LOCKS = {}

def fdroid_search(q):
    key = 'fd:' + q.lower().strip()[:80]
    now = time.time()
    with LOCK:
        hit = CACHE.get(key)
        if hit and now - hit[0] < CACHE_TTL: return hit[1]
    url = 'https://search.f-droid.org/api/search_apps?lang=ru&q=' + urllib.parse.quote(q)
    data = json.loads(urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'AngelStore/1'}), timeout=10).read())
    out = []
    for a in data.get('apps', [])[:20]:
        m = re.search(r'/packages/([\w.]+)', a.get('url', ''))
        if m: out.append({'pkg': m.group(1), 'title': a.get('name') or m.group(1), 'subtitle': a.get('summary', ''), 'icon': a.get('icon')})
    with LOCK: CACHE[key] = (now, out)
    return out

def trim_cache():
    files = sorted((os.path.join(FCACHE, f) for f in os.listdir(FCACHE)), key=os.path.getmtime)
    total = sum(os.path.getsize(f) for f in files)
    while files and total > FCACHE_MAX:
        f = files.pop(0); total -= os.path.getsize(f); os.remove(f)

def fdroid_fetch(pkg):
    """Download the suggested APK from f-droid.org into our cache; return file name, size and SHA-256."""
    if not re.fullmatch(r'[\w.]{3,150}', pkg): raise ValueError('bad package')
    info = json.loads(urllib.request.urlopen('https://f-droid.org/api/v1/packages/' + pkg, timeout=10).read())
    vc = int(info['suggestedVersionCode'])
    name = f'{pkg}_{vc}.apk'; path = os.path.join(FCACHE, name)
    with LOCK: lk = FETCH_LOCKS.setdefault(name, threading.Lock())
    with lk:
        if not os.path.exists(path):
            tmp = path + '.part'
            with urllib.request.urlopen('https://f-droid.org/repo/' + name, timeout=60) as r, open(tmp, 'wb') as f:
                shutil.copyfileobj(r, f, 1 << 20)
            os.replace(tmp, path); trim_cache()
        h = hashlib.sha256()
        with open(path, 'rb') as f:
            for b in iter(lambda: f.read(1 << 20), b''): h.update(b)
        os.utime(path)
    vn = next((p.get('versionName') for p in info.get('packages', []) if p.get('versionCode') == vc), '')
    return {'pkg': pkg, 'file': name, 'versionCode': vc, 'versionName': vn, 'size': os.path.getsize(path), 'sha256': h.hexdigest()}

def catalog():
    with open(os.path.join(REPO, 'index.json'), encoding='utf-8') as f: return json.load(f)

class H(BaseHTTPRequestHandler):
    server_version = 'AngelStore/1'
    def log_message(self, fmt, *a): pass            # no per-request logs (privacy)

    def send(self, code, body, ctype='application/json; charset=utf-8', extra=None):
        if isinstance(body, (dict, list)): body = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', ctype); self.send_header('Content-Length', str(len(body)))
        for k, v in (extra or {}).items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(body)

    def limited(self):
        ip = self.client_address[0]; minute = int(time.time() // 60)
        with LOCK:
            m, n = HITS.get(ip, (minute, 0))
            n = n + 1 if m == minute else 1
            HITS[ip] = (minute, n)
            if len(HITS) > 5000: HITS.clear()
        return n > HIT_LIMIT

    def do_GET(self):
        if self.limited(): return self.send(429, {'error': 'slow down'})
        u = urllib.parse.urlparse(self.path); qs = urllib.parse.parse_qs(u.query)
        try:
            if u.path == '/api/search':
                q = (qs.get('q') or [''])[0]
                if not q.strip(): return self.send(400, {'error': 'q required'})
                return self.send(200, {'play': cached_search(q)})
            if u.path == '/api/fdroid':
                q = (qs.get('q') or [''])[0]
                if not q.strip(): return self.send(400, {'error': 'q required'})
                return self.send(200, {'fdroid': fdroid_search(q)})
            if u.path == '/api/fdroid/fetch':
                return self.send(200, fdroid_fetch((qs.get('pkg') or [''])[0]))
            m = re.fullmatch(r'/fdroid/([\w.]+_\d+\.apk)', u.path)
            if m:
                path = os.path.join(FCACHE, m.group(1))
                if not os.path.exists(path): return self.send(404, {'error': 'fetch first'})
                self.send_response(200); self.send_header('Content-Type', 'application/vnd.android.package-archive')
                self.send_header('Content-Length', str(os.path.getsize(path))); self.end_headers()
                with open(path, 'rb') as f: shutil.copyfileobj(f, self.wfile, 1 << 20)
                return
            if u.path == '/api/ota':
                try: cur = int((qs.get('current') or ['0'])[0])
                except ValueError: cur = 0
                with open(os.path.join(REPO, 'ota', 'ota.json'), encoding='utf-8') as f: idx = json.load(f)
                d = idx.get('deltas', {}).get(str(cur))
                if not d or d['to'] <= cur: return self.send(200, {'available': False, 'latest': idx.get('latest')})
                size = d['payload']['size'] + d['manifest']['size'] + d['frames']['size']
                return self.send(200, {'available': True, 'from': cur, 'versionCode': d['to'], 'versionName': idx.get('versionName'),
                                       'changelog': idx.get('changelog', ''), 'size': size,
                                       'payload': d['payload'], 'manifest': d['manifest'], 'frames': d['frames'], 'sig': d.get('sig')})
            m = re.fullmatch(r'/ota/((?:payload|manifest|frames)_\d+_\d+\.(?:bin|txt|tar))', u.path)
            if m:
                path = os.path.join(REPO, 'ota', m.group(1))
                if not os.path.exists(path): return self.send(404, {'error': 'not found'})
                self.send_response(200); self.send_header('Content-Type', 'application/octet-stream')
                self.send_header('Content-Length', str(os.path.getsize(path))); self.end_headers()
                with open(path, 'rb') as f: shutil.copyfileobj(f, self.wfile, 1 << 20)
                return
            if u.path == '/api/apps':
                return self.send(200, catalog())
            m = re.fullmatch(r'/(apk|icon)/([\w.\-]+\.(?:apk|png))', u.path)
            if m:
                names = {a.get('file') for a in catalog()['apps']} | {a['pkg'] + '.png' for a in catalog()['apps']}
                if m.group(2) not in names: return self.send(404, {'error': 'not found'})
                with open(os.path.join(REPO, m.group(2)), 'rb') as f: data = f.read()
                ct = 'application/vnd.android.package-archive' if m.group(1) == 'apk' else 'image/png'
                return self.send(200, data, ct, {'Cache-Control': 'max-age=300'})
            if u.path == '/': return self.send(200, {'service': 'Angel Store API', 'ok': True})
            return self.send(404, {'error': 'not found'})
        except Exception as e:
            return self.send(502, {'error': type(e).__name__})

def main():
    cred = os.environ.get('CREDENTIALS_DIRECTORY')
    srv = ThreadingHTTPServer(('0.0.0.0', PORT), H)
    if cred:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(os.path.join(cred, 'cert'), os.path.join(cred, 'key'))
        srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    srv.serve_forever()

if __name__ == '__main__':
    main()
