package translator;

import arc.Core;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.NetClient;
import mindustry.gen.SendChatMessageCallPacket;
import mindustry.gen.SendMessageCallPacket2;
import mindustry.net.Net;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-side chat interception.
 *
 * Behaviour when joining servers that do NOT run TranslatorMod:
 *  - outgoing chat (SendChatMessageCallPacket) is translated before it is sent;
 *  - incoming player chat (SendMessageCallPacket2) is translated before display.
 *
 * Incoming messages that already carry the TR\u2192EN style tag are parsed: if the
 * tag's target matches the local player's target they are shown as-is, otherwise
 * the original text is extracted and re-translated into the local player's
 * language. The original message is always kept on screen, followed by the
 * translation in parentheses.
 */
public class ClientTranslateHandler{

    private static final int MAX_PENDING = 16;

    /** "[XX[]\u2192[YY[] original" as broadcast by modded servers and modded clients. */
    private static final Pattern SERVER_TAGGED = Pattern.compile("^(\\[[^\\]]*\\])?([A-Za-z]{2,4})\\[\\]\u2192\\[([A-Za-z]{2,4})\\[\\] (.*)$");
    /** "original[gray] (translation)[]" without a language tag. */
    private static final Pattern TRANSLATED_SUFFIX = Pattern.compile("^(.*?)(\\[gray\\]\\s*\\(.*?\\)\\[\\])$");
    /** "original (translation)" tail without any styling codes. */
    private static final Pattern PAREN_TAIL = Pattern.compile("^(.*?) \\([^()]*\\)$");
    /** Tag prefix of an already-translated chat message. */
    private static final Pattern TAGGED = Pattern.compile("^(\\[[^\\]]*\\])?[A-Za-z]{2,4}\\[\\]\u2192\\[[A-Za-z]{2,4}\\[\\]");

    private static ClientTranslateHandler handler;

    private final TranslatorPlugin mod;
    private final Net originalNet;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final java.util.List<Runnable> pending = new java.util.ArrayList<>();
    private static final long FAILURE_COOLDOWN_MS = 60_000L;
    private volatile long translatorDownUntil;
    private boolean running;

    private ClientTranslateHandler(TranslatorPlugin mod, Net originalNet){
        this.mod = mod;
        this.originalNet = originalNet;
    }

    public static void install(TranslatorPlugin mod){
        if(Vars.headless || Vars.net instanceof TranslatingNet || handler != null){
            return;
        }
        Net original = Vars.net;
        handler = new ClientTranslateHandler(mod, original);
        Vars.net = new TranslatingNet(original);
        Log.info("[Translator] Client chat interception installed.");
    }

    static void onSend(SendChatMessageCallPacket packet, boolean reliable){
        handler.enqueue(handler.new SendTask(packet, reliable));
    }

    static void onReceive(SendMessageCallPacket2 packet){
        handler.onMessage(packet);
    }

    //------------------------------------------------------------------------
    // outbound chat
    //------------------------------------------------------------------------

    private class SendTask implements Runnable{
        final SendChatMessageCallPacket packet;
        final boolean reliable;

        SendTask(SendChatMessageCallPacket packet, boolean reliable){
            this.packet = packet;
            this.reliable = reliable;
        }

        @Override
        public void run(){
            if(mod.config.serverTranslates){
                sendRaw(packet, reliable);
                return;
            }
            String raw = TranslatorPlugin.clean(packet.message);
            String target = mod.config.target;
            if(mod.config.enabled && !target.equalsIgnoreCase("off")
                && !raw.isEmpty() && !raw.startsWith("/") && raw.length() >= mod.config.minLength
                && !alreadyTagged(packet.message) && !mod.config.source.equalsIgnoreCase(target)
                && !translatorDown()){
                GoogleTranslator.TranslateResult r = translate(raw, target, mod.config.source);
                if(r == null){
                    translatorDownUntil = System.currentTimeMillis() + FAILURE_COOLDOWN_MS;
                }else if(!r.translation.isEmpty() && !r.detectedLang.equalsIgnoreCase(target)){
                    translatorDownUntil = 0;
                    packet.message = build(raw, r.translation, r.detectedLang, target, mod.config.showDetectedLang);
                }
            }
            sendRaw(packet, reliable);
        }
    }

    private void sendRaw(SendChatMessageCallPacket packet, boolean reliable){
        Core.app.post(() -> originalNet.send(packet, reliable));
    }

    //------------------------------------------------------------------------
    // inbound chat
    //------------------------------------------------------------------------

    private void onMessage(SendMessageCallPacket2 packet){
        if(packet == null){
            return;
        }
        if(packet.playersender == null){
            deliver(packet);
            return;
        }
        String rawText = packet.unformatted;
        if(rawText == null){
            deliver(packet);
            return;
        }
        if(mod.config.serverTranslates){
            deliver(packet);
            return;
        }
        String othersTarget = mod.config.othersTarget;
        boolean own = Vars.player != null && packet.playersender == Vars.player;
        String target = own ? displayTarget() : othersTarget;
        if(target == null || target.equalsIgnoreCase("off")){
            deliver(packet);
            return;
        }
        String text = rawText;
        TranslationMarker.Parsed marked = TranslationMarker.unwrap(rawText);
        if(marked != null){
            if(marked.langIndex == TranslatorPlugin.langIndex(target)){
                deliver(packet);
                return;
            }
            text = marked.original;
        }else{
            Matcher tagged = SERVER_TAGGED.matcher(rawText);
            if(tagged.matches()){
                if(tagged.group(3).equalsIgnoreCase(target)){
                    deliver(packet);
                    return;
                }
                text = tagged.group(4);
                Matcher s = TRANSLATED_SUFFIX.matcher(text);
                if(s.matches()){
                    text = s.group(1);
                }else{
                    text = stripTrailingParens(text);
                }
            }else{
                Matcher suffixed = TRANSLATED_SUFFIX.matcher(text);
                if(suffixed.matches()){
                    text = suffixed.group(1);
                }
                if(hasTrailingParens(TranslatorPlugin.clean(text))){
                    Log.debug("[Translator] Receive keeping untagged translated-looking text as-is: " + text);
                    deliver(packet);
                    return;
                }
            }
        }
        String raw = TranslatorPlugin.clean(text);
        int min = mod.config.minLength;
        String reason = null;
        if(!mod.config.enabled){
            reason = "disabled";
        }else if(raw.isEmpty()){
            reason = "empty";
        }else if(raw.startsWith("/")){
            reason = "command";
        }else if(raw.length() < min){
            reason = "tooShort";
        }
        if(reason != null){
            Log.debug("[Translator] Receive skip (" + reason + "): " + raw);
            deliver(packet);
            return;
        }
        Log.info("[Translator] Receive translating: " + raw + " -> " + target);
        enqueue(new ReceiveTask(packet, raw, own));
    }

    private class ReceiveTask implements Runnable{
        final SendMessageCallPacket2 packet;
        final String text;
        final boolean own;

        ReceiveTask(SendMessageCallPacket2 packet, String text, boolean own){
            this.packet = packet;
            this.text = text;
            this.own = own;
        }

        @Override
        public void run(){
            if(!mod.config.enabled || mod.config.serverTranslates){
                deliver(packet);
                return;
            }
            String target = own ? displayTarget() : mod.config.othersTarget;
            if(target == null || target.equalsIgnoreCase("off") || translatorDown()){
                deliver(packet);
                return;
            }
            GoogleTranslator.TranslateResult r = translate(text, target, own ? mod.config.source : "auto");
            if(r == null){
                translatorDownUntil = System.currentTimeMillis() + FAILURE_COOLDOWN_MS;
                Log.err("[Translator] Receive-translate failed for: " + text);
                deliver(packet);
                return;
            }
            String replacement;
            if(r.translation.isEmpty() || r.detectedLang.equalsIgnoreCase(target)){
                replacement = tag(r.detectedLang, target, mod.config.showDetectedLang) + text;
            }else{
                replacement = build(text, r.translation, r.detectedLang, target, mod.config.showDetectedLang);
            }
            String display = replaceRegion(packet.message, packet.unformatted, replacement);
            SendMessageCallPacket2 out = new SendMessageCallPacket2();
            out.message = display;
            out.unformatted = packet.unformatted;
            out.playersender = packet.playersender;
            deliver(out);
        }
    }

    private void deliver(SendMessageCallPacket2 packet){
        if(packet == null){
            return;
        }
        Core.app.post(() -> NetClient.sendMessage(packet.message, packet.unformatted, packet.playersender));
    }

    /** Replaces the given region of the formatted message, keeping everything before it (sender name etc.). */
    private static String replaceRegion(String message, String region, String replacement){
        if(message == null || region == null || region.isEmpty()){
            return message;
        }
        int idx = message.indexOf(region);
        if(idx < 0){
            Log.warn("[Translator] Could not locate message text in the formatted message; showing it as-is (the server may have reformatted it): " + message);
            return message;
        }
        return message.substring(0, idx) + replacement + message.substring(idx + region.length());
    }

    //------------------------------------------------------------------------
    // shared pipeline
    //------------------------------------------------------------------------

    private void enqueue(Runnable task){
        boolean overflow = false;
        synchronized(pending){
            if(pending.size() >= MAX_PENDING){
                overflow = true;
            }else{
                pending.add(task);
                if(!running){
                    running = true;
                    exec.execute(this::workLoop);
                }
            }
        }
        if(overflow){
            translatorDownUntil = System.currentTimeMillis() + FAILURE_COOLDOWN_MS;
            Log.warn("[Translator] Translation queue full; sending message untranslated.");
            if(task instanceof SendTask){
                SendTask sendTask = (SendTask)task;
                sendRaw(sendTask.packet, sendTask.reliable);
            }else if(task instanceof ReceiveTask){
                deliver(((ReceiveTask)task).packet);
            }
        }
    }

    private void workLoop(){
        while(true){
            Runnable task;
            synchronized(pending){
                if(pending.isEmpty()){
                    running = false;
                    return;
                }
                task = pending.remove(0);
            }
            try{
                task.run();
            }catch(Exception e){
                Log.err("[Translator] Client translation task failed.", e);
            }
        }
    }

    //------------------------------------------------------------------------
    // helpers
    //------------------------------------------------------------------------

    private GoogleTranslator.TranslateResult translate(String text, String target, String source){
        try{
            return mod.translator.translate(text, source, target);
        }catch(Exception e){
            Log.err("[Translator] Translation error.", e);
            return null;
        }
    }

    /** True while translation is temporarily halted after a failure; recovers automatically. */
    private boolean translatorDown(){
        return System.currentTimeMillis() < translatorDownUntil;
    }

    /** Language the local player's own messages are shown in: their outgoing target, or "off" when translation of their messages is disabled. */
    private String displayTarget(){
        return mod.config.target.equalsIgnoreCase("off") ? "off" : mod.config.target;
    }

    private static boolean alreadyTagged(String text){
        if(text == null || text.isEmpty()){
            return false;
        }
        if(TranslationMarker.unwrap(text) != null){
            return true;
        }
        Matcher m = TAGGED.matcher(text);
        return m.find();
    }

    /** Removes a parenthesised translation tail ("..." followed by " (translation)") from a formatted message, leaving everything before it. */
    private static String stripTrailingParens(String text){
        Matcher m;
        while((m = PAREN_TAIL.matcher(text)).matches()){
            text = m.group(1);
        }
        return text;
    }

    private static boolean hasTrailingParens(String cleaned){
        return PAREN_TAIL.matcher(cleaned).matches();
    }

    /** "[XX[]\u2192[YY[] original [#XXYYZZ][gray] (translation)[]" */
    private static String build(String original, String translation, String detected, String target, boolean showTag){
        return tag(detected, target, showTag) + original + TranslationMarker.wrap(original, translation, TranslatorPlugin.langIndex(target));
    }

    private static String tag(String detected, String target, boolean showTag){
        if(!showTag){
            return "";
        }
        return "[cyan]" + detected.toUpperCase() + "[]\u2192[" + target.toUpperCase() + "[] ";
    }
}