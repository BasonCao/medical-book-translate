package com.aitiniubi.medicalbooktranslator.pdf;

import android.content.Context;
import com.aitiniubi.medicalbooktranslator.translation.TranslationRouter;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDStream;
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser;
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter;
import com.tom_roush.pdfbox.contentstream.operator.Operator;
import com.tom_roush.pdfbox.text.TextPosition;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.util.Matrix;
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
                boolean layoutV3="3".equals(state.getProperty("pdf.layout.version",""));
                for(int i=1;i<=total;i++){
                    String t=layoutV3?state.getProperty("page."+i,""):"";
                    if(!t.trim().isEmpty()){translations.put(i,t);done++;}
                }
                listener.onProgress(done,total,0,"Khôi phục tiến độ PDF: "+done+"/"+total);

                // Extract visual layout units first. Each unit is translated as a
                // self-contained block, so the renderer can put the Vietnamese text
                // back into the exact original block instead of guessing line order.
                Map<Integer,List<LayoutUnit>> pageUnits=new HashMap<>();
                try(PDDocument doc=PDDocument.load(source)){
                    for(int page=1;page<=total;page++){
                        pageUnits.put(page,extractLayoutUnits(doc,page));
                    }
                }

                // V1.9.2 stored one translated string per page. That is not enough
                // for multi-column PDFs because PDF text drawing order can differ
                // from visual reading order. V1.9.3 uses stable UNIT markers.
                final int parallelism=3;
                java.util.concurrent.ExecutorService pool=
                        java.util.concurrent.Executors.newFixedThreadPool(parallelism);
                java.util.concurrent.CompletionService<PageResult> completion=
                        new java.util.concurrent.ExecutorCompletionService<>(pool);
                int submitted=0;
                for(int page=1;page<=total;page++){
                    final int pageNo=page;
                    final List<LayoutUnit> units=pageUnits.get(page);
                    if(units==null||units.isEmpty())continue;
                    completion.submit(()->{
                        StringBuilder prompt=new StringBuilder();
                        prompt.append("Translate the following medical textbook page from English to professional Vietnamese.\\n")
                              .append("IMPORTANT: Keep every UNIT marker exactly unchanged. Do not merge, split, reorder, omit, summarize, or invent units.\\n")
                              .append("Translate the text INSIDE each unit only. Preserve medical terminology, abbreviations, numbers, units, citations, URLs, formulas, gene/drug names.\\n")
                              .append("Return plain text only, using the same UNIT markers.\\n\\n");
                        for(int i=0;i<units.size();i++){
                            prompt.append("[[[UNIT_").append(i).append("]]]\\n")
                                  .append(units.get(i).source).append("\\n");
                        }
                        String translated=TranslationRouter.translate(prompt.toString(),
                                "Medical obstetric ultrasound / fetal medicine textbook. Do not invent, omit, or summarize information.",providers);
                        if(translated==null||translated.trim().isEmpty())
                            throw new IOException("AI trả về bản dịch rỗng ở trang "+pageNo);
                        String normalized=parseUnitResponse(translated,units.size(),pageNo);
                        return new PageResult(pageNo,normalized);
                    });
                    submitted++;
                }

                try{
                    state.setProperty("pdf.layout.version","3");
                    for(int n=0;n<submitted;n++){
                        PageResult result=completion.take().get();
                        translations.put(result.page,result.text);
                        synchronized(state){
                            state.setProperty("page."+result.page,result.text);
                            save(state,stateFile);
                        }
                        done++;
                        listener.onProgress(done,total,result.page,
                                "Đã dịch PDF "+done+"/"+total+" trang | bố cục block + "+parallelism+" trang song song");
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

    /**
     * Layout-preserving PDF renderer. The original page remains intact, including
     * every image/vector object. Only original text operators are removed; each
     * translated UNIT is then drawn inside its original visual bounding box.
     */
    private static void buildReflowPdf(Context context,File source,File output,
                                       Map<Integer,String> translations)throws Exception{
        PDFBoxResourceLoader.init(context.getApplicationContext());
        try(PDDocument doc=PDDocument.load(source)){
            List<FontSlot> fonts=loadFonts(doc);
            if(fonts.isEmpty())throw new IOException("Thiết bị không có font TTF Unicode để tạo PDF tiếng Việt.");
            for(int i=0;i<doc.getNumberOfPages();i++){
                PDPage page=doc.getPage(i);
                List<LayoutUnit> units=extractLayoutUnits(doc,i+1);
                if(units.isEmpty())continue;
                Map<Integer,String> translated=parseUnitMap(translations.get(i+1),units.size());
                stripTextOperators(doc,page);
                float pageHeight=page.getMediaBox().getHeight();
                try(PDPageContentStream cs=new PDPageContentStream(doc,page,
                        PDPageContentStream.AppendMode.APPEND,true,true)){
                    for(int n=0;n<units.size();n++){
                        String text=translated.get(n);
                        if(text==null||text.trim().isEmpty())continue;
                        drawUnit(cs,text,units.get(n),fonts,pageHeight);
                    }
                }
            }
            doc.save(output);
        }
    }

    private static void drawUnit(PDPageContentStream cs,String text,LayoutUnit unit,
                                 List<FontSlot> fonts,float pageHeight)throws IOException{
        String clean=sanitizeForPdf(text).trim();
        if(clean.isEmpty())return;
        float maxWidth=Math.max(10f,unit.width);
        float maxHeight=Math.max(10f,unit.height);
        float size=Math.max(5.5f,Math.min(18f,unit.fontSize));
        List<String> lines;
        while(true){
            lines=wrapText(clean,fonts,size,maxWidth);
            float leading=size*1.16f;
            if(lines.size()*leading<=maxHeight || size<=5.5f)break;
            size=Math.max(5.5f,size-0.45f);
        }
        float leading=size*1.16f;
        float yTop=unit.y;
        try{
            cs.beginText();
            cs.setFont(fonts.get(0).font,size);
            for(int i=0;i<lines.size();i++){
                String line=lines.get(i);
                float y=pageHeight-(yTop+i*leading)-size*0.84f;
                if(y<1f)y=1f;
                cs.setTextMatrix(Matrix.getTranslateInstance(unit.x,y));
                showTextWithFallback(cs,line,fonts,size);
            }
            cs.endText();
        }catch(Exception e){
            try{cs.endText();}catch(Exception ignored){}
            throw e;
        }
    }

    private static List<String> wrapText(String text,List<FontSlot> fonts,float size,float maxWidth)
            throws IOException{
        List<String> out=new ArrayList<>();
        for(String paragraph:text.replace("\\r","").split("\\n+")){
            String p=paragraph.trim();
            if(p.isEmpty()){out.add("");continue;}
            StringBuilder line=new StringBuilder();
            for(String word:p.split("\\s+")){
                String candidate=line.length()==0?word:line+" "+word;
                if(line.length()>0&&measureWidth(candidate,fonts,size)>maxWidth){
                    out.add(line.toString());line=new StringBuilder(word);
                }else line=new StringBuilder(candidate);
            }
            if(line.length()>0)out.add(line.toString());
        }
        return out;
    }

    private static List<LayoutUnit> extractLayoutUnits(PDDocument doc,int pageNumber)
            throws IOException{
        LayoutStripper stripper=new LayoutStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageNumber);stripper.setEndPage(pageNumber);
        stripper.getText(doc);
        return stripper.buildUnits(doc.getPage(pageNumber-1).getMediaBox().getWidth());
    }

    private static final class LayoutStripper extends PDFTextStripper{
        final List<TextPosition> glyphs=new ArrayList<>();
        LayoutStripper()throws IOException{super();}
        @Override protected void processTextPosition(TextPosition text){
            String u=text.getUnicode();
            if(u!=null&&!u.isEmpty())glyphs.add(text);
            super.processTextPosition(text);
        }

        List<LayoutUnit> buildUnits(float pageWidth){
            List<LayoutLine> lines=buildLines(pageWidth);
            if(lines.isEmpty())return new ArrayList<>();
            Map<Integer,List<LayoutLine>> columns=new HashMap<>();
            for(LayoutLine line:lines){
                float right=line.x+line.width;
                int col;
                if(line.x<pageWidth*0.18f && right>pageWidth*0.52f) col=-1; // full width
                else col=(line.x+line.width/2f<pageWidth/2f)?0:1;
                columns.computeIfAbsent(col,k->new ArrayList<>()).add(line);
            }
            for(List<LayoutLine> list:columns.values())
                Collections.sort(list,(a,b)->Float.compare(a.y,b.y));

            List<LayoutUnit> ordered=new ArrayList<>();
            // Header/full-width material comes first.
            addUnits(ordered,columns.get(-1),pageWidth);
            // Then left column, then right column. This matches normal medical
            // journal reading order for the target two-column layouts.
            addUnits(ordered,columns.get(0),pageWidth);
            addUnits(ordered,columns.get(1),pageWidth);
            Collections.sort(ordered,(a,b)->{
                // Preserve column order, but keep header units at the top.
                int c=Integer.compare(a.column,b.column);
                if(c!=0)return c;
                return Float.compare(a.y,b.y);
            });
            return ordered;
        }

        private void addUnits(List<LayoutUnit> out,List<LayoutLine> lines,float pageWidth){
            if(lines==null||lines.isEmpty())return;
            LayoutUnit current=null;
            for(LayoutLine line:lines){
                float verticalGap=current==null?Float.MAX_VALUE:line.y-current.bottom;
                // Do not split a paragraph merely because a PDF font size changes.
                // Font-size changes inside the same visual block are common in
                // journal PDFs (bold/italic/superscript), and splitting there can
                // create overlapping translated blocks.
                boolean newUnit=current==null || verticalGap>8f;
                if(newUnit){
                    if(current!=null)out.add(current);
                    current=new LayoutUnit(line.source,line.x,line.y,line.width,line.height,line.fontSize,
                            columnOf(line,pageWidth));
                }else{
                    current.source += " " + line.source;
                    float right=Math.max(current.x+current.width,line.x+line.width);
                    current.width=right-current.x;
                    current.height=Math.max(current.height,line.y+line.height-current.y);
                    current.bottom=Math.max(current.bottom,line.y+line.height);
                    current.fontSize=Math.min(current.fontSize,line.fontSize>0?line.fontSize:current.fontSize);
                }
            }
            if(current!=null)out.add(current);
        }

        private int columnOf(LayoutLine l,float pageWidth){
            float right=l.x+l.width;
            if(l.x<pageWidth*0.18f && right>pageWidth*0.52f)return -1;
            return l.x+l.width/2f<pageWidth/2f?0:1;
        }

        List<LayoutLine> buildLines(float pageWidth){
            List<LayoutLine> out=new ArrayList<>();
            List<TextPosition> sorted=new ArrayList<>(glyphs);
            Collections.sort(sorted,(a,b)->{
                int y=Float.compare(a.getY(),b.getY());
                if(y!=0)return y;
                return Float.compare(a.getX(),b.getX());
            });
            List<List<TextPosition>> rows=new ArrayList<>();
            for(TextPosition p:sorted){
                float tol=Math.max(2f,p.getFontSizeInPt()*0.28f);
                List<TextPosition> row=null;
                for(int i=rows.size()-1;i>=0;i--){
                    List<TextPosition> r=rows.get(i);
                    float ry=r.get(0).getY();
                    if(p.getY()-ry>tol+2f)break;
                    if(Math.abs(p.getY()-ry)<=tol){row=r;break;}
                }
                if(row==null){row=new ArrayList<>();rows.add(row);} row.add(p);
            }
            for(List<TextPosition> row:rows){
                Collections.sort(row,Comparator.comparing(TextPosition::getX));
                List<TextPosition> piece=new ArrayList<>();
                TextPosition prev=null;
                for(TextPosition p:row){
                    if(prev!=null){
                        float gap=p.getX()-(prev.getX()+prev.getWidth());
                        boolean crossesTwoColumns =
                                (prev.getX()+prev.getWidth()) < pageWidth*0.49f
                                && p.getX() > pageWidth*0.51f
                                && gap > 8f;
                        float threshold=Math.max(42f,prev.getFontSizeInPt()*3.5f);
                        if((gap>threshold || crossesTwoColumns)&&!piece.isEmpty()){
                            addLine(out,piece);piece=new ArrayList<>();
                        }
                    }
                    piece.add(p);prev=p;
                }
                addLine(out,piece);
            }
            Collections.sort(out,(a,b)->{
                int y=Float.compare(a.y,b.y);return y!=0?y:Float.compare(a.x,b.x);
            });
            return out;
        }

        private void addLine(List<LayoutLine> out,List<TextPosition> piece){
            if(piece==null||piece.isEmpty())return;
            StringBuilder s=new StringBuilder();float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE;
            float maxX=0,maxY=0,size=0;TextPosition prev=null;
            for(TextPosition p:piece){
                String u=p.getUnicode();if(u==null)continue;
                if(prev!=null){
                    float gap=p.getX()-(prev.getX()+prev.getWidth());
                    if(gap>Math.max(1.5f,p.getWidthOfSpace()*0.55f)&&s.length()>0)s.append(' ');
                }
                s.append(u);minX=Math.min(minX,p.getX());minY=Math.min(minY,p.getY());
                maxX=Math.max(maxX,p.getX()+p.getWidth());maxY=Math.max(maxY,p.getY()+p.getHeight());
                size=Math.max(size,p.getFontSizeInPt());prev=p;
            }
            if(s.length()>0&&maxX>minX)out.add(new LayoutLine(s.toString().trim(),minX,minY,maxX-minX,maxY-minY,size));
        }
    }

    private static final class LayoutLine{
        String source;final float x,y,width,height,fontSize;
        LayoutLine(String s,float x,float y,float w,float h,float fs){source=s;this.x=x;this.y=y;this.width=w;this.height=h;this.fontSize=fs>0?fs:10f;}
    }

    private static final class LayoutUnit{
        String source;final float x,y;float width,height,fontSize;final int column;float bottom;
        LayoutUnit(String s,float x,float y,float w,float h,float fs,int c){source=s;this.x=x;this.y=y;width=w;height=h;fontSize=fs>0?fs:10f;column=c;bottom=y+h;}
    }

    private static String parseUnitResponse(String raw,int count,int page)throws IOException{
        Map<Integer,String> m=parseUnitMap(raw,count);
        StringBuilder out=new StringBuilder();
        for(int i=0;i<count;i++){
            String v=m.get(i);if(v==null)throw new IOException("AI thiếu UNIT_"+i+" ở trang "+page);
            if(i>0)out.append("\\n");
            out.append("[[[UNIT_").append(i).append("]]]\\n").append(v.trim());
        }
        return out.toString();
    }

    private static Map<Integer,String> parseUnitMap(String raw,int count)throws IOException{
        Map<Integer,String> out=new HashMap<>();
        if(raw==null)return out;
        java.util.regex.Pattern p=java.util.regex.Pattern.compile("\\[\\[\\[UNIT_(\\d+)\\]\\]\\]\\s*",java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m=p.matcher(raw);
        List<Integer> ids=new ArrayList<>();List<Integer> contentStarts=new ArrayList<>();List<Integer> markerStarts=new ArrayList<>();
        while(m.find()){ids.add(Integer.parseInt(m.group(1)));contentStarts.add(m.end());markerStarts.add(m.start());}
        for(int i=0;i<ids.size();i++){
            int id=ids.get(i);if(id<0||id>=count)continue;
            int end=i+1<markerStarts.size()?markerStarts.get(i+1):raw.length();
            out.put(id,raw.substring(contentStarts.get(i),end).replace("\\n","\n").trim());
        }
        return out;
    }

    /** Remove only BT...ET text sections; images and vector graphics remain untouched. */
    private static void stripTextOperators(PDDocument doc,PDPage page)throws IOException{
        PDFStreamParser parser=new PDFStreamParser(page);parser.parse();
        List<Object> kept=new ArrayList<>();boolean inText=false;
        for(Object token:parser.getTokens()){
            if(token instanceof Operator){
                String name=((Operator)token).getName();
                if("BT".equals(name)){inText=true;continue;}
                if("ET".equals(name)){inText=false;continue;}
                if(inText)continue;
            }else if(inText)continue;
            kept.add(token);
        }
        PDStream replacement=new PDStream(doc);
        try(OutputStream os=replacement.createOutputStream()){
            new ContentStreamWriter(os).writeTokens(kept);
        }
        page.setContents(replacement);
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
                try{
                    width+=font.font.getStringWidth(glyphText)/1000f*size;
                }catch(Exception unsupported){
                    FontSlot primary=fonts.get(0);
                    width+=primary.font.getStringWidth("?")/1000f*size;
                }
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
                // Test both encoding and width calculation. Some Android/PDFBox
                // builds can report encode() successfully but still throw the
                // exact "No glyph for U+...." error from getStringWidth().
                slot.font.encode(glyph);
                slot.font.getStringWidth(glyph);
                return slot;
            }catch(Exception ignored){
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
