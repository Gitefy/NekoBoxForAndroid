package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.database.RouterGroupRepository
import io.nekohasekai.sagernet.database.RouterMember
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RouterGroupListFragment : PreferenceFragmentCompat() {
    private var rebuildJob: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        // Display current DB state only. Membership reconcile belongs to subscription /
        // profile / settings write paths — not every page visit.
        rebuild()
    }

    fun rebuild() {
        // onCreatePreferences runs inside Fragment.onCreate, before onCreateView:
        // there is no view yet, so viewLifecycleOwner is illegal here. Bind the
        // load to the fragment lifecycle and guard UI work with isAdded instead.
        if (!isAdded) return
        val context = requireContext()
        rebuildJob?.cancel()
        rebuildJob = lifecycleScope.launch(Dispatchers.IO) {
            val snapshot = runCatching { loadRouterListSnapshot() }.getOrElse { error ->
                Logs.w("Unable to load Router groups", error)
                RouterListSnapshot(emptyList(), emptyMap(), emptyMap())
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                bindRouterList(context, snapshot)
            }
        }
    }

    override fun onDestroyView() {
        rebuildJob?.cancel()
        rebuildJob = null
        super.onDestroyView()
    }

    private data class RouterListSnapshot(
        val groups: List<RouterGroup>,
        val membersByRouter: Map<Long, List<RouterMember>>,
        val selectedProxies: Map<Long, ProxyEntity>,
    )

    private fun loadRouterListSnapshot(): RouterListSnapshot {
        val groups = RouterGroupRepository.all()
        val membersByRouter = SagerDatabase.routerMemberDao.all().groupBy { it.routerId }
        val selectedIds = groups.mapNotNull { it.selectedProxyId.takeIf { id -> id > 0 } }
        val selectedProxies =
            if (selectedIds.isEmpty()) emptyMap()
            else SagerDatabase.proxyDao.getEntities(selectedIds).associateBy { it.id }
        return RouterListSnapshot(groups, membersByRouter, selectedProxies)
    }

    private fun bindRouterList(context: Context, snapshot: RouterListSnapshot) {
        val screen = preferenceManager.createPreferenceScreen(context)
        if (snapshot.groups.isEmpty()) {
            screen.addPreference(Preference(context).apply {
                title = getString(R.string.router_empty_title)
                summary = getString(R.string.router_empty_summary)
                setIcon(R.drawable.ic_hardware_router)
                setOnPreferenceClickListener {
                    startActivity(Intent(context, RouterGroupSettingsActivity::class.java))
                    true
                }
            })
        } else {
            val category = PreferenceCategory(context).apply {
                title = getString(R.string.router_groups_title)
            }
            screen.addPreference(category)
            snapshot.groups.forEach { group ->
                category.addPreference(
                    group.toPreference(
                        context,
                        snapshot.membersByRouter[group.id].orEmpty(),
                        snapshot.selectedProxies[group.selectedProxyId],
                    )
                )
            }
        }
        preferenceScreen = screen
    }

    private fun RouterGroup.toPreference(
        context: Context,
        members: List<RouterMember>,
        selectedProxy: ProxyEntity?,
    ): Preference = Preference(context).apply {
        title = name.ifBlank { stableTag }
        setIcon(R.drawable.ic_hardware_router)
        val modeName = context.getString(
            if (mode == RouterGroup.MODE_URL_TEST) R.string.router_mode_automatic
            else R.string.router_mode_manual
        )
        val selectedName = selectedProxy?.displayName() ?: context.getString(R.string.router_no_selection)

        summary = when {
            !enabled -> getString(R.string.router_group_disabled)
            lastError.isNotBlank() -> lastError
            mode == RouterGroup.MODE_URL_TEST -> getString(R.string.router_status, modeName, members.size)
            else -> "${getString(R.string.router_status, modeName, members.size)} • ${getString(R.string.router_current_node, selectedName)}"
        }

        setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), RouterGroupSettingsActivity::class.java).apply {
                putExtra(RouterGroupSettingsActivity.EXTRA_ROUTER_ID, id)
            })
            true
        }
    }
}
