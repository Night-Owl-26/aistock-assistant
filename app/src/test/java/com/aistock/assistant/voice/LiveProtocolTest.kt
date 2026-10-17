package com.aistock.assistant.voice

import com.aistock.assistant.ai.llm.ToolParam
import com.aistock.assistant.ai.llm.ToolSpec
import com.aistock.assistant.voice.live.LiveEvent
import com.aistock.assistant.voice.live.LiveProtocol
import com.aistock.assistant.voice.live.LiveTurns
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LiveProtocolTest {
    @Test
    fun setup_has_model_audio_transcripts_barge_in_and_tools() {
        val s = JSONObject(LiveProtocol.setup("gemini-3.8-live", "You are Stocky", listOf(ToolSpec("get_sales", "Sales", listOf(ToolParam("period", "string", "p", options = listOf("TODAY"))))), "h1")).getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", s.getString("model"))
        assertEquals("AUDIO", s.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertTrue(s.has("inputAudioTranscription") && s.has("outputAudioTranscription"))
        assertEquals("START_OF_ACTIVITY_INTERRUPTS", s.getJSONObject("realtimeInputConfig").getString("activityHandling"))
        assertEquals("h1", s.getJSONObject("sessionResumption").getString("handle"))
        assertEquals("get_sales", s.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).getString("name"))
        assertEquals("You are Stocky", s.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val a = JSONObject(LiveProtocol.audio("AAAA")).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", a.getString("mimeType"))
    }

    @Test
    fun server_messages_become_events() {
        val e1 = LiveProtocol.parse("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQID"}}]},"outputTranscription":{"text":"Innikku "}}}""")
        assertTrue(e1[0] is LiveEvent.Audio); assertEquals("Innikku ", (e1[1] as LiveEvent.StockyText).text)
        val e2 = LiveProtocol.parse("""{"toolCall":{"functionCalls":[{"id":"f1","name":"propose_stock_in","args":{"product":"pink clip","quantity":10.0}}]}}""")
        val call = (e2.single() as LiveEvent.Tools).calls.single()
        assertEquals("10", call.args["quantity"]); assertEquals("f1", call.id)
        assertTrue(LiveProtocol.parse("""{"serverContent":{"interrupted":true}}""").single() is LiveEvent.Interrupted)
        assertEquals(12, (LiveProtocol.parse("""{"goAway":{"timeLeft":"12.5s"}}""").single() as LiveEvent.GoAway).secondsLeft)
        assertEquals("abc", (LiveProtocol.parse("""{"sessionResumptionUpdate":{"newHandle":"abc","resumable":true}}""").single() as LiveEvent.Resumable).handle)
        assertTrue(LiveProtocol.parse("""{"sessionResumptionUpdate":{"newHandle":"","resumable":false}}""").isEmpty())
        assertTrue(LiveProtocol.parse("not json").isEmpty())
        val tr = JSONObject(LiveProtocol.toolResponse(listOf(Triple("f1", "get_sales", "₹1,200")))).getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("₹1,200", tr.getJSONObject("response").getString("result"))
    }

    @Test
    fun the_same_tool_call_never_runs_twice_and_cancelled_ones_not_at_all() {
        val t = LiveTurns()
        assertTrue(t.onUser("Stocky, "))
        assertFalse(t.onUser("innikku sales evlo?"))
        assertTrue(t.shouldRun("a")); assertFalse(t.shouldRun("a"))
        t.cancel(listOf("b")); assertFalse(t.shouldRun("b"))
        t.onStocky("Innikku 12 sales.")
        assertEquals("Stocky, innikku sales evlo?" to "Innikku 12 sales.", t.onTurnComplete())
        assertTrue(t.onUser("thanks"))
    }
}
