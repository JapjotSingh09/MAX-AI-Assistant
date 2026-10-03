package com.max.assistant.assistant.nlu

import com.max.assistant.assistant.ActionType

/**
 * What the user wants DONE INSIDE an app, as far as routing is concerned.
 *
 * App-agnostic on purpose: SEARCH applies to YouTube, Spotify and anything else
 * that exposes a search deep link. The app is a parameter, resolved later
 * against the real PackageManager.
 */
enum class AppActionKind {
    SEARCH,
    PLAY,
    PROFILE,
    CHAT,
    NAVIGATE,
    COMPOSE,
    OPEN
}

/**
 * ONE row of the intent table.
 *
 * A rule states which signals must be present ([requires]), which must be
 * ABSENT ([forbids]) and which merely strengthen it ([boosts]). It never
 * contains an `if`. That is the whole point: adding an intent is adding a row,
 * and the correctness of the old "battery opens settings" bug is prevented
 * structurally - every settings rule must FORBID [Feature.QUESTION], so a
 * question can never score as an instruction to open a screen.
 */
data class IntentRule(
    val id: String,
    val category: IntentCategory,
    /** All of these must be present for the rule to fire. */
    val requires: Set<Feature>,
    /** If ANY of these is present, the rule cannot fire. This is the safety net. */
    val forbids: Set<Feature> = emptySet(),
    /** Present: +2 score. Absent: no penalty. Used to rank between rules. */
    val boosts: Set<Feature> = emptySet(),
    /** Lower wins ties. Higher wins near-ties. */
    val weight: Int = 0,
    /** Builds the parameters once the rule has won. */
    val build: (Utterance) -> Map<String, Any?> = { emptyMap() }
) {
    /**
     * How strongly this utterance satisfies the rule, or null if it cannot fire.
     *
     * The penalty for extra specific matches is what separates "open wifi
     * settings" (banned: it is a question) from a settings request.
     */
    /** How strongly this utterance satisfies the rule, or null if it cannot fire. */
    fun score(u: Utterance): Int? {
        if (!u.features.containsAll(requires)) return null
        // `forbids` is checked against the COMMAND-shaped features, not the raw
        // ones. "What is my phone model?" contains "phone", which is also a
        // call verb; the question has no imperative in it, so that reading must
        // not veto a device-information request. Without this, one ambiguous
        // word could block an entire intent.
        if (forbids.any { it in u.commandFeatures }) return null
        var s = 10
        boosts.forEach { if (it in u.features) s += 2 }
        return s + weight
    }
}

/**
 * TIE-BREAKING between two rules that describe the SAME kind of thing.
 *
 * Needed because "open battery settings" legitimately satisfies more than one
 * settings row ("open X settings" and "X settings"), and they differ only in
 * which verb phrase they expect. Scoring ties must be resolved deterministically
 * by specificity, never by list order, otherwise behaviour changes whenever a
 * row is inserted.
 *
 * @return a positive number when [preferred] is the more specific reading of
 *   two otherwise equal candidates; 0 when the comparison is inconclusive.
 */
fun moreSpecific(preferred: IntentRule, other: IntentRule): Int {
    if (preferred.requires.size != other.requires.size) {
        return preferred.requires.size - other.requires.size
    }
    // A rule that FORBIDS more shapes is the safer of two equals: it can only
    // fire on a narrower slice of utterances.
    return preferred.forbids.size - other.forbids.size
}