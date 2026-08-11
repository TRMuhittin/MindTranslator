package translator;

/** Single local player profile; the mod is entirely client-side. */
public class TranslatorConfig{

    public boolean enabled = true;
    public String target = "off";
    public String source = "auto";
    public String othersTarget = "off";
    public int minLength = 3;
    public boolean showDetectedLang = true;
    public boolean serverTranslates = false;
}