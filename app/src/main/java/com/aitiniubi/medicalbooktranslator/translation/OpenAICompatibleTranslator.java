package com.aitiniubi.medicalbooktranslator.translation;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class OpenAICompatibleTranslator {
    private static final int MAX_ATTEMPTS = 3;

    public static String translate(String source, String context, TranslationConfig c) throws Exception {
        if(c.endpoint==null||c.endpoint.trim().isEmpty()) throw new IllegalArgumentException("Chưa cấu hình AI endpoint");
        if(c.model==null||c.model.trim().isEmpty()) throw new IllegalArgumentException("Chưa cấu hình model");

        String normalized = normalizeEndpoint(c.endpoint);
        boolean responsesApi = normalized.endsWith("/responses");
        String system="You are a medical book translator specializing in obstetric and gynecologic ultrasound and fetal medicine. Translate English to professional Vietnamese. Preserve meaning, numbers, units, abbreviations, citations, HTML/XML tags, entities and inline markup exactly. Do not add explanations. Return only the translated content. Do not translate URLs, file names, CSS classes, IDs, reference numbers or abbreviations unless they are ordinary prose.";
        String userText="Context:\n"+context+"\n\nText to translate:\n"+source;

        JSONObject body=new JSONObject();
        body.put("model",c.model.trim());
        if(responsesApi) {
            JSONArray input=new JSONArray();
            input.put(new JSONObject().put("role","system").put("content",new JSONArray().put(new JSONObject().put("type","input_text").put("text",system))));
            input.put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","input_text").put("text",userText))));
            body.put("input",input);
        } else {
            JSONArray msgs=new JSONArray();
            msgs.put(new JSONObject().put("role","system").put("content",system));
            msgs.put(new JSONObject().put("role","user").put("content",userText));
            body.put("messages",msgs);
            body.put("temperature",0.1);
        }

        byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);
        List<String> endpoints=endpointCandidates(normalized);
        IOException lastIo=null;

        for(String endpoint:endpoints) {
            for(int attempt=1;attempt<=MAX_ATTEMPTS;attempt++) {
                HttpURLConnection h=null;
                try {
                    h=(HttpURLConnection)new URL(endpoint).openConnection();
                    h.setRequestMethod("POST");
                    h.setConnectTimeout(30000);
                    h.setReadTimeout(180000);
                    h.setDoOutput(true);
                    h.setUseCaches(false);
                    h.setInstanceFollowRedirects(true);
                    h.setFixedLengthStreamingMode(payload.length);
                    h.setRequestProperty("Content-Type","application/json; charset=utf-8");
                    h.setRequestProperty("Accept","application/json");
                    h.setRequestProperty("Accept-Encoding","identity");
                    h.setRequestProperty("Connection","close");
                    h.setRequestProperty("User-Agent","MedicalBookTranslator/1.3 Android");
                    if(c.apiKey!=null&&!c.apiKey.trim().isEmpty()) h.setRequestProperty("Authorization","Bearer "+c.apiKey.trim());
                    if(endpoint.contains("openrouter.ai")) {
                        h.setRequestProperty("HTTP-Referer","https://github.com/BasonCao/medical-book-translate");
                        h.setRequestProperty("X-Title","Medical Book Translator");
                    }

                    try(OutputStream os=h.getOutputStream()) {
                        os.write(payload);
                        os.flush();
                    }

                    int code=h.getResponseCode();
                    InputStream is=code>=200&&code<300?h.getInputStream():h.getErrorStream();
                    String resp=read(is);

                    if(code>=200&&code<300) {
                        return responsesApi ? parseResponsesText(resp) : parseChatText(resp);
                    }

                    String error=extractError(resp);
                    if((code==429 || code>=500) && attempt<MAX_ATTEMPTS) {
                        sleepBeforeRetry(attempt);
                        continue;
                    }
                    throw new IOException("AI HTTP "+code+": "+error);
                } catch(UnknownHostException e) {
                    lastIo=e;
                    break;
                } catch(SocketException | EOFException e) {
                    lastIo=e;
                    if(attempt<MAX_ATTEMPTS) {
                        sleepBeforeRetry(attempt);
                        continue;
                    }
                    break;
                } catch(IOException e) {
                    lastIo=e;
                    if(attempt<MAX_ATTEMPTS && isTransient(e)) {
                        sleepBeforeRetry(attempt);
                        continue;
                    }
                    throw e;
                } finally {
                    if(h!=null) h.disconnect();
                }
            }
        }

        String hostError=lastIo==null?"không xác định":lastIo.getMessage();
        if(isOpenRouter(normalized)) {
            throw new IOException("Không phân giải được máy chủ OpenRouter. App đã thử openrouter.ai, us.openrouter.ai và eu.openrouter.ai. Hãy kiểm tra Internet/Private DNS trên Android. Chi tiết: "+hostError,lastIo);
        }
        throw new IOException("Không thể kết nối tới AI provider. Chi tiết: "+hostError,lastIo);
    }

    private static boolean isOpenRouter(String endpoint) {
        return endpoint.contains("openrouter.ai");
    }

    private static List<String> endpointCandidates(String endpoint) {
        ArrayList<String> out=new ArrayList<>();
        out.add(endpoint);
        if(isOpenRouter(endpoint)) {
            String path=endpoint.substring(endpoint.indexOf(".ai")+3);
            addUnique(out,"https://us.openrouter.ai"+path);
            addUnique(out,"https://eu.openrouter.ai"+path);
        }
        return out;
    }

    private static void addUnique(List<String> list,String value) {
        if(!list.contains(value)) list.add(value);
    }

    private static boolean isTransient(IOException e) {
        String m=e.getMessage();
        if(m==null) return false;
        String s=m.toLowerCase(Locale.US);
        return s.contains("connection reset")
                || s.contains("connection aborted")
                || s.contains("software caused connection abort")
                || s.contains("broken pipe")
                || s.contains("timed out")
                || s.contains("timeout");
    }

    private static void sleepBeforeRetry(int attempt) throws IOException {
        try { Thread.sleep(700L*attempt); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Kết nối AI bị gián đoạn.",e); }
    }

    private static String normalizeEndpoint(String raw) {
        String e=raw.trim().replaceAll("/+$","");
        if(e.endsWith("/responses") || e.endsWith("/chat/completions")) return e;
        if(e.endsWith("/v1")) return e+"/responses";
        return e+"/responses";
    }

    private static String parseResponsesText(String resp) throws Exception {
        JSONObject r=new JSONObject(resp);
        if(r.has("output_text") && !r.isNull("output_text")) return cleanModelOutput(r.getString("output_text"));
        StringBuilder out=new StringBuilder();
        JSONArray output=r.optJSONArray("output");
        if(output!=null) for(int i=0;i<output.length();i++) {
            JSONObject item=output.optJSONObject(i); if(item==null) continue;
            JSONArray content=item.optJSONArray("content"); if(content==null) continue;
            for(int j=0;j<content.length();j++) {
                JSONObject part=content.optJSONObject(j); if(part==null) continue;
                String text=part.optString("text","");
                if(!text.isEmpty()) { if(out.length()>0) out.append("\n"); out.append(text); }
            }
        }
        if(out.length()==0) throw new IOException("Không tìm thấy nội dung bản dịch trong Responses API.");
        return cleanModelOutput(out.toString());
    }

    private static String parseChatText(String resp) throws Exception {
        JSONObject r=new JSONObject(resp);
        return cleanModelOutput(r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));
    }

    private static String cleanModelOutput(String s) {
        String t=s==null?"":s.trim();
        String fence=String.valueOf((char)96)+String.valueOf((char)96)+String.valueOf((char)96);
        if(t.startsWith(fence)) {
            t=t.replaceFirst("^"+fence+"(?:html|xml)?\\s*","");
            t=t.replaceFirst("\\s*"+fence+"$","");
        }
        return t.trim();
    }

    private static String extractError(String resp) {
        try {
            JSONObject r=new JSONObject(resp);
            JSONObject error=r.optJSONObject("error");
            if(error!=null) {
                String message=error.optString("message","");
                if(!message.isEmpty()) return message;
            }
        } catch(Exception ignored) {}
        return resp==null||resp.isEmpty()?"Provider không trả về chi tiết lỗi.":resp;
    }

    private static String read(InputStream in)throws Exception {
        if(in==null)return "";
        try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))) {
            StringBuilder s=new StringBuilder(); String l;
            while((l=r.readLine())!=null)s.append(l);
            return s.toString();
        }
    }
}
