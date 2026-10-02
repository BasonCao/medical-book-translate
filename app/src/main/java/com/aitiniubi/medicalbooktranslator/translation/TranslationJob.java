package com.aitiniubi.medicalbooktranslator.translation;

import com.aitiniubi.medicalbooktranslator.epub.EpubBuilder;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

public final class TranslationJob {
    public interface Listener {
        void onProgress(int done,int total,int batch,String info);
        void onDone(File output);
        void onPaused(File draft,int done,int total,Exception reason);
        void onError(Exception e);
    }

    private static final Pattern BLOCK=Pattern.compile(
        "<(p|h1|h2|h3|h4|h5|h6|figcaption|caption|th|td|li|blockquote|dt|dd|pre|address)\\b([^>]*)>(.*?)</\\1>",
        Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern CONTAINER_BLOCK=Pattern.compile(
        "<(div|section|article|aside)\\b([^>]*)>(.*?)</\\1>",
        Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern ANY_BLOCK_TAG=Pattern.compile(
        "<(?:p|h[1-6]|figcaption|caption|th|td|li|blockquote|dt|dd|pre|address|div|section|article|aside)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final int MAX_BATCH_UNITS=10;
    private static final int MAX_BATCH_CHARS=16000;
    private static final Pattern MARKUP_TOKEN=Pattern.compile("<!--.*?-->|<[^>]+>|&(?:#[0-9]+|#x[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]+);",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);

    private TranslationJob(){}

    public static void run(File source,File output,File workspace,
                           List<TranslationRouter.Provider> providers,Listener listener){
        new Thread(()->{
            try{
                if(source==null||!source.isFile())throw new IOException("Không tìm thấy EPUB nguồn.");
                if(providers==null||providers.isEmpty())throw new IOException("Chưa cấu hình AI provider nào có API key.");

                List<Unit> units=extractUnits(source);
                String sourceHash=TranslationStateStore.sha256(source);
                int total=0;
                for(Unit u:units) if(u.translatable) total++;
                TranslationStateStore store=new TranslationStateStore(workspace);
                store.initialize(sourceHash,source.getName(),total);
                TranslationLogger logger=new TranslationLogger(workspace);
                TranslationLogger.bind(logger);
                logger.start(source.getName(), sourceHash, total);
                logger.event("QUEUE", "translatable=" + total);

                Map<String,String> doneMap=new LinkedHashMap<>();
                int done=0;
                for(Unit u:units){
                    TranslationStateStore.Record r=store.get(u.id,u.sourceHash);
                    if(r!=null&&!blank(r.translation)&&looksComplete(u,r.translation)){
                        doneMap.put(u.id,r.translation);done++;
                    }
                }
                listener.onProgress(done,total,0,"Khôi phục draft: "+done+"/"+total);
                logger.event("RECOVER", "done=" + done + " pending=" + (total-done));

                List<Unit> pending=new ArrayList<>();
                for(Unit u:units)if(!doneMap.containsKey(u.id)&&u.translatable)pending.add(u);

                if(pending.isEmpty()){
                    File draft=new File(workspace,"translated-current.epub");
                    rebuild(source,draft,units,doneMap);copyFile(draft,output);listener.onDone(output);return;
                }

                final List<GlossaryManager.Term> glossary=GlossaryManager.load(workspace);
                final int parallelism=4;
                java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(parallelism);
                java.util.concurrent.CompletionService<BatchResult> completion=new java.util.concurrent.ExecutorCompletionService<>(pool);
                int next=0,submitted=0,completedBatches=0;
                while(next<pending.size() && submitted<parallelism){
                    List<Unit> batch=makeBatch(pending,next); next+=batch.size(); submitted++;
                    logger.event("BATCH_SUBMIT", "batch=" + submitted + " units=" + batch.size() + " chars=" + batchChars(batch));
                    completion.submit(()->translateOneBatchSafe(batch,providers,glossary));
                }
                try{
                    while(completedBatches<submitted){
                        BatchResult br;
                        try{
                            br=completion.take().get();
                        }catch(Exception batchError){
                            // A failed batch must not abort the entire book. Recover
                            // each unit independently below; failed units remain source-only.
                            completedBatches++;
                            continue;
                        }
                        completedBatches++;
                        Map<String,String> got=br.translations;
                        List<Unit> batch=br.batch;
                        int batchNo=completedBatches;
                        // A provider quota/rate-limit failure is a batch-level failure.
                        // Do NOT launch one recovery request per unit: that only burns
                        // more quota and makes the book appear to hang.
                        if(br.failureReason!=null && isProviderUnavailable(br.failureReason)){
                            for(Unit u:batch){
                                try{
                                    store.put(new TranslationStateStore.Record(
                                            u.id,u.file,u.sourceHash,"","SOURCE_ONLY",1));
                                }catch(Exception ignored){}
                                logger.event("UNIT_SKIP","unit="+shortId(u.id)+" file="+u.file
                                        +" reason=provider unavailable: "+safeLog(br.failureReason));
                            }
                            logger.event("BATCH_SKIP","batch="+batchNo+" units="+batch.size()
                                    +" reason=provider unavailable; no per-unit retry");
                            store.saveManifest(sourceHash,source.getName(),total,done);
                            if(next<pending.size()){
                                List<Unit> nextBatch=makeBatch(pending,next);next+=nextBatch.size();submitted++;
                                completion.submit(()->translateOneBatchSafe(nextBatch,providers,glossary));
                            }
                            continue;
                        }
                        for(Unit u:batch){
                            String t=got.get(u.id);
                            try{
                                t=translateValidatedUnit(u,t,br.context,providers);
                                store.put(new TranslationStateStore.Record(u.id,u.file,u.sourceHash,t));
                                doneMap.put(u.id,t);done++;
                                listener.onProgress(done,total,batchNo,"Đã lưu batch "+batchNo);
                            }catch(Exception unitError){
                                // Never stop the whole EPUB because one short heading,
                                // caption, or transient AI response failed. Do NOT store an
                                // empty translation. rebuild() will keep the original source
                                // HTML for this unit, so no source content can be lost.
                                try{
                                    store.put(new TranslationStateStore.Record(
                                            u.id,u.file,u.sourceHash,"","SOURCE_ONLY",1));
                                }catch(Exception ignored){}
                                logger.event("UNIT_SKIP","unit="+shortId(u.id)+" file="+u.file
                                        +" reason="+safeLog(unitError.getMessage()));
                                listener.onProgress(done,total,batchNo,
                                        "SOURCE_ONLY unit "+u.id.substring(0,Math.min(12,u.id.length()))
                                        +" — bỏ qua, ghi log; sẽ retry ở lần Tiếp tục. "
                                        +unitError.getMessage());
                            }
                        }
                        store.saveManifest(sourceHash,source.getName(),total,done);
                        if(next<pending.size()){
                            List<Unit> nextBatch=makeBatch(pending,next);next+=nextBatch.size();submitted++;
                            completion.submit(()->translateOneBatchSafe(nextBatch,providers,glossary));
                        }
                    }
                }catch(Exception e){
                    store.saveManifest(sourceHash,source.getName(),total,done);
                    File draft=new File(workspace,"translated-current.epub");
                    rebuild(source,draft,units,doneMap);
                    listener.onPaused(draft,done,total,e);return;
                }finally{pool.shutdownNow();}

                logger.event("REBUILD", "starting final rebuild translated-current.epub with done=" + done + "/" + total);
                File finalDraft=new File(workspace,"translated-current.epub");
                rebuild(source,finalDraft,units,doneMap);copyFile(finalDraft,output);
                store.saveManifest(sourceHash,source.getName(),units.size(),done);
                listener.onDone(output);
            }catch(Exception e){logger.error("JOB_ERROR",e);listener.onError(e);}finally{TranslationLogger.unbind();}
        },"epub-translation").start();
    }

    private static List<Unit> extractUnits(File source)throws Exception{
        List<Unit> out=new ArrayList<>();
        try(ZipFile zip=new ZipFile(source)){
            Enumeration<? extends ZipEntry> en=zip.entries();
            while(en.hasMoreElements()){
                ZipEntry e=en.nextElement();String name=e.getName();
                String lowerName=name.toLowerCase(Locale.US);
                if(!(lowerName.endsWith(".xhtml")||lowerName.endsWith(".html")||lowerName.endsWith(".htm")))continue;
                String x=read(zip,e);
                List<Candidate> candidates=new ArrayList<>();
                Matcher m=BLOCK.matcher(x);
                while(m.find()) candidates.add(new Candidate(m.start(),m.end(),m.group(1),m.group(2),m.group(3)));

                // Add leaf containers that contain prose directly. Never add a
                // container when it contains another block element, so paragraphs
                // are not swallowed or translated twice.
                Matcher cm=CONTAINER_BLOCK.matcher(x);
                while(cm.find()){
                    Matcher child=ANY_BLOCK_TAG.matcher(cm.group(3));
                    if(!child.find()) candidates.add(new Candidate(cm.start(),cm.end(),cm.group(1),cm.group(2),cm.group(3)));
                }

                candidates.sort(Comparator.comparingInt(a->a.start));
                int ordinal=0;
                for(Candidate q:candidates){
                    String plain=strip(q.inner);boolean tr=shouldTranslate(q.tag,q.attrs,plain);
                    String sh=TranslationStateStore.sha256(q.inner);
                    String id=TranslationStateStore.sha256(name+"|"+ordinal+"|"+sh);
                    out.add(new Unit(id,name,ordinal,q.tag,q.attrs,q.inner,sh,tr));ordinal++;
                }
            }
        }
        return out;
    }

    private static boolean shouldTranslate(String tag,String attrs,String plain){
        if(plain.length()<2||!plain.matches("(?s).*\\p{L}.*"))return false;
        String a=attrs==null?"":attrs.toLowerCase(Locale.US);
        if(a.matches(".*\\b(ref|ref1|reflist|references|bibliography|bib|doi|url|tsource|tsource1|figcredit|fignum)\\b.*"))return false;
        String compact=plain.replaceAll("[^A-Za-z0-9-]","");
        String lower=plain.trim().toLowerCase(Locale.US);
        boolean shortHeading=lower.equals("definition")
                ||lower.equals("feature")
                ||lower.equals("renal")
                ||lower.equals("seizure")
                ||lower.equals("prenatal")
                ||lower.equals("classic signs")
                ||lower.equals("key points")
                ||lower.equals("references")
                ||lower.matches("table\\s+129\\.[0-9]+");
        if(compact.length()<=10&&compact.matches("[A-Za-z][A-Za-z0-9-]+")
                &&!shortHeading)return false;
        boolean vi=plain.matches("(?s).*?[ÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠàáâãèéêìíòóôõùúăđĩũơƯưẠ-ỹ].*");
        if(vi&&englishWords(plain)<Math.max(3,vietnameseWords(plain)*2))return false;
        return !plain.matches("[\\s\\d.,:;()\\[\\]/%+-]+");
    }

    private static int englishWords(String s){
        Matcher m=Pattern.compile("\\b[A-Za-z]{3,}\\b").matcher(s);int n=0;while(m.find())n++;return n;
    }
    private static int vietnameseWords(String s){
        Matcher m=Pattern.compile("[À-ỹĐđ]{2,}").matcher(s);int n=0;while(m.find())n++;return n;
    }

    /**
     * Fast validation policy:
     * - Trust a valid batch result.
     * - If invalid, make ONE direct recovery request.
     * - If still invalid, skip the unit and continue.
     * No 3x retry + sentence-by-sentence + final direct cascade.
     */
    private static String translateValidatedUnit(Unit u,String candidate,
                                                     String context,
                                                     List<TranslationRouter.Provider> providers)
            throws Exception{
        String t=clean(candidate);
        String reason=validationReason(u,t);
        if(reason==null)return t;

        try{
            String direct=translateDirect(u,context,providers);
            String directReason=validationReason(u,direct);
            if(directReason==null)return direct;
            throw new IOException(directReason);
        }catch(Exception e){
            throw new IOException("validation failed: "+reason+"; direct recovery failed: "
                    +safeLog(e.getMessage()),e);
        }
    }

    private static boolean looksComplete(Unit u,String translation){
        return validationReason(u,translation)==null;
    }

    private static String translateDirect(Unit u,String context,
                                           List<TranslationRouter.Provider> providers)
            throws Exception{
        Map<String,String> marks=new LinkedHashMap<>();
        String src=protectMarkup(u.inner,marks);
        String prompt="Translate ONLY the following English medical-text fragment into professional Vietnamese. "
                +"Never return an empty answer. Do not explain anything. "
                +"Keep every placeholder __MBT_MARKUP_000__ exactly unchanged and in the same position. "
                +"Preserve numbers, units, abbreviations and citations. Return only the translation.\n\n"+src;
        String response=TranslationRouter.translate(prompt,context,providers);
        return clean(restoreMarkup(response,marks));
    }

    private static String translateBySentences(Unit u,String context,
                                                    List<TranslationRouter.Provider> providers)
            throws Exception{
        Map<String,String> marks=new LinkedHashMap<>();
        String src=protectMarkup(u.inner,marks);
        List<String> sentences=splitSentences(src);
        if(sentences.isEmpty()) return "";
        if(sentences.size()==1) return translateDirect(u,context,providers);

        StringBuilder prompt=new StringBuilder();
        prompt.append("Translate EVERY sentence below into professional Vietnamese. ")
              .append("Return ONLY a JSON array of strings, in exactly the same order and count. ")
              .append("Do not omit, merge, summarize, or reorder any sentence. ")
              .append("Preserve numbers, ranges, abbreviations, gene names, units and citation markers. ")
              .append("This is a recovery pass because the previous translation omitted sentence(s).\\n");\n        for(int i=0;i<sentences.size();i++){\n            prompt.append(i+1).append(". ").append(sentences.get(i)).append("\
");\n        }\n\n        String response=TranslationRouter.translate(prompt.toString(),context,providers).trim();\n        int a=response.indexOf('['),b=response.lastIndexOf(']');\n        if(a<0||b<=a)throw new IOException("Fallback từng câu không trả về JSON.");\n        JSONArray arr=new JSONArray(response.substring(a,b+1));\n        if(arr.length()!=sentences.size())\n            throw new IOException("Fallback từng câu trả về "+arr.length()+"/"+sentences.size()+" câu.");\n        StringBuilder out=new StringBuilder();\n        for(int i=0;i<arr.length();i++){\n            String t=arr.optString(i,"").trim();\n            if(t.isEmpty())throw new IOException("Fallback từng câu có câu trống.");\n            if(i>0)out.append(" ");\n            out.append(t);\n        }\n        return restoreMarkup(out.toString(),marks);\n    }\n\n    private static List<String> splitSentences(String s){\n        List<String> out=new ArrayList<>();\n        if(s==null)return out;\n        Matcher m=Pattern.compile(".*?(?:[.!?](?=\\s|$)|$)",Pattern.DOTALL).matcher(s.trim());\n        while(m.find()){\n            String x=m.group().trim();\n            if(!x.isEmpty())out.add(x);\n            if(m.end()==s.trim().length())break;\n        }\n        return out;\n    }\n\n    private static String validationReason(Unit u,String translation){\n        if(blank(translation))return "AI trả về nội dung trống";\n        String src=strip(u.inner);\n        String dst=strip(translation);\n        if(src.length()<2)return null;\n        if(dst.length()==0)return "bản dịch không có nội dung nhìn thấy";\n\n        if(src.length()>=80 && dst.length()<Math.max(20,src.length()*0.30))\n            return "bản dịch ngắn bất thường so với nội dung nguồn";\n\n        int srcSent=sentenceCount(src);\n        int dstSent=sentenceCount(dst);\n        if(srcSent>=2 && dstSent<srcSent)\n            return "thiếu câu (nguồn "+srcSent+" câu, bản dịch "+dstSent+" câu)";\n        if(srcSent>=2 && dstSent<1)\n            return "không có câu trong bản dịch";\n\n        List<String> nums=importantTokens(src);\n        if(!nums.isEmpty()){\n            Map<String,Integer> have=new HashMap<>();\n            for(String x:importantTokens(dst))have.put(x,have.getOrDefault(x,0)+1);\n            Map<String,Integer> need=new HashMap<>();\n            for(String x:nums)need.put(x,need.getOrDefault(x,0)+1);\n            int covered=0,total=0;\n            for(Map.Entry<String,Integer> e:need.entrySet()){\n                total+=e.getValue();\n                covered+=Math.min(e.getValue(),have.getOrDefault(e.getKey(),0));\n            }\n            if(total>0 && covered < Math.max(1,(int)Math.ceil(total*0.75)))\n                return "thiếu số liệu/citation quan trọng ("+covered+"/"+total+" token được giữ lại)";\n        }\n\n        String low=dst.toLowerCase(Locale.US);\n        if(src.toLowerCase(Locale.US).equals("feature")\n                &&!low.equals("đặc điểm")&&!low.equals("đặc trưng"))return "heading Feature chưa được dịch";\n        if(src.toLowerCase(Locale.US).equals("renal")\n                &&!low.contains("thận"))return "heading Renal chưa được dịch";\n        if(src.toLowerCase(Locale.US).equals("seizure")\n                &&!low.contains("co giật"))return "heading Seizure chưa được dịch";\n        if(src.toLowerCase(Locale.US).equals("prenatal")\n                &&!low.contains("trước sinh"))return "heading Prenatal chưa được dịch";\n        if(src.toLowerCase(Locale.US).equals("definition")\n                &&!low.contains("định nghĩa"))return "heading Definition chưa được dịch";\n        if(src.toLowerCase(Locale.US).equals("classic signs")\n                &&!low.contains("dấu hiệu"))return "heading Classic Signs chưa được dịch";\n        return null;\n    }\n\n    private static boolean isProviderUnavailable(String message){
        if(message==null)return false;
        String s=message.toLowerCase(Locale.US);
        return s.contains("http 429")||s.contains("quota")||s.contains("rate limit")
                ||s.contains("resource_exhausted")||s.contains("all ai provider");
    }

    private static String shortId(String s){
        if(s==null)return "";
        return s.substring(0,Math.min(12,s.length()));
    }

    private static String safeLog(String s){
        if(s==null)return "unknown";
        return s.replace('\n',' ').replace('\r',' ').replace('|','/');
    }

    private static int sentenceCount(String s){\n        if(s==null||s.trim().isEmpty())return 0;\n        Matcher m=Pattern.compile("[.!?](?=\\s|$)").matcher(s);\n        int n=0;while(m.find())n++;\n        return Math.max(1,n);\n    }\n\n    private static List<String> importantTokens(String s){\n        List<String> out=new ArrayList<>();\n        if(s==null)return out;\n        Matcher m=Pattern.compile(\n                "(?i)(?<![A-Za-z0-9])(?:\\d+(?:[.,]\\d+)?(?:–|-|to)\\d+(?:[.,]\\d+)?%?|\\d+(?:[.,]\\d+)?%|\\d+:[0-9]+|[A-Z]{2,}\\d*(?:[.-]\\d+)+)(?![A-Za-z0-9])")\n                .matcher(s);\n        while(m.find())out.add(m.group().toLowerCase(Locale.US));\n        return out;\n    }\n\n    private static int batchChars(List<Unit> batch){ int n=0; for(Unit u:batch)n+=u.inner.length(); return n; }\n\n    private static List<Unit> makeBatch(List<Unit> p,int start){\n        List<Unit> b=new ArrayList<>();int chars=0;\n        for(int i=start;i<p.size()&&b.size()<MAX_BATCH_UNITS;i++){\n            Unit u=p.get(i);int cost=u.inner.length()+180;\n            if(!b.isEmpty()&&chars+cost>MAX_BATCH_CHARS)break;\n            b.add(u);chars+=cost;\n        }\n        return b;\n    }\n\n    private static BatchResult translateOneBatchSafe(List<Unit> batch,List<TranslationRouter.Provider> providers,List<GlossaryManager.Term> glossary){\n        try{\n            return translateOneBatch(batch,providers,glossary);\n        }catch(Exception e){\n            // Keep the batch alive so each unit gets its own recovery attempt.\n            // An empty map means no translation is trusted; the original EPUB\n            // source is retained for every unit that still fails.\n            return new BatchResult(batch,new HashMap<>(),\n                    "Batch failed; recovering units independently. "+e.getMessage());\n        }\n    }\n\n    private static BatchResult translateOneBatch(List<Unit> batch,List<TranslationRouter.Provider> providers,List<GlossaryManager.Term> glossary)throws Exception{\n        StringBuilder context=new StringBuilder("Medical obstetric ultrasound / fetal medicine textbook. Translate English to professional Vietnamese. Preserve medical terminology, abbreviations, numbers, units, citations and inline HTML/XML markup.");\n        StringBuilder combined=new StringBuilder();\n        for(Unit u:batch)combined.append(strip(u.inner)).append("
");\n        String gt=GlossaryManager.promptTerms(combined.toString(),glossary);\n        if(!gt.isEmpty())context.append("

").append(gt);\n        Map<String,String> got=translateBatch(batch,context.toString(),providers);\n        return new BatchResult(batch,got,context.toString());\n    }\n\n    private static Map<String,String> translateBatch(List<Unit> batch,String context,List<TranslationRouter.Provider> providers)throws Exception{\n        StringBuilder s=new StringBuilder();\n        s.append("Return ONLY a JSON array. Each object has exactly id and translation. Keep IDs unchanged. ");\n        s.append("Translate only visible English prose. Preserve every HTML/XML tag and attribute. No Markdown.
");\n        for(Unit u:batch){\n            Map<String,String> marks=new LinkedHashMap<>();\n            String protectedSource=protectMarkup(u.inner,marks);\n            s.append("{\"id\":\"").append(json(u.id)).append("\",\"source\":\"").append(json(protectedSource)).append("\"}\n");
        }
        String response=TranslationRouter.translate(s.toString(),context,providers).trim();
        String fence=String.valueOf((char)96)+String.valueOf((char)96)+String.valueOf((char)96);
        if(response.startsWith(fence))response=response.replaceFirst("^"+fence+"(?:json)?\\s*","").replaceFirst("\\s*"+fence+"$","");
        int a=response.indexOf('['),b=response.lastIndexOf(']');
        if(a<0||b<=a)throw new IOException("AI không trả về JSON batch hợp lệ.");
        JSONArray arr=new JSONArray(response.substring(a,b+1));Map<String,String> out=new HashMap<>();
        for(int i=0;i<arr.length();i++){
            JSONObject o=arr.optJSONObject(i);if(o==null)continue;
            String id=o.optString("id",""),t=o.optString("translation","");
            if(!id.isEmpty()&&!t.isEmpty()){
                for(Unit u:batch) if(u.id.equals(id)){
                    Map<String,String> marks=new LinkedHashMap<>();
                    protectMarkup(u.inner,marks);
                    try{ t=restoreMarkup(t,marks); if(!blank(t)) out.put(id,t); }
                    catch(Exception ignored){ }
                    break;
                }
            }
        }
        return out;
    }

    private static void rebuild(File source,File output,List<Unit> units,Map<String,String> done)throws Exception{
        Map<String,List<Rep>> byFile=new LinkedHashMap<>();
        for(Unit u:units){String t=done.get(u.id);if(!blank(t))byFile.computeIfAbsent(u.file,k->new ArrayList<>()).add(new Rep(u,t));}
        Map<String,String> replacements=new HashMap<>();
        try(ZipFile zip=new ZipFile(source)){
            for(Map.Entry<String,List<Rep>> e:byFile.entrySet()){
                ZipEntry ze=zip.getEntry(e.getKey());if(ze==null)continue;
                String x=read(zip,ze);List<Rep> rs=e.getValue();
                rs.sort((a,b)->Integer.compare(b.u.ordinal,a.u.ordinal));
                for(Rep r:rs){
                    Matcher m=BLOCK.matcher(x);int i=0;boolean replaced=false;
                    while(m.find()){
                        if(i==r.u.ordinal){
                            String rep="<"+m.group(1)+m.group(2)+">"+r.t+"</"+m.group(1)+">";
                            x=x.substring(0,m.start())+rep+x.substring(m.end());replaced=true;break;
                        }
                        i++;
                    }
                    if(!replaced)throw new IOException("Không tìm thấy translation unit "+r.u.id+" khi rebuild.");
                }
                replacements.put(e.getKey(),x);
            }
        }
        EpubBuilder.copyWithReplacements(source,output,replacements);
    }

    private static String protectMarkup(String s,Map<String,String> marks){
        if(s==null||s.isEmpty())return "";
        Matcher m=MARKUP_TOKEN.matcher(s);
        StringBuffer out=new StringBuffer();
        int i=0;
        while(m.find()){
            String token=String.format(Locale.US,"__MBT_MARKUP_%03d__",i++);
            marks.put(token,m.group());
            m.appendReplacement(out,Matcher.quoteReplacement(token));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String restoreMarkup(String s,Map<String,String> marks)throws IOException{
        String t=s==null?"":s;
        for(Map.Entry<String,String> e:marks.entrySet()){
            int count=0,from=0;
            while((from=t.indexOf(e.getKey(),from))>=0){count++;from+=e.getKey().length();}
            if(count!=1)throw new IOException("AI làm mất/thay đổi placeholder "+e.getKey());
            t=t.replace(e.getKey(),e.getValue());
        }
        return t;
    }

    private static String clean(String s){
        String t=s==null?"":s.trim();
        String fence=String.valueOf((char)96)+String.valueOf((char)96)+String.valueOf((char)96);
        if(t.startsWith(fence))t=t.replaceFirst("^"+fence+"(?:html|xml)?\\s*","").replaceFirst("\\s*"+fence+"$","");
        int a=t.indexOf("<"),b=t.lastIndexOf(">");
        return a>0&&b>a?t.substring(a,b+1).trim():t;
    }
    private static String json(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}\n    private static String strip(String s){return s.replaceAll("<[^>]+>"," ").replaceAll("&[a-zA-Z#0-9]+;"," ").replaceAll("\\s+"," ").trim();}\n    private static boolean blank(String s){return s==null||s.trim().isEmpty();}\n    private static String read(ZipFile z,ZipEntry e)throws Exception{try(InputStream in=z.getInputStream(e);ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))>0)o.write(b,0,n);return o.toString(StandardCharsets.UTF_8.name());}}\n    private static void copyFile(File s,File t)throws IOException{File p=t.getParentFile();if(p!=null&&!p.exists())p.mkdirs();try(InputStream in=new FileInputStream(s);OutputStream o=new FileOutputStream(t)){byte[] b=new byte[16384];int n;while((n=in.read(b))>0)o.write(b,0,n);}}\n\n    private static final class Candidate{\n        final int start,end; final String tag,attrs,inner;\n        Candidate(int start,int end,String tag,String attrs,String inner){this.start=start;this.end=end;this.tag=tag;this.attrs=attrs;this.inner=inner;}\n    }\n\n    private static final class Unit{\n        final String id,file,tag,attrs,inner,sourceHash;final int ordinal;final boolean translatable;\n        Unit(String id,String file,int ordinal,String tag,String attrs,String inner,String sh,boolean tr){this.id=id;this.file=file;this.ordinal=ordinal;this.tag=tag;this.attrs=attrs;this.inner=inner;this.sourceHash=sh;this.translatable=tr;}\n    }\n    private static final class Rep{final Unit u;final String t;Rep(Unit u,String t){this.u=u;this.t=t;}}\n    private static final class BatchResult{\n        final List<Unit> batch;final Map<String,String> translations;final String context;\n        BatchResult(List<Unit> b,Map<String,String> t,String c){batch=b;translations=t;context=c;}\n    }\n}\n