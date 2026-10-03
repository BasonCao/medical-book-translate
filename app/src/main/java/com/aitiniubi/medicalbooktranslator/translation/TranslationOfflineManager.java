package com.aitiniubi.medicalbooktranslator.translation;

import android.content.Context;
import java.io.File;

public final class TranslationOfflineManager {
    private TranslationOfflineManager(){}
    public static String status(Context c){
        File f=OfflineNllbTranslator.modelFile(c);
        if(f.isFile())return "🟢 Offline NLLB 600M đã cài — "+String.format(java.util.Locale.US,"%.0f MB",f.length()/1048576.0);
        return "⚪ Offline NLLB 600M chưa tải";
    }
}