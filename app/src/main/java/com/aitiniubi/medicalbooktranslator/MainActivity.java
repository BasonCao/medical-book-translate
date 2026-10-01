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

    private TextView status,report;
    private ProgressBar progress;
    private Button analyze,translate,export,reset,logButton,pdfLayout,pdfOneColumn,pdfKeepLayout;
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
        appTitle.setText("Medical Book Translator V1.11.0");
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

        open.setOnClickListener(v->pick());
        analyze.setOnClickListener(v->analyze());
        settings.setOnClickListener(v->{try{settings();}catch(Exception e){showError(e);}});
        glossary.setOnClickListener(v->glossary());
        translate.setOnClickListener(v->translate());
        export.setOnClickListener(v->saveOutput());
        pdfLayout.setOnClickListener(v->choosePdfLayoutAndTranslate(fallbackProviders()));
        pdfOneColumn.setOnClickListener(v->startPdfWithLayout(true));
        pdfKeepLayout.setOnClickListener(v->startPdfWithLayout(false));
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
        if(p.equals(PROVIDERS[0]))c.endpoint=OPENROUTER_ENDPOINT;
        else if(p.equals(PROVIDERS[1]))c.endpoint=GEMINI_ENDPOINT;
        else if(p.equals(PROVIDERS[2]))c.endpoint=OPENAI_ENDPOINT;
        else if(p.equals(PROVIDERS[3]))c.endpoint=DEEPSEEK_ENDPOINT;
        else if(p.equals(PROVIDERS[4]))c.endpoint=MISTRAL_ENDPOINT;
        else c.endpoint=prefsHolder.getString("endpoint_custom","");
        c.model=providerModel(p);c.apiKey=providerKey(p);return c;
    }

    private boolean isFreePoolProvider(String p){
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
        if(!profiles.isEmpty())return profiles;
        boolean freeOnly=prefsHolder.getBoolean(PREF_FREE_POOL,true),allowPaid=prefsHolder.getBoolean(PREF_ALLOW_PAID,false);
        String selected=providerFor(prefsHolder.getString("endpoint",OPENROUTER_ENDPOINT));
        String[] order=freeOnly?new String[]{selected,PROVIDERS[0],PROVIDERS[1],allowPaid?PROVIDERS[2]:"",allowPaid?PROVIDERS[3]:"",allowPaid?PROVIDERS[4]:"",allowPaid?PROVIDERS[5]:""}:new String[]{selected,PROVIDERS[0],PROVIDERS[1],PROVIDERS[2],PROVIDERS[3],PROVIDERS[4],PROVIDERS[5]};
        List<TranslationRouter.Provider> out=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        for(String p:order){
            if(p==null||p.isEmpty()||!seen.add(p))continue;
            if(freeOnly&&!isFreePoolProvider(p)&&!allowPaid)continue;
            TranslationConfig cfg=makeProviderConfig(p);
            if(cfg.endpoint!=null&&!cfg.endpoint.trim().isEmpty()&&cfg.model!=null&&!cfg.model.trim().isEmpty()&&cfg.apiKey!=null&&!cfg.apiKey.trim().isEmpty())out.add(new TranslationRouter.Provider(p,cfg));
        }
        return out;
    }

    private int indexOf(String[] a,String v){for(int i=0;i<a.length;i++)if(a[i].equals(v))return i;return -1;}
    private String[] modelsFor(String p){if(p==null||p.trim().isEmpty())return OR_MODELS;if(p.equals(PROVIDERS[0]))return OR_MODELS;if(p.equals(PROVIDERS[1]))return geminiCatalog()[0];if(p.equals(PROVIDERS[2]))return OPENAI_MODELS;if(p.equals(PROVIDERS[3]))return DEEPSEEK_MODELS;if(p.equals(PROVIDERS[4]))return MISTRAL_MODELS;return new String[]{providerModel(PROVIDERS[5])};}
    private String[] labelsFor(String p){if(p==null||p.trim().isEmpty())return OR_LABELS;if(p.equals(PROVIDERS[0]))return OR_LABELS;if(p.equals(PROVIDERS[1]))return geminiCatalog()[1];if(p.equals(PROVIDERS[2]))return OPENAI_LABELS;if(p.equals(PROVIDERS[3]))return DEEPSEEK_LABELS;if(p.equals(PROVIDERS[4]))return MISTRAL_LABELS;return new String[]{providerModel(PROVIDERS[5])};}

    private String[][] geminiCatalog(){
        String idsRaw=prefsHolder.getString("gemini_catalog_ids","");
        String labelsRaw=prefsHolder.getString("gemini_catalog_labels","");
        if(!idsRaw.trim().isEmpty()&&!labelsRaw.trim().isEmpty()){
            String[] ids=idsRaw.split("\\n",-1),labels=labelsRaw.split("\\n",-1);\n            if(ids.length==labels.length&&ids.length>0)return new String[][]{ids,labels};\n        }\n        return new String[][]{GEMINI_MODELS,GEMINI_LABELS};\n    }\n\n    private void refreshGeminiCatalog(Spinner model,TextView note,Runnable refresh,String apiKey){\n        final String requestApiKey=apiKey==null?"":apiKey.trim();\n        if(requestApiKey.isEmpty()){new AlertDialog.Builder(this).setTitle("Chưa có Gemini API key").setMessage("Nhập Gemini API key trước, rồi bấm làm mới danh sách model.").setPositiveButton("OK",null).show();return;}\n        Toast.makeText(this,"Đang lấy danh sách Gemini model từ Google…",Toast.LENGTH_SHORT).show();\n        new Thread(()->{try{\n            HttpUrl url=HttpUrl.parse(GEMINI_MODELS_API).newBuilder().addQueryParameter("key",requestApiKey).build();\n            Request req=new Request.Builder().url(url).get().build();\n            try(Response res=new OkHttpClient().newCall(req).execute()){\n                String body=res.body()==null?"":res.body().string();\n                if(!res.isSuccessful())throw new IOException("Gemini models API HTTP "+res.code()+"\\n"+body);\n                JSONArray arr=new JSONObject(body).optJSONArray("models");\n                if(arr==null)throw new IOException("Gemini models API không trả về danh sách models.");\n                LinkedHashMap<String,String> found=new LinkedHashMap<>();\n                for(int i=0;i<arr.length();i++){\n                    JSONObject m=arr.optJSONObject(i);if(m==null)continue;\n                    String name=m.optString("name",""),id=name.startsWith("models/")?name.substring(7):name;\n                    JSONArray methods=m.optJSONArray("supportedGenerationMethods");boolean generate=false;\n                    if(methods!=null)for(int j=0;j<methods.length();j++)if("generateContent".equalsIgnoreCase(methods.optString(j)))generate=true;\n                    String low=id.toLowerCase(Locale.US);\n                    if(!generate||!low.startsWith("gemini-"))continue;\n                    if(low.contains("-tts")||low.contains("-live")||low.contains("image")||low.contains("transcribe")||low.contains("embedding")||low.contains("robotics")||low.contains("computer-use")||low.contains("deep-research")||low.contains("omni"))continue;\n                    String display=m.optString("displayName",id),desc=m.optString("description",""),suffix=desc.toLowerCase(Locale.US).contains("preview")||low.contains("preview")?" — PREVIEW":"";\n                    found.put(id,display+suffix);\n                }\n                if(found.isEmpty())throw new IOException("Không tìm thấy Gemini model text hỗ trợ generateContent.");\n                StringBuilder ids=new StringBuilder(),labels=new StringBuilder();int n=0;\n                for(Map.Entry<String,String> e:found.entrySet()){if(n++>0){ids.append("\\n");labels.append("\\n");}ids.append(e.getKey());labels.append(e.getValue());}\n                prefsHolder.edit().putString("gemini_catalog_ids",ids.toString()).putString("gemini_catalog_labels",labels.toString()).apply();\n                runOnUiThread(()->{refresh.run();note.setText("Gemini catalog đã cập nhật từ Google: "+found.size()+" model text hỗ trợ generateContent.");Toast.makeText(this,"Đã cập nhật "+found.size()+" Gemini model.",Toast.LENGTH_LONG).show();});\n            }\n        }catch(Exception e){runOnUiThread(()->showError(e));}}).start();\n    }\n\n    private void glossary(){\n        if(workspace==null){\n            new AlertDialog.Builder(this).setTitle("Thuật ngữ chuyên ngành")\n                    .setMessage("Hãy chọn EPUB/PDF trước để tạo workspace cho glossary.")\n                    .setPositiveButton("OK",null).show();return;\n        }\n        final ArrayList<GlossaryManager.Term> all=new ArrayList<>(GlossaryManager.load(workspace));\n        final ArrayList<GlossaryManager.Term> shown=new ArrayList<>(all);\n        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(12,4,12,4);\n        TextView info=new TextView(this);info.setText("Tổng "+all.size()+" thuật ngữ. Chạm một dòng để chỉnh sửa. Nạp CSV sẽ cộng thêm, không ghi đè.");root.addView(info);\n        EditText search=new EditText(this);search.setSingleLine(true);search.setHint("🔎 Tìm English / Vietnamese");root.addView(search);\n        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);\n        Button add=new Button(this);add.setText("➕ THÊM");Button imp=new Button(this);imp.setText("📥 NẠP THÊM CSV");\n        actions.addView(add,new LinearLayout.LayoutParams(0,-2,1));actions.addView(imp,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);\n        final ListView list=new ListView(this);list.setDividerHeight(1);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));\n        BaseAdapter adapter=new BaseAdapter(){\n            public int getCount(){return shown.size();} public Object getItem(int position){return shown.get(position);} public long getItemId(int position){return position;}\n            public View getView(int position,View convertView,android.view.ViewGroup parent){\n                TextView tv=(TextView)(convertView instanceof TextView?convertView:new TextView(MainActivity.this));\n                GlossaryManager.Term t=shown.get(position);tv.setText((position+1)+". "+t.english+" → "+t.vietnamese);tv.setTextSize(15);tv.setPadding(12,12,12,12);tv.setMaxLines(2);return tv;\n            }\n        };\n        list.setAdapter(adapter);\n        Runnable filter=()->{\n            String q=search.getText().toString().trim().toLowerCase(Locale.US);shown.clear();\n            for(GlossaryManager.Term t:all){String en=t.english==null?"":t.english.toLowerCase(Locale.US);String vi=t.vietnamese==null?"":t.vietnamese.toLowerCase(Locale.US);if(q.isEmpty()||en.contains(q)||vi.contains(q))shown.add(t);}\n            adapter.notifyDataSetChanged();info.setText("Tổng "+all.size()+" thuật ngữ · đang hiển thị "+shown.size());\n        };\n        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c1,int c2){}public void onTextChanged(CharSequence s,int st,int b,int c1){filter.run();}public void afterTextChanged(android.text.Editable e){}});\n        final AlertDialog dlg=new AlertDialog.Builder(this).setTitle("📚 Trung tâm thuật ngữ ("+all.size()+")").setView(root).setNegativeButton("Đóng",null).create();\n        list.setOnItemClickListener((parent,view,position,id)->{GlossaryManager.Term old=shown.get(position);editGlossaryTerm(all,old,()->{filter.run();});});\n        add.setOnClickListener(v->{GlossaryManager.Term empty=new GlossaryManager.Term("","","","");editGlossaryTerm(all,empty,()->{shown.clear();shown.addAll(all);adapter.notifyDataSetChanged();info.setText("Tổng "+all.size()+" thuật ngữ.");});});\n        imp.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("text/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/csv","text/comma-separated-values","application/csv","text/plain"});i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,12);dlg.dismiss();});\n        dlg.show();\n    }\n\n    private void editGlossaryTerm(ArrayList<GlossaryManager.Term> all,GlossaryManager.Term old,Runnable changed){\n        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(16,4,16,4);\n        EditText en=new EditText(this);en.setSingleLine(true);en.setHint("English *");en.setText(old.english);box.addView(en);\n        EditText vi=new EditText(this);vi.setSingleLine(true);vi.setHint("Vietnamese *");vi.setText(old.vietnamese);box.addView(vi);\n        EditText ca=new EditText(this);ca.setSingleLine(true);ca.setHint("Category");ca.setText(old.category);box.addView(ca);\n        EditText no=new EditText(this);no.setSingleLine(true);no.setHint("Note");no.setText(old.note);box.addView(no);\n        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle(old.english.isEmpty()?"➕ Thêm thuật ngữ":"✏️ Chỉnh sửa thuật ngữ").setView(box).setNegativeButton("Hủy",null).setPositiveButton("LƯU",null);\n        if(!old.english.isEmpty())b.setNeutralButton("XÓA",null);\n        AlertDialog d=b.create();\n        d.setOnShowListener(x->{\n            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{\n                String english=en.getText().toString().trim();String vietnamese=vi.getText().toString().trim().replace("\\n","\n");\n                if(english.isEmpty()||vietnamese.isEmpty()){Toast.makeText(this,"English và Vietnamese không được trống.",Toast.LENGTH_SHORT).show();return;}\n                String key=english.toLowerCase(Locale.US);\n                for(GlossaryManager.Term t:all)if(t!=old&&t.english!=null&&t.english.trim().toLowerCase(Locale.US).equals(key)){Toast.makeText(this,"English đã tồn tại trong glossary.",Toast.LENGTH_SHORT).show();return;}\n                GlossaryManager.Term updated=new GlossaryManager.Term(english,vietnamese,ca.getText().toString().trim(),no.getText().toString().trim());int idx=all.indexOf(old);if(idx>=0)all.set(idx,updated);else all.add(updated);\n                try{GlossaryManager.save(workspace,all);changed.run();d.dismiss();Toast.makeText(this,"Đã lưu thuật ngữ.",Toast.LENGTH_SHORT).show();}catch(Exception ex){showError(ex);}\n            });\n            if(!old.english.isEmpty())d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{new AlertDialog.Builder(this).setTitle("Xóa thuật ngữ?").setMessage(old.english).setPositiveButton("Xóa",(dd,ww)->{all.remove(old);try{GlossaryManager.save(workspace,all);changed.run();d.dismiss();Toast.makeText(this,"Đã xóa.",Toast.LENGTH_SHORT).show();}catch(Exception ex){showError(ex);}}).setNegativeButton("Hủy",null).show();});\n        });\n        d.show();\n    }\n    private String profileKey(int slot,String field){return "ai_profile_"+slot+"_"+field;}\n    private boolean hasProfile(int slot){return !prefsHolder.getString(profileKey(slot,"key"),"").trim().isEmpty();}\n    private String profileProvider(int slot){return prefsHolder.getString(profileKey(slot,"provider"),PROVIDERS[0]);}\n    private boolean isFreeProfile(String p,String m){return p.equals(PROVIDERS[0])||(p.equals(PROVIDERS[1])&&isFreeGeminiModel(m));}\n    private String maskedKey(String k){if(k==null||k.isEmpty())return "(trống)";return k.length()<=8?"••••••••":k.substring(0,4)+"••••"+k.substring(k.length()-4);}\n    private TranslationConfig profileConfig(int slot){\n        String p=profileProvider(slot);TranslationConfig c=new TranslationConfig();\n        if(p.equals(PROVIDERS[0]))c.endpoint=OPENROUTER_ENDPOINT;\n        else if(p.equals(PROVIDERS[1]))c.endpoint=GEMINI_ENDPOINT;\n        else if(p.equals(PROVIDERS[2]))c.endpoint=OPENAI_ENDPOINT;\n        else if(p.equals(PROVIDERS[3]))c.endpoint=DEEPSEEK_ENDPOINT;\n        else if(p.equals(PROVIDERS[4]))c.endpoint=MISTRAL_ENDPOINT;\n        else c.endpoint=prefsHolder.getString(profileKey(slot,"endpoint"),"");\n        c.model=prefsHolder.getString(profileKey(slot,"model"),"");\n        c.apiKey=prefsHolder.getString(profileKey(slot,"key"),"");return c;\n    }\n    private List<TranslationRouter.Provider> savedProfileProviders(){\n        List<TranslationRouter.Provider> out=new ArrayList<>();HashSet<String> seen=new HashSet<>();\n        boolean freeOnly=prefsHolder.getBoolean(PREF_FREE_POOL,true),allowPaid=prefsHolder.getBoolean(PREF_ALLOW_PAID,false);\n        for(int i=1;i<=10;i++)if(hasProfile(i)){\n            String p=profileProvider(i);TranslationConfig c=profileConfig(i);\n            if(c.endpoint==null||c.endpoint.isEmpty()||c.model==null||c.model.isEmpty()||c.apiKey==null||c.apiKey.isEmpty())continue;\n            if(freeOnly&&!isFreeProfile(p,c.model)&&!allowPaid)continue;\n            String id=p+"|"+c.model+"|"+c.apiKey;if(seen.add(id))out.add(new TranslationRouter.Provider("Cấu hình "+i+" — "+p,c));\n        }\n        return out;\n    }\n\n    /**\n     * Stable AI settings UI.\n     * Avoids dynamic re-parenting and callback recursion: every control is created\n     * once and remains attached to exactly one container.\n     */\n    private void settings(){\n        final LinearLayout root=new LinearLayout(this);\n        root.setOrientation(LinearLayout.VERTICAL);\n        root.setPadding(24,8,24,8);\n\n        TextView profileTitle=new TextView(this);\n        profileTitle.setText("CẤU HÌNH API — chọn cấu hình để nạp lại key");\n        profileTitle.setTextSize(15);\n        root.addView(profileTitle);\n\n        final Spinner profile=new Spinner(this);\n        String[] profileNames=new String[10];\n        for(int i=0;i<10;i++){\n            String key=prefsHolder.getString(profileKey(i+1,"key"),"");\n            profileNames[i]="Cấu hình "+(i+1)+(key.trim().isEmpty()?" — trống":" — "+maskedKey(key));\n        }\n        profile.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,profileNames));\n        int initial=Math.max(0,Math.min(9,prefsHolder.getInt("active_profile",1)-1));\n        profile.setSelection(initial,false);\n        root.addView(profile);\n\n        final Spinner provider=new Spinner(this);\n        provider.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,PROVIDERS));\n        root.addView(provider);\n\n        final Spinner model=new Spinner(this);\n        root.addView(model);\n\n        final Button refreshGemini=new Button(this);\n        refreshGemini.setText("🔄 LÀM MỚI DANH SÁCH GEMINI MODEL");\n        root.addView(refreshGemini);\n\n        final EditText customModel=new EditText(this);\n        customModel.setSingleLine(true);\n        customModel.setHint("Model ID tùy chỉnh");\n        root.addView(customModel);\n\n        final EditText ep=new EditText(this);\n        ep.setSingleLine(true);\n        ep.setHint("Endpoint");\n        root.addView(ep);\n\n        final EditText key=new EditText(this);\n        key.setSingleLine(true);\n        key.setHint("API key");\n        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);\n        root.addView(key);\n\n        final TextView keyLink=new TextView(this);\n        keyLink.setTextSize(14);\n        keyLink.setPadding(0,8,0,8);\n        keyLink.setTextColor(0xff1565c0);\n        keyLink.setPaintFlags(keyLink.getPaintFlags()|8);\n        root.addView(keyLink);\n\n        final CheckBox freePool=new CheckBox(this);\n        freePool.setText("FREE AI POOL — dùng các cấu hình Free");\n        freePool.setChecked(prefsHolder.getBoolean(PREF_FREE_POOL,true));\n        root.addView(freePool);\n\n        final CheckBox allowPaid=new CheckBox(this);\n        allowPaid.setText("Cho phép provider trả phí khi free pool hết quota");\n        allowPaid.setChecked(prefsHolder.getBoolean(PREF_ALLOW_PAID,false));\n        root.addView(allowPaid);\n\n        final TextView note=new TextView(this);\n        note.setTextSize(12);\n        note.setPadding(0,8,0,0);\n        root.addView(note);\n\n        final TextView fallbackNote=new TextView(this);\n        fallbackNote.setTextSize(12);\n        fallbackNote.setPadding(0,8,0,8);\n        root.addView(fallbackNote);\n\n        final Button save=new Button(this);\n        save.setText("💾 LƯU CẤU HÌNH");\n        root.addView(save);\n\n        final Button test=new Button(this);\n        test.setText("KIỂM TRA CẤU HÌNH NÀY");\n        root.addView(test);\n\n        final Button clear=new Button(this);\n        clear.setText("🗑 XÓA CẤU HÌNH");\n        root.addView(clear);\n\n        final boolean[] internal={true};\n\n        Runnable showProfile=()->{\n            int slot=profile.getSelectedItemPosition()+1;\n            prefsHolder.edit().putInt("active_profile",slot).apply();\n\n            String p=profileProvider(slot);\n            if(p==null||indexOf(PROVIDERS,p)<0)p=PROVIDERS[0];\n            internal[0]=true;\n            provider.setSelection(indexOf(PROVIDERS,p),false);\n\n            String savedModel=prefsHolder.getString(profileKey(slot,"model"),"");\n            String[] ms=modelsFor(p),labels=labelsFor(p);\n            model.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));\n            int mi=indexOf(ms,savedModel);\n            if(mi<0)mi=0;\n            model.setSelection(mi,false);\n\n            key.setText(prefsHolder.getString(profileKey(slot,"key"),""));\n            if(p.equals(PROVIDERS[0])){\n                ep.setText(OPENROUTER_ENDPOINT);\n                customModel.setVisibility(View.GONE);\n                model.setVisibility(View.VISIBLE);\n                note.setText("OpenRouter: mỗi cấu hình có API key riêng.");\n                keyLink.setText("🔑 OpenRouter API key");\n                keyLink.setVisibility(View.VISIBLE);\n                keyLink.setOnClickListener(v->openUrl("https://openrouter.ai/settings/keys"));\n            }else if(p.equals(PROVIDERS[1])){\n                ep.setText(GEMINI_ENDPOINT);\n                customModel.setVisibility(View.GONE);\n                model.setVisibility(View.VISIBLE);\n                note.setText("Gemini Free Tier: ưu tiên Flash-Lite/Flash. Danh sách được cập nhật theo API key bằng nút LÀM MỚI. *Google có thể giới hạn quyền truy cập một số model 2.5.");\n                keyLink.setText("🔑 Gemini API key");\n                keyLink.setVisibility(View.VISIBLE);\n                keyLink.setOnClickListener(v->openUrl("https://aistudio.google.com/apikey"));\n            }else if(p.equals(PROVIDERS[2])){\n                ep.setText(OPENAI_ENDPOINT);\n                customModel.setVisibility(View.GONE);\n                model.setVisibility(View.VISIBLE);\n                note.setText("OpenAI API.");\n                keyLink.setText("🔑 OpenAI API key");\n                keyLink.setVisibility(View.VISIBLE);\n                keyLink.setOnClickListener(v->openUrl("https://platform.openai.com/api-keys"));\n            }else if(p.equals(PROVIDERS[3])){\n                ep.setText(DEEPSEEK_ENDPOINT);\n                customModel.setVisibility(View.GONE);\n                model.setVisibility(View.VISIBLE);\n                note.setText("DeepSeek API.");\n                keyLink.setText("🔑 DeepSeek API key");\n                keyLink.setVisibility(View.VISIBLE);\n                keyLink.setOnClickListener(v->openUrl("https://platform.deepseek.com/api_keys"));\n            }else if(p.equals(PROVIDERS[4])){\n                ep.setText(MISTRAL_ENDPOINT);\n                customModel.setVisibility(View.GONE);\n                model.setVisibility(View.VISIBLE);\n                note.setText("Mistral API.");\n                keyLink.setText("🔑 Mistral API key");\n                keyLink.setVisibility(View.VISIBLE);\n                keyLink.setOnClickListener(v->openUrl("https://console.mistral.ai/api-keys/"));\n            }else{\n                ep.setText(prefsHolder.getString(profileKey(slot,"endpoint"),""));\n                customModel.setText(savedModel);\n                customModel.setVisibility(View.VISIBLE);\n                model.setVisibility(View.GONE);\n                note.setText("Provider tùy chỉnh: endpoint + model + API key.");\n                keyLink.setText("ℹ️ Custom provider");\n                keyLink.setVisibility(View.VISIBLE);\n                keyLink.setOnClickListener(null);\n            }\n            refreshGemini.setVisibility(p.equals(PROVIDERS[1])?View.VISIBLE:View.GONE);\n\n            List<TranslationRouter.Provider> ps=savedProfileProviders();\n            StringBuilder fb=new StringBuilder(freePool.isChecked()?"FREE POOL: ":"ALL PROVIDERS: ");\n            if(ps.isEmpty())fb.append("chưa có cấu hình hợp lệ");\n            else for(int i=0;i<ps.size();i++){if(i>0)fb.append(" → ");fb.append(ps.get(i).name);}\n            fallbackNote.setText(fb.toString());\n            internal[0]=false;\n        };\n\n        profile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){\n            public void onItemSelected(AdapterView<?> a,View v,int pos,long id){showProfile.run();}\n            public void onNothingSelected(AdapterView<?> a){}\n        });\n\n        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){\n            public void onItemSelected(AdapterView<?> a,View v,int pos,long id){\n                if(internal[0])return;\n                String p=PROVIDERS[pos];\n                int slot=profile.getSelectedItemPosition()+1;\n                String saved=prefsHolder.getString(profileKey(slot,"model"),"");\n                String[] ms=modelsFor(p),labels=labelsFor(p);\n                model.setAdapter(new ArrayAdapter<String>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,labels));\n                int mi=indexOf(ms,saved);if(mi<0)mi=0;model.setSelection(mi,false);\n                if(p.equals(PROVIDERS[0]))ep.setText(OPENROUTER_ENDPOINT);\n                else if(p.equals(PROVIDERS[1]))ep.setText(GEMINI_ENDPOINT);\n                else if(p.equals(PROVIDERS[2]))ep.setText(OPENAI_ENDPOINT);\n                else if(p.equals(PROVIDERS[3]))ep.setText(DEEPSEEK_ENDPOINT);\n                else if(p.equals(PROVIDERS[4]))ep.setText(MISTRAL_ENDPOINT);\n            }\n            public void onNothingSelected(AdapterView<?> a){}\n        });\n\n        freePool.setOnCheckedChangeListener((b,checked)->{\n            if(!internal[0])showProfile.run();\n        });\n        allowPaid.setOnCheckedChangeListener((b,checked)->{\n            if(!internal[0])showProfile.run();\n        });\n\n        refreshGemini.setOnClickListener(v->refreshGeminiCatalog(model,note,showProfile,key.getText().toString()));\n\n        save.setOnClickListener(v->{\n            int slot=profile.getSelectedItemPosition()+1;\n            String p=(String)provider.getSelectedItem();\n            if(p==null||p.trim().isEmpty())p=PROVIDERS[0];\n            String selectedModel;\n            if(p.equals(PROVIDERS[5]))selectedModel=customModel.getText().toString().trim();\n            else{\n                String[] ms=modelsFor(p);int pos=model.getSelectedItemPosition();\n                selectedModel=ms.length==0?"":ms[Math.max(0,Math.min(pos,ms.length-1))];\n            }\n            String enteredKey=key.getText().toString().trim();\n            if(enteredKey.isEmpty()){Toast.makeText(this,"API key đang trống.",Toast.LENGTH_SHORT).show();return;}\n            if(selectedModel.isEmpty()){Toast.makeText(this,"Model đang trống.",Toast.LENGTH_SHORT).show();return;}\n            prefsHolder.edit()\n                    .putBoolean(PREF_FREE_POOL,freePool.isChecked())\n                    .putBoolean(PREF_ALLOW_PAID,allowPaid.isChecked())\n                    .putString(profileKey(slot,"provider"),p)\n                    .putString(profileKey(slot,"endpoint"),p.equals(PROVIDERS[5])?ep.getText().toString().trim():"")\n                    .putString(profileKey(slot,"model"),selectedModel)\n                    .putString(profileKey(slot,"key"),enteredKey)\n                    .apply();\n            profile.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,profileNamesFromPrefs()));\n            profile.setSelection(slot-1,false);\n            status.setText((freePool.isChecked()?"FREE POOL":"ALL PROVIDERS")+" | Đã lưu Cấu hình "+slot);\n            Toast.makeText(this,"Đã lưu Cấu hình "+slot,Toast.LENGTH_SHORT).show();\n            showProfile.run();\n        });\n\n        test.setOnClickListener(v->{\n            String p=(String)provider.getSelectedItem();\n            if(p==null)p=PROVIDERS[0];\n            String selectedModel;\n            if(p.equals(PROVIDERS[5]))selectedModel=customModel.getText().toString().trim();\n            else{\n                String[] ms=modelsFor(p);int pos=model.getSelectedItemPosition();\n                selectedModel=ms.length==0?"":ms[Math.max(0,Math.min(pos,ms.length-1))];\n            }\n            TranslationConfig tc=new TranslationConfig();\n            tc.endpoint=ep.getText().toString().trim();\n            tc.model=selectedModel;\n            tc.apiKey=key.getText().toString().trim();\n            if(tc.apiKey.isEmpty()){Toast.makeText(this,"API key đang trống.",Toast.LENGTH_SHORT).show();return;}\n            test.setEnabled(false);\n            final String providerName=p;\n            new Thread(()->{try{\n                String out=OpenAICompatibleTranslator.translate("Translate only: fetal ultrasound","Medical translation connection test.",tc);\n                runOnUiThread(()->{\n                    test.setEnabled(true);\n                    new AlertDialog.Builder(this).setTitle("Kết nối thành công")\n                            .setMessage("Cấu hình "+profile.getSelectedItemPosition()+1+"\nProvider: "+providerName+"\nModel: "+tc.model+"\n\n"+out)\n                            .setPositiveButton("OK",null).show();\n                });\n            }catch(Exception ex){runOnUiThread(()->{test.setEnabled(true);showError(ex);});}}).start();\n        });\n\n        clear.setOnClickListener(v->{\n            int slot=profile.getSelectedItemPosition()+1;\n            new AlertDialog.Builder(this).setTitle("Xóa Cấu hình "+slot+"?")\n                    .setMessage("API key và model của cấu hình này sẽ bị xóa khỏi máy.")\n                    .setPositiveButton("Xóa",(d,w)->{\n                        prefsHolder.edit().remove(profileKey(slot,"provider")).remove(profileKey(slot,"endpoint"))\n                                .remove(profileKey(slot,"model")).remove(profileKey(slot,"key")).apply();\n                        showProfile.run();\n                    }).setNegativeButton("Hủy",null).show();\n        });\n\n        new AlertDialog.Builder(this).setTitle("Cấu hình AI + Failover")\n                .setView(root).setNegativeButton("Đóng",null).show();\n\n        root.post(showProfile);\n    }\n\n    private String[] profileNamesFromPrefs(){\n        String[] out=new String[10];\n        for(int i=0;i<10;i++){\n            String key=prefsHolder.getString(profileKey(i+1,"key"),"");\n            out[i]="Cấu hình "+(i+1)+(key.trim().isEmpty()?" — trống":" — "+maskedKey(key));\n        }\n        return out;\n    }\n\n    private void translate(){\n        if(translating)return;\n        if(selectedFile==null){pick();return;}\n        List<TranslationRouter.Provider> providers=fallbackProviders();\n        if(providers.isEmpty()){settings();return;}\n        if(workspace==null)workspace=new File(getFilesDir(),"translation_workspaces");\n        if(pdfMode){\n            Toast.makeText(this,"Chọn 1 CỘT hoặc GIỮ NGUYÊN BỐ CỤC GỐC.",Toast.LENGTH_SHORT).show();\n            return;\n        }\n        File out=new File(workspace,"translated-final.epub");\n        translating=true;translate.setEnabled(false);reset.setEnabled(false);\n        export.setEnabled(false);progress.setVisibility(View.VISIBLE);progress.setIndeterminate(false);progress.setMax(100);\n        report.setText("🤖 Translation Queue đang chạy\nBatch tối đa 8 unit / request\nMỗi batch hoàn thành sẽ được lưu ngay.");\n        TranslationJob.run(selectedFile,out,workspace,providers,new TranslationJob.Listener(){\n            public void onProgress(int d,int t,int batch,String info){\n                int p=t<=0?0:(int)(100.0*d/t);\n                runOnUiThread(()->{progress.setProgress(p);status.setText("Dịch: "+d+"/"+t+" unit | batch "+batch);report.setText(info+"\n"+d+"/"+t+" unit\n\nNếu hết quota: draft vẫn được lưu, bấm Dịch / Tiếp tục vào ngày khác.");});\n            }\n            public void onDone(File f){\n                lastOutput=f;\n                runOnUiThread(()->{translating=false;translate.setEnabled(true);reset.setEnabled(true);export.setEnabled(true);progress.setProgress(100);report.setText("✅ Dịch hoàn tất.\nEPUB đã rebuild từ source + các translation unit đã khóa.\n"+f.getAbsolutePath());});\n            }\n            public void onPaused(File draft,int d,int t,Exception reason){\n                lastOutput=draft;\n                runOnUiThread(()->{translating=false;translate.setEnabled(true);reset.setEnabled(true);export.setEnabled(draft!=null&&draft.isFile());progress.setProgress(t<=0?0:(int)(100.0*d/t));String msg=reason.getMessage()==null?reason.toString():reason.getMessage();report.setText("⏸ PAUSED — đã lưu "+d+"/"+t+" unit.\n\n"+msg+"\n\nKhông mất phần đã dịch. Bấm Dịch / Tiếp tục sau khi quota hồi phục hoặc sau khi cấu hình provider khác.");});\n            }\n            public void onError(Exception e){\n                runOnUiThread(()->{translating=false;translate.setEnabled(true);reset.setEnabled(true);showError(e);});\n            }\n        });\n    }\n\n    private void updatePdfLayoutButtons(){\n        boolean visible=pdfMode;\n        if(pdfLayout!=null)pdfLayout.setVisibility(View.GONE);\n        if(pdfOneColumn!=null)pdfOneColumn.setVisibility(visible?View.VISIBLE:View.GONE);\n        if(pdfKeepLayout!=null)pdfKeepLayout.setVisibility(visible?View.VISIBLE:View.GONE);\n        if(pdfOneColumn!=null)pdfOneColumn.setText("📄 1 CỘT");\n        if(pdfKeepLayout!=null)pdfKeepLayout.setText("📰 GIỮ NGUYÊN BỐ CỤC GỐC");\n    }\n\n    private void startPdfWithLayout(boolean singleColumn){\n        if(!pdfMode||selectedFile==null){\n            Toast.makeText(this,"Hãy chọn một file PDF trước.",Toast.LENGTH_SHORT).show();\n            return;\n        }\n        List<TranslationRouter.Provider> providers=fallbackProviders();\n        if(providers.isEmpty()){settings();return;}\n        prefsHolder.edit().putInt("pdf_layout_mode",singleColumn?1:0).apply();\n        updatePdfLayoutButtons();\n        translatePdf(providers,singleColumn);\n    }\n\n\n    private void choosePdfLayoutAndTranslate(List<TranslationRouter.Provider> providers){\n        final int saved=prefsHolder.getInt("pdf_layout_mode",0);\n        final int[] selected={Math.max(0,Math.min(saved,1))};\n\n        // Use ordinary Buttons instead of RadioButton/RadioGroup. On some Android\n        // themes/devices the RadioButton children can collapse/not render inside\n        // a programmatically created AlertDialog. The two large buttons are always\n        // visible and also show the current selection explicitly.\n        LinearLayout root=new LinearLayout(this);\n        root.setOrientation(LinearLayout.VERTICAL);\n        int pad=(int)(16*getResources().getDisplayMetrics().density);\n        root.setPadding(pad,0,pad,0);\n\n        TextView hint=new TextView(this);\n        hint.setText("Chọn bố cục PDF sau khi dịch:");\n        hint.setTextSize(16);\n        hint.setTextColor(0xFF555555);\n        hint.setPadding(0,pad/2,0,pad/2);\n        root.addView(hint,new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));\n\n        Button keep=new Button(this);\n        Button one=new Button(this);\n        keep.setAllCaps(false);\n        one.setAllCaps(false);\n        keep.setTextSize(15);\n        one.setTextSize(15);\n        keep.setMinHeight((int)(52*getResources().getDisplayMetrics().density));\n        one.setMinHeight((int)(52*getResources().getDisplayMetrics().density));\n\n        LinearLayout.LayoutParams choiceLp=new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);\n        choiceLp.setMargins(0,0,0,pad/2);\n        root.addView(keep,choiceLp);\n        LinearLayout.LayoutParams choiceLp2=new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);\n        choiceLp2.setMargins(0,0,0,pad/4);\n        root.addView(one,choiceLp2);\n\n        TextView note=new TextView(this);\n        note.setText("2 cột: giữ bố cục, hình và bảng gần PDF gốc.\n"\n                +"1 cột: dồn văn bản thành một cột để đọc trên điện thoại; vị trí hình/bảng có thể thay đổi.");\n        note.setTextSize(13);\n        note.setTextColor(0xFF666666);\n        note.setPadding(0,pad/3,0,pad/2);\n        root.addView(note,new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));\n\n        Runnable refreshChoices=()->{\n            keep.setText((selected[0]==0?"✓ ":"") + "📰 Giữ nguyên bố cục PDF gốc (2 cột)");\n            one.setText((selected[0]==1?"✓ ":"") + "📄 Chuyển sang bố cục 1 cột");\n        };\n        refreshChoices.run();\n\n        keep.setOnClickListener(v->{selected[0]=0;refreshChoices.run();});\n        one.setOnClickListener(v->{selected[0]=1;refreshChoices.run();});\n\n        new AlertDialog.Builder(this)\n                .setTitle("Bố cục PDF đầu ra")\n                .setView(root)\n                .setPositiveButton("DỊCH / TIẾP TỤC",(d,w)->{\n                    prefsHolder.edit().putInt("pdf_layout_mode",selected[0]).apply();\n                    updatePdfLayoutButtons();\n                    translatePdf(providers,selected[0]==1);\n                })\n                .setNegativeButton("HỦY",null)\n                .show();\n    }\n    private void translatePdf(List<TranslationRouter.Provider> providers,boolean singleColumn){\n        File out=new File(workspace,"translated-final.pdf");\n        translating=true;translate.setEnabled(false);reset.setEnabled(false);export.setEnabled(false);\n        progress.setVisibility(View.VISIBLE);progress.setIndeterminate(false);progress.setMax(100);\n        report.setText("📄 Dịch PDF text layer. PDF scan/image-only không được hỗ trợ.");\n        PdfTranslationJob.run(this,selectedFile,out,workspace,providers,singleColumn,new PdfTranslationJob.Listener(){\n            public void onProgress(int d,int t,int page,String info){\n                int p=t<=0?0:(int)(100.0*d/t);\n                runOnUiThread(()->{progress.setProgress(p);status.setText("PDF: "+d+"/"+t+" trang | trang "+page);report.setText(info+"\n"+d+"/"+t+" trang");});\n            }\n            public void onDone(File f){\n                lastOutput=f;\n                runOnUiThread(()->{translating=false;translate.setEnabled(true);reset.setEnabled(true);export.setEnabled(true);progress.setProgress(100);report.setText("✅ Dịch PDF hoàn tất.\n"+f.getAbsolutePath()+"\n\nBố cục: "+(singleColumn?"1 cột":"2 cột như PDF gốc")+" . Hình ảnh/bảng được giữ theo chế độ đã chọn.");});\n            }\n            public void onPaused(File draft,int d,int t,Exception reason){\n                lastOutput=draft;\n                runOnUiThread(()->{translating=false;translate.setEnabled(true);reset.setEnabled(true);export.setEnabled(draft!=null&&draft.isFile());progress.setProgress(t<=0?0:(int)(100.0*d/t));showError(reason);});\n            }\n            public void onError(Exception e){\n                runOnUiThread(()->{translating=false;translate.setEnabled(true);reset.setEnabled(true);showError(e);});\n            }\n        });\n    }\n\n    private void resetProgress(){\n        if(workspace==null)return;\n        new AlertDialog.Builder(this).setTitle("Xóa tiến độ dịch?")\n                .setMessage("Chỉ xóa translation units và draft hiện tại. EPUB nguồn vẫn được giữ nguyên.")\n                .setPositiveButton("Xóa",(d,w)->{\n                    File units=new File(workspace,"units"),manifest=new File(workspace,"progress.json"),draftEpub=new File(workspace,"translated-current.epub"),finalEpub=new File(workspace,"translated-final.epub"),draftPdf=new File(workspace,"translated-current.pdf"),finalPdf=new File(workspace,"translated-final.pdf"),pdfState=new File(workspace,"pdf-progress.properties");\n                    deleteTree(units);manifest.delete();draftEpub.delete();finalEpub.delete();draftPdf.delete();finalPdf.delete();pdfState.delete();lastOutput=null;export.setEnabled(false);\n                    report.setText("Đã xóa tiến độ. EPUB nguồn vẫn còn, có thể dịch lại từ đầu.");\n                }).setNegativeButton("Hủy",null).show();\n    }\n\n    private void deleteTree(File f){\n        if(f==null||!f.exists())return;\n        if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)deleteTree(x);}\n        f.delete();\n    }\n\n    private void exportTranslationLog(){\n        if(workspace==null){Toast.makeText(this,"Chưa có workspace dịch.",Toast.LENGTH_SHORT).show();return;}\n        File log=new File(workspace,"translation-debug.log");\n        if(!log.isFile()){Toast.makeText(this,"Chưa có log. Hãy chạy Dịch / Tiếp tục trước.",Toast.LENGTH_SHORT).show();return;}\n        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);\n        i.setType("text/plain");\n        i.putExtra(Intent.EXTRA_TITLE,"translation-debug.log");\n        startActivityForResult(i,13);\n    }\n\n    private void saveOutput(){\n        if(lastOutput==null||!lastOutput.isFile()){Toast.makeText(this,pdfMode?"Chưa có PDF draft.":"Chưa có EPUB draft.",Toast.LENGTH_SHORT).show();return;}\n        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);\n        i.setType(pdfMode?"application/pdf":"application/epub+zip");\n        i.putExtra(Intent.EXTRA_TITLE,lastOutput.getName());\n        startActivityForResult(i,11);\n    }\n\n    private void openUrl(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){showError(e);}}\n    private static void copyFile(File s,File t)throws IOException{\n        File p=t.getParentFile();if(p!=null&&!p.exists())p.mkdirs();\n        try(InputStream in=new FileInputStream(s);OutputStream out=new FileOutputStream(t)){byte[] b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);}\n    }\n    private void showError(Exception e){\n        progress.setVisibility(View.GONE);\n        new AlertDialog.Builder(this).setTitle("Lỗi").setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("OK",null).show();\n    }\n}\n