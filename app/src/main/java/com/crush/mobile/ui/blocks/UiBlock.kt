package com.crush.mobile.ui.blocks

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * V2-7.3: the closed, host-owned block vocabulary.
 *
 * This is deliberately not a general UI language. It is a small set the app
 * knows how to draw deterministically, with no HTML, JavaScript, URLs, images or
 * actions. Model-authored output is a V2-8 experiment; this type exists so both
 * the validator and the renderer can be tested without a producer.
 */
sealed interface UiBlock {
    data class Text(val text: String) : UiBlock
    data class Code(val language: String, val text: String) : UiBlock
    data class Table(val columns: List<String>, val rows: List<List<String>>) : UiBlock
    data class KeyValue(val items: List<Item>) : UiBlock {
        data class Item(val key: String, val value: String)
    }
    data class Callout(val tone: Tone, val title: String, val text: String) : UiBlock {
        enum class Tone { INFO, WARNING, ERROR }
    }
    data object Divider : UiBlock
}

data class UiBlockDocument(val blocks: List<UiBlock>)

sealed interface UiBlockParse {
    data class Valid(val document: UiBlockDocument) : UiBlockParse
    data class Invalid(val reason: String) : UiBlockParse
}

/**
 * Strict `ui_block/v1` validator.
 *
 * Fail closed: any unknown version, type or field rejects the WHOLE document.
 * The caller then renders the original text; there is no partial tree and no
 * "best effort" block that could hide a malformed payload.
 *
 * Limits are deliberately small. A block tree is a compact receipt, not a
 * document format; the caps keep one model reply from turning into thousands
 * of layout nodes or a multi-megabyte state object.
 */
object UiBlockParser {

    const val VERSION = "ui_block/v1"

    private const val MAX_SOURCE_CHARS = 16_000
    private const val MAX_BLOCKS = 32
    private const val MAX_TEXT = 4_000
    private const val MAX_CODE = 8_000
    private const val MAX_LANGUAGE = 32
    private const val MAX_TABLE_COLUMNS = 8
    private const val MAX_TABLE_ROWS = 200
    private const val MAX_CELL = 500
    private const val MAX_KV_ITEMS = 32
    private const val MAX_KV_KEY = 80
    private const val MAX_KV_VALUE = 1_000
    private const val MAX_CALLOUT_TITLE = 120
    private const val MAX_CALLOUT_TEXT = 2_000

    fun parse(source: String): UiBlockParse {
        if (source.length > MAX_SOURCE_CHARS) {
            return invalid("source exceeds $MAX_SOURCE_CHARS chars")
        }
        val root = runCatching { JsonParser.parseString(source) }.getOrNull() as? JsonObject
            ?: return invalid("not a JSON object")
        unexpectedField(root, setOf("v", "blocks"))?.let { return invalid(it) }
        if (root.string("v") != VERSION) return invalid("version must be $VERSION")

        val blocks = root.get("blocks") as? JsonArray
            ?: return invalid("blocks must be an array")
        if (blocks.size() !in 1..MAX_BLOCKS) {
            return invalid("blocks must contain 1..$MAX_BLOCKS entries")
        }

        val parsed = ArrayList<UiBlock>(blocks.size())
        blocks.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return invalid("block $index is not an object")
            when (val result = parseBlock(obj, index)) {
                is BlockResult.Ok -> parsed += result.block
                is BlockResult.Bad -> return invalid(result.reason)
            }
        }
        return UiBlockParse.Valid(UiBlockDocument(parsed))
    }

    private sealed interface BlockResult {
        data class Ok(val block: UiBlock) : BlockResult
        data class Bad(val reason: String) : BlockResult
    }

    private fun parseBlock(obj: JsonObject, index: Int): BlockResult {
        val type = obj.string("type") ?: return bad(index, "type is required")
        return when (type) {
            "text" -> parseText(obj, index)
            "code" -> parseCode(obj, index)
            "table" -> parseTable(obj, index)
            "kv" -> parseKeyValue(obj, index)
            "callout" -> parseCallout(obj, index)
            "divider" -> {
                unexpectedField(obj, setOf("type"))?.let { return bad(index, it) }
                BlockResult.Ok(UiBlock.Divider)
            }
            else -> bad(index, "unknown type '$type'")
        }
    }

    private fun parseText(obj: JsonObject, index: Int): BlockResult {
        unexpectedField(obj, setOf("type", "text"))?.let { return bad(index, it) }
        val text = obj.string("text")?.takeIf { it.isNotBlank() }
            ?: return bad(index, "text is required")
        if (text.length > MAX_TEXT) return bad(index, "text exceeds $MAX_TEXT chars")
        return BlockResult.Ok(UiBlock.Text(text))
    }

    private fun parseCode(obj: JsonObject, index: Int): BlockResult {
        unexpectedField(obj, setOf("type", "lang", "text"))?.let { return bad(index, it) }
        val text = obj.string("text")?.takeIf { it.isNotBlank() }
            ?: return bad(index, "text is required")
        if (text.length > MAX_CODE) return bad(index, "code exceeds $MAX_CODE chars")
        val language = obj.string("lang").orEmpty()
        if (language.length > MAX_LANGUAGE) {
            return bad(index, "lang exceeds $MAX_LANGUAGE chars")
        }
        return BlockResult.Ok(UiBlock.Code(language, text))
    }

    private fun parseTable(obj: JsonObject, index: Int): BlockResult {
        unexpectedField(obj, setOf("type", "columns", "rows"))?.let { return bad(index, it) }
        val columns = obj.stringArray("columns")
            ?: return bad(index, "columns must be an array of strings")
        if (columns.isEmpty() || columns.size > MAX_TABLE_COLUMNS) {
            return bad(index, "columns must contain 1..$MAX_TABLE_COLUMNS entries")
        }
        if (columns.any { it.isBlank() || it.length > MAX_CELL }) {
            return bad(index, "a column is blank or longer than $MAX_CELL chars")
        }

        val rowsArray = obj.get("rows") as? JsonArray
            ?: return bad(index, "rows must be an array")
        if (rowsArray.size() > MAX_TABLE_ROWS) {
            return bad(index, "rows exceed $MAX_TABLE_ROWS")
        }
        val rows = ArrayList<List<String>>(rowsArray.size())
        rowsArray.forEachIndexed { rowIndex, rowElement ->
            val rowArray = rowElement as? JsonArray
                ?: return bad(index, "row $rowIndex is not an array")
            if (rowArray.size() != columns.size) {
                return bad(index, "row $rowIndex has ${rowArray.size()} cells, expected ${columns.size}")
            }
            val cells = rowArray.map { cell ->
                val value = cell.asStringOrNull() ?: return bad(index, "row $rowIndex has a non-string cell")
                if (value.length > MAX_CELL) {
                    return bad(index, "row $rowIndex cell exceeds $MAX_CELL chars")
                }
                value
            }
            rows += cells
        }
        return BlockResult.Ok(UiBlock.Table(columns, rows))
    }

    private fun parseKeyValue(obj: JsonObject, index: Int): BlockResult {
        unexpectedField(obj, setOf("type", "items"))?.let { return bad(index, it) }
        val itemsArray = obj.get("items") as? JsonArray
            ?: return bad(index, "items must be an array")
        if (itemsArray.size() == 0 || itemsArray.size() > MAX_KV_ITEMS) {
            return bad(index, "items must contain 1..$MAX_KV_ITEMS entries")
        }
        val items = ArrayList<UiBlock.KeyValue.Item>(itemsArray.size())
        itemsArray.forEachIndexed { itemIndex, element ->
            val item = element as? JsonObject
                ?: return bad(index, "item $itemIndex is not an object")
            unexpectedField(item, setOf("k", "v"))?.let {
                return bad(index, "item $itemIndex: $it")
            }
            val key = item.string("k")?.takeIf { it.isNotBlank() }
                ?: return bad(index, "item $itemIndex needs a key")
            val value = item.string("v") ?: return bad(index, "item $itemIndex needs a value")
            if (key.length > MAX_KV_KEY) return bad(index, "item $itemIndex key too long")
            if (value.length > MAX_KV_VALUE) return bad(index, "item $itemIndex value too long")
            items += UiBlock.KeyValue.Item(key, value)
        }
        return BlockResult.Ok(UiBlock.KeyValue(items))
    }

    private fun parseCallout(obj: JsonObject, index: Int): BlockResult {
        unexpectedField(obj, setOf("type", "tone", "title", "text"))?.let { return bad(index, it) }
        val tone = when (obj.string("tone")) {
            "info" -> UiBlock.Callout.Tone.INFO
            "warning" -> UiBlock.Callout.Tone.WARNING
            "error" -> UiBlock.Callout.Tone.ERROR
            else -> return bad(index, "tone must be info, warning or error")
        }
        val title = obj.string("title").orEmpty()
        if (title.length > MAX_CALLOUT_TITLE) {
            return bad(index, "title exceeds $MAX_CALLOUT_TITLE chars")
        }
        val text = obj.string("text")?.takeIf { it.isNotBlank() }
            ?: return bad(index, "text is required")
        if (text.length > MAX_CALLOUT_TEXT) {
            return bad(index, "callout text exceeds $MAX_CALLOUT_TEXT chars")
        }
        return BlockResult.Ok(UiBlock.Callout(tone, title, text))
    }

    private fun unexpectedField(obj: JsonObject, allowed: Set<String>): String? =
        obj.keySet().firstOrNull { it !in allowed }?.let { "unexpected field '$it'" }

    private fun JsonObject.string(key: String): String? =
        get(key)?.asStringOrNull()

    private fun JsonObject.stringArray(key: String): List<String>? {
        val array = get(key) as? JsonArray ?: return null
        return array.map { it.asStringOrNull() ?: return null }
    }

    private fun com.google.gson.JsonElement.asStringOrNull(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun invalid(reason: String): UiBlockParse = UiBlockParse.Invalid(reason)
    private fun bad(index: Int, reason: String): BlockResult =
        BlockResult.Bad("block $index: $reason")
}
