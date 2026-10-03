package com.max.assistant.assistant.nlu

/**
 * Entity extraction: pulls the OBJECT out of a sentence.
 *
 * Separate from [IntentRule] on purpose. A rule says "this is a battery
 * question"; this says "the thing being asked about is the battery". Keeping
 * them apart means the same vocabulary works for every language and every
 * intent without the rule table growing a regex per intent.
 *
 * A small, fixed lexicon of FILLER words is all this needs. App names are NOT
 * listed here - they are resolved against the real PackageManager at
 * execution time (see AppResolver), which is what lets MAX open Discord,
 * Telegram or any other installed app with no code change.
 */
object Entities {

    private val CI = RegexOption.IGNORE_CASE

    /**
     * Words that describe the REQUEST rather than the target. Stripping these
     * leaves the thing the user actually wants acted upon.
     */
    private val filler = listOf(
        // politeness + framing
        "please", "can you", "could you", "would you", "will you", "hey max", "hi max",
        "max", "hey", "hi", "ok", "okay",
        // question framing
        "what is", "whats", "what are", "what's", "what", "which", "how much", "how many",
        "how long", "why is", "why does", "why", "tell me", "let me know", "do you know",
        "is my", "are my", "do i have", "do i", "does my", "am i", "currently", "right now",
        "now", "currently", "at the moment", "please tell me",
        // action verbs
        "open", "launch", "start", "run", "access", "load", "browse", "fire up", "get",
        "show", "display", "view", "bring up", "pull up", "check", "read", "see", "give",
        "take me to", "go to", "head to", "navigate to", "switch on", "switch off",
        "turn on", "turn off", "turn up", "turn down", "power on", "enable", "disable",
        "activate", "deactivate", "toggle", "increase", "raise", "reduce", "lower",
        "boost", "set", "change", "adjust", "search for", "search", "look for", "find",
        "play", "listen", "lookup", "browse for", "kholo", "khol", "dikhao", "batao",
        "chalu karo", "chalu", "band karo", "band", "badha do", "kam karo", "lagao",
        "dhundho", "dhundh", "talash", "bajao", "dhanda", "zor se", "shuru",
        // target nouns that are words, not things to act on
        "battery", "charge", "power", "status", "level", "percentage", "percent",
        "info", "information", "details", "stats", "settings", "setting", "screen",
        "page", "panel", "menu", "options", "preferences", "current", "current status",
        // hinglish question words
        "kitna", "kitni", "kitne", "kyun", "kyu", "hai", "hain", "he", "meri", "mera",
        "mera", "bata", "bata do", "kitni der", "chalegi", "chalega"
    )

    /** Longest-first so "search for" is stripped before "search". */
    private val fillerMatcher: Regex by lazy {
        Regex(
            "\\b(?:" + filler.distinct().sortedByDescending { it.length }
                .joinToString("|") { Regex.escape(it) } + ")\\b", CI
        )
    }

    /** Removes filler words and returns what is left, or "" when nothing is. */
    fun target(text: String): String {
        var s = text
        // Drop leading fillers one at a time, so "open" inside a name survives.
        var changed = true
        while (changed) {
            changed = false
            for (f in filler.distinct().sortedByDescending { it.length }) {
                val re = Regex("^\\b" + Regex.escape(f) + "\\b[ ,]*", CI)
                val m = re.find(s)
                if (m != null && m.range.first == 0) {
                    s = s.removeRange(m.range).trim(); changed = true; break
                }
            }
        }
        return s.trim().trim(',', '?', '.', '-', ':').trim()
    }

    /** Removes every filler occurrence from the middle of the string. */
    fun scrub(text: String): String =
        fillerMatcher.replace(text, " ").replace(Regex("\\s+"), " ").trim()

    /**
     * The trailing noun phrase after a search-type verb.
     * "search for android tutorials on youtube" -> "android tutorials".
     */
    fun searchQuery(text: String): String {
        // Collapse the double spaces left by removing the app name.
        var s = text.replace(Regex("\\s+"), " ").trim()
        val leading = listOf(
            "search for", "search", "look for", "find", "lookup", "look up",
            "search karo", "dhundho", "dhundh", "talash", "dekho", "search kardo",
            // The verb may already have been stripped, leaving the preposition.
            "for", "on", "in", "about"
        )
        for (p in leading) {
            s = Regex("^\\b" + Regex.escape(p) + "\\b", CI).replace(s, "").trim()
        }
        // Drop a trailing "on/in <app>" clause: the app is resolved separately,
        // so only the search text itself belongs here.
        s = Regex("\\s+(?:on|in|inside|within|through)\\s+[\\p{L}\\p{N} ._-]{2,30}$", CI)
            .replace(s, "").trim()
        // A dangling preposition left by that removal is not part of the query.
        s = Regex("\\s+(?:on|in|for|to)$", CI).replace(s, "").trim()
        return s.trim().trim(',', '?', '.', '-', ':').trim()
    }

    /**
     * The contact name in a call/message request.
     * "call dad" -> "dad"; "call dad and tell him hi" -> "dad".
     */
    fun contactName(text: String, after: List<String>): String? {
        var s = text
        for (p in after) {
            s = Regex("^\\b" + Regex.escape(p) + "\\b", CI).replace(s, "").trim()
        }
        // Everything after a clause boundary belongs to the message, not the name.
        s = Regex("\\s+(?:and|and then|then|saying|that says|with|to say)\\s+.*$", CI).replace(s, "").trim()
        s = s.trim().trim(',', '?', '.', '-', ':').trim()
        return s.ifBlank { null }
    }

    /**
     * The APP NAME from an app request.
     *
     * Handles the shapes people actually say:
     *   "open Discord"                 -> "Discord"   (English, verb first)
     *   "Chrome khol"                  -> "Chrome"    (Hinglish, verb LAST)
     *   "launch the Telegram app"      -> "Telegram"
     *   "search YouTube for tutorials" -> "YouTube"
     *   "Spotify pe search karo"       -> "Spotify"
     *
     * Returns null when the sentence does not clearly name an app, which is
     * what stops the app router from claiming ordinary commands.
     */
    fun appName(text: String, verbs: List<String>): String? {
        // A second, unrelated clause means this is a SENTENCE, not an app request:
        // "open the pod bay doors and tell me a story" must not become "open
        // pod bay doors". The check is on the WHOLE sentence, before the
        // preposition cut, because the giveaway ("tell") sits after the cut.
        if (words(text).any { it in CONVERSATION_VERBS }) return null

        var s = text.trim().trim(',', '.', '-', ':', '?').trim()
        if (s.isEmpty()) return null

        // 1. A leading verb: "open Discord", "search YouTube for ...".
        val before = s
        stripLeadingVerb(s, verbs)?.let { s = it }
        if (s == before) {
            // 2. A trailing verb: "Chrome khol", "Spotify chalu karo".
            val trimmed = stripTrailingVerb(s, verbs)
            if (trimmed == null) return null
            s = trimmed
        }

        // 3. Politeness and definiteness: "the Discord app" -> "Discord".
        s = Regex("^\\b(?:the|my|that)\\b\\s*", CI).replace(s, "").trim()
        s = Regex("\\s+app$", CI).replace(s, "").trim()

        // 4. Cut at the first preposition: everything after it is the QUERY,
        //    not the app name ("YouTube for Android tutorials" -> "YouTube").
        s = s.split(" ").takeWhile { !PREPOSITION.contains(it.lowercase()) }.joinToString(" ")
        s = s.trim().trim(',', '.', '-', ':').trim()

        if (s.isEmpty()) return null
        // A second, unrelated clause means this is a SENTENCE, not an app
        // request: "open the pod bay doors and tell me a story" must not become
        // "open pod bay doors". Real app names are never followed by a verb.
        val words = s.split(' ').filter { it.isNotBlank() }
        if (words.size > 3) return null
        return s
    }

    /** Lower-cased, punctuation-free words of [text]. */
    private fun words(text: String): List<String> =
        text.lowercase().split(' ').map { it.trim(',', '.', '-', ':', '?', '!') }
            .filter { it.isNotBlank() }

    /**
     * Verbs that mean "then do something else", i.e. the sentence continues
     * past the app name.
     */
    private val CONVERSATION_VERBS = setOf(
        "tell", "explain", "summarize", "summarise", "translate", "write", "draft",
        "read", "describe", "list", "show", "make", "create", "compose", "answer",
        "batao", "bata", "likho", "samjhao"
    )

    private fun stripLeadingVerb(text: String, verbs: List<String>): String? {
        for (v in verbs.sortedByDescending { it.length }) {
            val re = Regex("^\\b" + Regex.escape(v) + "\\b[ ,]*", CI)
            val m = re.find(text)
            if (m != null && m.range.first == 0) return text.removeRange(m.range).trim()
        }
        return null
    }

    private fun stripTrailingVerb(text: String, verbs: List<String>): String? {
        for (v in verbs.sortedByDescending { it.length }) {
            val re = Regex("\\s+\\b" + Regex.escape(v) + "\\b\\s*$", CI)
            val m = re.find(text)
            if (m != null) return text.removeRange(m.range).trim()
        }
        return null
    }

    /** Words that end an app name and begin the query. */
    private val PREPOSITION = setOf(
        "for", "on", "in", "about", "to", "with", "me", "se", "of", "and", "ki", "ka", "pe"
    )

    /**
     * The app named in a trailing "on <app>" / "in <app>" clause.
     *
     * "search for Arijit Singh on Spotify" -> "Spotify". This is an EXPLICIT
     * choice by the user, so it always beats remembered context.
     */
    fun trailingApp(text: String): String? {
        val m = Regex(
            "\\s+(?:on|in|inside|within|through|using|via)\\s+([\\p{L}][\\p{L}\\p{N} ._-]{0,30}?)\\s*$",
            CI
        ).find(text.trim()) ?: return null
        val name = m.groupValues[1].trim().trim(',', '.', '-')
        // A clause like "on the web" is not an app.
        if (name.isBlank() || name.equals("web", true) || name.equals("google", true)) return null
        if (name.split(' ').size > 3) return null
        return name
    }
}