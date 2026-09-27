package com.aitiniubi.medicalbooktranslator.translation;

import org.json.*;import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;

public final class OpenAICompatibleTranslator {
    public static String translate(String source, String context, TranslationConfig c) throws Exception {
        if(c.endpoint==null||c.endpoint.trim().isEmpty()) throw new IllegalArgumentException("Chưa cấu hình AI endpoint");
        if(c.model==null||c.model.trim().isEmpty()) throw new IllegalArgumentException("Chưa cấu hình model");
        HttpURLConnection h=(HttpURLConnection)new URL(c.endpoint).openConnection(); h.setRequestMethod("POST"); h.setConnectTimeout(20000); h.setReadTimeout(120000); h.setDoOutput(true); h.setRequestProperty("Content-Type","application/json");
        if(c.apiKey!=null&&!c.apiKey.isEmpty()) h.setRequestProperty("Authorization","Bearer "+c.apiKey);
        String system="You are a medical book translator specializing in obstetric and gynecologic ultrasound and fetal medicine. Translate English to professional Vietnamese. Preserve meaning, numbers, units, abbreviations, citations, and HTML/XML tags. Do not add explanations. Return only the translated content.";
        JSONObject body=new JSONObject(); body.put("model",c.model); JSONArray msgs=new JSONArray(); msgs.put(new JSONObject().put("role","system").put("content",system)); msgs.put(new JSONObject().put("role","user").put("content","Context:\n"+context+"\n\nText:\n"+source)); body.put("messages",msgs); body.put("temperature",0.1);
        try(OutputStream os=h.getOutputStream()){os.write(body.toString().getBytes(StandardCharsets.UTF_8));}
        int code=h.getResponseCode(); InputStream is=code>=200&&code<300?h.getInputStream():h.getErrorStream(); String resp=read(is); if(code<200||code>=300) throw new IOException("AI HTTP "+code+": "+resp);
        JSONObject r=new JSONObject(resp); return r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
    }
    private static String read(InputStream in)throws Exception{if(in==null)return "";try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){StringBuilder s=new StringBuilder();String l;while((l=r.readLine())!=null)s.append(l);return s.toString();}}
}
