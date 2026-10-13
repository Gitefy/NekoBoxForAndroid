package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.RequestFlowData
import io.nekohasekai.sagernet.bg.proto.RequestFlowMapper
import io.nekohasekai.sagernet.bg.RequestReloadAck
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RequestRuleApply
import io.nekohasekai.sagernet.database.RequestRuleFactory
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import java.util.Locale

class RequestFragment : ToolbarFragment(R.layout.layout_request) {

    private lateinit var list: RecyclerView
    private lateinit var adapter: RequestAdapter
    private val labels = RequestAppLabelCache(
        resolve = { pkg -> runCatching { PackageCache.loadLabel(pkg) }.getOrNull() },
        isInstalled = { pkg -> PackageCache[pkg] != null },
    )
    private val listener = { refresh() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_request)
        list = view.findViewById(R.id.request_list)
        list.layoutManager = FixedLinearLayoutManager(list)
        adapter = RequestAdapter()
        list.adapter = adapter
        view.findViewById<EditText>(R.id.request_search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                RequestStore.query = s?.toString().orEmpty()
                refresh()
            }
        })
        view.findViewById<RadioGroup>(R.id.request_filter).setOnCheckedChangeListener { _, checkedId ->
            RequestStore.kindFilter = when (checkedId) {
                R.id.request_filter_proxy -> RequestStore.FILTER_PROXY
                R.id.request_filter_direct -> RequestStore.FILTER_DIRECT
                R.id.request_filter_block -> RequestStore.FILTER_BLOCK
                else -> RequestStore.FILTER_ALL
            }
            refresh()
        }
        RequestStore.addListener(listener)
        refresh()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        labels.clear()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        (activity as? MainActivity)?.setRequestPageVisible(true)
    }

    override fun onStop() {
        (activity as? MainActivity)?.setRequestPageVisible(false)
        super.onStop()
    }

    override fun onDestroyView() {
        RequestStore.removeListener(listener)
        if (::list.isInitialized) {
            list.adapter = null
        }
        super.onDestroyView()
    }

    private fun refresh() {
        val next = RequestStore.filtered()
        if (next !== adapter.currentList) {
            adapter.submitList(next)
        }
        runOnDefaultDispatcher {
            val misses = labels.resolveMissing(next.map { it.packageName })
            if (misses > 0) {
                onMainDispatcher {
                    adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_LABEL)
                }
            }
        }
    }

    inner class RequestAdapter : ListAdapter<RequestFlowData, RequestHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestHolder {
            return RequestHolder(layoutInflater.inflate(R.layout.layout_request_item, parent, false))
        }
        override fun onBindViewHolder(holder: RequestHolder, position: Int) = holder.bind(getItem(position))
        override fun onBindViewHolder(holder: RequestHolder, position: Int, payloads: MutableList<Any>) {
            val item = getItem(position)
            if (payloads.isEmpty()) {
                holder.bind(item)
                return
            }
            if (payloads.contains(PAYLOAD_STATS)) holder.bindStats(item)
            if (payloads.contains(PAYLOAD_LABEL)) holder.bindApp(item)
        }
    }

    inner class RequestHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val appView: TextView = view.findViewById(R.id.request_app)
        private val targetView: TextView = view.findViewById(R.id.request_target)
        private val routeView: TextView = view.findViewById(R.id.request_route)
        private val metaView: TextView = view.findViewById(R.id.request_meta)

        init {
            itemView.setOnClickListener {
                val selected = RequestRowSelection.select(bindingAdapterPosition, adapter.currentList)
                    ?: return@setOnClickListener
                showDetail(selected)
            }
        }

        fun bind(flow: RequestFlowData) {
            bindApp(flow)
            val host = if (flow.domain.isNotBlank()) {
                RequestDestination.formatHostPort(flow.domain, flow.destinationPort)
            } else {
                RequestDestination.formatHostPort(flow.destinationAddress, flow.destinationPort)
            }
            targetView.text = listOf(flow.network.uppercase(Locale.US), host).filter { it.isNotBlank() }.joinToString(" · ")
            routeView.text = when (flow.kind) {
                RequestFlowMapper.KIND_DIRECT -> "DIRECT"
                RequestFlowMapper.KIND_BLOCK -> "REJECT"
                else -> {
                    val left = flow.routerName.ifBlank { flow.logicalOutbound }
                    val right = flow.finalProfileName.ifBlank { flow.finalOutboundTag }
                    if (left.isBlank()) right else "$left › $right"
                }
            }
            bindStats(flow)
        }

        fun bindApp(flow: RequestFlowData) {
            appView.text = appLabel(flow)
        }

        fun bindStats(flow: RequestFlowData) {
            val state = if (flow.closed) "closed" else "active"
            val time = formatTime(flow.createdAt)
            metaView.text = "$time  ↑ ${formatBytes(flow.uploadBytes)}  ↓ ${formatBytes(flow.downloadBytes)}  $state"
        }
    }

    private fun appLabel(flow: RequestFlowData): String {
        val packages = labels.displayAll(flow.packageName)
        if (packages.isNotEmpty()) return packages.joinToString(", ")
        return if (flow.uid > 0) {
            getString(R.string.request_uid_label, flow.uid)
        } else {
            getString(R.string.request_app_unknown)
        }
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        if (value < 1024 * 1024) return "${value / 1024} KB"
        return String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
    }

    private fun formatTime(epochMs: Long): String {
        if (epochMs <= 0L) return ""
        return java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(epochMs))
    }

    private fun formatTimeFull(epochMs: Long): String {
        if (epochMs <= 0L) return ""
        return java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(java.util.Date(epochMs))
    }

    private fun showDetail(flow: RequestFlowData) {
        val body = buildString {
            appendLine("${getString(R.string.request_app_identity)}: ${appLabel(flow)}")
            appendLine("${getString(R.string.request_packages)}: ${RequestRuleFactory.parsePackageNames(flow.packageName).joinToString(", ").ifBlank { getString(R.string.request_app_unknown) }}")
            appendLine("${getString(R.string.request_uid)}: ${flow.uid}")
            if (RequestRuleFactory.parsePackageNames(flow.packageName).size > 1) {
                appendLine(getString(R.string.request_shared_uid_notice))
            }
            appendLine("Domain: ${flow.domain}")
            appendLine("Destination: ${RequestDestination.formatHostPort(flow.destinationAddress, flow.destinationPort)}")
            if (flow.originDestination.isNotBlank()) {
                appendLine("Origin: ${flow.originDestination}")
            }
            appendLine("Network: ${flow.network}")
            appendLine("Rule: ${flow.matchedRuleText}")
            if (flow.chain.isNotBlank()) {
                appendLine("Chain: ${flow.chain}")
            }
            appendLine("Logical: ${flow.logicalOutbound}")
            appendLine("Router: ${flow.routerName.ifBlank { flow.routerStableTag }}")
            appendLine("Actual: ${flow.finalProfileName.ifBlank { flow.finalOutboundTag }}")
            appendLine("Upload: ${formatBytes(flow.uploadBytes)}")
            appendLine("Download: ${formatBytes(flow.downloadBytes)}")
            appendLine("Start: ${formatTimeFull(flow.createdAt)}")
            appendLine("Closed: ${if (flow.closed) formatTimeFull(flow.closedAt) else "active"}")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.menu_request)
            .setMessage(body)
            .setPositiveButton(R.string.request_add_rule) { _, _ -> showCreateRule(flow) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCreateRule(flow: RequestFlowData) {
        val matchLabels = mutableListOf<String>()
        val matchKinds = mutableListOf<RequestRuleFactory.MatchKind>()
        if (flow.domain.isNotBlank()) {
            matchLabels.add(getString(R.string.request_match_exact_domain))
            matchKinds.add(RequestRuleFactory.MatchKind.EXACT_DOMAIN)
            matchLabels.add(getString(R.string.request_match_domain_suffix))
            matchKinds.add(RequestRuleFactory.MatchKind.DOMAIN_SUFFIX)
        }
        if (labels.hasResolvedPackages(flow.packageName)) {
            matchLabels.add(getString(R.string.request_match_app))
            matchKinds.add(RequestRuleFactory.MatchKind.APP)
        }
        if (RequestDestination.isRawIp(flow.destinationAddress)) {
            matchLabels.add(getString(R.string.request_match_ip))
            matchKinds.add(RequestRuleFactory.MatchKind.DEST_IP)
        }
        if (matchKinds.isEmpty()) return
        val sharedUidPackages = RequestRuleFactory.parsePackageNames(flow.packageName)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.request_match)
            .apply {
                if (sharedUidPackages.size > 1) setMessage(R.string.request_shared_uid_notice)
            }
            .setItems(matchLabels.toTypedArray()) { _, which ->
                showOutboundPicker(flow, matchKinds[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showOutboundPicker(flow: RequestFlowData, match: RequestRuleFactory.MatchKind) {
        runOnDefaultDispatcher {
            val routers = SagerDatabase.routerGroupDao.all().filter { it.enabled }
            val labels = mutableListOf(
                getString(R.string.route_bypass),
                getString(R.string.route_block),
                getString(R.string.route_proxy),
            )
            val kinds = mutableListOf(
                RequestRuleFactory.OutboundKind.DIRECT,
                RequestRuleFactory.OutboundKind.REJECT,
                RequestRuleFactory.OutboundKind.PROXY,
            )
            val tags = mutableListOf("", "", "")
            routers.forEach { router ->
                labels.add(router.name.ifBlank { router.stableTag })
                kinds.add(RequestRuleFactory.OutboundKind.ROUTER)
                tags.add(router.stableTag)
            }
            onMainDispatcher {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.request_outbound)
                    .setItems(labels.toTypedArray()) { _, which ->
                        confirmSave(flow, match, kinds[which], tags[which], routers.associate { it.stableTag to it.id })
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun confirmSave(
        flow: RequestFlowData,
        match: RequestRuleFactory.MatchKind,
        outbound: RequestRuleFactory.OutboundKind,
        routerTag: String,
        routers: Map<String, Long>,
    ) {
        val draft = RequestRuleFactory.build(
            match = match,
            outboundKind = outbound,
            domain = flow.domain,
            packageName = flow.packageName,
            destinationIp = flow.destinationAddress,
            routerStableTag = routerTag,
            routersByStableTag = routers,
        ) ?: return
        val persist: suspend () -> Boolean = {
            runCatching {
                ProfileManager.createRule(draft.toRuleEntity())
                true
            }.getOrDefault(false)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.request_save_apply)
            .setMessage(draft.name)
            .setPositiveButton(R.string.request_save_only) { _, _ ->
                persistRule(persist, apply = false)
            }
            .setNeutralButton(R.string.request_save_apply) { _, _ ->
                persistRule(persist, apply = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun persistRule(persist: suspend () -> Boolean, apply: Boolean) {
        runOnLifecycleDispatcher {
            val result = if (apply) {
                RequestRuleApply.saveAndApply(
                    persist = persist,
                    reload = {
                        RequestReloadAck.awaitApplied(
                            send = { request -> SagerNet.reloadServiceFully(request) },
                        )
                    },
                )
            } else {
                RequestRuleApply.saveOnly(persist)
            }
            onMainDispatcher {
                if (!isAdded || view == null) return@onMainDispatcher
                val msg = when (result.outcome) {
                    RequestRuleApply.Outcome.SAVED_NOT_APPLIED ->
                        getString(R.string.request_rule_saved_not_applied)
                    RequestRuleApply.Outcome.APPLIED -> getString(R.string.request_rule_applied)
                    RequestRuleApply.Outcome.RELOAD_FAILED ->
                        getString(R.string.request_rule_saved_reload_failed)
                    RequestRuleApply.Outcome.PERSIST_FAILED ->
                        getString(R.string.request_rule_persist_failed)
                }
                (activity as? MainActivity)?.snackbar(msg)?.show()
            }
        }
    }

    companion object {
        const val PAYLOAD_STATS = "stats"
        const val PAYLOAD_LABEL = "label"
        private val DIFF = object : DiffUtil.ItemCallback<RequestFlowData>() {
            override fun areItemsTheSame(oldItem: RequestFlowData, newItem: RequestFlowData): Boolean {
                return RequestFlowDiff.sameItem(oldItem, newItem)
            }
            override fun areContentsTheSame(oldItem: RequestFlowData, newItem: RequestFlowData): Boolean {
                return RequestFlowDiff.sameContent(oldItem, newItem)
            }
            override fun getChangePayload(oldItem: RequestFlowData, newItem: RequestFlowData): Any? {
                return if (RequestFlowDiff.statsOnly(oldItem, newItem)) PAYLOAD_STATS else null
            }
        }
    }
}
