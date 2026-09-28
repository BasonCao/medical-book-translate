package com.aitiniubi.medicalbooktranslator;

import android.app.*;import android.content.*;import android.net.Uri;import android.os.*;import android.text.InputType;import android.view.*;import android.widget.*;import android.text.TextUtils;
import com.aitiniubi.medicalbooktranslator.epub.*;import com.aitiniubi.medicalbooktranslator.translation.*;
import java.io.*;import java.util.*;

public class MainActivity extends Activity {
    private static final String OPENROUTER_ENDPOINT="https://openrouter.ai/api/v1/chat/completions";
    private static final String DEFAULT_OR_MODEL="inclusionai/ling-3.0-flash-sante:free";
    private static final String GEMINI_ENDPOINT="https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
    private static final String OPENAI_ENDPOINT="https://api.openai.com/v1/responses";
    private static final String[] PROVIDERS={"OpenRouter — FREE","Google Gemini — FREE tier","OpenAI","Custom OpenAI-compatible"};
    private static final String[] OR_MODELS={"openrouter/free","inclusionai/ling-3.0-flash-sante:free","nvidia/nemotron-3-ultra:free","qwen/qwen3.8-27b:free","google/gemma-4-31b-it:free","google/gemma-4-26b-a4b-it:free","inclusionai/ling-3.0-flash-fin:free"};
    private static final String[] OR_LABELS={"Auto Free Router","Ling 3.0 Flash Sante — Medical","NVIDIA Nemotron 3 Ultra — Free","Qwen 3.8 27B — Free","Gemma 4 31B — Free","Gemma 4 26B A4B — Free","Ling 3.0 Flash Fin — Free"};
    private static final String[] GEMINI_MODELS={"gemini-3.8-flash","gemini-3.7-flash","gemini-3.6-flash","gemini-3.1-flash-lite"};
    private static final String[] GEMINI_LABELS={"Gemini 3.8 Flash — Free tier","Gemini 3.7 Flash — Free tier","Gemini 3.6 Flash — Free tier","Gemini 3.1 Flash-Lite — Free tier"};
    private static final String[] OPENAI_MODELS={"gpt-5.6-luna","gpt-5.6-terra","gpt-5.6-sol","gpt-5.6"};
    private static final String PREF_OR_KEY="apiKey_openrouter";
    private static final String PREF_GEMINI_KEY="apiKey_gemini";
    private static final String PREF_OPENAI_KEY="apiKey_openai";
    private static final String PREF_CUSTOM_KEY="apiKey_custom";

    private TextView status,report; private ProgressBar progress; private Button analyze,translate,export; private File selectedFile,lastOutput; private EpubBook book;
    private android.content.SharedPreferences prefsHolder;
    @Override public void onCreate(Bundle b){super.onCreate(b);setContentView(com.aitiniubi.medicalbooktranslator.R.layout.activity_main);prefsHolder=getSharedPreferences("config",MODE_PRIVATE);
        status=findViewById(R.id.status);report=findViewById(R.id.report);progress=findViewById(R.id.progress);Button open=findViewById(R.id.openButton);analyze=findViewById(R.id.analyzeButton);translate=findViewById(R.id.translateButton);Button settings=findViewById(R.id.settingsButton);export=findViewById(R.id.exportButton);
        open.setOnClickListener(v->pick());analyze.setOnClickListener(v->analyze());settings.setOnClickListener(v->settings());translate.setOnClickListener(v->translate());export.setOnClickListener(v->saveOutput());
    }
    private void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/epub+zip");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);}
    @Override protected void onActivityResult(int r,int c, Intent d){super.onActivityResult(r,c,d);if(r==11&&c==RESULT_OK&&d!=null){try{try(InputStream in=new FileInputStream(lastOutput);OutputStream out=getContentResolver().openOutputStream(d.getData())){byte[]b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);}Toast.makeText(this,"Đã xuất EPUB",Toast.LENGTH_LONG).show();}catch(Exception e){showError(e);}return;}if(r==10&&c==RESULT_OK&&d!=null){try{Uri u=d.getData();selectedFile=new File(getCacheDir(),"input.epub");try(InputStream in=getContentResolver().openInputStream(u);FileOutputStream out=new FileOutputStream(selectedFile)){byte[]b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);}status.setText("Đã chọn: "+u.getLastPathSegment());analyze.setEnabled(true);translate.setEnabled(true);}catch(Exception e){showError(e);}}}
    private void analyze(){if(selectedFile==null)return;progress.setVisibility(View.VISIBLE);progress.setIndeterminate(true);report.setText("Đang phân tích cấu trúc EPUB…");new Thread(()->{try{book=EpubAnalyzer.analyze(selectedFile);runOnUiThread(()->{progress.setVisibility(View.GONE);progress.setIndeterminate(false);report.setText("EPUB: "+book.title+"\nFiles: "+book.totalFiles+"\nXHTML: "+book.xhtmlFiles.size()+"\nĐoạn văn: "+book.paragraphCount+"\nHình/ảnh tham chiếu: "+book.imageReferenceCount+"\nFigure: "+book.figureCount+"\nBảng: "+book.tableCount+"\n\nCấu trúc gốc sẽ được giữ nguyên khi rebuild.");});}catch(Exception e){runOnUiThread(()->showError(e));}}).start();}
    private TranslationConfig config(){
        TranslationConfig c=new TranslationConfig();
        c.endpoint=prefsHolder.getString("endpoint",OPENROUTER_ENDPOINT);
        c.apiKey=prefsHolder.getString("apiKey",legacyKeyForEndpoint(c.endpoint));
        String savedModel=prefsHolder.getString("model",DEFAULT_OR_MODEL);
        c.model="openrouter/free".equals(savedModel)?DEFAULT_OR_MODEL:savedModel;
        return c;
    }

    private String legacyKeyForEndpoint(String endpoint){
        if(endpoint!=null&&endpoint.contains("openrouter.ai")) return prefsHolder.getString(PREF_OR_KEY,"");
        if(endpoint!=null&&endpoint.contains("generativelanguage.googleapis.com")) return prefsHolder.getString(PREF_GEMINI_KEY,"");
        if(endpoint!=null&&endpoint.contains("api.openai.com")) return prefsHolder.getString(PREF_OPENAI_KEY,"");
        return prefsHolder.getString(PREF_CUSTOM_KEY,"");
    }

    private String providerKey(String provider){
        if(provider.equals(PROVIDERS[0])) return prefsHolder.getString(PREF_OR_KEY,"");
        if(provider.equals(PROVIDERS[1])) return prefsHolder.getString(PREF_GEMINI_KEY,"");
        if(provider.equals(PROVIDERS[2])) return prefsHolder.getString(PREF_OPENAI_KEY,"");
        return prefsHolder.getString(PREF_CUSTOM_KEY,"");
    }

    private String providerModel(String provider){
        if(provider.equals(PROVIDERS[0])){
            String m=prefsHolder.getString("model_openrouter",DEFAULT_OR_MODEL);
            return "openrouter/free".equals(m)?DEFAULT_OR_MODEL:m;
        }
        if(provider.equals(PROVIDERS[1])) return prefsHolder.getString("model_gemini",GEMINI_MODELS[0]);
        if(provider.equals(PROVIDERS[2])) return prefsHolder.getString("model_openai",OPENAI_MODELS[0]);
        return prefsHolder.getString("model_custom","");
    }

    private TranslationConfig makeProviderConfig(String provider){
        TranslationConfig c=new TranslationConfig();
        if(provider.equals(PROVIDERS[0])) c.endpoint=OPENROUTER_ENDPOINT;
        else if(provider.equals(PROVIDERS[1])) c.endpoint=GEMINI_ENDPOINT;
        else if(provider.equals(PROVIDERS[2])) c.endpoint=OPENAI_ENDPOINT;
        else c.endpoint=prefsHolder.getString("endpoint_custom","");
        c.model=providerModel(provider);
        c.apiKey=providerKey(provider);
        return c;
    }

    private List<TranslationRouter.Provider> fallbackProviders(){
        String selected=providerFor(prefsHolder.getString("endpoint",OPENROUTER_ENDPOINT));
        String[] order={selected,PROVIDERS[0],PROVIDERS[1],PROVIDERS[2],PROVIDERS[3]};
        List<TranslationRouter.Provider> out=new ArrayList<>();
        HashSet<String> seen=new HashSet<>();
        for(String p:order){
            if(!seen.add(p)) continue;
            TranslationConfig c=makeProviderConfig(p);
            if(c.endpoint!=null&&!c.endpoint.trim().isEmpty()&&c.model!=null&&!c.model.trim().isEmpty()&&c.apiKey!=null&&!c.apiKey.trim().isEmpty()){
                out.add(new TranslationRouter.Provider(p,c));
            }
        }
        return out;
    }

    private int indexOf(String[] a,String value){for(int i=0;i<a.length;i++)if(a[i].equals(value))return i;return -1;}

    private String[] modelsFor(String p){return p.equals(PROVIDERS[0])?OR_MODELS:p.equals(PROVIDERS[1])?GEMINI_MODELS:p.equals(PROVIDERS[2])?OPENAI_MODELS:new String[]{providerModel(PROVIDERS[3])};}

    private String[] labelsFor(String p){return p.equals(PROVIDERS[0])?OR_LABELS:p.equals(PROVIDERS[1])?GEMINI_LABELS:OPENAI_MODELS;}

    private void settings(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(40,10,40,10);
        Spinner provider=new Spinner(this);provider.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,PROVIDERS));
        String savedEndpoint=prefsHolder.getString("endpoint",OPENROUTER_ENDPOINT);
        provider.setSelection(Math.max(0,indexOf(PROVIDERS,providerFor(savedEndpoint))));
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
            String[] ms=modelsFor(p);
            String[] labels=labelsFor(p);
            if(p.equals(PROVIDERS[0])){
                ep.setText(OPENROUTER_ENDPOINT);customModel.setVisibility(View.GONE);
                note.setText("OpenRouter FREE: khi hết quota/ngày app sẽ tự chuyển sang provider tiếp theo nếu đã cấu hình.");
            } else if(p.equals(PROVIDERS[1])){
                ep.setText(GEMINI_ENDPOINT);customModel.setVisibility(View.GONE);
                note.setText("Gemini API tương thích OpenAI; model hiện tại lấy từ tài liệu Google.");
            } else if(p.equals(PROVIDERS[2])){
                ep.setText(OPENAI_ENDPOINT);customModel.setVisibility(View.GONE);
                note.setText("OpenAI API có thể yêu cầu billing/credit.");
            } else {
                ep.setText(prefsHolder.getString("endpoint_custom",""));
                customModel.setText(providerModel(PROVIDERS[3]));customModel.setVisibility(View.VISIBLE);
                note.setText("Provider tùy chỉnh: nhập endpoint, model và API key.");
            }
            String saved=providerModel(p);
            model.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));
            int sel=indexOf(ms,saved);if(sel<0)sel=0;model.setSelection(sel);
            model.setVisibility(p.equals(PROVIDERS[3])?View.GONE:View.VISIBLE);
            key.setText(providerKey(p));
            if(p.equals(PROVIDERS[0])){
                keyLink.setText("🔑 Lấy OpenRouter API key");keyLink.setVisibility(View.VISIBLE);
                keyLink.setOnClickListener(v->openUrl("https://openrouter.ai/settings/keys"));
            } else if(p.equals(PROVIDERS[1])){
                keyLink.setText("🔑 Lấy Gemini API key (Google AI Studio)");keyLink.setVisibility(View.VISIBLE);
                keyLink.setOnClickListener(v->openUrl("https://aistudio.google.com/apikey"));
            } else if(p.equals(PROVIDERS[2])){
                keyLink.setText("🔑 Lấy OpenAI API key");keyLink.setVisibility(View.VISIBLE);
                keyLink.setOnClickListener(v->openUrl("https://platform.openai.com/api-keys"));
            } else {
                keyLink.setText("ℹ️ Provider tùy chỉnh");keyLink.setVisibility(View.VISIBLE);keyLink.setOnClickListener(v->{});
            }
            StringBuilder fb=new StringBuilder("Failover hiện có: ");
            List<TranslationRouter.Provider> ps=fallbackProviders();
            if(ps.isEmpty()) fb.append("chưa có provider dự phòng.");
            else for(int i=0;i<ps.size();i++){if(i>0)fb.append(" → ");fb.append(ps.get(i).name);}
            fallbackNote.setText(fb.toString());
        };
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> a,View v,int pos,long id){refresh.run();}public void onNothingSelected(AdapterView<?> a){}});
        model.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> a,View v,int pos,long id){}public void onNothingSelected(AdapterView<?> a){}});

        Button test=new Button(this);test.setText("KIỂM TRA PROVIDER NÀY");
        test.setOnClickListener(v->{
            String p=(String)provider.getSelectedItem();
            String selectedModel;
            if(p.equals(PROVIDERS[3])) selectedModel=customModel.getText().toString().trim();
            else {String[] ms=modelsFor(p);int pos=model.getSelectedItemPosition();selectedModel=ms[Math.max(0,Math.min(pos,ms.length-1))];}
            TranslationConfig tc=new TranslationConfig();tc.endpoint=ep.getText().toString().trim();tc.model=selectedModel;tc.apiKey=key.getText().toString();test.setEnabled(false);
            new Thread(()->{try{
                String out=OpenAICompatibleTranslator.translate("Translate only: fetal ultrasound","Medical translation connection test.",tc);
                runOnUiThread(()->{test.setEnabled(true);new AlertDialog.Builder(this).setTitle("Kết nối thành công").setMessage("Provider: "+p+"\nModel: "+tc.model+"\n\n"+out).setPositiveButton("OK",null).show();});
            }catch(Exception ex){runOnUiThread(()->{test.setEnabled(true);showError(ex);});}}).start();
        });

        box.addView(provider);box.addView(model);box.addView(customModel);box.addView(ep);box.addView(key);box.addView(keyLink);box.addView(test);box.addView(note);box.addView(fallbackNote);
        new AlertDialog.Builder(this).setTitle("Cấu hình AI + Failover").setView(box).setPositiveButton("Lưu",(d,w)->{
            String p=(String)provider.getSelectedItem();
            String selectedModel;
            if(p.equals(PROVIDERS[3])) selectedModel=customModel.getText().toString().trim();
            else {String[] ms=modelsFor(p);int pos=model.getSelectedItemPosition();selectedModel=ms[Math.max(0,Math.min(pos,ms.length-1))];}
            String enteredKey=key.getText().toString();
            android.content.SharedPreferences.Editor e=prefsHolder.edit();
            e.putString("apiKey",enteredKey).putString("endpoint",ep.getText().toString().trim()).putString("model",selectedModel);
            if(p.equals(PROVIDERS[0])) e.putString(PREF_OR_KEY,enteredKey).putString("model_openrouter",selectedModel);
            else if(p.equals(PROVIDERS[1])) e.putString(PREF_GEMINI_KEY,enteredKey).putString("model_gemini",selectedModel);
            else if(p.equals(PROVIDERS[2])) e.putString(PREF_OPENAI_KEY,enteredKey).putString("model_openai",selectedModel);
            else e.putString(PREF_CUSTOM_KEY,enteredKey).putString("endpoint_custom",ep.getText().toString().trim()).putString("model_custom",selectedModel);
            e.apply();
            status.setText("AI: "+p+" / "+selectedModel+" | Failover: "+fallbackProviders().size()+" provider");
        }).setNegativeButton("Hủy",null).show();
    }

    private void openUrl(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){showError(e);}}
    private void translate(){List<TranslationRouter.Provider> providers=fallbackProviders();if(providers.isEmpty()){settings();return;}File out=new File(getExternalFilesDir(null),"translated_"+System.currentTimeMillis()+".epub");progress.setVisibility(View.VISIBLE);progress.setIndeterminate(false);progress.setMax(100);report.setText("Đang dịch…\nFailover: "+providers.size()+" provider\nBản gốc vẫn được giữ nguyên.");translate.setEnabled(false);TranslationJob.run(selectedFile,out,providers,new TranslationJob.Listener(){public void onProgress(int d,int t){int p=(int)(100.0*d/t);runOnUiThread(()->progress.setProgress(p));}public void onDone(File f){lastOutput=f;runOnUiThread(()->{progress.setProgress(100);translate.setEnabled(true);export.setEnabled(true);report.append("\n\n✓ Đã tạo EPUB: "+f.getAbsolutePath());});}public void onError(Exception e){runOnUiThread(()->{translate.setEnabled(true);showError(e);});}});}
    private void saveOutput(){if(lastOutput==null)return;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/epub+zip");i.putExtra(Intent.EXTRA_TITLE,lastOutput.getName());startActivityForResult(i,11);}
    private void showError(Exception e){progress.setVisibility(View.GONE);new AlertDialog.Builder(this).setTitle("Lỗi").setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("OK",null).show();}
}
