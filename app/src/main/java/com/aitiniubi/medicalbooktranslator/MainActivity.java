package com.aitiniubi.medicalbooktranslator;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.aitiniubi.medicalbooktranslator.epub.*;
import com.aitiniubi.medicalbooktranslator.translation.*;
import java.io.*;
import java.util.*;

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
    private static final String[] GEMINI_MODELS={"gemini-3.8-flash","gemini-3.7-flash","gemini-2.5-flash-lite"};
    private static final String[] GEMINI_LABELS={"Gemini 3.8 Flash — FREE tier","Gemini 3.7 Flash — FREE tier","Gemini 2.5 Flash-Lite — FREE tier"};
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

    private TextView status,report;
    private ProgressBar progress;
    private Button analyze,translate,export,reset;
    private File selectedFile,lastOutput,workspace;
    private EpubBook book;
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
        export=findViewById(R.id.exportButton);
        reset=findViewById(R.id.resetButton);

        open.setOnClickListener(v->pick());
        analyze.setOnClickListener(v->analyze());
        settings.setOnClickListener(v->settings());
        translate.setOnClickListener(v->translate());
        export.setOnClickListener(v->saveOutput());
        reset.setOnClickListener(v->resetProgress());

        restoreWorkspace();
    }

    private void restoreWorkspace(){
        String path=prefsHolder.getString("activeWorkspace","");
        if(path.isEmpty())return;
        File ws=new File(path),src=new File(ws,"source.epub");
        if(!src.isFile())return;
        workspace=ws;selectedFile=src;
        File draft=new File(ws,"translated-current.epub");
        lastOutput=draft.isFile()?draft:null;
        analyze.setEnabled(true);translate.setEnabled(true);export.setEnabled(lastOutput!=null);
        TranslationStateStore store=new TranslationStateStore(ws);
        status.setText("📖 Workspace đã lưu\n"+src.getName()+"\n"+store.summary()+"\nCó thể bấm Dịch / Tiếp tục.");
        report.setText("Tiến độ dịch được lưu bền vững trong máy. Hết quota hoặc đóng app vẫn có thể tiếp tục.");
    }

    private void pick(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("application/epub+zip");i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,10);
    }

    @Override protected void onActivityResult(int r,int c,Intent d){
        super.onActivityResult(r,c,d);
        if(r==11&&c==RESULT_OK&&d!=null){
            try{
                if(lastOutput==null||!lastOutput.isFile())throw new IOException("Chưa có EPUB draft để xuất.");
                try(InputStream in=new FileInputStream(lastOutput);OutputStream out=getContentResolver().openOutputStream(d.getData())){
                    byte[] b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);
                }
                Toast.makeText(this,"Đã xuất EPUB",Toast.LENGTH_LONG).show();
            }catch(Exception e){showError(e);}
            return;
        }
        if(r==10&&c==RESULT_OK&&d!=null){
            try{
                Uri u=d.getData();
                File temp=new File(getCacheDir(),"picked.epub");
                try(InputStream in=getContentResolver().openInputStream(u);FileOutputStream out=new FileOutputStream(temp)){
                    byte[] b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);
                }
                String hash=TranslationStateStore.sha256(temp);
                workspace=new File(new File(getFilesDir(),"translation_workspaces"),hash);
                if(!workspace.exists()&&!workspace.mkdirs())throw new IOException("Không tạo được translation workspace.");
                selectedFile=new File(workspace,"source.epub");
                copyFile(temp,selectedFile);
                temp.delete();
                prefsHolder.edit().putString("activeWorkspace",workspace.getAbsolutePath()).apply();
                File draft=new File(workspace,"translated-current.epub");
                lastOutput=draft.isFile()?draft:null;
                analyze.setEnabled(true);translate.setEnabled(true);export.setEnabled(lastOutput!=null);
                status.setText("Đã chọn: "+u.getLastPathSegment()+"\nWorkspace: "+workspace.getName());
                report.setText("Bấm Phân tích EPUB để kiểm tra cấu trúc, hoặc Dịch / Tiếp tục để chạy translation queue.");
            }catch(Exception e){showError(e);}
        }
    }

    private void analyze(){
        if(selectedFile==null)return;
        progress.setVisibility(View.VISIBLE);progress.setIndeterminate(true);
        report.setText("Đang phân tích cấu trúc EPUB…");
        new Thread(()->{
            try{
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

    private List<TranslationRouter.Provider> fallbackProviders(){
        String selected=providerFor(prefsHolder.getString("endpoint",OPENROUTER_ENDPOINT));
        String[] order={selected,PROVIDERS[0],PROVIDERS[1],PROVIDERS[2],PROVIDERS[3],PROVIDERS[4],PROVIDERS[5]};
        List<TranslationRouter.Provider> out=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        for(String p:order){
            if(!seen.add(p))continue;
            TranslationConfig c=makeProviderConfig(p);
            if(c.endpoint!=null&&!c.endpoint.trim().isEmpty()&&c.model!=null&&!c.model.trim().isEmpty()&&c.apiKey!=null&&!c.apiKey.trim().isEmpty())
                out.add(new TranslationRouter.Provider(p,c));
        }
        return out;
    }

    private int indexOf(String[] a,String v){for(int i=0;i<a.length;i++)if(a[i].equals(v))return i;return -1;}
    private String[] modelsFor(String p){return p.equals(PROVIDERS[0])?OR_MODELS:p.equals(PROVIDERS[1])?GEMINI_MODELS:p.equals(PROVIDERS[2])?OPENAI_MODELS:p.equals(PROVIDERS[3])?DEEPSEEK_MODELS:p.equals(PROVIDERS[4])?MISTRAL_MODELS:new String[]{providerModel(PROVIDERS[5])};}
    private String[] labelsFor(String p){return p.equals(PROVIDERS[0])?OR_LABELS:p.equals(PROVIDERS[1])?GEMINI_LABELS:p.equals(PROVIDERS[2])?OPENAI_LABELS:p.equals(PROVIDERS[3])?DEEPSEEK_LABELS:p.equals(PROVIDERS[4])?MISTRAL_LABELS:new String[]{providerModel(PROVIDERS[5])};}

    private void settings(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(40,10,40,10);
        Spinner provider=new Spinner(this);provider.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,PROVIDERS));
        provider.setSelection(Math.max(0,indexOf(PROVIDERS,providerFor(prefsHolder.getString("endpoint",OPENROUTER_ENDPOINT)))));
        Spinner model=new Spinner(this);
        EditText customModel=new EditText(this);customModel.setHint("Model ID tùy chỉnh");customModel.setSingleLine(true);
        EditText ep=new EditText(this);ep.setHint("Endpoint");ep.setSingleLine(true);
        EditText key=new EditText(this);key.setHint("API key");key.setSingleLine(true);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView note=new TextView(this);note.setTextSize(12);note.setPadding(0,12,0,0);
        TextView fallbackNote=new TextView(this);fallbackNote.setTextSize(12);fallbackNote.setPadding(0,8,0,0);
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
            StringBuilder fb=new StringBuilder("Failover: ");List<TranslationRouter.Provider> ps=fallbackProviders();
            if(ps.isEmpty())fb.append("chưa cấu hình");else for(int i=0;i<ps.size();i++){if(i>0)fb.append(" → ");fb.append(ps.get(i).name);}
            fallbackNote.setText(fb.toString());
        };

        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> a,View v,int pos,long id){refresh.run();}public void onNothingSelected(AdapterView<?> a){}});
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

        box.addView(provider);box.addView(model);box.addView(customModel);box.addView(ep);box.addView(key);box.addView(keyLink);box.addView(test);box.addView(note);box.addView(fallbackNote);
        new AlertDialog.Builder(this).setTitle("Cấu hình AI + Failover").setView(box).setPositiveButton("Lưu",(d,w)->{
            String p=(String)provider.getSelectedItem(),selectedModel;
            if(p.equals(PROVIDERS[5]))selectedModel=customModel.getText().toString().trim();
            else{String[] ms=modelsFor(p);int pos=model.getSelectedItemPosition();selectedModel=ms[Math.max(0,Math.min(pos,ms.length-1))];}
            String enteredKey=key.getText().toString();
            android.content.SharedPreferences.Editor e=prefsHolder.edit();
            e.putString("apiKey",enteredKey).putString("endpoint",ep.getText().toString().trim()).putString("model",selectedModel);
            if(p.equals(PROVIDERS[0]))e.putString(PREF_OR_KEY,enteredKey).putString("model_openrouter",selectedModel);
            else if(p.equals(PROVIDERS[1]))e.putString(PREF_GEMINI_KEY,enteredKey).putString("model_gemini",selectedModel);
            else if(p.equals(PROVIDERS[2]))e.putString(PREF_OPENAI_KEY,enteredKey).putString("model_openai",selectedModel);
            else if(p.equals(PROVIDERS[3]))e.putString(PREF_DEEPSEEK_KEY,enteredKey).putString("model_deepseek",selectedModel);
            else if(p.equals(PROVIDERS[4]))e.putString(PREF_MISTRAL_KEY,enteredKey).putString("model_mistral",selectedModel);
            else e.putString(PREF_CUSTOM_KEY,enteredKey).putString("endpoint_custom",ep.getText().toString().trim()).putString("model_custom",selectedModel);
            e.apply();status.setText("AI: "+p+" / "+selectedModel+" | Failover: "+fallbackProviders().size()+" provider");
        }).setNegativeButton("Hủy",null).show();
    }

    private void translate(){
        if(translating)return;
        if(selectedFile==null){pick();return;}
        List<TranslationRouter.Provider> providers=fallbackProviders();
        if(providers.isEmpty()){settings();return;}
        if(workspace==null)workspace=new File(getFilesDir(),"translation_workspaces");
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

    private void resetProgress(){
        if(workspace==null)return;
        new AlertDialog.Builder(this).setTitle("Xóa tiến độ dịch?")
                .setMessage("Chỉ xóa translation units và draft hiện tại. EPUB nguồn vẫn được giữ nguyên.")
                .setPositiveButton("Xóa",(d,w)->{
                    File units=new File(workspace,"units"),manifest=new File(workspace,"progress.json"),draft=new File(workspace,"translated-current.epub"),finalFile=new File(workspace,"translated-final.epub");
                    deleteTree(units);manifest.delete();draft.delete();finalFile.delete();lastOutput=null;export.setEnabled(false);
                    report.setText("Đã xóa tiến độ. EPUB nguồn vẫn còn, có thể dịch lại từ đầu.");
                }).setNegativeButton("Hủy",null).show();
    }

    private void deleteTree(File f){
        if(f==null||!f.exists())return;
        if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)deleteTree(x);}
        f.delete();
    }

    private void saveOutput(){
        if(lastOutput==null||!lastOutput.isFile()){Toast.makeText(this,"Chưa có EPUB draft.",Toast.LENGTH_SHORT).show();return;}
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/epub+zip");i.putExtra(Intent.EXTRA_TITLE,lastOutput.getName());startActivityForResult(i,11);
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
