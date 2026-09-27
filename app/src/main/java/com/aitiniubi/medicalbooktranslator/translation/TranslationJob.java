package com.aitiniubi.medicalbooktranslator.translation;

import com.aitiniubi.medicalbooktranslator.epub.EpubBuilder;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.*;import java.util.regex.*;import java.util.zip.*;

public final class TranslationJob {
    public interface Listener { void onProgress(int done,int total); void onDone(File output); void onError(Exception e); }
    private static final Pattern BLOCK=Pattern.compile("<(p|h1|h2|h3|h4|h5|h6|figcaption|caption)\\b([^>]*)>(.*?)</\\1>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private TranslationJob(){}
    public static void run(File source,File output,TranslationConfig cfg,Listener listener){
        new Thread(() -> { try {
            List<String> files=new ArrayList<>(); try(ZipFile z=new ZipFile(source)){Enumeration<? extends ZipEntry> en=z.entries();while(en.hasMoreElements()){String n=en.nextElement().getName();if(n.toLowerCase(Locale.US).endsWith(".xhtml"))files.add(n);}}
            Map<String,String> replacements=new HashMap<>(); int total=0; for(String f:files){try(ZipFile z=new ZipFile(source)){String x=read(z,z.getEntry(f)); total+=count(x);}}
            int[] done={0};
            for(String f:files){String x;try(ZipFile z=new ZipFile(source)){x=read(z,z.getEntry(f));} String context="Medical obstetric ultrasound / fetal medicine chapter. Keep established terminology consistent.";
                Matcher m=BLOCK.matcher(x); StringBuffer sb=new StringBuffer(); while(m.find()){String inner=m.group(3); String plain=stripTags(inner); if(plain.trim().length()<3 || !containsLetter(plain)){m.appendReplacement(sb,Matcher.quoteReplacement(m.group()));continue;}
                    String translated=OpenAICompatibleTranslator.translate(inner,context,cfg); if(translated.startsWith("```")){translated=translated.replaceFirst("^```(?:html|xml)?\\s*"," ").replaceFirst("\\s*```$","").trim();}
                    String rep="<"+m.group(1)+m.group(2)+">"+translated+"</"+m.group(1)+">"; m.appendReplacement(sb,Matcher.quoteReplacement(rep)); done[0]++; listener.onProgress(done[0],Math.max(total,1)); }
                m.appendTail(sb); replacements.put(f,sb.toString());
            }
            EpubBuilder.copyWithReplacements(source,output,replacements); listener.onDone(output);
        }catch(Exception e){listener.onError(e);}}).start();
    }
    private static int count(String x){Matcher m=BLOCK.matcher(x);int c=0;while(m.find()){if(containsLetter(stripTags(m.group(3))))c++;}return c;}
    private static boolean containsLetter(String s){return s.matches("(?s).*\\p{L}.*");}
    private static String stripTags(String s){return s.replaceAll("<[^>]+>"," ").replaceAll("&[a-zA-Z#0-9]+;"," ").trim();}
    private static String read(ZipFile z,ZipEntry e)throws Exception{try(InputStream in=z.getInputStream(e);ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[]b=new byte[16384];int n;while((n=in.read(b))>0)o.write(b,0,n);return o.toString(StandardCharsets.UTF_8.name());}}
}
