package com.birddex.app;

import android.content.Context;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ContentFilter: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class ContentFilter {

    private static final Set<String> NSFW_WORDS = new HashSet<>(Arrays.asList(
            // --- Profanity & Core NSFW ---
            "fuck", "shit", "asshole", "bitch", "cunt", "dick", "pussy", "bastard",
            "slut", "whore", "sex", "porn", "pornography", "xxx", "nsfw", "erotic",
            "hardcore", "softcore", "adult content", "motherfucker", "cocksucker",
            "cockfucker", "jackass", "dipshit", "dumbass", "dumbshit", "goddamn", "piss",
            "ahole", "biotch", "cunt", "fucker", "fucking",

            // --- Anatomy & Sexual Terms ---
            "penis", "vagina", "clitoris", "testicles", "scrotum", "boobs", "tits",
            "ass", "breasts", "genitals", "cock", "balls", "clit", "labia",
            "erection", "masturbate", "masturbation", "orgasm", "cum", "cumming",
            "ejaculate", "penetrate", "penetration", "intercourse", "coitus",
            "blowjob", "handjob", "deepthroat", "rimjob", "rimming", "anal",
            "cummin", "coom", "blowie", "his member", "wet cunt",

            // --- Fetishes & Adult Services ---
            "onlyfans", "camgirl", "camsite", "stripper", "escort", "brothel",
            "fetish", "bdsm", "kink", "kinky", "dominatrix", "submissive",
            "bondage", "dildo", "vibrator", "pegging", "fingering", "scissoring",
            "grinding", "foot fetish", "roleplay sex",

            // --- Explicit Content & Scenarios ---
            "nude", "nudes", "naked", "topless", "lewd", "explicit", "send nudes",
            "milf", "dilf", "sugar daddy", "sugar baby", "creampie", "facial",
            "spitroast", "threesome", "foursome", "gangbang", "orgy", "hentai",
            "doujinshi", "adult video", "sex tape", "gilf",

            // --- Dating & Hookups ---
            "hookup", "one night stand", "booty call", "smash", "get laid",
            "bang", "doggy style", "69", "quickie", "hooking up", "sleep together",

            // --- Slurs & Hate Speech ---
            "nigger", "kike", "faggot", "dyke", "retard", "tranny", "spic", "chink",
            "wetback", "coon", "nazi", "hitler", "negro", "beaner", "gook", "gypo", "fag",
            "cracker", "zipperhead", "sand nigger", "turban head", "darkie", "chud", "transvestite",
            "troon", "nigga", "Dark Skin", "Cholo", "Gringo",

            // --- Violence & Harm ---
            "kill yourself", "suicide", "murder", "rape", "molest", "pedophile",
            "underage", "terrorist", "massacre", "genocide", "kys", "rapist",
            "KYS",

            // --- Organizations
            "al qaeda", "ISIS", "KKK", "Klu Klux Klan", "Kool Kids Klub", "cia", "fbi",

            // --- Illegal Substances ---
            "cocaine", "heroin", "meth", "fentanyl", "oxycodone", "xanax", "percocet",
            "crack cocaine", "mdma", "ecstasy",

            // --- Controversial / Political ---
            "9-11", "white power", "black lives matter", "magam", "maga",
            "magat", "libtard", "glowie", "ice agent", "Israel", "Palestine",
            "jet fuel can't melt steel beams", "jet fuel cant melt steel beams",
            "black excellence", "white superiority", "IDF", "Ukraine",
            "From the river to the sea", "From the river, to the sea",
            "Russia", "Free Palestine",


            // --- People ---
            "trump", "biden", "obama", "bill clinton", "hillary clinton", "nick fuentes",
            "osama", "bin laden", "JD Vance", "Andrew Tate", "Tristan Tate", "Sneako",
            "epstein", "Ghislaine Maxwell", "Jeffery Epstein", "Benjamin Netanyahu",
            "Netanyahu", "Adolf Hitler", "Hitler", "Himmler",

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
    private static final Pattern LONG_CHAR_SPAM_PATTERN = Pattern.compile("(.)\1{11,}", Pattern.DOTALL);

    // Detects Credit Card patterns (13 to 16 digits)
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile("\\b(?:\\d[ -]*?){13,16}\\b");

    // Detects Zalgo / Glitch text (excessive combining marks that break UI)
    private static final Pattern ZALGO_PATTERN = Pattern.compile("[\\u0300-\u036F\\u1DC0-\u1DFF\\u20D0-\u20FF\\uFE20-\uFE2F]{3,}");

    /**
     * Checks if the given text is safe to post.
     * Shows a Toast message if inappropriate content is found.
     */
    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
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
            // Give the user immediate feedback about the result of this action.
            Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /**
     * Returns a string describing why content is inappropriate, or null if it's safe.
     */
    /**
     * Returns the current value/state this class needs somewhere else in the app.
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
        if (isExcessiveCharacterSpam(text)) return "excessive character repetition";

        // 7. Check for Language
        if (hasInappropriateLanguage(lower)) return "inappropriate language";

        return null;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    private static boolean isExcessiveCharacterSpam(String text) {
        String raw = text == null ? "" : text;
        String compact = raw.replaceAll("\\s+", "");
        if (compact.isEmpty()) return false;

        // Allow normal stretched words like "heyyyyyyy" or "nooooooo",
        // but still block obvious floods like "aaaaaaaaaaaa" or "!!!!!!!!!!!!".
        if (LONG_CHAR_SPAM_PATTERN.matcher(raw).find()) return true;

        if (compact.length() >= 18) {
            int[] counts = new int[Character.MAX_VALUE + 1];
            int maxCount = 0;
            String lowerCompact = compact.toLowerCase();
            for (int i = 0; i < lowerCompact.length(); i++) {
                char ch = lowerCompact.charAt(i);
                counts[ch]++;
                if (counts[ch] > maxCount) {
                    maxCount = counts[ch];
                }
            }
            if (maxCount >= 12 && ((double) maxCount / (double) compact.length()) >= 0.75d) {
                return true;
            }
        }

        return false;
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

    /**
     * Main logic block for this part of the feature.
     */
    private static boolean checkMatch(String input, String target) {
        if (target.length() < 3) return false;
        String regex = "\\b" + Pattern.quote(target) + "\\b";
        return Pattern.compile(regex).matcher(input).find();
    }

    /**
     * Main logic block for this part of the feature.
     */
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

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    private static boolean isWhitelisted(String input, String nsfwWord) {
        for (String white : BIRD_WHITELIST) {
            if (input.contains(white) && white.contains(nsfwWord)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Main logic block for this part of the feature.
     */
    private static String normalize(String text) {
        if (text == null) return "";

        String normalized = text.toLowerCase()
                .replace('0', 'o').replace('1', 'i').replace('3', 'e')
                .replace('4', 'a').replace('5', 's').replace('7', 't')
                .replace('8', 'b').replace('@', 'a').replace('$', 's').replace('!', 'i');

        return normalized.replaceAll("(.)\\1+", "$1");
    }

    /**
     * Main logic block for this part of the feature.
     */
    public static boolean containsInappropriateContent(String text) {
        return getInappropriateReason(text) != null;
    }
}
