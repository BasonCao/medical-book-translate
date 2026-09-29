package com.aitiniubi.medicalbooktranslator.pdf;

public final class PdfBook {
    public final String title;
    public final int pageCount;
    public final int textPages;
    public final int emptyPages;
    public PdfBook(String title,int pageCount,int textPages,int emptyPages){
        this.title=title;this.pageCount=pageCount;this.textPages=textPages;this.emptyPages=emptyPages;
    }
}
