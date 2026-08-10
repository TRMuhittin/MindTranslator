package translator;

import java.util.LinkedHashMap;
import java.util.Map;

public class TranslatorConfig{
    public boolean enabled = true;
    public String targetLang = "en";
    public String othersTargetLang = "off";
    public String writeLang = "auto";
    public int minMessageLength = 3;
    public boolean showDetectedLang = true;
    public final Map<String, PlayerSetting> players = new LinkedHashMap<>();

    public static class PlayerSetting{
        public boolean disabled;
        public String target = "";
        public String othersTarget = "";
        public String source = "";
        public int minLength;
    }
}