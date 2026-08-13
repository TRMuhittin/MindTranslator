package translator;

/** Single local player profile; the mod is entirely client-side. Fields are volatile: they are
 * written by the UI thread and read by the translation executor thread. */
public class TranslatorConfig{

    public volatile boolean enabled = true;
    public volatile String target = "off";
    public volatile String source = "auto";
    public volatile String othersTarget = "off";
    public volatile int minLength = 3;
    public volatile boolean showDetectedLang = true;
    public volatile boolean serverTranslates = false;
}