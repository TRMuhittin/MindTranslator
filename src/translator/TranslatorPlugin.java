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
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.Icon;
import mindustry.mod.Mod;
import mindustry.ui.Styles;

/**
 * Fully client-side chat translator. Outgoing chat is translated before it is
 * sent, incoming chat is translated before it is displayed; nothing runs on
 * servers.
 */
public class TranslatorPlugin extends Mod{

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

    private static final Seq<String> TARGET_CODES = Seq.with("off").addAll(LANG_CODES);
    private static final Seq<String> SOURCE_CODES = Seq.with("auto").addAll(LANG_CODES);

    private static TranslatorPlugin instance;

    final GoogleTranslator translator = new GoogleTranslator();
    volatile TranslatorConfig config;

    public static TranslatorPlugin get(){
        return instance;
    }

    /** Language index in the frozen protocol table (see TranslationMarker and README). */
    public static int langIndex(String code){
        return LANG_CODES.indexOf(code);
    }

    public static int langCount(){
        return LANG_CODES.size;
    }

    @Override
    public void init(){
        instance = this;
        config = loadConfig(getConfigFolder());
        Log.info("[Translator] Chat translation is " + (config.enabled ? "enabled" : "disabled") + ".");

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

        CheckBox enable = new CheckBox("Translation enabled");
        enable.setChecked(config.enabled);
        enable.changed(() -> {
            config.enabled = enable.isChecked();
            saveConfig();
            showInfoToast("[green]Chat translation is now " + (config.enabled ? "enabled" : "disabled") + ".");
        });
        content.add(enable).colspan(2).left().padBottom(10);
        content.row();

        langPicker(content, "Language my messages are translated to:", TARGET_CODES, false, config.target, code -> {
            config.target = code;
            saveConfig();
            showInfoToast(code.equals("off")
                ? "[scarlet]Your messages will not be translated; they will appear as written."
                : "[green]Your messages will be translated to " + langName(code) + ".");
        });

        langPicker(content, "Language I write in:", SOURCE_CODES, false, config.source, code -> {
            config.source = code;
            showInfoToast("[green]Your writing language is now " + langName(code) + ".");
            saveConfig();
        });

        langPicker(content, "Language incoming messages are translated to:", TARGET_CODES, false, config.othersTarget, code -> {
            config.othersTarget = code;
            showInfoToast(code.equals("off")
                ? "[scarlet]Incoming messages will not be translated; they will appear in their original language."
                : "[green]Incoming messages will be translated to " + langName(code) + ".");
            saveConfig();
        });

        content.add("Min. message length to translate:").left().padTop(12);
        TextField minField = new TextField(String.valueOf(config.minLength));
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
            config.minLength = value;
            saveConfig();
        });
        content.add(minField).width(100).left().padTop(12).padLeft(8);
        content.row();

        CheckBox labels = new CheckBox("Show language tags (TR\u2192EN)");
        labels.setChecked(config.showDetectedLang);
        labels.changed(() -> {
            config.showDetectedLang = labels.isChecked();
            saveConfig();
        });
        content.add(labels).colspan(2).left().padTop(12);
        content.row();

        CheckBox serverSide = new CheckBox("Server already translates (mod stays passive)");
        serverSide.setChecked(config.serverTranslates);
        serverSide.changed(() -> {
            config.serverTranslates = serverSide.isChecked();
            saveConfig();
            showInfoToast(config.serverTranslates
                ? "[cyan]Passive mode: the server handles all translation; your messages are sent as written."
                : "[cyan]Active mode: the mod translates your messages and incoming ones.");
        });
        content.add(serverSide).colspan(2).left().padTop(12);
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

    private void showInfoToast(String text){
        Vars.ui.showInfoToast(text, 4f);
    }

    private synchronized void saveConfig(){
        Fi file = getConfigFolder().child("config.json");
        String json = "{\n" +
            "  \"enabled\": " + config.enabled + ",\n" +
            "  \"target\": \"" + config.target + "\",\n" +
            "  \"source\": \"" + config.source + "\",\n" +
            "  \"othersTarget\": \"" + config.othersTarget + "\",\n" +
            "  \"minLength\": " + config.minLength + ",\n" +
            "  \"showDetectedLang\": " + config.showDetectedLang + ",\n" +
            "  \"serverTranslates\": " + config.serverTranslates + "\n" +
            "}";
        file.writeString(json, false);
    }

    private TranslatorConfig loadConfig(Fi configFolder){
        Fi file = configFolder.child("config.json");
        TranslatorConfig config = new TranslatorConfig();
        if(file.exists()){
            try{
                JsonValue value = new JsonReader().parse(file.readString());
                config.enabled = value.getBoolean("enabled", config.enabled);
                config.target = value.getString("target", config.target);
                config.source = value.getString("source", value.getString("writeLang", config.source));
                config.othersTarget = value.getString("othersTarget", value.getString("othersTargetLang", config.othersTarget));
                config.minLength = value.getInt("minLength", value.getInt("minMessageLength", config.minLength));
                config.showDetectedLang = value.getBoolean("showDetectedLang", config.showDetectedLang);
                config.serverTranslates = value.getBoolean("serverTranslates", config.serverTranslates);
            }catch(Exception e){
                Log.err("[Translator] Could not read config.json, using default settings.", e);
            }
        }else{
            file.writeString("{\n" +
                "  \"enabled\": true,\n" +
                "  \"target\": \"off\",\n" +
                "  \"source\": \"auto\",\n" +
                "  \"othersTarget\": \"off\",\n" +
                "  \"minLength\": 3,\n" +
                "  \"showDetectedLang\": true,\n" +
                "  \"serverTranslates\": false\n" +
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