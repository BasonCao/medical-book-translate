package com.aitiniubi.medicalbooktranslator.translation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detailed, privacy-safe translation diagnostics.
 * Logs pipeline stage, batch/unit id, provider/model, timing, validation,
 * retry/fallback path and errors. Source text is never written to the log.
 */
public final class TranslationLogger {
    private final File file;
    private final AtomicLong seq = new AtomicLong();
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    public TranslationLogger(File workspace) {
        if (workspace != null && !workspace.exists()) workspace.mkdirs();
        file = new File(workspace == null ? new File(".") : workspace, "translation-debug.log");
    }

    public synchronized void start(String sourceName, String sourceHash, int total) {
        write("JOB_START source=" + sourceName + " sourceHash=" + sourceHash + " total=" + total);
    }

    public synchronized void event(String stage, String message) {
        write(stage + " " + message);
    }

    public synchronized void unit(String stage, String id, String file, int ordinal,
                                   String status, String provider, String model,
                                   long elapsedMs, String detail) {
        write(stage + " unit=" + shortId(id) + " file=" + safe(file)
                + " ordinal=" + ordinal + " status=" + safe(status)
                + " provider=" + safe(provider) + " model=" + safe(model)
                + " elapsedMs=" + elapsedMs + " detail=" + safe(detail));
    }

    public synchronized void error(String stage, Throwable t) {
        String msg = t == null ? "" : (t.getMessage() == null ? t.toString() : t.getMessage());
        write(stage + " ERROR " + safe(msg));
    }

    public File getFile() { return file; }

    public static TranslationLogger current() { return CURRENT.get(); }
    private static final ThreadLocal<TranslationLogger> CURRENT = new ThreadLocal<>();
    public static void bind(TranslationLogger logger) { if (logger == null) CURRENT.remove(); else CURRENT.set(logger); }
    public static void unbind() { CURRENT.remove(); }

    private void write(String line) {
        String row = fmt.format(new Date()) + " #" + seq.incrementAndGet() + " " + line + System.lineSeparator();
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(row.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
            // Diagnostics must never break translation.
        }
    }

    private static String shortId(String s) {
        if (s == null) return "";
        return s.substring(0, Math.min(12, s.length()));
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace((char)10, ' ').replace((char)13, ' ').replace('|', '/');
    }
}
