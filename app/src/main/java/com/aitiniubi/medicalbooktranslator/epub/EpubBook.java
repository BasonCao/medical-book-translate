package com.aitiniubi.medicalbooktranslator.epub;

import java.util.*;

public final class EpubBook {
    public final String sourcePath;
    public final List<String> xhtmlFiles = new ArrayList<>();
    public final List<String> imageFiles = new ArrayList<>();
    public final List<String> cssFiles = new ArrayList<>();
    public int totalFiles;
    public int paragraphCount;
    public int figureCount;
    public int imageReferenceCount;
    public int tableCount;
    public String title = "";
    public EpubBook(String sourcePath) { this.sourcePath = sourcePath; }
}
