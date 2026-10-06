package com.kachat.app.util

import com.google.gson.JsonParser

/**
 * Checks on JSON a Gson store wrote to disk (audit AND-001).
 *
 * Gson writes a class by its field names. A release build whose R8 rules did not keep those
 * names wrote them renamed (`{"a":...,"b":...}`), and a later build - with the keep rule, or just
 * a different mapping - reads that back either as all-null or, worse, into the wrong fields when
 * several share a type. Such a record is told apart by its keys: any key the class does not
 * declare means the record is not this class's, and the store resets it once rather than
 * misreading it.
 */
object PersistedJson {

    /** True when [json] is an object with at least one key outside [knownFields]. A record
     *  that does not parse, or is not an object, is not "foreign" here - the store's own read
     *  already fails on it and falls back. An empty object (`{}`, every field null) is fine. */
    fun hasForeignFields(json: String, knownFields: Set<String>): Boolean = try {
        val element = JsonParser.parseString(json)
        element.isJsonObject && element.asJsonObject.keySet().any { it !in knownFields }
    } catch (e: Exception) {
        false
    }
}
