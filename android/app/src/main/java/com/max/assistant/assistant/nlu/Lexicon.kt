package com.max.assistant.assistant.nlu

import java.util.Locale

/**
 * A single lexical signal found in an utterance.
 *
 * WHY features instead of keywords:
 * the old parser asked "does the text contain 'battery'?", which is why
 * "my battery is at 15%, how long will it last?" opened Battery Settings.
 * A feature is a TYPED signal - QUESTION, TARGET_BATTERY, TIME_CUE - so a rule
 * can require one signal AND forbid another. The word "battery" on its own only
 * ever produces [TARGET_BATTERY]; it is never enough to open anything.
 */
enum class Feature {
    // --- Action verbs ---------------------------------------------------
    /** open / launch / start / "khol" / "chalu" */
    OPEN,

    /** show / display / view / "dikhao" / "bata" */
    SHOW,

    /** "take me to" / "go to" - an explicit move, not a launch. */
    GO_TO,

    /** turn on / enable / "on karo" */
    ENABLE,

    /** turn off / disable / "band karo" */
    DISABLE,

    /** toggle / "badal do" */
    TOGGLE,

    /** increase / raise / "badha" */
    RAISE,

    /** decrease / lower / "kam karo" */
    LOWER,

    // --- Question / statement shape -------------------------------------
    /** A question word, or a "?" anywhere in the raw utterance. */
    QUESTION,

    /** "why" / "kyun" - a diagnostic, not an action. */
    DIAGNOSTIC_CUE,

    // --- Targets (the noun the sentence is about) -----------------------
    TARGET_BATTERY,
    TARGET_WIFI,
    TARGET_BLUETOOTH,
    TARGET_STORAGE,
    TARGET_DISPLAY,
    TARGET_SOUND,
    TARGET_LOCATION,
    TARGET_VOLUME,
    TARGET_FLASHLIGHT,
    TARGET_DEVICE,
    TARGET_SETTINGS,

    // --- Modifiers (what the user wants DONE with the target) -----------
    /** The literal word "settings" - the user asked for a SCREEN. */
    SETTINGS_SCREEN,

    /** "status" / "level" / "percentage" - the user asked for a FACT. */
    STATUS_CUE,

    /** "how much" / "kitna" / "bachi hai" - a QUANTITY question. */
    LEVEL_CUE,

    /** "how long" / "kitni der" / "chalegi" - a DURATION question. */
    TIME_CUE,

    /** "drain" / "dying" / "fast" - the battery is behaving oddly. */
    DRAIN_CUE,

    /** "saver" / "power saving" - the battery-saver feature. */
    SAVER_CUE,

    /** "info" / "details" / "stats" - an explicit information request. */
    INFO_CUE,

    /**
     * The user is ASKING for a fact.
     *
     * Derived, not matched: present whenever any question-shaped signal is.
     * Every device-information rule REQUIRES this, which is the structural form
     * of "the word battery alone must never be enough". Without it, "search
     * YouTube for ANDROID tutorials" matched the "android" device noun and was
     * treated as a request for device information.
     */
    INFORMATION_REQUEST,

    // --- People ---------------------------------------------------------
    CONTACT_NAME,
    CALL_VERB,
    MESSAGE_VERB,

    // --- App interaction -------------------------------------------------
    APP_NAME,
    /** search / look for / "dhundho" / "talash" */
    SEARCH_VERB,
    /** play / "bajao" */
    PLAY_VERB,
    /** profile / "profile kholo" */
    PROFILE_CUE,
    /** chat / "baat" */
    CHAT_CUE,
    /** "directions" inside maps */
    DIRECTIONS_CUE,

    /** A bare URL. */
    URL
}
/**
 * The vocabulary, as DATA.
 *
 * Every phrase below is a LEXICAL signal, not a command. Nothing in this file
 * decides what to do - that is [IntentRouter]'s job. Adding a word for a new
 * language, or a new target noun, is one line in a table.
 *
 * The table is scanned with ONE compiled regex rather than a chain of `contains`
 * calls, so analysing an utterance is a single pass and stays cheap on the
 * voice hot path.
 */
object Lexicon {

    private val CI = RegexOption.IGNORE_CASE

    // --- Romanised Hindi / Hinglish (what the recogniser usually returns) ---
    private val hinglish: List<Pair<String, Feature>> = listOf(
        "khol" to Feature.OPEN, "kholo" to Feature.OPEN, "kholiye" to Feature.OPEN,
        "chalu" to Feature.ENABLE, "chalu karo" to Feature.ENABLE,
        "chalu kardo" to Feature.ENABLE, "off karo" to Feature.DISABLE,
        "band karo" to Feature.DISABLE, "band kardo" to Feature.DISABLE,
        "band kar do" to Feature.DISABLE, "banda karo" to Feature.DISABLE,
        "dikhao" to Feature.SHOW, "dikhawao" to Feature.SHOW,
        "batao" to Feature.SHOW, "bata" to Feature.SHOW, "bata do" to Feature.SHOW,
        "kitna" to Feature.LEVEL_CUE, "kitni" to Feature.LEVEL_CUE,
        "kitne" to Feature.LEVEL_CUE, "bcha" to Feature.LEVEL_CUE,
        "bchi" to Feature.LEVEL_CUE, "bacha" to Feature.LEVEL_CUE,
        "bachi" to Feature.LEVEL_CUE, "bache" to Feature.LEVEL_CUE,
        "kitni der" to Feature.TIME_CUE, "kitni der se" to Feature.TIME_CUE,
        "chalegi" to Feature.TIME_CUE, "chalega" to Feature.TIME_CUE,
        "kyun" to Feature.DIAGNOSTIC_CUE, "kyu" to Feature.DIAGNOSTIC_CUE,
        "kyun kar" to Feature.DIAGNOSTIC_CUE, "kyu nahi" to Feature.DIAGNOSTIC_CUE,
        "kyu ho raha" to Feature.DIAGNOSTIC_CUE,
        "badha do" to Feature.RAISE, "zor se" to Feature.RAISE,
        "kam karo" to Feature.LOWER, "dhanda" to Feature.LOWER,
        "lagao" to Feature.OPEN, "shuru" to Feature.OPEN, "on" to Feature.ENABLE,
        "message" to Feature.MESSAGE_VERB, "bhejo" to Feature.MESSAGE_VERB,
        "call karo" to Feature.CALL_VERB, "call kar do" to Feature.CALL_VERB,
        "tasveer" to Feature.OPEN, "camera" to Feature.OPEN,
        "search karo" to Feature.SEARCH_VERB, "dhundho" to Feature.SEARCH_VERB,
        "dhundh" to Feature.SEARCH_VERB, "talash" to Feature.SEARCH_VERB,
        "bajao" to Feature.PLAY_VERB, "baja" to Feature.PLAY_VERB,
        "meri" to Feature.QUESTION, "mera" to Feature.QUESTION
    )

    // --- Devanagari script (in case the recogniser returns native Hindi) ---
    private val devanagari: List<Pair<String, Feature>> = listOf(
        "बैटरी" to Feature.TARGET_BATTERY,
        "चार्ज" to Feature.TARGET_BATTERY, "चार्जिंग" to Feature.TARGET_BATTERY,
        "वाईफाई" to Feature.TARGET_WIFI, "वाइफाई" to Feature.TARGET_WIFI,
        "ब्लूटूथ" to Feature.TARGET_BLUETOOTH,
        "सेटिंग" to Feature.TARGET_SETTINGS, "सेटिंग्स" to Feature.TARGET_SETTINGS,
        "कितना" to Feature.LEVEL_CUE, "कितनी" to Feature.LEVEL_CUE,
        "कब तक" to Feature.TIME_CUE, "क्यों" to Feature.DIAGNOSTIC_CUE,
        "खोल" to Feature.OPEN, "खोलो" to Feature.OPEN,
        "चालू" to Feature.ENABLE, "बंद" to Feature.DISABLE,
        "दिखाओ" to Feature.SHOW, "बताओ" to Feature.SHOW,
        "खोजो" to Feature.SEARCH_VERB, "खोज" to Feature.SEARCH_VERB,
        "फोन" to Feature.CALL_VERB, "कॉल" to Feature.CALL_VERB
    )
// --- English / transliterated vocabulary ------------------------------
    private val english: List<Pair<String, Feature>> = listOf(
        // open-ish verbs
        "open" to Feature.OPEN, "launch" to Feature.OPEN, "start" to Feature.OPEN,
        "run" to Feature.OPEN, "access" to Feature.OPEN, "get" to Feature.OPEN,
        "load" to Feature.OPEN, "browse" to Feature.OPEN, "fire up" to Feature.OPEN,
        "go to" to Feature.GO_TO, "take me to" to Feature.GO_TO,
        "navigate to" to Feature.GO_TO, "head to" to Feature.GO_TO,
        // show-ish verbs
        "show" to Feature.SHOW, "display" to Feature.SHOW, "view" to Feature.SHOW,
        "bring up" to Feature.SHOW, "pull up" to Feature.SHOW,
        "tell me" to Feature.SHOW, "check" to Feature.SHOW,
        "read" to Feature.SHOW, "see" to Feature.SHOW,
        // on / off
        "on" to Feature.ENABLE, "enable" to Feature.ENABLE, "activate" to Feature.ENABLE,
        "switch on" to Feature.ENABLE, "turn on" to Feature.ENABLE,
        "power on" to Feature.ENABLE, "off" to Feature.DISABLE,
        "disable" to Feature.DISABLE, "deactivate" to Feature.DISABLE,
        "switch off" to Feature.DISABLE, "turn off" to Feature.DISABLE,
        "toggle" to Feature.TOGGLE, "flip" to Feature.TOGGLE,
        "increase" to Feature.RAISE, "raise" to Feature.RAISE,
        "boost" to Feature.RAISE, "louder" to Feature.RAISE,
        "volume up" to Feature.RAISE, "turn up" to Feature.RAISE,
        "decrease" to Feature.LOWER, "lower" to Feature.LOWER,
        "reduce" to Feature.LOWER, "quiet" to Feature.LOWER,
        "volume down" to Feature.LOWER, "turn down" to Feature.LOWER,
        "mute" to Feature.DISABLE, "silence" to Feature.DISABLE,
        // questions and diagnostics
        "what" to Feature.QUESTION, "whats" to Feature.QUESTION,
        "which" to Feature.QUESTION, "how much" to Feature.LEVEL_CUE,
        "how many" to Feature.LEVEL_CUE, "how long" to Feature.TIME_CUE,
        "kitna time" to Feature.TIME_CUE, "why" to Feature.DIAGNOSTIC_CUE,
        "whats causing" to Feature.DIAGNOSTIC_CUE,
        "reason" to Feature.DIAGNOSTIC_CUE, "problem" to Feature.DIAGNOSTIC_CUE,
        // targets
        "battery" to Feature.TARGET_BATTERY, "charge" to Feature.TARGET_BATTERY,
        "power" to Feature.TARGET_BATTERY, "juice" to Feature.TARGET_BATTERY,
        "wifi" to Feature.TARGET_WIFI, "wi fi" to Feature.TARGET_WIFI,
        "internet" to Feature.TARGET_WIFI, "bluetooth" to Feature.TARGET_BLUETOOTH,
        "bt" to Feature.TARGET_BLUETOOTH, "storage" to Feature.TARGET_STORAGE,
        "disk" to Feature.TARGET_STORAGE, "space" to Feature.TARGET_STORAGE,
        "memory" to Feature.TARGET_STORAGE, "display" to Feature.TARGET_DISPLAY,
        "screen" to Feature.TARGET_DISPLAY, "brightness" to Feature.TARGET_DISPLAY,
        "sound" to Feature.TARGET_SOUND, "audio" to Feature.TARGET_SOUND,
        "location" to Feature.TARGET_LOCATION, "gps" to Feature.TARGET_LOCATION,
        "volume" to Feature.TARGET_VOLUME,
        "flashlight" to Feature.TARGET_FLASHLIGHT, "torch" to Feature.TARGET_FLASHLIGHT,
        "phone" to Feature.TARGET_DEVICE, "device" to Feature.TARGET_DEVICE,
        "model" to Feature.TARGET_DEVICE, "handset" to Feature.TARGET_DEVICE,
        "android" to Feature.TARGET_DEVICE, "version" to Feature.TARGET_DEVICE,
        "settings" to Feature.TARGET_SETTINGS, "setting" to Feature.TARGET_SETTINGS,
        "preferences" to Feature.TARGET_SETTINGS,
        // The SAME words also mean "a settings SCREEN", not "settings as a
        // topic". Both readings matter: one lets "open settings" reach the
        // settings home screen, the other makes "open battery settings" an
        // explicit screen request rather than a bare noun.
        "settings" to Feature.SETTINGS_SCREEN, "setting" to Feature.SETTINGS_SCREEN,
        // modifiers that name a SCREEN or a FACT
        "panel" to Feature.SETTINGS_SCREEN, "page" to Feature.SETTINGS_SCREEN,
        "menu" to Feature.SETTINGS_SCREEN, "options" to Feature.SETTINGS_SCREEN,
        "status" to Feature.STATUS_CUE, "level" to Feature.STATUS_CUE,
        "percentage" to Feature.STATUS_CUE, "percent" to Feature.STATUS_CUE,
        // "left"/"last" are deliberately NOT time cues here: "how much battery
        // is left" is a LEVEL question, and treating it as a duration is what
        // made that phrase answer with a time estimate instead of a number.
        // Only unambiguous duration words (how long, remaining, runtime) map
        // to TIME_CUE.
        "remaining" to Feature.TIME_CUE,
        "estimate" to Feature.TIME_CUE,
        "runtime" to Feature.TIME_CUE, "drain" to Feature.DRAIN_CUE,
        "draining" to Feature.DRAIN_CUE, "drained" to Feature.DRAIN_CUE,
        "dying" to Feature.DRAIN_CUE, "fast" to Feature.DRAIN_CUE,
        "quickly" to Feature.DRAIN_CUE, "suddenly" to Feature.DRAIN_CUE,
        "saver" to Feature.SAVER_CUE, "saving" to Feature.SAVER_CUE,
        "power saving" to Feature.SAVER_CUE, "battery saving" to Feature.SAVER_CUE,
        "low power" to Feature.SAVER_CUE, "info" to Feature.INFO_CUE,
        "information" to Feature.INFO_CUE, "details" to Feature.INFO_CUE,
        "stats" to Feature.INFO_CUE,
        // people
        "contact" to Feature.CONTACT_NAME, "contacts" to Feature.CONTACT_NAME,
        "call" to Feature.CALL_VERB, "phone" to Feature.CALL_VERB,
        "ring" to Feature.CALL_VERB, "dial" to Feature.CALL_VERB,
        "message" to Feature.MESSAGE_VERB, "text" to Feature.MESSAGE_VERB,
        "sms" to Feature.MESSAGE_VERB, "whatsapp" to Feature.MESSAGE_VERB,
        // app interactions
        "search" to Feature.SEARCH_VERB, "look for" to Feature.SEARCH_VERB,
        "find" to Feature.SEARCH_VERB, "lookup" to Feature.SEARCH_VERB,
        "play" to Feature.PLAY_VERB, "listen" to Feature.PLAY_VERB,
        "profile" to Feature.PROFILE_CUE, "chat" to Feature.CHAT_CUE,
        "talk to" to Feature.CHAT_CUE, "directions" to Feature.DIRECTIONS_CUE,
        "route" to Feature.DIRECTIONS_CUE
    )
/**
     * ONE regex for every Latin-script phrase, built once.
     *
     * `\b` keeps "on" from matching inside "phone" and "open" from matching
     * "opener". Longest phrases are listed first so "turn on" wins over "on".
     */
    private val latinMatcher: Regex by lazy {
        val phrases = (hinglish.map { it.first } + english.map { it.first })
            .distinct()
            .sortedByDescending { it.length }
        Regex("\\b(?:" + phrases.joinToString("|") { Regex.escape(it) } + ")\\b", CI)
    }

    /**
     * Phrase -> the features it implies.
     *
     * A SET, not a single value, and that detail matters: "volume up" must
     * imply BOTH "volume" and "raise up". With one-feature-per-phrase the
     * longer phrase swallowed its own subject, so "volume up" looked like a
     * verb with no target and fell through. Multiplicity is what makes
     * multi-word phrases compose instead of cancelling out.
     */
    private val phraseFeatures: Map<String, Set<Feature>> by lazy {
        val m = HashMap<String, MutableSet<Feature>>()
        (hinglish + english).forEach { (p, f) -> m.getOrPut(p) { LinkedHashSet() }.add(f) }
        m
    }

    /**
     * Trailing Hinglish auxiliaries that mark a statement as a question.
     *
     * "battery kitni bachi hai" has no wh-word and no "?", but the auxiliary
     * "hai" is exactly what makes it a question rather than a statement.
     */
    private val auxMatcher: Regex by lazy {
        Regex(
            "\\b(?:" + listOf("hai", "hain", "he", "hui", "raha", "rahi", "rahe")
                .joinToString("|") + ")\\b", CI
        )
    }

    /**
     * Extracts every lexical [Feature] present in [rawText].
     *
     * The raw text is also scanned for "?" because a question mark alone is a
     * strong shape signal even when the sentence contains no wh-word.
     */
    fun features(rawText: String): Set<Feature> {
        val out = LinkedHashSet<Feature>()
        val text = rawText.lowercase(Locale.ROOT)

        if (text.contains('?')) out.add(Feature.QUESTION)

        // Hyphens become spaces so ONE lexicon entry covers every spelling:
        // "Wi-Fi", "wi fi" and "wifi" all reduce to the same tokens. Without
        // this, "What's my Wi-Fi status?" matched no target at all.
        val normalized = text.replace('-', ' ').replace('_', ' ').replace(Regex("\\s+"), " ")

        latinMatcher.findAll(normalized).forEach { m ->
            // A multi-word phrase contributes its features AND its individual
            // words, so "volume up" yields RAISE and TARGET_VOLUME together
            // rather than the longer match hiding the shorter one.
            out.addAll(phraseFeatures[m.value] ?: emptySet())
            m.value.split(' ').forEach { word ->
                out.addAll(phraseFeatures[word] ?: emptySet())
            }
        }

        // Native-script terms are matched WITHOUT \b: Java's \b is ASCII-aware,
        // so it would never match Devanagari word boundaries.
        devanagari.forEach { (term, f) -> if (text.contains(term)) out.add(f) }

        if (auxMatcher.containsMatchIn(text)) out.add(Feature.QUESTION)

        // An English yes/no question is often spoken with no question mark:
        // "is bluetooth on", "are you there". A leading copula is the reliable
        // signal for that shape, and copulas rarely start a COMMAND, so the
        // false-positive risk is low.
        if (YES_NO_QUESTION.containsMatchIn(text)) out.add(Feature.QUESTION)

        // A derived signal, so it can never drift from the question-shaped
        // features above. Every DEVICE_INFO rule requires it, which is what
        // stops a bare noun ("... Android tutorials") from reading as a request
        // for device information.
        if (out.any { it in ASKING_SHAPE }) out.add(Feature.INFORMATION_REQUEST)

        return out
    }

    /** "is bluetooth on", "are you there" - a question with no question mark. */
    private val YES_NO_QUESTION: Regex by lazy {
        Regex("^\\s*(?:is|are|was|were|am|does|do|did|can|could|should|has|have)\\b", CI)
    }

    /**
     * The signals that mean "the user is asking for a fact".
     *
     * Shared with the router's settings ban, so "what counts as a question" is
     * defined in exactly one place.
     */
    val ASKING_SHAPE = setOf(
        Feature.QUESTION, Feature.LEVEL_CUE, Feature.TIME_CUE,
        Feature.STATUS_CUE, Feature.DIAGNOSTIC_CUE, Feature.INFO_CUE
    )
}