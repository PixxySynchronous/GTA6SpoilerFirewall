package com.piyush.spoilerfirewall

import java.text.Normalizer
import java.util.Locale

enum class Verdict { SAFE, SPOILER, UNKNOWN }

/**
 * Local, offline text rules. Pure Kotlin so it can be unit-tested on the JVM.
 *
 * Policy (deliberately strict for launch week):
 *  - Any Grand Theft Auto mention at all (GTA, GTA 5, GTA Online, Vice City,
 *    Rockstar Games, Los Santos, ...) -> SPOILER.
 *  - A character / ambiguous word (rockstar, lucia, jason, trevor, ...) together
 *    with a risk word (ending, dies, leak, mission, ...) -> SPOILER.
 *  - Too little text to judge -> UNKNOWN (stays hidden).
 *  - Otherwise SAFE.
 */
object SpoilerRules {

    // Must be declared before the term lists below, which normalize() during init.
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    /** Anything matching one of these is blocked outright: the whole GTA franchise. */
    private val gameTitles = listOf(
        "gta", "grand theft auto", "grandtheftauto",
        "gta6", "gtavi", "gta5", "gtav", "gta4", "gtaiv", "gta3", "gtaiii", "gtao", "gtarp",
        "rockstar games", "rockstargames", "rockstar north",
        "vice city", "liberty city", "los santos", "leonida", "san andreas"
    ).map(::normalize)

    /** Words that only count together with a risk word (common names on their own). */
    private val gameContext = listOf(
        "rockstar", "lucia", "jason", "trevor", "franklin", "niko bellic", "cj"
    ).map(::normalize)

    private val riskTerms = listOf(
        "ending", "endings", "dies", "die", "death", "dead", "killed", "kills", "betray", "betrayal",
        "final mission", "last mission", "mission", "missions", "final boss", "boss",
        "plot", "twist", "story", "storyline", "spoiler", "spoilers", "leak", "leaks", "leaked",
        "reveal", "revealed", "secret", "cutscene", "credits", "walkthrough", "playthrough",
        "gameplay", "full game", "chapter", "explained"
    ).map(::normalize)

    fun normalize(raw: CharSequence): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC) // e.g. "Ⅵ" -> "VI"
            .lowercase(Locale.ROOT)
            .replace(separators, " ")
            .trim()

    fun evaluate(text: CharSequence?, minChars: Int = 3): Verdict {
        if (text.isNullOrBlank()) return Verdict.UNKNOWN
        val norm = normalize(text)
        if (norm.isEmpty()) return Verdict.UNKNOWN
        val padded = " $norm "
        fun has(term: String) = padded.contains(" $term ")

        if (gameTitles.any(::has)) return Verdict.SPOILER
        if (gameContext.any(::has) && riskTerms.any(::has)) return Verdict.SPOILER
        return if (norm.length >= minChars) Verdict.SAFE else Verdict.UNKNOWN
    }

    fun isSpoiler(text: CharSequence?): Boolean = evaluate(text, 0) == Verdict.SPOILER
}
