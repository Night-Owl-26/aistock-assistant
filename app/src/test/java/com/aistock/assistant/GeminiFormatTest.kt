package com.aistock.assistant

import com.aistock.assistant.ai.llm.Gemini
import com.aistock.assistant.ai.llm.LlmException
import com.aistock.assistant.ai.llm.LlmMessage
import com.aistock.assistant.ai.llm.ToolCall
import com.aistock.assistant.ai.llm.ToolParam
import com.aistock.assistant.ai.llm.ToolSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GeminiFormatTest {
    @Test
    fun request_uses_gemini_shapes_and_alternating_turns() {
        val body = Gemini.request(
            listOf(
                LlmMessage.System("You are Stocky"),
                LlmMessage.Assistant("Hello"), // dropped: must open with the user
                LlmMessage.User("hi"), LlmMessage.User("how much blue cotton"),
                LlmMessage.Assistant("", listOf(ToolCall("c1", "find_products", mapOf("query" to "blue cotton"), "sig"))),
                LlmMessage.ToolResult("c1", "find_products", "Blue Cotton: 12 m"),
            ),
            listOf(ToolSpec("find_products", "Look up products", listOf(ToolParam("query", "string", "name", true))), ToolSpec("low_stock", "Low stock")),
        )
        val o = JSONObject(body)
        assertEquals("You are Stocky", o.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val c = o.getJSONArray("contents")
        assertEquals(3, c.length())
        assertEquals("user", c.getJSONObject(0).getString("role"))
        assertEquals(2, c.getJSONObject(0).getJSONArray("parts").length())
        val call = c.getJSONObject(1).getJSONArray("parts").getJSONObject(0)
        assertEquals("model", c.getJSONObject(1).getString("role"))
        assertEquals("find_products", call.getJSONObject("functionCall").getString("name"))
        assertEquals("sig", call.getString("thoughtSignature"))
        assertEquals("Blue Cotton: 12 m", c.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse").getJSONObject("response").getString("result"))
        val d = o.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals("STRING", d.getJSONObject(0).getJSONObject("parameters").getJSONObject("properties").getJSONObject("query").getString("type"))
        assertTrue(!d.getJSONObject(1).has("parameters"))
    }

    @Test
    fun reply_text_and_function_calls_are_read() {
        val r = Gemini.parse("""{"candidates":[{"content":{"role":"model","parts":[{"text":"hidden","thought":true},{"text":"Sure. "},{"functionCall":{"name":"propose_stock_in","args":{"product":"Blue Cotton","quantity":10.0}},"thoughtSignature":"abc"}]},"finishReason":"STOP"}]}""")
        assertEquals("Sure.", r.text)
        assertEquals("10", r.toolCalls[0].args["quantity"])
        assertEquals("abc", r.toolCalls[0].signature)
    }

    @Test(expected = LlmException::class)
    fun a_blocked_reply_is_an_error() { Gemini.parse("""{"promptFeedback":{"blockReason":"SAFETY"}}""") }
}
