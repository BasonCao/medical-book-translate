package com.aitiniubi.medicalbooktranslator;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.aitiniubi.medicalbooktranslator.epub.*;
import com.aitiniubi.medicalbooktranslator.pdf.*;
import com.aitiniubi.medicalbooktranslator.translation.*;
import java.io.*;
import java.util.*;
import okhttp3.*;
import org.json.*;

public class MainActivity extends Activity {
    private static final String OPENROUTER_ENDPOINT="https://openrouter.ai/api/v1/chat/completions";
    private static final String DEFAULT_OR_MODEL="inclusionai/ling-3.0-flash-sante:free";
    private static final String GEMINI_ENDPOINT="https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
    private static final String OPENAI_ENDPOINT="https://api.openai.com/v1/responses";
    private static final String DEEPSEEK_ENDPOINT="https://api.deepseek.com/v1/chat/completions";
    private static final String MISTRAL_ENDPOINT="https://api.mistral.ai/v1/chat/completions";

    private static final String[] PROVIDERS={"OpenRouter — FREE / PAID","Google Gemini — FREE / PAID","OpenAI — PAID","DeepSeek — PAID","Mistral — PAID","Custom OpenAI-compatible"};
    private static final String[] OR_MODELS={"openrouter/free","inclusionai/ling-3.0-flash-sante:free","nvidia/nemotron-3-ultra:free","qwen/qwen3.8-27b:free","google/gemma-4-31b-it:free","google/gemma-4-26b-a4b-it:free","inclusionai/ling-3.0-flash-fin:free"};
    private static final String[] OR_LABELS={"Auto Free Router","Ling 3.0 Flash Sante — Medical","NVIDIA Nemotron 3 Ultra — Free","Qwen 3.8 27B — Free","Gemma 4 31B — Free","Gemma 4 26B A4B — Free","Ling 3.0 Flash Fin — Free"};
    private static final String[] GEMINI_MODELS={"gemini-3.5-flash-lite","gemini-3.7-flash","gemini-3.8-flash","gemini-3.5-flash","gemini-3.1-flash-lite","gemini-2.5-flash","gemini-2.5-flash-lite"};
    private static final String[] GEMINI_LABELS={"Gemini 3.5 Flash-Lite — FREE TIER / translation","Gemini 3.7 Flash — FREE TIER","Gemini 3.8 Flash — FREE TIER","Gemini 3.5 Flash — FREE TIER","Gemini 3.1 Flash-Lite — FREE TIER / translation","Gemini 2.5 Flash — FREE TIER*","Gemini 2.5 Flash-Lite — FREE TIER*"};
    private static final String GEMINI_MODELS_API="https://generativelanguage.googleapis.com/v1beta/models";
    private static final String[] OPENAI_MODELS={"gpt-5.6-luna","gpt-5.6-terra","gpt-5.6-sol"};
    private static final String[] OPENAI_LABELS={"GPT-5.6 Luna — PAID / low cost","GPT-5.6 Terra — PAID","GPT-5.6 Sol — PAID"};
    private static final String[] DEEPSEEK_MODELS={"deepseek-flash","deepseek-v4-pro"};
    private static final String[] DEEPSEEK_LABELS={"DeepSeek V4.1 Flash — PAID","DeepSeek V4 Pro — PAID"};
    private static final String[] MISTRAL_MODELS={"mistral-small-latest","mistral-large-latest"};
    private static final String[] MISTRAL_LABELS={"Mistral Small — PAID","Mistral Large — PAID"};

    private static final String PREF_OR_KEY="apiKey_openrouter";
    private static final String PREF_GEMINI_KEY="apiKey_gemini";
    private static final String PREF_OPENAI_KEY="apiKey_openai";
    private static final String PREF_DEEPSEEK_KEY="apiKey_deepseek";
    private static final String PREF_MISTRAL_KEY="apiKey_mistral";
    private static final String PREF_CUSTOM_KEY="apiKey_custom";
    private static final String PREF_FREE_POOL="free_ai_pool";
    private static final String PREF_ALLOW_PAID="allow_paid_fallback";
    private static final String OFFLINE_PROVIDER="Offline NLLB-600M";
    private static final String OFFLINE_MODEL_ID="nllb-600m-Q4_0";
    private static final int REQ_OFFLINE_MODEL=1907;

    private TextView status,report;
    private ProgressBar progress;
    private Button analyze,translate,export,reset,logButton,pdfLayout,pdfOneColumn,pdfKeepLayout,offlineButton;
    private File selectedFile,lastOutput,workspace;
    private EpubBook book;
    private PdfBook pdfBook;
    private boolean pdfMode=false;
    private android.content.SharedPreferences prefsHolder;
    private volatile boolean translating=false;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(com.aitiniubi.medicalbooktranslator.R.layout.activity_main);
        TextView appTitle=findViewById(R.id.appTitle);
        appTitle.setText("Medical Book Translator V1.13.0");
        prefsHolder=getSharedPreferences("config",MODE_PRIVATE);
        status=findViewById(R.id.status);
        report=findViewById(R.id.report);
        progress=findViewById(R.id.progress);
        Button open=findViewById(R.id.openButton);
        analyze=findViewById(R.id.analyzeButton);
        translate=findViewById(R.id.translateButton);
        Button settings=findViewById(R.id.settingsButton);
        Button glossary=findViewById(R.id.glossaryButton);
        export=findViewById(R.id.exportButton);
        logButton=findViewById(R.id.logButton);
        reset=findViewById(R.id.resetButton);
        pdfLayout=findViewById(R.id.pdfLayoutButton);
        pdfOneColumn=findViewById(R.id.pdfOneColumnButton);
        pdfKeepLayout=findViewById(R.id.pdfKeepLayoutButton);
        offlineButton=findViewById(R.id.offlineButton);

        open.setOnClickListener(v->pick());
        analyze.setOnClickListener(v->analyze());
        settings.setOnClickListener(v->{try{settings();}catch(Exception e){showError(e);}});
        glossary.setOnClickListener(v->glossary());
        translate.setOnClickListener(v->translate());
        export.setOnClickListener(v->saveOutput());
        pdfLayout.setOnClickListener(v->choosePdfLayoutAndTranslate(fallbackProviders()));
        pdfOneColumn.setOnClickListener(v->startPdfWithLayout(true));
        pdfKeepLayout.setOnClickListener(v->startPdfWithLayout(false));
        if(offlineButton!=null) offlineButton.setOnClickListener(v->{
            if(OfflineModelManager.isReady(this)) runOfflineSelfTest();
            else manageOfflineModel();
        });
        updateOfflineButton();
        updatePdfLayoutButtons();
        reset.setOnClickListener(v->resetProgress());
        if(logButton!=null) logButton.setOnClickListener(v->exportTranslationLog());

        restoreWorkspace();
    }

    private void restoreWorkspace(){
        String path=prefsHolder.getString("activeWorkspace","");
        if(path.isEmpty())return;
        File ws=new File(path);
        File epub=new File(ws,"source.epub"), pdf=new File(ws,"source.pdf");
        if(epub.isFile()){ selectedFile=epub; pdfMode=false; }
        else if(pdf.isFile()){ selectedFile=pdf; pdfMode=true; }
        else return;
        workspace=ws;
        File draft=new File(ws,pdfMode?"translated-current.pdf":"translated-current.epub");
        lastOutput=draft.isFile()?draft:null;
        analyze.setEnabled(true);translate.setEnabled(!pdfMode);export.setEnabled(lastOutput!=null);
        updatePdfLayoutButtons();
        TranslationStateStore store=new TranslationStateStore(ws);
        status.setText("📖 Workspace đã lưu\n"+selectedFile.getName()+"\n"+store.summary()+"\nCó thể bấm Dịch / Tiếp tục.");
        report.setText(pdfMode ? "PDF text layer: có thể dịch. PDF scan/image-only không hỗ trợ." : "Tiến độ EPUB được lưu bền vững trong máy. Hết quota hoặc đóng app vẫn có thể tiếp tục.");
    }

    private void pick(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/epub+zip","application/pdf"});
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,10);
    }

    @Override protected void onActivityResult(int r,int c,Intent d){
        if(r==REQ_OFFLINE_MODEL){
            if(c!=RESULT_OK||d==null||d.getData()==null)return;
            Uri uri=d.getData();
            if(offlineButton!=null)offlineButton.setEnabled(false);
            progress.setVisibility(View.VISIBLE);progress.setIndeterminate(false);progress.setMax(100);
            report.setText("📦 Đang nhập model NLLB-600M…");
            new Thread(()->{
                File tmp=OfflineModelManager.partial(this);
                File dst=OfflineModelManager.model(this);
                try{
                    File dir=tmp.getParentFile();
                    if(dir!=null && !dir.exists() && !dir.mkdirs())throw new IOException("Không tạo được thư mục model.");
                    long expected=-1L;
                    try(android.content.res.AssetFileDescriptor afd=getContentResolver().openAssetFileDescriptor(uri,"r")){
                        if(afd!=null)expected=afd.getLength();
                    }
                    if(expected>0 && expected<450L*1024L*1024L)throw new IOException("File model quá nhỏ: "+(expected/(1024*1024))+" MB.");
                    try(InputStream in=getContentResolver().openInputStream(uri)){
                        if(in==null)throw new IOException("Không mở được file model đã chọn.");
                        try(OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp,false))){
                            byte[] buf=new byte[1024*1024]; long done=0; int n;
                            while((n=in.read(buf))>0){
                                out.write(buf,0,n); done+=n;
                                final long dDone=done,total=expected;
                                runOnUiThread(()->{
                                    int p=total>0?(int)Math.min(100,(dDone*100L)/total):0;
                                    progress.setProgress(p);
                                    report.setText("📦 Nhập model: "+(dDone/(1024*1024))+" MB"+(total>0?" / "+(total/(1024*1024))+" MB":""));
                                });
                            }
                        }
                    }
                    if(tmp.length()<450L*1024L*1024L)throw new IOException("File model chưa đủ kích thước Q4_0: "+(tmp.length()/(1024*1024))+" MB.");
                    if(dst.exists() && !dst.delete())throw new IOException("Không thay thế được model cũ.");
                    if(!tmp.renameTo(dst))throw new IOException("Không thể hoàn tất việc nhập model.");
                    if(!OfflineModelManager.isReady(this))throw new IOException("Model đã nhập nhưng engine offline chưa sẵn sàng.");
                    prefsHolder.edit().putBoolean("offline_enabled",true).apply();
                    runOnUiThread(()->{
                        progress.setVisibility(View.GONE);
                        if(offlineButton!=null)offlineButton.setEnabled(true);
                        updateOfflineButton();
                        report.setText("✅ Model NLLB-600M đã được cài từ file.\nDịch OFFLINE không cần Internet/API key.");
                    });
                }catch(Exception ex){
                    runOnUiThread(()->{
                        progress.setVisibility(View.GONE);
                        if(offlineButton!=null)offlineButton.setEnabled(true);
                        updateOfflineButton();
                        showError(ex);
                    });
                }
            },"offline-model-import").start();
            return;
        }
        super.onActivityResult(r,c,d);
        if(r==12&&c==RESULT_OK&&d!=null){
            final Uri u=d.getData();
            if(u==null||workspace==null){Toast.makeText(this,"Không có file CSV hoặc workspace.",Toast.LENGTH_SHORT).show();return;}
            Toast.makeText(this,"⏳ Đang nạp CSV ở nền… Không cần chờ trên màn hình.",Toast.LENGTH_SHORT).show();
            new Thread(()->{
                try(InputStream in=getContentResolver().openInputStream(u)){
                    if(in==null)throw new IOException("Không mở được file CSV.");
                    final int n=GlossaryManager.importCsv(workspace,in);
                    final int total=GlossaryManager.load(workspace).size();
                    runOnUiThread(()->{
                        Toast.makeText(this,"✅ Đã nạp thêm "+n+" thuật ngữ. Tổng: "+total,Toast.LENGTH_LONG).show();
                        report.setText("📚 Glossary: "+total+" thuật ngữ đang được áp dụng khi dịch batch.");
                    });
                }catch(Exception e){
                    runOnUiThread(()->showError(e));
                }
            },"Glossary-CSV-Import").start();
            return;
        }
        if(r==13&&c==RESULT_OK&&d!=null){
            try(FileInputStream in=new FileInputStream(new File(workspace,"translation-debug.log"));OutputStream out=getContentResolver().openOutputStream(d.getData())){
                if(out==null)throw new IOException("Không mở được nơi lưu log.");
                byte[] b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);
                Toast.makeText(this,"Đã xuất translation-debug.log",Toast.LENGTH_LONG).show();
            }catch(Exception e){showError(e);}
            return;
        }
        if(r==11&&c==RESULT_OK&&d!=null){
            try{
                if(lastOutput==null||!lastOutput.isFile())throw new IOException(pdfMode?"Chưa có PDF draft để xuất.":"Chưa có EPUB draft để xuất.");
                try(InputStream in=new FileInputStream(lastOutput);OutputStream out=getContentResolver().openOutputStream(d.getData())){
                    byte[] b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);
                }
                Toast.makeText(this,pdfMode?"Đã xuất PDF":"Đã xuất EPUB",Toast.LENGTH_LONG).show();
            }catch(Exception e){showError(e);}
            return;
        }
        if(r==10&&c==RESULT_OK&&d!=null){
            try{
                Uri u=d.getData();
                String mime=getContentResolver().getType(u);
                String name=u.getLastPathSegment()==null?"":u.getLastPathSegment().toLowerCase(Locale.US);
                // Do not trust the provider MIME/filename alone: some Android document providers
                // expose a PDF as application/octet-stream or an opaque content name. Inspect the
                // actual file signature so a PDF can never be sent into the EPUB ZIP parser.
                File temp=new File(getCacheDir(),"picked.bin");
                try(InputStream in=getContentResolver().openInputStream(u);FileOutputStream out=new FileOutputStream(temp)){
                    if(in==null)throw new IOException("Không mở được file đã chọn.");
                    byte[] b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);
                }
                byte[] header=new byte[8];
                int headerRead=0;
                try(InputStream hin=new FileInputStream(temp)){
                    headerRead=hin.read(header);
                }
                boolean pdfSignature=headerRead>=5
                        && header[0]=='%' && header[1]=='P' && header[2]=='D' && header[3]=='F' && header[4]=='-';
                boolean zipSignature=headerRead>=4
                        && header[0]=='P' && header[1]=='K'
                        && ((header[2]==3 && header[3]==4) || (header[2]==5 && header[3]==6) || (header[2]==7 && header[3]==8));
                if(pdfSignature){
                    pdfMode=true;
                }else if(zipSignature){
                    pdfMode=false;
                }else{
                    throw new IOException("File không phải PDF/EPUB hợp lệ. App chỉ nhận PDF có text layer hoặc EPUB.");
                }
                String ext=pdfMode?".pdf":".epub";
                File normalized=new File(getCacheDir(),"picked"+ext);
                if(!temp.renameTo(normalized)){
                    copyFile(temp,normalized);
                    temp.delete();
                }
                temp=normalized;
                if(pdfMode && temp.length()<5)throw new IOException("File PDF rỗng hoặc không hợp lệ.");
                String hash=TranslationStateStore.sha256(temp);
                workspace=new File(new File(getFilesDir(),"translation_workspaces"),hash);
                if(pdfLayout!=null){updatePdfLayoutButtons();}
                if(!workspace.exists()&&!workspace.mkdirs())throw new IOException("Không tạo được translation workspace.");
                selectedFile=new File(workspace,"source"+ext);
                copyFile(temp,selectedFile);
                temp.delete();
                prefsHolder.edit().putString("activeWorkspace",workspace.getAbsolutePath()).putBoolean("activePdf",pdfMode).apply();
                File draft=new File(workspace,pdfMode?"translated-current.pdf":"translated-current.epub");
                lastOutput=draft.isFile()?draft:null;
                analyze.setEnabled(true);translate.setEnabled(!pdfMode);export.setEnabled(lastOutput!=null);
                updatePdfLayoutButtons();
                status.setText("Đã chọn: "+u.getLastPathSegment()+"\nLoại: "+(pdfMode?"PDF text layer":"EPUB")+"\nWorkspace: "+workspace.getName());
                report.setText(pdfMode ? "Bấm Phân tích PDF. App chỉ dịch PDF có text layer, không xử lý PDF scan." : "Bấm Phân tích EPUB để kiểm tra cấu trúc, hoặc Dịch / Tiếp tục để chạy translation queue.");
            }catch(Exception e){showError(e);}
        }
    }

    private void analyze(){
        if(selectedFile==null)return;
        progress.setVisibility(View.VISIBLE);progress.setIndeterminate(true);
        report.setText(pdfMode ? "Đang phân tích PDF…" : "Đang phân tích cấu trúc EPUB…");
        new Thread(()->{
            try{
                if(pdfMode){
                    pdfBook=PdfAnalyzer.analyze(this,selectedFile);
                    runOnUiThread(()->{
                        progress.setVisibility(View.GONE);progress.setIndeterminate(false);
                        report.setText("PDF: "+pdfBook.title+
                                "\nSố trang: "+pdfBook.pageCount+
                                "\nTrang có text: "+pdfBook.textPages+
                                "\nTrang không có text: "+pdfBook.emptyPages+
                                "\n\nChỉ PDF có text layer được dịch. PDF scan/image-only sẽ không được xử lý.");
                    });
                } else {
                    book=EpubAnalyzer.analyze(selectedFile);
                    runOnUiThread(()->{
                        progress.setVisibility(View.GONE);progress.setIndeterminate(false);
                        report.setText("EPUB: "+book.title+
                                "\nFiles: "+book.totalFiles+
                                "\nXHTML: "+book.xhtmlFiles.size()+
                                "\nĐoạn văn: "+book.paragraphCount+
                                "\nHình/ảnh: "+book.imageReferenceCount+
                                "\nFigure: "+book.figureCount+
                                "\nBảng: "+book.tableCount+
                                "\n\nTranslation V1.5: paragraph + heading + list + table-cell queue, lưu từng unit và rebuild draft sau mỗi batch.");
                    });
                }
            }catch(Exception e){runOnUiThread(()->showError(e));}
        }).start();
    }

    private String legacyKeyForEndpoint(String endpoint){
        if(endpoint!=null&&endpoint.contains("openrouter.ai"))return prefsHolder.getString(PREF_OR_KEY,"");
        if(endpoint!=null&&endpoint.contains("generativelanguage.googleapis.com"))return prefsHolder.getString(PREF_GEMINI_KEY,"");
        if(endpoint!=null&&endpoint.contains("api.openai.com"))return prefsHolder.getString(PREF_OPENAI_KEY,"");
        if(endpoint!=null&&endpoint.contains("api.deepseek.com"))return prefsHolder.getString(PREF_DEEPSEEK_KEY,"");
        if(endpoint!=null&&endpoint.contains("api.mistral.ai"))return prefsHolder.getString(PREF_MISTRAL_KEY,"");
        return prefsHolder.getString(PREF_CUSTOM_KEY,"");
    }

    private String providerFor(String endpoint){
        if(endpoint!=null&&endpoint.contains("openrouter.ai"))return PROVIDERS[0];
        if(endpoint!=null&&endpoint.contains("generativelanguage.googleapis.com"))return PROVIDERS[1];
        if(endpoint!=null&&endpoint.contains("api.openai.com"))return PROVIDERS[2];
        if(endpoint!=null&&endpoint.contains("api.deepseek.com"))return PROVIDERS[3];
        if(endpoint!=null&&endpoint.contains("api.mistral.ai"))return PROVIDERS[4];
        return PROVIDERS[5];
    }

    private String providerKey(String provider){
        if(provider.equals(OFFLINE_PROVIDER))return "local";
        if(provider.equals(PROVIDERS[0])){
            String k=prefsHolder.getString(PREF_OR_KEY,"");
            if(k.isEmpty()&&providerFor(prefsHolder.getString("endpoint",OPENROUTER_ENDPOINT)).equals(PROVIDERS[0]))k=prefsHolder.getString("apiKey","");
            return k;
        }
        if(provider.equals(PROVIDERS[1]))return prefsHolder.getString(PREF_GEMINI_KEY,"");
        if(provider.equals(PROVIDERS[2]))return prefsHolder.getString(PREF_OPENAI_KEY,"");
        if(provider.equals(PROVIDERS[3]))return prefsHolder.getString(PREF_DEEPSEEK_KEY,"");
        if(provider.equals(PROVIDERS[4]))return prefsHolder.getString(PREF_MISTRAL_KEY,"");
        return prefsHolder.getString(PREF_CUSTOM_KEY,"");
    }

    private String providerModel(String provider){
        if(provider.equals(OFFLINE_PROVIDER))return OFFLINE_MODEL_ID;
        if(provider.equals(PROVIDERS[0])){
            String m=prefsHolder.getString("model_openrouter",DEFAULT_OR_MODEL);
            return "openrouter/free".equals(m)?DEFAULT_OR_MODEL:m;
        }
        if(provider.equals(PROVIDERS[1]))return prefsHolder.getString("model_gemini",GEMINI_MODELS[0]);
        if(provider.equals(PROVIDERS[2]))return prefsHolder.getString("model_openai",OPENAI_MODELS[0]);
        if(provider.equals(PROVIDERS[3]))return prefsHolder.getString("model_deepseek",DEEPSEEK_MODELS[0]);
        if(provider.equals(PROVIDERS[4]))return prefsHolder.getString("model_mistral",MISTRAL_MODELS[0]);
        return prefsHolder.getString("model_custom","");
    }

    private TranslationConfig makeProviderConfig(String p){
        TranslationConfig c=new TranslationConfig();
        if(p.equals(OFFLINE_PROVIDER)){c.endpoint="offline://nllb";c.model=OFFLINE_MODEL_ID;c.apiKey="local";return c;}
        if(p.equals(PROVIDERS[0]))c.endpoint=OPENROUTER_ENDPOINT;
        else if(p.equals(PROVIDERS[1]))c.endpoint=GEMINI_ENDPOINT;
        else if(p.equals(PROVIDERS[2]))c.endpoint=OPENAI_ENDPOINT;
        else if(p.equals(PROVIDERS[3]))c.endpoint=DEEPSEEK_ENDPOINT;
        else if(p.equals(PROVIDERS[4]))c.endpoint=MISTRAL_ENDPOINT;
        else c.endpoint=prefsHolder.getString("endpoint_custom","");
        c.model=providerModel(p);c.apiKey=providerKey(p);return c;
    }

    private boolean isFreePoolProvider(String p){
        if(p.equals(OFFLINE_PROVIDER)) return true;
        if(p.equals(PROVIDERS[0])) return true;
        if(p.equals(PROVIDERS[1])) return isFreeGeminiModel(providerModel(p));
        return false;
    }

    private boolean isFreeGeminiModel(String model){
        if(model==null) return false;
        String m=model.toLowerCase(Locale.US);
        return m.startsWith("gemini-") && m.contains("flash")
                && !m.contains("pro") && !m.contains("image")
                && !m.contains("live") && !m.contains("tts")
                && !m.contains("embedding");
    }

    private List<TranslationRouter.Provider> fallbackProviders(){
        List<TranslationRouter.Provider> profiles=savedProfileProviders();
        List<TranslationRouter.Provider> offlineFirst=new ArrayList<>();
        if(prefsHolder.getBoolean("offline_enabled",true) && OfflineModelManager.isReady(this)){
            offlineFirst.add(new TranslationRouter.Provider(OFFLINE_PROVIDER,makeProviderConfig(OFFLINE_PROVIDER)));
        }
        if(!profiles.isEmpty()){
            offlineFirst.addAll(profiles);
            return offlineFirst;
        }
        boolean freeOnly=prefsHolder.getBoolean(PREF_FREE_POOL,true),allowPaid=prefsHolder.getBoolean(PREF_ALLOW_PAID,false);
        String selected=providerFor(prefsHolder.getString("endpoint",OPENROUTER_ENDPOINT));
        String[] order=freeOnly?new String[]{selected,PROVIDERS[0],PROVIDERS[1],allowPaid?PROVIDERS[2]:"",allowPaid?PROVIDERS[3]:"",allowPaid?PROVIDERS[4]:"",allowPaid?PROVIDERS[5]:""}:new String[]{selected,PROVIDERS[0],PROVIDERS[1],PROVIDERS[2],PROVIDERS[3],PROVIDERS[4],PROVIDERS[5]};
        List<TranslationRouter.Provider> out=new ArrayList<>(offlineFirst);HashSet<String> seen=new HashSet<>();
        for(TranslationRouter.Provider op:offlineFirst)seen.add(op.name);
        for(String p:order){
            if(p==null||p.isEmpty()||!seen.add(p))continue;
            if(freeOnly&&!isFreePoolProvider(p)&&!allowPaid)continue;
            TranslationConfig cfg=makeProviderConfig(p);
            if(cfg.endpoint!=null&&!cfg.endpoint.trim().isEmpty()&&cfg.model!=null&&!cfg.model.trim().isEmpty()&&cfg.apiKey!=null&&!cfg.apiKey.trim().isEmpty())out.add(new TranslationRouter.Provider(p,cfg));
        }
        return out;    }

    private int indexOf(String[] a,String v){for(int i=0;i<a.length;i++)if(a[i].equals(v))return i;return -1;}
    private String[] modelsFor(String p){if(p==null||p.trim().isEmpty())return OR_MODELS;if(p.equals(PROVIDERS[0]))return OR_MODELS;if(p.equals(PROVIDERS[1]))return geminiCatalog()[0];if(p.equals(PROVIDERS[2]))return OPENAI_MODELS;if(p.equals(PROVIDERS[3]))return DEEPSEEK_MODELS;if(p.equals(PROVIDERS[4]))return MISTRAL_MODELS;return new String[]{providerModel(PROVIDERS[5])};}
    private String[] labelsFor(String p){if(p==null||p.trim().isEmpty())return OR_LABELS;if(p.equals(PROVIDERS[0]))return OR_LABELS;if(p.equals(PROVIDERS[1]))return geminiCatalog()[1];if(p.equals(PROVIDERS[2]))return OPENAI_LABELS;if(p.equals(PROVIDERS[3]))return DEEPSEEK_LABELS;if(p.equals(PROVIDERS[4]))return MISTRAL_LABELS;return new String[]{providerModel(PROVIDERS[5])};}

    private String[][] geminiCatalog(){
        String idsRaw=prefsHolder.getString("gemini_catalog_ids","");
        String labelsRaw=prefsHolder.getString("gemini_catalog_labels","");
        if(!idsRaw.trim().isEmpty()&&!labelsRaw.trim().isEmpty()){
            String[] ids=idsRaw.split("\\n",-1),labels=labelsRaw.split("\\n",-1);
            if(ids.length==labels.length&&ids.length>0)return new String[][]{ids,labels};
        }
        return new String[][]{GEMINI_MODELS,GEMINI_LABELS};
    }

    private void refreshGeminiCatalog(Spinner model,TextView note,Runnable refresh,String apiKey){
        final String requestApiKey=apiKey==null?"":apiKey.trim();
        if(requestApiKey.isEmpty()){new AlertDialog.Builder(this).setTitle("Chưa có Gemini API key").setMessage("Nhập Gemini API key trước, rồi bấm làm mới danh sách model.").setPositiveButton("OK",null).show();return;}
        Toast.makeText(this,"Đang lấy danh sách Gemini model từ Google…",Toast.LENGTH_SHORT).show();
        new Thread(()->{try{
            HttpUrl url=HttpUrl.parse(GEMINI_MODELS_API).newBuilder().addQueryParameter("key",requestApiKey).build();
            Request req=new Request.Builder().url(url).get().build();
            try(Response res=new OkHttpClient().newCall(req).execute()){
                String body=res.body()==null?"":res.body().string();
                if(!res.isSuccessful())throw new IOException("Gemini models API HTTP "+res.code()+"\\n"+body);
                JSONArray arr=new JSONObject(body).optJSONArray("models");
                if(arr==null)throw new IOException("Gemini models API không trả về danh sách models.");
                LinkedHashMap<String,String> found=new LinkedHashMap<>();
                for(int i=0;i<arr.length();i++){
                    JSONObject m=arr.optJSONObject(i);if(m==null)continue;
                    String name=m.optString("name",""),id=name.startsWith("models/")?name.substring(7):name;
                    JSONArray methods=m.optJSONArray("supportedGenerationMethods");boolean generate=false;
                    if(methods!=null)for(int j=0;j<methods.length();j++)if("generateContent".equalsIgnoreCase(methods.optString(j)))generate=true;
                    String low=id.toLowerCase(Locale.US);
                    if(!generate||!low.startsWith("gemini-"))continue;
                    if(low.contains("-tts")||low.contains("-live")||low.contains("image")||low.contains("transcribe")||low.contains("embedding")||low.contains("robotics")||low.contains("computer-use")||low.contains("deep-research")||low.contains("omni"))continue;
                    String display=m.optString("displayName",id),desc=m.optString("description",""),suffix=desc.toLowerCase(Locale.US).contains("preview")||low.contains("preview")?" — PREVIEW":"";
                    found.put(id,display+suffix);
                }
                if(found.isEmpty())throw new IOException("Không tìm thấy Gemini model text hỗ trợ generateContent.");
                StringBuilder ids=new StringBuilder(),labels=new StringBuilder();int n=0;
                for(Map.Entry<String,String> e:found.entrySet()){if(n++>0){ids.append("\\n");labels.append("\\n");}ids.append(e.getKey());labels.append(e.getValue());}
                prefsHolder.edit().putString("gemini_catalog_ids",ids.toString()).putString("gemini_catalog_labels",labels.toString()).apply();
                runOnUiThread(()->{refresh.run();note.setText("Gemini catalog đã cập nhật từ Google: "+found.size()+" model text hỗ trợ generateContent.");Toast.makeText(this,"Đã cập nhật "+found.size()+" Gemini model.",Toast.LENGTH_LONG).show();});
            }
        }catch(Exception e){runOnUiThread(()->showError(e));}}).start();
    }

    private void glossary(){
        if(workspace==null){
            new AlertDialog.Builder(this).setTitle("Thuật ngữ chuyên ngành")
                    .setMessage("Hãy chọn EPUB/PDF trước để tạo workspace cho glossary.")
                    .setPositiveButton("OK",null).show();return;
        }
        final ArrayList<GlossaryManager.Term> all=new ArrayList<>(GlossaryManager.load(workspace));
        final ArrayList<GlossaryManager.Term> shown=new ArrayList<>(all);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(12,4,12,4);
        TextView info=new TextView(this);info.setText("Tổng "+all.size()+" thuật ngữ. Chạm một dòng để chỉnh sửa. Nạp CSV sẽ cộng thêm, không ghi đè.");root.addView(info);
        EditText search=new EditText(this);search.setSingleLine(true);search.setHint("🔎 Tìm English / Vietnamese");root.addView(search);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button add=new Button(this);add.setText("➕ THÊM");Button imp=new Button(this);imp.setText("📥 NẠP THÊM CSV");
        actions.addView(add,new LinearLayout.LayoutParams(0,-2,1));actions.addView(imp,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);
        final ListView list=new ListView(this);list.setDividerHeight(1);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        BaseAdapter adapter=new BaseAdapter(){
            public int getCount(){return shown.size();} public Object getItem(int position){return shown.get(position);} public long getItemId(int position){return position;}
            public View getView(int position,View convertView,android.view.ViewGroup parent){
                TextView tv=(TextView)(convertView instanceof TextView?convertView:new TextView(MainActivity.this));
                GlossaryManager.Term t=shown.get(position);tv.setText((position+1)+". "+t.english+" → "+t.vietnamese);tv.setTextSize(15);tv.setPadding(12,12,12,12);tv.setMaxLines(2);return tv;
            }
        };
        list.setAdapter(adapter);
        Runnable filter=()->{
            String q=search.getText().toString().trim().toLowerCase(Locale.US);shown.clear();
            for(GlossaryManager.Term t:all){String en=t.english==null?"":t.english.toLowerCase(Locale.US);String vi=t.vietnamese==null?"":t.vietnamese.toLowerCase(Locale.US);if(q.isEmpty()||en.contains(q)||vi.contains(q))shown.add(t);}
            adapter.notifyDataSetChanged();info.setText("Tổng "+all.size()+" thuật ngữ · đang hiển thị "+shown.size());
        };
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c1,int c2){}public void onTextChanged(CharSequence s,int st,int b,int c1){filter.run();}public void afterTextChanged(android.text.Editable e){}});
        final AlertDialog dlg=new AlertDialog.Builder(this).setTitle("📚 Trung tâm thuật ngữ ("+all.size()+")").setView(root).setNegativeButton("Đóng",null).create();
        list.setOnItemClickListener((parent,view,position,id)->{GlossaryManager.Term old=shown.get(position);editGlossaryTerm(all,old,()->{filter.run();});});
        add.setOnClickListener(v->{GlossaryManager.Term empty=new GlossaryManager.Term("","","","");editGlossaryTerm(all,empty,()->{shown.clear();shown.addAll(all);adapter.notifyDataSetChanged();info.setText("Tổng "+all.size()+" thuật ngữ.");});});
        imp.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("text/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/csv","text/comma-separated-values","application/csv","text/plain"});i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,12);dlg.dismiss();});
        dlg.show();
    }

    private void editGlossaryTerm(ArrayList<GlossaryManager.Term> all,GlossaryManager.Term old,Runnable changed){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(16,4,16,4);
        EditText en=new EditText(this);en.setSingleLine(true);en.setHint("English *");en.setText(old.english);box.addView(en);
        EditText vi=new EditText(this);vi.setSingleLine(true);vi.setHint("Vietnamese *");vi.setText(old.vietnamese);box.addView(vi);
        EditText ca=new EditText(this);ca.setSingleLine(true);ca.setHint("Category");ca.setText(old.category);box.addView(ca);
        EditText no=new EditText(this);no.setSingleLine(true);no.setHint("Note");no.setText(old.note);box.addView(no);
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle(old.english.isEmpty()?"➕ Thêm thuật ngữ":"✏️ Chỉnh sửa thuật ngữ").setView(box).setNegativeButton("Hủy",null).setPositiveButton("LƯU",null);
        if(!old.english.isEmpty())b.setNeutralButton("XÓA",null);
        AlertDialog d=b.create();
        d.setOnShowListener(x->{
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                String english=en.getText().toString().trim();String vietnamese=vi.getText().toString().trim().replace("\\n","\n");
                if(english.isEmpty()||vietnamese.isEmpty()){Toast.makeText(this,"English và Vietnamese không được trống.",Toast.LENGTH_SHORT).show();return;}
                String key=english.toLowerCase(Locale.US);
                for(GlossaryManager.Term t:all)if(t!=old&&t.english!=null&&t.english.trim().toLowerCase(Locale.US).equals(key)){Toast.makeText(this,"English đã tồn tại trong glossary.",Toast.LENGTH_SHORT).show();return;}
                GlossaryManager.Term updated=new GlossaryManager.Term(english,vietnamese,ca.getText().toString().trim(),no.getText().toString().trim());int idx=all.indexOf(old);if(idx>=0)all.set(idx,updated);else all.add(updated);
                try{GlossaryManager.save(workspace,all);changed.run();d.dismiss();Toast.makeText(this,"Đã lưu thuật ngữ.",Toast.LENGTH_SHORT).show();}catch(Exception ex){showError(ex);}
            });
            if(!old.english.isEmpty())d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{new AlertDialog.Builder(this).setTitle("Xóa thuật ngữ?").setMessage(old.english).setPositiveButton("Xóa",(dd,ww)->{all.remove(old);try{GlossaryManager.save(workspace,all);changed.run();d.dismiss();Toast.makeText(this,"Đã xóa.",Toast.LENGTH_SHORT).show();}catch(Exception ex){showError(ex);}}).setNegativeButton("Hủy",null).show();});
        });
        d.show();
    }
    private String profileKey(int slot,String field){return "ai_profile_"+slot+"_"+field;}
    private boolean hasProfile(int slot){return !prefsHolder.getString(profileKey(slot,"key"),"").trim().isEmpty();}
    private String profileProvider(int slot){return prefsHolder.getString(profileKey(slot,"provider"),PROVIDERS[0]);}
    private boolean isFreeProfile(String p,String m){return p.equals(PROVIDERS[0])||(p.equals(PROVIDERS[1])&&isFreeGeminiModel(m));}
    private String maskedKey(String k){if(k==null||k.isEmpty())return "(trống)";return k.length()<=8?"••••••••":k.substring(0,4)+"••••"+k.substring(k.length()-4);}
    private TranslationConfig profileConfig(int slot){
        String p=profileProvider(slot);TranslationConfig c=new TranslationConfig();
        if(p.equals(PROVIDERS[0]))c.endpoint=OPENROUTER_ENDPOINT;
        else if(p.equals(PROVIDERS[1]))c.endpoint=GEMINI_ENDPOINT;
        else if(p.equals(PROVIDERS[2]))c.endpoint=OPENAI_ENDPOINT;
        else if(p.equals(PROVIDERS[3]))c.endpoint=DEEPSEEK_ENDPOINT;
        else if(p.equals(PROVIDERS[4]))c.endpoint=MISTRAL_ENDPOINT;
        else c.endpoint=prefsHolder.getString(profileKey(slot,"endpoint"),"");
        c.model=prefsHolder.getString(profileKey(slot,"model"),"");
        c.apiKey=prefsHolder.getString(profileKey(slot,"key"),"");return c;
    }
    private List<TranslationRouter.Provider> savedProfileProviders(){
        List<TranslationRouter.Provider> out=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        boolean freeOnly=prefsHolder.getBoolean(PREF_FREE_POOL,true),allowPaid=prefsHolder.getBoolean(PREF_ALLOW_PAID,false);
        for(int i=1;i<=10;i++)if(hasProfile(i)){
            String p=profileProvider(i);TranslationConfig c=profileConfig(i);
            if(c.endpoint==null||c.endpoint.isEmpty()||c.model==null||c.model.isEmpty()||c.apiKey==null||c.apiKey.isEmpty())continue;
            if(freeOnly&&!isFreeProfile(p,c.model)&&!allowPaid)continue;
            String id=p+"|"+c.model+"|"+c.apiKey;if(seen.add(id))out.add(new TranslationRouter.Provider("Cấu hình "+i+" — "+p,c));
        }
        return out;
    }

    /**
     * Stable AI settings UI.
     * Avoids dynamic re-parenting and callback recursion: every control is created
     * once and remains attached to exactly one container.
     */
    private void settings(){
        final LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,8,24,8);

        TextView profileTitle=new TextView(this);
        profileTitle.setText("CẤU HÌNH API — chọn cấu hình để nạp lại key");
        profileTitle.setTextSize(15);
        root.addView(profileTitle);

        final Spinner profile=new Spinner(this);
        String[] profileNames=new String[10];
        for(int i=0;i<10;i++){
            String key=prefsHolder.getString(profileKey(i+1,"key"),"");
            profileNames[i]="Cấu hình "+(i+1)+(key.trim().isEmpty()?" — trống":" — "+maskedKey(key));
        }
        profile.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,profileNames));
        int initial=Math.max(0,Math.min(9,prefsHolder.getInt("active_profile",1)-1));
        profile.setSelection(initial,false);
        root.addView(profile);

        final Spinner provider=new Spinner(this);
        provider.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,PROVIDERS));
        root.addView(provider);

        final Spinner model=new Spinner(this);
        root.addView(model);

        final Button refreshGemini=new Button(this);
        refreshGemini.setText("🔄 LÀM MỚI DANH SÁCH GEMINI MODEL");
        root.addView(refreshGemini);

        final EditText customModel=new EditText(this);
        customModel.setSingleLine(true);
        customModel.setHint("Model ID tùy chỉnh");
        root.addView(customModel);

        final EditText ep=new EditText(this);
        ep.setSingleLine(true);
        ep.setHint("Endpoint");
        root.addView(ep);

        final EditText key=new EditText(this);
        key.setSingleLine(true);
        key.setHint("API key");
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(key);

        final TextView keyLink=new TextView(this);
        keyLink.setTextSize(14);
        keyLink.setPadding(0,8,0,8);
        keyLink.setTextColor(0xff1565c0);
        keyLink.setPaintFlags(keyLink.getPaintFlags()|8);
        root.addView(keyLink);

        final CheckBox freePool=new CheckBox(this);
        freePool.setText("FREE AI POOL — dùng các cấu hình Free");
        freePool.setChecked(prefsHolder.getBoolean(PREF_FREE_POOL,true));
        root.addView(freePool);

        final CheckBox allowPaid=new CheckBox(this);
        allowPaid.setText("Cho phép provider trả phí khi free pool hết quota");
        allowPaid.setChecked(prefsHolder.getBoolean(PREF_ALLOW_PAID,false));
        root.addView(allowPaid);

        final TextView note=new TextView(this);
        note.setTextSize(12);
        note.setPadding(0,8,0,0);
        root.addView(note);

        final TextView fallbackNote=new TextView(this);
        fallbackNote.setTextSize(12);
        fallbackNote.setPadding(0,8,0,8);
        root.addView(fallbackNote);

        final Button save=new Button(this);
        save.setText("💾 LƯU CẤU HÌNH");
        root.addView(save);

        final Button test=new Button(this);
        test.setText("KIỂM TRA CẤU HÌNH NÀY");
        root.addView(test);

        final Button clear=new Button(this);
        clear.setText("🗑 XÓA CẤU HÌNH");
        root.addView(clear);

        final boolean[] internal={true};

        Runnable showProfile=()->{
            int slot=profile.getSelectedItemPosition()+1;
            prefsHolder.edit().putInt("active_profile",slot).apply();

            String p=profileProvider(slot);
            if(p==null||indexOf(PROVIDERS,p)<0)p=PROVIDERS[0];
            internal[0]=true;
            provider.setSelection(indexOf(PROVIDERS,p),false);

            String savedModel=prefsHolder.getString(profileKey(slot,"model"),"");
            String[] ms=modelsFor(p),labels=labelsFor(p);
            model.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));
            int mi=indexOf(ms,savedModel);
            if(mi<0)mi=0;
            model.setSelection(mi,false);

            key.setText(prefsHolder.getString(profileKey(slot,"key"),""));
            if(p.equals(PROVIDERS[0])){
                ep.setText(OPENROUTER_ENDPOINT);
                customModel.setVisibility(View.GONE);
                model.setVisibility(View.VISIBLE);
                note.setText("OpenRouter: mỗi cấu hình có API key riêng.");
                keyLink.setText("🔑 OpenRouter API key");
                keyLink.setVisibility(View.VISIBLE);
                keyLink.setOnClickListener(v->openUrl("https://openrouter.ai/settings/keys"));
            }else if(p.equals(PROVIDERS[1])){
                ep.setText(GEMINI_ENDPOINT);
                customModel.setVisibility(View.GONE);
                model.setVisibility(View.VISIBLE);
                note.setText("Gemini Free Tier: ưu tiên Flash-Lite/Flash. Danh sách được cập nhật theo API key bằng nút LÀM MỚI. *Google có thể giới hạn quyền truy cập một số model 2.5.");
                keyLink.setText("🔑 Gemini API key");
                keyLink.setVisibility(View.VISIBLE);
                keyLink.setOnClickListener(v->openUrl("https://aistudio.google.com/apikey"));
            }else if(p.equals(PROVIDERS[2])){
                ep.setText(OPENAI_ENDPOINT);
                customModel.setVisibility(View.GONE);
                model.setVisibility(View.VISIBLE);
                note.setText("OpenAI API.");
                keyLink.setText("🔑 OpenAI API key");
                keyLink.setVisibility(View.VISIBLE);
                keyLink.setOnClickListener(v->openUrl("https://platform.openai.com/api-keys"));
            }else if(p.equals(PROVIDERS[3])){
                ep.setText(DEEPSEEK_ENDPOINT);
                customModel.setVisibility(View.GONE);
                model.setVisibility(View.VISIBLE);
                note.setText("DeepSeek API.");
                keyLink.setText("🔑 DeepSeek API key");
                keyLink.setVisibility(View.VISIBLE);
                keyLink.setOnClickListener(v->openUrl("https://platform.deepseek.com/api_keys"));
            }else if(p.equals(PROVIDERS[4])){
                ep.setText(MISTRAL_ENDPOINT);
                customModel.setVisibility(View.GONE);
                model.setVisibility(View.VISIBLE);
                note.setText("Mistral API.");
                keyLink.setText("🔑 Mistral API key");
                keyLink.setVisibility(View.VISIBLE);
                keyLink.setOnClickListener(v->openUrl("https://console.mistral.ai/api-keys/"));
            }else{
                ep.setText(prefsHolder.getString(profileKey(slot,"endpoint"),""));
                customModel.setText(savedModel);
                customModel.setVisibility(View.VISIBLE);
                model.setVisibility(View.GONE);
                note.setText("Provider tùy chỉnh: endpoint + model + API key.");
                keyLink.setText("ℹ️ Custom provider");
                keyLink.setVisibility(View.VISIBLE);
                keyLink.setOnClickListener(null);
            }
            refreshGemini.setVisibility(p.equals(PROVIDERS[1])?View.VISIBLE:View.GONE);

            List<TranslationRouter.Provider> ps=savedProfileProviders();
            StringBuilder fb=new StringBuilder(freePool.isChecked()?"FREE POOL: ":"ALL PROVIDERS: ");
            if(ps.isEmpty())fb.append("chưa có cấu hình hợp lệ");
            else for(int i=0;i<ps.size();i++){if(i>0)fb.append(" → ");fb.append(ps.get(i).name);}
            fallbackNote.setText(fb.toString());
            internal[0]=false;
        };

        profile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> a,View v,int pos,long id){showProfile.run();}
            public void onNothingSelected(AdapterView<?> a){}
        });

        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> a,View v,int pos,long id){
                if(internal[0])return;
                String p=PROVIDERS[pos];
                int slot=profile.getSelectedItemPosition()+1;
                String saved=prefsHolder.getString(profileKey(slot,"model"),"");
                String[] ms=modelsFor(p),labels=labelsFor(p);
                model.setAdapter(new ArrayAdapter<String>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,labels));
                int mi=indexOf(ms,saved);if(mi<0)mi=0;model.setSelection(mi,false);
                if(p.equals(PROVIDERS[0]))ep.setText(OPENROUTER_ENDPOINT);
                else if(p.equals(PROVIDERS[1]))ep.setText(GEMINI_ENDPOINT);
                else if(p.equals(PROVIDERS[2]))ep.setText(OPENAI_ENDPOINT);
                else if(p.equals(PROVIDERS[3]))ep.setText(DEEPSEEK_ENDPOINT);
                else if(p.equals(PROVIDERS[4]))ep.setText(MISTRAL_ENDPOINT);
            }
            public void onNothingSelected(AdapterView<?> a){}
        });

        freePool.setOnCheckedChangeListener((b,checked)->{
            if(!internal[0])showProfile.run();
        });
        allowPaid.setOnCheckedChangeListener((b,checked)->{
            if(!internal[0])showProfile.run();
        });

        refreshGemini.setOnClickListener(v->refreshGeminiCatalog(model,note,showProfile,key.getText().toString()));

        save.setOnClickListener(v->{
            int slot=profile.getSelectedItemPosition()+1;
            String p=(String)provider.getSelectedItem();
            if(p==null||p.trim().isEmpty())p=PROVIDERS[0];
            String selectedModel;
            if(p.equals(PROVIDERS[5]))selectedModel=customModel.getText().toString().trim();
            else{
                String[] ms=modelsFor(p);int pos=model.getSelectedItemPosition();
                selectedModel=ms.length==0?"":ms[Math.max(0,Math.min(pos,ms.length-1))];
            }
            String enteredKey=key.getText().toString().trim();
            if(enteredKey.isEmpty()){Toast.makeText(this,"API key đang trống.",Toast.LENGTH_SHORT).show();return;}            if(selectedModel.isEmpty()){Toast.makeText(this,"Model đang trống.",Toast.LENGTH_SHORT).show();return;}
            prefsHolder.edit()
                    .putBoolean(PREF_FREE_POOL,freePool.isChecked())
                    .putBoolean(PREF_ALLOW_PAID,allowPaid.isChecked())
                    .putString(profileKey(slot,"provider"),p)
                    .putString(profileKey(slot,"endpoint"),p.equals(PROVIDERS[5])?ep.getText().toString().trim():"")
                    .putString(profileKey(slot,"model"),selectedModel)
                    .putString(profileKey(slot,"key"),enteredKey)
                    .apply();
            profile.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,profileNamesFromPrefs()));
            profile.setSelection(slot-1,false);
            status.setText((freePool.isChecked()?"FREE POOL":"ALL PROVIDERS")+" | Đã lưu Cấu hình "+slot);
            Toast.makeText(this,"Đã lưu Cấu hình "+slot,Toast.LENGTH_SHORT).show();
            showProfile.run();
        });

        test.setOnClickListener(v->{
            String p=(String)provider.getSelectedItem();
            if(p==null)p=PROVIDERS[0];
            String selectedModel;
            if(p.equals(PROVIDERS[5]))selectedModel=customModel.getText().toString().trim();
            else{
                String[] ms=modelsFor(p);int pos=model.getSelectedItemPosition();
                selectedModel=ms.length==0?"":ms[Math.max(0,Math.min(pos,ms.length-1))];
            }
            TranslationConfig tc=new TranslationConfig();
            tc.endpoint=ep.getText().toString().trim();
            tc.model=selectedModel;
            tc.apiKey=key.getText().toString().trim();
            if(tc.apiKey.isEmpty()){Toast.makeText(this,"API key đang trống.",Toast.LENGTH_SHORT).show();return;}
            test.setEnabled(false);
            final String providerName=p;
            new Thread(()->{try{
                String out=OpenAICompatibleTranslator.translate("Translate only: fetal ultrasound","Medical translation connection test.",tc);
                runOnUiThread(()->{
                    test.setEnabled(true);
                    new AlertDialog.Builder(this).setTitle("Kết nối thành công")
                            .setMessage("Cấu hình "+profile.getSelectedItemPosition()+1+"\nProvider: "+providerName+"\nModel: "+tc.model+"\n\n"+out)
                            .setPositiveButton("OK",null).show();
                });
            }catch(Exception ex){runOnUiThread(()->{test.setEnabled(true);showError(ex);});}}).start();
        });

        clear.setOnClickListener(v->{
            int slot=profile.getSelectedItemPosition()+1;
            new AlertDialog.Builder(this).setTitle("Xóa Cấu hình "+slot+"?")
                    .setMessage("API key và model của cấu hình này sẽ bị xóa khỏi máy.")
                    .setPositiveButton("Xóa",(d,w)->{
                        prefsHolder.edit().remove(profileKey(slot,"provider")).remove(profileKey(slot,"endpoint"))
                                .remove(profileKey(slot,"model")).remove(profileKey(slot,"key")).apply();
                        showProfile.run();
                    }).setNegativeButton("Hủy",null).show();
        });

        new AlertDialog.Builder(this).setTitle("Cấu hình AI + Failover")
                .setView(root).setNegativeButton("Đóng",null).show();

        root.post(showProfile);
    }

    private String[] profileNamesFromPrefs(){
        String[] out=new String[10];
        for(int i=0;i<10;i++){
            String key=prefsHolder.getString(profileKey(i+1,"key"),"");
            out[i]="Cấu hình "+(i+1)+(key.trim().isEmpty()?" — trống":" — "+maskedKey(key));
        }
        return out;
    }

    private void updateOfflineButton(){
        if(offlineButton==null)return;
        if(OfflineModelManager.isReady(this)){
            offlineButton.setText("🧠 Dịch OFFLINE — NLLB 600M ✓");
        }else if(OfflineModelManager.isModelReady(this)){
            offlineButton.setText("🧠 Kích hoạt engine OFFLINE NLLB");
        }else{
            offlineButton.setText("🧠 Tải model OFFLINE NLLB 600M (~495 MB)");
        }
    }

    private void runOfflineSelfTest(){
        if(offlineButton!=null)offlineButton.setEnabled(false);
        new Thread(()->{
            try{
                OfflineNllbTranslator.EngineResult r=OfflineNllbTranslator.selfTest(this,workspace);
                String msg="Engine OFFLINE tự kiểm tra thành công.\n\n"
                        +"Exit code: "+r.exitCode+"\nABI: "+r.abi+"\nBinary: "+r.binaryPath+"\n\nRaw output:\n"+r.rawOutput;
                runOnUiThread(()->{
                    if(offlineButton!=null)offlineButton.setEnabled(true);
                    new AlertDialog.Builder(this).setTitle("OFFLINE NLLB self-test")
                            .setMessage(msg).setPositiveButton("OK",null).show();
                });
            }catch(Exception e){
                String msg=e.getMessage()==null?e.toString():e.getMessage();
                runOnUiThread(()->{
                    if(offlineButton!=null)offlineButton.setEnabled(true);
                    new AlertDialog.Builder(this).setTitle("OFFLINE NLLB self-test thất bại")
                            .setMessage(msg).setPositiveButton("OK",null).show();
                });
            }
        },"offline-self-test").start();
    }

    private void manageOfflineModel(){
        if(OfflineModelManager.isReady(this)){
            prefsHolder.edit().putBoolean("offline_enabled",true).apply();
            updateOfflineButton();
            Toast.makeText(this,"Offline NLLB đã sẵn sàng. Khi dịch, app sẽ ưu tiên model local.",Toast.LENGTH_LONG).show();
            return;
        }
        if(OfflineModelManager.isModelReady(this) && OfflineModelManager.ensureBinary(this)){
            prefsHolder.edit().putBoolean("offline_enabled",true).apply();
            updateOfflineButton();
            Toast.makeText(this,"Đã kích hoạt engine NLLB offline.",Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Dịch offline NLLB-200 600M")
            .setMessage("Model Q4_0 khoảng 495 MB. APK không chứa model nên file cài đặt vẫn nhẹ. Sau khi tải xong, dịch có thể chạy không cần Internet/API key.\n\nNếu Hugging Face trả HTTP 401/403, bạn có thể tải file bằng Chrome rồi chọn \"Nhập model\"; app vẫn kiểm tra kích thước và giữ model trong bộ nhớ riêng.")
            .setNegativeButton("Hủy",null)
            .setNeutralButton("Nhập model", (d,w)->pickOfflineModel())
            .setPositiveButton("Tải model", (d,w)->downloadOfflineModel())
            .show();
    }

    private void downloadOfflineModel(){
        if(offlineButton!=null)offlineButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);progress.setIndeterminate(false);progress.setMax(100);
        long resumeBytes=OfflineModelManager.partialBytes(this);
        report.setText(resumeBytes>0
                ? "↻ Tiếp tục tải model NLLB-600M Q4_0… đã có "+(resumeBytes/(1024*1024))+" MB"
                : "⬇ Đang tải model NLLB-600M Q4_0…");
        new Thread(()->{
            try{
                OfflineModelManager.downloadModel(this,(done,total)->{
                    int p=total>0?(int)Math.min(100,(done*100L)/total):0;
                    runOnUiThread(()->{progress.setProgress(p);report.setText("⬇ Tải model offline: "+p+"%\n"+(done/(1024*1024))+" / "+(total>0?total/(1024*1024):0)+" MB");});
                });
                boolean ready=OfflineModelManager.isReady(this);
                if(!ready)throw new IOException("Model đã tải nhưng engine NLLB chưa sẵn sàng.");
                prefsHolder.edit().putBoolean("offline_enabled",true).apply();
                runOnUiThread(()->{progress.setVisibility(View.GONE);if(offlineButton!=null)offlineButton.setEnabled(true);updateOfflineButton();report.setText("✅ Dịch OFFLINE NLLB-600M đã sẵn sàng.\nKhông cần Internet/API key khi dịch.");});
            }catch(Exception e){
                runOnUiThread(()->{
                    progress.setVisibility(View.GONE);
                    if(offlineButton!=null)offlineButton.setEnabled(true);
                    updateOfflineButton();
                    String m=e.getMessage()==null?e.toString():e.getMessage();
                    if(m.contains("HTTP 401") || m.contains("HTTP 403")) showOfflineModelRecovery(e);
                    else showError(e);
                });
            }
        },"offline-model-download").start();
    }

    private void pickOfflineModel(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i,REQ_OFFLINE_MODEL);
    }

    private void openOfflineModelPage(){
        try{
            Intent i=new Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/Hosstia/nllb-200-distilled-600m-gguf"));
            startActivity(i);
        }catch(Exception ignored){}
    }

    private void showOfflineModelRecovery(Exception e){
        String msg=e.getMessage()==null?e.toString():e.getMessage();
        new AlertDialog.Builder(this)
            .setTitle("Không tải được model NLLB")
            .setMessage("Nguồn model đang trả HTTP 401/403 cho tải trực tiếp từ app. Đây là lỗi quyền truy cập của máy chủ, không phải lỗi file APK.\n\nCách chắc chắn nhất:\n1. Mở trang model bằng Chrome.\n2. Tải nllb-600m-Q4_0.gguf (~495 MB).\n3. Quay lại app → Dịch offline → Nhập model.\n\nFile sẽ được sao chép vào bộ nhớ riêng của app và không cần tải lại mỗi lần dịch.\n\nChi tiết: "+msg)
            .setNegativeButton("Đóng",null)
            .setNeutralButton("Mở trang model",(d,w)->openOfflineModelPage())
            .setPositiveButton("Nhập file",(d,w)->pickOfflineModel())
            .show();
    }

    private void translate(){
        if(translating)return;
        if(selectedFile==null){pick();return;}
        List<TranslationRouter.Provider> providers=fallbackProviders();
        if(providers.isEmpty()){settings();return;}
        if(workspace==null)workspace=new File(getFilesDir(),"translation_workspaces");
        if(pdfMode){
            Toast.makeText(this,"Chọn 1 CỘT hoặc GIỮ NGUYÊN BỐ CỤC GỐC.",Toast.LENGTH_SHORT).show();
            return;
        }
        File out=new File(workspace,"translated-final.epub");
        translating=true;translate.setEnabled(false);reset.setEnabled(false);
        export.setEnabled(false);progress.setVisibility(View.VISIBLE);progress.setIndeterminate(false);progress.setMax(100);
        report.setText("🤖 Translation Queue đang chạy\nBatch tối đa 8 unit / request\nMỗi batch hoàn thành sẽ được lưu ngay.");
        TranslationJob.run(this,selectedFile,out,workspace,providers,new TranslationJob.Listener(){
            public void onProgress(int d,int t,int batch,String info){
                int p=t<=0?0:(int)(100.0*d/t);
                runOnUiThread(()->{progress.setProgress(p);status.setText("Dịch: "+d+"/"+t+" unit | batch "+batch);report.setText(info+"\n"+d+"/"+t+" unit\n\nNếu hết quota: draft vẫn được lưu, bấm Dịch / Tiếp tục vào ngày khác.");});
            }
            public void onDone(File f){
                lastOutput=f;
                runOnUiThread(()->{translating=false;translate.setEnabled(true);reset.setEnabled(true);export.setEnabled(true);progress.setProgress(100);report.setText("✅ Dịch hoàn tất.\nEPUB đã rebuild từ source + các translation unit đã khóa.\n"+f.getAbsolutePath());});
            }
            public void onPaused(File draft,int d,int t,Exception reason){
                lastOutput=draft;
                runOnUiThread(()->{translating=false;translate.setEnabled(true);reset.setEnabled(true);export.setEnabled(draft!=null&&draft.isFile());progress.setProgress(t<=0?0:(int)(100.0*d/t));String msg=reason.getMessage()==null?reason.toString():reason.getMessage();report.setText("⏸ PAUSED — đã lưu "+d+"/"+t+" unit.\n\n"+msg+"\n\nKhông mất phần đã dịch. Bấm Dịch / Tiếp tục sau khi quota hồi phục hoặc sau khi cấu hình provider khác.");});
            }
            public void onError(Exception e){
                runOnUiThread(()->{translating=false;translate.setEnabled(true);reset.setEnabled(true);showError(e);});
            }
        });
    }

    private void updatePdfLayoutButtons(){
        boolean visible=pdfMode;
        if(pdfLayout!=null)pdfLayout.setVisibility(View.GONE);
        if(pdfOneColumn!=null)pdfOneColumn.setVisibility(visible?View.VISIBLE:View.GONE);
        if(pdfKeepLayout!=null)pdfKeepLayout.setVisibility(visible?View.VISIBLE:View.GONE);
        if(pdfOneColumn!=null)pdfOneColumn.setText("📄 1 CỘT");
        if(pdfKeepLayout!=null)pdfKeepLayout.setText("📰 GIỮ NGUYÊN BỐ CỤC GỐC");
    }

    private void startPdfWithLayout(boolean singleColumn){
        if(!pdfMode||selectedFile==null){
            Toast.makeText(this,"Hãy chọn một file PDF trước.",Toast.LENGTH_SHORT).show();
            return;
        }
        List<TranslationRouter.Provider> providers=fallbackProviders();
        if(providers.isEmpty()){settings();return;}
        prefsHolder.edit().putInt("pdf_layout_mode",singleColumn?1:0).apply();
        updatePdfLayoutButtons();
        translatePdf(providers,singleColumn);
    }


    private void choosePdfLayoutAndTranslate(List<TranslationRouter.Provider> providers){
        final int saved=prefsHolder.getInt("pdf_layout_mode",0);
        final int[] selected={Math.max(0,Math.min(saved,1))};

        // Use ordinary Buttons instead of RadioButton/RadioGroup. On some Android
        // themes/devices the RadioButton children can collapse/not render inside
        // a programmatically created AlertDialog. The two large buttons are always
        // visible and also show the current selection explicitly.
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(16*getResources().getDisplayMetrics().density);
        root.setPadding(pad,0,pad,0);

        TextView hint=new TextView(this);
        hint.setText("Chọn bố cục PDF sau khi dịch:");
        hint.setTextSize(16);
        hint.setTextColor(0xFF555555);
        hint.setPadding(0,pad/2,0,pad/2);
        root.addView(hint,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        Button keep=new Button(this);
        Button one=new Button(this);
        keep.setAllCaps(false);
        one.setAllCaps(false);
        keep.setTextSize(15);
        one.setTextSize(15);
        keep.setMinHeight((int)(52*getResources().getDisplayMetrics().density));
        one.setMinHeight((int)(52*getResources().getDisplayMetrics().density));

        LinearLayout.LayoutParams choiceLp=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        choiceLp.setMargins(0,0,0,pad/2);
        root.addView(keep,choiceLp);
        LinearLayout.LayoutParams choiceLp2=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        choiceLp2.setMargins(0,0,0,pad/4);
        root.addView(one,choiceLp2);

        TextView note=new TextView(this);
        note.setText("2 cột: giữ bố cục, hình và bảng gần PDF gốc.\n"
                +"1 cột: dồn văn bản thành một cột để đọc trên điện thoại; vị trí hình/bảng có thể thay đổi.");
        note.setTextSize(13);
        note.setTextColor(0xFF666666);
        note.setPadding(0,pad/3,0,pad/2);
        root.addView(note,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        Runnable refreshChoices=()->{
            keep.setText((selected[0]==0?"✓ ":"") + "📰 Giữ nguyên bố cục PDF gốc (2 cột)");
            one.setText((selected[0]==1?"✓ ":"") + "📄 Chuyển sang bố cục 1 cột");
        };
        refreshChoices.run();

        keep.setOnClickListener(v->{selected[0]=0;refreshChoices.run();});
        one.setOnClickListener(v->{selected[0]=1;refreshChoices.run();});

        new AlertDialog.Builder(this)
                .setTitle("Bố cục PDF đầu ra")
                .setView(root)
                .setPositiveButton("DỊCH / TIẾP TỤC",(d,w)->{
                    prefsHolder.edit().putInt("pdf_layout_mode",selected[0]).apply();
                    updatePdfLayoutButtons();
                    translatePdf(providers,selected[0]==1);
                })
                .setNegativeButton("HỦY",null)
                .show();
    }
    private void translatePdf(List<TranslationRouter.Provider> providers,boolean singleColumn){
        File out=new File(workspace,"translated-final.pdf");
        translating=true;translate.setEnabled(false);reset.setEnabled(false);export.setEnabled(false);
        progress.setVisibility(View.VISIBLE);progress.setIndeterminate(false);progress.setMax(100);
        report.setText("📄 Dịch PDF text layer. PDF scan/image-only không được hỗ trợ.");
        PdfTranslationJob.run(this,selectedFile,out,workspace,providers,singleColumn,new PdfTranslationJob.Listener(){
            public void onProgress(int d,int t,int page,String info){
                int p=t<=0?0:(int)(100.0*d/t);
                runOnUiThread(()->{progress.setProgress(p);status.setText("PDF: "+d+"/"+t+" trang | trang "+page);report.setText(info+"\n"+d+"/"+t+" trang");});
            }
            public void onDone(File f){
                lastOutput=f;
                runOnUiThread(()->{translating=false;translate.setEnabled(true);reset.setEnabled(true);export.setEnabled(true);progress.setProgress(100);report.setText("✅ Dịch PDF hoàn tất.\n"+f.getAbsolutePath()+"\n\nBố cục: "+(singleColumn?"1 cột":"2 cột như PDF gốc")+" . Hình ảnh/bảng được giữ theo chế độ đã chọn.");});
            }
            public void onPaused(File draft,int d,int t,Exception reason){
                lastOutput=draft;
                runOnUiThread(()->{translating=false;translate.setEnabled(true);reset.setEnabled(true);export.setEnabled(draft!=null&&draft.isFile());progress.setProgress(t<=0?0:(int)(100.0*d/t));showError(reason);});            }
            public void onError(Exception e){
                runOnUiThread(()->{translating=false;translate.setEnabled(true);reset.setEnabled(true);showError(e);});
            }
        });
    }

    private void resetProgress(){
        if(workspace==null)return;
        new AlertDialog.Builder(this).setTitle("Xóa tiến độ dịch?")
                .setMessage("Chỉ xóa translation units và draft hiện tại. EPUB nguồn vẫn được giữ nguyên.")
                .setPositiveButton("Xóa",(d,w)->{
                    File units=new File(workspace,"units"),manifest=new File(workspace,"progress.json"),draftEpub=new File(workspace,"translated-current.epub"),finalEpub=new File(workspace,"translated-final.epub"),draftPdf=new File(workspace,"translated-current.pdf"),finalPdf=new File(workspace,"translated-final.pdf"),pdfState=new File(workspace,"pdf-progress.properties");
                    deleteTree(units);manifest.delete();draftEpub.delete();finalEpub.delete();draftPdf.delete();finalPdf.delete();pdfState.delete();lastOutput=null;export.setEnabled(false);
                    report.setText("Đã xóa tiến độ. EPUB nguồn vẫn còn, có thể dịch lại từ đầu.");
                }).setNegativeButton("Hủy",null).show();
    }

    private void deleteTree(File f){
        if(f==null||!f.exists())return;
        if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)deleteTree(x);}
        f.delete();
    }

    private void exportTranslationLog(){
        if(workspace==null){Toast.makeText(this,"Chưa có workspace dịch.",Toast.LENGTH_SHORT).show();return;}
        File log=new File(workspace,"translation-debug.log");
        if(!log.isFile()){Toast.makeText(this,"Chưa có log. Hãy chạy Dịch / Tiếp tục trước.",Toast.LENGTH_SHORT).show();return;}
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE,"translation-debug.log");
        startActivityForResult(i,13);
    }

    private void saveOutput(){
        if(lastOutput==null||!lastOutput.isFile()){Toast.makeText(this,pdfMode?"Chưa có PDF draft.":"Chưa có EPUB draft.",Toast.LENGTH_SHORT).show();return;}
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType(pdfMode?"application/pdf":"application/epub+zip");
        i.putExtra(Intent.EXTRA_TITLE,lastOutput.getName());
        startActivityForResult(i,11);
    }

    private void openUrl(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){showError(e);}}
    private static void copyFile(File s,File t)throws IOException{
        File p=t.getParentFile();if(p!=null&&!p.exists())p.mkdirs();
        try(InputStream in=new FileInputStream(s);OutputStream out=new FileOutputStream(t)){byte[] b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);}
    }
    private void showError(Exception e){
        progress.setVisibility(View.GONE);
        new AlertDialog.Builder(this).setTitle("Lỗi").setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("OK",null).show();
    }
}