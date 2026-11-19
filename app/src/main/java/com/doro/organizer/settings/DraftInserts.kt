package com.doro.organizer.settings

import com.doro.organizer.model.DraftInsert
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The value of `nav.draft_inserts`: draft-insert records keyed by `draftKey`, oldest first (architecture.md
 * § SettingsStore). Keys are draft-local UUIDs and values are ids, never user text.
 */
internal object DraftInserts {
    /**
     * [records] with [record] as the newest entry for [draftKey] (a previous one for the same key is replaced) and only
     * the newest [SettingsStore.MAX_DRAFT_INSERTS] kept.
     */
    fun with(records: List<Pair<String, DraftInsert>>, draftKey: String, record: DraftInsert) =
        (records.filter { it.first != draftKey } + (draftKey to record)).takeLast(SettingsStore.MAX_DRAFT_INSERTS)

    fun encode(records: List<Pair<String, DraftInsert>>): String = JSONArray().apply {
        records.forEach { (key, record) ->
            put(
                JSONObject()
                    .put(KEY, key)
                    .put(NOTE_ID, record.noteId)
                    .put(PERSONS, JSONObject(record.createdPersons))
                    .put(PROJECTS, JSONObject(record.createdProjects)),
            )
        }
    }.toString()

    /**
     * Malformed text reads as no records: losing one only reopens the small duplicate-insert window that DEC-37
     * already accepts, while failing would break every editor until the next data reset.
     */
    fun decode(text: String?): List<Pair<String, DraftInsert>> {
        if (text == null) return emptyList()
        return try {
            val array = JSONArray(text)
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                item.getString(KEY) to DraftInsert(
                    noteId = item.getLong(NOTE_ID),
                    createdPersons = item.getJSONObject(PERSONS).toLongMap(),
                    createdProjects = item.getJSONObject(PROJECTS).toLongMap(),
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun JSONObject.toLongMap(): Map<String, Long> = keys().asSequence().associateWith(::getLong)

    private const val KEY = "key"
    private const val NOTE_ID = "noteId"
    private const val PERSONS = "persons"
    private const val PROJECTS = "projects"
}
