package io.nekohasekai.sagernet.fmt

import android.os.Parcel
import android.os.Parcelable
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject

/** The Parcelable-backed JSON array format used by NekoBox backups. */
object BackupSerializer {

    const val BACKUP_VERSION = 3

    /** Capture related rows together; exporting must never repair or delete user data. */
    fun exportDatabase(database: SagerDatabase, profiles: Boolean, rules: Boolean): JSONObject {
        val json = JSONObject().put("version", BACKUP_VERSION)
        database.runInTransaction {
            if (profiles) {
                val allProfiles = database.proxyDao().getAll()
                allProfiles.forEach { it.requireBean() }
                putParcelableArray(json, "profiles", allProfiles)
                putParcelableArray(json, "groups", database.groupDao().allGroups())
                putParcelableArray(json, "routerGroups", database.routerGroupDao().all())
                putParcelableArray(json, "routerMembers", database.routerMemberDao().all())
                putParcelableArray(json, "routerSources", database.routerGroupSourceDao().all())
            }
            if (rules) {
                val allRules = database.rulesDao().allRules()
                putParcelableArray(json, "rules", allRules)
                putRouterRuleReferences(json, allRules)
            }
        }
        return json
    }

    fun putRouterRuleReferences(json: JSONObject, rules: Iterable<RuleEntity>) {
        json.put("routerRuleRefs", JSONArray().apply {
            rules.filter { it.routerGroupId > 0L }.forEach { rule ->
                put(JSONObject().apply {
                    put("ruleId", rule.id)
                    put("routerGroupId", rule.routerGroupId)
                })
            }
        })
    }

    fun validateRuleReferences(
        rules: Collection<RuleEntity>,
        routerIds: Set<Long>,
        proxyIds: Set<Long>
    ) {
        for (rule in rules) {
            if (rule.routerGroupId > 0L) {
                require(rule.routerGroupId in routerIds) {
                    "Rule ${rule.id} references missing router group ${rule.routerGroupId}"
                }
            } else if (rule.outbound > 0L) {
                require(rule.outbound in proxyIds) {
                    "Rule ${rule.id} references missing profile ${rule.outbound}"
                }
            }
        }
    }

    fun getRouterRuleReferences(json: JSONObject): Map<Long, Long> {
        if (!json.has("routerRuleRefs")) {
            return emptyMap()
        }
        require(!json.isNull("routerRuleRefs")) { "Section 'routerRuleRefs' in backup cannot be null" }
        val values = json.optJSONArray("routerRuleRefs")
            ?: throw IllegalArgumentException("Section 'routerRuleRefs' in backup must be a JSON array")
        return buildMap {
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                val ruleId = value.getLong("ruleId")
                val routerGroupId = value.getLong("routerGroupId")
                if (ruleId > 0L && routerGroupId > 0L) put(ruleId, routerGroupId)
            }
        }
    }

    fun putParcelableArray(
        json: JSONObject,
        key: String,
        values: Iterable<out Parcelable>
    ) {
        json.put(key, JSONArray().apply {
            values.forEach { put(encode(it)) }
        })
    }

    fun <T : Parcelable> getParcelableArray(
        json: JSONObject,
        key: String,
        creator: Parcelable.Creator<T>
    ): List<T> {
        if (!json.has(key)) return emptyList()
        require(!json.isNull(key)) { "Section '$key' in backup cannot be null" }
        val values = json.optJSONArray(key)
            ?: throw IllegalArgumentException("Section '$key' in backup must be a JSON array")
        return (0 until values.length()).map { index ->
            decode(values.getString(index), creator)
        }
    }

    fun <T> getParcelableArray(
        json: JSONObject,
        key: String,
        decoder: (Parcel) -> T
    ): List<T> {
        if (!json.has(key)) return emptyList()
        require(!json.isNull(key)) { "Section '$key' in backup cannot be null" }
        val values = json.optJSONArray(key)
            ?: throw IllegalArgumentException("Section '$key' in backup must be a JSON array")
        return (0 until values.length()).map { index ->
            val data = Util.b64Decode(values.getString(index))
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                decoder(parcel)
            } finally {
                parcel.recycle()
            }
        }
    }

    private fun encode(value: Parcelable): String {
        val parcel = Parcel.obtain()
        return try {
            value.writeToParcel(parcel, 0)
            Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    private fun <T : Parcelable> decode(
        encoded: String,
        creator: Parcelable.Creator<T>
    ): T {
        val data = Util.b64Decode(encoded)
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(data, 0, data.size)
            parcel.setDataPosition(0)
            creator.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
