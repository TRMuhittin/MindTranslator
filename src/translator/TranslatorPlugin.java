package translator;

import arc.Events;
import arc.files.Fi;
import arc.func.Cons;
import arc.scene.ui.CheckBox;
import arc.scene.ui.Dialog;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import arc.util.io.ByteBufferInput;
import arc.util.io.ByteBufferOutput;
import arc.util.io.Reads;
import arc.util.io.Writes;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import mindustry.Vars;
import mindustry.core.NetServer;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.Call;
import mindustry.gen.Icon;
import mindustry.gen.Player;
import mindustry.graphics.Pal;
import mindustry.mod.Mod;
import mindustry.ui.Styles;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TranslatorPlugin extends Mod{

    /** "[XX[]\u2192[YY[] original (translation)" as broadcast by modded clients. */
    private static final Pattern SERVER_TAGGED = Pattern.compile("^(\\[[^\\]]*\\])?([A-Za-z]{2,4})\\[\\]\u2192\\[([A-Za-z]{2,4})\\[\\] (.*)$");
    /** "original[gray] (translation)[]" suffix added by translated broadcasts. */
    private static final Pattern TRANSLATED_SUFFIX = Pattern.compile("^(.*)(\\[gray\\] \\(.*?\\)\\[\\])$");

    private static final String[][] LANGS = {
        {"tr", "Turkish"},
        {"en", "English"},
        {"ru", "Russian"},
        {"de", "German"},
        {"fr", "French"},
        {"es", "Spanish"},
        {"it", "Italian"},
        {"pt", "Portuguese"},
        {"nl", "Dutch"},
        {"pl", "Polish"},
        {"uk", "Ukrainian"},
        {"el", "Greek"},
        {"ar", "Arabic"},
        {"zh", "Chinese"},
        {"ja", "Japanese"},
        {"ko", "Korean"},
        {"hi", "Hindi"},
        {"sv", "Swedish"},
        {"cs", "Czech"},
        {"fi", "Finnish"},
        {"no", "Norwegian"},
        {"id", "Indonesian"},
        {"th", "Thai"},
        {"ro", "Romanian"},
        {"bg", "Bulgarian"}
    };

    private static final Seq<String> LANG_CODES = Seq.with(
        "tr", "en", "ru", "de", "fr", "es", "it", "pt", "nl", "pl", "uk", "el", "ar",
        "zh", "ja", "ko", "hi", "sv", "cs", "fi", "no", "id", "th", "ro", "bg");

    private static final Seq<String> GLOBAL_SOURCE_CODES = Seq.with("auto").addAll(LANG_CODES);
    private static final Seq<String> PERSONAL_TARGET_CODES = Seq.with("off").addAll(LANG_CODES);
    private static final Seq<String> PERSONAL_SOURCE_CODES = Seq.with("auto").addAll(LANG_CODES);

    private static TranslatorPlugin instance;

    final GoogleTranslator translator = new GoogleTranslator();
    volatile TranslatorConfig config;

    public static TranslatorPlugin get(){
        return instance;
    }

    @Override
    public void init(){
        instance = this;
        config = loadConfig(getConfigFolder());
        Log.info("[Translator] Chat translation is " + (config.enabled ? "enabled" : "disabled") + ".");

        Vars.netServer.admins.addChatFilter((player, message) -> {
            if(player == null || message == null || !config.enabled){
                return message;
            }
            TranslatorConfig.PlayerSetting ps = config.players.get(player.uuid());
            if(ps != null && ps.disabled){
                return message;
            }
            String text = clean(message);
            int min = config.minMessageLength;
            if(ps != null && ps.minLength > 0){
                min = ps.minLength;
            }
            if(text.length() < min){
                return message;
            }
            //keep the message as sent: client translation tags are preserved so vanilla clients see the translated version
            return message;
        });

        //translates the broadcast/displayed chat into the local player's "incoming" language
        NetServer.ChatFormatter previousFormatter = Vars.netServer.chatFormatter;
        Vars.netServer.chatFormatter = (player, message) -> {
            String formatted = previousFormatter.format(player, message);
            if(message == null || !config.enabled){
                return formatted;
            }
            //the local player's own messages are already translated by their client's outgoing pipeline
            if(Vars.player != null && player == Vars.player){
                return formatted;
            }
            String target = incomingTargetForLocal();
            if(target == null || target.equalsIgnoreCase("off")){
                return formatted;
            }
            String text = clean(message);
            Matcher tagged = SERVER_TAGGED.matcher(message);
            if(tagged.matches()){
                if(tagged.group(3).equalsIgnoreCase(target)){
                    return formatted;
                }
                text = tagged.group(4);
            }
            Matcher suffixed = TRANSLATED_SUFFIX.matcher(text);
            if(suffixed.matches()){
                text = suffixed.group(1);
            }
            text = clean(text);
            if(text.isEmpty() || text.startsWith("/") || text.length() < incomingMinForLocal()){
                return formatted;
            }
            GoogleTranslator.TranslateResult result = translator.translate(text, target, "auto");
            if(result == null || result.translation.isEmpty() || result.detectedLang.equalsIgnoreCase(target)){
                return formatted;
            }
            String tag = config.showDetectedLang
                ? "[cyan]" + result.detectedLang.toUpperCase() + "[]\u2192[" + target.toUpperCase() + "[] "
                : "";
            String replacement = tag + text + "[gray] (" + result.translation + ")[]";
            int idx = formatted.indexOf(message);
            if(idx < 0){
                return formatted;
            }
            return formatted.substring(0, idx) + replacement + formatted.substring(idx + message.length());
        };

        Vars.netServer.addBinaryPacketHandler("translator", (player, data) -> {
            if(player == null){
                return;
            }
            applyBinarySetting(player.uuid(), data);
        });

        if(!Vars.headless){
            Events.on(ClientLoadEvent.class, event -> {
                buildUi();
                ClientTranslateHandler.install(TranslatorPlugin.this);
            });
        }
    }

    private void buildUi(){
        ImageButton button = new ImageButton(Icon.settings, Styles.flati);
        button.setSize(Scl.scl(40f), Scl.scl(40f));
        button.clicked(() -> showSettingsDialog());
        button.update(() -> button.setPosition(Scl.scl(12f), Vars.ui.hudGroup.getHeight() - button.getHeight() - Scl.scl(12f)));
        Vars.ui.hudGroup.addChild(button);
        Log.info("[Translator] Settings button added to HUD.");
    }

    private void showSettingsDialog(){
        Dialog dialog = new Dialog("[gold]Translation Settings[]");
        Table content = new Table();

        if(isGlobalEditor()){
            content.add("[orange]Server Settings[]").colspan(2).left().pad(8);
            content.row();

            CheckBox enable = new CheckBox("Translation enabled");
            enable.setChecked(config.enabled);
            enable.changed(() -> {
                config.enabled = enable.isChecked();
                saveConfig();
                showInfoToast("[green]Chat translation is now " + (config.enabled ? "enabled" : "disabled") + ".");
            });
            content.add(enable).colspan(2).left().padBottom(10);
            content.row();

            langPicker(content, "Default language players' messages are translated to:", LANG_CODES, false, config.targetLang, code -> {
                config.targetLang = code;
                saveConfig();
                showInfoToast("[green]Default language for players' messages: " + langName(code) + ".");
            });

            langPicker(content, "Default language players write in:", GLOBAL_SOURCE_CODES, false, config.writeLang, code -> {
                config.writeLang = code;
                saveConfig();
                showInfoToast("[green]Default writing language: " + langName(code) + ".");
            });

            langPicker(content, "Default language incoming messages are translated to:", PERSONAL_TARGET_CODES, false, config.othersTargetLang, code -> {
                config.othersTargetLang = code;
                saveConfig();
                showInfoToast("[green]Default language for incoming messages: " + labelFor(code) + ".");
            });

            CheckBox labels = new CheckBox("Show language tags (TR\u2192EN)");
            labels.setChecked(config.showDetectedLang);
            labels.changed(() -> {
                config.showDetectedLang = labels.isChecked();
                saveConfig();
            });
            content.add(labels).colspan(2).left().padTop(12);
            content.row();

            content.add("Min. message length:").left().padTop(12);
            TextField minField = new TextField(String.valueOf(config.minMessageLength));
            minField.setFilter(TextField.TextFieldFilter.digitsOnly);
            minField.changed(() -> {
                if(minField.getText().isEmpty()){
                    return;
                }
                int value;
                try{
                    value = Integer.parseInt(minField.getText());
                }catch(Exception e){
                    return;
                }
                if(value < 1){
                    value = 1;
                }
                config.minMessageLength = value;
                saveConfig();
            });
            content.add(minField).width(100).left().padTop(12);
            content.row();
            content.image().color(Pal.gray).growX().pad(12);
            content.row();
        }

        content.add("[sky]Personal Settings[]").colspan(2).left().pad(8);
        content.row();

        TranslatorConfig.PlayerSetting ps = config.players.get(localUuid());
        String currentTarget = (ps == null || ps.disabled || ps.target.isEmpty()) ? "off" : ps.target;
        langPicker(content, "Language my messages are translated to:", PERSONAL_TARGET_CODES, false, currentTarget, code -> {
            TranslatorConfig.PlayerSetting s = settingOf(localUuid());
            if(code.equals("off")){
                s.disabled = true;
                s.target = "";
                showInfoToast("[scarlet]Your messages will not be translated; they will appear as written.");
            }else{
                s.disabled = false;
                s.target = code;
                showInfoToast("[green]Your messages will be translated to " + langName(code) + ".");
            }
            saveConfig();
            syncSetting(s);
        });

        String currentSource = (ps == null || ps.source.isEmpty()) ? "auto" : ps.source;
        langPicker(content, "Language I write in:", PERSONAL_SOURCE_CODES, false, currentSource, code -> {
            TranslatorConfig.PlayerSetting s = settingOf(localUuid());
            s.source = code.equals("auto") ? "" : code;
            showInfoToast("[green]Your writing language is now " + langName(code) + ".");
            saveConfig();
            syncSetting(s);
        });

        String currentOthers = (ps == null || ps.othersTarget.isEmpty()) ? config.othersTargetLang : ps.othersTarget;
        langPicker(content, "Language incoming messages are translated to:", PERSONAL_TARGET_CODES, false, currentOthers, code -> {
            TranslatorConfig.PlayerSetting s = settingOf(localUuid());
            s.othersTarget = code.equals("off") ? "off" : code;
            showInfoToast(code.equals("off")
                ? "[scarlet]Incoming messages will not be translated; they will appear in their original language."
                : "[green]Incoming messages will be translated to " + langName(code) + ".");
            saveConfig();
            syncSetting(s);
        });

        content.add("Min. message length to translate:").left().padTop(12);
        TextField minField = new TextField(String.valueOf(ps == null ? 0 : ps.minLength));
        minField.setFilter(TextField.TextFieldFilter.digitsOnly);
        minField.changed(() -> {
            if(minField.getText().isEmpty()){
                return;
            }
            int value;
            try{
                value = Integer.parseInt(minField.getText());
            }catch(Exception e){
                return;
            }
            if(value < 0){
                value = 0;
            }
            TranslatorConfig.PlayerSetting s = settingOf(localUuid());
            s.minLength = value;
            saveConfig();
            syncSetting(s);
        });
        content.add(minField).width(100).left().padTop(12).padLeft(8);
        content.row();
        content.add("[orange]0 means the server default (set in Server Settings above) is used.")
            .colspan(2).wrap().width(Scl.scl(400)).left().padTop(4);
        content.row();

        content.add("[orange]Tip: 'Auto' language detection can rarely be wrong; selecting your language manually improves accuracy.")
            .colspan(2).wrap().width(Scl.scl(400)).left().padTop(12);
        content.row();
ScrollPane outer = new ScrollPane(content, Styles.defaultPane);
        outer.setScrollingDisabled(false, false);
        dialog.cont.add(outer).width(Scl.scl(800f)).height(Scl.scl(800f)).pad(4);

        dialog.buttons.defaults().size(280, 60).pad(8);
        dialog.buttons.button("[scarlet]Close[]", Icon.cancel, dialog::hide);
        dialog.show();
    }

    private void langPicker(Table parent, String title, Seq<String> codes, boolean twoCols, String current, Cons<String> onSelect){
        parent.add(title).colspan(2).left().padTop(12);
        parent.row();

        Table grid = new Table();
        Seq<TextButton> all = new Seq<>();
        for(String code : codes){
            TextButton btn = new TextButton(labelFor(code), Styles.flatTogglet);
            btn.setChecked(code.equals(current));
            btn.clicked(() -> {
                for(TextButton b : all){
                    b.setChecked(false);
                }
                btn.setChecked(true);
                onSelect.get(code);
            });
            all.add(btn);
            grid.add(btn).size(380, 52).pad(4);
            grid.row();
        }

        ScrollPane pane = new ScrollPane(grid, Styles.defaultPane);
        pane.setScrollingDisabled(true, false);
        parent.add(pane).colspan(2).width(Scl.scl(400)).height(Scl.scl(320)).padTop(4);
        parent.row();
    }

    private static String labelFor(String code){
        if(code.equals("off")){
            return "[scarlet]Off";
        }
        if(code.equals("auto")){
            return "[yellow]Auto";
        }
        return langName(code);
    }

    private static String langName(String code){
        if(code == null || code.isEmpty() || code.equals("auto")){
            return "Auto";
        }
        for(String[] lang : LANGS){
            if(lang[0].equals(code)){
                return lang[1];
            }
        }
        return code;
    }

    private boolean isGlobalEditor(){
        return Vars.player != null && (!Vars.net.active() || Vars.player.admin);
    }

    private TranslatorConfig.PlayerSetting settingOf(String uuid){
        return config.players.computeIfAbsent(uuid, u -> new TranslatorConfig.PlayerSetting());
    }

    private String localUuid(){
        Player player = Vars.player;
        return player != null ? player.uuid() : "";
    }

    private String incomingTargetForLocal(){
        TranslatorConfig.PlayerSetting ps = config.players.get(localUuid());
        if(ps != null && !ps.othersTarget.isEmpty()){
            return ps.othersTarget;
        }
        return config.othersTargetLang;
    }

    private int incomingMinForLocal(){
        TranslatorConfig.PlayerSetting ps = config.players.get(localUuid());
        if(ps != null && ps.minLength > 0){
            return ps.minLength;
        }
        return config.minMessageLength;
    }

    private void showInfoToast(String text){
        Vars.ui.showInfoToast(text, 4f);
    }

    private void syncSetting(TranslatorConfig.PlayerSetting ps){
        if(!Vars.net.client()){
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocate(192);
        Writes writes = Writes.get(new ByteBufferOutput(buffer));
        writes.bool(ps.disabled);
        writes.str(ps.target);
        writes.str(ps.source);
        writes.str(ps.othersTarget);
        writes.i(ps.minLength);
        byte[] data = new byte[buffer.position()];
        buffer.flip();
        buffer.get(data);
        Call.clientBinaryPacketReliable("translator", data);
    }

    private void applyBinarySetting(String uuid, byte[] data){
        try{
            Reads reads = Reads.get(new ByteBufferInput(ByteBuffer.wrap(data)));
            boolean disabled = reads.bool();
            String target = reads.str();
            String source = reads.str();
            String othersTarget = reads.str();
            int minLength = reads.i();
            TranslatorConfig.PlayerSetting ps = config.players.computeIfAbsent(uuid, u -> new TranslatorConfig.PlayerSetting());
            ps.disabled = disabled;
            ps.target = target;
            ps.source = source;
            ps.othersTarget = othersTarget;
            ps.minLength = minLength;
            saveConfig();
            Log.info("[Translator] " + uuid + " settings updated (disabled: " + disabled + ", target: " + target
                + ", source: " + source + ", othersTarget: " + othersTarget + ", minLength: " + minLength + ").");
        }catch(Exception e){
            Log.err("[Translator] Invalid settings packet received.", e);
        }
    }

    private synchronized void saveConfig(){
        Fi file = getConfigFolder().child("config.json");
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"enabled\": ").append(config.enabled).append(",\n");
        sb.append("  \"targetLang\": \"").append(config.targetLang).append("\",\n");
        sb.append("  \"writeLang\": \"").append(config.writeLang).append("\",\n");
        sb.append("  \"othersTargetLang\": \"").append(config.othersTargetLang).append("\",\n");
        sb.append("  \"minMessageLength\": ").append(config.minMessageLength).append(",\n");
        sb.append("  \"showDetectedLang\": ").append(config.showDetectedLang).append(",\n");
        sb.append("  \"players\": {\n");
        boolean first = true;
        for(Map.Entry<String, TranslatorConfig.PlayerSetting> entry : config.players.entrySet()){
            if(!first){
                sb.append(",\n");
            }
            TranslatorConfig.PlayerSetting ps = entry.getValue();
            sb.append("    \"").append(entry.getKey()).append("\": { \"disabled\": ").append(ps.disabled)
                .append(", \"target\": \"").append(ps.target)
                .append("\", \"source\": \"").append(ps.source)
                .append("\", \"othersTarget\": \"").append(ps.othersTarget)
                .append("\", \"minLength\": ").append(ps.minLength).append(" }");
            first = false;
        }
        sb.append("\n  }\n}");
        file.writeString(sb.toString(), false);
    }

    private TranslatorConfig loadConfig(Fi configFolder){
        Fi file = configFolder.child("config.json");
        TranslatorConfig config = new TranslatorConfig();
        if(file.exists()){
            try{
                JsonValue value = new JsonReader().parse(file.readString());
                config.enabled = value.getBoolean("enabled", config.enabled);
                config.targetLang = value.getString("targetLang", config.targetLang);
                config.writeLang = value.getString("writeLang", config.writeLang);
                config.othersTargetLang = value.getString("othersTargetLang", config.othersTargetLang);
                config.minMessageLength = value.getInt("minMessageLength", config.minMessageLength);
                config.showDetectedLang = value.getBoolean("showDetectedLang", config.showDetectedLang);
                JsonValue players = value.get("players");
                if(players != null){
                    for(JsonValue p = players.child; p != null; p = p.next){
                        TranslatorConfig.PlayerSetting ps = new TranslatorConfig.PlayerSetting();
                        ps.disabled = p.getBoolean("disabled", false);
                        ps.target = p.getString("target", "");
                        ps.source = p.getString("source", "");
                        ps.othersTarget = p.getString("othersTarget", "");
                        ps.minLength = p.getInt("minLength", 0);
                        config.players.put(p.name(), ps);
                    }
                }
            }catch(Exception e){
                Log.err("[Translator] Could not read config.json, using default settings.", e);
            }
        }else{
            file.writeString("{\n" +
                "  \"enabled\": true,\n" +
                "  \"targetLang\": \"en\",\n" +
                "  \"writeLang\": \"auto\",\n" +
                "  \"othersTargetLang\": \"off\",\n" +
                "  \"minMessageLength\": 3,\n" +
                "  \"showDetectedLang\": true,\n" +
                "  \"players\": {}\n" +
                "}", false);
            Log.info("[Translator] config.json created with default settings.");
        }
        return config;
    }

    public static String clean(String text){
        if(text == null){
            return "";
        }
        if(text.length() >= 2){
            char c1 = text.charAt(text.length() - 2);
            char c2 = text.charAt(text.length() - 1);
            if(c1 >= 0xF80 && c1 <= 0x107F && c2 >= 0xF80 && c2 <= 0x107F){
                text = text.substring(0, text.length() - 2);
            }
        }
        StringBuilder sb = new StringBuilder(text.length());
        for(int i = 0; i < text.length(); i++){
            char c = text.charAt(i);
            if(c < 0x20 || (c >= 0x7F && c <= 0x9F)){
                continue;
            }
            if(Character.isHighSurrogate(c) || Character.isLowSurrogate(c)){
                if(Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))){
                    sb.append(c).append(text.charAt(++i));
                }
                continue;
            }
            switch(c){
                case 0x00A0:
                case 0x00AD:
                case 0x200B: case 0x200C: case 0x200D: case 0x200E: case 0x200F:
                case 0x202A: case 0x202B: case 0x202C: case 0x202D: case 0x202E:
                case 0x2060: case 0x2061: case 0x2062: case 0x2063: case 0x2064:
                case 0x2066: case 0x2067: case 0x2068: case 0x2069:
                case 0xFEFF:
                    continue;
            }
            if(c >= 0xFDD0 && c <= 0xFDEF || c >= 0xFFF0 && c <= 0xFFFF){
                continue;
            }
            sb.append(c);
        }
        return Strings.stripColors(sb.toString());
    }
}