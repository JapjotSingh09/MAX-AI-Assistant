package com.max.assistant.assistant

import com.max.assistant.assistant.nlu.Entities
import com.max.assistant.assistant.nlu.IntentCategory
import com.max.assistant.assistant.nlu.IntentRouter

// LOCAL FIRST, CLOUD WHEN NEEDED.
// Simple commands are recognised with plain rules: instant, free, and they work offline.
// Anything not recognised returns null, and the ViewModel then asks the cloud AI.
//
// This mirrors the server parser (`src/lib/commands/parser.ts`). Both sides
// accept the same phrasings so a command behaves the same whether it arrives
// over the network or is handled on the phone while offline.
sealed interface ParsedCommand {
    data class Intent(val intent: CommandIntent) : ParsedCommand
    /** "remember that ..." - stored on the MAX server, so it needs a connection. */
    data class Memory(val text: String) : ParsedCommand
    /** "what did I ask you to remember?" - answered by the server. */
    object MemoryList : ParsedCommand
    /** "forget that ..." - deletes matching memories on the server. */
    data class MemoryForget(val query: String) : ParsedCommand

    /**
     * MAX could not tell what was wanted, and asks instead of guessing.
     *
     * This is a first-class outcome, not a failure. Guessing is what produced
     * the original bug - a plausible-looking action taken from an unclear
     * sentence - so an unclear sentence now produces a QUESTION.
     */
    data class Clarify(val question: String) : ParsedCommand
}

object CommandParser {
    private val ci = RegexOption.IGNORE_CASE

    // Normalisation: strip the wake phrase, politeness and trailing punctuation.
    fun normalize(input: String): String = input.trim()
        .replace(Regex("[.!?]+$"), "")
        .replace(Regex("^(hey |hi |ok |okay )?max[, ]+", ci), "")
        .replace(Regex("^((please|can you|could you|would you) )+", ci), "")
        .replace(Regex("\\s+please$", ci), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Number words, including "ninety" etc. so spoken durations work. */
    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fourty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90, "hundred" to 100
    )

    private val number: (String) -> Int? = { word -> word.toIntOrNull() ?: numberWords[word.lowercase()] }

    /** "one and a half hours" -> whole count + fraction, unit matched separately. */
    private val compound = Regex("^(\\w+)\\s+and\\s+(?:an?\\s+)?(half|quarter)$", ci)
    private val fraction = mapOf("half" to 0.5, "quarter" to 0.25)

    /** A spoken time phrase, e.g. "tomorrow at 9 am". Group 1 is the whole phrase. */
    private val timePhrase =
        Regex("\\b((?:today |tomorrow |tonight )?(?:at )?\\d{1,2}(?::\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)?)\\b", ci)

    /** Seconds in one [unit], e.g. "minutes" -> 60. */
    private fun unitSeconds(unit: String): Int = when {
        unit.lowercase().startsWith("h") -> 3600
        unit.lowercase().startsWith("m") -> 60
        else -> 1
    }

    /** A whole number of [unit]s, or null when the count is not a number. */
    private fun seconds(count: Int?, unit: String): Int? =
        if (count == null) null else (count * unitSeconds(unit)).coerceAtLeast(1)

    private fun make(action: ActionType, vararg params: Pair<String, Any?>): ParsedCommand? =
        IntentValidator.validate(action.name, if (params.isEmpty()) emptyMap() else mapOf(*params))
            ?.let { ParsedCommand.Intent(it) }


fun parseWithContext(input: String, fallbackApp: String? = null): ParsedCommand? {
        // FORM-BASED FIRST, for commands the router cannot see.
        //
        // Alarms, timers, notes, tasks, memories and contacts are recognised by
        // SHAPE, not by topic: "set an alarm for 7 AM", "create a note saying
        // milk". The feature router intentionally does not try to extract that
        // kind of payload, so these rules run before it and are unaffected by
        // it. They were already correct, and they cannot misfire on a battery
        // question, which is why they are safe to keep exactly as they were.
        parseLegacy(input)?.let { return it }

        // Then the INTENT router, for the questions and device readings that the
        // keyword cascade used to mishandle.
        val frame = IntentRouter.route(input)
        when {
            // A clear device reading: execute it.
            frame.isExecutable && frame.action != null -> {
                val params = frame.parameters.filterValues { it != null }
                IntentValidator.validate(frame.action.name, params)
                    ?.let { return ParsedCommand.Intent(it) }
            }

            // Genuinely ambiguous: ASK. Never act on a coin flip.
            frame.category == IntentCategory.AMBIGUOUS && frame.clarification != null ->
                return ParsedCommand.Clarify(frame.clarification)

            // Otherwise fall through to the app router below, then to the AI.
            else -> Unit
        }

        // App launch and in-app actions, resolved generically against whatever
        // is actually installed. Runs LAST because it is the most permissive.
        IntentRouter.routeApp(input, fallbackApp)?.let { appFrame ->
            val action = appFrame.action
            if (action != null) {
                IntentValidator.validate(action.name, appFrame.parameters.filterValues { it != null })
                    ?.let { return ParsedCommand.Intent(it) }
            }
        }

        return null
    }

    /** Alias kept so existing callers keep compiling. */
    fun parse(input: String): ParsedCommand? = parseWithContext(input)

    /**
     * The original keyword rules, for form-based commands.
     *
     * These remain the right tool for "set an alarm for 7 AM" or "create a note
     * saying milk": those are recognisable by SHAPE, not by topic, and they
     * capture a payload the feature router deliberately does not try to parse.
     */
    fun parseLegacy(input: String): ParsedCommand? {
        val t = normalize(input)
        val l = t.lowercase()
        var m: MatchResult?

        // --- Memory: stored on the MAX server, so these need a connection ---
        m = Regex("^remember(?: that)? (.{3,300})$", ci).find(t)
        if (m != null) return ParsedCommand.Memory(m.groupValues[1].trim())

        if (
            Regex("^(what|which)\\b.*\\b(remember|memor(?:y|ies))\\b", ci).matches(t) ||
            Regex("^(what do you remember|show (?:me |my )?memor(?:y|ies)|list (?:my )?memor(?:y|ies))\\b", ci).matches(t)
        ) {
            return ParsedCommand.MemoryList
        }

        // "forget ..." only counts as a delete when it STARTS the message, so an
        // ordinary sentence that happens to mention forgetting still goes to the AI.
        m = Regex("^forget (?:that )?(.{3,300})$", ci).find(t)
        if (m != null) return ParsedCommand.MemoryForget(m.groupValues[1].trim())
        if (Regex("^(?:forget|delete|clear) (?:all |everything |every )?(?:of )?(?:my |your )?(?:memor(?:y|ies)|saved things)\\b", ci).matches(t)) {
            return ParsedCommand.MemoryForget("")
        }

        // --- Flashlight ---
        Regex("^(?:turn |switch )?(on|off) (?:the )?(?:flash ?light|torch)$").find(l)?.let { return make(ActionType.TOGGLE_FLASHLIGHT, "state" to it.groupValues[1]) }
        Regex("^(?:turn |switch )?(?:the )?(?:flash ?light|torch) (on|off)$").find(l)?.let { return make(ActionType.TOGGLE_FLASHLIGHT, "state" to it.groupValues[1]) }
        if (Regex("^(?:toggle )?(?:the )?(?:flash ?light|torch)$").matches(l)) return make(ActionType.TOGGLE_FLASHLIGHT, "state" to "toggle")

        // --- Timer: "set a timer for 10 minutes" / "... for ninety minutes" ---
        m = Regex("^(?:set |start )?(?:a )?timer (?:for )?(.{2,40}?)(second|sec|minute|min|hour|hr)s?$", ci).find(t)
        if (m != null) {
            val phrase = m.groupValues[1].trim()
            val unit = m.groupValues[2]
            // "one and a half hours": whole count plus a fraction, unit already matched.
            val c = compound.find(phrase)
            if (c != null) {
                val whole = number(c.groupValues[1])
                val frac = fraction[c.groupValues[2].lowercase()]
                if (whole != null && frac != null) {
                    // Round, never truncate: 1.5 hours must be 5400s, not 3600s.
                    val totalSeconds = Math.round((whole + frac) * unitSeconds(unit))
                    if (totalSeconds >= 1) return make(ActionType.SET_TIMER, "seconds" to totalSeconds)
                }
            }
            seconds(number(phrase), unit)?.let { return make(ActionType.SET_TIMER, "seconds" to it) }
        }

        // --- Alarm: "set an alarm for 7 AM" / "... for tomorrow at 9" ---
        m = Regex(
            "^(?:set |create )?(?:an? )?(?:alarm|wake me up) (?:for |at )?(?:(?:today|tomorrow|tonight) )?(?:at )?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$",
            ci
        ).find(l)
        if (m != null) {
            var hour = m.groupValues[1].toIntOrNull() ?: return null
            val minute = m.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: 0
            when (m.groupValues[3].lowercase()) {
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            return make(ActionType.SET_ALARM, "hour" to hour, "minute" to minute)
        }

        // --- Reminder: "remind me tomorrow at 9 AM to call dad" ---
        m = Regex("^(?:create a |set a |add a )?remind(?:er)?(?: me)?(?: to| for| about)? (.{2,200})$", ci).find(t)
        if (m != null) {
            var body = m.groupValues[1].trim()
            val timePart = timePhrase.find(body)?.groupValues?.get(1)
            val whenText = timePart?.lowercase()
                ?: Regex("\\b(tomorrow|tonight|today|next week)\\b", ci).find(body)?.value?.lowercase()
            if (timePart != null) {
                val withoutTime = body.replace(timePhrase, " ").trim()
                if (withoutTime.isNotEmpty()) body = withoutTime
            }
            return make(ActionType.CREATE_REMINDER, "text" to body, "when" to whenText)
        }

        // --- Camera, notifications, calendar ---
        if (Regex("^(?:open |launch |start )(?:the )?camera$").matches(l) || l == "take a photo") return make(ActionType.OPEN_CAMERA)
        if (Regex("(?:what|which) notifications (?:did i miss|do i have)|^show (?:my )?notifications$|^(?:read|check) (?:my )?notifications$").containsMatchIn(l)) {
            return make(ActionType.SHOW_NOTIFICATIONS)
        }
        if (Regex("^(?:open |show |what(?:'s| is) on )?(?:my |the )?(?:calendar|agenda|schedule)(?: for today)?$", ci).matches(t)) {
            return make(ActionType.READ_CALENDAR)
        }

        // --- Volume ---
        if (Regex("^(?:turn |volume )?(?:the )?volume up$|^(?:increase|raise) (?:the )?volume$").matches(l)) return make(ActionType.ADJUST_VOLUME, "direction" to "up")
        if (Regex("^(?:turn |volume )?(?:the )?volume down$|^(?:decrease|lower|reduce) (?:the )?volume$").matches(l)) return make(ActionType.ADJUST_VOLUME, "direction" to "down")
        if (Regex("^(?:mute|silence)(?: (?:the )?(?:phone|volume|sound))?$").matches(l)) return make(ActionType.ADJUST_VOLUME, "direction" to "mute")

        // --- Settings (before the Bluetooth rule, so "bluetooth settings" wins) ---
        m = Regex("^open (?:the )?(?:(wi-?fi|bluetooth|sound|display|battery|location|apps?) )?settings$").find(l)
        if (m != null) {
            val section = m.groupValues[1].replace("-", "")
            return if (section.isEmpty()) make(ActionType.OPEN_SETTINGS) else make(ActionType.OPEN_SETTINGS, "section" to section)
        }
        // --- Bluetooth (Android cannot toggle it; the executor says so) ---
        m = Regex("^(?:turn |switch )?(on|off|toggle)? ?bluetooth$").find(l)
        if (m != null) return make(ActionType.SET_BLUETOOTH, "state" to (m.groupValues[1].ifEmpty { "toggle" }))

        // --- WhatsApp ---
        if (Regex("^(?:open|launch) whats ?app$").matches(l)) return make(ActionType.OPEN_WHATSAPP)
        m = Regex("^(?:whatsapp|message|text|send (?:a )?whatsapp(?: message)? to) (.+?) (?:on whatsapp )?(?:saying|that says|:) (.+)$", ci).find(t)
        if (m != null && l.contains("whats")) {
            return make(ActionType.OPEN_WHATSAPP, "contactName" to m.groupValues[1].trim(), "message" to m.groupValues[2].trim())
        }

        // --- SMS ---
        m = Regex("^send (.+?) (?:a |an )?(?:message|text|sms)(?: saying| that says| with|:)? (.+)$", ci).find(t)
            ?: Regex("^(?:message|text|sms) (.+?) (?:saying|that says|:) (.+)$", ci).find(t)
        if (m != null) return make(ActionType.SEND_SMS, "contactName" to m.groupValues[1].trim(), "message" to m.groupValues[2].trim())

        // --- Calls ---
        m = Regex("^(?:call|dial|phone|ring) ([+\\d][\\d\\s-]{4,})$").find(l)
        if (m != null) return make(ActionType.OPEN_DIALER, "number" to m.groupValues[1].replace(Regex("[\\s-]"), ""))
        if (Regex("^(?:open (?:the )?(?:dialer|phone)|dial)$").matches(l)) return make(ActionType.OPEN_DIALER)
        m = Regex("^(?:call|phone|ring) (.{1,80})$", ci).find(t)
        if (m != null) return make(ActionType.CALL_CONTACT, "contactName" to m.groupValues[1].trim())

        // --- Maps ---
        m = Regex("^(?:navigate|directions|drive|take me|go) (?:to |me to )(.{2,160})$", ci).find(t)
        if (m != null) return make(ActionType.NAVIGATE, "destination" to m.groupValues[1].trim())
        if (Regex("^(?:open|launch) (?:google )?maps$").matches(l)) return make(ActionType.OPEN_MAPS)
        m = Regex("^(?:find|search(?: for)?|show me|look for) (.{2,100}? (?:near me|nearby|around me))$", ci).find(t)
        if (m != null) return make(ActionType.OPEN_MAPS, "query" to m.groupValues[1].trim())
        m = Regex("^(?:search|find) (?:maps |map )?(?:for )?(.{2,100}) (?:on|in) (?:google )?maps$", ci).find(t)
        if (m != null) return make(ActionType.OPEN_MAPS, "query" to m.groupValues[1].trim())

        // --- Notes (stored on this phone, so they work offline) ---
        m = Regex("^(?:create|make|add|write|save)(?: a| an)? note (?:saying |that says |with |about |: )(.{1,2000})$", ci).find(t)
            ?: Regex("^(?:note|write down)(?::| that| to| the)(.{1,2000})$", ci).find(t)
        if (m != null) {
            val body = m.groupValues[1].trim()
            return make(ActionType.CREATE_NOTE, "title" to body.take(80), "content" to body)
        }
        if (Regex("^(?:show|list|read|what are|what's|what is) (?:me )?(?:my |the )?notes\\b", ci).matches(t)) return make(ActionType.LIST_NOTES)
        m = Regex("^(?:delete|remove) (?:the |my |a )?note (?:about |called |named |saying |with |: )(.{1,80})$", ci).find(t)
        if (m != null) return make(ActionType.DELETE_NOTE, "query" to m.groupValues[1].trim())
        // --- Tasks ---
        m = Regex("^(?:create|add|make)? ?(?:a )?task (?:to |saying |that says |called |: )(.{1,120})$", ci).find(t)
        if (m != null) {
            val body = m.groupValues[1].trim()
            val due = Regex("\\b(today|tomorrow|tonight|next week|on \\w+day|by \\w+day)\\b", ci).find(body)?.value
            val title = (if (due != null) body.replace(Regex(Regex.escape(due), ci), "") else body).trim().take(120)
            if (title.isNotEmpty()) return make(ActionType.CREATE_TASK, "title" to title, "due" to due)
        }
        m = Regex("^(?:add|create) (?:this |the )?(?:to my |a )?(?:todo|to-do)(?: list)? (?:task )?(?:to |: )?(.{1,120})$", ci).find(t)
        if (m != null && m.groupValues[1].isNotBlank()) return make(ActionType.CREATE_TASK, "title" to m.groupValues[1].trim().take(120))
        if (Regex("^(?:show|list|read|what are|what's|what is) (?:me )?(?:my |the |open )?(?:tasks|todos|to-?dos)\\b", ci).matches(t)) return make(ActionType.LIST_TASKS)
        m = Regex("^(?:mark|complete|finish|check off)(?: the)? (?:task |todo )?(?:about |called |named |: )?(.{1,80})$", ci).find(t)
        if (m != null && !Regex("^(?:off|done)$", ci).matches(m.groupValues[1].trim())) {
            return make(ActionType.COMPLETE_TASK, "query" to m.groupValues[1].trim())
        }

        // --- Weather, search, share ---
        if (l.contains("weather")) {
            val loc = Regex("^(?:what(?:'s| is) the |open |show )?weather(?: like)?(?: (?:in|at|for))? ?(.{2,80})?$", ci)
                .find(t)?.groupValues?.get(1)?.trim()
            // Only pass `location` when there IS one: an explicit null would fail
            // validation, and "no location" must stay a valid request.
            return if (loc.isNullOrBlank()) make(ActionType.OPEN_WEATHER) else make(ActionType.OPEN_WEATHER, "location" to loc)
        }
        // "Search <App> for X" names an APP, so it belongs to the generic app
        // router, not to the web. Both legacy search rules are gated on this
        // one test: without it the web rules swallow every in-app search.
        val namesAnApp = Entities.trailingApp(t) != null ||
            Regex("^search\\s+(?:in|on)\\s+[\\p{L}].{0,30}\\s+for\\b", ci).containsMatchIn(t) ||
            Regex("^search\\s+(?:for\\s+)?[\\p{L}][\\p{L}\\p{N} ._-]{1,30}\\s+for\\b", ci).containsMatchIn(t)

        // A bare "search for X" is a WEB search, so this rule must come before
        // the browser rule - both begin with the word "search".
        m = if (namesAnApp) null else Regex("^search (?:for )?(.{2,200})$", ci).find(t)
        if (m != null && !Regex("near me|nearby|around me|on (?:google )?maps", ci).containsMatchIn(m.groupValues[1])) {
            return make(ActionType.WEB_SEARCH, "query" to m.groupValues[1].trim())
        }
        m = Regex("^(?:open|go to|visit) ((?:https?://)?[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?:/\\S*)?)$", ci).find(t)
        if (m != null) {
            val raw = m.groupValues[1]
            return make(ActionType.OPEN_BROWSER, "url" to if (raw.startsWith("http", true)) raw else "https://$raw")
        }
        m = if (namesAnApp) null
        else Regex("^(?:google|search(?: the web| online)?(?: for)?|look up) (.{2,200})$", ci).find(t)
        if (m != null) return make(ActionType.OPEN_BROWSER, "query" to m.groupValues[1].trim())
        m = Regex("^share (?:this )?(.{2,1000})$", ci).find(t)
        if (m != null) return make(ActionType.SHARE_TEXT, "text" to m.groupValues[1].trim())

        // --- Destructive whole-store clears: the registry always confirms ---
        if (Regex("^(?:clear|delete|erase) (?:(?:all|my|the|of|our)\\s+)*(?:conversation|chat)(?:s| history)?$", ci).matches(t)) {
            return make(ActionType.CLEAR_CONVERSATIONS)
        }

        // --- Open an app (last, so more specific rules win) ---
        m = Regex("^(?:open|launch|start) (.{2,40})$", ci).find(t)
        if (m != null) {
            val app = m.groupValues[1].replace(Regex("^the ", ci), "").replace(Regex(" app$", ci), "").trim()
            if (app.split(" ").size <= 3) return make(ActionType.OPEN_APP, "appName" to app)
        }
        return null
    }
}

