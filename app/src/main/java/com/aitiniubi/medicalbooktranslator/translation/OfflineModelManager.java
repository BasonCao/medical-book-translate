package com.aitiniubi.medicalbooktranslator.translation;

import android.content.Context;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

import okhttp3.dnsoverhttps.DnsOverHttps;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Manages the optional on-device NLLB-200 distilled 600M Q4_0 model.
 *
 * The model is deliberately NOT bundled into the APK. Only the ARM64
 * nllb-simple engine is bundled. The model is downloaded into app-private
 * storage and can be resumed after an interrupted download.
 */
public final class OfflineModelManager {
    public static final String MODEL_FILE = "nllb-600m-Q4_0.gguf";
    public static final String BINARY_FILE = "nllb-simple";

    // Public model documented by the upstream model card as the mobile Q4_0 build (~495 MB).
    public static final String MODEL_URL =
            "https://huggingface.co/Hosstia/nllb-200-distilled-600m-gguf/resolve/main/nllb-600m-Q4_0.gguf";
    public static final String MODEL_URL_FALLBACK =
            "https://hf-mirror.com/Hosstia/nllb-200-distilled-600m-gguf/resolve/main/nllb-600m-Q4_0.gguf";

    private static final long MIN_MODEL_BYTES = 450L * 1024L * 1024L;
    private static final long EXPECTED_MODEL_BYTES = 495L * 1024L * 1024L;
    private static final String BINARY_ASSET = "offline-engine/nllb-simple";

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
        return new File(root(c), BINARY_FILE);
    }

    public static boolean isModelReady(Context c) {
        File f = model(c);
        return f.isFile() && f.length() >= MIN_MODEL_BYTES;
    }

    public static long partialBytes(Context c) {
        File f = partial(c);
        return f.isFile() ? f.length() : 0L;
    }

    public static boolean isReady(Context c) {
        return isModelReady(c) && ensureBinary(c);
    }

    public static boolean ensureBinary(Context c) {
        File dst = binary(c);
        if (dst.isFile() && dst.length() > 10_000_000 && dst.canExecute()) return true;

        File dir = root(c);
        if (!dir.exists() && !dir.mkdirs()) return false;

        try (InputStream in = c.getAssets().open(BINARY_ASSET);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            dst.delete();
            return false;
        }

        if (!dst.setExecutable(true, true)) return false;
        return dst.isFile() && dst.length() > 10_000_000 && dst.canExecute();
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
        String[] urls = new String[]{MODEL_URL, MODEL_URL_FALLBACK};

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
                    if (dst.exists() && !dst.delete()) {
                        throw new IOException("Không thay thế được model cũ.");
                    }
                    if (!tmp.renameTo(dst)) {
                        throw new IOException("Không thể hoàn tất cài model offline.");
                    }
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
