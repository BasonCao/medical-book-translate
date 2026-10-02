package com.aitiniubi.medicalbooktranslator.pdf;

import android.content.Context;
import android.graphics.Path;
import android.graphics.PointF;
import com.aitiniubi.medicalbooktranslator.translation.TranslationRouter;
import com.aitiniubi.medicalbooktranslator.translation.TranslationLogger;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDStream;
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser;
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter;
import com.tom_roush.pdfbox.contentstream.operator.Operator;
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage;
import com.tom_roush.pdfbox.text.TextPosition;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.cos.COSStream;
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

    public static void run(Context context,File source,File output,File workspace,List<TranslationRouter.Provider> providers,boolean singleColumn,Listener listener){
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

                TranslationLogger logger=new TranslationLogger(workspace);
                TranslationLogger.bind(logger);
                logger.start(source.getName(), fileHash(source), total);
                logger.event("PDF_QUEUE","pages="+total);
                Map<Integer,String> translations=new HashMap<>();
                int done=0;
                int skipped=0;
                boolean layoutV9="9".equals(state.getProperty("pdf.layout.version",""));
                for(int i=1;i<=total;i++){
                    String t=layoutV9?state.getProperty("page."+i,""):"";
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
                final int parallelism=4;
                java.util.concurrent.ExecutorService pool=
                        java.util.concurrent.Executors.newFixedThreadPool(parallelism);
                java.util.concurrent.CompletionService<PageResult> completion=
                        new java.util.concurrent.ExecutorCompletionService<>(pool);
                final java.util.concurrent.atomic.AtomicBoolean quotaSignal=
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                int submitted=0;
                for(int page=1;page<=total;page++){
                    final int pageNo=page;
                    final List<LayoutUnit> units=pageUnits.get(page);
                    if(units==null||units.isEmpty())continue;
                    completion.submit(()->{
                        TranslationLogger.bind(logger);
                        long started=System.currentTimeMillis();
                        try{
                            if(quotaSignal.get()){
                                return new PageResult(pageNo,null,
                                        new IOException("AI provider hết quota/rate limit; trang chưa gửi request."),
                                        true);
                            }
                            TranslationRouter.setDiagnostics(logger,"PDF");
                            logger.event("PAGE_START","page="+pageNo+" units="+units.size());
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
                            logger.event("PAGE_OK","page="+pageNo+" elapsedMs="+(System.currentTimeMillis()-started));
                            return new PageResult(pageNo,normalized);
                        }catch(Exception e){
                            boolean quota=isQuotaError(e);
                            if(quota)quotaSignal.set(true);
                            logger.event(quota?"QUOTA_PAUSE":"PAGE_SKIP",
                                    "page="+pageNo+" elapsedMs="+(System.currentTimeMillis()-started)
                                    +" reason="+safeLog(e.getMessage()));
                            return new PageResult(pageNo,null,e,quota);
                        }finally{
                            TranslationRouter.clearDiagnostics();
                            TranslationLogger.unbind();
                        }
                    });
                    submitted++;
                }

                try{
                    state.setProperty("pdf.layout.version","9");
                    for(int n=0;n<submitted;n++){
                        PageResult result=completion.take().get();
                        if(result.quota){
                            Exception reason=result.error==null
                                    ?new IOException("AI provider hết quota/rate limit.")
                                    :new IOException("AI provider hết quota/rate limit: "+safeLog(result.error.getMessage()),result.error);
                            logger.event("QUOTA_PAUSE","page="+result.page+" reason="+safeLog(reason.getMessage()));
                            File draft=new File(workspace,"translated-current.pdf");
                            buildReflowPdf(context,source,draft,translations,singleColumn);
                            listener.onPaused(draft,done,total,reason);
                            return;
                        }
                        if(result.text==null||result.text.trim().isEmpty()){
                            skipped++;
                            logger.event("PAGE_SKIPPED","page="+result.page+" reason=translation failed; source page retained");
                            listener.onProgress(done+skipped,total,result.page,
                                    "Bỏ qua trang PDF "+result.page+" — ghi log, giữ nguyên trang nguồn");
                            continue;
                        }
                        translations.put(result.page,result.text);
                        synchronized(state){
                            state.setProperty("page."+result.page,result.text);
                            save(state,stateFile);
                        }
                        done++;
                        listener.onProgress(done+skipped,total,result.page,
                                "Đã dịch PDF "+done+"/"+total+" trang | bỏ qua "+skipped+" | "+parallelism+" trang song song");
                    }
                    logger.event("PDF_QUEUE_DONE","translated="+done+" skipped="+skipped+" total="+total);
                }catch(java.util.concurrent.ExecutionException e){
                    Throwable cause=e.getCause();
                    if(cause instanceof Exception)throw (Exception)cause;
                    throw new IOException("Lỗi dịch PDF.",cause);
                }finally{
                    pool.shutdownNow();
                }

                File draft=new File(workspace,"translated-current.pdf");
                buildReflowPdf(context,source,draft,translations,singleColumn);
                copyFile(draft,output);
                listener.onDone(output);
            }catch(Exception e){
                listener.onError(e);
            }finally{
                TranslationLogger.unbind();
            }
        },"pdf-translation").start();
    }

    /**
     * Layout-preserving PDF renderer. The original page remains intact, including
     * every image/vector object. Only original text operators are removed; each
     * translated UNIT is then drawn inside its original visual bounding box.
     */
    private static void buildReflowPdf(Context context,File source,File output,
                                       Map<Integer,String> translations,boolean singleColumn)throws Exception{
        PDFBoxResourceLoader.init(context.getApplicationContext());
        try(PDDocument doc=PDDocument.load(source)){
            List<FontSlot> fonts=loadFonts(doc);
            if(fonts.isEmpty())throw new IOException("Thiết bị không có font TTF Unicode để tạo PDF tiếng Việt.");

            List<PDPage> sourcePages=new ArrayList<>();
            for(int i=0;i<doc.getNumberOfPages();i++)sourcePages.add(doc.getPage(i));

            for(int i=0;i<sourcePages.size();i++){
                PDPage page=sourcePages.get(i);
                List<LayoutUnit> units=extractLayoutUnits(doc,i+1);
                if(units.isEmpty())continue;
                String pageTranslation=translations.get(i+1);
                // Failed pages are deliberately left untouched in the source PDF.
                // Never remove English text when the translation for this page is absent.
                if(pageTranslation==null||pageTranslation.trim().isEmpty())continue;
                Map<Integer,String> translated=parseUnitMap(pageTranslation,units.size());
                if(singleColumn && hasTwoColumnLayout(units)){
                    buildSingleColumnPage(doc,page,units,translated,fonts);
                }else{
                    buildTwoColumnPage(doc,page,units,translated,fonts);
                }
            }
            doc.save(output);
        }
    }

    /** V1.10.14: preserve two-column geometry, isolate table cells, and protect images. Original images,
     * tables and vector artwork are retained; only the source text operators are
     * removed and translated units are drawn back inside their original boxes. */
    private static void buildTwoColumnPage(PDDocument doc,PDPage page,
                                            List<LayoutUnit> units,
                                            Map<Integer,String> translated,
                                            List<FontSlot> fonts)throws IOException{
        // Capture table geometry before rewriting page streams.
        List<TableRegion> tables=collectTableRegions(page);

        // Capture original images before stripping source text. Some publisher
        // PDFs place text and an image in the same Form XObject; redrawing the
        // original image after stripping guarantees no leftover English text
        // can remain visually over a figure.
        List<ImagePlacement> originalImages=collectImagePlacements(page);

        // Remove source text while preserving the original vector graphics.
        stripTextOperators(doc,page);

        float pageHeight=page.getMediaBox().getHeight();
        try(PDPageContentStream cs=new PDPageContentStream(doc,page,
                PDPageContentStream.AppendMode.APPEND,true,true)){
            for(ImagePlacement p:originalImages){
                try{
                    cs.drawImage(p.image,p.x,p.y,p.width,p.height);
                }catch(Exception ignored){}
            }
            for(int n=0;n<units.size();n++){
                String text=translated.get(n);
                if(text==null||text.trim().isEmpty())continue;
                LayoutUnit unit=units.get(n);
                if(drawTableCellIfNeeded(cs,text,unit,fonts,pageHeight,tables))
                    continue;
                drawUnitSafe(cs,text,unit,fonts,pageHeight,originalImages);
            }
        }
    }

    private static boolean hasTwoColumnLayout(List<LayoutUnit> units){
        boolean left=false,right=false;
        for(LayoutUnit u:units){
            if(u.column==0)left=true;
            else if(u.column==1)right=true;
        }
        return left&&right;
    }


    /**
     * V1.9.7: convert detected two-column pages into a clean one-column flow.
     * The flow is allowed to continue onto newly-created pages instead of
     * shrinking the whole article into unreadably small text.
     */
    private static void buildSingleColumnPage(PDDocument doc,PDPage firstPage,
                                               List<LayoutUnit> units,
                                               Map<Integer,String> translated,
                                               List<FontSlot> fonts)throws IOException{
        final float pageW=firstPage.getMediaBox().getWidth();
        final float pageH=firstPage.getMediaBox().getHeight();
        final float margin=36f;
        final float maxW=Math.max(120f,pageW-margin*2f);
        final float imageMaxW=Math.min(maxW,maxW*0.88f);
        final float textSize=8.8f;
        final float leading=textSize*1.22f;

        List<ImagePlacement> placements=collectImagePlacements(firstPage);
        if(placements.isEmpty()){
            PDResources resources=firstPage.getResources();
            if(resources!=null){
                for(COSName name:resources.getXObjectNames()){
                    try{
                        PDXObject xo=resources.getXObject(name);
                        if(xo instanceof PDImageXObject){
                            PDImageXObject image=(PDImageXObject)xo;
                            placements.add(new ImagePlacement(image,0,0,image.getWidth(),
                                    image.getHeight(),pageH));
                        }
                    }catch(Exception ignored){}
                }
            }
        }

        // Remove the original two-column content. We redraw text/images in a
        // deliberate reading order below.
        firstPage.setContents(new PDStream(doc));

        List<FlowItem> header=new ArrayList<>();
        List<FlowItem> left=new ArrayList<>();
        List<FlowItem> right=new ArrayList<>();

        for(int i=0;i<units.size();i++){
            String text=translated.get(i);
            if(text==null||text.trim().isEmpty())continue;
            LayoutUnit u=units.get(i);
            if(isJournalFooter(u.source,u.y,pageH))continue;

            FlowItem item=FlowItem.text(text,u.y);
            if(u.column<0)header.add(item);
            else if(u.column==0)left.add(item);
            else right.add(item);
        }

        for(ImagePlacement p:placements){
            // Wide figures are page-level items; otherwise use the original
            // left/right column. This fixes V1.9.6's center-point misclassification.
            FlowItem item=FlowItem.image(p,p.top);
            if(p.width>=pageW*0.50f)header.add(item);
            else if(p.x+p.width/2f<pageW/2f)left.add(item);
            else right.add(item);
        }

        Comparator<FlowItem> byY=(x,y)->Float.compare(x.top,y.top);
        Collections.sort(header,byY);
        Collections.sort(left,byY);
        Collections.sort(right,byY);

        List<FlowItem> flow=new ArrayList<>();
        flow.addAll(header);
        flow.addAll(left);
        flow.addAll(right);

        PDPage current=firstPage;
        float top=pageH-margin;
        PDPageContentStream cs=new PDPageContentStream(doc,current);
        try{
            for(FlowItem item:flow){
                if(item.image!=null){
                    float ratio=item.image.height>0?item.image.width/item.image.height:1f;
                    float w=Math.min(imageMaxW,item.image.width);
                    float h=w/Math.max(0.1f,ratio);
                    float needed=h+12f;

                    if(top-needed<margin && top<pageH-margin-2f){
                        cs.close();
                        PDPage previous=current;
                        current=new PDPage(firstPage.getMediaBox());
                        doc.getPages().insertAfter(current,previous);
                        cs=new PDPageContentStream(doc,current);
                        top=pageH-margin;
                    }

                    // If a single figure is taller than the printable area,
                    // scale it down only as much as necessary.
                    if(h>pageH-margin*2f){
                        h=pageH-margin*2f;
                        w=h*Math.max(0.1f,ratio);
                        if(w>maxW){w=maxW;h=w/Math.max(0.1f,ratio);}
                    }

                    top-=h;
                    cs.drawImage(item.image.image,(pageW-w)/2f,top,w,h);
                    top-=12f;
                    continue;
                }

                String clean=sanitizeForPdf(item.text).trim();
                if(clean.isEmpty())continue;
                List<String> lines=wrapText(clean,fonts,textSize,maxW);
                float needed=lines.size()*leading+4f;

                if(top-needed<margin && top<pageH-margin-2f){
                    cs.close();
                    PDPage previous=current;
                    current=new PDPage(firstPage.getMediaBox());
                    doc.getPages().insertAfter(current,previous);
                    cs=new PDPageContentStream(doc,current);
                    top=pageH-margin;
                }

                cs.beginText();
                cs.setFont(fonts.get(0).font,textSize);
                for(String line:lines){
                    cs.setTextMatrix(Matrix.getTranslateInstance(margin,top-textSize));
                    showTextWithFallback(cs,line,fonts,textSize);
                    top-=leading;
                }
                cs.endText();
                top-=4f;
            }
        }finally{
            try{cs.close();}catch(Exception ignored){}
        }
    }

    private static boolean isJournalFooter(String text,float y,float pageH){
        if(text==null)return false;
        String s=text.trim().toLowerCase(Locale.US);
        if(y>pageH-35f)return true;
        return s.contains("j obstet gynaecol can")
                ||s.contains("https://doi.org/")
                ||s.contains("© 2018")
                ||s.contains("published by elsevier")
                ||s.contains("all rights reserved");
    }

    private static float estimateFlowHeight(List<FlowItem> flow,List<FontSlot> fonts,
                                            float size,float maxW,float imageMaxW)throws IOException{
        float total=0f;
        for(FlowItem item:flow){
            if(item.image!=null){
                float ratio=item.image.height>0?item.image.width/item.image.height:1f;
                float w=Math.min(imageMaxW,item.image.width);
                total+=w/Math.max(0.1f,ratio)+10f;
            }else{
                List<String> lines=wrapText(sanitizeForPdf(item.text).trim(),fonts,size,maxW);
                total+=lines.size()*size*1.22f+4f;
            }
        }
        return total;
    }

    private static List<ImagePlacement> collectImagePlacements(PDPage page)throws IOException{
        ImageCollector collector=new ImageCollector(page);
        collector.processPage(page);
        return collector.images;
    }

    private static List<FillRect> collectFilledRects(PDPage page)throws IOException{
        FillRectCollector collector=new FillRectCollector(page);
        collector.processPage(page);
        return collector.rects;
    }

    private static void redrawFilledRects(PDPageContentStream cs,List<FillRect> rects,
                                          float pageHeight)throws IOException{
        for(FillRect r:rects){
            if(r.width<8f||r.height<4f||r.width*r.height<120f)continue;
            try{
                int rgb=r.color.toRGB();
                float rr=((rgb>>16)&255)/255f;
                float gg=((rgb>>8)&255)/255f;
                float bb=(rgb&255)/255f;
                if(rr>0.97f&&gg>0.97f&&bb>0.97f)continue;
                cs.saveGraphicsState();
                cs.setNonStrokingColor(rr,gg,bb);
                cs.addRect(r.x,r.y,r.width,r.height);
                cs.fill();
                cs.restoreGraphicsState();
            }catch(Exception ignored){}
        }
    }

    private static final class FillRect{
        final float x,y,width,height;
        final PDColor color;
        FillRect(float x,float y,float width,float height,PDColor color){
            this.x=x;this.y=y;this.width=width;this.height=height;this.color=color;
        }
    }

    private static final class FillRectCollector extends PDFGraphicsStreamEngine{
        final List<FillRect> rects=new ArrayList<>();
        private PointF a,b,c,d;
        FillRectCollector(PDPage page){super(page);}

        @Override public void appendRectangle(PointF p0,PointF p1,PointF p2,PointF p3){
            a=p0;b=p1;c=p2;d=p3;
        }

        @Override public void fillPath(Path.FillType windingRule){
            if(a==null||b==null||c==null||d==null)return;
            float minX=Math.min(Math.min(a.x,b.x),Math.min(c.x,d.x));
            float maxX=Math.max(Math.max(a.x,b.x),Math.max(c.x,d.x));
            float minY=Math.min(Math.min(a.y,b.y),Math.min(c.y,d.y));
            float maxY=Math.max(Math.max(a.y,b.y),Math.max(c.y,d.y));
            if(maxX-minX>=8f&&maxY-minY>=4f){
                PDColor color=getGraphicsState().getNonStrokingColor();
                if(color!=null&&!color.isPattern())
                    rects.add(new FillRect(minX,minY,maxX-minX,maxY-minY,color));
            }
            a=b=c=d=null;
        }

        @Override public void fillAndStrokePath(Path.FillType windingRule){fillPath(windingRule);}
        @Override public void closePath(){}
        @Override public void clip(Path.FillType windingRule){}
        @Override public void curveTo(float x1,float y1,float x2,float y2,float x3,float y3){}
        @Override public void endPath(){a=b=c=d=null;}
        @Override public PointF getCurrentPoint(){return null;}
        @Override public void lineTo(float x,float y){}
        @Override public void moveTo(float x,float y){}
        @Override public void shadingFill(COSName shadingName){}
        @Override public void strokePath(){a=b=c=d=null;}
        @Override public void drawImage(PDImage pdImage)throws IOException{}
    }

    private static final class FlowItem{
        final String text;
        final ImagePlacement image;
        final float top;
        private FlowItem(String t,ImagePlacement i,float y){text=t;image=i;top=y;}
        static FlowItem text(String t,float y){return new FlowItem(t,null,y);}
        static FlowItem image(ImagePlacement i,float y){return new FlowItem(null,i,y);}
    }

    private static final class ImagePlacement{
        final PDImageXObject image;
        final float x,y,width,height,top;
        ImagePlacement(PDImageXObject image,float x,float y,float w,float h,float pageHeight){
            this.image=image;this.x=x;this.y=y;this.width=Math.abs(w);this.height=Math.abs(h);
            this.top=pageHeight-(Math.min(y,y+h)+Math.abs(h));
        }
    }

    private static final class ImageCollector extends PDFGraphicsStreamEngine{
        final List<ImagePlacement> images=new ArrayList<>();
        ImageCollector(PDPage page){super(page);}

        @Override public void drawImage(PDImage pdImage)throws IOException{
            if(!(pdImage instanceof PDImageXObject))return;
            Matrix m=getGraphicsState().getCurrentTransformationMatrix();
            PointF p0=m.transformPoint(0,0);
            PointF p1=m.transformPoint(1,0);
            PointF p2=m.transformPoint(0,1);
            PointF p3=m.transformPoint(1,1);
            float minX=Math.min(Math.min(p0.x,p1.x),Math.min(p2.x,p3.x));
            float maxX=Math.max(Math.max(p0.x,p1.x),Math.max(p2.x,p3.x));
            float minY=Math.min(Math.min(p0.y,p1.y),Math.min(p2.y,p3.y));
            float maxY=Math.max(Math.max(p0.y,p1.y),Math.max(p2.y,p3.y));
            images.add(new ImagePlacement((PDImageXObject)pdImage,minX,minY,
                    maxX-minX,maxY-minY,getPage().getMediaBox().getHeight()));
        }

        @Override public void appendRectangle(PointF p0,PointF p1,
                                               PointF p2,PointF p3){}
        @Override public void clip(Path.FillType windingRule){}
        @Override public void closePath(){}
        @Override public void curveTo(float x1,float y1,float x2,float y2,float x3,float y3){}
        @Override public void endPath(){}
        @Override public void fillAndStrokePath(Path.FillType windingRule){}
        @Override public void fillPath(Path.FillType windingRule){}
        @Override public PointF getCurrentPoint(){return null;}
        @Override public void lineTo(float x,float y){}
        @Override public void moveTo(float x,float y){}
        @Override public void shadingFill(COSName shadingName){}
        @Override public void strokePath(){}
    }

    /**
     * Render a translated unit using the complete vector table cell rectangle.
     * This prevents narrow source numeric strings from forcing Vietnamese text
     * into a tiny width and vertically stacked words.
     */
    private static boolean drawTableCellIfNeeded(PDPageContentStream cs,String text,
                                                   LayoutUnit unit,List<FontSlot> fonts,
                                                   float pageHeight,List<TableRegion> tables)
            throws IOException{
        if(tables==null||tables.isEmpty())return false;

        float cx=unit.x+unit.width*0.5f;
        float cy=unit.y+unit.height*0.5f;
        TableRegion table=null;
        for(TableRegion t:tables){
            if(t.contains(cx,cy)){table=t;break;}
        }
        if(table==null)return false;

        float left=table.leftOf(cx);
        float right=table.rightOf(cx);
        float top=table.rowTop(cy);
        float bottom=table.rowBottom(cy);
        if(right-left<12f||bottom-top<6f)return false;

        float padX=3.0f;
        float padY=1.2f;
        float boxW=Math.max(12f,right-left-padX*2f);
        float boxH=Math.max(7f,bottom-top-padY*2f);

        float size=Math.max(5.3f,Math.min(9.2f,unit.fontSize));
        List<String> lines;
        float leading;
        while(true){
            lines=wrapText(sanitizeForPdf(text).trim(),fonts,size,boxW);
            leading=size*1.14f;
            if(lines.size()*leading<=boxH||size<=5.3f)break;
            size=Math.max(5.3f,size-0.25f);
        }

        boolean rightCell=cx>=(table.x0+table.x1)*0.5f;
        float contentH=lines.size()*leading;
        float firstTop=top+padY+Math.max(0f,(boxH-contentH)*0.5f)+size*0.78f;

        try{
            cs.beginText();
            setTextColor(cs,unit.colorRgb);
            cs.setFont(fonts.get(0).font,size);
            for(int i=0;i<lines.size();i++){
                String line=lines.get(i);
                float lineW=measureWidth(line,fonts,size);
                float x=rightCell
                        ? left+(right-left-lineW)*0.5f
                        : left+padX;
                if(x<left+0.5f)x=left+0.5f;
                float y=pageHeight-(firstTop+i*leading);
                if(y<1f)y=1f;
                cs.setTextMatrix(Matrix.getTranslateInstance(x,y));
                showTextWithFallback(cs,line,fonts,size);
            }
            cs.endText();
        }catch(Exception e){
            try{cs.endText();}catch(Exception ignored){}
            throw e;
        }
        return true;
    }

    private static void drawUnitSafe(PDPageContentStream cs,String text,LayoutUnit unit,
                                     List<FontSlot> fonts,float pageHeight,
                                     List<ImagePlacement> images)throws IOException{
        if(images==null||images.isEmpty()){
            drawUnit(cs,text,unit,fonts,pageHeight);
            return;
        }

        float safeWidth=Math.max(10f,unit.width);
        float unitTop=unit.y;
        float unitBottom=unit.y+unit.height;
        float unitLeft=unit.x;

        for(ImagePlacement image:images){
            float imageTop=image.top;
            float imageBottom=image.top+image.height;
            boolean verticalOverlap=imageBottom>unitTop+1f
                    &&imageTop<unitBottom-1f;
            if(verticalOverlap&&image.x>unitLeft){
                float candidate=image.x-unitLeft-3f;
                if(candidate>10f)safeWidth=Math.min(safeWidth,candidate);
            }
        }

        if(safeWidth>=unit.width-0.5f){
            drawUnit(cs,text,unit,fonts,pageHeight);
            return;
        }

        LayoutUnit safeUnit=new LayoutUnit(unit.source,unit.x,unit.y,
                safeWidth,unit.height,unit.fontSize,unit.column,unit.colorRgb);
        drawUnit(cs,text,safeUnit,fonts,pageHeight);
    }

    private static void drawUnit(PDPageContentStream cs,String text,LayoutUnit unit,
                                 List<FontSlot> fonts,float pageHeight)throws IOException{
        String clean=sanitizeForPdf(text).trim();
        if(clean.isEmpty())return;
        float maxWidth=Math.max(10f,unit.width);
        float maxHeight=Math.max(10f,unit.height);
        float size=Math.max(4.5f,Math.min(18f,unit.fontSize));
        List<String> lines;
        while(true){
            lines=wrapText(clean,fonts,size,maxWidth);
            float leading=size*1.16f;
            if(lines.size()*leading<=maxHeight || size<=4.5f)break;
            size=Math.max(4.5f,size-0.35f);
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

    private static void setTextColor(PDPageContentStream cs,int rgb)throws IOException{
        float r=((rgb>>16)&255)/255f;
        float g=((rgb>>8)&255)/255f;
        float b=(rgb&255)/255f;
        cs.setNonStrokingColor(r,g,b);
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
        List<TableRegion> tables=new ArrayList<>();
        try{tables.addAll(collectTableRegions(doc.getPage(pageNumber-1)));}
        catch(Exception ignored){}
        return stripper.buildUnits(
                doc.getPage(pageNumber-1).getMediaBox().getWidth(),tables);
    }

    /**
     * Detect repeated vector grids used by publisher tables.
     *
     * The target PDF uses multiple long horizontal rules plus one vertical
     * divider. We retain only repeated wide rules, which prevents a logo or
     * isolated colored box from being mistaken for a table.
     */
    private static List<TableRegion> collectTableRegions(PDPage page)throws IOException{
        GridCollector collector=new GridCollector(page);
        collector.processPage(page);

        List<GuideLine> hs=new ArrayList<>();
        for(GuideLine line:collector.horizontal){
            if(line.length()>=70f)hs.add(line);
        }
        if(hs.isEmpty())return Collections.emptyList();

        // Group nearly-coincident horizontal segments.
        Collections.sort(hs,(a,b)->Float.compare(a.y,b.y));
        List<GuideLine> grouped=new ArrayList<>();
        for(GuideLine line:hs){
            GuideLine hit=null;
            for(GuideLine g:grouped){
                if(Math.abs(g.y-line.y)<=1.5f
                        && overlapRatio(g.x1,g.x2,line.x1,line.x2)>=0.70f){
                    hit=g;break;
                }
            }
            if(hit==null)grouped.add(new GuideLine(line.x1,line.x2,line.y));
            else{
                hit.x1=Math.min(hit.x1,line.x1);
                hit.x2=Math.max(hit.x2,line.x2);
            }
        }

        if(grouped.size()<4)return Collections.emptyList();

        // The table is the repeated family with the widest common span.
        GuideLine anchor=null;
        for(GuideLine line:grouped){
            if(anchor==null||line.length()>anchor.length())anchor=line;
        }
        if(anchor==null)return Collections.emptyList();

        List<GuideLine> family=new ArrayList<>();
        for(GuideLine line:grouped){
            if(Math.abs(line.x1-anchor.x1)<=6f
                    &&Math.abs(line.x2-anchor.x2)<=6f
                    &&line.length()>=Math.max(55f,anchor.length()*0.75f)){
                family.add(line);
            }
        }
        if(family.size()<4)return Collections.emptyList();

        Collections.sort(family,(a,b)->Float.compare(a.y,b.y));
        float x0=anchor.x1;
        float x1=anchor.x2;
        float pdfTopY=family.get(0).y;
        float pdfBottomY=family.get(family.size()-1).y;
        float pageH=page.getMediaBox().getHeight();
        float top=Math.min(pageH-pdfTopY,pageH-pdfBottomY);
        float bottom=Math.max(pageH-pdfTopY,pageH-pdfBottomY);

        // Collect vertical dividers that actually span a meaningful fraction
        // of the repeated horizontal-rule family.
        List<Float> verticals=new ArrayList<>();
        verticals.add(x0);
        verticals.add(x1);
        float regionPdfMin=Math.min(pdfTopY,pdfBottomY);
        float regionPdfMax=Math.max(pdfTopY,pdfBottomY);

        for(GuideLine line:collector.vertical){
            if(line.length()<8f)continue;
            float x=(line.x1+line.x2)*0.5f;
            if(x<x0-2f||x>x1+2f)continue;
            float a=Math.min(line.y1,line.y2);
            float b=Math.max(line.y1,line.y2);
            float overlap=Math.max(0f,Math.min(b,regionPdfMax)-Math.max(a,regionPdfMin));
            if(overlap>Math.max(8f,(regionPdfMax-regionPdfMin)*0.18f))
                verticals.add(x);
        }
        verticals=uniqueFloats(verticals,1.5f);
        if(verticals.size()<3)return Collections.emptyList();

        List<Float> horizontals=new ArrayList<>();
        for(GuideLine line:family)horizontals.add(pageH-line.y);
        horizontals=uniqueFloats(horizontals,1.5f);

        List<TableRegion> result=new ArrayList<>();
        result.add(new TableRegion(x0,x1,top,bottom,verticals,horizontals));
        return result;
    }

    private static float overlapRatio(float a0,float a1,float b0,float b1){
        float left=Math.max(Math.min(a0,a1),Math.min(b0,b1));
        float right=Math.min(Math.max(a0,a1),Math.max(b0,b1));
        float overlap=Math.max(0f,right-left);
        float shorter=Math.min(Math.abs(a1-a0),Math.abs(b1-b0));
        return shorter<=0f?0f:overlap/shorter;
    }

    private static List<Float> uniqueFloats(List<Float> values,float tolerance){
        Collections.sort(values);
        List<Float> out=new ArrayList<>();
        for(Float value:values){
            if(value==null)continue;
            if(out.isEmpty()||Math.abs(out.get(out.size()-1)-value)>tolerance)
                out.add(value);
            else
                out.set(out.size()-1,(out.get(out.size()-1)+value)*0.5f);
        }
        return out;
    }

    private static final class GuideLine{
        float x1,x2,y,y1,y2;
        GuideLine(float x1,float x2,float y){
            this.x1=Math.min(x1,x2);
            this.x2=Math.max(x1,x2);
            this.y=y;
            this.y1=y;
            this.y2=y;
        }
        GuideLine(float x1,float y1,float x2,float y2){
            this.x1=x1;
            this.x2=x2;
            this.y=y1;
            this.y1=y1;
            this.y2=y2;
        }
        float length(){
            return (float)Math.hypot(x2-x1,y2-y1);
        }
    }

    private static final class GridCollector extends PDFGraphicsStreamEngine{
        final List<GuideLine> horizontal=new ArrayList<>();
        final List<GuideLine> vertical=new ArrayList<>();
        private PointF current;
        private PointF start;

        GridCollector(PDPage page){super(page);}

        private void addSegment(float x1,float y1,float x2,float y2){
            if(Math.abs(y2-y1)<=1.5f&&Math.abs(x2-x1)>=40f)
                horizontal.add(new GuideLine(x1,x2,(y1+y2)*0.5f));
            if(Math.abs(x2-x1)<=1.5f&&Math.abs(y2-y1)>=8f)
                vertical.add(new GuideLine(x1,y1,x2,y2));
        }

        @Override public void moveTo(float x,float y){
            current=new PointF(x,y);
            start=new PointF(x,y);
        }

        @Override public void lineTo(float x,float y){
            if(current!=null)addSegment(current.x,current.y,x,y);
            current=new PointF(x,y);
        }

        @Override public void appendRectangle(PointF p0,PointF p1,PointF p2,PointF p3){
            addSegment(p0.x,p0.y,p1.x,p1.y);
            addSegment(p1.x,p1.y,p2.x,p2.y);
            addSegment(p2.x,p2.y,p3.x,p3.y);
            addSegment(p3.x,p3.y,p0.x,p0.y);
            current=p0;
            start=p0;
        }

        @Override public void closePath(){
            if(current!=null&&start!=null)addSegment(current.x,current.y,start.x,start.y);
            current=start;
        }

        @Override public void curveTo(float x1,float y1,float x2,float y2,float x3,float y3){
            current=new PointF(x3,y3);
        }

        @Override public void endPath(){current=null;start=null;}
        @Override public PointF getCurrentPoint(){return current;}
        @Override public void clip(Path.FillType windingRule){}
        @Override public void fillAndStrokePath(Path.FillType windingRule){}
        @Override public void fillPath(Path.FillType windingRule){}
        @Override public void shadingFill(COSName shadingName){}
        @Override public void strokePath(){current=null;start=null;}
        @Override public void drawImage(PDImage pdImage)throws IOException{}
    }

    private static final class TableRegion{
        final float x0,x1,top,bottom;
        final List<Float> verticals,horizontals;

        TableRegion(float x0,float x1,float top,float bottom,
                    List<Float> verticals,List<Float> horizontals){
            this.x0=x0;
            this.x1=x1;
            this.top=top;
            this.bottom=bottom;
            this.verticals=verticals;
            this.horizontals=horizontals;
        }

        boolean contains(float x,float y){
            return x>=x0-3f&&x<=x1+3f&&y>=top-3f&&y<=bottom+3f;
        }

        float leftOf(float x){
            float result=x0;
            for(Float v:verticals){
                if(v<=x+1f)result=v;
            }
            return result;
        }

        float rightOf(float x){
            float result=x1;
            for(Float v:verticals){
                if(v>x+1f){result=v;break;}
            }
            return result;
        }

        float rowTop(float y){
            float result=top;
            for(Float h:horizontals){
                if(h<=y+1f)result=h;
            }
            return result;
        }

        float rowBottom(float y){
            float result=bottom;
            for(Float h:horizontals){
                if(h>y+1f){result=h;break;}
            }
            return result;
        }
    }

    private static final class LayoutStripper extends PDFTextStripper{
        final List<TextPosition> glyphs=new ArrayList<>();
        final Map<TextPosition,Integer> colorByGlyph=new IdentityHashMap<>();
        LayoutStripper()throws IOException{super();}
        @Override protected void processTextPosition(TextPosition text){
            String u=text.getUnicode();
            if(u!=null&&!u.isEmpty()){
                glyphs.add(text);
                try{
                    PDColor color=getGraphicsState().getNonStrokingColor();
                    if(color!=null&&!color.isPattern())colorByGlyph.put(text,color.toRGB());
                }catch(Exception ignored){}
            }
            super.processTextPosition(text);
        }

        List<LayoutUnit> buildUnits(float pageWidth,List<TableRegion> tables){
            List<LayoutLine> lines=buildLines(pageWidth,tables);
            if(lines.isEmpty())return new ArrayList<>();

            Map<Integer,List<LayoutLine>> columns=new HashMap<>();
            for(LayoutLine line:lines){
                float right=line.x+line.width;
                int col;
                if(line.y<330f || (line.x<pageWidth*0.12f && right>pageWidth*0.58f))
                    col=-1;
                else
                    col=(line.x+line.width*0.5f<pageWidth*0.5f)?0:1;
                columns.computeIfAbsent(col,k->new ArrayList<>()).add(line);
            }

            for(List<LayoutLine> list:columns.values())
                Collections.sort(list,(x,y)->{
                    int yy=Float.compare(x.y,y.y);
                    return yy!=0?yy:Float.compare(x.x,y.x);
                });

            List<LayoutUnit> ordered=new ArrayList<>();
            addUnits(ordered,columns.get(-1),pageWidth,tables);
            addUnits(ordered,columns.get(0),pageWidth,tables);
            addUnits(ordered,columns.get(1),pageWidth,tables);

            if(tables!=null&&!tables.isEmpty()){
                Iterator<LayoutUnit> it=ordered.iterator();
                while(it.hasNext()){
                    LayoutUnit u=it.next();
                    float cx=u.x+u.width*0.5f;
                    float cy=u.y+u.height*0.5f;
                    for(TableRegion t:tables){
                        if(t.contains(cx,cy)){
                            it.remove();
                            break;
                        }
                    }
                }

                for(TableRegion t:tables)
                    ordered.addAll(buildTableCellUnits(t));
            }

            Collections.sort(ordered,(x,y)->{
                int cc=Integer.compare(x.column,y.column);
                if(cc!=0)return cc;
                int yy=Float.compare(x.y,y.y);
                return yy!=0?yy:Float.compare(x.x,y.x);
            });
            return ordered;
        }

        private List<LayoutUnit> buildTableCellUnits(TableRegion table){
            Map<String,List<TextPosition>> cells=new HashMap<>();

            for(TextPosition glyph:glyphs){
                String u=glyph.getUnicode();
                if(u==null||u.isEmpty())continue;

                float cx=glyph.getX()+glyph.getWidth()*0.5f;
                float cy=glyph.getY()+glyph.getHeight()*0.5f;
                if(!table.contains(cx,cy))continue;

                float left=table.leftOf(cx);
                float top=table.rowTop(cy);
                String key=Math.round(left*10f)+":"+Math.round(top*10f);
                cells.computeIfAbsent(key,k->new ArrayList<>()).add(glyph);
            }

            List<LayoutUnit> result=new ArrayList<>();
            for(List<TextPosition> cellGlyphs:cells.values()){
                if(cellGlyphs.isEmpty())continue;

                Collections.sort(cellGlyphs,(x,y)->{
                    int yy=Float.compare(x.getY(),y.getY());
                    return yy!=0?yy:Float.compare(x.getX(),y.getX());
                });

                StringBuilder text=new StringBuilder();
                TextPosition prev=null;
                float maxSize=0f;

                for(TextPosition glyph:cellGlyphs){
                    String u=glyph.getUnicode();
                    if(u==null)continue;

                    if(prev!=null){
                        float prevCy=prev.getY()+prev.getHeight()*0.5f;
                        float curCy=glyph.getY()+glyph.getHeight()*0.5f;
                        float yGap=Math.abs(curCy-prevCy);
                        float xGap=glyph.getX()-(prev.getX()+prev.getWidth());
                        if(yGap>Math.max(2f,glyph.getFontSizeInPt()*0.45f)
                                &&text.length()>0){
                            text.append(" ");
                        }else if(xGap>Math.max(0.85f,
                                Math.min(3.2f,
                                  Math.max(glyph.getFontSizeInPt(),
                                           prev.getFontSizeInPt())*0.16f))
                                &&text.length()>0){
                            text.append(" ");
                        }
                    }

                    text.append(u);
                    maxSize=Math.max(maxSize,glyph.getFontSizeInPt());
                    prev=glyph;
                }

                String clean=text.toString().replaceAll("\\s+"," ").trim();
                if(clean.isEmpty())continue;

                float cx=0f,cy=0f;
                for(TextPosition g:cellGlyphs){
                    cx+=g.getX()+g.getWidth()*0.5f;
                    cy+=g.getY()+g.getHeight()*0.5f;
                }
                cx/=cellGlyphs.size();
                cy/=cellGlyphs.size();

                float left=table.leftOf(cx);
                float right=table.rightOf(cx);
                float top=table.rowTop(cy);
                float bottom=table.rowBottom(cy);
                int col=cx<((table.x0+table.x1)*0.5f)?0:1;

                result.add(new LayoutUnit(clean,left,top,
                        Math.max(1f,right-left),Math.max(1f,bottom-top),
                        maxSize>0f?maxSize:8f,col));
            }

            Collections.sort(result,(x,y)->{
                int yy=Float.compare(x.y,y.y);
                return yy!=0?yy:Float.compare(x.x,y.x);
            });
            return result;
        }

        private void addUnits(List<LayoutUnit> out,List<LayoutLine> lines,
                              float pageWidth,List<TableRegion> tables){
            if(lines==null||lines.isEmpty())return;
            LayoutUnit current=null;
            for(LayoutLine line:lines){
                float verticalGap=current==null?Float.MAX_VALUE:line.y-current.bottom;
                boolean guideBetween=current!=null
                        && crossesTableColumn(current.x+current.width,line.x,current.y,line.y,tables);
                boolean rowBetween=current!=null
                        && crossesTableRow(current.y,current.bottom,line.y,tables);

                // A table divider means a new cell or a new table row. Elsewhere
                // keep the paragraph-merging behavior used for normal prose.
                float xShift=current==null?0f:Math.abs(line.x-current.x);
                boolean distinctVisualBlock=current!=null && xShift>60f;

                boolean newUnit=current==null || verticalGap>8f || guideBetween
                        || rowBetween || distinctVisualBlock;

                if(newUnit){
                    if(current!=null)out.add(current);
                    current=new LayoutUnit(line.source,line.x,line.y,line.width,line.height,line.fontSize,
                            columnOf(line,pageWidth),line.colorRgb);
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

        private boolean crossesTableColumn(float left,float right,float y1,float y2,
                                      List<TableRegion> tables){
            if(tables==null||tables.isEmpty()||right<=left+1f)return false;
            float midY=(y1+y2)*0.5f;
            for(TableRegion t:tables){
                if(midY<t.top-3f||midY>t.bottom+3f)continue;
                for(Float g:t.verticals){
                    if(g!=null&&g>left+1f&&g<right-0.5f)return true;
                }
            }
            return false;
        }

        private boolean crossesTableRow(float y1,float bottom1,float y2,
                                        List<TableRegion> tables){
            if(tables==null||tables.isEmpty())return false;
            float min=Math.min(bottom1,y2);
            float max=Math.max(y1,y2);
            float mid=(y1+y2)*0.5f;
            for(TableRegion t:tables){
                if(mid<t.top-3f||mid>t.bottom+3f)continue;
                for(Float h:t.horizontals){
                    if(h!=null&&h>min+0.8f&&h<max-0.3f)return true;
                }
            }
            return false;
        }

        private int columnOf(LayoutLine l,float pageWidth){
            float right=l.x+l.width;
            // Top-of-page journal material (running header, title, authors,
            // affiliations and figure captions above a top figure) stays before
            // the two-column body. The target journal pages begin their body
            // below roughly 330pt.
            if(l.y<330f)return -1;
            if(l.x<pageWidth*0.12f && right>pageWidth*0.58f)return -1;
            return l.x+l.width/2f<pageWidth/2f?0:1;
        }

        List<LayoutLine> buildLines(float pageWidth,List<TableRegion> tables){
            List<LayoutLine> out=new ArrayList<>();
            Set<Float> textTableDividers=inferTableDividerXs(pageWidth);
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
                if(row==null){row=new ArrayList<>();rows.add(row);}
                row.add(p);
            }

            for(List<TextPosition> row:rows){
                Collections.sort(row,Comparator.comparing(TextPosition::getX));
                List<TextPosition> piece=new ArrayList<>();
                TextPosition prev=null;
                for(TextPosition p:row){
                    if(prev!=null){
                        float prevRight=prev.getX()+prev.getWidth();
                        float gap=p.getX()-prevRight;

                        boolean crossesTwoColumns =
                                prevRight < pageWidth*0.49f
                                && p.getX() > pageWidth*0.51f
                                && gap > 8f;

                        boolean crossesVerticalGuide=false;
                        float pieceLeft=piece.isEmpty()?prev.getX():piece.get(0).getX();
                        float pieceY=piece.isEmpty()?prev.getY():piece.get(0).getY();
                        if(tables!=null){
                            for(TableRegion t:tables){
                                if(pieceY<t.top-3f||pieceY>t.bottom+3f)continue;
                                for(Float g:t.verticals){
                                    if(g!=null&&g>pieceLeft+0.5f&&g<p.getX()-0.5f){
                                        crossesVerticalGuide=true;
                                        break;
                                    }
                                }
                                if(crossesVerticalGuide)break;
                            }
                        }
                        if(!crossesVerticalGuide){
                            for(Float g:textTableDividers){
                                if(g!=null&&g>pieceLeft+0.5f&&g<p.getX()-0.5f
                                        &&Math.abs(g-(prev.getX()+prev.getWidth()))>0.5f){
                                    crossesVerticalGuide=true;
                                    break;
                                }
                            }
                        }

                        float threshold=Math.max(24f,prev.getFontSizeInPt()*3.5f);
                        if((gap>threshold||crossesTwoColumns||crossesVerticalGuide)&&!piece.isEmpty()){
                            addLine(out,piece);
                            piece=new ArrayList<>();
                        }
                    }
                    piece.add(p);
                    prev=p;
                }
                addLine(out,piece);
            }

            Collections.sort(out,(a,b)->{
                int y=Float.compare(a.y,b.y);
                return y!=0?y:Float.compare(a.x,b.x);
            });
            return out;
        }

        /**
         * Infer a table divider from repeated large horizontal gaps between
         * text clusters. This is the fallback for tables embedded in Form
         * XObjects, where PDFGraphicsStreamEngine on the page cannot see the
         * divider vector.
         */
        private Set<Float> inferTableDividerXs(float pageWidth){
            List<List<TextPosition>> rows=makeRows();
            Map<Float,Integer> counts=new HashMap<>();
            for(List<TextPosition> row:rows){
                if(row.size()<2)continue;
                Collections.sort(row,Comparator.comparing(TextPosition::getX));
                for(int i=1;i<row.size();i++){
                    TextPosition a=row.get(i-1), b=row.get(i);
                    float gap=b.getX()-(a.getX()+a.getWidth());
                    float x=(a.getX()+a.getWidth()+b.getX())*0.5f;
                    if(gap>=18f && x>pageWidth*0.10f && x<pageWidth*0.90f
                            && !(a.getX()+a.getWidth()<pageWidth*0.49f
                                 && b.getX()>pageWidth*0.51f)){
                        Float hit=null;
                        for(Float g:counts.keySet()){
                            if(Math.abs(g-x)<=7f){hit=g;break;}
                        }
                        if(hit==null)counts.put(x,1);
                        else{
                            int n=counts.remove(hit);
                            counts.put((hit+x)*0.5f,n+1);
                        }
                    }
                }
            }
            Set<Float> result=new HashSet<>();
            for(Map.Entry<Float,Integer> e:counts.entrySet()){
                if(e.getValue()>=4)result.add(e.getKey());
            }
            return result;
        }

        List<TableRegion> inferTextTables(float pageWidth){
            Set<Float> dividers=inferTableDividerXs(pageWidth);
            if(dividers.isEmpty())return Collections.emptyList();

            List<List<TextPosition>> rows=makeRows();
            List<Float> ys=new ArrayList<>();
            float xMin=Float.MAX_VALUE,xMax=0;
            for(List<TextPosition> row:rows){
                if(row.isEmpty())continue;
                boolean nearDivider=false;
                float min=row.get(0).getX();
                float max=row.get(row.size()-1).getX()+row.get(row.size()-1).getWidth();
                for(Float d:dividers){
                    if(d>min+8f&&d<max-8f){nearDivider=true;break;}
                }
                if(nearDivider){
                    ys.add(row.get(0).getY());
                    xMin=Math.min(xMin,min);
                    xMax=Math.max(xMax,max);
                }
            }
            if(ys.size()<4)return Collections.emptyList();

            Collections.sort(ys);
            float top=ys.get(0)-3f;
            float bottom=ys.get(ys.size()-1)+12f;

            List<Float> vs=new ArrayList<>();
            vs.add(xMin-2f);
            for(Float d:dividers)vs.add(d);
            vs.add(xMax+2f);
            vs=uniqueFloats(vs,2f);

            List<Float> hs=new ArrayList<>();
            // Text-derived rows do not need exact horizontal rules. The vector
            // table renderer will use these coarse row boundaries; actual text
            // units remain individually split by the same divider.
            for(Float y:ys)hs.add(y);
            hs=uniqueFloats(hs,5f);
            if(vs.size()<3)return Collections.emptyList();
            return Collections.singletonList(
                    new TableRegion(vs.get(0),vs.get(vs.size()-1),
                            top,bottom,vs,hs));
        }

        private List<List<TextPosition>> makeRows(){
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
                if(row==null){row=new ArrayList<>();rows.add(row);}
                row.add(p);
            }
            return rows;
        }

        private void addLine(List<LayoutLine> out,List<TextPosition> piece){
            if(piece==null||piece.isEmpty())return;
            StringBuilder s=new StringBuilder();float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE;
            float maxX=0,maxY=0,size=0;TextPosition prev=null;
            int colorRgb=0x000000;
            boolean colorCaptured=false;
            for(TextPosition p:piece){
                String u=p.getUnicode();if(u==null)continue;
                if(!colorCaptured){
                    Integer color=colorByGlyph.get(p);
                    if(color!=null){colorRgb=color;colorCaptured=true;}
                }
                if(prev!=null){
                    float gap=p.getX()-(prev.getX()+prev.getWidth());
                    float spaceThreshold=Math.max(0.85f,
                            Math.min(3.2f,Math.max(p.getFontSizeInPt(),prev.getFontSizeInPt())*0.16f));
                    if(gap>spaceThreshold&&s.length()>0)s.append(' ');
                }
                s.append(u);minX=Math.min(minX,p.getX());minY=Math.min(minY,p.getY());
                maxX=Math.max(maxX,p.getX()+p.getWidth());maxY=Math.max(maxY,p.getY()+p.getHeight());
                size=Math.max(size,p.getFontSizeInPt());prev=p;
            }
            if(s.length()>0&&maxX>minX)
                out.add(new LayoutLine(s.toString().trim(),minX,minY,maxX-minX,maxY-minY,size,colorRgb));
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
    /**
     * Remove source text operators from the page AND from Form XObjects.
     *
     * Many publisher PDFs place a table, header, or even the whole page inside
     * a Form XObject. Removing BT/ET only from the page stream therefore leaves
     * the original English text visible underneath the Vietnamese translation.
     * We recursively rewrite Form XObject streams while preserving every
     * non-text operator (fills, strokes, clipping, images, etc.).
     */
    private static void stripTextOperators(PDDocument doc,PDPage page)throws IOException{
        Set<Object> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        rewritePageWithoutText(doc,page);
        stripFormXObjects(page.getResources(),doc,visited);
    }

    private static void stripFormXObjects(PDResources resources,PDDocument doc,
                                          Set<Object> visited)throws IOException{
        if(resources==null)return;
        for(COSName name:resources.getXObjectNames()){
            try{
                PDXObject xo=resources.getXObject(name);
                if(xo instanceof com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject){
                    com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject form=
                            (com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject)xo;
                    Object key=form.getCOSObject();
                    if(!visited.add(key))continue;

                    PDFStreamParser parser=new PDFStreamParser(form.getCOSObject());
                    parser.parse();
                    List<Object> kept=filterNonTextTokens(parser.getTokens());

                    COSStream stream=form.getCOSObject();
                    try(OutputStream os=stream.createOutputStream()){
                        new ContentStreamWriter(os).writeTokens(kept);
                    }
                    stripFormXObjects(form.getResources(),doc,visited);
                }
            }catch(Exception ignored){
                // A malformed/unsupported form must not abort the whole PDF.
            }
        }
    }


    /**
     * Remove text-showing operators while preserving graphics-state changes.
     *
     * Important for publisher PDFs: color operators such as
     *   /CS0 cs 1 scn
     * are sometimes placed inside a BT...ET text object immediately before
     * a vector table is painted. BT/ET do NOT save/restore the graphics state,
     * so deleting every token inside BT...ET also deletes the table's color
     * state and makes the following fill render black. We therefore remove
     * text-state/text-showing operators but retain graphics-state operators
     * (colors, line width, clipping, q/Q, gs, paths, images, etc.).
     */
    private static List<Object> filterNonTextTokens(List<Object> tokens){
        List<Object> kept=new ArrayList<>();
        List<Object> pending=new ArrayList<>();
        boolean inText=false;

        for(Object token:tokens){
            if(!(token instanceof Operator)){
                if(inText) pending.add(token);
                else kept.add(token);
                continue;
            }

            String name=((Operator)token).getName();

            if("BT".equals(name)){
                // A text object starts. Operands collected before the next
                // operator belong to the operator that follows; discard them
                // until that operator is classified.
                inText=true;
                pending.clear();
                continue;
            }

            if("ET".equals(name)){
                // Any remaining operands were text operands with no valid
                // operator after them; discard them.
                inText=false;
                pending.clear();
                continue;
            }

            if(!inText){
                kept.addAll(pending);
                pending.clear();
                kept.add(token);
                continue;
            }

            if(isGraphicsOperator(name)){
                // Graphics operators are allowed/used by the publisher inside
                // a BT...ET section. Keep their operands too, e.g.
                // /CS0 cs 1 scn
                kept.addAll(pending);
                pending.clear();
                kept.add(token);
            }else{
                // Text state, positioning and text showing operators are
                // removed together with their operands.
                pending.clear();
            }
        }

        // Never leave orphan operands from an incomplete text object.
        return kept;
    }

    private static boolean isGraphicsOperator(String name){
        // Graphics state
        if("q".equals(name)||"Q".equals(name)||"cm".equals(name)
                ||"w".equals(name)||"J".equals(name)||"j".equals(name)
                ||"M".equals(name)||"d".equals(name)||"ri".equals(name)
                ||"i".equals(name)||"gs".equals(name))return true;

        // Device / calibrated / ICC / separation colors
        if("CS".equals(name)||"cs".equals(name)
                ||"SC".equals(name)||"SCN".equals(name)
                ||"sc".equals(name)||"scn".equals(name)
                ||"G".equals(name)||"g".equals(name)
                ||"RG".equals(name)||"rg".equals(name)
                ||"K".equals(name)||"k".equals(name))return true;

        // Path construction and painting
        if("m".equals(name)||"l".equals(name)||"c".equals(name)
                ||"v".equals(name)||"y".equals(name)||"h".equals(name)
                ||"re".equals(name)||"S".equals(name)||"s".equals(name)
                ||"F".equals(name)||"f".equals(name)||"f*".equals(name)
                ||"B".equals(name)||"B*".equals(name)
                ||"b".equals(name)||"b*".equals(name)
                ||"n".equals(name)||"W".equals(name)||"W*".equals(name))return true;

        // External graphics
        return "Do".equals(name)||"sh".equals(name);
    }

    private static boolean isTextOnlyOperator(String name){
        // Text state operators
        if("Tc".equals(name)||"Tw".equals(name)||"Tz".equals(name)
                ||"TL".equals(name)||"Tf".equals(name)||"Tr".equals(name)
                ||"Ts".equals(name))return true;

        // Text positioning operators
        if("Td".equals(name)||"TD".equals(name)||"Tm".equals(name)
                ||"T*".equals(name))return true;

        // Text showing operators
        if("Tj".equals(name)||"TJ".equals(name)
                ||"'".equals(name)||"\\\"".equals(name))return true;

        return false;
    }

    private static void rewritePageWithoutText(PDDocument doc,PDPage page)throws IOException{
        PDFStreamParser parser=new PDFStreamParser(page);
        parser.parse();
        List<Object> kept=filterNonTextTokens(parser.getTokens());
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
        final Exception error;
        final boolean quota;
        PageResult(int page,String text){this(page,text,null,false);}
        PageResult(int page,String text,Exception error,boolean quota){
            this.page=page;this.text=text;this.error=error;this.quota=quota;
        }
    }

    private static boolean isQuotaError(Throwable e){
        Throwable x=e;
        while(x!=null){
            String s=x.getMessage();
            if(s!=null){
                String low=s.toLowerCase(Locale.US);
                if(low.contains("resource_exhausted")
                        ||low.contains("quota exceeded")
                        ||low.contains("quota/rate limit")
                        ||low.contains("rate limit")
                        ||low.contains("rate_limit")
                        ||low.contains("too many requests")
                        ||low.contains("http 429")
                        ||low.contains("code 429")) return true;
            }
            x=x.getCause();
        }
        return false;
    }

    private static String safeLog(String s){
        if(s==null)return "unknown";
        return s.replace('\n',' ').replace('\r',' ').replace('|','/');
    }

    private static String fileHash(File f){
        try{
            java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(f)){
                byte[] b=new byte[16384]; int n;
                while((n=in.read(b))>0)md.update(b,0,n);
            }
            StringBuilder s=new StringBuilder();
            for(byte x:md.digest())s.append(String.format(Locale.US,"%02x",x));
            return s.toString();
        }catch(Exception e){return "unknown";}
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