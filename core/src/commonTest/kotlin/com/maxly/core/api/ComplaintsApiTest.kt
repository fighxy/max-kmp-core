package com.maxly.core.api

import com.maxly.core.protocol.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ComplaintsApiTest {
    @Test
    fun reasonsSendComplainSyncAndSkipEmptyIds() = runTest {
        val sink = ScriptSink(
            mapOf(
                "complains" to listOf(
                    mapOf(
                        "typeId" to 6,
                        "reasons" to listOf(
                            mapOf("reasonId" to 3, "reasonTitle" to "Спам"),
                            mapOf("reasonId" to 0, "reasonTitle" to "пусто"),
                            "junk",
                        ),
                    ),
                    mapOf("typeId" to 2, "reasons" to listOf(mapOf("reasonId" to 1, "reasonTitle" to "Канал"))),
                    mapOf("typeId" to "нет"),
                ),
            ),
        )
        val reasons = ComplaintsApi(sink).reasons()
        assertEquals(Opcode.COMPLAIN_REASONS_GET, sink.sent.single().first)
        assertEquals(mapOf<String, Any>("complainSync" to 0), sink.sent.single().second)
        assertEquals(listOf(ComplaintReason(3, "Спам")), reasons[ComplaintsApi.USER])
        assertEquals(listOf(ComplaintReason(1, "Канал")), reasons[ComplaintsApi.CHANNEL])
        assertEquals(2, ComplaintsApi.CHANNEL)
        assertEquals(6, ComplaintsApi.USER)
    }

    @Test
    fun sendOmitsParentUntilAMessageComplaintHasOne() = runTest {
        val sink = ScriptSink(mapOf("success" to true), mapOf("success" to false), emptyMap<String, Any?>())
        val api = ComplaintsApi(sink)
        assertTrue(api.send(3, ComplaintsApi.CHANNEL, listOf(42L)))
        assertFalse(api.send(3, ComplaintsApi.USER, listOf(9L), parentId = 42L))
        assertFalse(api.send(1, 6, listOf(9L)))
        assertEquals(
            mapOf<String, Any>("reasonId" to 3, "typeId" to 2, "ids" to listOf(42L)),
            sink.sent[0].second,
        )
        assertEquals(
            mapOf<String, Any>("reasonId" to 3, "typeId" to 6, "ids" to listOf(9L), "parentId" to 42L),
            sink.sent[1].second,
        )
        assertEquals(listOf(Opcode.COMPLAIN, Opcode.COMPLAIN, Opcode.COMPLAIN), sink.opcodes)
    }

    @Test
    fun missingReasonsAreEmptyAndEmptyIdsFail() = runTest {
        assertTrue(ComplaintsApi(ScriptSink(emptyMap<String, Any?>())).reasons().isEmpty())
        assertFailsWith<IllegalArgumentException> { ComplaintsApi(ScriptSink()).send(1, 6, emptyList()) }
    }

    @Test
    fun maxApiExposesComplaints() = runTest {
        val sink = ScriptSink(emptyMap<String, Any?>())
        MaxApi(sink).complaints.reasons()
        assertEquals(listOf(Opcode.COMPLAIN_REASONS_GET), sink.opcodes)
    }
}
