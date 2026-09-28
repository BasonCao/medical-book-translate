package com.aitiniubi.medicalbooktranslator.translation;

import com.aitiniubi.medicalbooktranslator.epub.EpubBuilder;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

public final class TranslationJob {
    public interface Listener {
        void onProgress(int done,int total,int batch,String info);
        void onDone(File output);
        void onPaused(File draft,int done,int total,Exception reason);
        void onError(Exception e);
    }

    private static final Pattern BLOCK=Pattern.compile(
        "<(p|h1|h2|h3|h4|h5|h6|figcaption|caption|th|td|li)\\b([^>]*)>(.*?)</\\1>",
        Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final int MAX_BATCH_UNITS=8;
    private static final int MAX_BATCH_CHARS=12000;

    private TranslationJob(){}

    public static void run(File source,File output,File workspace,
                           List<TranslationRouter.Provider> providers,Listener listener){
        new Thread(()->{
            try{
                if(source==null||!source.isFile())throw new IOException("Không tìm thấy EPUB nguồn.");
                if(providers==null||providers.isEmpty())throw new IOException("Chưa cấu hình AI provider nào có API key.");

                List<Unit> units=extractUnits(source);
                String sourceHash=TranslationStateStore.sha256(source);
                int total=0;
                for(Unit u:units) if(u.translatable) total++;
                TranslationStateStore store=new TranslationStateStore(workspace);
                store.initialize(sourceHash,source.getName(),total);

                Map<String,String> doneMap=new LinkedHashMap<>();
                int done=0;
                for(Unit u:units){
                    TranslationStateStore.Record r=store.get(u.id,u.sourceHash);
                    if(r!=null&&!blank(r.translation)){doneMap.put(u.id,r.translation);done++;}
                }
                listener.onProgress(done,total,0,"Khôi phục draft: "+done+"/"+total);

                List<Unit> pending=new ArrayList<>();
                for(Unit u:units)if(!doneMap.containsKey(u.id)&&u.translatable)pending.add(u);

                if(pending.isEmpty()){
                    File draft=new File(workspace,"translated-current.epub");
                    rebuild(source,draft,units,doneMap);copyFile(draft,output);listener.onDone(output);return;
                }

                int batchNo=0;
                for(int start=0;start<pending.size();){
                    List<Unit> batch=makeBatch(pending,start);batchNo++;
                    String context="Medical obstetric ultrasound / fetal medicine textbook. Translate English to professional Vietnamese. Preserve medical terminology, abbreviations, numbers, units, citations and inline HTML/XML markup.";
                    try{
                        Map<String,String> got=translateBatch(batch,context,providers);
                        for(Unit u:batch){
                            String t=got.get(u.id);
                            if(blank(t))t=TranslationRouter.translate(u.inner,context+" Return only the translated HTML fragment.",providers);
                            t=clean(t);
                            if(blank(t))throw new IOException("AI trả về bản dịch rỗng cho unit "+u.id);
                            store.put(new TranslationStateStore.Record(u.id,u.file,u.sourceHash,t));
                            doneMap.put(u.id,t);done++;
                            listener.onProgress(done,total,batchNo,"Đã lưu batch "+batchNo);
                        }
                        store.saveManifest(sourceHash,source.getName(),total,done);
                        File draft=new File(workspace,"translated-current.epub");
                        rebuild(source,draft,units,doneMap);
                        start+=batch.size();
                    }catch(Exception e){
                        store.saveManifest(sourceHash,source.getName(),units.size(),done);
                        File draft=new File(workspace,"translated-current.epub");
                        rebuild(source,draft,units,doneMap);
                        listener.onPaused(draft,done,total,e);return;
                    }
                }

                File finalDraft=new File(workspace,"translated-current.epub");
                rebuild(source,finalDraft,units,doneMap);copyFile(finalDraft,output);
                store.saveManifest(sourceHash,source.getName(),units.size(),done);
                listener.onDone(output);
            }catch(Exception e){listener.onError(e);}
        },"epub-translation").start();
    }

    private static List<Unit> extractUnits(File source)throws Exception{
        List<Unit> out=new ArrayList<>();
        try(ZipFile zip=new ZipFile(source)){
            Enumeration<? extends ZipEntry> en=zip.entries();
            while(en.hasMoreElements()){
                ZipEntry e=en.nextElement();String name=e.getName();
                if(!name.toLowerCase(Locale.US).endsWith(".xhtml"))continue;
                String x=read(zip,e);Matcher m=BLOCK.matcher(x);int ordinal=0;
                while(m.find()){
                    String tag=m.group(1),attrs=m.group(2),inner=m.group(3);
                    String plain=strip(inner);boolean tr=shouldTranslate(attrs,plain);
                    String sh=TranslationStateStore.sha256(inner);
                    String id=TranslationStateStore.sha256(name+"|"+ordinal+"|"+sh);
                    out.add(new Unit(id,name,ordinal,tag,attrs,inner,sh,tr));ordinal++;
                }
            }
        }
        return out;
    }

    private static boolean shouldTranslate(String attrs,String plain){
        if(plain.length()<2||!plain.matches("(?s).*\\p{L}.*"))return false;
        String a=attrs==null?"":attrs.toLowerCase(Locale.US);
        if(a.matches(".*\\b(reflist|references|bibliography|bib|doi|url)\\b.*"))return false;
        String compact=plain.replaceAll("[^A-Za-z0-9-]","");
        if(compact.length()<=10&&compact.matches("[A-Za-z][A-Za-z0-9-]+"))return false;
        boolean vi=plain.matches("(?s).*?[ÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠàáâãèéêìíòóôõùúăđĩũơƯưẠ-ỹ].*");
        if(vi&&englishWords(plain)<Math.max(3,vietnameseWords(plain)*2))return false;
        return !plain.matches("[\\s\\d.,:;()\\[\\]/%+-]+");
    }

    private static int englishWords(String s){
        Matcher m=Pattern.compile("\\b[A-Za-z]{3,}\\b").matcher(s);int n=0;while(m.find())n++;return n;
    }
    private static int vietnameseWords(String s){
        Matcher m=Pattern.compile("[À-ỹĐđ]{2,}").matcher(s);int n=0;while(m.find())n++;return n;
    }

    private static List<Unit> makeBatch(List<Unit> p,int start){
        List<Unit> b=new ArrayList<>();int chars=0;
        for(int i=start;i<p.size()&&b.size()<MAX_BATCH_UNITS;i++){
            Unit u=p.get(i);int cost=u.inner.length()+180;
            if(!b.isEmpty()&&chars+cost>MAX_BATCH_CHARS)break;
            b.add(u);chars+=cost;
        }
        return b;
    }

    private static Map<String,String> translateBatch(List<Unit> batch,String context,List<TranslationRouter.Provider> providers)throws Exception{
        StringBuilder s=new StringBuilder();
        s.append("Return ONLY a JSON array. Each object has exactly id and translation. Keep IDs unchanged. ");
        s.append("Translate only visible English prose. Preserve every HTML/XML tag and attribute. No Markdown.\n");
        for(Unit u:batch){
            s.append("{\"id\":\"").append(json(u.id)).append("\",\"source\":\"").append(json(u.inner)).append("\"}\n");
        }
        String response=TranslationRouter.translate(s.toString(),context,providers).trim();
        String fence=String.valueOf((char)96)+String.valueOf((char)96)+String.valueOf((char)96);
        if(response.startsWith(fence))response=response.replaceFirst("^"+fence+"(?:json)?\\s*","").replaceFirst("\\s*"+fence+"$","");
        int a=response.indexOf('['),b=response.lastIndexOf(']');
        if(a<0||b<=a)throw new IOException("AI không trả về JSON batch hợp lệ.");
        JSONArray arr=new JSONArray(response.substring(a,b+1));Map<String,String> out=new HashMap<>();
        for(int i=0;i<arr.length();i++){
            JSONObject o=arr.optJSONObject(i);if(o==null)continue;
            String id=o.optString("id",""),t=o.optString("translation","");
            if(!id.isEmpty()&&!t.isEmpty())out.put(id,t);
        }
        return out;
    }

    private static void rebuild(File source,File output,List<Unit> units,Map<String,String> done)throws Exception{
        Map<String,List<Rep>> byFile=new LinkedHashMap<>();
        for(Unit u:units){String t=done.get(u.id);if(!blank(t))byFile.computeIfAbsent(u.file,k->new ArrayList<>()).add(new Rep(u,t));}
        Map<String,String> replacements=new HashMap<>();
        try(ZipFile zip=new ZipFile(source)){
            for(Map.Entry<String,List<Rep>> e:byFile.entrySet()){
                ZipEntry ze=zip.getEntry(e.getKey());if(ze==null)continue;
                String x=read(zip,ze);List<Rep> rs=e.getValue();
                rs.sort((a,b)->Integer.compare(b.u.ordinal,a.u.ordinal));
                for(Rep r:rs){
                    Matcher m=BLOCK.matcher(x);int i=0;boolean replaced=false;
                    while(m.find()){
                        if(i==r.u.ordinal){
                            String rep="<"+m.group(1)+m.group(2)+">"+r.t+"</"+m.group(1)+">";
                            x=x.substring(0,m.start())+rep+x.substring(m.end());replaced=true;break;
                        }
                        i++;
                    }
                    if(!replaced)throw new IOException("Không tìm thấy translation unit "+r.u.id+" khi rebuild.");
                }
                replacements.put(e.getKey(),x);
            }
        }
        EpubBuilder.copyWithReplacements(source,output,replacements);
    }

    private static String clean(String s){
        String t=s==null?"":s.trim();
        String fence=String.valueOf((char)96)+String.valueOf((char)96)+String.valueOf((char)96);
        if(t.startsWith(fence))t=t.replaceFirst("^"+fence+"(?:html|xml)?\\s*","").replaceFirst("\\s*"+fence+"$","");
        int a=t.indexOf("<"),b=t.lastIndexOf(">");
        return a>0&&b>a?t.substring(a,b+1).trim():t;
    }
    private static String json(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
    private static String strip(String s){return s.replaceAll("<[^>]+>"," ").replaceAll("&[a-zA-Z#0-9]+;"," ").replaceAll("\\s+"," ").trim();}
    private static boolean blank(String s){return s==null||s.trim().isEmpty();}
    private static String read(ZipFile z,ZipEntry e)throws Exception{try(InputStream in=z.getInputStream(e);ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))>0)o.write(b,0,n);return o.toString(StandardCharsets.UTF_8.name());}}
    private static void copyFile(File s,File t)throws IOException{File p=t.getParentFile();if(p!=null&&!p.exists())p.mkdirs();try(InputStream in=new FileInputStream(s);OutputStream o=new FileOutputStream(t)){byte[] b=new byte[16384];int n;while((n=in.read(b))>0)o.write(b,0,n);}}

    private static final class Unit{
        final String id,file,tag,attrs,inner,sourceHash;final int ordinal;final boolean translatable;
        Unit(String id,String file,int ordinal,String tag,String attrs,String inner,String sh,boolean tr){this.id=id;this.file=file;this.ordinal=ordinal;this.tag=tag;this.attrs=attrs;this.inner=inner;this.sourceHash=sh;this.translatable=tr;}
    }
    private static final class Rep{final Unit u;final String t;Rep(Unit u,String t){this.u=u;this.t=t;}}
}
