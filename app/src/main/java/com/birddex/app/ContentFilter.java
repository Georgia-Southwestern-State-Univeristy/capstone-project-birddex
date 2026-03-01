package com.birddex.app;

import android.content.Context;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class ContentFilter {

    private static final Set<String> NSFW_WORDS = new HashSet<>(Arrays.asList(
            // --- Profanity & Core NSFW ---
            "fuck", "shit", "asshole", "bitch", "cunt", "dick", "pussy", "bastard",
            "slut", "whore", "sex", "porn", "pornography", "xxx", "nsfw", "erotic",
            "hardcore", "softcore", "adult content", "motherfucker", "cocksucker",
            "cockfucker", "jackass", "dipshit", "dumbass", "dumbshit", "goddamn", "piss",

            // --- Anatomy & Sexual Terms ---
            "penis", "vagina", "clitoris", "testicles", "scrotum", "boobs", "tits",
            "ass", "butt", "breasts", "genitals", "cock", "balls", "clit", "labia",
            "erection", "masturbate", "masturbation", "orgasm", "cum", "cumming",
            "ejaculate", "penetrate", "penetration", "intercourse", "coitus",
            "blowjob", "handjob", "deepthroat", "rimjob", "rimming", "anal",

            // --- Fetishes & Adult Services ---
            "onlyfans", "camgirl", "camsite", "stripper", "escort", "brothel",
            "fetish", "bdsm", "kink", "kinky", "dominatrix", "submissive",
            "bondage", "dildo", "vibrator", "pegging", "fingering", "scissoring",
            "grinding", "foot fetish", "roleplay sex",

            // --- Explicit Content & Scenarios ---
            "nude", "nudes", "naked", "topless", "lewd", "explicit", "send nudes",
            "milf", "dilf", "sugar daddy", "sugar baby", "creampie", "facial",
            "spitroast", "threesome", "foursome", "gangbang", "orgy", "hentai",
            "doujinshi", "adult video", "sex tape",

            // --- Dating & Hookups ---
            "hookup", "one night stand", "booty call", "smash", "get laid",
            "bang", "doggy style", "69", "quickie", "hooking up", "sleep together",

            // --- Slurs & Hate Speech ---
            "nigger", "kike", "faggot", "dyke", "retard", "tranny", "spic", "chink",
            "wetback", "coon", "nazi", "hitler", "negro", "beaner", "gook", "gypo",

            // --- Violence & Harm ---
            "kill yourself", "suicide", "murder", "rape", "molest", "pedophile",
            "underage", "terrorist", "massacre", "genocide", "kys",

            // --- Illegal Substances ---
            "cocaine", "heroin", "meth", "fentanyl", "oxycodone", "xanax", "percocet",
            "crack cocaine", "mdma", "ecstasy",

            // --- Controversial / Political ---
            "9-11", "epstein", "white power", "black lives matter", "magam", "maga",

            // --- Miscellaneous Clothing ---
            "g string", "lingerie", "thong"
    ));

    // WHITELIST: Real bird names that often trigger profanity filters
    private static final Set<String> BIRD_WHITELIST = new HashSet<>(Arrays.asList(
            "tit", "tits", "booby", "boobies", "shag", "woodcock", "dickcissel",
            "bushtit", "cock", "ass", "blue tit", "great tit", "tufted titmouse"
    ));

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}");
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b(\\+?\\d{1,3}[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+\\s?");
    private static final Pattern SPAM_REPETITION_PATTERN = Pattern.compile("(.)\\1{4,}");
    
    // Detects Credit Card patterns (13 to 16 digits)
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile("\\b(?:\\d[ -]*?){13,16}\\b");
    
    // Detects Zalgo / Glitch text (excessive combining marks that break UI)
    private static final Pattern ZALGO_PATTERN = Pattern.compile("[\\u0300-\u036F\\u1DC0-\u1DFF\\u20D0-\u20FF\\uFE20-\uFE2F]{3,}");

    /**
     * Checks if the given text is safe to post.
     * Shows a Toast message if inappropriate content is found.
     */
    public static boolean isSafe(Context context, String text, String fieldName) {
        String result = getInappropriateReason(text);
        if (result != null) {
            String toastMessage;
            if (result.equals("inappropriate language")) {
                toastMessage = "Inappropriate language detected. Your " + fieldName.toLowerCase() + " cannot be posted.";
            } else if (result.equals("glitch text")) {
                toastMessage = "Formatting error detected. Please remove special symbols.";
            } else {
                toastMessage = fieldName + " contains " + result + ".";
            }
            Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /**
     * Returns a string describing why content is inappropriate, or null if it's safe.
     */
    public static String getInappropriateReason(String text) {
        if (text == null || text.trim().isEmpty()) return null;

        // 1. Check for Zalgo / Glitch text first (can break normalization)
        if (ZALGO_PATTERN.matcher(text).find()) return "glitch text";

        // 2. Normalize Unicode (converts accented chars like 'fûck' to 'fuck')
        String unicodeNormalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("[^\\p{ASCII}]", "");
        
        String lower = unicodeNormalized.toLowerCase();

        // 3. Check for Financial Data
        if (CREDIT_CARD_PATTERN.matcher(text).find()) return "sensitive financial data";

        // 4. Check for PII
        if (EMAIL_PATTERN.matcher(text).find()) return "an email address";
        if (PHONE_PATTERN.matcher(text).find()) return "a phone number";

        // 5. Check for Links
        if (URL_PATTERN.matcher(text).find()) return "external links";

        // 6. Check for Spam/Keyboard smashing
        if (SPAM_REPETITION_PATTERN.matcher(text).find()) return "excessive character repetition";

        // 7. Check for Language
        if (hasInappropriateLanguage(lower)) return "inappropriate language";

        return null;
    }

    private static boolean hasInappropriateLanguage(String input) {
        String normalizedInput = normalize(input);

        for (String word : NSFW_WORDS) {
            if (isWhitelisted(input, word)) continue;

            if (checkMatch(normalizedInput, normalize(word))) return true;
            if (checkWithBypass(input, word)) return true;
        }
        return false;
    }

    private static boolean checkMatch(String input, String target) {
        if (target.length() < 3) return false;
        String regex = "\\b" + Pattern.quote(target) + "\\b";
        return Pattern.compile(regex).matcher(input).find();
    }

    private static boolean checkWithBypass(String input, String word) {
        StringBuilder regexBuilder = new StringBuilder("\\b");
        for (int i = 0; i < word.length(); i++) {
            regexBuilder.append(Pattern.quote(String.valueOf(word.charAt(i))));
            if (i < word.length() - 1) {
                regexBuilder.append("[\\W_]*");
            }
        }
        regexBuilder.append("\\b");
        return Pattern.compile(regexBuilder.toString()).matcher(input).find();
    }

    private static boolean isWhitelisted(String input, String nsfwWord) {
        for (String white : BIRD_WHITELIST) {
            if (input.contains(white) && white.contains(nsfwWord)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        if (text == null) return "";

        String normalized = text.toLowerCase()
                .replace('0', 'o').replace('1', 'i').replace('3', 'e')
                .replace('4', 'a').replace('5', 's').replace('7', 't')
                .replace('8', 'b').replace('@', 'a').replace('$', 's').replace('!', 'i');

        return normalized.replaceAll("(.)\\1+", "$1");
    }

    public static boolean containsInappropriateContent(String text) {
        return getInappropriateReason(text) != null;
    }
}
