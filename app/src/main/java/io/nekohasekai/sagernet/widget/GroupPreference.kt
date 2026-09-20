package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import kotlinx.coroutines.Job
import moe.matsuri.nb4a.ui.SimpleMenuPreference

/**
 * Chooses the group for new/imported proxies. Entries load off the main
 * thread after attach so inflation never runBlocking-queries Room.
 * The persisted value is kept while loading so the dropdown cannot snap to a
 * wrong default group id.
 */
class GroupPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = R.attr.dropdownPreferenceStyle
) : SimpleMenuPreference(context, attrs, defStyle, 0) {

    private var model = GroupPreferenceCatalog.loading(null)
    private var loadJob: Job? = null
    private var attached = false

    override fun onAttached() {
        super.onAttached()
        attached = true
        applyModel(GroupPreferenceCatalog.loading(value))
        loadJob?.cancel()
        loadJob = runOnIoDispatcher {
            val items = SagerDatabase.groupDao.allGroups().map { group ->
                GroupPreferenceItem(group.id, group.displayName())
            }
            val next = GroupPreferenceCatalog.ready(items)
            runOnMainDispatcher {
                if (!attached) return@runOnMainDispatcher
                applyModel(next)
            }
        }
    }

    override fun onDetached() {
        attached = false
        loadJob?.cancel()
        loadJob = null
        super.onDetached()
    }

    override fun getSummary(): CharSequence? {
        return GroupPreferenceCatalog.summary(value, model, super.getSummary())
    }

    private fun applyModel(next: GroupPreferenceCatalog.Model) {
        model = next
        entries = next.entries
        entryValues = next.entryValues
        notifyChanged()
    }
}
