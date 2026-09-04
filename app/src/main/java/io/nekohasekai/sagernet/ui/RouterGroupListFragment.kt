package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.RouterGroup
import io.nekohasekai.sagernet.database.RouterGroupRepository
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

    private fun rebuild() {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        screen.addPreference(Preference(requireContext()).apply {
            title = getString(R.string.router_group_add)
            summary = getString(R.string.router_group_add_summary)
            setIcon(R.drawable.ic_action_note_add)
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), RouterGroupSettingsActivity::class.java))
                true
            }
        })
        val category = PreferenceCategory(requireContext()).apply {
            title = getString(R.string.router_groups_title)
        }
        screen.addPreference(category)
        RouterGroupRepository.all().forEach { group -> category.addPreference(group.toPreference()) }
        preferenceScreen = screen
    }

    private fun RouterGroup.toPreference() = Preference(requireContext()).apply {
        title = name.ifBlank { stableTag }
        val members = SagerDatabase.routerMemberDao.getByRouter(id)
        val modeName = getString(
            if (mode == RouterGroup.MODE_URL_TEST) R.string.router_mode_automatic
            else R.string.router_mode_manual
        )
        val state = when {
            !enabled -> getString(R.string.router_group_disabled)
            lastError.isNotBlank() -> lastError
            else -> getString(R.string.router_status, modeName, members.size)
        }
        summary = state
        setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), RouterGroupSettingsActivity::class.java).apply {
                putExtra(RouterGroupSettingsActivity.EXTRA_ROUTER_ID, id)
            })
            true
        }
    }
}
