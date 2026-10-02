package com.max.assistant.assistant

// LOCAL FIRST, CLOUD WHEN NEEDED.
// Simple commands are recognised with plain rules: instant, free, and they work offline.
// Anything not recognised returns null, and the ViewModel then asks the cloud AI.
sealed interface ParsedCommand {
    data class Intent(val intent: CommandIntent) : ParsedCommand
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

    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "sixty" to 60
    )

    private fun number(word: String): Int? = word.toIntOrNull() ?: numberWords[word.lowercase()]

    private fun make(action: ActionType, vararg params: Pair<String, Any?>): ParsedCommand? =
        IntentValidator.validate(action.name, mapOf(*params))?.let { ParsedCommand.Intent(it) }

    fun parse(input: String): ParsedCommand? {
        val t = normalize(input)
        val l = t.lowercase()
        var m: MatchResult?

        // Flashlight
        Regex("^(?:turn |switch )?(on|off) (?:the )?(?:flash ?light|torch)$").find(l)?.let { return make(ActionType.TOGGLE_FLASHLIGHT, "state" to it.groupValues[1]) }
        Regex("^(?:turn |switch )?(?:the )?(?:flash ?light|torch) (on|off)$").find(l)?.let { return make(ActionType.TOGGLE_FLASHLIGHT, "state" to it.groupValues[1]) }
        if (Regex("^(?:toggle )?(?:the )?(?:flash ?light|torch)$").matches(l)) return make(ActionType.TOGGLE_FLASHLIGHT, "state" to "toggle")

        // Timer: "set a timer for 10 minutes"
        m = Regex("^(?:set |start )?(?:a )?timer (?:for )?(\\w+) (second|sec|minute|min|hour|hr)s?$").find(l)
        if (m != null) {
            val n = number(m.groupValues[1])
            if (n != null) {
                val unit = m.groupValues[2]
                val mult = if (unit.startsWith("h")) 3600 else if (unit.startsWith("m")) 60 else 1
                return make(ActionType.SET_TIMER, "seconds" to n * mult)
            }
        }

        // Alarm: "set an alarm for 7 AM", "alarm at 6:30 pm"
        m = Regex("^(?:set |create )?(?:an? )?(?:alarm|wake me up) (?:for |at )(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$").find(l)
        if (m != null) {
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            val mer = m.groupValues[3]
            if (mer == "pm" && hour < 12) hour += 12
            if (mer == "am" && hour == 12) hour = 0
            return make(ActionType.SET_ALARM, "hour" to hour, "minute" to minute)
        }

        // Reminder
        m = Regex("^(?:create a |set a |add a )?remind(?:er)?(?: me)?(?: to| for| about)? (.{2,200})$", ci).find(t)
        if (m != null) {
            val body = m.groupValues[1].trim()
            val whenText = Regex("\\b(tomorrow|tonight|today|next week)\\b", ci).find(body)?.value
            return if (whenText != null) make(ActionType.CREATE_REMINDER, "text" to body, "when" to whenText)
            else make(ActionType.CREATE_REMINDER, "text" to body)
        }

        // Camera, notifications
        if (Regex("^(?:open |launch |start )(?:the )?camera$").matches(l)) return make(ActionType.OPEN_CAMERA)
        if (Regex("(?:what|which) notifications (?:did i miss|do i have)|^show (?:my )?notifications$").containsMatchIn(l)) return make(ActionType.SHOW_NOTIFICATIONS)

        // Volume
        if (Regex("^(?:turn |volume )?(?:the )?volume up$|^(?:increase|raise) (?:the )?volume$").matches(l)) return make(ActionType.ADJUST_VOLUME, "direction" to "up")
        if (Regex("^(?:turn |volume )?(?:the )?volume down$|^(?:decrease|lower|reduce) (?:the )?volume$").matches(l)) return make(ActionType.ADJUST_VOLUME, "direction" to "down")
        if (Regex("^(?:mute|silence)(?: (?:the )?(?:phone|volume|sound))?$").matches(l)) return make(ActionType.ADJUST_VOLUME, "direction" to "mute")

        // Settings
        m = Regex("^open (?:the )?(?:(wi-?fi|bluetooth|sound|display|battery|location) )?settings$").find(l)
        if (m != null) {
            val section = m.groupValues[1].replace("-", "")
            return if (section.isEmpty()) make(ActionType.OPEN_SETTINGS) else make(ActionType.OPEN_SETTINGS, "section" to section)
        }

        // WhatsApp
        if (Regex("^(?:open|launch) whats ?app$").matches(l)) return make(ActionType.OPEN_WHATSAPP)
        m = Regex("^(?:whatsapp|message|text|send (?:a )?whatsapp(?: message)? to) (.+?) (?:on whatsapp )?(?:saying|that says|:) (.+)$", ci).find(t)
        if (m != null && l.contains("whats")) return make(ActionType.OPEN_WHATSAPP, "contactName" to m.groupValues[1].trim(), "message" to m.groupValues[2].trim())

        // SMS: "send Rahul a message saying I'll reach in 10 minutes"
        m = Regex("^send (.+?) (?:a |an )?(?:message|text|sms)(?: saying| that says| with|:)? (.+)$", ci).find(t)
            ?: Regex("^(?:message|text|sms) (.+?) (?:saying|that says|:) (.+)$", ci).find(t)
        if (m != null) return make(ActionType.SEND_SMS, "contactName" to m.groupValues[1].trim(), "message" to m.groupValues[2].trim())

        // Calls
        m = Regex("^(?:call|dial|phone|ring) ([+\\d][\\d\\s-]{4,})$").find(l)
        if (m != null) return make(ActionType.OPEN_DIALER, "number" to m.groupValues[1].replace(Regex("[\\s-]"), ""))
        if (Regex("^(?:open (?:the )?(?:dialer|phone)|dial)$").matches(l)) return make(ActionType.OPEN_DIALER)
        m = Regex("^(?:call|phone|ring) (.{1,80})$", ci).find(t)
        if (m != null) return make(ActionType.CALL_CONTACT, "contactName" to m.groupValues[1].trim())

        // Navigation / maps
        m = Regex("^(?:navigate|directions|drive|take me|go) (?:to |me to )(.{2,160})$", ci).find(t)
        if (m != null) return make(ActionType.NAVIGATE, "destination" to m.groupValues[1].trim())
        if (Regex("^(?:open|launch) (?:google )?maps$").matches(l)) return make(ActionType.OPEN_MAPS)
        m = Regex("^(?:find|search(?: for)?|show me|look for) (.{2,100}? (?:near me|nearby|around me))$", ci).find(t)
        if (m != null) return make(ActionType.OPEN_MAPS, "query" to m.groupValues[1].trim())

        // Browser
        m = Regex("^(?:open|go to|visit) ((?:https?://)?[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?:/\\S*)?)$", ci).find(t)
        if (m != null) {
            val raw = m.groupValues[1]
            return make(ActionType.OPEN_BROWSER, "url" to if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw")
        }
        m = Regex("^(?:google|search(?: the web| online)?(?: for)?|look up) (.{2,200})$", ci).find(t)
        if (m != null) return make(ActionType.OPEN_BROWSER, "query" to m.groupValues[1].trim())

        // Open an app (last, so more specific rules win)
        m = Regex("^(?:open|launch|start) (.{2,40})$", ci).find(t)
        if (m != null) {
            val app = m.groupValues[1].replace(Regex("^the ", ci), "").replace(Regex(" app$", ci), "").trim()
            if (app.split(" ").size <= 3) return make(ActionType.OPEN_APP, "appName" to app)
        }
        return null
    }
}
