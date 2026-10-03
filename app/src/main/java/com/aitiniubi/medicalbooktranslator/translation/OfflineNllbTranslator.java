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
        int a=prompt.indexOf('['),b=prompt.lastIndexOf(']');
        if(a<0||b<=a)throw new IOException("Offline NLLB nhận batch không hợp lệ.");
        JSONArray in=new JSONArray(prompt.substring(a,b+1)),out=new JSONArray();
        for(int i=0;i<in.length();i++){
            JSONObject item=in.optJSONObject(i);if(item==null)continue;
            String id=item.optString("id",""),source=item.optString("source","");
            if(id.isEmpty())continue;
            String translated=translatePreservingMarkup(context,source,OfflineModelManager.model(context));
            JSONObject o=new JSONObject();o.put("id",id);o.put("translation",translated);out.put(o);
        }
        return out.toString();
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
