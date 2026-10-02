package com.pragon.mobile

/**
 * Turns what the speech recognizer heard (or what was typed) into something Pragon can act on.
 * No Android classes in here on purpose, so it is easy to test on a plain JVM.
 *
 * Control phrases work alone or with "pragon" in front:
 *   "im home" / "daddy's home" (or "pragon im home")  -> Wake
 *   "mute" (or "hey pragon mute")                      -> Mute
 *   "goodbye" (or "hey pragon goodbye")                -> Goodbye
 * Without "pragon" the sentence has to be short. Everything else only counts while Float mode is active.
 */
object VoiceParser {

    sealed class Cmd {
        object None : Cmd()
        object Wake : Cmd()
        object Mute : Cmd()
        object Goodbye : Cmd()
        /** Hand this text to the brain (Brain decides: phone action, mode switch, or conversation). */
        data class Handle(val text: String) : Cmd()
    }

    /**
     * A phone action found in a sentence. [soft] = the verb was vague ("show me", "start", "go to"),
     * so if the app doesn't exist the sentence is probably just conversation and should go to the AI.
     */
    data class Action(val action: String, val value: String = "", val soft: Boolean = false)

    // Speech recognizers often mishear the name. These are the usual suspects.
    private const val STRONG =
        "(?:pragon|paragon|pragan|pragun|pragone|pragonn|praagon|prakon|pergon|pre gone|pro gone|" +
            "prague on|prague one|prug on|pra gone|pra gun)"
    // "dragon" / "bragon" are real words, so they only count as the name at the very start ("hey dragon ...").
    private const val WEAK = "(?:dragon|bragon)"
    private val WAKE = Regex("(?:^| )$STRONG(?= |$)|^(?:(?:hey|hi|hello|ok|okay|yo) )*$WEAK(?= |$)")
    private val FILLER_START = Regex("^(?:(?:hey|hi|hello|ok|okay|yo)\\s+)+")

    fun norm(s: String): String = s.lowercase()
        .replace("\u2019", "").replace("'", "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun hasWake(n: String) = WAKE.containsMatchIn(n)

    /** Removes the wake word and polite filler so "hey pragon please open youtube" -> "open youtube". */
    fun clean(n: String): String {
        var c = n
        c = WAKE.replace(c, " ")
        c = c.replace(Regex("\\s+"), " ").trim()
        c = FILLER_START.replace(c, "").trim()
        c = c.replace(Regex("^(?:(?:please|can you|could you|would you|will you|now|just|i want you to|i need you to)\\s+)+"), "")
        c = c.replace(Regex("(?:\\s+(?:please|now|for me))+$"), "")
        return c.trim()
    }

    /** Normalised + cleaned: the form the action matchers work on. */
    fun cleanCommand(text: String): String = clean(norm(text))

    private val RAW_WAKE = Regex(
        "^(?:(?:hey|hi|hello|ok|okay|yo)[,.!\\s]+)*(?:pragon|paragon|pragan|pragun|pragone|prakon|pergon|dragon|bragon)\\b[,.!?:\\s]*",
        RegexOption.IGNORE_CASE
    )
    /** Strips a leading "Hey Pragon," from the recognizer's raw text but keeps its capitals and punctuation. */
    fun stripWakeRaw(raw: String): String = RAW_WAKE.replace(raw.trim(), "").trim()

    // "I'm home" / "daddy's home" and nothing else, so "I am going home" can never trigger it.
    private val WAKE_PHRASE = Regex(
        "^(?:(?:hey|hi|ok|okay)\\s+)?(?:$STRONG\\s+)?(?:im|i m|i am|iam|daddys|daddy is|daddy|dady|dad is|dads|dad)\\s+(?:back\\s+)?home(?:\\s+(?:now|again|$STRONG))?$"
    )
    private val MUTE_PHRASE = Regex("\\b(?:mute|stop listening|be quiet|go silent|go to sleep)\\b")
    private val GOODBYE_PHRASE = Regex("\\b(?:goodbye|good bye|bye bye|bye|shut down|close yourself|go away)\\b")
    private val SELF_CLOSE = Regex(
        "^(?:(?:hey|ok|okay)\\s+)?(?:close|quit|exit|end|stop|shut down|kill)\\s+(?:the\\s+)?" +
            "(?:pragon|paragon|pragan|pragun|dragon|yourself|float mode|float|voice mode)$"
    )
    /** "close pragon" / "quit yourself" / "close float mode". Must be checked on the RAW text, before the wake word is stripped. */
    fun isSelfClose(text: String): Boolean {
        val n = norm(text)
        if (SELF_CLOSE.matches(n)) return true
        // "hey pragon close yourself": once the name is removed, the target must still be Pragon itself
        return hasWake(n) && Regex("^(?:close|quit|exit|end|stop|shut down|kill) (?:yourself|float mode|float|voice mode)$").matches(clean(n))
    }

    private val NOISE = setOf("um", "uh", "hmm", "mm", "mmm", "ah", "oh", "huh", "er", "erm")

    private fun words(n: String) = n.split(" ").size

    /**
     * @param alts the recognizer's alternatives, best first
     * @param active true when Float mode is listening for commands
     * @param mode current Pragon mode
     * @param needsWake when true, free-form conversation needs the word "Pragon"
     */
    fun parse(alts: List<String>, active: Boolean, mode: PMode = PMode.MASTER, needsWake: Boolean = false): Cmd {
        val norms = alts.map { norm(it) }.filter { it.isNotEmpty() }
        if (norms.isEmpty()) return Cmd.None

        // 0. "close pragon / close yourself / close float mode" must never become "close the current app"
        if (norms.any { SELF_CLOSE.matches(it) }) return Cmd.Goodbye

        // 1. control phrases. Without "pragon" the sentence must be short, so a long sentence from a video can't trigger them.
        for (n in norms) if (GOODBYE_PHRASE.containsMatchIn(n) && (hasWake(n) || words(n) <= 3)) return Cmd.Goodbye
        for (n in norms) if (MUTE_PHRASE.containsMatchIn(n) && (hasWake(n) || words(n) <= 4)) return Cmd.Mute
        for (n in norms) if (WAKE_PHRASE.matches(n)) return Cmd.Wake
        if (!active) return Cmd.None

        // 2. a mode switch or a phone action: first alternative that makes sense
        for ((i, n) in norms.withIndex()) {
            val c = clean(n)
            if (c.isEmpty()) continue
            if (modeSwitch(c) != null || voiceChange(c) != null || actionFor(c) != null) {
                val raw = stripWakeRaw(alts.getOrNull(i) ?: c).ifBlank { c }
                return Cmd.Handle(raw)
            }
        }

        // 3. nothing matched: is it conversation?
        val first = norms.first()
        val c = clean(first)
        if (c.isEmpty() || c in NOISE) return Cmd.None
        val raw = stripWakeRaw(alts[0]).ifBlank { c }
        return when (mode) {
            PMode.AGENT -> if (hasWake(first) && c.length >= 3) Cmd.Handle(raw) else Cmd.None
            else -> {
                val addressed = hasWake(first)
                val ok = addressed || (!needsWake && words(c) <= 60)
                if (ok && c.length >= 2) Cmd.Handle(raw) else Cmd.None
            }
        }
    }

    // ── voice changer ────────────────────────────────────────────────────

    private val VOICE_NEXT = Regex(
        "^(?:(?:change|switch|swap|alter|shift|update)(?: to)?(?: (?:your|the|my|a))?(?: (?:new|different|another|other|next))? ?voice" +
            "|(?:use|try|give me|get)(?: (?:a|another|the))?(?: (?:new|different|other|next))? voice" +
            "|(?:next|new|different|another) voice|voice changer|sound different)(?: please)?$"
    )
    private val VOICE_RESET = Regex(
        "^(?:(?:reset|restore|revert)(?: (?:your|the))?(?: (?:voice|original voice|default voice))" +
            "|(?:use|go back to|back to)(?: (?:your|the))? (?:original|default|normal) voice|(?:original|default|normal) voice)(?: please)?$"
    )

    /** "change your voice" -> "next", "reset your voice" -> "reset", else null. */
    fun voiceChange(c: String): String? = when {
        VOICE_RESET.matches(c) -> "reset"
        VOICE_NEXT.matches(c) -> "next"
        else -> null
    }

    // ── mode switching ───────────────────────────────────────────────────

    private const val MODE_WORDS = "(assistant|assistance|master|mastermode|masters|muster|agent|asistant)"
    private val MODE_WITH_VERB = Regex(
        "^(?:(?:switch|change|go|set|turn|put|move|enable|activate|enter|use|be)(?: me| yourself| it| over)?(?: back)?" +
            "(?: in| into| to| on)?(?: the)? )$MODE_WORDS(?: mode)?(?: on| please)?$"
    )
    private val MODE_BARE = Regex("^(?:the )?$MODE_WORDS mode(?: on| please)?$")

    /** "agent mode", "switch to assistant mode", "go master" -> the new mode. */
    fun modeSwitch(c: String): PMode? {
        val m = MODE_WITH_VERB.matchEntire(c) ?: MODE_BARE.matchEntire(c) ?: return null
        return when (m.groupValues[1]) {
            "assistant", "assistance", "asistant" -> PMode.ASSISTANT
            "agent" -> PMode.AGENT
            else -> PMode.MASTER
        }
    }

    // ── phone actions ────────────────────────────────────────────────────

    /** Finds a phone action in an already normalised + cleaned sentence, or null if it is not a command. */
    fun actionFor(c: String): Action? {
        if (c.isEmpty()) return null

        // ---- phone buttons ----
        if (Regex("^(?:go )?(?:to )?(?:the )?home(?: screen)?$|^go to home$").matches(c)) return Action("key", "home")
        if (Regex("^(?:go )?back$").matches(c)) return Action("key", "back")
        if (Regex("^(?:show |open )?(?:the )?recents?(?: apps?)?$").matches(c)) return Action("key", "recents")
        if (Regex("^(?:show |open |pull down )?(?:the )?notifications?(?: panel| shade)?$").matches(c)) return Action("key", "notifications")
        if (Regex("^(?:open )?quick settings$").matches(c)) return Action("key", "quick_settings")
        if (Regex("^(?:lock|lock the|lock my)(?: phone| screen)?$|^turn off (?:the )?screen$").matches(c)) return Action("key", "lock")
        if (Regex("^(?:take (?:a )?|capture (?:the )?)?screen ?shot$|^capture (?:the )?screen$").matches(c)) return Action("key", "screenshot")
        if (Regex("^(?:phone )?(?:status|battery(?: level| status| percentage)?)$|^how much battery.*$").matches(c)) return Action("status")

        // ---- flashlight ----
        Regex("^(?:turn |switch )?(on|off)?\\s?(?:the |my )?(?:flash ?light|torch)(?: (on|off))?$").matchEntire(c)?.let {
            val v = it.groupValues[1].ifEmpty { it.groupValues[2] }.ifEmpty { "toggle" }
            return Action("flashlight", v)
        }

        // ---- volume / media ----
        if (Regex("^(?:volume up|louder|increase (?:the )?volume|raise (?:the )?volume|turn (?:it|the volume) up)$").matches(c))
            return Action("key", "volume_up")
        if (Regex("^(?:volume down|quieter|lower (?:the )?volume|decrease (?:the )?volume|turn (?:it|the volume) down)$").matches(c))
            return Action("key", "volume_down")
        if (Regex("^(?:pause|play|resume|stop|play pause)(?: the| this)?(?: video| music| song| it)?$").matches(c))
            return Action("key", "play_pause")
        if (Regex("^next(?: video| song| track)?$|^skip(?: this)?(?: video| song| track)?$").matches(c)) return Action("key", "next")
        if (Regex("^(?:previous|prev|last)(?: video| song| track)?$|^go to previous$").matches(c)) return Action("key", "previous")

        // ---- scrolling ----
        Regex("^(scroll|swipe) (up|down|left|right)$").matchEntire(c)?.let {
            val verb = it.groupValues[1]
            val dir = it.groupValues[2]
            // "scroll down" = move the content up = swipe up
            val swipe = if (verb == "scroll") when (dir) { "down" -> "up"; "up" -> "down"; else -> dir } else dir
            return Action("swipe", swipe)
        }

        // ---- timer / alarm ----
        Regex("^(?:set |start |create )?(?:a |an )?timer (?:for |of )?(.+)$").matchEntire(c)?.let {
            val s = durationSeconds(it.groupValues[1]) ?: return@let
            return Action("timer", s.toString())
        }
        Regex("^(?:set |create )?(?:an? )?alarm (?:for|at) (.+)$|^wake me (?:up )?at (.+)$").matchEntire(c)?.let {
            val t = clockTime(it.groupValues[1].ifEmpty { it.groupValues[2] }) ?: return@let
            return Action("alarm", t)
        }

        // ---- YouTube / web search ----
        Regex("^(?:search|find|look up) (?:for )?(.+?) (?:on|in) youtube$").matchEntire(c)?.let { return Action("youtube_search", it.groupValues[1]) }
        Regex("^play (.+?) (?:on|in) youtube$").matchEntire(c)?.let { return Action("youtube_search", it.groupValues[1]) }
        Regex("^(?:search youtube|youtube search|search youtube for|youtube) (?:for )?(.+)$").matchEntire(c)?.let { return Action("youtube_search", it.groupValues[1]) }
        Regex("^(?:google|search google for|search for|search|look up) (.+)$").matchEntire(c)?.let { return Action("web_search", it.groupValues[1]) }
        // "play despacito" -> YouTube (the bare "play"/"play music" cases were handled above)
        Regex("^play (.{2,60})$").matchEntire(c)?.let { return Action("youtube_search", it.groupValues[1], soft = true) }

        // ---- call / type ----
        Regex("^(?:call|dial|phone) (?:number )?([0-9+*# ]{3,})$").matchEntire(c)?.let { return Action("call", it.groupValues[1].replace(" ", "")) }
        Regex("^type (.+)$").matchEntire(c)?.let { return Action("type_text", it.groupValues[1]) }

        // ---- close an app ("close youtube", "close this app", "close") ----
        Regex("^(?:close|quit|exit|kill|terminate|force stop|force close)(?: (.*))?$").matchEntire(c)?.let {
            val target = it.groupValues[1].trim().removePrefix("the ").removePrefix("app ").removeSuffix(" app").trim()
            if (target == "yourself" || target == "float mode" || target == "float" || target == "pragon") return null
            return Action("close_app", target)
        }
        // "shut down X" / "end X" only with a target, never alone
        Regex("^(?:shut down|shut|end) (.+)$").matchEntire(c)?.let {
            val target = it.groupValues[1].trim().removePrefix("the ").removePrefix("app ").removeSuffix(" app").trim()
            if (target.isEmpty() || target in setOf("yourself", "float mode", "float", "pragon", "it", "this", "down")) return null
            return Action("close_app", target)
        }

        // ---- open an app or a site ("open instagram", "open youtube.com") ----
        Regex("^(open|launch|start|run|go to|switch to|take me to|show me)(?: the)?(?: app)? (.+?)(?: app)?$").matchEntire(c)?.let {
            val verb = it.groupValues[1]
            val target = it.groupValues[2].trim()
            if (target.isEmpty() || words(target) > 4) return null   // a whole sentence, not an app name
            val soft = verb != "open" && verb != "launch"
            if (Regex("^[a-z0-9-]+ (?:dot )?(?:com|org|net|in|io)$").matches(target)) {
                return Action("open_url", target.replace(" dot ", ".").replace(" ", "."))
            }
            return Action("open_app", target, soft)
        }
        return null
    }

    // ── helpers for timer / alarm ────────────────────────────────────────

    private val NUM_WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty five" to 45,
        "forty" to 40, "fifty" to 50, "sixty" to 60,
    )

    /** "5 minutes", "1 hour 30 minutes", "half an hour", "90 seconds", "10" (= minutes) -> seconds, or null. */
    fun durationSeconds(s0: String): Int? {
        val s = s0.trim()
        if (s.isEmpty()) return null
        if (Regex("^half an hour$|^half hour$").matches(s)) return 1800
        var total = 0
        var found = false
        val re = Regex("(\\d+|forty five|[a-z]+) ?(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b")
        for (m in re.findAll(s)) {
            val n = m.groupValues[1].toIntOrNull() ?: NUM_WORDS[m.groupValues[1]] ?: continue
            val u = m.groupValues[2]
            total += when {
                u.startsWith("h") -> n * 3600
                u.startsWith("m") -> n * 60
                else -> n
            }
            found = true
        }
        if (!found) {
            val bare = Regex("^(\\d+)$").matchEntire(s)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            total = bare * 60
            found = true
        }
        return if (found && total in 1..86399) total else null
    }

    /** "7 30 pm", "7am", "19 45", "7" -> "HH:MM" (24 h), or null. */
    fun clockTime(s0: String): String? {
        val s = s0.trim()
        val m = Regex("^(\\d{1,2})(?: ?(\\d{2}))?(?: ?(a m|p m|am|pm))?(?: oclock)?$").matchEntire(s) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3].replace(" ", "")
        if (min > 59 || h > 23) return null
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        return "%02d:%02d".format(h, min)
    }
}
