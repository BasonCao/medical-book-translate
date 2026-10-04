package com.aitiniubi.medicalbooktranslator.translation;

import android.content.Context;
import java.io.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.HttpUrl;
import okhttp3.dnsoverhttps.DnsOverHttps;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

/** Manages the optional on-device NLLB-200 distilled 600M Q4_0 model. */
public final class OfflineModelManager {
    public static final String MODEL_FILE = "nllb-600m-Q4_0.gguf";
    public static final String BINARY_FILE = "nllb-simple";
    public static final String MODEL_URL = "https://huggingface.co/Hosstia/nllb-200-distilled-600m-gguf/resolve/main/nllb-600m-Q4_0.gguf";
    private static final String MODEL_URL_FALLBACK = "https://hf-mirror.com/Hosstia/nllb-200-distilled-600m-gguf/resolve/main/nllb-600m-Q4_0.gguf";
    private static final long MIN_MODEL_BYTES = 450L * 1024L * 1024L;
    private OfflineModelManager() {}
    public interface Progress { void onProgress(long done, long total); }
    public static File root(Context c) { return new File(c.getFilesDir(), "offline-model"); }
    public static File model(Context c) { return new File(root(c), MODEL_FILE); }
    public static File binary(Context c) { return new File(root(c), BINARY_FILE); }
    private static final String BINARY_ASSET = "offline-engine/nllb-simple";
    private static final String MODEL_ASSET = "offline-model/nllb-600m-Q4_0.gguf";
    public static boolean isModelReady(Context c) { File f=model(c); return f.isFile() && f.length()>=MIN_MODEL_BYTES; }
    public static boolean isReady(Context c) { return isModelReady(c) && ensureBinary(c); }
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

    public static void downloadModel(Context c, Progress progress) throws Exception {
        File dir=root(c); if(!dir.exists()&&!dir.mkdirs()) throw new IOException("Không tạo được thư mục model offline.");
        if(copyBundledModel(c, progress)) return;
        File tmp=new File(dir,MODEL_FILE+".part"), dst=model(c);
        OkHttpClient bootstrap=new OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(180,TimeUnit.SECONDS).writeTimeout(180,TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
        DnsOverHttps doh=new DnsOverHttps.Builder().client(bootstrap).url(HttpUrl.parse("https://cloudflare-dns.com/dns-query")).bootstrapDnsHosts(ip("1.1.1.1"),ip("1.0.0.1"),ip("2606:4700:4700::1111"),ip("2606:4700:4700::1001")).includeIPv6(true).build();
        OkHttpClient client=bootstrap.newBuilder().dns(doh).followRedirects(true).followSslRedirects(true).build();
        IOException last=null;
        String[] urls=new String[]{MODEL_URL,MODEL_URL_FALLBACK};
        for(String url:urls){
            tmp.delete();
            Request req=new Request.Builder().url(url).header("User-Agent","MedBook-Dich-AI/1.13.1").header("Accept","application/octet-stream").build();
            try(Response res=client.newCall(req).execute()){
                if(!res.isSuccessful()||res.body()==null){ last=new IOException("HTTP "+res.code()+" từ "+url); continue; }
                long total=res.body().contentLength();
                try(InputStream in=res.body().byteStream();OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp))){
                    byte[] buf=new byte[1024*1024]; long done=0; int n;
                    while((n=in.read(buf))>0){out.write(buf,0,n);done+=n;if(progress!=null)progress.onProgress(done,total);}
                }
                if(tmp.length()>=MIN_MODEL_BYTES) break;
                last=new IOException("Model tải về quá nhỏ: "+tmp.length()+" bytes từ "+url);
            } catch(IOException e){ last=e; }
        }
        if(tmp.length()<MIN_MODEL_BYTES){
            tmp.delete();
            throw new IOException("Tải model NLLB thất bại. Nguồn chính và nguồn dự phòng đều không khả dụng. "+(last!=null?last.getMessage():""));
        }
        if(tmp.length()<MIN_MODEL_BYTES){tmp.delete();throw new IOException("Model tải về không đầy đủ: "+tmp.length()+" bytes");}
        if(dst.exists()&&!dst.delete())throw new IOException("Không thay thế được model cũ.");
        if(!tmp.renameTo(dst))throw new IOException("Không thể hoàn tất cài model offline.");
    }
    private static boolean copyBundledModel(Context c, Progress progress) {
        File dst=model(c);
        File tmp=new File(root(c),MODEL_FILE+".asset.part");
        try(InputStream in=c.getAssets().open(MODEL_ASSET);
            OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp))) {
            byte[] buf=new byte[1024*1024];
            long done=0;
            int n;
            while((n=in.read(buf))>0) {
                out.write(buf,0,n);
                done+=n;
                if(progress!=null) progress.onProgress(done,MIN_MODEL_BYTES);
            }
        } catch(IOException e) {
            tmp.delete();
            return false;
        }
        if(tmp.length()<MIN_MODEL_BYTES) {
            tmp.delete();
            return false;
        }
        if(dst.exists()&&!dst.delete()) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(dst);
    }

    private static InetAddress ip(String value){ try{return InetAddress.getByName(value);}catch(UnknownHostException e){throw new IllegalStateException("Invalid DNS bootstrap address: "+value,e);} }
}
