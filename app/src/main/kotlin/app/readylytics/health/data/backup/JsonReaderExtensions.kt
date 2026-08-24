package app.readylytics.health.data.backup

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun readNextObjectAsString(
    json: Json,
    reader: JsonReader,
): String {
    val sb = StringBuilder()
    parseValue(json, reader, sb)
    return sb.toString()
}

private fun parseValue(
    json: Json,
    reader: JsonReader,
    sb: StringBuilder,
) {
    when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            reader.beginObject()
            sb.append("{")
            var first = true
            while (reader.hasNext()) {
                if (!first) sb.append(",")
                sb.append(json.encodeToString(reader.nextName())).append(":")
                parseValue(json, reader, sb)
                first = false
            }
            reader.endObject()
            sb.append("}")
        }
        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            sb.append("[")
            var first = true
            while (reader.hasNext()) {
                if (!first) sb.append(",")
                parseValue(json, reader, sb)
                first = false
            }
            reader.endArray()
            sb.append("]")
        }
        JsonToken.STRING -> {
            sb.append(json.encodeToString(reader.nextString()))
        }
        JsonToken.NUMBER -> {
            sb.append(reader.nextString())
        }
        JsonToken.BOOLEAN -> {
            sb.append(reader.nextBoolean())
        }
        JsonToken.NULL -> {
            reader.nextNull()
            sb.append("null")
        }
        else -> reader.skipValue()
    }
}
