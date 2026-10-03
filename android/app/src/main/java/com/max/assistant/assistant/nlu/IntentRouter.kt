package com.max.assistant.assistant.nlu

import com.max.assistant.assistant.ActionType

/**
 * THE INTENT ROUTER.
 *
 * The problem this replaces: the old parser was a flat cascade of regexes that
 * jumped from a KEYWORD to an ACTION. So "my battery is at 15%, how long will
 * it last?" fell through to the cloud AI, which saw a tool called
 * OPEN_SETTINGS{battery} and used it. The word "battery" implied a screen.
 *
 * How this is different:
 *  1. A sentence is reduced to TYPED LEXICAL FEATURES ([Feature]), not keywords.
 *  2. Each candidate intent is a DECLARATIVE ROW ([IntentRule]) stating which
 *     features it REQUIRES and which it FORBIDS.
 *  3. Scoring picks a winner; if the winner is weak, or two intents tie, MAX
 *     ASKS instead of guessing.
 *
 * The most important rule in the table is a structural guarantee that makes the
 * reported bug impossible:
 *
 *     every OPEN_SETTINGS rule FORBIDS Feature.QUESTION
 *
 * So no question can ever route to a settings screen. "How much battery do I
 * have?" and "Open battery settings" are now different intents with different
 * actions, separated by question shape rather than by which nouns appear.
 */
object IntentRouter {

    /** Below this a reading is not acted on; the router asks instead. */
    private const val EXECUTE_THRESHOLD = 10

    /** The winner must beat the runner-up by this much, or MAX asks. */
    private const val MARGIN = 3

    /**
     * Features that mean "the user is ASKING". A settings screen is a place to
     * GO, so a question can never be one - this set is what makes that true.
     *
     * Shared with [Lexicon.ASKING_SHAPE], so "what counts as a question" has
     * exactly one definition in the project.
     */
    private val QUESTION_SHAPE = Lexicon.ASKING_SHAPE

    /** Maps a settings target onto the executor's section key. */
    private fun sectionFor(u: Utterance): String? = when {
        u.has(Feature.TARGET_BATTERY) -> "battery"
        u.has(Feature.TARGET_WIFI) -> "wifi"
        u.has(Feature.TARGET_BLUETOOTH) -> "bluetooth"
        u.has(Feature.TARGET_SOUND) -> "sound"
        u.has(Feature.TARGET_DISPLAY) -> "display"
        u.has(Feature.TARGET_LOCATION) -> "location"
        u.has(Feature.TARGET_STORAGE) -> "storage"
        else -> null
    }

    /** An OPEN_SETTINGS row that always bans question shape. */
    private fun settingsRule(
        id: String,
        verb: Feature,
        weight: Int,
        bans: Set<Feature> = QUESTION_SHAPE
    ) = IntentRule(
        id = id,
        category = IntentCategory.DEVICE_ACTION,
        requires = setOf(Feature.SETTINGS_SCREEN, verb),
        forbids = bans,
        weight = weight,
        build = { u -> mapOf("section" to sectionFor(u)) }
    )
/**
     * The intent table. Read top to bottom, this is the assistant's whole
     * device-capability surface; adding a capability is adding one row.
     *
     * The DEVICE_INFO rows come first deliberately. They are the ones the old
     * parser lacked, and they are what turns "battery" into an ANSWER rather
     * than a settings screen.
     */
    private val RULES: List<IntentRule> = listOf(

        // ===== DEVICE INFORMATION - questions about facts ==============
        IntentRule(
            id = "battery_estimate",
            category = IntentCategory.DEVICE_INFO,
            requires = setOf(Feature.TARGET_BATTERY, Feature.TIME_CUE),
            // "how long will it last" is a question even with no "?"
            boosts = setOf(Feature.QUESTION, Feature.LEVEL_CUE),
            weight = 6,
            build = { mapOf("subject" to "battery") }
        ),
        IntentRule(
            id = "battery_level",
            category = IntentCategory.DEVICE_INFO,
            requires = setOf(Feature.TARGET_BATTERY, Feature.INFORMATION_REQUEST),
            forbids = setOf(Feature.SETTINGS_SCREEN, Feature.SAVER_CUE, Feature.DRAIN_CUE),
            boosts = setOf(Feature.LEVEL_CUE, Feature.STATUS_CUE, Feature.QUESTION, Feature.INFO_CUE),
            weight = 4,
            build = { mapOf("subject" to "battery") }
        ),
        IntentRule(
            id = "battery_saver",
            category = IntentCategory.DEVICE_ACTION,
            requires = setOf(Feature.TARGET_BATTERY, Feature.SAVER_CUE),
            forbids = QUESTION_SHAPE,
            build = { u ->
                val state = when {
                    u.has(Feature.DISABLE) -> "off"
                    u.has(Feature.ENABLE) -> "on"
                    else -> "toggle"
                }
                mapOf("state" to state)
            }
        ),
        IntentRule(
            id = "wifi_status",
            category = IntentCategory.DEVICE_INFO,
            requires = setOf(Feature.TARGET_WIFI, Feature.INFORMATION_REQUEST),
            forbids = setOf(Feature.SETTINGS_SCREEN, Feature.TARGET_STORAGE),
            boosts = setOf(Feature.STATUS_CUE, Feature.QUESTION, Feature.LEVEL_CUE),
            build = { emptyMap() }
        ),
        IntentRule(
            id = "bluetooth_status",
            category = IntentCategory.DEVICE_INFO,
            requires = setOf(Feature.TARGET_BLUETOOTH, Feature.INFORMATION_REQUEST),
            // "Is Bluetooth on?" must reach here, not be read as a command to
            // enable it: in a question, on/off describe the state, not the ask.
            forbids = setOf(Feature.SETTINGS_SCREEN),
            boosts = setOf(Feature.STATUS_CUE, Feature.QUESTION),
            build = { emptyMap() }
        ),
        IntentRule(
            id = "storage_info",
            category = IntentCategory.DEVICE_INFO,
            requires = setOf(Feature.TARGET_STORAGE, Feature.INFORMATION_REQUEST),
            forbids = setOf(Feature.SETTINGS_SCREEN),
            boosts = setOf(Feature.STATUS_CUE, Feature.QUESTION, Feature.LEVEL_CUE),
            build = { emptyMap() }
        ),
        IntentRule(
            id = "device_info",
            category = IntentCategory.DEVICE_INFO,
            requires = setOf(Feature.TARGET_DEVICE, Feature.INFORMATION_REQUEST),
            forbids = setOf(Feature.SETTINGS_SCREEN, Feature.CALL_VERB, Feature.APP_NAME),
            boosts = setOf(Feature.QUESTION, Feature.INFO_CUE, Feature.STATUS_CUE),
            build = { emptyMap() }
        ),
        IntentRule(
            id = "display_info",
            category = IntentCategory.DEVICE_INFO,
            requires = setOf(Feature.TARGET_DISPLAY, Feature.INFORMATION_REQUEST),
            forbids = setOf(Feature.SETTINGS_SCREEN),
            boosts = setOf(Feature.QUESTION, Feature.STATUS_CUE, Feature.LEVEL_CUE),
            build = { emptyMap() }
        ),
        IntentRule(
            id = "sound_info",
            category = IntentCategory.DEVICE_INFO,
            requires = setOf(Feature.TARGET_SOUND, Feature.INFORMATION_REQUEST),
            forbids = setOf(Feature.SETTINGS_SCREEN, Feature.RAISE, Feature.LOWER, Feature.DISABLE),
            boosts = setOf(Feature.QUESTION, Feature.STATUS_CUE, Feature.LEVEL_CUE),
            build = { emptyMap() }
        )
    )
// ---- Settings screens: the rows that carry the QUESTION ban -------
    //
    // These are the ones the old parser got wrong. Each FORBIDS question shape,
    // so "how much battery do I have?" cannot reach them even though it
    // contains the word "battery". A settings rule only fires for an
    // IMPERATIVE, which is exactly what "open battery settings" is.

    private val SETTINGS_RULES: List<IntentRule> = listOf(
        settingsRule("open_settings_section", Feature.OPEN, weight = 3),
        // "show me bluetooth settings" - SHOW is an explicit screen request.
        settingsRule(
            "open_settings_section_show", Feature.SHOW, weight = 2,
            bans = setOf(Feature.QUESTION, Feature.STATUS_CUE, Feature.LEVEL_CUE, Feature.TIME_CUE)
        ),
        // "take me to battery settings" / "battery settings kholo".
        IntentRule(
            id = "open_settings_section_go",
            category = IntentCategory.DEVICE_ACTION,
            requires = setOf(Feature.SETTINGS_SCREEN, Feature.GO_TO),
            forbids = QUESTION_SHAPE,
            build = { u -> mapOf("section" to sectionFor(u)) }
        ),
        // "open settings" with no section at all.
        IntentRule(
            id = "open_settings_plain",
            category = IntentCategory.DEVICE_ACTION,
            requires = setOf(Feature.TARGET_SETTINGS, Feature.OPEN),
            forbids = QUESTION_SHAPE,
            build = { mapOf("section" to null) }
        ),
        // A bare "battery settings" needs the verb as a BOOST rather than a
        // requirement, because "battery settings kholo" and "take me to battery
        // settings" express the move with very different verbs.
        IntentRule(
            id = "open_battery_settings_explicit",
            category = IntentCategory.DEVICE_ACTION,
            requires = setOf(Feature.TARGET_SETTINGS),
            forbids = setOf(Feature.QUESTION, Feature.LEVEL_CUE, Feature.TIME_CUE, Feature.STATUS_CUE),
            boosts = setOf(Feature.OPEN, Feature.GO_TO, Feature.SHOW),
            weight = 4,
            build = { u -> mapOf("section" to sectionFor(u)) }
        )
    )

    /** Volume and flashlight: unambiguous controls with no question reading. */
    private val CONTROL_RULES: List<IntentRule> = listOf(
        IntentRule(
            id = "flashlight",
            category = IntentCategory.DEVICE_ACTION,
            requires = setOf(Feature.TARGET_FLASHLIGHT),
            forbids = setOf(Feature.QUESTION),
            build = { u ->
                val state = when {
                    u.hasCommand(Feature.ENABLE) -> "on"
                    u.hasCommand(Feature.DISABLE) -> "off"
                    else -> "toggle"
                }
                mapOf("state" to state)
            }
        ),
        IntentRule(
            id = "volume",
            category = IntentCategory.DEVICE_ACTION,
            requires = setOf(Feature.TARGET_VOLUME),
            forbids = setOf(Feature.QUESTION),
            build = { u ->
                val dir = when {
                    u.hasCommand(Feature.RAISE) -> "up"
                    u.hasCommand(Feature.LOWER) -> "down"
                    else -> "mute"
                }
                mapOf("direction" to dir)
            }
        )
    )

    /**
     * Sorts candidates: score first, then specificity.
 *
 * The second key matters because several settings rows can legitimately
 * describe the same utterance ("open battery settings" satisfies both the
 * "open X settings" row and the "X settings" row). Without a deterministic
 * tie-break they tie, the router sees a near-miss, and MAX asks about a
 * perfectly clear command.
 */
private fun rank(a: Candidate, b: Candidate): Int {
    if (a.score != b.score) return b.score - a.score
    val spec = moreSpecific(a.rule, b.rule)
    if (spec != 0) return spec
    // Final, stable fallback: rule id. Deterministic across runs and across
    // insertions, so behaviour never depends on where a row sits in the list.
    return a.rule.id.compareTo(b.rule.id)
}

/** One reading: which rule fired and how strongly. */
    private data class Candidate(
        val rule: IntentRule,
        val score: Int,
        val parameters: Map<String, Any?>
    )

    // ===== APP LAUNCH and APP-SPECIFIC ACTIONS =========================

    /** Verbs that mean "do this to/inside an app". */
    private val appVerbs = listOf(
        // launch
        "open", "launch", "start", "run", "load", "access", "browse", "fire up",
        "khol", "kholo", "chalu", "lagao",
        // in-app operations
        "search", "look for", "find", "lookup", "play", "listen", "search karo",
        "dhundho", "dhundh", "talash", "bajao"
    )

    /**
     * Routes an app request: "open Discord", "search YouTube for tutorials".
     *
     * Generic by construction. The app name is NOT recognised here - it is
     * handed to AppResolver, which matches it against what is really installed.
     * So this returns null unless there is a plausible APP NAME to resolve,
     * which is what stops it swallowing ordinary commands.
     *
     * @param fallbackApp the app from short-lived context, used only when the
     *   user did NOT name one explicitly - see ConversationContext.
     */
    fun routeApp(text: String, fallbackApp: String? = null): IntentFrame? {
        val u = Utterance(text)
        if (u.words.isEmpty()) return null

        // A clear DEVICE reading always wins over an app reading.
        // "What's my battery percentage?" must never become "open YouTube",
        // even when YouTube is the remembered context app.
        val device = route(text)
        if (device.isExecutable && device.action != null) return null
        if (device.category == IntentCategory.AMBIGUOUS && device.clarification != null) return null

        val operation = when {
            u.hasCommand(Feature.SEARCH_VERB) -> AppActionKind.SEARCH
            u.hasCommand(Feature.PLAY_VERB) -> AppActionKind.PLAY
            u.has(Feature.PROFILE_CUE) -> AppActionKind.PROFILE
            u.has(Feature.CHAT_CUE) -> AppActionKind.CHAT
            u.has(Feature.DIRECTIONS_CUE) -> AppActionKind.NAVIGATE
            else -> AppActionKind.OPEN
        }

        // Pull the app name off the front of the sentence.
        var appName = Entities.appName(u.text, appVerbs)
        // An explicit "on <app>" / "in <app>" clause is an unambiguous choice.
        val trailing = Entities.trailingApp(u.text)
        if (!trailing.isNullOrBlank()) appName = trailing

        // The QUERY is the sentence minus the verb and minus the app name, so
        // "search YouTube for Android tutorials" yields "Android tutorials" and
        // not the app name repeated back.
        val querySource = if (appName.isNullOrBlank()) u.text
        else u.text.replace(appName, " ", ignoreCase = true)

        // The entity: what to look for, play, or navigate to.
        val entity = when (operation) {
            AppActionKind.SEARCH, AppActionKind.PLAY -> Entities.searchQuery(querySource)
            AppActionKind.NAVIGATE, AppActionKind.PROFILE, AppActionKind.CHAT ->
                Entities.target(querySource)
            else -> null
        }

        // No explicit app: fall back to the one just used, but ONLY for an in-app
        // OPERATION ("search X" after "open YouTube"). A plain launch must name
        // its app: a sentence that merely failed to parse must never be turned
        // into "open whatever was open before".
        if (appName.isNullOrBlank() && operation != AppActionKind.OPEN) {
            appName = fallbackApp
        }
        // Still nothing: a bare "search for X" is a WEB search.
        if (appName.isNullOrBlank()) {
            if (operation == AppActionKind.SEARCH && !entity.isNullOrBlank()) {
                return IntentFrame(
                    category = IntentCategory.APP_ACTION,
                    action = ActionType.WEB_SEARCH,
                    parameters = mapOf("query" to entity),
                    confidence = Confidence.MEDIUM,
                    source = "app:web-search"
                )
            }
            return null
        }

        return IntentFrame(
            category = if (operation == AppActionKind.OPEN) {
                IntentCategory.APP_LAUNCH
            } else {
                IntentCategory.APP_ACTION
            },
            // A plain launch uses OPEN_APP, the pre-existing tool, so nothing
            // that already depends on it changes. Only an in-app OPERATION needs
            // the new generic action.
            action = if (operation == AppActionKind.OPEN) {
                ActionType.OPEN_APP
            } else {
                ActionType.APP_ACTION
            },
            parameters = buildMap {
                put("appName", appName)
                put("operation", operation.name)
                if (!entity.isNullOrBlank()) put("entity", entity)
            },
            confidence = Confidence.MEDIUM,
            source = "app:${operation.name.lowercase()}"
        )
    }
/**
     * Routes [raw] to an [IntentFrame].
     *
     * Runs on the voice hot path, so it is deliberately simple: one pass of
     * feature extraction, then a small linear scan over the rule table. No
     * regex backtracking, no I/O, no allocation beyond the result.
     */
    fun route(raw: String): IntentFrame {
        val u = Utterance(raw)

        if (u.words.isEmpty()) {
            return IntentFrame(
                IntentCategory.AMBIGUOUS,
                confidence = Confidence.LOW,
                clarification = "I didn't catch that. Could you say it again?"
            )
        }

        val candidates = ArrayList<Candidate>(8)
        for (rule in RULES + SETTINGS_RULES + CONTROL_RULES) {
            val s = rule.score(u) ?: continue
            candidates.add(Candidate(rule, s, rule.build(u)))
        }

        if (candidates.isEmpty()) {
            // Nothing in the device table matched. That is a NORMAL outcome: it
            // means the sentence is a question for the AI, not a device command.
            return IntentFrame(
                category = IntentCategory.AI_QUERY,
                confidence = Confidence.MEDIUM,
                source = "router:no-device-match"
            )
        }

        candidates.sortWith(::rank)
        val best = candidates.first()
        val runnerUp = candidates.getOrNull(1)

        if (best.score < EXECUTE_THRESHOLD) return ask(best, runnerUp)
        // A near-tie between two DIFFERENT categories is a genuine ambiguity -
        // "open battery" could mean the screen or the numbers. Only ask when the
        // alternatives would actually do something different; two rows that
        // both mean "open this settings screen" are not an ambiguity.
        if (runnerUp != null &&
            best.score - runnerUp.score < MARGIN &&
            runnerUp.rule.category != best.rule.category
        ) return ask(best, runnerUp)

        val confidence = when {
            runnerUp == null -> Confidence.HIGH
            runnerUp.rule.category != best.rule.category &&
                best.score - runnerUp.score < 6 -> Confidence.MEDIUM
            // A close runner-up that agrees on what to DO is not a doubt, just
            // two phrasings of the same intent matching.
            else -> Confidence.HIGH
        }

        return IntentFrame(
            category = best.rule.category,
            action = actionFor(best.rule.id),
            parameters = best.parameters,
            confidence = confidence,
            source = "router:${best.rule.id}",
            alternatives = candidates.drop(1).take(2).map { it.rule.id }
        )
    }

    /**
     * Builds the clarification question instead of acting.
     *
     * The wording names the two concrete things MAX could do. "Did you mean?"
     * with no options is not a real question, and picking for the user is
     * exactly the behaviour this project is trying to stop.
     */
    private fun ask(best: Candidate, runnerUp: Candidate?): IntentFrame {
        val subject = best.parameters["subject"] as? String
            ?: best.parameters["section"] as? String
        val what = when (best.rule.category) {
            IntentCategory.DEVICE_INFO -> "see your ${subject ?: "device"} information"
            IntentCategory.DEVICE_ACTION -> "open ${subject?.let { "$it " } ?: ""}settings"
            else -> "do that"
        }
        val alternative = when (runnerUp?.rule?.category) {
            IntentCategory.DEVICE_INFO ->
                "see your ${runnerUp.parameters["subject"] ?: "device"} information"
            IntentCategory.DEVICE_ACTION -> "open settings instead"
            else -> null
        }
        val q = if (alternative != null && alternative != what) {
            "Do you want me to $what, or $alternative?"
        } else {
            "Do you want me to $what?"
        }
        return IntentFrame(
            category = IntentCategory.AMBIGUOUS,
            confidence = Confidence.LOW,
            source = "router:ambiguous:${best.rule.id}",
            alternatives = listOfNotNull(best.rule.id, runnerUp?.rule?.id),
            clarification = q
        )
    }

    /**
     * Maps a rule id onto the executable action.
     *
     * A table rather than a field on the rule because the DEVICE_INFO rows
     * share actions - "battery_level", "wifi_status" and "storage_info" are
     * all read actions, and the `subject` parameter tells the executor which
     * sensor to read.
     */
    private fun actionFor(id: String): ActionType? = when (id) {
        "battery_estimate" -> ActionType.BATTERY_ESTIMATE_QUERY
        "battery_level" -> ActionType.READ_BATTERY_LEVEL
        "battery_saver" -> ActionType.SET_BATTERY_SAVER
        "wifi_status" -> ActionType.READ_WIFI_STATUS
        "bluetooth_status" -> ActionType.READ_BLUETOOTH_STATUS
        "storage_info" -> ActionType.READ_STORAGE
        "device_info" -> ActionType.READ_DEVICE_INFO
        "display_info" -> ActionType.READ_DISPLAY_INFO
        "sound_info" -> ActionType.READ_SOUND_INFO
        "open_settings_section", "open_settings_section_show",
        "open_settings_section_go", "open_settings_plain",
        "open_battery_settings_explicit" -> ActionType.OPEN_SETTINGS
        "flashlight" -> ActionType.TOGGLE_FLASHLIGHT
        "volume" -> ActionType.ADJUST_VOLUME
        else -> null
    }

    /** Exposed for tests, so adding a row without an action is detectable. */
    fun ruleIds(): List<String> = (RULES + SETTINGS_RULES + CONTROL_RULES).map { it.id }
}
