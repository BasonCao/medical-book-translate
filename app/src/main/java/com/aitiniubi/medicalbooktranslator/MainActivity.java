package com.aitiniubi.medicalbooktranslator;

import android.app.*;import android.content.*;import android.net.Uri;import android.os.*;import android.text.InputType;import android.view.*;import android.widget.*;
import com.aitiniubi.medicalbooktranslator.epub.*;import com.aitiniubi.medicalbooktranslator.translation.*;
import java.io.*;import java.util.*;

public class MainActivity extends Activity {
    private TextView status,report; private ProgressBar progress; private Button analyze,translate,export; private File selectedFile,lastOutput; private EpubBook book;
    private android.content.SharedPreferences prefsHolder;
    @Override public void onCreate(Bundle b){super.onCreate(b);setContentView(com.aitiniubi.medicalbooktranslator.R.layout.activity_main);prefsHolder=getSharedPreferences("config",MODE_PRIVATE);
        status=findViewById(R.id.status);report=findViewById(R.id.report);progress=findViewById(R.id.progress);Button open=findViewById(R.id.openButton);analyze=findViewById(R.id.analyzeButton);translate=findViewById(R.id.translateButton);Button settings=findViewById(R.id.settingsButton);export=findViewById(R.id.exportButton);
        open.setOnClickListener(v->pick());analyze.setOnClickListener(v->analyze());settings.setOnClickListener(v->settings());translate.setOnClickListener(v->translate());export.setOnClickListener(v->saveOutput());
    }
    private void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/epub+zip");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);}
    @Override protected void onActivityResult(int r,int c, Intent d){super.onActivityResult(r,c,d);if(r==11&&c==RESULT_OK&&d!=null){try{try(InputStream in=new FileInputStream(lastOutput);OutputStream out=getContentResolver().openOutputStream(d.getData())){byte[]b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);}Toast.makeText(this,"Đã xuất EPUB",Toast.LENGTH_LONG).show();}catch(Exception e){showError(e);}return;}if(r==10&&c==RESULT_OK&&d!=null){try{Uri u=d.getData();selectedFile=new File(getCacheDir(),"input.epub");try(InputStream in=getContentResolver().openInputStream(u);FileOutputStream out=new FileOutputStream(selectedFile)){byte[]b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);}status.setText("Đã chọn: "+u.getLastPathSegment());analyze.setEnabled(true);translate.setEnabled(true);}catch(Exception e){showError(e);}}}
    private void analyze(){if(selectedFile==null)return;progress.setVisibility(View.VISIBLE);progress.setIndeterminate(true);report.setText("Đang phân tích cấu trúc EPUB…");new Thread(()->{try{book=EpubAnalyzer.analyze(selectedFile);runOnUiThread(()->{progress.setVisibility(View.GONE);progress.setIndeterminate(false);report.setText("EPUB: "+book.title+"\nFiles: "+book.totalFiles+"\nXHTML: "+book.xhtmlFiles.size()+"\nĐoạn văn: "+book.paragraphCount+"\nHình/ảnh tham chiếu: "+book.imageReferenceCount+"\nFigure: "+book.figureCount+"\nBảng: "+book.tableCount+"\n\nCấu trúc gốc sẽ được giữ nguyên khi rebuild.");});}catch(Exception e){runOnUiThread(()->showError(e));}}).start();}
    private TranslationConfig config(){TranslationConfig c=new TranslationConfig();c.endpoint=prefsHolder.getString("endpoint",OPENROUTER_ENDPOINT);c.apiKey=prefsHolder.getString("apiKey","");c.model=prefsHolder.getString("model","openrouter/free");return c;}
    private int indexOf(String[] a,String value){for(int i=0;i<a.length;i++)if(a[i].equals(value))return i;return -1;}
    private String providerFor(String endpoint){if(endpoint!=null&&endpoint.contains("openrouter.ai"))return PROVIDERS[0];if(endpoint!=null&&endpoint.contains("generativelanguage.googleapis.com"))return PROVIDERS[1];if(endpoint!=null&&endpoint.contains("api.openai.com"))return PROVIDERS[2];return PROVIDERS[3];}
    private void settings(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(40,10,40,10);
        Spinner provider=new Spinner(this);provider.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,PROVIDERS));
        String savedEndpoint=prefsHolder.getString("endpoint",OPENROUTER_ENDPOINT),savedModel=prefsHolder.getString("model","openrouter/free");
        provider.setSelection(Math.max(0,indexOf(PROVIDERS,providerFor(savedEndpoint))));
        Spinner model=new Spinner(this);EditText customModel=new EditText(this);customModel.setHint("Model ID tùy chỉnh");customModel.setSingleLine(true);
        EditText ep=new EditText(this);ep.setHint("Endpoint");ep.setSingleLine(true);ep.setText(savedEndpoint);
        EditText key=new EditText(this);key.setHint("API key");key.setSingleLine(true);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setText(prefsHolder.getString("apiKey",""));
        TextView note=new TextView(this);note.setTextSize(12);note.setPadding(0,12,0,0);
        final boolean[] first={true};
        Runnable refresh=()->{String p=(String)provider.getSelectedItem();String[] ms;String[] labels;
            if(p.equals(PROVIDERS[0])){ms=OR_MODELS;labels=OR_LABELS;ep.setText(OPENROUTER_ENDPOINT);customModel.setVisibility(View.GONE);note.setText("OpenRouter: model FREE ($0) nhưng quota/độ sẵn sàng có thể thay đổi.");}
            else if(p.equals(PROVIDERS[1])){ms=GEMINI_MODELS;labels=GEMINI_LABELS;ep.setText(GEMINI_ENDPOINT);customModel.setVisibility(View.GONE);note.setText("Gemini: các model trong danh sách có Free Tier theo tài liệu Google hiện tại.");}
            else if(p.equals(PROVIDERS[2])){ms=OPENAI_MODELS;labels=OPENAI_MODELS;ep.setText(OPENAI_ENDPOINT);customModel.setVisibility(View.GONE);note.setText("OpenAI API cần credit/billing. HTTP 429 có thể xảy ra khi tài khoản hết credit.");}
            else {ms=new String[]{savedModel};labels=ms;ep.setText(savedEndpoint);customModel.setText(savedModel);customModel.setVisibility(View.VISIBLE);note.setText("Nhập endpoint và model của provider OpenAI-compatible bất kỳ.");}
            model.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));
            int sel=indexOf(ms,savedModel);if(sel<0)sel=0;model.setSelection(sel);model.setVisibility(p.equals(PROVIDERS[3])?View.GONE:View.VISIBLE);
            if(!first[0]){if(p.equals(PROVIDERS[0]))savedModel="openrouter/free";else if(p.equals(PROVIDERS[1]))savedModel=GEMINI_MODELS[0];else if(p.equals(PROVIDERS[2]))savedModel=OPENAI_MODELS[0];}
            first[0]=false;
        };
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> a,View v,int pos,long id){refresh.run();}public void onNothingSelected(AdapterView<?> a){}});
        refresh.run();
        Button test=new Button(this);test.setText("KIỂM TRA KẾT NỐI");
        test.setOnClickListener(v->{String p=(String)provider.getSelectedItem();String selectedModel;
            if(p.equals(PROVIDERS[3]))selectedModel=customModel.getText().toString().trim();else{String[] ms=p.equals(PROVIDERS[0])?OR_MODELS:p.equals(PROVIDERS[1])?GEMINI_MODELS:OPENAI_MODELS;int pos=model.getSelectedItemPosition();selectedModel=ms[Math.max(0,Math.min(pos,ms.length-1))];}
            TranslationConfig tc=new TranslationConfig();tc.endpoint=ep.getText().toString().trim();tc.model=selectedModel;tc.apiKey=key.getText().toString();test.setEnabled(false);
            new Thread(()->{try{String out=OpenAICompatibleTranslator.translate("Translate only: fetal ultrasound","Medical translation connection test.",tc);runOnUiThread(()->{test.setEnabled(true);new AlertDialog.Builder(this).setTitle("Kết nối thành công").setMessage("Model: "+tc.model+"\\n\\n"+out).setPositiveButton("OK",null).show();});}catch(Exception ex){runOnUiThread(()->{test.setEnabled(true);showError(ex);});}}).start();
        });
        box.addView(provider);box.addView(model);box.addView(customModel);box.addView(ep);box.addView(key);box.addView(test);box.addView(note);
        new AlertDialog.Builder(this).setTitle("Cấu hình AI").setView(box).setPositiveButton("Lưu",(d,w)->{String p=(String)provider.getSelectedItem();String selectedModel;
            if(p.equals(PROVIDERS[3]))selectedModel=customModel.getText().toString().trim();else{String[] ms=p.equals(PROVIDERS[0])?OR_MODELS:p.equals(PROVIDERS[1])?GEMINI_MODELS:OPENAI_MODELS;int pos=model.getSelectedItemPosition();selectedModel=ms[Math.max(0,Math.min(pos,ms.length-1))];}
            prefsHolder.edit().putString("endpoint",ep.getText().toString().trim()).putString("model",selectedModel).putString("apiKey",key.getText().toString()).apply();status.setText("AI: "+p+" / "+selectedModel);}).setNegativeButton("Hủy",null).show();
    }
    private void translate(){TranslationConfig c=config();if(c.endpoint.isEmpty()||c.model.isEmpty()){settings();return;}File out=new File(getExternalFilesDir(null),"translated_"+System.currentTimeMillis()+".epub");progress.setVisibility(View.VISIBLE);progress.setIndeterminate(false);progress.setMax(100);report.setText("Đang dịch…\nBản gốc vẫn được giữ nguyên.");translate.setEnabled(false);TranslationJob.run(selectedFile,out,c,new TranslationJob.Listener(){public void onProgress(int d,int t){int p=(int)(100.0*d/t);runOnUiThread(()->progress.setProgress(p));}public void onDone(File f){lastOutput=f;runOnUiThread(()->{progress.setProgress(100);translate.setEnabled(true);export.setEnabled(true);report.append("\n\n✓ Đã tạo EPUB: "+f.getAbsolutePath());});}public void onError(Exception e){runOnUiThread(()->{translate.setEnabled(true);showError(e);});}});}
    private void saveOutput(){if(lastOutput==null)return;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/epub+zip");i.putExtra(Intent.EXTRA_TITLE,lastOutput.getName());startActivityForResult(i,11);}
    private void showError(Exception e){progress.setVisibility(View.GONE);new AlertDialog.Builder(this).setTitle("Lỗi").setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("OK",null).show();}
}
