package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.dbOffMain
import moe.matsuri.nb4a.ui.SimpleMenuPreference

/**
 * Chooses the group for new/imported proxies. The entries come from
 * `proxy_groups` — the query runs synchronously in `init` because
 * Preference inflation already happens on the UI thread before the view is
 * attached. [dbOffMain] is the guardrail: it quietly shifts the call onto
 * Dispatchers.IO when the DB is no longer `allowMainThreadQueries`.
 */
class GroupPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = R.attr.dropdownPreferenceStyle
) : SimpleMenuPreference(context, attrs, defStyle, 0) {

    init {
        val groups = dbOffMain { SagerDatabase.groupDao.allGroups() }

        entries = groups.map { it.displayName() }.toTypedArray()
        entryValues = groups.map { "${it.id}" }.toTypedArray()
    }

    override fun getSummary(): CharSequence? {
        if (!value.isNullOrBlank() && value != "0") {
            return dbOffMain { SagerDatabase.groupDao.getById(value.toLong())?.displayName() }
                ?: super.getSummary()
        }
        return super.getSummary()
    }

}
