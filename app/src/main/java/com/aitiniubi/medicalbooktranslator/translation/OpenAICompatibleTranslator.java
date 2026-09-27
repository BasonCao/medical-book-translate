package com.aitiniubi.medicalbooktranslator.translation;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class OpenAICompatibleTranslator {
    public static String translate(String source, String context, TranslationConfig c) throws Exception {
        if(c.endpoint==null||c.endpoint.trim().isEmpty()) throw new IllegalArgumentException("Chưa cấu hình AI endpoint");
        if(c.model==null||c.model.trim().isEmpty()) throw new IllegalArgumentException("Chưa cấu hình model");
        String endpoint = normalizeEndpoint(c.endpoint);
        boolean responsesApi = endpoint.endsWith("/responses");
        HttpURLConnection h=(HttpURLConnection)new URL(endpoint).openConnection();
        h.setRequestMethod("POST"); h.setConnectTimeout(20000); h.setReadTimeout(120000); h.setDoOutput(true);
        h.setRequestProperty("Content-Type","application/json"); h.setRequestProperty("Accept","application/json");
        if(c.apiKey!=null&&!c.apiKey.trim().isEmpty()) h.setRequestProperty("Authorization","Bearer "+c.apiKey.trim());
        String system="You are a medical book translator specializing in obstetric and gynecologic ultrasound and fetal medicine. Translate English to professional Vietnamese. Preserve meaning, numbers, units, abbreviations, citations, HTML/XML tags, entities and inline markup exactly. Do not add explanations. Return only the translated content. Do not translate URLs, file names, CSS classes, IDs, reference numbers or abbreviations unless they are ordinary prose.";
        JSONObject body=new JSONObject(); body.put("model",c.model.trim());
        String userText="Context:\n"+context+"\n\nText to translate:\n"+source;
        if(responsesApi) {
            JSONArray input=new JSONArray();
            input.put(new JSONObject().put("role","system").put("content",new JSONArray().put(new JSONObject().put("type","input_text").put("text",system))));
            input.put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","input_text").put("text",userText))));
            body.put("input",input);
        } else {
            JSONArray msgs=new JSONArray(); msgs.put(new JSONObject().put("role","system").put("content",system)); msgs.put(new JSONObject().put("role","user").put("content",userText));
            body.put("messages",msgs); body.put("temperature",0.1);
        }
        try(OutputStream os=h.getOutputStream()){ os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code=h.getResponseCode(); InputStream is=code>=200&&code<300?h.getInputStream():h.getErrorStream(); String resp=read(is);
        if(code<200||code>=300) throw new IOException("AI HTTP "+code+": "+extractError(resp));
        return responsesApi ? parseResponsesText(resp) : parseChatText(resp);
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
        StringBuilder out=new StringBuilder(); JSONArray output=r.optJSONArray("output");
        if(output!=null) for(int i=0;i<output.length();i++) {
            JSONObject item=output.optJSONObject(i); if(item==null) continue; JSONArray content=item.optJSONArray("content"); if(content==null) continue;
            for(int j=0;j<content.length();j++) { JSONObject part=content.optJSONObject(j); if(part==null) continue; String text=part.optString("text",""); if(!text.isEmpty()){ if(out.length()>0) out.append("\n"); out.append(text); } }
        }
        if(out.length()==0) throw new IOException("Không tìm thấy nội dung bản dịch trong Responses API.");
        return cleanModelOutput(out.toString());
    }
    private static String parseChatText(String resp) throws Exception {
        JSONObject r=new JSONObject(resp); return cleanModelOutput(r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));
    }
    private static String cleanModelOutput(String s) {
        String t=s==null?"":s.trim();
        if(t.startsWith("```")) { t=t.replaceFirst("^```(?:html|xml)?\\s*",""); t=t.replaceFirst("\\s*```$",""); }
        return t.trim();
    }
    private static String extractError(String resp) {
        try { JSONObject r=new JSONObject(resp); JSONObject error=r.optJSONObject("error"); if(error!=null){ String message=error.optString("message",""); if(!message.isEmpty()) return message; } } catch(Exception ignored) {}
        return resp;
    }
    private static String read(InputStream in)throws Exception {
        if(in==null)return ""; try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){ StringBuilder s=new StringBuilder(); String l; while((l=r.readLine())!=null)s.append(l); return s.toString(); }
    }
}