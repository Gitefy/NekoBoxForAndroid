package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.database.RouterGroupRepository
import io.nekohasekai.sagernet.database.RouterMember
import io.nekohasekai.sagernet.database.SagerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RouterGroupListFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) = rebuild()

    override fun onResume() {
        super.onResume()
        rebuild()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                GroupManager.reconcileRouterMembers(GroupManager.snapshotRouterMembers())
            }
            withContext(Dispatchers.Main) {
                if (isAdded) rebuild()
            }
        }
    }

    fun rebuild() {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        val groups = RouterGroupRepository.all()

        if (groups.isEmpty()) {
            screen.addPreference(Preference(requireContext()).apply {
                title = getString(R.string.router_empty_title)
                summary = getString(R.string.router_empty_summary)
                setIcon(R.drawable.ic_hardware_router)
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), RouterGroupSettingsActivity::class.java))
                    true
                }
            })
        } else {
            val category = PreferenceCategory(requireContext()).apply {
                title = getString(R.string.router_groups_title)
            }
            screen.addPreference(category)
            groups.forEach { group -> category.addPreference(group.toPreference()) }
        }

        preferenceScreen = screen
    }

    private fun RouterGroup.toPreference(): Preference = Preference(requireContext()).apply {
        title = name.ifBlank { stableTag }
        setIcon(R.drawable.ic_hardware_router)
        val members = SagerDatabase.routerMemberDao.getByRouter(id)
        val modeName = getString(
            if (mode == RouterGroup.MODE_URL_TEST) R.string.router_mode_automatic
            else R.string.router_mode_manual
        )
        val selectedProxy = if (selectedProxyId > 0) SagerDatabase.proxyDao.getById(selectedProxyId) else null
        val selectedName = selectedProxy?.displayName() ?: getString(R.string.router_no_selection)

        summary = when {
            !enabled -> getString(R.string.router_group_disabled)
            lastError.isNotBlank() -> lastError
            mode == RouterGroup.MODE_URL_TEST -> getString(R.string.router_status, modeName, members.size)
            else -> "${getString(R.string.router_status, modeName, members.size)} • ${getString(R.string.router_current_node, selectedName)}"
        }

        setOnPreferenceClickListener {
            if (mode == RouterGroup.MODE_SELECTOR && members.isNotEmpty()) {
                showNodeSelectionDialog(this@toPreference, members)
            } else {
                startActivity(Intent(requireContext(), RouterGroupSettingsActivity::class.java).apply {
                    putExtra(RouterGroupSettingsActivity.EXTRA_ROUTER_ID, id)
                })
            }
            true
        }
    }

    private fun showNodeSelectionDialog(group: RouterGroup, members: List<RouterMember>) {
        val proxies = SagerDatabase.proxyDao.getEntities(members.map { it.proxyId })
        val proxyMap = proxies.associateBy { it.id }
        val orderedProxies = members.mapNotNull { proxyMap[it.proxyId] }
        if (orderedProxies.isEmpty()) {
            startActivity(Intent(requireContext(), RouterGroupSettingsActivity::class.java).apply {
                putExtra(RouterGroupSettingsActivity.EXTRA_ROUTER_ID, group.id)
            })
            return
        }
        val items = orderedProxies.map { it.displayName() }.toTypedArray()
        val currentIndex = orderedProxies.indexOfFirst { it.id == group.selectedProxyId }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(group.name.ifBlank { group.stableTag })
            .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                val chosen = orderedProxies[which]
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        RouterGroupRepository.select(group.id, chosen.id)
                    }.onSuccess { updated ->
                        if (DataStore.serviceState.started) {
                            SagerNet.reloadService(updated.stableTag, chosen.id)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        rebuild()
                    }
                }
                dialog.dismiss()
            }
            .setNeutralButton(R.string.router_edit_group) { _, _ ->
                startActivity(Intent(requireContext(), RouterGroupSettingsActivity::class.java).apply {
                    putExtra(RouterGroupSettingsActivity.EXTRA_ROUTER_ID, group.id)
                })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
