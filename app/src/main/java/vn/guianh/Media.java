package vn.guianh;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Ghi file nhận được vào kho ảnh chung của điện thoại, để nó hiện ngay
 * trong ứng dụng Thư viện / Ảnh chứ không nằm lạc trong thư mục Download.
 *
 * Dùng MediaStore nên KHÔNG cần xin quyền truy cập bộ nhớ: Android cho phép
 * mọi app tự ghi vào thư mục riêng của mình bên trong kho ảnh chung.
 */
public class Media {

    /** Thư mục con hiện ra trong Thư viện ảnh. */
    private static final String ALBUM = "GuiAnh";

    /**
     * Lưu dữ liệu từ luồng vào album. Trả về địa chỉ file đã lưu.
     * Ghi theo kiểu dòng chảy nên video mấy GB cũng không tốn RAM.
     */
    public static Uri save(Context ctx, String name, InputStream in) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        String mime = mimeOf(name);

        Uri collection;
        String relPath;
        if (mime.startsWith("image/")) {
            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relPath = Environment.DIRECTORY_PICTURES + "/" + ALBUM;
        } else if (mime.startsWith("video/")) {
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relPath = Environment.DIRECTORY_MOVIES + "/" + ALBUM;
        } else if (mime.startsWith("audio/")) {
            collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relPath = Environment.DIRECTORY_MUSIC + "/" + ALBUM;
        } else {
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relPath = Environment.DIRECTORY_DOWNLOADS + "/" + ALBUM;
        }

        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        // Đánh dấu "đang ghi" để Thư viện không hiện file dở dang
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri item = cr.insert(collection, v);
        if (item == null) throw new IOException("Không tạo được mục trong thư viện");

        try {
            OutputStream out = cr.openOutputStream(item);
            if (out == null) throw new IOException("Không mở được nơi ghi");
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } finally {
                out.close();
            }
        } catch (IOException e) {
            cr.delete(item, null, null); // ghi hỏng thì dọn luôn, không để rác
            throw e;
        }

        // Ghi xong: bỏ dấu "đang ghi", lúc này Thư viện mới thấy
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(item, done, null, null);
        return item;
    }

    /** Đoán kiểu nội dung từ phần đuôi tên file. */
    public static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        switch (ext) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png":  return "image/png";
            case "gif":  return "image/gif";
            case "webp": return "image/webp";
            case "bmp":  return "image/bmp";
            case "heic": return "image/heic";
            case "heif": return "image/heif";
            case "mp4":  return "video/mp4";
            case "mkv":  return "video/x-matroska";
            case "mov":  return "video/quicktime";
            case "avi":  return "video/x-msvideo";
            case "3gp":  return "video/3gpp";
            case "webm": return "video/webm";
            case "mp3":  return "audio/mpeg";
            case "m4a":  return "audio/mp4";
            case "wav":  return "audio/wav";
            case "flac": return "audio/flac";
            case "pdf":  return "application/pdf";
            case "txt":  return "text/plain";
            case "zip":  return "application/zip";
            default:     return "application/octet-stream";
        }
    }

    /** Nơi file được lưu, để hiện cho người dùng biết. */
    public static String whereSaved(String name) {
        String mime = mimeOf(name);
        if (mime.startsWith("image/")) return "Thư viện ảnh → album " + ALBUM;
        if (mime.startsWith("video/")) return "Thư viện video → album " + ALBUM;
        if (mime.startsWith("audio/")) return "Nhạc → " + ALBUM;
        return "Tệp đã tải → " + ALBUM;
    }
}
