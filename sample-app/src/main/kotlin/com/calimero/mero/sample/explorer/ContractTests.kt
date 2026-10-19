@file:Suppress("TooManyFunctions")

package com.calimero.mero.sample.explorer

import android.content.Context
import com.calimero.mero.Mero
import com.calimero.mero.rpc.RpcException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The scaffolding-e2e contract tests, run from a JSON spec shared with the Swift sample
 * (`assets/contract-tests.json`, ported from `apps/scaffolding-e2e`'s TestRunner). Each case is a
 * list of contract calls, each with one expectation; `${run}` is a per-run prefix and `${name}` a
 * value an earlier step saved. See the spec's `ops` and `conventions`. (== Swift `ContractSpec`.)
 */
class ContractSpec(
    val packageName: String,
    val cases: List<Case>,
) {
    class Case(
        val id: String,
        val name: String,
        val group: String,
        val steps: List<JsonElement>,
    )

    companion object {
        fun parse(json: String): ContractSpec {
            val root = Json.parseToJsonElement(json).jsonObject
            val cases =
                root["cases"]!!.jsonArray.map { c ->
                    val o = c.jsonObject
                    Case(
                        id = o["id"]!!.jsonPrimitive.content,
                        name = o["name"]!!.jsonPrimitive.content,
                        group = o["group"]!!.jsonPrimitive.content,
                        steps = o["steps"]!!.jsonArray,
                    )
                }
            return ContractSpec(root["package"]!!.jsonPrimitive.content, cases)
        }

        /** The spec bundled in the app's assets, or null when it is missing or unreadable. */
        fun load(context: Context): ContractSpec? =
            runCatching {
                context.assets
                    .open("contract-tests.json")
                    .bufferedReader()
                    .use { parse(it.readText()) }
            }.getOrNull()
    }
}

/** Runs one spec case against a context, with the run's shared prefix. (== Swift `ContractCaseRunner`.) */
class ContractCaseRunner(
    private val mero: Mero,
    private val contextId: String,
    private val run: String,
) {
    /** Null when the case passed, else why it failed. */
    suspend fun run(testCase: ContractSpec.Case): String? {
        val vars = mutableMapOf<String, JsonElement>()
        testCase.steps.forEachIndexed { index, stepValue ->
            val step = stepValue as? JsonObject
            val method = (step?.get("call") as? JsonPrimitive)?.content
            val expect = step?.get("expect") as? JsonObject
            val op = (expect?.get("op") as? JsonPrimitive)?.content
            if (step == null || method == null || expect == null || op == null) return "step ${index + 1}: malformed"

            val args = substitute(step["args"] ?: JsonObject(emptyMap()), vars) as? JsonObject ?: JsonObject(emptyMap())
            var output: JsonElement = JsonNull
            var failure: String? = null
            try {
                output = mero.rpc.executeRaw(contextId, method, args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = describe(e)
            }
            val path = (step["path"] as? JsonPrimitive)?.content
            if (path != null && failure == null) {
                output = select(output, (substitute(JsonPrimitive(path), vars) as? JsonPrimitive)?.content ?: path)
            }
            val expectation = substitute(expect, vars) as? JsonObject ?: expect
            check(op, expectation, output, failure)?.let { return "step ${index + 1} $method: $it" }
            (step["save"] as? JsonPrimitive)?.content?.let { vars[it] = output }
        }
        return null
    }

    /** An error's whole text: a contract error's reason lives in the RPC error's `data`, not its `type`. */
    private fun describe(e: Exception): String {
        val base = e.message ?: e.toString()
        val data = (e as? RpcException)?.data?.takeIf { it !is JsonNull }?.toString()
        return if (data == null || base.contains(data)) base else "$base: $data"
    }

    // ---- expectations ------------------------------------------------------

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun check(
        op: String,
        e: JsonObject,
        output: JsonElement,
        failure: String?,
    ): String? {
        when (op) {
            "any" -> return null
            "error" -> return if (failure == null) "expected an error, got ${show(output)}" else null
            "okOrErrorMatches" -> {
                val f = failure ?: return null
                val pattern = e.str("pattern")
                return if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(f)) null else "error \"$f\" does not match /$pattern/"
            }
        }
        if (failure != null) return failure
        val value = e["value"] ?: JsonNull
        if (op in COLLECTION_OPS) return checkCollection(op, e, output)
        return when (op) {
            "noError" -> null
            "eq" -> if (equal(output, value)) null else "expected ${show(value)}, got ${show(output)}"
            "neq" -> if (equal(output, value)) "expected anything but ${show(value)}" else null
            "isNull" -> if (output is JsonNull) null else "expected null, got ${show(output)}"
            "notNull" -> if (output is JsonNull) "expected a value, got null" else null
            "isArray" -> if (output is JsonArray) null else "expected an array, got ${show(output)}"
            "isObject" -> if (output is JsonObject) null else "expected an object, got ${show(output)}"
            "isTrue" -> if (bool(output) == true) null else "expected true, got ${show(output)}"
            "isFalse" -> if (bool(output) == false) null else "expected false, got ${show(output)}"
            "gte", "gt", "lt", "lte" -> compare(op, output, value)
            else -> "unknown op $op"
        }
    }

    private fun compare(
        op: String,
        output: JsonElement,
        value: JsonElement,
    ): String? {
        val a = number(output)
        val b = number(value)
        if (a == null || b == null) return "expected numbers, got ${show(output)} and ${show(value)}"
        val ok =
            when (op) {
                "gte" -> a >= b
                "gt" -> a > b
                "lt" -> a < b
                else -> a <= b
            }
        return if (ok) null else "expected ${show(output)} $op ${show(value)}"
    }

    @Suppress("CyclomaticComplexMethod")
    private fun checkCollection(
        op: String,
        e: JsonObject,
        output: JsonElement,
    ): String? {
        val value = e["value"] ?: JsonNull
        val text = (output as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (op) {
            "nonEmptyString" ->
                when {
                    text == null -> "expected a string, got ${show(output)}"
                    text.isEmpty() -> "expected a non-empty string"
                    else -> null
                }
            "matches" -> {
                val pattern = e.str("pattern")
                if (text != null && Regex(pattern).containsMatchIn(text)) null else "expected /$pattern/, got ${show(output)}"
            }
            "hasKey" -> {
                val key = e.str("key")
                if ((output as? JsonObject)?.containsKey(key) == true) null else "no key $key in ${show(output)}"
            }
            "contains" -> {
                val needle = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                when {
                    text != null && needle != null -> if (text.contains(needle)) null else "\"$text\" does not contain \"$needle\""
                    output is JsonArray -> if (output.any { equal(it, value) }) null else "${show(value)} not in ${show(output)}"
                    else -> "expected a string or array, got ${show(output)}"
                }
            }
            "containsWhere", "notContainsWhere" -> {
                val key = e.str("key")
                val found = (output as? JsonArray).orEmpty().any { equal((it as? JsonObject)?.get(key) ?: JsonNull, value) }
                if (found == (op == "containsWhere")) null else "$op $key=${show(value)} failed on ${show(output)}"
            }
            "isSorted" -> {
                val strings = (output as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (strings == null || strings.any { it == null }) {
                    "expected an array of strings"
                } else {
                    val list = strings.filterNotNull()
                    if (list == list.sortedWith(::compareCodePoints)) null else "not sorted: ${show(output)}"
                }
            }
            else -> "unknown op $op"
        }
    }

    // ---- substitution and paths ---------------------------------------------

    private fun substitute(
        node: JsonElement,
        vars: Map<String, JsonElement>,
    ): JsonElement =
        when (node) {
            is JsonPrimitive ->
                if (!node.isString) {
                    node
                } else {
                    exactVar(node.content, vars) ?: JsonPrimitive(substituteText(node.content, vars))
                }
            is JsonObject -> JsonObject(node.entries.associate { (k, v) -> substituteText(k, vars) to substitute(v, vars) })
            is JsonArray -> JsonArray(node.map { substitute(it, vars) })
        }

    private fun substituteText(
        text: String,
        vars: Map<String, JsonElement>,
    ): String {
        var out = text.replace("\${run}", run)
        for ((name, value) in vars) out = out.replace("\${$name}", plain(value))
        return out
    }

    /** `"${name}"`, `"${name}+N"` or `"${name}-N"` → the saved value, typed. */
    private fun exactVar(
        text: String,
        vars: Map<String, JsonElement>,
    ): JsonElement? {
        val match = EXACT_VAR.matchEntire(text)
        val name = match?.groupValues?.get(1)
        val saved = name?.takeIf { it != "run" }?.let { vars[it] } ?: return null
        val offset = match.groupValues[2].toIntOrNull()
        val n = number(saved)
        return if (offset == null || n == null) saved else numberElement(n + offset)
    }

    companion object {
        private val EXACT_VAR = Regex("""^\$\{(\w+)\}([+-]\d+)?$""")
        private val COLLECTION_OPS = setOf("contains", "containsWhere", "notContainsWhere", "isSorted", "hasKey", "matches", "nonEmptyString")
        private const val SHOW_LIMIT = 160

        private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""

        /** Dot path: key, array index (negative from the end), or `length`. */
        fun select(
            node: JsonElement,
            path: String,
        ): JsonElement {
            var current = node
            for (segment in path.split(".").filter { it.isNotEmpty() }) {
                if (segment == "length") {
                    val length =
                        when {
                            current is JsonArray -> current.size
                            current is JsonObject -> current.size
                            current is JsonPrimitive && current.isString -> current.content.codePointCount(0, current.content.length)
                            else -> null
                        }
                    if (length != null) {
                        current = JsonPrimitive(length)
                        continue
                    }
                }
                val index = segment.toIntOrNull()
                current =
                    when {
                        index != null && current is JsonArray -> {
                            val i = if (index < 0) current.size + index else index
                            current.getOrNull(i) ?: JsonNull
                        }
                        current is JsonObject -> current[segment] ?: JsonNull
                        else -> return JsonNull
                    }
            }
            return current
        }

        fun number(value: JsonElement?): Double? {
            val p = value as? JsonPrimitive ?: return null
            if (p.isString || p is JsonNull || p.booleanOrNull != null) return null
            return p.doubleOrNull
        }

        fun bool(value: JsonElement): Boolean? = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        /** Deep JSON equality, numbers compared by value (1 == 1.0). */
        fun equal(
            a: JsonElement,
            b: JsonElement,
        ): Boolean {
            val x = number(a)
            val y = number(b)
            if (x != null && y != null) return x == y
            return when {
                a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { equal(a[it], b[it]) }
                a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { equal(a[it]!!, b[it]!!) }
                a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
                a is JsonPrimitive && b is JsonPrimitive -> a.isString == b.isString && a.content == b.content
                else -> false
            }
        }

        fun plain(value: JsonElement): String {
            val p = value as? JsonPrimitive
            if (p != null && p.isString) return p.content
            number(value)?.let { n -> return if (n == Math.rint(n) && !n.isInfinite()) n.toLong().toString() else n.toString() }
            return show(value)
        }

        fun show(value: JsonElement?): String {
            if (value == null || value is JsonNull) return "null"
            if (value is JsonPrimitive && value.isString) return "\"${value.content}\""
            val text = value.toString()
            return if (text.length > SHOW_LIMIT) text.take(SHOW_LIMIT) + "…" else text
        }

        private fun numberElement(n: Double): JsonPrimitive = if (n == Math.rint(n) && !n.isInfinite()) JsonPrimitive(n.toLong()) else JsonPrimitive(n)

        /** Unicode code point order, the same as Rust's `String` Ord. */
        private fun compareCodePoints(
            a: String,
            b: String,
        ): Int {
            val x = a.codePoints().toArray()
            val y = b.codePoints().toArray()
            for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return x[i].compareTo(y[i])
            return x.size.compareTo(y.size)
        }
    }
}
