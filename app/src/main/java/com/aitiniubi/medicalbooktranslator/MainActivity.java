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
        settings.setOnClickListener(v->settings());
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
            try{
                Uri u=d.getData();
                InputStream in=getContentResolver().openInputStream(u);
                if(in==null)throw new IOException("Không mở được file CSV.");
                int n=GlossaryManager.importCsv(workspace,in);in.close();
                List<GlossaryManager.Term> terms=GlossaryManager.load(workspace);
                Toast.makeText(this,"Đã nạp "+n+" thuật ngữ. Tổng hiện tại: "+terms.size(),Toast.LENGTH_LONG).show();
                report.setText("📚 Glossary: "+terms.size()+" thuật ngữ đang được áp dụng khi dịch batch.");
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
        boolean freeOnly=prefsHolder.getBoolean(PREF_FREE_POOL,true);
        boolean allowPaid=prefsHolder.getBoolean(PREF_ALLOW_PAID,false);
        String selected=providerFor(prefsHolder.getString("endpoint",OPENROUTER_ENDPOINT));
        String[] order=freeOnly
                ? new String[]{selected,PROVIDERS[0],PROVIDERS[1],allowPaid?PROVIDERS[2]:"",allowPaid?PROVIDERS[3]:"",allowPaid?PROVIDERS[4]:"",allowPaid?PROVIDERS[5]:""}
                : new String[]{selected,PROVIDERS[0],PROVIDERS[1],PROVIDERS[2],PROVIDERS[3],PROVIDERS[4],PROVIDERS[5]};
        List<TranslationRouter.Provider> out=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        for(String p:order){
            if(p==null||p.isEmpty()||!seen.add(p))continue;
            if(freeOnly&&!isFreePoolProvider(p)&&!allowPaid)continue;
            TranslationConfig cfg=makeProviderConfig(p);
            if(cfg.endpoint!=null&&!cfg.endpoint.trim().isEmpty()&&cfg.model!=null&&!cfg.model.trim().isEmpty()&&cfg.apiKey!=null&&!cfg.apiKey.trim().isEmpty())
                out.add(new TranslationRouter.Provider(p,cfg));
        }
        return out;
    }

    private int indexOf(String[] a,String v){for(int i=0;i<a.length;i++)if(a[i].equals(v))return i;return -1;}
    private String[] modelsFor(String p){if(p.equals(PROVIDERS[0]))return OR_MODELS;if(p.equals(PROVIDERS[1]))return geminiCatalog()[0];if(p.equals(PROVIDERS[2]))return OPENAI_MODELS;if(p.equals(PROVIDERS[3]))return DEEPSEEK_MODELS;if(p.equals(PROVIDERS[4]))return MISTRAL_MODELS;return new String[]{providerModel(PROVIDERS[5])};}
    private String[] labelsFor(String p){if(p.equals(PROVIDERS[0]))return OR_LABELS;if(p.equals(PROVIDERS[1]))return geminiCatalog()[1];if(p.equals(PROVIDERS[2]))return OPENAI_LABELS;if(p.equals(PROVIDERS[3]))return DEEPSEEK_LABELS;if(p.equals(PROVIDERS[4]))return MISTRAL_LABELS;return new String[]{providerModel(PROVIDERS[5])};}

    private String[][] geminiCatalog(){
        String idsRaw=prefsHolder.getString("gemini_catalog_ids","");
        String labelsRaw=prefsHolder.getString("gemini_catalog_labels","");
        if(!idsRaw.trim().isEmpty()&&!labelsRaw.trim().isEmpty()){
            String[] ids=idsRaw.split("\\n",-1),labels=labelsRaw.split("\\n",-1);
            if(ids.length==labels.length&&ids.length>0)return new String[][]{ids,labels};
        }
        return new String[][]{GEMINI_MODELS,GEMINI_LABELS};
    }

    private void refreshGeminiCatalog(Spinner model,TextView note,Runnable refresh){
        String apiKey=prefsHolder.getString(PREF_GEMINI_KEY,"").trim();
        if(apiKey.isEmpty()){new AlertDialog.Builder(this).setTitle("Chưa có Gemini API key").setMessage("Nhập Gemini API key trước, rồi bấm làm mới danh sách model.").setPositiveButton("OK",null).show();return;}
        Toast.makeText(this,"Đang lấy danh sách Gemini model từ Google…",Toast.LENGTH_SHORT).show();
        new Thread(()->{try{
            HttpUrl url=HttpUrl.parse(GEMINI_MODELS_API).newBuilder().addQueryParameter("key",apiKey).build();
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
                    .setPositiveButton("OK",null).show(); return;
        }
        List<GlossaryManager.Term> terms=GlossaryManager.load(workspace);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(32,8,32,8);
        TextView info=new TextView(this);
        info.setText("Glossary hiện tại: "+terms.size()+" thuật ngữ\\n\\nCSV mẫu gồm: English,Vietnamese,Category,Note\\nVí dụ: nuchal translucency,độ mờ da gáy,Ultrasound,NT");
        info.setPadding(0,0,0,16);
        Button importBtn=new Button(this);importBtn.setText("📥 NẠP CSV THUẬT NGỮ");
        Button viewBtn=new Button(this);viewBtn.setText("🔎 XEM THUẬT NGỮ");
        box.addView(info);box.addView(importBtn);box.addView(viewBtn);
        AlertDialog dlg=new AlertDialog.Builder(this).setTitle("📚 Thuật ngữ chuyên ngành").setView(box).setNegativeButton("Đóng",null).create();
        importBtn.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("text/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/csv","text/comma-separated-values","application/csv","text/plain"});i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,12);dlg.dismiss();});
        viewBtn.setOnClickListener(v->{
            List<GlossaryManager.Term> now=GlossaryManager.load(workspace);StringBuilder s=new StringBuilder();
            for(int i=0;i<now.size()&&i<200;i++)s.append(now.get(i).english).append(" → ").append(now.get(i).vietnamese).append("\\n");
            if(now.size()>200)s.append("\\n... còn ").append(now.size()-200).append(" thuật ngữ");
            new AlertDialog.Builder(this).setTitle("Glossary ("+now.size()+")").setMessage(s.length()==0?"Chưa có thuật ngữ.":s.toString()).setPositiveButton("OK",null).show();
        });
        dlg.show();
    }

    private void settings(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(40,10,40,10);
        Spinner provider=new Spinner(this);provider.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,PROVIDERS));
        provider.setSelection(Math.max(0,indexOf(PROVIDERS,providerFor(prefsHolder.getString("endpoint",OPENROUTER_ENDPOINT)))));
        Spinner model=new Spinner(this);
        Button refreshGemini=new Button(this);refreshGemini.setText("🔄 Làm mới danh sách Gemini model");
        EditText customModel=new EditText(this);customModel.setHint("Model ID tùy chỉnh");customModel.setSingleLine(true);
        EditText ep=new EditText(this);ep.setHint("Endpoint");ep.setSingleLine(true);
        EditText key=new EditText(this);key.setHint("API key");key.setSingleLine(true);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView note=new TextView(this);note.setTextSize(12);note.setPadding(0,12,0,0);
        TextView fallbackNote=new TextView(this);fallbackNote.setTextSize(12);fallbackNote.setPadding(0,8,0,0);
        CheckBox freePool=new CheckBox(this);freePool.setText("FREE AI POOL — OpenRouter Free + Gemini Free");
        freePool.setChecked(prefsHolder.getBoolean(PREF_FREE_POOL,true));
        CheckBox allowPaid=new CheckBox(this);allowPaid.setText("Cho phép provider trả phí khi free pool hết quota");
        allowPaid.setChecked(prefsHolder.getBoolean(PREF_ALLOW_PAID,false));
        TextView keyLink=new TextView(this);keyLink.setTextSize(14);keyLink.setPadding(0,10,0,10);keyLink.setVisibility(View.GONE);
        keyLink.setTextColor(0xff1565c0);keyLink.setPaintFlags(keyLink.getPaintFlags()|8);

        Runnable refresh=()->{
            String p=(String)provider.getSelectedItem();
            String[] ms=modelsFor(p),labels=labelsFor(p);
            if(p.equals(PROVIDERS[0])){ep.setText(OPENROUTER_ENDPOINT);customModel.setVisibility(View.GONE);note.setText("OpenRouter: FREE và PAID. Khi provider hết quota app sẽ thử provider tiếp theo.");}
            else if(p.equals(PROVIDERS[1])){ep.setText(GEMINI_ENDPOINT);customModel.setVisibility(View.GONE);note.setText("Gemini API có Free Tier cho một số model và paid tier khi cần nhiều hơn.");}
            else if(p.equals(PROVIDERS[2])){ep.setText(OPENAI_ENDPOINT);customModel.setVisibility(View.GONE);note.setText("OpenAI API trả phí theo token.");}
            else if(p.equals(PROVIDERS[3])){ep.setText(DEEPSEEK_ENDPOINT);customModel.setVisibility(View.GONE);note.setText("DeepSeek V4.1 Flash và V4 Pro hỗ trợ OpenAI Chat Completions.");}
            else if(p.equals(PROVIDERS[4])){ep.setText(MISTRAL_ENDPOINT);customModel.setVisibility(View.GONE);note.setText("Mistral API hỗ trợ OpenAI-compatible Chat Completions.");}
            else{ep.setText(prefsHolder.getString("endpoint_custom",""));customModel.setText(providerModel(PROVIDERS[5]));customModel.setVisibility(View.VISIBLE);note.setText("Provider tùy chỉnh: nhập endpoint, model và API key.");}
            String saved=providerModel(p);model.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));
            int sel=indexOf(ms,saved);if(sel<0)sel=0;model.setSelection(sel);model.setVisibility(p.equals(PROVIDERS[5])?View.GONE:View.VISIBLE);
            key.setText(providerKey(p));
            if(p.equals(PROVIDERS[0])){keyLink.setText("🔑 OpenRouter API key");keyLink.setVisibility(View.VISIBLE);keyLink.setOnClickListener(v->openUrl("https://openrouter.ai/settings/keys"));}
            else if(p.equals(PROVIDERS[1])){keyLink.setText("🔑 Gemini API key");keyLink.setVisibility(View.VISIBLE);keyLink.setOnClickListener(v->openUrl("https://aistudio.google.com/apikey"));}
            else if(p.equals(PROVIDERS[2])){keyLink.setText("🔑 OpenAI API key");keyLink.setVisibility(View.VISIBLE);keyLink.setOnClickListener(v->openUrl("https://platform.openai.com/api-keys"));}
            else if(p.equals(PROVIDERS[3])){keyLink.setText("🔑 DeepSeek API key");keyLink.setVisibility(View.VISIBLE);keyLink.setOnClickListener(v->openUrl("https://platform.deepseek.com/api_keys"));}
            else if(p.equals(PROVIDERS[4])){keyLink.setText("🔑 Mistral API key");keyLink.setVisibility(View.VISIBLE);keyLink.setOnClickListener(v->openUrl("https://console.mistral.ai/api-keys/"));}
            else{keyLink.setText("ℹ️ Custom provider");keyLink.setVisibility(View.VISIBLE);}
            StringBuilder fb=new StringBuilder(freePool.isChecked()?"FREE POOL: ":"ALL PROVIDERS: ");List<TranslationRouter.Provider> ps=fallbackProviders();
            if(ps.isEmpty())fb.append("chưa cấu hình");else for(int i=0;i<ps.size();i++){if(i>0)fb.append(" → ");fb.append(ps.get(i).name);}
            fallbackNote.setText(fb.toString());
        };

        refreshGemini.setVisibility(provider.getSelectedItemPosition()==1?View.VISIBLE:View.GONE);
        refreshGemini.setOnClickListener(v->refreshGeminiCatalog(model,note,refresh));
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> a,View v,int pos,long id){refresh.run();refreshGemini.setVisibility(pos==1?View.VISIBLE:View.GONE);}public void onNothingSelected(AdapterView<?> a){}});
        model.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> a,View v,int pos,long id){}public void onNothingSelected(AdapterView<?> a){}});

        Button test=new Button(this);test.setText("KIỂM TRA PROVIDER NÀY");
        test.setOnClickListener(v->{
            String p=(String)provider.getSelectedItem(),selectedModel;
            if(p.equals(PROVIDERS[5]))selectedModel=customModel.getText().toString().trim();
            else{String[] ms=modelsFor(p);int pos=model.getSelectedItemPosition();selectedModel=ms[Math.max(0,Math.min(pos,ms.length-1))];}
            TranslationConfig tc=new TranslationConfig();tc.endpoint=ep.getText().toString().trim();tc.model=selectedModel;tc.apiKey=key.getText().toString();test.setEnabled(false);
            new Thread(()->{try{
                String out=OpenAICompatibleTranslator.translate("Translate only: fetal ultrasound","Medical translation connection test.",tc);
                runOnUiThread(()->{test.setEnabled(true);new AlertDialog.Builder(this).setTitle("Kết nối thành công").setMessage("Provider: "+p+"\nModel: "+tc.model+"\n\n"+out).setPositiveButton("OK",null).show();});
            }catch(Exception ex){runOnUiThread(()->{test.setEnabled(true);showError(ex);});}}).start();
        });

        box.addView(freePool);box.addView(allowPaid);box.addView(provider);box.addView(model);box.addView(refreshGemini);box.addView(customModel);box.addView(ep);box.addView(key);box.addView(keyLink);box.addView(test);box.addView(note);box.addView(fallbackNote);
        new AlertDialog.Builder(this).setTitle("Cấu hình AI + Failover").setView(box).setPositiveButton("Lưu",(d,w)->{
            String p=(String)provider.getSelectedItem(),selectedModel;
            if(p.equals(PROVIDERS[5]))selectedModel=customModel.getText().toString().trim();
            else{String[] ms=modelsFor(p);int pos=model.getSelectedItemPosition();selectedModel=ms[Math.max(0,Math.min(pos,ms.length-1))];}
            String enteredKey=key.getText().toString();
            android.content.SharedPreferences.Editor e=prefsHolder.edit();
            e.putBoolean(PREF_FREE_POOL,freePool.isChecked()).putBoolean(PREF_ALLOW_PAID,allowPaid.isChecked());
            e.putString("apiKey",enteredKey).putString("endpoint",ep.getText().toString().trim()).putString("model",selectedModel);
            if(p.equals(PROVIDERS[0]))e.putString(PREF_OR_KEY,enteredKey).putString("model_openrouter",selectedModel);
            else if(p.equals(PROVIDERS[1]))e.putString(PREF_GEMINI_KEY,enteredKey).putString("model_gemini",selectedModel);
            else if(p.equals(PROVIDERS[2]))e.putString(PREF_OPENAI_KEY,enteredKey).putString("model_openai",selectedModel);
            else if(p.equals(PROVIDERS[3]))e.putString(PREF_DEEPSEEK_KEY,enteredKey).putString("model_deepseek",selectedModel);
            else if(p.equals(PROVIDERS[4]))e.putString(PREF_MISTRAL_KEY,enteredKey).putString("model_mistral",selectedModel);
            else e.putString(PREF_CUSTOM_KEY,enteredKey).putString("endpoint_custom",ep.getText().toString().trim()).putString("model_custom",selectedModel);
            e.apply();status.setText((freePool.isChecked()?"FREE POOL":"ALL PROVIDERS")+" | AI: "+p+" / "+selectedModel+" | Failover: "+fallbackProviders().size()+" provider");
        }).setNegativeButton("Hủy",null).show();
    }

    private void translate(){
        if(translating)return;
        if(selectedFile==null){pick();return;}
        List<TranslationRouter.Provider> providers=fallbackProviders();
        if(providers.isEmpty()){settings();return;}
        if(workspace==null)workspace=new File(getFilesDir(),"translation_workspaces");
        if(pdfMode){
            translatePdf(providers);
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

    private void translatePdf(List<TranslationRouter.Provider> providers){
        File out=new File(workspace,"translated-final.pdf");
        translating=true;translate.setEnabled(false);reset.setEnabled(false);export.setEnabled(false);
        progress.setVisibility(View.VISIBLE);progress.setIndeterminate(false);progress.setMax(100);
        report.setText("📄 Dịch PDF text layer. PDF scan/image-only không được hỗ trợ.");
        PdfTranslationJob.run(this,selectedFile,out,workspace,providers,new PdfTranslationJob.Listener(){
            public void onProgress(int d,int t,int page,String info){
                int p=t<=0?0:(int)(100.0*d/t);
                runOnUiThread(()->{progress.setProgress(p);status.setText("PDF: "+d+"/"+t+" trang | trang "+page);report.setText(info+"\n"+d+"/"+t+" trang");});
            }
            public void onDone(File f){
                lastOutput=f;
                runOnUiThread(()->{translating=false;translate.setEnabled(true);reset.setEnabled(true);export.setEnabled(true);progress.setProgress(100);report.setText("✅ Dịch PDF hoàn tất.\n"+f.getAbsolutePath()+"\n\nLưu ý: bản V1.7-PDF giữ số trang/kích thước trang nhưng dựng lại phần text; không giữ nguyên bố cục đồ họa 1:1.");});
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
