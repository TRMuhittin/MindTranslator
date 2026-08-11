package translator;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Open translation-identification protocol shared with other translation mods.
 * Translated messages carry an invisible marker right before the gray
 * translation: {@code original [#XXYYZZ][gray] (translation)[]}, where XX is
 * the translating mod's id, YY the index of the language the text was
 * translated into (TranslatorPlugin.LANGS order) and ZZ a checksum of the
 * cleaned original text. A reader seeing a marker whose language differs from
 * its own target discards the foreign translation and re-translates the
 * original; a matching language means no re-translation. Mod ids are listed in
 * the README.
 */
public class TranslationMarker{

    /** This mod's protocol id. */
    public static final int MOD_ID = 0x01;

    /** {@code original [#XXYYZZ][gray] (translation)[]} */
    private static final Pattern MARKED = Pattern.compile("^(.*?)\\[#([0-9A-Fa-f]{6})\\]\\[gray\\] \\((.*?)\\)\\[\\]$");
    /** Optional {@code TR\u2192EN} tag prefix inside the original portion. */
    private static final Pattern TAG_PREFIX = Pattern.compile("^(\\[[^\\]]*\\])?[A-Za-z]{2,4}\\[\\]\u2192\\[[A-Za-z]{2,4}\\[\\] ?");

    public static class Parsed{
        public final String original;
        public final String translation;
        public final int modId;
        public final int langIndex;

        public Parsed(String original, String translation, int modId, int langIndex){
            this.original = original;
            this.translation = translation;
            this.modId = modId;
            this.langIndex = langIndex;
        }
    }

    /** Builds the invisible marker plus gray translation: {@code [#XXYYZZ][gray] (translation)[]}. */
    public static String wrap(String original, String translation, int langIndex){
        return "[#" + colorFor(original, langIndex) + "][gray] (" + translation + ")[]";
    }

    /** The 6-digit marker color for the given original text and target language index. */
    public static String colorFor(String original, int langIndex){
        return String.format("%02X%02X%02X", MOD_ID, langIndex & 0xFF, checksum(original));
    }

    /** Parses a marked translation; returns {@code null} when absent or unverifiable. */
    public static Parsed unwrap(String message){
        if(message == null){
            return null;
        }
        Matcher m = MARKED.matcher(message);
        if(!m.matches()){
            return null;
        }
        String original = stripTag(m.group(1));
        int color = Integer.parseInt(m.group(2), 16);
        int modId = (color >> 16) & 0xFF;
        int langIndex = (color >> 8) & 0xFF;
        if(langIndex < 0 || langIndex >= TranslatorPlugin.langCount() || (color & 0xFF) != checksum(original)){
            return null;
        }
        return new Parsed(original, m.group(3), modId, langIndex);
    }

    private static String stripTag(String text){
        Matcher m = TAG_PREFIX.matcher(text);
        return m.find() ? text.substring(m.end()) : text;
    }

    /** 8-bit FNV-1a checksum of the cleaned original text (protocol spec, see README). */
    private static int checksum(String text){
        int hash = 0x811C9DC5;
        for(byte b : TranslatorPlugin.clean(text).getBytes(StandardCharsets.UTF_8)){
            hash ^= (b & 0xFF);
            hash *= 0x01000193;
        }
        return hash & 0xFF;
    }
}