package com.aitiniubi.medicalbooktranslator.translation;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Executes the bundled ARM64 nllb-simple binary completely on-device. */
public final class OfflineNllbTranslator {
    private static final Pattern MARKUP=Pattern.compile("<!--.*?-->|<[^>]+>|&(?:#[0-9]+|#x[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]+);",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern LETTER=Pattern.compile(".*\\p{L}.*",Pattern.DOTALL);
    private OfflineNllbTranslator(){}
    public static String translateBatchPrompt(Context context,String prompt,String model)throws Exception{
        if(!OfflineModelManager.isReady(context))throw new IOException("Model offline NLLB chưa được cài đặt.");
        if(prompt==null||prompt.trim().isEmpty())throw new IOException("Offline NLLB nhận prompt rỗng.");

        // JSON path: TranslationJob sends a natural-language instruction followed by
        // one JSON object per line. Recovery/direct requests may also contain a
        // single JSON object embedded after the instruction text.
        List<JSONObject> items=findJsonObjects(prompt);
        if(!items.isEmpty()){
            JSONArray out=new JSONArray();
            for(JSONObject item:items){
                String id=item.optString("id","");
                String source=item.optString("source","");
                if(id.isEmpty()||source.isEmpty())continue;
                String translated=translatePreservingMarkup(context,source,OfflineModelManager.model(context));
                JSONObject o=new JSONObject();o.put("id",id);o.put("translation",translated);out.put(o);
            }
            if(out.length()>0)return out.toString();
        }

        // PDF path: pages are sent as stable [[[UNIT_n]]] markers rather than JSON.
        Pattern marker=Pattern.compile("\\[\\[\\[UNIT_(\\d+)\\]\\]\\]\\s*",
                Pattern.CASE_INSENSITIVE);
        Matcher mm=marker.matcher(prompt);
        List<Integer> ids=new ArrayList<>();
        List<Integer> starts=new ArrayList<>();
        while(mm.find()){ids.add(Integer.parseInt(mm.group(1)));starts.add(mm.end());}
        if(!ids.isEmpty()){
            StringBuilder out=new StringBuilder();
            for(int i=0;i<ids.size();i++){
                int end=i+1<starts.size()?mmStartForNext(marker,prompt,starts.get(i)):prompt.length();
                String source=prompt.substring(starts.get(i),end).trim();
                String translated=translatePreservingMarkup(context,source,OfflineModelManager.model(context));
                if(i>0)out.append("\\n");
                out.append("[[[UNIT_").append(ids.get(i)).append("]]]\\n").append(translated);
            }
            return out.toString();
        }

        // Single-item fallback: accept {"id":"...","source":"..."}.
        try{
            JSONObject item=new JSONObject(prompt.trim());
            String id=item.optString("id","");
            String source=item.optString("source","");
            if(!id.isEmpty()&&!source.isEmpty()){
                String translated=translatePreservingMarkup(context,source,OfflineModelManager.model(context));
                JSONObject out=new JSONObject();out.put("id",id);out.put("translation",translated);
                return new JSONArray().put(out).toString();
            }
        }catch(Exception ignored){}

        throw new IOException("Offline NLLB nhận batch không hợp lệ: không tìm thấy JSON items hoặc UNIT markers.");
    }

    private static List<JSONObject> findJsonObjects(String prompt){
        List<JSONObject> out=new ArrayList<>();
        if(prompt==null)return out;

        // Normal EPUB batch: one JSON object per line after the instruction text.
        String[] lines=prompt.split("\\R");
        for(String line:lines){
            String x=line.trim();
            if(x.isEmpty())continue;
            int start=x.indexOf('{'),end=x.lastIndexOf('}');
            if(start>=0&&end>start){
                try{
                    JSONObject o=new JSONObject(x.substring(start,end+1));
                    if(o.has("id")&&o.has("source"))out.add(o);
                }catch(Exception ignored){}
            }
        }
        if(!out.isEmpty())return out;

        // Direct/recovery request: locate an embedded JSON object while
        // respecting quoted strings and escaped quotes.
        int depth=0,start=-1;boolean quoted=false,escaped=false;
        for(int i=0;i<prompt.length();i++){
            char ch=prompt.charAt(i);
            if(quoted){
                if(escaped)escaped=false;
                else if(ch=='\\\\')escaped=true;
                else if(ch=='"')quoted=false;
                continue;
            }
            if(ch=='"'){quoted=true;continue;}
            if(ch=='{'){
                if(depth==0)start=i;
                depth++;
            }else if(ch=='}'&&depth>0){
                depth--;
                if(depth==0&&start>=0){
                    try{
                        JSONObject o=new JSONObject(prompt.substring(start,i+1));
                        if(o.has("id")&&o.has("source"))out.add(o);
                    }catch(Exception ignored){}
                    start=-1;
                }
            }
        }
        return out;
    }

    private static int mmStartForNext(Pattern marker,String prompt,int after){
        Matcher m=marker.matcher(prompt);
        if(m.find(after))return m.start();
        return prompt.length();
    }

    private static String translatePreservingMarkup(Context c,String html,File model)throws Exception{
        Matcher m=MARKUP.matcher(html);StringBuilder out=new StringBuilder();int pos=0;
        while(m.find()){if(m.start()>pos)out.append(translateTextChunk(c,html.substring(pos,m.start()),model));out.append(m.group());pos=m.end();}
        if(pos<html.length())out.append(translateTextChunk(c,html.substring(pos),model));
        return out.toString();
    }
    private static String translateTextChunk(Context c,String text,File model)throws Exception{
        if(text==null||text.trim().isEmpty()||!LETTER.matcher(text).matches())return text;
        List<String> chunks=splitLongText(text);StringBuilder out=new StringBuilder();
        for(String chunk:chunks){if(chunk.trim().isEmpty()||!LETTER.matcher(chunk).matches())out.append(chunk);else out.append(run(c,model,chunk));}
        return out.toString();
    }
    private static List<String> splitLongText(String text){
        if(text.length()<=1400)return Collections.singletonList(text);
        List<String> out=new ArrayList<>();String[] sentences=text.split("(?<=[.!?;:])\\s+");StringBuilder cur=new StringBuilder();
        for(String s:sentences){if(cur.length()>0&&cur.length()+s.length()+1>1400){out.add(cur.toString());cur.setLength(0);}if(cur.length()>0)cur.append(' ');cur.append(s);}
        if(cur.length()>0)out.add(cur.toString());if(out.isEmpty())out.add(text);return out;
    }
    private static String run(Context c,File model,String text)throws Exception{
        File exe=OfflineModelManager.binary(c);if(!exe.isFile()||!exe.canExecute())throw new IOException("NLLB engine chưa sẵn sàng.");
        ProcessBuilder pb=new ProcessBuilder(exe.getAbsolutePath(),"-m",model.getAbsolutePath(),"-p",text,"-n","200","-t","4");pb.redirectErrorStream(true);
        Process p=pb.start();ByteArrayOutputStream buf=new ByteArrayOutputStream();
        try(InputStream in=p.getInputStream()){
            byte[] b=new byte[8192];int n;long deadline=System.currentTimeMillis()+120000L;
            while((n=in.read(b))>0){buf.write(b,0,n);if(System.currentTimeMillis()>deadline){p.destroyForcibly();throw new IOException("NLLB offline timeout (>120s).");}}
        }
        int code=p.waitFor();String raw=buf.toString(StandardCharsets.UTF_8.name()).trim();
        if(code!=0)throw new IOException("NLLB offline engine exit="+code+"\n"+tail(raw));
        String translated=extractTranslation(raw);if(translated.isEmpty())throw new IOException("NLLB offline không trả về bản dịch.\n"+tail(raw));return translated;
    }
    private static String extractTranslation(String raw){
        String[] lines=raw.split("\\R");String candidate="";
        for(String line:lines){String s=line.trim();if(s.isEmpty())continue;String lower=s.toLowerCase(Locale.US);
            if(lower.startsWith("main:")||lower.startsWith("load:")||lower.startsWith("decode:")||lower.startsWith("llama_")||lower.startsWith("system_info")||lower.startsWith("model"))continue;
            int colon=s.indexOf(':');if(colon>0&&lower.substring(0,colon).contains("translation"))s=s.substring(colon+1).trim();
            if(!s.isEmpty())candidate=s;
        }return candidate;
    }
    private static String tail(String s){return s.length()<=1000?s:s.substring(s.length()-1000);}
}
