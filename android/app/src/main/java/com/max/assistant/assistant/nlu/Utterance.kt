package com.max.assistant.assistant.nlu

import com.max.assistant.assistant.ActionType
import java.util.Locale

/**
 * The analysed utterance: text plus the lexical features found in it.
 *
 * Extraction of entities (an app name, a search term, a contact) happens here
 * rather than inside the rules, so a rule only has to say WHICH intent it
 * represents - never how to dig through the sentence.
 */
class Utterance(raw: String) {

    val raw: String = raw.trim()

    /** Wake phrase, politeness and trailing punctuation removed. */
    val text: String = normalize(this.raw)

    val lower: String = this.text.lowercase(Locale.ROOT)

    val words: List<String> = this.lower.split(' ').filter { it.isNotBlank() }

    /** True when the sentence was only a couple of words, e.g. "open battery". */
    val isShort: Boolean = this.words.size <= 3

    /** Every lexical signal in this utterance. */
    val features: Set<Feature> = Lexicon.features(this.raw)

    /**
     * Verbs that only command something when the sentence is imperative.
     * In a question they describe the state being asked about.
     */
    private val imperativeVerbs = setOf(
        Feature.ENABLE, Feature.DISABLE, Feature.TOGGLE,
        Feature.RAISE, Feature.LOWER,
        // "phone" is both a DEVICE noun and a CALL verb. In "what is my phone
        // model?" the call reading has to be dropped, or it vetoes the
        // device-information intent and the request falls through to the AI.
        Feature.CALL_VERB
    )

    /**
     * The features that may act as COMMANDS.
     *
     * "Is Bluetooth on?" contains the word "on", but it is not asking for
     * Bluetooth to be switched on - it is asking ABOUT its state. Treating that
     * "on" as an instruction is the same class of mistake as reading "battery"
     * as a request for Battery Settings, one level up: a keyword standing in
     * for an intent.
     *
     * So in a question, imperative verbs are demoted to being the subject of the
     * question rather than the action. Imperatives are the only features
     * affected, because they are the only ones that change what an action WOULD
     * do; demoting them in a question cannot turn "turn on the flashlight" into
     * something else, since that sentence has no question shape at all.
     */
    val commandFeatures: Set<Feature> =
        if (Feature.QUESTION in features) features - imperativeVerbs else features

    fun has(vararg f: Feature): Boolean = f.any { it in features }

    fun lacks(vararg f: Feature): Boolean = f.none { it in features }

    /** The imperative (command-shaped) features of this utterance. */
    fun hasCommand(vararg f: Feature): Boolean = f.any { it in commandFeatures }

    /** The sentence with [strip] and its leading verb removed. */
    fun tailAfter(vararg strip: String): String {
        var s = text
        for (p in strip) {
            s = Regex("^\\b" + Regex.escape(p) + "\\b", RegexOption.IGNORE_CASE).replace(s, "")
        }
        return s.trim().trim(',', '?', '.', '-').trim()
    }

    companion object {
        private val CI = RegexOption.IGNORE_CASE

        /**
         * Filler removal. Kept separate from CommandParser.normalize (which also
         * strips durations and alarm words) so the two never drift apart.
         */
        fun normalize(input: String): String = input.trim()
            .replace(Regex("[.!?]+$"), "")
            .replace(Regex("^(hey |hi |ok |okay )?max[, ]+", CI), "")
            .replace(Regex("^((please|can you|could you|would you|pls) )+", CI), "")
            .replace(Regex("\\s+please$", CI), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}