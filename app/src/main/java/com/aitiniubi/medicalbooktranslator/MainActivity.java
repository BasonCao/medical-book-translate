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
    private static final String[] GEMINI_MODELS={"gemini-3.8-flash","gemini-3.7-flash","gemini-3.6-flash","gemini-3.5-flash","gemini-3.5-flash-lite","gemini-3.1-flash-lite","gemini-3.1-pro-preview","gemini-3-flash-preview","gemini-2.5-flash","gemini-2.5-flash-lite","gemini-2.5-pro"};
    private static final String[] GEMINI_LABELS={"Gemini 3.8 Flash — STABLE","Gemini 3.7 Flash — STABLE","Gemini 3.6 Flash — STABLE","Gemini 3.5 Flash — STABLE","Gemini 3.5 Flash-Lite — STABLE","Gemini 3.1 Flash-Lite — STABLE","Gemini 3.1 Pro — PREVIEW","Gemini 3 Flash — PREVIEW","Gemini 2.5 Flash — LEGACY","Gemini 2.5 Flash-Lite — LEGACY","Gemini 2.5 Pro — LEGACY"};
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
    private Button analyze,translate,export,reset;
    private File selectedFile,lastOutput,workspace;
    private EpubBook book;
    private PdfBook pdfBook;
    private boolean pdfMode=false;
    private android.content.SharedPreferences prefsHolder;
    private volatile boolean translating=false;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(com.aitiniubi.medicalbooktranslator.R.layout.activity_main);
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
        reset=findViewById(R.id.resetButton);

        open.setOnClickListener(v->pick());
        analyze.setOnClickListener(v->analyze());
        settings.setOnClickListener(v->{try{settings();}catch(Throwable t){showError(t);}});
        glossary.setOnClickListener(v->glossary());
        translate.setOnClickListener(v->translate());
        export.setOnClickListener(v->saveOutput());
        reset.setOnClickListener(v->resetProgress());

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
        analyze.setEnabled(true);translate.setEnabled(true);export.setEnabled(lastOutput!=null);
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
                if(!workspace.exists()&&!workspace.mkdirs())throw new IOException("Không tạo được translation workspace.");
                selectedFile=new File(workspace,"source"+ext);
                copyFile(temp,selectedFile);
                temp.delete();
                prefsHolder.edit().putString("activeWorkspace",workspace.getAbsolutePath()).putBoolean("activePdf",pdfMode).apply();
                File draft=new File(workspace,pdfMode?"translated-current.pdf":"translated-current.epub");
                lastOutput=draft.isFile()?draft:null;
                analyze.setEnabled(true);translate.setEnabled(true);export.setEnabled(lastOutput!=null);
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
        List<GlossaryManager.Term> terms=GlossaryManager.load(workspace);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(16,4,16,4);
        TextView info=new TextView(this);
        info.setText("Tổng "+terms.size()+" thuật ngữ. Sửa trực tiếp rồi bấm LƯU. NẠP THÊM CSV sẽ cộng thêm, không ghi đè thuật ngữ cũ; English trùng sẽ bỏ qua.");
        root.addView(info);

        EditText search=new EditText(this);search.setSingleLine(true);search.setHint("🔎 Tìm thuật ngữ English / Vietnamese");
        root.addView(search);

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button add=new Button(this);add.setText("➕ THÊM");
        Button imp=new Button(this);imp.setText("📥 NẠP THÊM CSV");
        actions.addView(add,new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(imp,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(actions);

        ScrollView sv=new ScrollView(this);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));

        ArrayList<LinearLayout> rows=new ArrayList<>();
        ArrayList<GlossaryManager.Term> current=new ArrayList<>(terms);

        java.util.function.BiConsumer<GlossaryManager.Term,Boolean> addRow=(t,focus)->{
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(0,8,0,8);
            EditText en=new EditText(this);en.setSingleLine(true);en.setText(t==null?"":t.english);en.setHint("English *");
            EditText vi=new EditText(this);vi.setSingleLine(true);vi.setText(t==null?"":t.vietnamese);vi.setHint("Vietnamese *");
            EditText ca=new EditText(this);ca.setSingleLine(true);ca.setText(t==null?"":t.category);ca.setHint("Category");
            EditText no=new EditText(this);no.setSingleLine(true);no.setText(t==null?"":t.note);no.setHint("Note");
            row.addView(en);row.addView(vi);row.addView(ca);row.addView(no);list.addView(row);rows.add(row);
            if(focus){en.requestFocus();sv.post(()->sv.fullScroll(View.FOCUS_DOWN));}
        };

        for(GlossaryManager.Term t:terms)addRow.accept(t,false);

        Runnable filter=()->{
            String q=search.getText().toString().trim().toLowerCase(Locale.US);
            for(LinearLayout row:rows){
                String en=((EditText)row.getChildAt(0)).getText().toString().toLowerCase(Locale.US);
                String vi=((EditText)row.getChildAt(1)).getText().toString().toLowerCase(Locale.US);
                row.setVisibility(q.isEmpty()||en.contains(q)||vi.contains(q)?View.VISIBLE:View.GONE);
            }
        };
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c1,int c2){}
            public void onTextChanged(CharSequence s,int st,int b,int c1){filter.run();}
            public void afterTextChanged(android.text.Editable e){}
        });

        AlertDialog dlg=new AlertDialog.Builder(this).setTitle("📚 Trung tâm thuật ngữ ("+terms.size()+")")
                .setView(root).setNegativeButton("Đóng",null).setPositiveButton("💾 LƯU",null).create();

        add.setOnClickListener(v->addRow.accept(null,true));
        imp.setOnClickListener(v->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("text/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/csv","text/comma-separated-values","application/csv","text/plain"});
            i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,12);dlg.dismiss();
        });

        dlg.setOnShowListener(x->dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            ArrayList<GlossaryManager.Term> edited=new ArrayList<>();
            HashSet<String> seen=new HashSet<>();
            for(LinearLayout row:rows){
                if(row.getVisibility()!=View.VISIBLE && !search.getText().toString().trim().isEmpty())continue;
                if(row.getChildCount()<4)continue;
                String en=((EditText)row.getChildAt(0)).getText().toString().trim();
                String vi=((EditText)row.getChildAt(1)).getText().toString().trim().replace("\\\\n","\n");
                if(en.isEmpty()||vi.isEmpty())continue;
                String k=en.toLowerCase(Locale.US);
                if(!seen.add(k))continue;
                edited.add(new GlossaryManager.Term(en,vi,
                        ((EditText)row.getChildAt(2)).getText().toString().trim(),
                        ((EditText)row.getChildAt(3)).getText().toString().trim()));
            }
            // A filtered view must not accidentally delete hidden rows. Merge the
            // edited visible rows back with the untouched original rows.
            if(!search.getText().toString().trim().isEmpty()){
                LinkedHashMap<String,GlossaryManager.Term> merged=new LinkedHashMap<>();
                for(GlossaryManager.Term t:terms)merged.put(t.english.trim().toLowerCase(Locale.US),t);
                for(GlossaryManager.Term t:edited)merged.put(t.english.trim().toLowerCase(Locale.US),t);
                edited=new ArrayList<>(merged.values());
            }
            try{
                GlossaryManager.save(workspace,edited);
                Toast.makeText(this,"Đã lưu "+edited.size()+" thuật ngữ.",Toast.LENGTH_LONG).show();
                report.setText("📚 Glossary: "+edited.size()+" thuật ngữ.");
                dlg.dismiss();
            }catch(Exception e){showError(e);}
        }));
        dlg.show();
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

    private void settings(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,8,24,8);

        // One-tap AI profiles. Each profile keeps its own provider/model/key.
        TextView profileTitle=new TextView(this);profileTitle.setText("CẤU HÌNH API — chạm để nạp lại key");
        profileTitle.setTextSize(15);box.addView(profileTitle);
        HorizontalScrollView hsv=new HorizontalScrollView(this);
        LinearLayout profileBar=new LinearLayout(this);profileBar.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(profileBar);box.addView(hsv,new LinearLayout.LayoutParams(-1,-2));

        Spinner provider=new Spinner(this);provider.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,PROVIDERS));
        Spinner model=new Spinner(this);
        Button refreshGemini=new Button(this);refreshGemini.setText("🔄 LÀM MỚI DANH SÁCH GEMINI MODEL");
        EditText customModel=new EditText(this);customModel.setHint("Model ID tùy chỉnh");customModel.setSingleLine(true);
        EditText ep=new EditText(this);ep.setHint("Endpoint");ep.setSingleLine(true);
        EditText key=new EditText(this);key.setHint("API key");key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);

        CheckBox freePool=new CheckBox(this);freePool.setText("FREE AI POOL — dùng các cấu hình Free");
        freePool.setChecked(prefsHolder.getBoolean(PREF_FREE_POOL,true));
        CheckBox allowPaid=new CheckBox(this);allowPaid.setText("Cho phép provider trả phí khi free pool hết quota");
        allowPaid.setChecked(prefsHolder.getBoolean(PREF_ALLOW_PAID,false));

        TextView note=new TextView(this);note.setTextSize(12);note.setPadding(0,8,0,0);
        TextView fallbackNote=new TextView(this);fallbackNote.setTextSize(12);fallbackNote.setPadding(0,8,0,8);
        TextView keyLink=new TextView(this);keyLink.setTextSize(14);keyLink.setPadding(0,8,0,8);
        keyLink.setTextColor(0xff1565c0);keyLink.setPaintFlags(keyLink.getPaintFlags()|8);

        final int[] active={Math.max(1,Math.min(10,prefsHolder.getInt("active_profile",1)))};

        // Migrate the old single-key config into profile 1 once.
        if(!hasProfile(1)){
            String legacyProvider=providerFor(prefsHolder.getString("endpoint",OPENROUTER_ENDPOINT));
            String legacyKey=providerKey(legacyProvider);
            String legacyModel=providerModel(legacyProvider);
            if(!legacyKey.trim().isEmpty()){
                prefsHolder.edit()
                        .putString(profileKey(1,"provider"),legacyProvider)
                        .putString(profileKey(1,"endpoint"),legacyProvider.equals(PROVIDERS[5])?prefsHolder.getString("endpoint_custom",""):"")
                        .putString(profileKey(1,"model"),legacyModel)
                        .putString(profileKey(1,"key"),legacyKey).apply();
            }
        }

        final Runnable[] updateProfileButtons=new Runnable[1];
        updateProfileButtons[0]=()->{
            profileBar.removeAllViews();
            for(int i=1;i<=10;i++){
                final int slot=i;
                Button b=new Button(this);
                b.setText("Cấu hình "+i+(hasProfile(i)?"\n"+maskedKey(prefsHolder.getString(profileKey(i,"key"),"")):"\n(trống)"));
                b.setAllCaps(false);
                b.setOnClickListener(v->{
                    active[0]=slot;
                    prefsHolder.edit().putInt("active_profile",slot).apply();
                    String p=profileProvider(slot);
                    provider.setSelection(Math.max(0,indexOf(PROVIDERS,p)));
                    loadProfileFields(slot,p,provider,model,customModel,ep,key,note,keyLink,refreshGemini);
                    updateProfileButtons[0].run();
                });
                profileBar.addView(b,new LinearLayout.LayoutParams(-2,-2));
            }
        };

        final boolean[] refreshing={false};
        Runnable refresh=()->{
            if(refreshing[0])return;
            refreshing[0]=true;
            try{
            Object selectedProvider=provider.getSelectedItem();
            if(selectedProvider==null)return;
            String p=selectedProvider.toString();
            String[] ms=modelsFor(p),labels=labelsFor(p);
            int slot=active[0];
            String saved=prefsHolder.getString(profileKey(slot,"model"),"");
            model.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));
            int sel=indexOf(ms,saved);if(sel<0)sel=0;model.setSelection(sel);

            if(p.equals(PROVIDERS[0])){
                ep.setText(OPENROUTER_ENDPOINT);customModel.setVisibility(View.GONE);model.setVisibility(View.VISIBLE);
                note.setText("OpenRouter: Free/Paid. Mỗi Cấu hình có API key riêng.");
                keyLink.setText("🔑 OpenRouter API key");keyLink.setVisibility(View.VISIBLE);keyLink.setOnClickListener(v->openUrl("https://openrouter.ai/settings/keys"));
            }else if(p.equals(PROVIDERS[1])){
                ep.setText(GEMINI_ENDPOINT);customModel.setVisibility(View.GONE);model.setVisibility(View.VISIBLE);
                note.setText("Gemini: mỗi Cấu hình có thể lưu một API key Free khác nhau.");
                keyLink.setText("🔑 Gemini API key");keyLink.setVisibility(View.VISIBLE);keyLink.setOnClickListener(v->openUrl("https://aistudio.google.com/apikey"));
            }else if(p.equals(PROVIDERS[2])){
                ep.setText(OPENAI_ENDPOINT);customModel.setVisibility(View.GONE);model.setVisibility(View.VISIBLE);
                note.setText("OpenAI API.");
                keyLink.setText("🔑 OpenAI API key");keyLink.setVisibility(View.VISIBLE);keyLink.setOnClickListener(v->openUrl("https://platform.openai.com/api-keys"));
            }else if(p.equals(PROVIDERS[3])){
                ep.setText(DEEPSEEK_ENDPOINT);customModel.setVisibility(View.GONE);model.setVisibility(View.VISIBLE);
                note.setText("DeepSeek API.");
                keyLink.setText("🔑 DeepSeek API key");keyLink.setVisibility(View.VISIBLE);keyLink.setOnClickListener(v->openUrl("https://platform.deepseek.com/api_keys"));
            }else if(p.equals(PROVIDERS[4])){
                ep.setText(MISTRAL_ENDPOINT);customModel.setVisibility(View.GONE);model.setVisibility(View.VISIBLE);
                note.setText("Mistral API.");
                keyLink.setText("🔑 Mistral API key");keyLink.setVisibility(View.VISIBLE);keyLink.setOnClickListener(v->openUrl("https://console.mistral.ai/api-keys/"));
            }else{
                ep.setText(prefsHolder.getString(profileKey(slot,"endpoint"),""));
                customModel.setText(saved);customModel.setVisibility(View.VISIBLE);model.setVisibility(View.GONE);
                note.setText("Provider tùy chỉnh: endpoint + model + API key.");
                keyLink.setText("ℹ️ Custom provider");keyLink.setVisibility(View.VISIBLE);
            }
            key.setText(prefsHolder.getString(profileKey(slot,"key"),""));
            refreshGemini.setVisibility(p.equals(PROVIDERS[1])?View.VISIBLE:View.GONE);

            StringBuilder fb=new StringBuilder(freePool.isChecked()?"FREE POOL: ":"ALL PROVIDERS: ");
            List<TranslationRouter.Provider> ps=savedProfileProviders();
            if(ps.isEmpty())fb.append("chưa có cấu hình hợp lệ");else for(int i=0;i<ps.size();i++){if(i>0)fb.append(" → ");fb.append(ps.get(i).name);}
            fallbackNote.setText(fb.toString());
            }finally{
                refreshing[0]=false;
            }
        };

        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> a,View v,int pos,long id){refresh.run();}
            public void onNothingSelected(AdapterView<?> a){}
        });
        refreshGemini.setOnClickListener(v->refreshGeminiCatalog(model,note,refresh,key.getText().toString()));
        freePool.setOnCheckedChangeListener((b,checked)->refresh.run());
        allowPaid.setOnCheckedChangeListener((b,checked)->refresh.run());

        Button test=new Button(this);test.setText("KIỂM TRA CẤU HÌNH NÀY");
        test.setOnClickListener(v->{
            String p=(String)provider.getSelectedItem(),selectedModel;
            if(p.equals(PROVIDERS[5]))selectedModel=customModel.getText().toString().trim();
            else{String[] ms=modelsFor(p);int pos=model.getSelectedItemPosition();selectedModel=ms[Math.max(0,Math.min(pos,ms.length-1))];}
            TranslationConfig tc=new TranslationConfig();tc.endpoint=ep.getText().toString().trim();tc.model=selectedModel;tc.apiKey=key.getText().toString().trim();
            if(tc.apiKey.isEmpty()){Toast.makeText(this,"API key đang trống.",Toast.LENGTH_SHORT).show();return;}
            test.setEnabled(false);
            new Thread(()->{try{
                String out=OpenAICompatibleTranslator.translate("Translate only: fetal ultrasound","Medical translation connection test.",tc);
                runOnUiThread(()->{test.setEnabled(true);new AlertDialog.Builder(this).setTitle("Kết nối thành công").setMessage("Cấu hình "+active[0]+"\nProvider: "+p+"\nModel: "+tc.model+"\n\n"+out).setPositiveButton("OK",null).show();});
            }catch(Exception ex){runOnUiThread(()->{test.setEnabled(true);showError(ex);});}}).start();
        });

        Button save=new Button(this);save.setText("💾 LƯU CẤU HÌNH "+active[0]);
        save.setOnClickListener(v->{
            String p=(String)provider.getSelectedItem(),selectedModel;
            if(p.equals(PROVIDERS[5]))selectedModel=customModel.getText().toString().trim();
            else{String[] ms=modelsFor(p);int pos=model.getSelectedItemPosition();selectedModel=ms[Math.max(0,Math.min(pos,ms.length-1))];}
            String enteredKey=key.getText().toString().trim();
            if(enteredKey.isEmpty()){Toast.makeText(this,"API key đang trống.",Toast.LENGTH_SHORT).show();return;}
            int slot=active[0];
            prefsHolder.edit()
                    .putBoolean(PREF_FREE_POOL,freePool.isChecked()).putBoolean(PREF_ALLOW_PAID,allowPaid.isChecked())
                    .putString(profileKey(slot,"provider"),p)
                    .putString(profileKey(slot,"endpoint"),p.equals(PROVIDERS[5])?ep.getText().toString().trim():"")
                    .putString(profileKey(slot,"model"),selectedModel)
                    .putString(profileKey(slot,"key"),enteredKey).apply();
            updateProfileButtons[0].run();
            status.setText((freePool.isChecked()?"FREE POOL":"ALL PROVIDERS")+" | Đã lưu Cấu hình "+slot+" | "+p+" / "+selectedModel);
            Toast.makeText(this,"Đã lưu Cấu hình "+slot+". Chạm nút cấu hình để nạp lại.",Toast.LENGTH_SHORT).show();
            refresh.run();
        });

        Button clear=new Button(this);clear.setText("🗑 XÓA CẤU HÌNH "+active[0]);
        clear.setOnClickListener(v->{
            int slot=active[0];
            new AlertDialog.Builder(this).setTitle("Xóa Cấu hình "+slot+"?")
                    .setMessage("API key và model của cấu hình này sẽ bị xóa khỏi máy.")
                    .setPositiveButton("Xóa",(d,w)->{
                        prefsHolder.edit().remove(profileKey(slot,"provider")).remove(profileKey(slot,"endpoint"))
                                .remove(profileKey(slot,"model")).remove(profileKey(slot,"key")).apply();
                        loadProfileFields(slot,PROVIDERS[0],provider,model,customModel,ep,key,note,keyLink,refreshGemini);
                        updateProfileButtons[0].run();refresh.run();
                    }).setNegativeButton("Hủy",null).show();
        });

        box.addView(freePool);box.addView(allowPaid);box.addView(profileTitle);box.addView(hsv);
        box.addView(provider);box.addView(model);box.addView(refreshGemini);box.addView(customModel);box.addView(ep);box.addView(key);box.addView(keyLink);
        box.addView(save);box.addView(test);box.addView(clear);box.addView(note);box.addView(fallbackNote);

        updateProfileButtons[0].run();
        int initial=active[0];String ip=profileProvider(initial);
        provider.setSelection(Math.max(0,indexOf(PROVIDERS,ip)));
        refresh.run();

        new AlertDialog.Builder(this).setTitle("Cấu hình AI + Failover").setView(box)
                .setNegativeButton("Đóng",null).show();
    }

    private void loadProfileFields(int slot,String p,Spinner provider,Spinner model,EditText customModel,
                                   EditText ep,EditText key,TextView note,TextView keyLink,Button refreshGemini){
        provider.setSelection(Math.max(0,indexOf(PROVIDERS,p)));
        String saved=prefsHolder.getString(profileKey(slot,"model"),"");
        key.setText(prefsHolder.getString(profileKey(slot,"key"),""));
        if(p.equals(PROVIDERS[5]))customModel.setText(saved);
        ep.setText(p.equals(PROVIDERS[5])?prefsHolder.getString(profileKey(slot,"endpoint"),""):"");
        refreshGemini.setVisibility(p.equals(PROVIDERS[1])?View.VISIBLE:View.GONE);
    }

    private void translate(){
        if(translating)return;
        if(selectedFile==null){pick();return;}
        List<TranslationRouter.Provider> providers=fallbackProviders();
        if(providers.isEmpty()){settings();return;}
        if(workspace==null)workspace=new File(getFilesDir(),"translation_workspaces");
        if(pdfMode){
            choosePdfLayoutAndTranslate(providers);
            return;
        }
        File out=new File(workspace,"translated-final.epub");
        translating=true;translate.setEnabled(false);reset.setEnabled(false);
        export.setEnabled(false);progress.setVisibility(View.VISIBLE);progress.setIndeterminate(false);progress.setMax(100);
        report.setText("🤖 Translation Queue đang chạy\nBatch tối đa 8 unit / request\nMỗi batch hoàn thành sẽ được lưu ngay.");
        TranslationJob.run(selectedFile,out,workspace,providers,new TranslationJob.Listener(){
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

    private void choosePdfLayoutAndTranslate(List<TranslationRouter.Provider> providers){
        String[] choices={
                "📰 Giữ bố cục 2 cột như PDF gốc",
                "📄 Chuyển sang bố cục 1 cột"
        };
        int saved=prefsHolder.getInt("pdf_layout_mode",0);
        final int[] selected={Math.max(0,Math.min(saved,1))};
        new AlertDialog.Builder(this)
                .setTitle("Bố cục PDF đầu ra")
                .setSingleChoiceItems(choices,selected[0],(d,which)->selected[0]=which)
                .setMessage("2 cột: giữ hình, bảng và bố cục gần PDF gốc.\n1 cột: dễ đọc hơn nhưng có thể thay đổi vị trí hình/bảng.")
                .setPositiveButton("DỊCH / TIẾP TỤC",(d,w)->{
                    prefsHolder.edit().putInt("pdf_layout_mode",selected[0]).apply();
                    translatePdf(providers,selected[0]==1);
                })
                .setNegativeButton("HỦY",null).show();
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
                runOnUiThread(()->{translating=false;translate.setEnabled(true);reset.setEnabled(true);export.setEnabled(draft!=null&&draft.isFile());progress.setProgress(t<=0?0:(int)(100.0*d/t));showError(reason);});
            }
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
