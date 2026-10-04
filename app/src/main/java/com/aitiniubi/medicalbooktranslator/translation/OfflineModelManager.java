package com.aitiniubi.medicalbooktranslator.translation;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.dnsoverhttps.DnsOverHttps;

/**
 * Manages the optional on-device NLLB-200 distilled 600M Q4_0 model.
 *
 * The model is deliberately NOT bundled in the APK. The APK contains only the
 * native inference engine; this class downloads the ~495 MB model on demand
 * into app-private storage.
 */
public final class OfflineModelManager {
    public static final String MODEL_FILE = "nllb-600m-Q4_0.gguf";
    public static final String BINARY_FILE = "nllb-simple";

    // Primary source documented by the model publisher.
    public static final String MODEL_URL =
            "https://huggingface.co/Hosstia/nllb-200-distilled-600m-gguf/resolve/main/nllb-600m-Q4_0.gguf";
    // Mirror used when the primary HF endpoint is unavailable from a network.
    private static final String MODEL_URL_FALLBACK =
            "https://hf-mirror.com/Hosstia/nllb-200-distilled-600m-gguf/resolve/main/nllb-600m-Q4_0.gguf";

    private static final long MIN_MODEL_BYTES = 450L * 1024L * 1024L;
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

    public static File partialModel(Context c) {
        return new File(root(c), MODEL_FILE + ".part");
    }

    public static File binary(Context c) {
        return new File(root(c), BINARY_FILE);
    }

    public static boolean isModelReady(Context c) {
        File f = model(c);
        return f.isFile() && f.length() >= MIN_MODEL_BYTES;
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
             java.io.OutputStream out =
                     new BufferedOutputStream(new FileOutputStream(dst))) {
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
     * Downloads the model separately from the APK.
     *
     * The .part file is retained between attempts and HTTP Range is used when
     * the server supports it, so an interrupted download can continue rather
     * than restarting from zero.
     */
    public static void downloadModel(Context c, Progress progress) throws Exception {
        File dir = root(c);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Không tạo được thư mục model offline.");
        }

        if (isModelReady(c)) {
            if (progress != null) progress.onProgress(model(c).length(), model(c).length());
            return;
        }

        File tmp = partialModel(c);
        File dst = model(c);

        OkHttpClient bootstrap = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(180, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        DnsOverHttps doh = new DnsOverHttps.Builder()
                .client(bootstrap)
                .url(HttpUrl.parse("https://cloudflare-dns.com/dns-query"))
                .bootstrapDnsHosts(
                        ip("1.1.1.1"),
                        ip("1.0.0.1"),
                        ip("2606:4700:4700::1111"),
                        ip("2606:4700:4700::1001"))
                .includeIPv6(true)
                .build();

        OkHttpClient client = bootstrap.newBuilder()
                .dns(doh)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        IOException last = null;

        for (String url : new String[]{MODEL_URL, MODEL_URL_FALLBACK}) {
            try {
                long existing = tmp.isFile() ? tmp.length() : 0L;
                Request.Builder rb = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "MedBook-Dich-AI/1.13.0")
                        .header("Accept", "application/octet-stream");

                if (existing > 0) {
                    rb.header("Range", "bytes=" + existing + "-");
                }

                try (Response res = client.newCall(rb.build()).execute()) {
                    if (!res.isSuccessful() || res.body() == null) {
                        last = new IOException("HTTP " + res.code() + " từ " + url);
                        continue;
                    }

                    boolean resumed = res.code() == 206 && existing > 0;
                    long base = resumed ? existing : 0L;

                    // A server that ignores Range returns 200. Restart safely
                    // instead of appending the whole file to a partial file.
                    if (!resumed && existing > 0) {
                        if (!tmp.delete()) {
                            throw new IOException("Không thể reset file tải dở.");
                        }
                        base = 0L;
                    }

                    long bodyLength = res.body().contentLength();
                    long total = bodyLength > 0 ? base + bodyLength : -1L;

                    if (progress != null) progress.onProgress(base, total);

                    if (resumed) {
                        appendResponse(tmp, res.body().byteStream(), base, total, progress);
                    } else {
                        writeResponse(tmp, res.body().byteStream(), total, progress);
                    }

                    if (tmp.length() >= MIN_MODEL_BYTES) {
                        atomicReplace(tmp, dst);
                        if (progress != null) progress.onProgress(dst.length(), dst.length());
                        return;
                    }

                    last = new IOException(
                            "Model tải về chưa đủ kích thước: " + tmp.length() + " bytes từ " + url);
                }
            } catch (IOException e) {
                last = e;
            }
        }

        throw new IOException(
                "Tải model NLLB thất bại. File .part vẫn được giữ để lần sau tiếp tục. "
                        + (last != null ? last.getMessage() : ""));
    }

    private static void writeResponse(
            File tmp, InputStream source, long total, Progress progress) throws IOException {
        try (InputStream in = new BufferedInputStream(source);
             java.io.OutputStream out =
                     new BufferedOutputStream(new FileOutputStream(tmp, false))) {
            copy(in, out, 0L, total, progress);
        }
    }

    private static void appendResponse(
            File tmp, InputStream source, long existing, long total, Progress progress)
            throws IOException {
        try (InputStream in = new BufferedInputStream(source);
             java.io.OutputStream out =
                     new BufferedOutputStream(new FileOutputStream(tmp, true))) {
            copy(in, out, existing, total, progress);
        }
    }

    private static void copy(
            InputStream in, java.io.OutputStream out, long done, long total, Progress progress)
            throws IOException {
        byte[] buf = new byte[1024 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (progress != null) progress.onProgress(done, total);
        }
        out.flush();
    }

    private static void atomicReplace(File tmp, File dst) throws IOException {
        if (dst.exists() && !dst.delete()) {
            throw new IOException("Không thay thế được model cũ.");
        }
        if (!tmp.renameTo(dst)) {
            throw new IOException("Không thể hoàn tất cài model offline.");
        }
    }

    /**
     * Optional integrity helper for future CDN pinning. It is not hard-coded
     * until a verified public SHA-256 for the selected Q4_0 file is available.
     */
    public static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(file))) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
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
