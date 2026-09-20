package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.AdapterView
import android.widget.Spinner
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import kotlinx.coroutines.Job
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class OutboundPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = R.attr.dropdownPreferenceStyle
) : SimpleMenuPreference(context, attrs, defStyle, 0) {

    companion object {
        const val VALUE_SELECT_PROFILE = "3"
        const val VALUE_SELECT_ROUTER = "4"
    }

    init {
        setEntries(R.array.outbound_entry)
        setEntryValues(R.array.outbound_value)
        layoutResource = R.layout.preference_dropdown_reselectable
    }

    override fun setValue(value: String?) {
        val oldValue = this.value
        super.setValue(value)
        if (oldValue == value) {
            notifyChanged()
        }
    }

    private var dropdownOpened = false
    private var summaryJob: Job? = null
    private var attached = false
    @Volatile private var summaryCacheKey: String? = null
    @Volatile private var summaryCacheText: CharSequence? = null

    override fun onClick() {
        dropdownOpened = true
        super.onClick()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        dropdownOpened = false
        super.onBindViewHolder(holder)

        val spinner = holder.itemView.findViewById<Spinner>(R.id.spinner)
        (spinner as? ReselectableSpinner)?.onPopupClosed = { dropdownOpened = false }
        var selectionReady = false
        holder.itemView.post { selectionReady = true }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (!selectionReady || position < 0) return
                val newValue = entryValues?.getOrNull(position)?.toString() ?: return
                val reselectedPicker = dropdownOpened && newValue == value &&
                    newValue in setOf(VALUE_SELECT_PROFILE, VALUE_SELECT_ROUTER)
                if ((newValue != value || reselectedPicker) && callChangeListener(newValue)) {
                    value = newValue
                }
                dropdownOpened = false
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                dropdownOpened = false
            }
        }
    }

    override fun getSummary(): CharSequence? {
        val current = value
        val profileId = if (current == VALUE_SELECT_PROFILE) {
            DataStore.profileCacheStore.getLong(key + "Long") ?: 0
        } else 0L
        val routerId = if (current == VALUE_SELECT_ROUTER) DataStore.routeOutboundRouter else 0L
        val cacheKey = OutboundPreferenceSummary.cacheKey(current, profileId, routerId)
        if (summaryCacheKey == cacheKey) {
            return summaryCacheText ?: super.getSummary()
        }
        requestSummary(current, profileId, routerId, cacheKey)
        return OutboundPreferenceSummary.resolve(
            value = current,
            profileName = null,
            routerName = null,
            routerMissing = false,
            invalidRouterLabel = context.getString(R.string.router_reference_invalid),
            fallback = super.getSummary(),
        )
    }

    override fun onAttached() {
        super.onAttached()
        attached = true
    }

    override fun onDetached() {
        attached = false
        summaryJob?.cancel()
        summaryJob = null
        super.onDetached()
    }

    private fun requestSummary(current: String?, profileId: Long, routerId: Long, cacheKey: String) {
        summaryJob?.cancel()
        summaryJob = runOnIoDispatcher {
            val profileName = if (current == VALUE_SELECT_PROFILE && profileId > 0) {
                ProfileManager.getProfile(profileId)?.displayName()
            } else null
            val routerName = if (current == VALUE_SELECT_ROUTER && routerId > 0) {
                SagerDatabase.routerGroupDao.getById(routerId)?.name
            } else null
            val text = OutboundPreferenceSummary.resolve(
                value = current,
                profileName = profileName,
                routerName = routerName,
                routerMissing = current == VALUE_SELECT_ROUTER && routerId > 0 && routerName == null,
                invalidRouterLabel = context.getString(R.string.router_reference_invalid),
                fallback = null,
            )
            runOnMainDispatcher {
                if (!attached) return@runOnMainDispatcher
                summaryCacheKey = cacheKey
                summaryCacheText = text
                notifyChanged()
            }
        }
    }

}
