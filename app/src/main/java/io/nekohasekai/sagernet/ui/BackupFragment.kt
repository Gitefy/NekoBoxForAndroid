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

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    private lateinit var binding: LayoutBackupBinding
    private lateinit var backupData: ByteArray
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

    var content = ""
    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
        if (data != null) {
            runOnDefaultDispatcher {
                try {
                    requireActivity().contentResolver.openOutputStream(data)!!.use { os ->
                        os.write(backupData)
                    }
                    onMainDispatcher {
                        snackbar(getString(R.string.action_export_msg)).show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)

        binding.actionExport.setOnClickListener {
            runOnDefaultDispatcher {
                try {
                    backupData = doBackup(
                        binding.backupConfigurations.isChecked,
                        binding.backupRules.isChecked,
                        binding.backupSettings.isChecked
                    )
                    onMainDispatcher {
                        startFilesForResult(
                            exportSettings, "nekobox_backup_${Date().toLocaleString()}.json"
                        )
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }

        binding.actionShare.setOnClickListener {
            runOnDefaultDispatcher {
                try {
                    backupData = doBackup(
                        binding.backupConfigurations.isChecked,
                        binding.backupRules.isChecked,
                        binding.backupSettings.isChecked
                    )
                    app.cacheDir.mkdirs()
                    val cacheFile = File(
                        app.cacheDir, "nekobox_backup_${Date().toLocaleString()}.json"
                    )
                    cacheFile.writeBytes(backupData)
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
        val out = JSONObject().apply {
            put("version", BackupSerializer.BACKUP_VERSION)
            if (profile) {
                val allProfiles = SagerDatabase.proxyDao.getAll()
                val (validProfiles, corruptedProfiles) = allProfiles.partition { proxy ->
                    runCatching { proxy.requireBean() }.isSuccess
                }
                if (corruptedProfiles.isNotEmpty()) {
                    Logs.w("Found ${corruptedProfiles.size} corrupted profiles in database, cleaning up...")
                    runCatching { SagerDatabase.proxyDao.deleteProxy(corruptedProfiles) }
                }
                BackupSerializer.putParcelableArray(this, "profiles", validProfiles)
                BackupSerializer.putParcelableArray(this, "groups", SagerDatabase.groupDao.allGroups())
                BackupSerializer.putParcelableArray(this, "routerGroups", SagerDatabase.routerGroupDao.all())
                BackupSerializer.putParcelableArray(this, "routerMembers", SagerDatabase.routerMemberDao.all())
                BackupSerializer.putParcelableArray(this, "routerSources", SagerDatabase.routerGroupSourceDao.all())
            }
            if (rule) {
                val rules = SagerDatabase.rulesDao.allRules()
                BackupSerializer.putParcelableArray(this, "rules", rules)
                BackupSerializer.putRouterRuleReferences(this, rules)
            }
            if (setting) {
                BackupSerializer.putParcelableArray(this, "settings", PublicDatabase.kvPairDao.all())
            }
        }

        val jsonContent = out.toStringPretty()
        return jsonContent.toByteArray()
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val activity = requireActivity()
        val fileName = requireContext().contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".json") && !fileName.endsWith(".zip")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        try {
            val content = requireContext().contentResolver.openInputStream(file)!!.use { input ->
                if (fileName.endsWith(".zip")) {
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
                        SagerNet.stopService()

                        val binding = LayoutProgressBinding.inflate(layoutInflater)
                        binding.content.text = getString(R.string.backup_importing)
                        val dialog = AlertDialog.Builder(requireContext())
                            .setView(binding.root)
                            .setCancelable(false)
                            .show()
                        runOnDefaultDispatcher {
                            runCatching {
                                finishImport(
                                    json,
                                    import.backupConfigurations.isChecked,
                                    import.backupRules.isChecked,
                                    import.backupSettings.isChecked
                                )
                                triggerFullRestart(requireContext())
                            }.onFailure {
                                Logs.w(it)
                                onMainDispatcher {
                                    dialog.dismiss()
                                    MessageStore.showMessage(activity, it.readableMessage)
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
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
        SagerDatabase.instance.runInTransaction {
            if (profile && content.has("profiles")) {
                val profiles = BackupSerializer.getParcelableArray(content, "profiles", ProxyEntity.CREATOR)
                val groups = BackupSerializer.getParcelableArray(content, "groups", ProxyGroup.CREATOR)
                val routerGroups = BackupSerializer.getParcelableArray(content, "routerGroups", RouterGroup.CREATOR)
                val routerMembers = BackupSerializer.getParcelableArray(content, "routerMembers", RouterMember.CREATOR)
                val routerSources = BackupSerializer.getParcelableArray(content, "routerSources", RouterGroupSource.CREATOR)

                SagerDatabase.routerGroupSourceDao.reset()
                SagerDatabase.routerMemberDao.reset()
                SagerDatabase.routerGroupDao.reset()
                SagerDatabase.proxyDao.reset()
                SagerDatabase.groupDao.reset()

                SagerDatabase.groupDao.insert(groups)
                SagerDatabase.proxyDao.insert(profiles)
                if (routerGroups.isNotEmpty()) SagerDatabase.routerGroupDao.insert(routerGroups)
                val validRouterIds = SagerDatabase.routerGroupDao.all().mapTo(hashSetOf()) { it.id }
                val validGroupIds = SagerDatabase.groupDao.allGroups().mapTo(hashSetOf()) { it.id }
                val validProxyIds = SagerDatabase.proxyDao.getAll().mapTo(hashSetOf()) { it.id }
                val validMembers = routerMembers.filter { it.routerId in validRouterIds && it.proxyId in validProxyIds }
                val validSources = routerSources.filter { it.routerId in validRouterIds && it.sourceGroupId in validGroupIds }
                if (validMembers.isNotEmpty()) SagerDatabase.routerMemberDao.insert(validMembers)
                if (validSources.isNotEmpty()) SagerDatabase.routerGroupSourceDao.insert(validSources)
                SagerDatabase.routerGroupDao.clearInvalidSelections()
            }

            if (rule && content.has("rules")) {
                val routerReferences = BackupSerializer.getRouterRuleReferences(content)
                val rules = BackupSerializer.getParcelableArray(content, "rules") {
                    ParcelizeBridge.createRule(it)
                }.map { imported ->
                    val routerGroupId = routerReferences[imported.id] ?: 0L
                    imported.copy(routerGroupId = routerGroupId)
                }
                SagerDatabase.rulesDao.reset()
                SagerDatabase.rulesDao.insert(rules)
            }
        }
        if (profile) {
            GroupManager.cleanupDanglingRouterMembers()
        }
        if (setting && content.has("settings")) {
            val settings = BackupSerializer.getParcelableArray(content, "settings", KeyValuePair.CREATOR)
            PublicDatabase.kvPairDao.reset()
            PublicDatabase.kvPairDao.insert(settings)
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
