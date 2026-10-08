package uz.jtscorp.filesync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.Texts.Language;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextsTest {

    private static final Pattern PLURAL_FORM = Pattern.compile("\\.(one|few)$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d}");

    @AfterEach
    void backToTheDefault() {
        Texts.use(Language.UZ);
    }

    /** A language's own plural forms aside, the three files must hold the same sentences. */
    private static Set<String> baseKeys(Language language) {
        Set<String> keys = new TreeSet<>();
        for (String key : Texts.bundle(language).keySet()) {
            if (!PLURAL_FORM.matcher(key).find()) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @Test
    void everyLanguageHasEverySentenceWithTheSamePlaceholders() {
        Set<String> uzbek = baseKeys(Language.UZ);
        for (Language language : List.of(Language.RU, Language.EN)) {
            assertEquals(uzbek, baseKeys(language), language + " keys");
            for (String key : Texts.bundle(language).keySet()) {
                String base = PLURAL_FORM.matcher(key).replaceAll("");
                assertEquals(placeholders(Texts.bundle(Language.UZ).getString(base)),
                        placeholders(Texts.bundle(language).getString(key)), language + " " + key);
            }
        }
    }

    /** A mistyped key shows up on screen as the key itself; nothing else would catch it. */
    @Test
    void everyKeyTheCodeAndTheFxmlNameExists() throws IOException {
        Pattern inJava = Pattern.compile("Texts\\.[tn]\\(\"([^\"]+)\"\\s*[,)]");
        Pattern inFxml = Pattern.compile("=\"%([^\"]+)\"");
        Set<String> known = Texts.bundle(Language.UZ).keySet();
        List<String> unknown = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                Pattern pattern = name.endsWith(".java") ? inJava : name.endsWith(".fxml") ? inFxml : null;
                if (pattern == null) {
                    continue;
                }
                Matcher matcher = pattern.matcher(Files.readString(file));
                while (matcher.find()) {
                    if (!known.contains(matcher.group(1))) {
                        unknown.add(name + ": " + matcher.group(1));
                    }
                }
            }
        }
        assertEquals(List.of(), unknown);
    }

    @Test
    void anApostropheDoesNotSwallowThePlaceholderAfterIt() {
        assertEquals("Profillarni o'qib bo'lmadi: disk", Texts.t("profiles.readFailed", "disk"));
        assertEquals("no.such.key", Texts.t("no.such.key"));
    }

    @Test
    void aCountPicksTheFormItsLanguageNeeds() {
        assertEquals("3 ta o'zgarish o'tkazildi", Texts.n("result.changes", 3));
        Texts.use(Language.EN);
        assertEquals("1 change transferred", Texts.n("result.changes", 1));
        assertEquals("3 changes transferred", Texts.n("result.changes", 3));

        assertEquals(".one", Texts.pluralSuffix(Language.RU, 21));
        assertEquals(".few", Texts.pluralSuffix(Language.RU, 3));
        assertEquals("", Texts.pluralSuffix(Language.RU, 11));
        assertEquals("", Texts.pluralSuffix(Language.RU, 14));
    }

    @Test
    void anUnknownLanguageCodeIsTheDefault() {
        assertEquals(Language.RU, Language.fromCode("ru"));
        assertEquals(Language.UZ, Language.fromCode(null));
        assertEquals(Language.UZ, Language.fromCode("xx"));
    }
}
