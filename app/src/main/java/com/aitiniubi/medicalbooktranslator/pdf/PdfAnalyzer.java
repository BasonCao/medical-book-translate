package com.aitiniubi.medicalbooktranslator.pdf;

import android.content.Context;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.File;

public final class PdfAnalyzer {
    private PdfAnalyzer(){}
    public static PdfBook analyze(Context context,File file)throws Exception{
        PDFBoxResourceLoader.init(context.getApplicationContext());
        try(PDDocument doc=PDDocument.load(file)){
            PDFTextStripper stripper=new PDFTextStripper();
            int text=0,empty=0;
            for(int i=1;i<=doc.getNumberOfPages();i++){
                stripper.setStartPage(i);stripper.setEndPage(i);
                String s=stripper.getText(doc);
                if(s!=null&&!s.trim().isEmpty())text++;else empty++;
            }
            String title=doc.getDocumentInformation().getTitle();
            if(title==null||title.trim().isEmpty())title=file.getName();
            return new PdfBook(title,doc.getNumberOfPages(),text,empty);
        }
    }
}
