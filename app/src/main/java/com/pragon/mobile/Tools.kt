package com.pragon.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What running one tool call did. */
class ToolOut(val ok: Boolean, val msg: String, val acted: Boolean, val label: String = "")

/** Tool schemas (Gemini and OpenAI/Ollama flavours) and the code that runs them. */
object Tools {

    private val ACTIONS = listOf(
        "open_app", "close_app", "open_url", "youtube_search", "web_search", "call", "key", "swipe",
        "type_text", "status", "flashlight", "timer", "alarm",
    )

    private const val ACTION_DOC =
        "open_app: value=app name. close_app: value=app name (force-stops it). open_url: value=link. " +
        "youtube_search / web_search: value=query. call: value=phone number (opens the dialer). " +
        "key: value one of home, back, recents, notifications, quick_settings, lock, wake, screenshot, volume_up, " +
        "volume_down, mute, play_pause, next, previous. swipe: value=up, down, left or right. type_text: value=text to type. " +
        "flashlight: value=on, off or toggle. timer: value=seconds. alarm: value=24-hour time like 07:30. status: phone info."

    private fun str(upper: Boolean) = if (upper) "STRING" else "string"
    private fun obj(upper: Boolean) = if (upper) "OBJECT" else "object"

    private fun params(upper: Boolean, props: JSONObject, required: List<String>) =
        JSONObject().put("type", obj(upper)).put("properties", props).put("required", JSONArray(required))

    private fun decls(mode: PMode, upper: Boolean): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        if (mode != PMode.ASSISTANT) {
            val props = JSONObject()
                .put("action", JSONObject().put("type", str(upper)).put("enum", JSONArray(ACTIONS)).put("description", ACTION_DOC))
                .put("value", JSONObject().put("type", str(upper)).put("description", "Argument for the action."))
            out.add(JSONObject().put("name", "phone_control").put("description", "Control the user's Android phone.")
                .put("parameters", params(upper, props, listOf("action"))))
        }
        val rp = JSONObject()
            .put("category", JSONObject().put("type", str(upper)).put("description", "identity, preference, project, relationship or note"))
            .put("key", JSONObject().put("type", str(upper)).put("description", "Short label, e.g. name, city, favourite_music"))
            .put("value", JSONObject().put("type", str(upper)).put("description", "The fact itself."))
        out.add(JSONObject().put("name", "remember")
            .put("description", "Silently save a lasting fact or preference about the user to long-term memory.")
            .put("parameters", params(upper, rp, listOf("key", "value"))))
        val fp = JSONObject().put("key", JSONObject().put("type", str(upper)).put("description", "Label of the fact to forget."))
        out.add(JSONObject().put("name", "forget").put("description", "Delete a remembered fact, only when the user asks.")
            .put("parameters", params(upper, fp, listOf("key"))))
        return out
    }

    /** [{functionDeclarations:[...]}] */
    fun gemini(mode: PMode): JSONArray =
        JSONArray().put(JSONObject().put("functionDeclarations", JSONArray(decls(mode, true))))

    /** [{type:"function", function:{...}}] for Ollama and OpenAI-compatible servers. */
    fun openai(mode: PMode): JSONArray {
        val a = JSONArray()
        for (d in decls(mode, false)) a.put(JSONObject().put("type", "function").put("function", d))
        return a
    }

    /**
     * Runs a tool call. [seen] holds the calls already made for this request: an identical repeat is skipped
     * (the PC protocol's "one request, one tool execution; no duplicate calls").
     */
    fun run(ctx: Context, call: ToolCall, mode: PMode, seen: MutableSet<String>): ToolOut {
        val sig = call.name + call.args.toString()
        if (!seen.add(sig)) return ToolOut(true, "Already done for this request.", false)
        return when (call.name) {
            "phone_control" -> {
                if (mode == PMode.ASSISTANT) return ToolOut(false, "Assistant mode cannot operate the phone.", false)
                val r = CommandExecutor.run(
                    ctx,
                    JSONObject().put("action", call.args.optString("action")).put("value", call.args.optString("value")),
                    fromStandalone = true
                )
                ToolOut(r.ok, r.msg, true, r.label)
            }
            "remember" -> {
                val ok = PragonMemory.remember(ctx, call.args.optString("category", "note"),
                    call.args.optString("key"), call.args.optString("value"))
                ToolOut(ok, if (ok) "Saved." else "Could not save that.", false)
            }
            "forget" -> {
                val ok = PragonMemory.forget(ctx, call.args.optString("key"))
                ToolOut(ok, if (ok) "Forgotten." else "I had nothing saved under that name.", false)
            }
            else -> ToolOut(false, "Unknown tool '${call.name}'.", false)
        }
    }
}

/** Short spoken confirmations for actions Pragon did itself (no AI round trip), in the PC protocol's voice. */
object Narrator {
    private var n = 0
    private fun pick(vararg v: String): String { n++; return v[n % v.size] }

    fun confirm(ctx: Context, a: VoiceParser.Action, r: CommandExecutor.Res): String {
        val call = Prefs.callMe(ctx)
        if (!r.ok) return r.msg
        // CommandExecutor already words soft results ("couldn't confirm it appeared"); keep those as they are.
        if (r.msg.contains("couldn't confirm", true) || r.msg.contains("locked", true)) return r.msg
        val label = r.label.ifBlank { a.value }.replaceFirstChar { it.uppercase() }
        return when (a.action) {
            "open_app" -> pick("$label opened, $call.", "$label is open, $call.", "Opened $label, $call.")
            "open_url" -> "Opened that link, $call."
            "close_app" -> "$label closed, $call."
            "youtube_search" -> "Here are the YouTube results for ${a.value}, $call."
            "web_search" -> "Here is what Google found for ${a.value}, $call."
            "call" -> "The dialer is ready, $call. Tap call to place it."
            "type_text" -> "Typed it, $call."
            "status" -> r.msg
            "flashlight" -> r.msg
            "timer" -> r.msg
            "alarm" -> r.msg
            "swipe" -> pick("Done, $call.", "Swiped ${a.value}, $call.")
            "key" -> when (a.value) {
                "home" -> "Home, $call."
                "back" -> "Back, $call."
                "recents" -> "Here are your recent apps, $call."
                "notifications" -> "Notifications, $call."
                "quick_settings" -> "Quick settings, $call."
                "lock" -> "Locking the phone, $call."
                "screenshot" -> "Screenshot taken, $call."
                "volume_up" -> "Volume up, $call."
                "volume_down" -> "Volume down, $call."
                "mute" -> "Mute toggled, $call."
                "play_pause" -> pick("Done, $call.", "Play or pause sent, $call.")
                "next" -> "Next, $call."
                "previous" -> "Previous, $call."
                else -> "Done, $call."
            }
            else -> pick("Done, $call.", "Certainly, $call.")
        }
    }

    fun modeChanged(ctx: Context, m: PMode): String {
        val call = Prefs.callMe(ctx)
        return when (m) {
            PMode.ASSISTANT -> "Assistant mode, $call. I will only talk with you."
            PMode.MASTER -> "Master mode, $call. I will talk and act, and tell you what I did."
            PMode.AGENT -> "Agent mode, $call. I will act and keep the talk to a minimum."
        }
    }

    fun assistantRefusal(ctx: Context): String =
        "I'm in Assistant mode, ${Prefs.callMe(ctx)}, so I can only talk. Say master mode or agent mode and I'll do it."
}
