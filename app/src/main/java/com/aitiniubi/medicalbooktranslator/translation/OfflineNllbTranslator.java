package com.aitiniubi.medicalbooktranslator.translation;

import android.content.Context;
import java.io.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

public final class OfflineNllbTranslator {
    public static final String ENDPOINT="offline://nllb";
    public static final String MODEL_ID="nllb-600m-q4_0";
    public static final String MODEL_URL="https://huggingface.co/Hosstia/nllb-200-distilled-600m-gguf/resolve/main/nllb-600m-Q4_0.gguf";
    private static final long MODEL_SIZE=472L*1024L*1024L;

    private OfflineNllbTranslator(){}

    public static boolean isInstalled(Context context){
        return modelFile(context).isFile() && modelFile(context).length()>100_000_000L;
    }

    public static File modelFile(Context context){
        return new File(new File(context.getExternalFilesDir("models"),"nllb"),"nllb-600m-Q4_0.gguf");
    }

    public static long installedBytes(Context context){
        File f=modelFile(context); return f.isFile()?f.length():0L;
    }

    public static void ensureModel(Context context, ProgressListener listener)throws Exception{
        File out=modelFile(context);
        if(out.isFile()&&out.length()>100_000_000L)return;
        File dir=out.getParentFile(); if(dir!=null&&!dir.exists()&&!dir.mkdirs())throw new IOException("Không tạo được thư mục model offline.");
        File part=new File(out.getAbsolutePath()+".part");
        long existing=part.isFile()?part.length():0L;
        OkHttpClient client=new OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(10,TimeUnit.MINUTES).build();
        Request.Builder rb=new Request.Builder().url(MODEL_URL);
        if(existing>0)rb.header("Range","bytes="+existing+"-");
        try(Response res=client.newCall(rb.build()).execute()){
            if(!res.isSuccessful()&&res.code()!=206)throw new IOException("Tải model NLLB thất bại HTTP "+res.code());
            long total=res.body()==null?0:res.body().contentLength();
            if(total<=0)total=MODEL_SIZE-existing;
            boolean append=existing>0&&res.code()==206;
            try(InputStream in=res.body().byteStream();OutputStream o=new BufferedOutputStream(new FileOutputStream(part,append))){
                byte[] buf=new byte[1024*1024];long done=append?existing:0;int n;
                while((n=in.read(buf))>0){o.write(buf,0,n);done+=n;if(listener!=null)listener.onProgress(done,append?existing+total:total);}
            }
        }
        if(!part.renameTo(out)){copy(part,out);part.delete();}
        if(out.length()<100_000_000L)throw new IOException("Model NLLB tải về không đầy đủ: "+out.length()+" bytes.");
    }

    public interface ProgressListener{void onProgress(long done,long total);}
    private static void copy(File a,File b)throws IOException{try(InputStream in=new FileInputStream(a);OutputStream out=new FileOutputStream(b)){byte[] z=new byte[1024*1024];int n;while((n=in.read(z))>0)out.write(z,0,n);}}
    
    public static String translate(Context context,String source)throws Exception{
        if(source==null||source.trim().isEmpty())return "";
        if(!isInstalled(context))throw new IOException("Chưa cài model Offline NLLB 600M. Hãy tải model trong Cài đặt Offline AI.");
        File binary=engineFile(context);
        if(!binary.isFile())throw new IOException("APK chưa chứa engine NLLB offline.");
        if(!binary.canExecute())binary.setExecutable(true);
        ProcessBuilder pb=new ProcessBuilder(binary.getAbsolutePath(),modelFile(context).getAbsolutePath(),
                "eng_Latn "+source.replace("\n"," "), "vie_Latn");
        pb.redirectErrorStream(true);
        Process p=pb.start();
        StringBuilder all=new StringBuilder();
        try(BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream(),"UTF-8"))){
            String line;while((line=br.readLine())!=null){if(all.length()>0)all.append("\n");all.append(line);}
        }
        if(!p.waitFor(5,TimeUnit.MINUTES)){p.destroyForcibly();throw new IOException("NLLB offline timeout.");}
        String raw=all.toString().trim();
        if(p.exitValue()!=0)throw new IOException("NLLB offline failed: "+raw);
        return cleanOutput(raw);
    }

    private static File engineFile(Context context)throws IOException{
        File out=new File(context.getFilesDir(),"nllb-simple");
        if(out.isFile()&&out.length()>100_000L)return out;
        try(InputStream in=context.getAssets().open("nllb-simple");
            OutputStream outStream=new BufferedOutputStream(new FileOutputStream(out))){
            byte[] buf=new byte[1024*1024];int n;
            while((n=in.read(buf))>0)outStream.write(buf,0,n);
        }
        if(!out.setExecutable(true,false))throw new IOException("Không cấp quyền chạy engine NLLB.");
        return out;
    }

    private static String cleanOutput(String raw){
        String[] lines=raw.split("\\R");
        StringBuilder best=new StringBuilder();
        for(String s:lines){
            s=s.trim(); if(s.isEmpty())continue;
            if(s.startsWith("llama_")||s.startsWith("ggml_")||s.startsWith("load_")||s.contains("time ="))continue;
            if(s.startsWith("Translation:"))s=s.substring("Translation:".length()).trim();
            best.append(s).append(' ');
        }
        return best.toString().trim();
    }
}