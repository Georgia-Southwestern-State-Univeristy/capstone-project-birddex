package com.birddex.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class ContentFilter {

    // Using a Set for faster lookups and no duplicates
    private static final Set<String> NSFW_WORDS = new HashSet<>(Arrays.asList(
            "fuck", "shit", "asshole", "bitch", "cunt", "dick", "pussy", "bastard",
            "slut", "whore", "sex", "porn", "pornography", "xxx", "nsfw", "erotic",
            "hardcore", "softcore", "adult content",

            "penis", "vagina", "clitoris", "testicles", "scrotum", "boobs", "tits",
            "ass", "butt", "breasts", "genitals", "cock", "balls",

            "fucking", "fucked", "suck", "sucking", "blowjob", "handjob",
            "masturbate", "masturbation", "orgasm", "cum", "cumming", "ejaculate",
            "penetrate", "penetration",

            "hookup", "one night stand", "booty call", "smash", "get laid",
            "bang", "doggy style", "69", "deepthroat",

            "onlyfans", "camgirl", "camsite", "stripper", "escort", "brothel",
            "fetish", "bdsm", "kink", "kinky", "dominatrix", "submissive",

            "anal", "rimjob", "rimming", "threesome", "gangbang", "orgy",

            "horny", "wet", "hard", "turned on", "lust", "aroused",

            "nude", "nudes", "naked", "topless", "lewd", "explicit", "send nudes",

            "milf", "dilf", "sugar daddy", "sugar baby",

            "creampie", "facial", "spitroast",

            "pegging", "fingering", "scissoring", "grinding",

            "adult video", "sex tape",

            "cam model", "live cam", "webcam girl", "webcam model",

            "foot fetish", "roleplay sex",

            "quickie", "hooking up", "sleep together",

            "g string", "lingerie", "thong"
    ));

    // Regex to catch simple obfuscations like f*ck, f**k, f_ck etc.
    private static final Pattern OBFUSCATED_FUCK =
            Pattern.compile("f[\\W_]*u[\\W_]*c[\\W_]*k");

    /**
     * Checks if the given text contains any NSFW words.
     */
    public static boolean containsInappropriateContent(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }

        String lower = text.toLowerCase();

        // 1. Check exact words in original text
        if (hasMatch(lower, false)) return true;

        // 2. Normalize leetspeak and repeated chars (e.g., s3x -> sex, fuuuuuck -> fuck)
        String normalized = normalize(lower);
        
        // 3. Check normalized text against normalized forbidden words
        if (hasMatch(normalized, true)) return true;

        // 4. Check for simple obfuscation (example: f*ck)
        if (OBFUSCATED_FUCK.matcher(lower).find()) {
            return true;
        }

        return false;
    }

    private static boolean hasMatch(String input, boolean normalizedMode) {
        for (String word : NSFW_WORDS) {
            String target = normalizedMode ? normalize(word) : word;
            
            // Skip normalized checks for very short words to avoid false positives (e.g., "ass" -> "as")
            if (normalizedMode && target.length() < 3) continue;

            String regex = "\\b" + Pattern.quote(target) + "\\b";
            if (Pattern.compile(regex).matcher(input).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalizes text for better filtering.
     * 1. Leetspeak normalization (3 -> e, 0 -> o, etc.)
     * 2. Repeated character cleanup (fuuuuck -> fuck)
     */
    private static String normalize(String text) {
        if (text == null) return "";

        // 1. Leetspeak normalization
        String normalized = text.toLowerCase()
                .replace('0', 'o')
                .replace('1', 'i') // handles 'l' or 'i'
                .replace('3', 'e')
                .replace('4', 'a')
                .replace('5', 's')
                .replace('7', 't')
                .replace('8', 'b')
                .replace('@', 'a')
                .replace('$', 's')
                .replace('!', 'i');

        // 2. Repeated character cleanup (e.g., fuuuuck -> fuck)
        // This regex reduces any sequence of identical characters to a single character.
        return normalized.replaceAll("(.)\\1+", "$1");
    }
}
