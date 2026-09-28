package com.aitiniubi.medicalbooktranslator.translation;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Persistent, crash-safe translation workspace.
 * Completed units are stored individually so a quota/network failure never
 * forces the book to restart from the beginning.
 */
public final class TranslationStateStore {
    public static final class Record {
        public String id;
        public String file;
        public String sourceHash;
        public String translation;
        public Record(String id, String file, String sourceHash, String translation) {
            this.id = id;
            this.file = file;
            this.sourceHash = sourceHash;
            this.translation = translation;
        }
    }

    private final File root;
    private final File unitsDir;
    private final File manifest;

    public TranslationStateStore(File root) {
        this.root = root;
        this.unitsDir = new File(root, "units");
        this.manifest = new File(root, "progress.json");
        if (!unitsDir.exists()) unitsDir.mkdirs();
    }

    public synchronized void initialize(String sourceHash, String sourceName, int total) throws IOException {
        if (manifest.isFile()) {
            try {
                JSONObject old = new JSONObject(readText(manifest));
                if (sourceHash.equals(old.optString("sourceHash", ""))) {
                    saveManifest(sourceHash, sourceName, total, countCompleted());
                    return;
                }
            } catch (Exception ignored) {
                // A damaged manifest must not destroy completed unit files.
            }
        }
        saveManifest(sourceHash, sourceName, total, countCompleted());
    }

    public synchronized Record get(String id, String sourceHash) {
        File f = new File(unitsDir, safe(id) + ".json");
        if (!f.isFile()) return null;
        try {
            JSONObject o = new JSONObject(readText(f));
            if (!sourceHash.equals(o.optString("sourceHash", ""))) return null;
            return new Record(
                    o.optString("id", id),
                    o.optString("file", ""),
                    sourceHash,
                    o.optString("translation", ""));
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void put(Record record) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("id", record.id);
            o.put("file", record.file);
            o.put("sourceHash", record.sourceHash);
            o.put("translation", record.translation);
        } catch (Exception e) {
            throw new IOException("Không thể tạo trạng thái translation unit.", e);
        }
        atomicWrite(new File(unitsDir, safe(record.id) + ".json"), o.toString());
    }

    public synchronized int countCompleted() {
        File[] files = unitsDir.listFiles((dir, name) -> name.endsWith(".json"));
        return files == null ? 0 : files.length;
    }

    public synchronized void saveManifest(String sourceHash, String sourceName, int total, int done) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("version", 2);
            o.put("sourceHash", sourceHash);
            o.put("sourceName", sourceName);
            o.put("totalUnits", total);
            o.put("completedUnits", done);
            o.put("updatedAt", System.currentTimeMillis());
        } catch (Exception e) {
            throw new IOException("Không thể tạo progress manifest.", e);
        }
        atomicWrite(manifest, o.toString());
    }

    public synchronized String summary() {
        if (!manifest.isFile()) return "Chưa có translation workspace.";
        try {
            JSONObject o = new JSONObject(readText(manifest));
            return "Đã dịch " + o.optInt("completedUnits", countCompleted())
                    + "/" + o.optInt("totalUnits", 0) + " unit";
        } catch (Exception e) {
            return "Đã dịch " + countCompleted() + " unit";
        }
    }

    public static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    public static String sha256(String value) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return hex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void atomicWrite(File target, String text) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Không tạo được thư mục: " + parent);
        }
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            try { ((FileOutputStream) out).getFD().sync(); } catch (Exception ignored) {}
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Không thay thế được file trạng thái: " + target);
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("Không rename được file trạng thái: " + target);
        }
    }
}
