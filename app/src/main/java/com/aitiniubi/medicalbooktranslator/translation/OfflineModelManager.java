package com.aitiniubi.medicalbooktranslator.translation;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

import okhttp3.dnsoverhttps.DnsOverHttps;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Manages the optional on-device NLLB-200 distilled 600M model.
 *
 * The model is deliberately NOT bundled into the APK. Only the ARM64
 * nllb-simple engine is bundled. The model is downloaded into app-private
 * storage and can be resumed after an interrupted download.
 */
public final class OfflineModelManager {
    public static final String MODEL_FILE = "nllb-600m-f16.gguf";
    public static final String BINARY_FILE = "nllb-simple";

    public static final String MODEL_URL =
            "https://huggingface.co/acceldium/nllb-200-distilled-600M-GGUF/resolve/main/nllb-600m.gguf?download=true";

    // The verified model is ~1.64 GiB (1,678 MiB). Keep a safety floor to reject the old Q4_0 model.
    private static final long MIN_MODEL_BYTES = 1_600L * 1024L * 1024L;
    private static final long EXPECTED_MODEL_BYTES = 1_800L * 1000L * 1000L;
    private static final String BINARY_ASSET = "offline-engine/nllb-simple";
    private static volatile Boolean selfTestReady = null;
    private static volatile String lastSelfTestError = "";

    private OfflineModelManager() {}

    public interface Progress {
        void onProgress(long done, long total);
    }

    public static File root(Context c) {
        return new File(c.getFilesDir(), "offline-model");
    }

    public static File model(Context c) {
        return new File(root(c), MODEL_FILE);
    }

    public static File partial(Context c) {
        return new File(root(c), MODEL_FILE + ".part");
    }

    public static File binary(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libnllb.so");
    }

    public static boolean isArm64Supported() {
        if (Build.VERSION.SDK_INT < 21) return false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equalsIgnoreCase(abi)) return true;
        }
        return false;
    }

    public static String readinessError(Context c) {
        if (!isArm64Supported()) {
            return "Thiết bị không hỗ trợ ARM64 (arm64-v8a). Dịch OFFLINE NLLB hiện chỉ hỗ trợ ARM64.";
        }
        if (!isModelReady(c)) return "Model NLLB-600M chưa được cài đặt.";
        File f = binary(c);
        if (!f.isFile()) return "Không tìm thấy engine NLLB trong APK: " + f.getAbsolutePath();
        if (!f.canExecute()) return "Android không cho phép thực thi engine NLLB: " + f.getAbsolutePath();
        return "";
    }

    public static boolean isModelReady(Context c) {
        File f = model(c);
        return f.isFile() && f.length() >= MIN_MODEL_BYTES;
    }

    public static void cleanupLegacyModel(Context c) {
        File legacy = new File(root(c), "nllb-600m-Q4_0.gguf");
        if (legacy.exists()) legacy.delete();
        File legacyPart = new File(root(c), "nllb-600m-Q4_0.gguf.part");
        if (legacyPart.exists()) legacyPart.delete();
    }

    public static String lastSelfTestError() { return lastSelfTestError; }

    public static long partialBytes(Context c) {
        File f = partial(c);
        return f.isFile() ? f.length() : 0L;
    }

    public static boolean isReady(Context c) {
        String basic = readinessError(c);
        if (!basic.isEmpty()) return false;
        if (Boolean.TRUE.equals(selfTestReady)) return true;
        try {
            OfflineNllbTranslator.selfTest(c, null);
            selfTestReady = true;
            lastSelfTestError = "";
            return true;
        } catch (Exception e) {
            selfTestReady = false;
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            lastSelfTestError = tail500(msg);
            return false;
        }
    }

    public static boolean ensureBinary(Context c) {
        if (!isArm64Supported()) return false;
        File f = binary(c);
        return f.isFile() && f.length() > 0 && f.canExecute();
    }

    /**
     * Download with HTTP Range resume.
     *
     * If the connection drops, the .part file is kept. Calling this method again
     * continues from the already downloaded byte offset whenever the server
     * supports Range. The completed file is atomically renamed into place.
     */
    public static void downloadModel(Context c, Progress progress) throws Exception {
        File dir = root(c);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Không tạo được thư mục model offline.");
        }

        if (isModelReady(c)) return;

        long remoteSize = probeRemoteSize(c);
        ensureFreeSpace(c, remoteSize);

        File tmp = partial(c);
        File dst = model(c);

        OkHttpClient bootstrap = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        DnsOverHttps doh = new DnsOverHttps.Builder()
                .client(bootstrap)
                .url(HttpUrl.parse("https://cloudflare-dns.com/dns-query"))
                .bootstrapDnsHosts(
                        ip("1.1.1.1"), ip("1.0.0.1"),
                        ip("2606:4700:4700::1111"),
                        ip("2606:4700:4700::1001"))
                .includeIPv6(true)
                .build();

        OkHttpClient client = bootstrap.newBuilder().dns(doh)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        IOException last = null;
        String[] urls = new String[]{MODEL_URL};

        for (String url : urls) {
            long existing = tmp.isFile() ? tmp.length() : 0L;
            Request.Builder rb = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "MedBook-Dich-AI/1.14")
                    .header("Accept", "application/octet-stream");

            if (existing > 0) {
                rb.header("Range", "bytes=" + existing + "-");
            }

            try (Response res = client.newCall(rb.build()).execute()) {
                if (res.code() == 416 && existing >= MIN_MODEL_BYTES) {
                    if (tmp.renameTo(dst)) return;
                    throw new IOException("Model đã đủ nhưng không thể hoàn tất file.");
                }

                if (!res.isSuccessful() || res.body() == null) {
                    // If a mirror rejects Range, restart from zero for that mirror.
                    if (existing > 0 && (res.code() == 200 || res.code() == 403 || res.code() == 416)) {
                        existing = 0;
                        tmp.delete();
                    }
                    last = new IOException("HTTP " + res.code() + " từ " + url);
                    continue;
                }

                boolean resumed = res.code() == 206;
                long total;
                String contentRange = res.header("Content-Range");
                if (resumed && contentRange != null) {
                    long remoteTotal = parseContentRangeTotal(contentRange);
                    total = remoteTotal > 0 ? remoteTotal : existing + Math.max(0, res.body().contentLength());
                } else {
                    // Server ignored Range. Never append a full response to a partial file.
                    if (existing > 0) {
                        tmp.delete();
                        existing = 0;
                    }
                    total = res.body().contentLength();
                }

                boolean append = resumed && existing > 0;
                try (InputStream in = res.body().byteStream();
                     OutputStream out = new BufferedOutputStream(
                             new FileOutputStream(tmp, append))) {
                    byte[] buf = new byte[1024 * 1024];
                    long done = existing;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (progress != null) progress.onProgress(done, total);
                    }
                }

                if (tmp.length() >= MIN_MODEL_BYTES) {
                    if (tmp.length() < remoteSize) {
                        last = new IOException("Model tải chưa đủ: " + tmp.length() + " / " + remoteSize + " bytes.");
                        continue;
                    }
                    if (dst.exists() && !dst.delete()) {
                        throw new IOException("Không thay thế được model cũ.");
                    }
                    if (!tmp.renameTo(dst)) {
                        throw new IOException("Không thể hoàn tất cài model offline.");
                    }
                    selfTestReady = null;
                    lastSelfTestError = "";
                    return;
                }

                last = new IOException(
                        "Model tải về chưa đủ: " + tmp.length() + " bytes.");
            } catch (IOException e) {
                last = e;
            }
        }

        throw new IOException(
                "Tải model NLLB thất bại. File .part vẫn được giữ để thử lại/resume. "
                        + (last != null ? last.getMessage() : ""));
    }

    private static long probeRemoteSize(Context c) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true).followSslRedirects(true).build();
        Request req = new Request.Builder().url(MODEL_URL).head().build();
        try (Response res = client.newCall(req).execute()) {
            if (!res.isSuccessful()) throw new IOException("HTTP " + res.code() + " khi kiểm tra kích thước model.");
            long size = res.body() == null ? -1L : res.body().contentLength();
            if (size < MIN_MODEL_BYTES) throw new IOException("Kích thước model từ server không hợp lệ: " + size + " bytes.");
            return size;
        }
    }

    private static void ensureFreeSpace(Context c, long modelBytes) throws IOException {
        android.os.StatFs stat = new android.os.StatFs(c.getFilesDir().getAbsolutePath());
        long free = stat.getAvailableBytes();
        long required = modelBytes * 2L;
        if (free < required) {
            throw new IOException("Không đủ dung lượng trống. Cần ít nhất " + formatGb(required)
                    + ", hiện có " + formatGb(free) + ".");
        }
    }

    private static String formatGb(long bytes) { return String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0); }
    private static String tail500(String s) { return s.length() <= 500 ? s : s.substring(s.length() - 500); }

    public static String modelStatus(Context c) {
        if (isModelReady(c)) {
            return String.format(Locale.US, "%.0f MB",
                    model(c).length() / (1024.0 * 1024.0));
        }
        long part = partialBytes(c);
        if (part > 0) {
            return String.format(Locale.US, "đã tải %.0f MB",
                    part / (1024.0 * 1024.0));
        }
        return "chưa tải";
    }


    private static long parseContentRangeTotal(String value) {
        try {
            int slash = value.lastIndexOf('/');
            if (slash < 0) return -1;
            return Long.parseLong(value.substring(slash + 1).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static InetAddress ip(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(
                    "Invalid DNS bootstrap address: " + value, e);
        }
    }
}
