package com.aitiniubi.medicalbooktranslator.pdf;

import android.content.Context;
import com.aitiniubi.medicalbooktranslator.translation.TranslationRouter;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.util.*;

public final class PdfTranslationJob {
    public interface Listener {
        void onProgress(int done,int total,int page,String info);
        void onDone(File output);
        void onPaused(File draft,int done,int total,Exception reason);
        void onError(Exception e);
    }
    private PdfTranslationJob(){}

    public static void run(Context context,File source,File output,File workspace,List<TranslationRouter.Provider> providers,Listener listener){
        new Thread(()->{
            try{
                PDFBoxResourceLoader.init(context.getApplicationContext());
                if(source==null||!source.isFile())throw new IOException("Không tìm thấy PDF nguồn.");
                if(providers==null||providers.isEmpty())throw new IOException("Chưa cấu hình AI provider nào có API key.");

                File stateFile=new File(workspace,"pdf-progress.properties");
                Properties state=new Properties();
                if(stateFile.isFile())try(FileInputStream in=new FileInputStream(stateFile)){state.load(in);}

                int total;
                try(PDDocument doc=PDDocument.load(source)){
                    total=doc.getNumberOfPages();
                    if(total<=0)throw new IOException("PDF không có trang.");
                    PDFTextStripper check=new PDFTextStripper();
                    for(int i=1;i<=total;i++){
                        check.setStartPage(i);check.setEndPage(i);
                        String s=check.getText(doc);
                        if(s==null||s.trim().isEmpty()){
                            throw new IOException("PDF scan/OCR không được hỗ trợ: trang "+i+" không có text layer.");
                        }
                    }
                }

                Map<Integer,String> translations=new HashMap<>();
                int done=0;
                for(int i=1;i<=total;i++){
                    String t=state.getProperty("page."+i,"");
                    if(!t.trim().isEmpty()){translations.put(i,t);done++;}
                }
                listener.onProgress(done,total,0,"Khôi phục tiến độ PDF: "+done+"/"+total);

                // Extract remaining pages on one PDFBox thread, then run the
                // expensive AI requests concurrently. PDFBox itself is not shared
                // across worker threads.
                Map<Integer,String> sourcePages=new HashMap<>();
                try(PDDocument doc=PDDocument.load(source)){
                    PDFTextStripper stripper=new PDFTextStripper();
                    for(int page=1;page<=total;page++){
                        if(translations.containsKey(page))continue;
                        stripper.setStartPage(page);stripper.setEndPage(page);
                        String sourceText=stripper.getText(doc);
                        if(sourceText==null||sourceText.trim().isEmpty()){
                            throw new IOException("Trang "+page+" không có text layer. PDF scan/OCR không được hỗ trợ.");
                        }
                        sourcePages.put(page,sourceText);
                    }
                }

                // V1.7 was strictly sequential (one AI request at a time).
                // V1.8 keeps a bounded pool of three requests to improve throughput
                // without opening an excessive number of connections on Android.
                final int parallelism=3;
                java.util.concurrent.ExecutorService pool=
                        java.util.concurrent.Executors.newFixedThreadPool(parallelism);
                java.util.concurrent.CompletionService<PageResult> completion=
                        new java.util.concurrent.ExecutorCompletionService<>(pool);
                int submitted=0;
                for(Map.Entry<Integer,String> entry:sourcePages.entrySet()){
                    final int page=entry.getKey();
                    final String sourceText=entry.getValue();
                    completion.submit(()->{
                        String prompt="Translate this medical textbook page from English to professional Vietnamese. Preserve medical terminology, abbreviations, numbers, units, citations, formulas, gene/drug names and paragraph breaks. Return plain text only.\n\n"+sourceText;
                        String translated=TranslationRouter.translate(prompt,"Medical obstetric ultrasound / fetal medicine textbook. Do not invent, omit, or summarize information.",providers);
                        if(translated==null||translated.trim().isEmpty())throw new IOException("AI trả về bản dịch rỗng ở trang "+page);
                        return new PageResult(page,translated.trim());
                    });
                    submitted++;
                }

                try{
                    for(int n=0;n<submitted;n++){
                        PageResult result=completion.take().get();
                        translations.put(result.page,result.text);
                        synchronized(state){
                            state.setProperty("page."+result.page,result.text);
                            save(state,stateFile);
                        }
                        done++;
                        listener.onProgress(done,total,result.page,
                                "Đã dịch PDF "+done+"/"+total+" trang | tối đa "+parallelism+" trang song song");
                    }
                }catch(java.util.concurrent.ExecutionException e){
                    Throwable cause=e.getCause();
                    if(cause instanceof Exception)throw (Exception)cause;
                    throw new IOException("Lỗi dịch PDF.",cause);
                }finally{
                    pool.shutdownNow();
                }

                File draft=new File(workspace,"translated-current.pdf");
                buildReflowPdf(context,source,draft,translations);
                copyFile(draft,output);
                listener.onDone(output);
            }catch(Exception e){
                listener.onError(e);
            }
        },"pdf-translation").start();
    }

    private static void buildReflowPdf(Context context,File source,File output,Map<Integer,String> translations)throws Exception{
        PDFBoxResourceLoader.init(context.getApplicationContext());
        try(PDDocument src=PDDocument.load(source);PDDocument out=new PDDocument()){
            List<FontSlot> fonts=loadFonts(out);
            if(fonts.isEmpty()){
                throw new IOException("Thiết bị không có font TTF Unicode để tạo PDF tiếng Việt.");
            }

            // The primary font is used for normal Vietnamese text; symbol/math fonts
            // are selected per code point so one unsupported glyph cannot abort export.
            for(int i=0;i<src.getNumberOfPages();i++){
                PDRectangle box=src.getPage(i).getMediaBox();
                PDPage page=new PDPage(new PDRectangle(box.getWidth(),box.getHeight()));
                out.addPage(page);
                String text=sanitizeForPdf(translations.get(i+1));
                try(PDPageContentStream cs=new PDPageContentStream(out,page)){
                    cs.beginText();
                    cs.setLeading(14);
                    cs.newLineAtOffset(42,box.getHeight()-48);
                    float max=box.getWidth()-84;
                    for(String para:text.replace("\\r","").split("\\n",-1)){
                        for(String line:wrap(para,fonts,10,max)){
                            showTextWithFallback(cs,line,fonts,10);
                            cs.newLine();
                        }
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            out.save(output);
        }
    }

    private static List<FontSlot> loadFonts(PDDocument out)throws IOException{
        List<FontSlot> fonts=new ArrayList<>();

        // Keep Roboto/Noto Sans first for Vietnamese and Latin text.
        addFontIfPresent(out,fonts,"Roboto",
                "/system/fonts/Roboto-Regular.ttf");
        addFontIfPresent(out,fonts,"NotoSans",
                "/system/fonts/NotoSans-Regular.ttf");

        // Android devices commonly ship these dedicated Unicode symbol fonts.
        // They cover characters such as U+25E6 (◦), mathematical symbols and arrows.
        addFontIfPresent(out,fonts,"NotoSansSymbols",
                "/system/fonts/NotoSansSymbols-Regular.ttf");
        addFontIfPresent(out,fonts,"NotoSansSymbols2",
                "/system/fonts/NotoSansSymbols2-Regular.ttf");
        addFontIfPresent(out,fonts,"NotoSansMath",
                "/system/fonts/NotoSansMath-Regular.ttf");
        addFontIfPresent(out,fonts,"DroidSansFallback",
                "/system/fonts/DroidSansFallback.ttf");

        return fonts;
    }

    private static void addFontIfPresent(PDDocument out,List<FontSlot> fonts,
                                         String name,String path)throws IOException{
        File file=new File(path);
        if(!file.isFile())return;
        try(FileInputStream in=new FileInputStream(file)){
            fonts.add(new FontSlot(name,PDType0Font.load(out,in,true)));
        }catch(Exception ignored){
            // A device may expose a font path that PDFBox cannot parse. Continue
            // with the remaining fonts instead of failing the whole PDF export.
        }
    }

    private static String sanitizeForPdf(String text){
        if(text==null||text.isEmpty())return "";
        StringBuilder out=new StringBuilder(text.length());
        for(int i=0;i<text.length();){
            int cp=text.codePointAt(i);i+=Character.charCount(cp);
            boolean privateUse=(cp>=0xE000&&cp<=0xF8FF)
                    ||(cp>=0xF0000&&cp<=0xFFFFD)
                    ||(cp>=0x100000&&cp<=0x10FFFD);
            if(privateUse||cp==0xFFFD)continue;
            if(Character.isISOControl(cp)&&cp!=10&&cp!=9&&cp!=13)continue;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static List<String> wrap(String text,List<FontSlot> fonts,
                                      float size,float max)throws IOException{
        List<String> lines=new ArrayList<>();
        if(text==null||text.isEmpty()){
            lines.add("");
            return lines;
        }

        StringBuilder line=new StringBuilder();
        for(String word:text.trim().split("\\s+")){
            String candidate=line.length()==0?word:line+" "+word;
            if(measureWidth(candidate,fonts,size)>max&&line.length()>0){
                lines.add(line.toString());
                line=new StringBuilder(word);
            }else{
                line=new StringBuilder(candidate);
            }
        }
        if(line.length()>0)lines.add(line.toString());
        return lines;
    }

    private static float measureWidth(String text,List<FontSlot> fonts,
                                      float size)throws IOException{
        if(text==null||text.isEmpty())return 0f;
        float width=0f;
        int i=0;
        while(i<text.length()){
            int cp=text.codePointAt(i);
            int next=i+Character.charCount(cp);
            FontSlot font=findFont(cp,fonts);
            String glyphText=text.substring(i,next);
            if(font!=null){
                width+=font.font.getStringWidth(glyphText)/1000f*size;
            }else{
                // The fallback replacement is a single ASCII glyph supported by
                // every normal text font, preventing PDFBox from throwing.
                FontSlot primary=fonts.get(0);
                width+=primary.font.getStringWidth("?")/1000f*size;
            }
            i=next;
        }
        return width;
    }

    private static void showTextWithFallback(PDPageContentStream cs,String text,
                                              List<FontSlot> fonts,float size)
            throws IOException{
        if(text==null||text.isEmpty())return;

        FontSlot active=null;
        StringBuilder run=new StringBuilder();

        int i=0;
        while(i<text.length()){
            int cp=text.codePointAt(i);
            int next=i+Character.charCount(cp);
            FontSlot selected=findFont(cp,fonts);
            if(selected==null)selected=fonts.get(0);

            if(active!=selected&&run.length()>0){
                cs.setFont(active.font,size);
                cs.showText(run.toString());
                run.setLength(0);
            }

            if(findFont(cp,fonts)!=null){
                run.appendCodePoint(cp);
            }else{
                run.append('?');
            }

            active=selected;
            i=next;
        }

        if(run.length()>0){
            cs.setFont(active.font,size);
            cs.showText(run.toString());
        }
    }

    private static FontSlot findFont(int codePoint,List<FontSlot> fonts)
            throws IOException{
        String glyph=new String(Character.toChars(codePoint));

        // Do not use PDType0Font.hasGlyph() here. On some Android/PDFBox
        // combinations it reports false for valid Vietnamese glyphs even
        // though the font encoder can encode them correctly. That caused the
        // previous build to replace characters such as "ệ", "ả" and "đ"
        // with ASCII "?". The authoritative test is the same encoder that
        // PDPageContentStream.showText() uses.
        for(FontSlot slot:fonts){
            try{
                slot.font.encode(glyph);
                return slot;
            }catch(IOException ignored){
                // Try the next Unicode fallback font.
            }
        }
        return null;
    }

    private static final class FontSlot{
        final String name;
        final PDType0Font font;

        FontSlot(String name,PDType0Font font){
            this.name=name;
            this.font=font;
        }
    }

    private static final class PageResult{
        final int page;
        final String text;
        PageResult(int page,String text){this.page=page;this.text=text;}
    }

    private static void save(Properties p,File f)throws IOException{
        try(FileOutputStream out=new FileOutputStream(f)){p.store(out,"MedBook PDF translation progress");}
    }
    private static void copyFile(File s,File t)throws IOException{
        File p=t.getParentFile();if(p!=null&&!p.exists())p.mkdirs();
        try(InputStream in=new FileInputStream(s);OutputStream o=new FileOutputStream(t)){
            byte[] b=new byte[16384];int n;while((n=in.read(b))>0)o.write(b,0,n);
        }
    }
}
