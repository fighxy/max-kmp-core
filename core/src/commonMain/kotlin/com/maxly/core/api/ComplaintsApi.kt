package com.maxly.core.api

import com.maxly.core.auth.RequestSink
import com.maxly.core.protocol.Opcode

/**
 * Complaints over a [RequestSink], as KometTeam/Komet `ComplaintsModule` sends them.
 *
 * Reasons: `COMPLAIN_REASONS_GET` 162, `{complainSync: 0}`. Reply `complains`:
 * `[{typeId, reasons: [{reasonId, reasonTitle}]}]`. A reason with id `0` is dropped.
 * A missing list is empty.
 *
 * Send: `COMPLAIN` 161, `{reasonId, typeId, ids}` and `parentId` only when it is given
 * (the chat of a reported message). Success is `payload.success == true`.
 *
 * Confirmed type ids: [CHANNEL] `2` (ids are the chat id, no parent), [USER] `6`.
 * A message type id is not confirmed and is not named here.
 */
class ComplaintsApi(private val sink: RequestSink) {
    suspend fun reasons(): Map<Int, List<ComplaintReason>> {
        val map = rawMap(sink.request(Opcode.COMPLAIN_REASONS_GET, linkedMapOf("complainSync" to 0)))
        val complains = map["complains"] as? List<*> ?: return emptyMap()
        val out = LinkedHashMap<Int, List<ComplaintReason>>()
        for (entry in complains) {
            val row = entry as? Map<*, *> ?: continue
            val typeId = row["typeId"].asLong()?.toInt() ?: continue
            val reasons = (row["reasons"] as? List<*>).orEmpty().mapNotNull { item ->
                val reason = item as? Map<*, *> ?: return@mapNotNull null
                val id = reason["reasonId"].asLong()?.toInt() ?: 0
                if (id == 0) null else ComplaintReason(id, reason["reasonTitle"]?.toString().orEmpty())
            }
            out[typeId] = reasons
        }
        return out
    }

    /** `true` only when the reply map says `success == true`. */
    suspend fun send(reasonId: Int, typeId: Int, ids: List<Long>, parentId: Long? = null): Boolean {
        require(ids.isNotEmpty()) { "ids must not be empty" }
        val payload = linkedMapOf<String, Any?>("reasonId" to reasonId, "typeId" to typeId, "ids" to ids)
        if (parentId != null) payload["parentId"] = parentId
        return rawMap(sink.request(Opcode.COMPLAIN, payload))["success"] == true
    }

    companion object {
        /** A whole channel. Komet `ComplaintsModule.channelTypeId`. */
        const val CHANNEL: Int = 2

        /** A user. Komet `ComplaintsModule.userTypeId`. */
        const val USER: Int = 6
    }
}

/** One reason from [ComplaintsApi.reasons]. */
data class ComplaintReason(val reasonId: Int, val reasonTitle: String)
