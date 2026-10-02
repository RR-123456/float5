package com.pragon.mobile

import android.content.Context
import org.json.JSONObject

/**
 * Pragon's brain for Standalone mode: one entry point used by both the chat screen and the float bubble.
 *
 *  ASSISTANT  only talks. Phone commands get a polite "I'm in Assistant mode".
 *  MASTER     (default) talks AND acts, and says what it did: "open youtube" opens it, then "YouTube opened, sir."
 *  AGENT      acts with as little talk as possible: silent on success, speaks only failures and answers.
 *
 * Plain phone commands are recognised locally and run instantly (no AI round trip, works offline, no API key).
 * Everything else goes to the chosen AI engine with Pragon's persona and streams back sentence by sentence.
 */
object Brain {

    /** What the UI should do with the answer. */
    class Reply(
        val text: String,
        /** Speak it? (false = show only, e.g. Agent mode after a successful action) */
        val speak: Boolean,
        /** True when its sentences were already delivered through [Sink.onSentence], so don't speak [text] again. */
        val streamed: Boolean,
        val acted: Boolean = false,
    )

    interface Sink {
        /** Text so far of the reply (for the chat bubble). */
        fun onPartial(textSoFar: String) {}
        /** A finished sentence, ready to be spoken. Only called in Assistant and Master mode. */
        fun onSentence(sentence: String) {}
    }

    private val lock = Any()

    fun respond(
        ctx: Context, input: String, files: List<Attachment> = emptyList(), viaVoice: Boolean = false, sink: Sink? = null,
    ): Reply = synchronized(lock) {
        val raw = input.trim()
        val call = Prefs.callMe(ctx)
        val mode = Prefs.mode(ctx)

        // ---- local commands (work with no AI at all) ----
        if (VoiceParser.isSelfClose(raw)) {
            return Reply("I can't close myself from here, $call. In float mode, say goodbye.", true, false)
        }
        val c = VoiceParser.cleanCommand(raw)
        VoiceParser.modeSwitch(c)?.let { m ->
            Prefs.saveMode(ctx, m)
            val t = Narrator.modeChanged(ctx, m)
            Conversation.note(raw, t)
            return Reply(t, true, false)
        }
        VoiceParser.voiceChange(c)?.let { v ->
            // Works in every mode. reload() is queued before the speech below, so the reply is in the NEW voice.
            val t = Speaker.get(ctx).changeVoice(reset = v == "reset")
            Conversation.note(raw, t)
            return Reply(t, true, false)
        }
        val act = if (files.isEmpty()) VoiceParser.actionFor(c) else null
        if (act != null) {
            if (mode == PMode.ASSISTANT) {
                val t = Narrator.assistantRefusal(ctx)
                Conversation.note(raw, t)
                return Reply(t, true, false)
            }
            val res = CommandExecutor.run(
                ctx, JSONObject().put("action", act.action).put("value", act.value), fromStandalone = true
            )
            // "show me the weather" is conversation, not an app called "weather": let the AI answer it
            val notAnApp = act.soft && act.action == "open_app" && !res.ok && res.msg.startsWith("I couldn't find an app")
            if (!notAnApp) {
                val t = Narrator.confirm(ctx, act, res)
                Conversation.note(raw, t)
                return Reply(t, speak = mode == PMode.MASTER || !res.ok, streamed = false, acted = true)
            }
        }

        // ---- the AI ----
        Conversation.checkEngine(ctx)
        val streaming = mode != PMode.AGENT          // Agent mode never narrates while it works
        var spoken = 0
        val acc = StringBuilder()
        val chatSink = object : ChatSink {
            override fun onText(delta: String) { acc.append(delta); sink?.onPartial(acc.toString()) }
            override fun onSentence(sentence: String) {
                if (streaming && sink != null) { spoken++; sink.onSentence(sentence) }
            }
        }
        val maxGemini = if (viaVoice) 1024 else 2048
        val maxLocal = if (viaVoice) 400 else 900
        val r = try {
            if (Prefs.engine(ctx) == "gemini") GeminiClient.chat(ctx, raw.ifEmpty { "Please look at the attached file." }, files, mode, maxGemini, chatSink)
            else LocalLlmClient.chat(ctx, raw.ifEmpty { "Please look at the attached file." }, files, mode, maxLocal, chatSink)
        } catch (e: Exception) {
            ChatResult("Something went wrong: ${e.message}", emptyList(), true)
        }

        val acted = r.actions.any { it.acted }
        val allOk = r.actions.all { it.ok }
        var text = r.text
        var streamed = spoken > 0 && text.isNotBlank()
        if (text.isBlank() && r.actions.isNotEmpty()) {
            // The model did the action but said nothing: report the real result ourselves.
            val last = r.actions.last()
            text = if (last.ok) "Done, $call." else last.msg
            streamed = false
        }
        if (text.isBlank()) text = "Done, $call."
        if (r.error) streamed = false
        val speak = when (mode) {
            PMode.AGENT -> r.error || !acted || !allOk
            else -> true
        }
        Reply(text, speak, streamed && speak, acted)
    }

    /** Loads the model and primes its cache in the background (Ollama / custom server). */
    fun warmup(ctx: Context) {
        val app = ctx.applicationContext
        Thread { try { LocalLlmClient.warmup(app) } catch (e: Exception) { } }.start()
    }
}
