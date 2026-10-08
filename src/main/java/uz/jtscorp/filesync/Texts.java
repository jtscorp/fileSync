package uz.jtscorp.filesync;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * Every sentence the app shows, looked up in {@code messages_<code>.properties}.
 *
 * <p>Placeholders {@code {0}}, {@code {1}} are replaced as plain text, not with
 * {@link java.text.MessageFormat}: there the apostrophe in Uzbek words (o', g') is a
 * quote character and swallows the placeholder after it.
 */
public final class Texts {

    public enum Language {
        UZ("uz", "O'zbekcha"), RU("ru", "Русский"), EN("en", "English");

        private final String code;
        private final String label;

        Language(String code, String label) {
            this.code = code;
            this.label = label;
        }

        public String code() {
            return code;
        }

        /** The language's own name, so it can be found by someone who reads no other. */
        public String label() {
            return label;
        }

        /** An unknown or missing code is the default, not an error: the app must still open. */
        public static Language fromCode(String code) {
            return Arrays.stream(values()).filter(language -> language.code.equals(code)).findFirst().orElse(UZ);
        }
    }

    private static final Map<Language, ResourceBundle> BUNDLES = new EnumMap<>(Language.class);
    private static volatile Language language = Language.UZ;

    private Texts() {}

    public static Language language() {
        return language;
    }

    /** Sentences already on screen stay as they are; the caller rebuilds what it shows. */
    public static void use(Language chosen) {
        language = chosen;
    }

    /** What an FXML's {@code %key} attributes are resolved against. */
    public static ResourceBundle bundle() {
        return bundle(language);
    }

    public static synchronized ResourceBundle bundle(Language of) {
        return BUNDLES.computeIfAbsent(of, Texts::load);
    }

    private static ResourceBundle load(Language of) {
        String file = "messages_" + of.code() + ".properties";
        try (InputStream in = Texts.class.getResourceAsStream(file)) {
            if (in == null) {
                throw new IllegalStateException(file + " is missing from the build");
            }
            return new PropertyResourceBundle(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(file + " could not be read", e);
        }
    }

    /** A missing key shows as the key itself: wrong, but visibly so, and never a crash mid-sync. */
    public static String t(String key, Object... args) {
        ResourceBundle bundle = bundle();
        return fill(bundle.containsKey(key) ? bundle.getString(key) : key, args);
    }

    /**
     * {@link #t} in the plural form the count needs: {@code key.one} or {@code key.few}
     * when the file has it, else {@code key}. {@code count} fills {@code {0}}.
     */
    public static String n(String key, long count, Object... more) {
        Object[] args = new Object[more.length + 1];
        args[0] = count;
        System.arraycopy(more, 0, args, 1, more.length);
        String form = key + pluralSuffix(language, count);
        return t(bundle().containsKey(form) ? form : key, args);
    }

    static String pluralSuffix(Language of, long count) {
        long n = Math.abs(count);
        return switch (of) {
            case UZ -> "";
            case EN -> n == 1 ? ".one" : "";
            case RU -> {
                long tens = n % 100;
                long ones = n % 10;
                if (ones == 1 && tens != 11) {
                    yield ".one";
                }
                yield ones >= 2 && ones <= 4 && (tens < 12 || tens > 14) ? ".few" : "";
            }
        };
    }

    private static String fill(String pattern, Object[] args) {
        String text = pattern;
        for (int i = 0; i < args.length; i++) {
            text = text.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return text;
    }
}
