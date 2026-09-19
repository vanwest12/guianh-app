package vn.guianh;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Màn hình duy nhất của app.
 *
 * Mở bình thường  -> màn hình cài đặt kết nối.
 * Mở qua nút Chia sẻ -> gửi thẳng file rồi tự đóng.
 */
public class MainActivity extends Activity {

    private Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private EditText hostField, pinField;
    private Button btnFind, btnConnect, btnForget, btnReceive;
    private boolean receiving = false;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        buildUi();

        Intent it = getIntent();
        String action = it != null ? it.getAction() : null;
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            handleShare(it);
        } else {
            showSettings();
            // Mở app lên là tự lấy những gì máy tính đang gửi
            if (prefs.isReady()) doReceive(false);
        }
    }

    // ---------------- Giao diện ----------------

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#F6F7F9"));

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));

        TextView title = new TextView(this);
        title.setText("GuiAnh");
        title.setTextSize(26);
        title.setTextColor(Color.parseColor("#111827"));
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(Color.parseColor("#374151"));
        status.setPadding(0, dp(12), 0, dp(16));
        root.addView(status);

        hostField = new EditText(this);
        hostField.setHint("Địa chỉ máy tính, ví dụ 192.168.1.6");
        hostField.setInputType(InputType.TYPE_CLASS_TEXT);
        hostField.setSingleLine(true);
        root.addView(hostField);

        btnFind = mkButton("Tự tìm máy tính trong mạng", false);
        root.addView(btnFind);

        pinField = new EditText(this);
        pinField.setHint("Mã PIN đã đặt trên máy tính");
        pinField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pinField.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        pinField.setLayoutParams(lp);
        root.addView(pinField);

        btnConnect = mkButton("Kết nối", true);
        root.addView(btnConnect);

        btnReceive = mkButton("Nhận ảnh từ máy tính", true);
        root.addView(btnReceive);

        btnForget = mkButton("Quên kết nối này", false);
        root.addView(btnForget);

        TextView help = new TextView(this);
        help.setText("GỬI ĐI: giữ một tấm ảnh trong thư viện, bấm Chia sẻ rồi chọn GuiAnh. "
                + "Ảnh bay thẳng sang máy tính.\n\n"
                + "NHẬN VỀ: mỗi lần mở app, những gì máy tính gửi sang sẽ tự động tải về và "
                + "vào thẳng album GuiAnh trong Thư viện ảnh.\n\n"
                + "Máy tính phải đang mở GuiAnh và cùng một mạng WiFi với điện thoại.");
        help.setTextSize(13);
        help.setTextColor(Color.parseColor("#6B7280"));
        help.setPadding(0, dp(24), 0, 0);
        root.addView(help);

        sv.addView(root);
        setContentView(sv);

        btnFind.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doDiscover(); }
        });
        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doConnect(); }
        });
        btnReceive.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doReceive(true); }
        });
        btnForget.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.clear();
                showSettings();
                toast("Đã xoá kết nối");
            }
        });
    }

    private Button mkButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        if (primary) {
            b.setBackgroundColor(Color.parseColor("#2563EB"));
            b.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private void showSettings() {
        setControlsVisible(true);
        if (prefs.isReady()) {
            status.setText("Đã kết nối với máy tính " + prefs.host() + ":" + prefs.port()
                    + "\n\nBạn có thể dùng nút Chia sẻ ngay bây giờ.");
            hostField.setText(prefs.host());
        } else {
            status.setText("Chưa kết nối với máy nào.\n\n"
                    + "Mở GuiAnh trên máy tính, rồi bấm nút bên dưới để tìm máy.");
        }
    }

    private void setControlsVisible(boolean show) {
        int v = show ? View.VISIBLE : View.GONE;
        hostField.setVisibility(v);
        pinField.setVisibility(v);
        btnFind.setVisibility(v);
        btnConnect.setVisibility(v);
        btnReceive.setVisibility(prefs.isReady() && show ? View.VISIBLE : View.GONE);
        btnForget.setVisibility(prefs.isReady() && show ? View.VISIBLE : View.GONE);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() { @Override public void run() { status.setText(s); } });
    }

    // ---------------- Dò tìm và kết nối ----------------

    private void doDiscover() {
        btnFind.setEnabled(false);
        setStatus("Đang tìm máy tính trong mạng WiFi…");
        new Thread(new Runnable() {
            @Override public void run() {
                final Net.Found f = Net.discover(new Net.Progress() {
                    @Override public void onProgress(String m) { setStatus(m); }
                });
                ui.post(new Runnable() {
                    @Override public void run() {
                        btnFind.setEnabled(true);
                        if (f == null) {
                            status.setText("Không tìm thấy máy tính nào.\n\n"
                                    + "Kiểm tra: máy tính đã mở GuiAnh chưa, điện thoại có đang "
                                    + "dùng chung WiFi không (tắt 4G/5G thử xem), và mạng WiFi "
                                    + "trên Windows đã đặt là Private chưa.\n\n"
                                    + "Hoặc gõ tay địa chỉ máy tính vào ô trên.");
                        } else {
                            hostField.setText(f.host);
                            prefs.save(f.host, f.port, prefs.token());
                            status.setText("Tìm thấy máy tính tại " + f.host + ":" + f.port
                                    + (f.needPin
                                    ? "\n\nGiờ nhập mã PIN rồi bấm Kết nối."
                                    : "\n\nMáy tính chưa đặt mã PIN. Hãy đặt mã PIN trên máy tính trước."));
                        }
                    }
                });
            }
        }).start();
    }

    private void doConnect() {
        final String host = hostField.getText().toString().trim();
        final String pin = pinField.getText().toString();
        if (host.isEmpty()) { toast("Chưa có địa chỉ máy tính"); return; }
        if (pin.isEmpty())  { toast("Chưa nhập mã PIN"); return; }

        btnConnect.setEnabled(false);
        setStatus("Đang kết nối…");
        final int port = prefs.port();
        new Thread(new Runnable() {
            @Override public void run() {
                String error = null;
                String token = null;
                int usedPort = port;
                try {
                    token = Net.login(host, port, pin);
                } catch (Exception e) {
                    error = e.getMessage();
                    // Có thể máy tính đang dùng cổng khác, thử các cổng còn lại
                    for (int p : new int[]{8787, 8788, 8789, 8790, 8800, 9090}) {
                        if (p == port) continue;
                        if (Net.hello(host, p, 600) == null) continue;
                        try {
                            token = Net.login(host, p, pin);
                            usedPort = p;
                            error = null;
                            break;
                        } catch (Exception e2) {
                            error = e2.getMessage();
                        }
                    }
                }
                final String fError = error;
                final String fToken = token;
                final int fPort = usedPort;
                ui.post(new Runnable() {
                    @Override public void run() {
                        btnConnect.setEnabled(true);
                        if (fToken != null) {
                            prefs.save(host, fPort, fToken);
                            pinField.setText("");
                            showSettings();
                            toast("Kết nối thành công");
                        } else {
                            status.setText("Không kết nối được.\n\n"
                                    + (fError == null ? "" : fError));
                        }
                    }
                });
            }
        }).start();
    }

    // ---------------- Nhận ảnh máy tính gửi sang ----------------

    /**
     * Lấy về mọi thứ máy tính đang gửi, ghi thẳng vào album ảnh.
     * loud = true khi người dùng tự bấm nút (báo cả khi không có gì).
     */
    private void doReceive(final boolean loud) {
        if (receiving || !prefs.isReady()) return;
        receiving = true;
        btnReceive.setEnabled(false);
        if (loud) setStatus("Đang hỏi máy tính…");

        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                int got = 0;
                String lastName = null;
                try {
                    java.util.Set<String> already = prefs.received();
                    java.util.List<Net.Pending> list = Net.listPending(prefs, already);
                    for (int i = 0; i < list.size(); i++) {
                        Net.Pending p = list.get(i);
                        setStatus("Đang nhận " + (i + 1) + "/" + list.size() + ": " + p.name);
                        Net.receive(MainActivity.this, prefs, p);
                        prefs.markReceived(p.id);
                        Net.removeItem(prefs, p.id);   // dọn khỏi hàng chờ trên máy tính
                        lastName = p.name;
                        got++;
                    }
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fErr = err, fName = lastName;
                final int fGot = got;
                ui.post(new Runnable() {
                    @Override public void run() {
                        receiving = false;
                        btnReceive.setEnabled(true);
                        if (fGot > 0) {
                            toast("Đã nhận " + fGot + " mục vào " + Media.whereSaved(fName));
                            showSettings();
                        } else if (fErr != null) {
                            if (loud) status.setText("Không nhận được.\n\n" + fErr);
                        } else if (loud) {
                            toast("Máy tính không gửi gì mới");
                            showSettings();
                        }
                    }
                });
            }
        }).start();
    }

    // ---------------- Nhận từ nút Chia sẻ ----------------

    private void handleShare(Intent it) {
        setControlsVisible(false);

        if (!prefs.isReady()) {
            setControlsVisible(true);
            status.setText("Chưa kết nối với máy tính nào.\n\n"
                    + "Hãy thiết lập kết nối bên dưới, rồi chia sẻ lại tấm ảnh.");
            return;
        }

        final List<Uri> uris = new ArrayList<>();
        String text = null;

        if (Intent.ACTION_SEND.equals(it.getAction())) {
            Uri u = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
            else text = it.getStringExtra(Intent.EXTRA_TEXT);
        } else {
            ArrayList<Uri> list = it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }

        if (uris.isEmpty() && (text == null || text.trim().isEmpty())) {
            status.setText("Không nhận được gì để gửi.");
            return;
        }

        final String fText = text;
        status.setText("Đang gửi…");
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                int sent = 0;
                try {
                    if (!uris.isEmpty()) {
                        sent = Net.upload(getContentResolver(), prefs, uris, new Net.Progress() {
                            @Override public void onProgress(String m) { setStatus(m); }
                        });
                    } else {
                        Net.sendText(prefs, fText);
                        sent = 1;
                    }
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fErr = err;
                final int fSent = sent;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (fErr == null) {
                            toast("Đã gửi " + fSent + " mục sang máy tính");
                            finish();
                        } else {
                            setControlsVisible(true);
                            status.setText("Gửi thất bại.\n\n" + fErr
                                    + "\n\nKiểm tra máy tính đã mở GuiAnh chưa và hai máy có "
                                    + "cùng WiFi không.");
                        }
                    }
                });
            }
        }).start();
    }
}
