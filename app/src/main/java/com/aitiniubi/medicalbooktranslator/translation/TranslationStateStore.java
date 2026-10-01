package com.aitiniubi.medicalbooktranslator.translation;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class TranslationStateStore {
    public static final class Record {
        public String id, file, sourceHash, translation, status;
        public int attempts;
        public Record(String id,String file,String sourceHash,String translation){
            this(id,file,sourceHash,translation,"DONE",0);
        }
        public Record(String id,String file,String sourceHash,String translation,String status,int attempts){
            this.id=id;this.file=file;this.sourceHash=sourceHash;this.translation=translation;
            this.status=status;this.attempts=attempts;
        }
    }
    private final File root,unitsDir,manifest;
    public TranslationStateStore(File root){
        this.root=root;this.unitsDir=new File(root,"units");this.manifest=new File(root,"progress.json");
        if(!unitsDir.exists())unitsDir.mkdirs();
    }
    public synchronized void initialize(String sourceHash,String sourceName,int total)throws IOException{
        if(manifest.isFile()){
            try{
                JSONObject old=new JSONObject(readText(manifest));
                if(sourceHash.equals(old.optString("sourceHash",""))){
                    saveManifest(sourceHash,sourceName,total,countCompleted());return;
                }
            }catch(Exception ignored){}
        }
        saveManifest(sourceHash,sourceName,total,countCompleted());
    }
    public synchronized Record get(String id,String sourceHash){
        File f=new File(unitsDir,safe(id)+".json");if(!f.isFile())return null;
        try{
            JSONObject o=new JSONObject(readText(f));
            if(!sourceHash.equals(o.optString("sourceHash","")))return null;
            return new Record(o.optString("id",id),o.optString("file",""),sourceHash,
                    o.optString("translation",""),o.optString("status","DONE"),o.optInt("attempts",0));
        }catch(Exception e){return null;}
    }
    public synchronized void put(Record r)throws IOException{
        JSONObject o=new JSONObject();
        try{
            o.put("id",r.id);o.put("file",r.file);o.put("sourceHash",r.sourceHash);
            o.put("translation",r.translation);o.put("status",r.status);o.put("attempts",r.attempts);
        }catch(Exception e){throw new IOException("Không thể tạo trạng thái translation unit.",e);}
        atomicWrite(new File(unitsDir,safe(r.id)+".json"),o.toString());
    }
    public synchronized int countCompleted(){
        File[] fs=unitsDir.listFiles((d,n)->n.endsWith(".json"));return fs==null?0:fs.length;
    }
    public synchronized void saveManifest(String sourceHash,String sourceName,int total,int done)throws IOException{
        JSONObject o=new JSONObject();
        try{
            o.put("version",3);o.put("sourceHash",sourceHash);o.put("sourceName",sourceName);
            o.put("totalUnits",total);o.put("completedUnits",done);o.put("updatedAt",System.currentTimeMillis());
        }catch(Exception e){throw new IOException("Không thể tạo progress manifest.",e);}
        atomicWrite(manifest,o.toString());
    }
    public synchronized String summary(){
        if(!manifest.isFile())return "Chưa có translation workspace.";
        try{JSONObject o=new JSONObject(readText(manifest));
            return "Đã dịch "+o.optInt("completedUnits",countCompleted())+"/"+o.optInt("totalUnits",0)+" unit";
        }catch(Exception e){return "Đã dịch "+countCompleted()+" unit";}
    }
    public static String sha256(File file)throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new BufferedInputStream(new FileInputStream(file))){
            byte[] b=new byte[32768];int n;while((n=in.read(b))>0)md.update(b,0,n);
        }return hex(md.digest());
    }
    public static String sha256(String value)throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256");return hex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static String hex(byte[] bytes){
        StringBuilder out=new StringBuilder(bytes.length*2);
        for(byte b:bytes)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();
    }
    private static String safe(String id){return id.replaceAll("[^A-Za-z0-9._-]","_");}
    private static String readText(File f)throws IOException{
        try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static void atomicWrite(File target,String text)throws IOException{
        File p=target.getParentFile();if(p!=null&&!p.exists()&&!p.mkdirs())throw new IOException("Không tạo được thư mục: "+p);
        File tmp=new File(target.getAbsolutePath()+".tmp");
        try(OutputStream out=new FileOutputStream(tmp)){out.write(text.getBytes(StandardCharsets.UTF_8));out.flush();try{((FileOutputStream)out).getFD().sync();}catch(Exception ignored){}}
        if(target.exists()&&!target.delete())throw new IOException("Không thay thế được file trạng thái: "+target);
        if(!tmp.renameTo(target))throw new IOException("Không rename được file trạng thái: "+target);
    }
}