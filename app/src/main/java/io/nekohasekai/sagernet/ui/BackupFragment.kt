package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.delay
import io.nekohasekai.sagernet.fmt.BackupSerializer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.*
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.ktx.snackbar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import io.nekohasekai.sagernet.fmt.KryoConverters
import java.text.SimpleDateFormat

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    private var pendingExportName: String? = null
    private var currentJob: kotlinx.coroutines.Job? = null
    private var snackbar: Snackbar? = null

    override fun onDestroyView() {
        super.onDestroyView()
        snackbar?.dismiss()
        snackbar = null
    }

    override fun onDestroy() {
        super.onDestroy()
        currentJob?.cancel()
        currentJob = null
    }

    override fun name0() = app.getString(R.string.backup)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExportName = savedInstanceState?.getString("pendingExportName")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingExportName", pendingExportName)
        super.onSaveInstanceState(outState)
    }

    private fun backupFileName() =
        "nekobox_backup_${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())}.json"

    var content = ""
    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
        val pending = pendingExportName?.let { File(app.cacheDir, it) }
        pendingExportName = null
        if (data != null) {
            lifecycleScope.launch {
                try {
                    onDefaultDispatcher {
                        check(pending?.isFile == true) { "Backup snapshot is unavailable; export again" }
                        pending.inputStream().use { input ->
                            checkNotNull(app.contentResolver.openOutputStream(data, "wt")) {
                                "Unable to open backup destination"
                            }.use { input.copyTo(it) }
                        }
                    }
                    if (view != null) {
                        snackbar(getString(R.string.action_export_msg)).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    if (view != null) {
                        snackbar(e.readableMessage).show()
                    }
                } finally {
                    pending?.delete()
                }
            }
        } else {
            pending?.delete()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)

        binding.actionExport.setOnClickListener {
            if (currentJob?.isActive == true || pendingExportName != null) return@setOnClickListener
            val profiles = binding.backupConfigurations.isChecked
            val rules = binding.backupRules.isChecked
            val settings = binding.backupSettings.isChecked
            currentJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val cacheFile = onDefaultDispatcher {
                        val bytes = doBackup(profiles, rules, settings)
                        File(app.cacheDir, backupFileName()).apply { writeBytes(bytes) }
                    }
                    pendingExportName = cacheFile.name
                    startFilesForResult(exportSettings, cacheFile.name)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    pendingExportName = null
                    snackbar(e.readableMessage).show()
                }
            }
        }

        binding.actionShare.setOnClickListener {
            if (currentJob?.isActive == true || pendingExportName != null) return@setOnClickListener
            val profiles = binding.backupConfigurations.isChecked
            val rules = binding.backupRules.isChecked
            val settings = binding.backupSettings.isChecked
            currentJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val cacheFile = onDefaultDispatcher {
                        val bytes = doBackup(profiles, rules, settings)
                        File(app.cacheDir, backupFileName()).apply { writeBytes(bytes) }
                    }
                    onMainDispatcher {
                        startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("application/json")
                                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    .putExtra(
                                        Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                            app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                        )
                                    ), app.getString(R.string.abc_shareactionprovider_share_with)
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }

    }

    private fun doBackup(
        profile: Boolean,
        rule: Boolean,
        setting: Boolean
    ): ByteArray {
        val out = BackupSerializer.exportDatabase(SagerDatabase.instance, profile, rule).apply {
            if (setting) {
                BackupSerializer.putParcelableArray(this, "settings", PublicDatabase.kvPairDao.all())
            }
        }

        val jsonContent = out.toStringPretty()
        return jsonContent.toByteArray()
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val activity = activity ?: return
        try {
            val fileName = onDefaultDispatcher {
                activity.contentResolver.query(file, null, null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
                }?.takeIf { it.isNotBlank() }
                    ?: file.lastPathSegment.orEmpty().substringAfterLast('/').substringAfter(':')
            }
            if (!fileName.endsWith(".json", true) && !fileName.endsWith(".zip", true)) {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
                return
            }
            val content = onDefaultDispatcher {
                checkNotNull(activity.contentResolver.openInputStream(file)).use { input ->
                if (fileName.endsWith(".zip", true)) {
                    ZipInputStream(BufferedInputStream(input)).use { zis ->
                        zis.nextEntry?.let { entry ->
                            if (entry.name.endsWith(".json")) {
                                zis.readBytes().toString(Charsets.UTF_8)
                            } else {
                                throw Exception("Invalid backup file format")
                            }
                        } ?: throw Exception("Invalid backup file format")
                    }
                } else {
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }
            }

            val json = JSONObject(content)
            onMainDispatcher {
                val import = LayoutImportBinding.inflate(layoutInflater)
                if (!json.has("profiles")) {
                    import.backupConfigurations.isVisible = false
                }
                if (!json.has("rules")) {
                    import.backupRules.isVisible = false
                }
                if (!json.has("settings")) {
                    import.backupSettings.isVisible = false
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                    .setView(import.root)
                    .setPositiveButton(R.string.backup_import) { _, _ ->
                        val profiles = import.backupConfigurations.isChecked
                        val rules = import.backupRules.isChecked
                        val settings = import.backupSettings.isChecked
                        SagerNet.stopService()

                        val binding = LayoutProgressBinding.inflate(layoutInflater)
                        binding.content.text = getString(R.string.backup_importing)
                        val dialog = AlertDialog.Builder(requireContext())
                            .setView(binding.root)
                            .setCancelable(false)
                            .show()
                        val appContext = requireContext().applicationContext
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.Default + NonCancellable) {
                                    finishImport(json, profiles, rules, settings)
                                    triggerFullRestart(appContext)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logs.w(e)
                                MessageStore.showMessage(e.readableMessage)
                            } finally {
                                try {
                                    dialog.dismiss()
                                } catch (e: Exception) {
                                    // Ignored if window already detached
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
            onMainDispatcher {
                MessageStore.showMessage(activity, e.readableMessage)
            }
        }
    }

    fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ) {
        KryoConverters.withStrictDeserialization {
            val decodedSettings = if (setting) {
                require(content.has("settings") && !content.isNull("settings")) {
                    "Backup settings are missing or invalid"
                }
                val list = BackupSerializer.getParcelableArray(content, "settings", KeyValuePair.CREATOR)
                val keys = HashSet<String>(list.size)
                for (kv in list) {
                    require(keys.add(kv.key)) { "Duplicate setting key in backup: ${kv.key}" }
                }
                list
            } else null

            data class DecodedProfiles(
                val profiles: List<ProxyEntity>,
                val groups: List<ProxyGroup>,
                val routerGroups: List<RouterGroup>,
                val routerMembers: List<RouterMember>,
                val routerSources: List<RouterGroupSource>,
                val validGroupIds: Set<Long>,
                val validRouterIds: Set<Long>,
                val validProxyIds: Set<Long>,
            )

            val decodedProfileData = if (profile) {
                require(content.has("profiles") && !content.isNull("profiles")) {
                    "Backup profiles are missing or invalid"
                }
                require(content.has("groups") && !content.isNull("groups")) {
                    "Backup groups are missing or invalid"
                }
                val profilesList = BackupSerializer.getParcelableArray(content, "profiles", ProxyEntity.CREATOR)
                val groupsList = BackupSerializer.getParcelableArray(content, "groups", ProxyGroup.CREATOR)
                val routerGroupsList = BackupSerializer.getParcelableArray(content, "routerGroups", RouterGroup.CREATOR)
                val routerMembersList = BackupSerializer.getParcelableArray(content, "routerMembers", RouterMember.CREATOR)
                val routerSourcesList = BackupSerializer.getParcelableArray(content, "routerSources", RouterGroupSource.CREATOR)

                profilesList.forEach { it.requireBean() }
                val groupIds = groupsList.mapTo(hashSetOf()) { it.id }
                require(profilesList.all { it.groupId in groupIds }) {
                    "Backup contains profiles without a group"
                }

                val routerIds = routerGroupsList.mapTo(hashSetOf()) { it.id }
                val proxyIds = profilesList.mapTo(hashSetOf()) { it.id }

                require(routerMembersList.all { it.routerId in routerIds && it.proxyId in proxyIds }) {
                    "Backup contains router members referencing missing routers or profiles"
                }
                require(routerSourcesList.all { it.routerId in routerIds && it.sourceGroupId in groupIds }) {
                    "Backup contains router sources referencing missing routers or groups"
                }

                DecodedProfiles(
                    profiles = profilesList,
                    groups = groupsList,
                    routerGroups = routerGroupsList,
                    routerMembers = routerMembersList,
                    routerSources = routerSourcesList,
                    validGroupIds = groupIds,
                    validRouterIds = routerIds,
                    validProxyIds = proxyIds,
                )
            } else null

            val decodedRules = if (rule) {
                require(content.has("rules") && !content.isNull("rules")) {
                    "Backup rules are missing or invalid"
                }
                val routerReferences = BackupSerializer.getRouterRuleReferences(content)
                val rulesList = BackupSerializer.getParcelableArray(content, "rules") {
                    ParcelizeBridge.createRule(it)
                }.map { imported ->
                    val routerGroupId = routerReferences[imported.id] ?: 0L
                    imported.copy(routerGroupId = routerGroupId)
                }

                val targetRouterIds = if (decodedProfileData != null) {
                    decodedProfileData.validRouterIds
                } else {
                    SagerDatabase.routerGroupDao.all().mapTo(hashSetOf()) { it.id }
                }
                val targetProxyIds = if (decodedProfileData != null) {
                    decodedProfileData.validProxyIds
                } else {
                    SagerDatabase.proxyDao.getAll().mapTo(hashSetOf()) { it.id }
                }

                BackupSerializer.validateRuleReferences(rulesList, targetRouterIds, targetProxyIds)
                rulesList
            } else null

            if (decodedProfileData != null && decodedRules == null) {
                val existingRules = SagerDatabase.rulesDao.allRules()
                BackupSerializer.validateRuleReferences(
                    existingRules,
                    decodedProfileData.validRouterIds,
                    decodedProfileData.validProxyIds
                )
            }

            SagerDatabase.instance.runInTransaction {
                if (decodedProfileData != null) {
                    SagerDatabase.routerGroupSourceDao.reset()
                    SagerDatabase.routerMemberDao.reset()
                    SagerDatabase.routerGroupDao.reset()
                    SagerDatabase.proxyDao.reset()
                    SagerDatabase.groupDao.reset()

                    SagerDatabase.groupDao.insert(decodedProfileData.groups)
                    SagerDatabase.proxyDao.insert(decodedProfileData.profiles)
                    if (decodedProfileData.routerGroups.isNotEmpty()) {
                        SagerDatabase.routerGroupDao.insert(decodedProfileData.routerGroups)
                    }
                    if (decodedProfileData.routerMembers.isNotEmpty()) {
                        SagerDatabase.routerMemberDao.insert(decodedProfileData.routerMembers)
                    }
                    if (decodedProfileData.routerSources.isNotEmpty()) {
                        SagerDatabase.routerGroupSourceDao.insert(decodedProfileData.routerSources)
                    }
                    SagerDatabase.routerGroupDao.clearInvalidSelections()
                }

                if (decodedRules != null) {
                    SagerDatabase.rulesDao.reset()
                    SagerDatabase.rulesDao.insert(decodedRules)
                }
            }
            if (decodedProfileData != null) {
                GroupManager.cleanupDanglingRouterMembers()
            }
            if (decodedSettings != null) {
                PublicDatabase.instance.runInTransaction {
                    PublicDatabase.kvPairDao.reset()
                    PublicDatabase.kvPairDao.insert(decodedSettings)
                }
            }
        }
    }

    private fun showMessage(message: String) {
        MessageStore.showMessage(message)
    }
    private fun showMessage(@StringRes resId: Int) {
        MessageStore.showMessage(requireActivity(), resId)
    }

    private fun showMessage(@StringRes resId: Int, vararg args: Any) {
        MessageStore.showMessage(requireActivity(), resId, *args)
    }

}
