package com.aitiniubi.medicalbooktranslator.translation;

import org.json.*;
import okhttp3.*;
import okhttp3.dnsoverhttps.DnsOverHttps;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class OpenAICompatibleTranslator {
    private static final int MAX_ATTEMPTS = 3;
    private static final long CONNECT_TIMEOUT_SECONDS = 30;
    private static final long READ_TIMEOUT_SECONDS = 180;

    /*
     * Android's platform resolver can fail with UnknownHostException even when
     * Chrome can reach the same HTTPS hostname. Use DNS-over-HTTPS with
     * bootstrap IPs so the API hostname is resolved independently, while TLS
     * still validates the original hostname. No certificate verification is
     * bypassed and no provider IP is hard-coded.
     */
    private static final OkHttpClient HTTP_CLIENT = createHttpClient();

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
            // Give translation enough completion room, while preventing reasoning models from\n            // consuming the entire budget on hidden chain-of-thought. OpenRouter supports\n            // the reasoning control on its chat-completions interface.\n            body.put("max_tokens",8192);\n            if(c.endpoint!=null && c.endpoint.contains("openrouter.ai")) {\n                body.put("reasoning",new JSONObject().put("enabled",false));\n            }
        }

        byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);
        List<String> endpoints=endpointCandidates(normalized);
        IOException lastIo=null;

        for(String endpoint:endpoints) {
            for(int attempt=1;attempt<=MAX_ATTEMPTS;attempt++) {
                try {
                    RequestBody requestBody=RequestBody.create(payload, MediaType.parse("application/json; charset=utf-8"));
                    Request.Builder rb=new Request.Builder()
                            .url(endpoint)
                            .post(requestBody)
                            .header("Accept","application/json")
                            .header("User-Agent","MedicalBookTranslator/1.4 Android");
                    if(c.apiKey!=null&&!c.apiKey.trim().isEmpty()) {
                        rb.header("Authorization","Bearer "+c.apiKey.trim());
                    }
                    if(endpoint.contains("openrouter.ai")) {
                        rb.header("HTTP-Referer","https://github.com/BasonCao/medical-book-translate");
                        rb.header("X-Title","Medical Book Translator");
                    }

                    try(Response response=HTTP_CLIENT.newCall(rb.build()).execute()) {
                        String resp=response.body()==null?"":response.body().string();
                        int code=response.code();
                        if(code>=200&&code<300) {
                            return responsesApi ? parseResponsesText(resp) : parseChatText(resp);
                        }

                        String error=extractError(resp);
                        if(code==429 && isDailyFreeQuotaExhausted(error)) {
                            throw new IOException(formatHttpError(code, endpoint, error));
                        }
                        if((code==429 || code>=500) && attempt<MAX_ATTEMPTS) {
                            long retryMs = code==429 ? retryDelayMillis(response, resp) : 700L*attempt;
                            sleepBeforeRetryMillis(retryMs);
                            continue;
                        }

                        throw new IOException(formatHttpError(code, endpoint, error));
                    }
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
                }
            }
        }

        String hostError=lastIo==null?"không xác định":lastIo.getMessage();
        if(isOpenRouter(normalized)) {
            throw new IOException("Không phân giải được máy chủ OpenRouter từ app. App đã dùng DNS-over-HTTPS và thử openrouter.ai, us.openrouter.ai và eu.openrouter.ai. Kiểm tra Internet/VPN/Private DNS nếu lỗi vẫn còn. Chi tiết: "+hostError,lastIo);
        }
        if(isGemini(normalized)) {
            throw new IOException("Không phân giải được máy chủ Gemini từ app. App đã dùng DNS-over-HTTPS để tránh lỗi resolver của Android. Kiểm tra Internet/VPN nếu lỗi vẫn còn. Chi tiết: "+hostError,lastIo);
        }
        throw new IOException("Không thể kết nối tới AI provider. Chi tiết: "+hostError,lastIo);
    }

    private static OkHttpClient createHttpClient() {
        OkHttpClient bootstrap = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        DnsOverHttps doh = new DnsOverHttps.Builder()
                .client(bootstrap)
                .url(HttpUrl.parse("https://cloudflare-dns.com/dns-query"))
                .bootstrapDnsHosts(
                        ip("1.1.1.1"),
                        ip("1.0.0.1"),
                        ip("2606:4700:4700::1111"),
                        ip("2606:4700:4700::1001"))
                .includeIPv6(true)
                .build();

        return bootstrap.newBuilder()
                .dns(doh)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    private static InetAddress ip(String value) {
        try { return InetAddress.getByName(value); }
        catch (UnknownHostException e) { throw new IllegalStateException("Invalid DNS bootstrap address: "+value,e); }
    }

    private static boolean isOpenRouter(String endpoint) {
        return endpoint.contains("openrouter.ai");
    }

    private static boolean isGemini(String endpoint) {
        return endpoint.contains("generativelanguage.googleapis.com");
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
                || s.contains("stream reset")
                || s.contains("refused")
                || s.contains("timed out")
                || s.contains("timeout");
    }

    private static void sleepBeforeRetry(int attempt) throws IOException {
        sleepBeforeRetryMillis(700L*attempt);
    }

    private static void sleepBeforeRetryMillis(long millis) throws IOException {
        try { Thread.sleep(Math.max(0L, millis)); }
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
        JSONArray choices=r.optJSONArray("choices");
        if(choices==null||choices.length()==0) throw new IOException("Provider trả về response không có choices. Response: "+compact(resp));
        JSONObject choice=choices.optJSONObject(0);
        if(choice==null) throw new IOException("Provider trả về choice không hợp lệ.");
        JSONObject msg=choice.optJSONObject("message");
        if(msg==null) throw new IOException("Provider trả về message không hợp lệ.");
        Object content=msg.opt("content");
        String text=extractContentText(content);
        if(text.isEmpty()) text=extractContentText(choice.opt("text"));
        if(text.isEmpty()) text=extractContentText(choice.opt("output_text"));
        if(!text.isEmpty()) return cleanModelOutput(text);

        // Some OpenAI-compatible providers return structured content blocks.
        Object reasoning=msg.opt("reasoning");
        String reasoningText=extractContentText(reasoning);
        if(!reasoningText.isEmpty()) {
            throw new IOException("OpenRouter model chỉ trả về reasoning mà không có nội dung dịch. finish_reason="
                    +choice.optString("finish_reason","unknown")+". Hãy chọn model FREE cố định thay cho Auto Free Router.");
        }

        throw new IOException("Provider không trả về nội dung bản dịch. finish_reason="
                +choice.optString("finish_reason","unknown")
                +", model="+r.optString("model","unknown")
                +". Response: "+compact(resp));
    }

    private static String extractContentText(Object value) {
        if(value==null||value==JSONObject.NULL) return "";
        if(value instanceof String) return ((String)value).trim();
        if(value instanceof JSONObject) {
            JSONObject o=(JSONObject)value;
            String t=o.optString("text","");
            if(!t.isEmpty()) return t.trim();
            return o.optString("content","").trim();
        }
        if(value instanceof JSONArray) {
            JSONArray a=(JSONArray)value;
            StringBuilder out=new StringBuilder();
            for(int i=0;i<a.length();i++) {
                Object item=a.opt(i);
                String t=extractContentText(item);
                if(!t.isEmpty()) {
                    if(out.length()>0) out.append("\n");
                    out.append(t);
                }
            }
            return out.toString().trim();
        }
        return String.valueOf(value).trim();
    }

    private static boolean isDailyFreeQuotaExhausted(String error) {
        if(error==null) return false;
        String s=error.toLowerCase(Locale.US);
        return s.contains("free-models-per-day")
                || s.contains("free model requests per day")
                || s.contains("requests per day")
                || s.contains("add 10 credits");
    }

    private static long retryDelayMillis(Response response,String body) {
        String header=response.header("Retry-After");
        if(header!=null) {
            try { return Math.min(60000L, Math.max(1000L, Long.parseLong(header.trim())*1000L)); }
            catch(Exception ignored) {}
        }
        try {
            JSONObject r=new JSONObject(body);
            JSONArray details=r.optJSONObject("error")==null?null:r.optJSONObject("error").optJSONArray("details");
            if(details!=null) for(int i=0;i<details.length();i++) {
                JSONObject d=details.optJSONObject(i);
                if(d==null) continue;
                String delay=d.optString("retryDelay","");
                if(delay.endsWith("s")) {
                    double seconds=Double.parseDouble(delay.substring(0,delay.length()-1));
                    return Math.min(60000L,Math.max(1000L,(long)(seconds*1000L)));
                }
            }
        } catch(Exception ignored) {}
        return 5000L;
    }

    private static String compact(String s) {
        if(s==null) return "";
        String t=s.replaceAll("\\s+"," ").trim();
        return t.length()>1200?t.substring(0,1200)+"…":t;
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
                String status=error.optString("status","");
                String code=error.has("code")?String.valueOf(error.opt("code")):"";
                StringBuilder out=new StringBuilder();
                if(!code.isEmpty()) out.append("code ").append(code).append(": ");
                if(!message.isEmpty()) out.append(message);
                if(!status.isEmpty()) out.append(" [").append(status).append("]");
                if(out.length()>0) return out.toString();
            }
        } catch(Exception ignored) {}
        return resp==null||resp.isEmpty()?"Provider không trả về chi tiết lỗi.":resp;
    }

    private static String formatHttpError(int code,String endpoint,String error) {
        String provider=isGemini(endpoint)?"Gemini":isOpenRouter(endpoint)?"OpenRouter":"AI provider";
        if(code==401||code==403) return provider+" HTTP "+code+": API key không hợp lệ hoặc không có quyền. "+error;
        if(code==404) return provider+" HTTP 404: endpoint hoặc model không tồn tại. "+error;
        if(code==429) return provider+" HTTP 429: quota/rate limit. "+error;
        if(code>=500) return provider+" HTTP "+code+": provider đang lỗi/tạm thời không sẵn sàng. "+error;
        return provider+" HTTP "+code+": "+error;
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
