package vn.guianh;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Toàn bộ phần nói chuyện với máy tính. Không dùng thư viện ngoài nào —
 * chỉ HttpURLConnection có sẵn trong Android.
 */
public class Net {

    public interface Progress {
        void onProgress(String message);
    }

    /** Kết quả dò tìm máy tính trong mạng nội bộ. */
    public static class Found {
        public final String host;
        public final int port;
        public final boolean needPin;
        Found(String host, int port, boolean needPin) {
            this.host = host; this.port = port; this.needPin = needPin;
        }
    }

    private static final int[] PORTS = {8787, 8788, 8789, 8790, 8800, 9090};

    /** Địa chỉ IPv4 của điện thoại trong mạng WiFi. */
    public static String ownIPv4() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Quét toàn bộ dải mạng của điện thoại để tìm máy tính đang chạy GuiAnh.
     * Quét song song nên chỉ mất vài giây.
     */
    public static Found discover(Progress p) {
        String own = ownIPv4();
        if (own == null) return null;
        int lastDot = own.lastIndexOf('.');
        if (lastDot < 0) return null;
        final String prefix = own.substring(0, lastDot + 1);

        final AtomicReference<Found> hit = new AtomicReference<>(null);
        for (final int port : PORTS) {
            if (hit.get() != null) break;
            if (p != null) p.onProgress("Đang dò " + prefix + "* cổng " + port + "…");
            ExecutorService pool = Executors.newFixedThreadPool(64);
            for (int i = 1; i <= 254; i++) {
                final String ip = prefix + i;
                pool.execute(new Runnable() {
                    @Override public void run() {
                        if (hit.get() != null) return;
                        Boolean needPin = hello(ip, port, 500);
                        if (needPin != null) hit.compareAndSet(null, new Found(ip, port, needPin));
                    }
                });
            }
            pool.shutdown();
            try { pool.awaitTermination(12, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            pool.shutdownNow();
            if (hit.get() != null) return hit.get();
        }
        return hit.get();
    }

    /** Hỏi một địa chỉ xem có phải GuiAnh không. Trả về null nếu không phải. */
    public static Boolean hello(String host, int port, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://" + host + ":" + port + "/api/hello");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            if (c.getResponseCode() != 200) return null;
            String body = readAll(c.getInputStream());
            if (!body.contains("\"GuiAnh\"")) return null;
            return body.contains("\"needPin\":true");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Đăng nhập bằng mã PIN, trả về chìa khoá để dùng cho các lần sau. */
    public static String login(String host, int port, String pin) throws IOException {
        HttpURLConnection c = (HttpURLConnection)
                new URL("http://" + host + ":" + port + "/api/login").openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        String body = "{\"pin\":\"" + jsonEscape(pin) + "\"}";
        c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

        int code = c.getResponseCode();
        String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code != 200) {
            throw new IOException(extract(resp, "error", "Mã PIN không đúng"));
        }
        String token = extract(resp, "token", "");
        if (token.isEmpty()) {
            throw new IOException("Máy tính không trả về chìa khoá. Hãy cập nhật GuiAnh trên máy tính.");
        }
        return token;
    }

    /**
     * Tải danh sách file lên máy tính. Đọc tới đâu gửi tới đó, không nạp cả
     * file vào bộ nhớ, nên video vài GB vẫn gửi được.
     */
    public static int upload(ContentResolver cr, Prefs prefs, List<Uri> uris, Progress p)
            throws IOException {
        String boundary = "----GuiAnhBoundary" + System.currentTimeMillis();
        URL url = new URL(prefs.baseUrl() + "/api/upload?k="
                + URLEncoder.encode(prefs.token(), "UTF-8"));

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(60000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setChunkedStreamingMode(256 * 1024);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        int sent = 0;
        OutputStream raw = c.getOutputStream();
        BufferedOutputStream out = new BufferedOutputStream(raw, 256 * 1024);
        try {
            for (int i = 0; i < uris.size(); i++) {
                Uri uri = uris.get(i);
                String name = displayName(cr, uri, "file-" + (i + 1));
                if (p != null) {
                    p.onProgress("Đang gửi " + (i + 1) + "/" + uris.size() + ": " + name);
                }
                String head = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"files\"; filename=\""
                        + name.replace("\"", "_") + "\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.UTF_8));

                InputStream in = cr.openInputStream(uri);
                if (in == null) throw new IOException("Không mở được " + name);
                byte[] buf = new byte[256 * 1024];
                int n;
                try {
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    in.close();
                }
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                sent++;
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            try { out.close(); } catch (Exception ignored) { }
        }

        int code = c.getResponseCode();
        String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code == 401) throw new IOException("Máy tính không nhận chìa khoá nữa. Hãy kết nối lại trong app.");
        if (code != 200) throw new IOException("Máy tính báo lỗi " + code + ": " + resp);
        return sent;
    }

    /** Gửi một đoạn văn bản hoặc đường link. */
    public static void sendText(Prefs prefs, String text) throws IOException {
        HttpURLConnection c = (HttpURLConnection)
                new URL(prefs.baseUrl() + "/api/text?k="
                        + URLEncoder.encode(prefs.token(), "UTF-8")).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.getOutputStream().write(("{\"text\":\"" + jsonEscape(text) + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        int code = c.getResponseCode();
        c.disconnect();
        if (code != 200) throw new IOException("Máy tính báo lỗi " + code);
    }

    // ---------- tiện ích ----------

    public static String displayName(ContentResolver cr, Uri uri, String fallback) {
        Cursor cur = null;
        try {
            cur = cr.query(uri, null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String s = cur.getString(i);
                    if (s != null && !s.trim().isEmpty()) return s;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        String last = uri.getLastPathSegment();
        return (last != null && !last.isEmpty()) ? last : fallback;
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        try {
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        } catch (IOException ignored) {
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Lấy giá trị chuỗi của một khoá trong JSON, không cần thư viện phân tích. */
    private static String extract(String json, String key, String fallback) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return fallback;
        i = json.indexOf(':', i + needle.length());
        if (i < 0) return fallback;
        i = json.indexOf('"', i);
        if (i < 0) return fallback;
        StringBuilder sb = new StringBuilder();
        for (int j = i + 1; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (ch == '\\' && j + 1 < json.length()) { sb.append(json.charAt(++j)); continue; }
            if (ch == '"') break;
            sb.append(ch);
        }
        String out = sb.toString();
        return out.isEmpty() ? fallback : out;
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }

    // ---------- Nhận file máy tính gửi sang ----------

    /** Một mục đang chờ trên máy tính. */
    public static class Pending {
        public final String id, name;
        public final long size;
        Pending(String id, String name, long size) {
            this.id = id; this.name = name; this.size = size;
        }
    }

    /**
     * Hỏi máy tính xem có file nào đang chờ không.
     * Chỉ lấy những mục do MÁY TÍNH gửi (from = "pc") và chưa nhận lần nào.
     */
    public static List<Pending> listPending(Prefs prefs, java.util.Set<String> already)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection)
                new URL(prefs.baseUrl() + "/api/items?k="
                        + URLEncoder.encode(prefs.token(), "UTF-8")).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        int code = c.getResponseCode();
        String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code == 401) throw new IOException("Máy tính không nhận chìa khoá nữa. Hãy kết nối lại.");
        if (code != 200) throw new IOException("Máy tính báo lỗi " + code);

        List<Pending> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(body).getJSONArray("items");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!"file".equals(o.optString("kind"))) continue;
                if (!"pc".equals(o.optString("from"))) continue;
                String id = o.optString("id");
                if (id.isEmpty() || already.contains(id)) continue;
                out.add(new Pending(id, o.optString("name", "file"), o.optLong("size", 0)));
            }
        } catch (Exception e) {
            throw new IOException("Không đọc được danh sách: " + e.getMessage());
        }
        return out;
    }

    /** Tải một mục về và ghi thẳng vào album ảnh của điện thoại. */
    public static void receive(android.content.Context ctx, Prefs prefs, Pending p)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection)
                new URL(prefs.baseUrl() + "/dl/" + p.id + "?k="
                        + URLEncoder.encode(prefs.token(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(60000);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("Không tải được " + p.name + " (mã " + code + ")");
        }
        InputStream in = c.getInputStream();
        try {
            Media.save(ctx, p.name, in);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            c.disconnect();
        }
    }

    /** Báo máy tính bỏ mục đã nhận xong ra khỏi hàng chờ. */
    public static void removeItem(Prefs prefs, String id) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL(prefs.baseUrl() + "/api/delete?k="
                            + URLEncoder.encode(prefs.token(), "UTF-8")).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.getOutputStream().write(("{\"id\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8));
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignored) {
            // Không xoá được cũng không sao, lần sau app tự bỏ qua vì đã nhớ id
        }
    }

    public static List<Uri> single(Uri u) {
        List<Uri> l = new ArrayList<>();
        if (u != null) l.add(u);
        return Collections.unmodifiableList(l);
    }
}
