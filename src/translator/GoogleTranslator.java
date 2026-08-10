package translator;

import arc.util.Log;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public class GoogleTranslator{

    private static final String API_URL = "https://translate.googleapis.com/translate_a/single";
    private static final int RATE_LIMIT_MS = 600;

    private final AtomicLong lastSend = new AtomicLong(0);

    public GoogleTranslator(){
    }

    public static class TranslateResult{
        public final String translation;
        public String detectedLang;

        public TranslateResult(String translation, String detectedLang){
            this.translation = translation;
            this.detectedLang = detectedLang;
        }
    }

    /** Synchronously translates the given text, blocking the calling thread. Returns null if the request fails. */
    public TranslateResult translate(String text, String source, String target){
        long wait = lastSend.get() - System.currentTimeMillis() + RATE_LIMIT_MS;
        if(wait > 0){
            try{
                Thread.sleep(wait);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
                return null;
            }
        }
        lastSend.set(System.currentTimeMillis());
        return fetch(text, source, target);
    }

    private TranslateResult fetch(String text, String source, String target){
        try{
            String url = API_URL + "?client=gtx&sl=" + source + "&tl=" + target + "&dt=t&q="
                + URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(3000);
            con.setReadTimeout(3000);
            con.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = con.getResponseCode();
            if(code < 200 || code >= 300){
                Log.err("[Translator] Google translate error code: HTTP " + code);
                con.disconnect();
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try(BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))){
                String line;
                while((line = r.readLine()) != null){
                    sb.append(line).append("\n");
                }
            }
            con.disconnect();
            TranslateResult result = parse(sb.toString());
            if(result != null){
                if(!source.equalsIgnoreCase("auto")){
                    result.detectedLang = source.toLowerCase(Locale.ROOT);
                }else if(!result.detectedLang.isEmpty() && result.detectedLang.contains("-")){
                    result.detectedLang = result.detectedLang.split("-")[0];
                }
                if(!result.detectedLang.isEmpty()){
                    result.detectedLang = result.detectedLang.toLowerCase(Locale.ROOT);
                }
            }
            return result;
        }catch(Exception e){
            Log.err("[Translator] Translation request failed: ", e);
            return null;
        }
    }

    /** Parses [[["translated","original",...]],null,"detectedLang",...] */
    private TranslateResult parse(String json){
        try{
            JsonValue root = new JsonReader().parse(json);
            JsonValue segment = root.child.child.child;
            if(segment == null){
                return null;
            }
            String translation = segment.asString();
            String detectedLang = root.child.next != null && root.child.next.next != null ? root.child.next.next.asString() : "";
            return new TranslateResult(translation, detectedLang);
        }catch(Exception e){
            Log.err("[Translator] Could not parse translation response.", e);
            return null;
        }
    }
}