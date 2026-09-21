package com.tinyggrok.app.data.model

import com.google.gson.Gson
import com.tinyggrok.app.AppDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tuning fields must serialize exactly as POST /v1/responses documents them:
 * `reasoning: {"effort": ...}` and `max_turns`. A silent rename here would be sent
 * to the API on every message, so it is worth pinning.
 */
class ResponsesRequestJsonTest {

    private val gson = Gson()

    private fun json(request: ResponsesRequest) = gson.toJson(request)

    @Test
    fun reasoningAndMaxTurnsUseTheDocumentedNames() {
        val body = json(
            ResponsesRequest(
                input = listOf(InputMessage(role = "user", content = "hi")),
                reasoning = ReasoningConfig(effort = "low"),
                maxTurns = 6
            )
        )
        assertTrue(body, body.contains("\"reasoning\":{\"effort\":\"low\"}"))
        assertTrue(body, body.contains("\"max_turns\":6"))
        assertFalse(body, body.contains("maxTurns"))
    }

    @Test
    fun tuningFieldsAreOmittedWhenUnset() {
        val body = json(ResponsesRequest(input = listOf(InputMessage(role = "user", content = "hi"))))
        assertFalse(body, body.contains("reasoning"))
        assertFalse(body, body.contains("max_turns"))
    }

    @Test
    fun theChosenModelIdIsWhatGoesOnTheWire() {
        // A request left alone carries the app's default model.
        val standard = json(ResponsesRequest(input = listOf(InputMessage(role = "user", content = "hi"))))
        assertTrue(standard, standard.contains("\"model\":\"grok-4.7\""))

        // And every model the picker offers is sent exactly as xAI spells it.
        for ((_, id) in AppDefaults.CHAT_MODELS) {
            val body = json(ResponsesRequest(model = id, input = listOf(InputMessage(role = "user", content = "hi"))))
            assertTrue(body, body.contains("\"model\":\"$id\""))
        }
    }

    @Test
    fun effortValuesMatchTheApi() {
        // Documented set for POST /v1/responses.
        val allowed = setOf("none", "low", "medium", "high", "xhigh")
        assertTrue(AppDefaults.EFFORT_LOW in allowed)
        assertTrue(AppDefaults.EFFORT_HIGH in allowed)
        assertEquals("low", AppDefaults.EFFORT_LOW)
    }
}
