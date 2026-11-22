package os.angel.store;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Search in RuStore (public JSON API) and Google Play (public search page). */
final class Stores {
    static final String UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    static final class App {
        String pkg, title, subtitle, icon, rating, query, file, sha256;
        boolean card, angel; int versionCode; long size; String[] urls;
        int playRank = -1, ruRank = -1, fdRank = -1;
        boolean inPlay() { return playRank >= 0; }
        boolean inRu() { return ruRank >= 0; }
        boolean inFd() { return fdRank >= 0; }
        int score() {
            int a = playRank < 0 ? 999 : playRank, b = ruRank < 0 ? 999 : ruRank, f = fdRank < 0 ? 999 : fdRank + 1;
            return Math.min(Math.min(a, b), f) * 2 - ((playRank >= 0 && ruRank >= 0) ? 3 : 0);
        }
    }

    static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(7000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally { c.disconnect(); }
    }

    /** Angel OS server, set per build in /system/build.prop (ro.angel.server). */
    static final String API = sysprop("ro.angel.server", "");
    static String sysprop(String k, String def) {
        try { String v = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, k, def); return v.isEmpty() ? def : v; }
        catch (Exception e) { return def; }
    }

    /** Google Play search through our server (play.google.com is unreachable from the phone). */
    static List<App> playApi(String q) throws Exception {
        JSONArray arr = new JSONObject(new String(get(API + "/api/search?q=" + URLEncoder.encode(q, "UTF-8")), "UTF-8")).getJSONArray("play");
        List<App> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i); App a = new App();
            a.pkg = o.getString("pkg"); a.title = o.optString("title", a.pkg); a.subtitle = o.optString("subtitle", "");
            a.icon = o.optString("icon", null); a.rating = o.isNull("rating") ? null : o.optString("rating", null); a.playRank = i;
            out.add(a);
        }
        return out;
    }

    /** Angel OS apps hosted on our server. */
    static List<App> catalog() throws Exception {
        JSONArray arr = new JSONObject(new String(get(API + "/api/apps"), "UTF-8")).getJSONArray("apps");
        List<App> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i); App a = new App(); a.angel = true;
            a.pkg = o.getString("pkg"); a.title = o.getString("name"); a.subtitle = o.optString("description", "");
            a.versionCode = o.getInt("versionCode"); a.file = o.getString("file"); a.sha256 = o.getString("sha256"); a.size = o.getLong("size");
            JSONArray u = o.optJSONArray("urls");   // GitHub Releases first, our server as the mirror
            if (u != null && u.length() > 0) { a.urls = new String[u.length() + 1]; for (int k = 0; k < u.length(); k++) a.urls[k] = u.getString(k); a.urls[u.length()] = API + "/apk/" + a.file; }
            else a.urls = new String[] { API + "/apk/" + a.file };
            a.icon = API + "/icon/" + a.pkg + ".png"; a.rating = "v" + o.optString("versionName", "");
            out.add(a);
        }
        return out;
    }

    static List<App> fdroid(String q) throws Exception {
        JSONArray arr = new JSONObject(new String(get(API + "/api/fdroid?q=" + URLEncoder.encode(q, "UTF-8")), "UTF-8")).getJSONArray("fdroid");
        List<App> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i); App a = new App();
            a.pkg = o.getString("pkg"); a.title = o.optString("title", a.pkg); a.subtitle = o.optString("subtitle", "");
            a.icon = o.isNull("icon") ? null : o.optString("icon", null); a.fdRank = i; out.add(a);
        }
        return out;
    }

    /** Ask the server to fetch the F-Droid APK into its cache; fills file/sha256/size/versionCode. */
    static void fdroidFetch(App a) throws Exception {
        JSONObject o = new JSONObject(new String(get(API + "/api/fdroid/fetch?pkg=" + URLEncoder.encode(a.pkg, "UTF-8")), "UTF-8"));
        a.file = o.getString("file"); a.sha256 = o.getString("sha256"); a.size = o.getLong("size"); a.versionCode = o.getInt("versionCode");
    }

    interface Progress { void on(long done, long total); }

    /** Resolve the current F-Droid build straight from f-droid.org (no load on our server). */
    static void fdroidDirect(App a) throws Exception {
        JSONObject o = new JSONObject(new String(get("https://f-droid.org/api/v1/packages/" + URLEncoder.encode(a.pkg, "UTF-8")), "UTF-8"));
        a.versionCode = o.getInt("suggestedVersionCode"); a.file = a.pkg + "_" + a.versionCode + ".apk"; a.sha256 = null;
        a.urls = new String[] { "https://f-droid.org/repo/" + a.file };
    }

    /** Try each URL in turn; stream to disk with progress; returns hex SHA-256 of what was saved. */
    static String download(String[] urls, java.io.File dst, Progress p) throws Exception {
        Exception last = null;
        for (String u : urls) {
            try { return download(u, dst, p); } catch (Exception e) { last = e; }
        }
        throw last == null ? new java.io.IOException("no source") : last;
    }

    static String download(String url, java.io.File dst, Progress p) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(30000); c.setRequestProperty("User-Agent", UA); c.setInstanceFollowRedirects(true);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        long total = c.getContentLengthLong(), done = 0;
        try (InputStream in = c.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); md.update(buf, 0, n); done += n; if (p != null) p.on(done, total); }
        } finally { c.disconnect(); }
        StringBuilder hex = new StringBuilder(); for (byte b : md.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /** Stream a URL to a file while hashing it; returns hex SHA-256. */
    static String download(String url, java.io.File dst) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(30000); c.setRequestProperty("User-Agent", UA);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = c.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); md.update(buf, 0, n); }
        } finally { c.disconnect(); }
        StringBuilder hex = new StringBuilder(); for (byte b : md.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    static List<App> rustore(String q) throws Exception {
        String url = "https://backapi.rustore.ru/applicationData/apps?pageSize=20&pageNumber=0&query=" + URLEncoder.encode(q, "UTF-8");
        JSONObject root = new JSONObject(new String(get(url), "UTF-8"));
        JSONArray arr = root.getJSONObject("body").getJSONArray("content");
        List<App> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            App a = new App();
            a.pkg = o.getString("packageName");
            a.title = o.optString("appName", a.pkg);
            a.subtitle = o.optString("shortDescription", "");
            a.icon = o.optString("iconUrl", null);
            double r = o.optDouble("averageUserRating", 0);
            if (r > 0) a.rating = String.format(java.util.Locale.US, "%.1f", r).replace('.', ',');
            a.ruRank = i;
            out.add(a);
        }
        return out;
    }

    private static final Pattern ANCHOR = Pattern.compile("/store/apps/details\\?id=([\\w.]+)");
    private static final Pattern ICON = Pattern.compile("src=\"(https://play-lh\\.googleusercontent\\.com/[^\"]+?=s\\d+[^\"]*)\"");
    private static final Pattern LABEL = Pattern.compile("\\A[^>]*?aria-label=\"([^\"]+)\"");
    private static final Pattern SPAN = Pattern.compile("<span[^>]*>([^<]{2,})</span>");
    private static final Pattern RATING = Pattern.compile("aria-label=\"[^\"]*?(\\d[,.]\\d)[^\"]*?5\"");

    static List<App> play(String q) throws Exception {
        String url = "https://play.google.com/store/search?c=apps&hl=ru&gl=RU&q=" + URLEncoder.encode(q, "UTF-8");
        String s = new String(get(url), "UTF-8");
        // first occurrence of each package, in page order
        Map<String, Integer> first = new LinkedHashMap<>();
        Matcher m = ANCHOR.matcher(s);
        while (m.find()) if (!first.containsKey(m.group(1))) first.put(m.group(1), m.start());
        List<String> pkgs = new ArrayList<>(first.keySet());
        List<App> out = new ArrayList<>();
        for (int i = 0; i < pkgs.size() && out.size() < 20; i++) {
            int start = first.get(pkgs.get(i));
            int end = i + 1 < pkgs.size() ? first.get(pkgs.get(i + 1)) : Math.min(s.length(), start + 8000);
            if (end <= start) end = Math.min(s.length(), start + 8000);
            String seg = s.substring(start, Math.min(end, start + 12000));
            App a = new App(); a.pkg = pkgs.get(i); a.playRank = out.size();
            Matcher lm = LABEL.matcher(seg.substring(0, Math.min(seg.length(), 400)));
            if (lm.find() && !lm.group(1).startsWith("Смотреть")) a.title = unescape(lm.group(1));
            Matcher im = ICON.matcher(seg);
            int after = 0;
            if (im.find()) { a.icon = im.group(1).replaceAll("=s\\d+", "=s128"); after = im.end(); }
            Matcher sm = SPAN.matcher(seg); sm.region(after, seg.length());
            List<String> spans = new ArrayList<>();
            while (sm.find() && spans.size() < 3) {
                String t = unescape(sm.group(1).trim());
                if (!t.isEmpty() && !t.matches("[\\d,.]+")) spans.add(t);
            }
            if (a.title == null && !spans.isEmpty()) a.title = spans.remove(0);
            else if (a.title != null && !spans.isEmpty() && spans.get(0).equals(a.title)) spans.remove(0);
            if (!spans.isEmpty()) a.subtitle = spans.get(0);
            Matcher rm = RATING.matcher(seg);
            if (rm.find()) a.rating = rm.group(1).replace('.', ',');
            if (a.title == null) a.title = a.pkg;
            if (a.icon != null) out.add(a);
        }
        return out;
    }

    static String unescape(String t) {
        return t.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }

    /** Merge by package name; apps found in both stores become one card. */
    static List<App> merge(List<App> play, List<App> ru, List<App> fd) {
        Map<String, App> map = new LinkedHashMap<>();
        for (App a : play) map.put(a.pkg, a);
        for (App r : ru) {
            App a = map.get(r.pkg);
            if (a == null) { map.put(r.pkg, r); continue; }
            a.ruRank = r.ruRank;
            if (a.subtitle == null || a.subtitle.isEmpty()) a.subtitle = r.subtitle;
            if (a.rating == null) a.rating = r.rating;
        }
        for (App f : fd) {
            App a = map.get(f.pkg);
            if (a == null) { map.put(f.pkg, f); continue; }
            a.fdRank = f.fdRank;
        }
        List<App> out = new ArrayList<>(map.values());
        out.sort((x, y) -> Integer.compare(x.score(), y.score()));
        return out;
    }
}
