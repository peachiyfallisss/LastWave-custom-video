package com.lastwave.app.data.lossless;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Recording identity is a gate, never a quality preference. No Android dependencies. */
public final class RecordingIdentity {
    private RecordingIdentity() {}

    private static final String[][] MARKERS = {
        {"live", "(?:[\\[(][^\\])]*\\blive\\b|[-–—:]\\s*live(?:\\s*$|\\s*[-–—(]|\\s+(?:at|from|in|on|version|recording|performance)\\b)|\\blive\\s+(?:at|from|in|on)\\b|\\blive\\s+(?:version|recording|performance)\\b|ライブ(?:音源|版|バージョン)|ライヴ(?:音源|版)|现场版|現場版|ao vivo|en vivo)"},
        {"acoustic", "\\bacoustic\\b|アコースティック|不插电|不插電"},
        {"instrumental", "\\binstrumental\\b|\\boff vocal\\b|インストゥルメンタル|伴奏版"},
        {"karaoke", "\\bkaraoke\\b|カラオケ"},
        {"cover", "\\bcover(?: version)?\\b|カバー(?:版)?|翻唱"},
        {"remix", "\\bremix(?:ed)?\\b|リミックス"},
        {"demo", "\\bdemo\\b|デモ版"},
        {"slowed", "\\bslowed\\b|\\bslow(?:ed)? (?:down|version)\\b"},
        {"reverb", "\\breverb\\b"},
        {"sped-up", "\\bsped[ -]?up\\b|\\bspeed[ -]?up\\b|\\bnightcore\\b"},
        {"radio-edit", "\\bradio (?:edit|version|mix)\\b"},
        {"extended", "\\bextended(?: version| mix| edit)?\\b"},
        {"remaster", "\\bremaster(?:ed)?\\b|リマスター"},
        {"clean", "(?:[\\[(]\\s*clean\\b|[-–—:]\\s*clean(?:\\s*$|\\s+version\\b)|\\bclean (?:version|edit)\\b)"},
        {"mashup", "\\bmash[ -]?up\\b"},
    };

    public static Set<String> variants(String title, String album, String artist) {
        String raw = (title == null ? "" : title) + " " + (album == null ? "" : album);
        if (artist != null && !artist.trim().isEmpty()) {
            raw = raw.replaceFirst("(?iu)^\\s*" + Pattern.quote(artist.trim()) + "\\s*[-–—:]\\s*", "");
        }
        raw = normalized(raw);
        Set<String> result = new LinkedHashSet<>();
        for (String[] marker : MARKERS) {
            if (Pattern.compile(marker[1], Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(raw).find()) {
                result.add(marker[0]);
            }
        }
        // Albums often consist solely of the word Live; titles such as Live Forever do not.
        if (album != null && Pattern.compile("\\blive\\b|ライブ|ライヴ").matcher(normalized(album)).find()) result.add("live");
        return result;
    }

    public static boolean sameVersion(String title, String album, String candidateTitle,
                                      String candidateAlbum, String artist) {
        return variants(title, album, artist).equals(variants(candidateTitle, candidateAlbum, artist));
    }

    private static String normalized(String raw) {
        return Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
