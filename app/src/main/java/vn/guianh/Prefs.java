package vn.guianh;

import android.content.Context;
import android.content.SharedPreferences;

/** Lưu địa chỉ máy tính và chìa khoá đăng nhập. */
public class Prefs {
    private static final String FILE = "guianh";
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String host()  { return sp.getString("host", ""); }
    public int    port()  { return sp.getInt("port", 8787); }
    public String token() { return sp.getString("token", ""); }

    public boolean isReady() {
        return !host().isEmpty() && !token().isEmpty();
    }

    public void save(String host, int port, String token) {
        sp.edit().putString("host", host).putInt("port", port).putString("token", token).apply();
    }

    public void clear() {
        sp.edit().clear().apply();
    }

    /** Những mục đã nhận rồi, để không tải trùng lần sau. */
    public java.util.Set<String> received() {
        java.util.Set<String> s = sp.getStringSet("received", null);
        return s == null ? new java.util.HashSet<String>() : new java.util.HashSet<>(s);
    }

    public void markReceived(String id) {
        java.util.Set<String> s = received();
        s.add(id);
        // Giữ danh sách gọn, chỉ cần nhớ vài trăm mục gần nhất
        if (s.size() > 500) {
            java.util.Iterator<String> it = s.iterator();
            while (s.size() > 400 && it.hasNext()) { it.next(); it.remove(); }
        }
        sp.edit().putStringSet("received", s).apply();
    }

    /** Địa chỉ gốc, ví dụ http://192.168.1.6:8787 */
    public String baseUrl() {
        return "http://" + host() + ":" + port();
    }
}
