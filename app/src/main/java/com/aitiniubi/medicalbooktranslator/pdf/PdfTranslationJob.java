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

                try(PDDocument doc=PDDocument.load(source)){
                    PDFTextStripper stripper=new PDFTextStripper();
                    for(int page=1;page<=total;page++){
                        if(translations.containsKey(page))continue;
                        stripper.setStartPage(page);stripper.setEndPage(page);
                        String sourceText=stripper.getText(doc);
                        if(sourceText==null||sourceText.trim().isEmpty()){
                            throw new IOException("Trang "+page+" không có text layer. PDF scan/OCR không được hỗ trợ.");
                        }
                        String prompt="Translate this medical textbook page from English to professional Vietnamese. Preserve medical terminology, abbreviations, numbers, units, citations, formulas, gene/drug names and paragraph breaks. Return plain text only.\n\n"+sourceText;
                        String translated=TranslationRouter.translate(prompt,"Medical obstetric ultrasound / fetal medicine textbook. Do not invent, omit, or summarize information.",providers);
                        if(translated==null||translated.trim().isEmpty())throw new IOException("AI trả về bản dịch rỗng ở trang "+page);
                        translations.put(page,translated.trim());
                        state.setProperty("page."+page,translated.trim());
                        save(state,stateFile);
                        done++;
                        listener.onProgress(done,total,page,"Đã dịch PDF trang "+page+"/"+total);
                    }
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
            PDType0Font font;
            File roboto=new File("/system/fonts/Roboto-Regular.ttf");
            File noto=new File("/system/fonts/NotoSans-Regular.ttf");
            if(roboto.isFile())font=PDType0Font.load(out,new FileInputStream(roboto),true);
            else if(noto.isFile())font=PDType0Font.load(out,new FileInputStream(noto),true);
            else throw new IOException("Thiết bị không có font Unicode hệ thống để tạo PDF tiếng Việt.");

            for(int i=0;i<src.getNumberOfPages();i++){
                PDRectangle box=src.getPage(i).getMediaBox();
                PDPage page=new PDPage(new PDRectangle(box.getWidth(),box.getHeight()));
                out.addPage(page);
                String text=sanitizeForPdf(translations.get(i+1));
                try(PDPageContentStream cs=new PDPageContentStream(out,page)){
                    cs.beginText();
                    cs.setFont(font,10);
                    cs.setLeading(14);
                    cs.newLineAtOffset(42,box.getHeight()-48);
                    float max=box.getWidth()-84;
                    for(String para:text.replace("\r","").split("\n")){
                        for(String line:wrap(para,font,10,max)){cs.showText(line);cs.newLine();}
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            out.save(output);
        }
    }

    private static String sanitizeForPdf(String text){
        if(text==null||text.isEmpty())return "";
        StringBuilder out=new StringBuilder(text.length());
        for(int i=0;i<text.length();){
            int cp=text.codePointAt(i);i+=Character.charCount(cp);
            boolean privateUse=(cp>=0xE000&&cp<=0xF8FF)||(cp>=0xF0000&&cp<=0xFFFFD)||(cp>=0x100000&&cp<=0x10FFFD);
            if(privateUse||cp==0xFFFD)continue;
            if(Character.isISOControl(cp)&&cp!=10&&cp!=9&&cp!=13)continue;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static List<String> wrap(String text,PDType0Font font,float size,float max)throws IOException{
        List<String> lines=new ArrayList<>();
        if(text==null||text.isEmpty()){lines.add("");return lines;}
        StringBuilder line=new StringBuilder();
        for(String word:text.trim().split("\\s+")){
            String candidate=line.length()==0?word:line+" "+word;
            if(font.getStringWidth(candidate)/1000f*size>max&&line.length()>0){
                lines.add(line.toString());
                line=new StringBuilder(word);
            }else line=new StringBuilder(candidate);
        }
        if(line.length()>0)lines.add(line.toString());
        return lines;
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
