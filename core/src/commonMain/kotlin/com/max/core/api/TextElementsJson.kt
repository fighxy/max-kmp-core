package com.max.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Formatting elements as JSON for apps that pass them as one string (the iOS bridge): an array
 * of the wire objects `{type, from, length, entityId?, entityName?, attributes?}`, offsets in
 * UTF-16 units.
 */
object TextElementsJson {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Elements of [value] read like received ones ([TextElement.parse] with [textLength]). A blank
     * [value] is an empty list; anything that is not a JSON array fails with
     * [IllegalArgumentException]. `entityId` may be a number or a decimal string.
     */
    fun parse(value: String, textLength: Int? = null): List<TextElement> {
        if (value.isBlank()) return emptyList()
        val root = try {
            json.parseToJsonElement(value)
        } catch (e: Exception) {
            throw IllegalArgumentException("elements are not JSON: ${e.message}")
        }
        val array = root as? JsonArray ?: throw IllegalArgumentException("elements must be a JSON array")
        return TextElement.parseAll(array.map(::plain), textLength)
    }

    /** [elements] as a JSON array of their wire form ([TextElement.toPayload]). */
    fun write(elements: List<TextElement>): String = JsonArray(elements.map { element(it.toPayload()) }).toString()

    private fun plain(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull
        is JsonArray -> e.map(::plain)
        is JsonObject -> e.mapValues { (_, v) -> plain(v) }
    }

    private fun element(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to element(x) })
        is List<*> -> JsonArray(v.map(::element))
        else -> JsonPrimitive(v.toString())
    }
}
