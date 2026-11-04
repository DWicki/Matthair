package io.github.dwicki.matthair.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

// Small, null-tolerant accessors for the loosely typed JSON that HA and the Matter Server send.

internal val JsonElement.obj: JsonObject get() = jsonObject

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
internal fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
internal fun JsonObject.strings(key: String): List<String> = array(key).mapNotNull { (it as? JsonPrimitive)?.content }

internal val String?.json: JsonElement get() = this?.let(::JsonPrimitive) ?: JsonNull
internal val Number.json: JsonElement get() = JsonPrimitive(this)
internal val Boolean.json: JsonElement get() = JsonPrimitive(this)
