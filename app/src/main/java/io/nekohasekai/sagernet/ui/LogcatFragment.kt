package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutLogcatBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.LogReadabilityCategory
import io.nekohasekai.sagernet.utils.LogReadabilityFormatter
import io.nekohasekai.sagernet.utils.LogReadabilitySeverity
import io.nekohasekai.sagernet.utils.NekoLogPolicy
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.utils.SendLog

class LogcatFragment : ToolbarFragment(R.layout.layout_logcat),
    Toolbar.OnMenuItemClickListener {

    private var _binding: LayoutLogcatBinding? = null
    val binding get() = _binding!!

    @SuppressLint("RestrictedApi", "WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_log)

        toolbar.inflateMenu(R.menu.logcat_menu)
        toolbar.setOnMenuItemClickListener(this)

        _binding = LayoutLogcatBinding.bind(view)

        binding.textview.breakStrategy = 0 // simple

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)

        reloadSession()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            DataStore.awaitReady()
            withContext(Dispatchers.Main) {
                if (_binding != null) reloadSession()
            }
        }
    }

    private var reloadJob: Job? = null

    private fun reloadSession() {
        if (_binding == null) return
        reloadJob?.cancel()
        reloadJob = viewLifecycleOwner.lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) { String(SendLog.getNekoLog(512 * 1024)) }
            if (_binding == null) return@launch
            val isReady = DataStore.configurationStore.isReady()
            val logLevel = DataStore.logLevel
            when (NekoLogPolicy.pageState(isReady, logLevel, raw)) {
                NekoLogPolicy.PageState.DISABLED -> {
                    binding.textview.text = getString(R.string.log_disabled)
                    return@launch
                }
                NekoLogPolicy.PageState.EMPTY -> {
                    binding.textview.text = getString(R.string.log_empty)
                    return@launch
                }
                NekoLogPolicy.PageState.CONTENT -> {}
            }

            val context = requireContext()
            val baseText = binding.textview.currentTextColor
            val formatted = withContext(Dispatchers.Default) {
                buildFormattedLog(context, raw, baseText)
            }
            if (_binding == null) return@launch
            binding.textview.text = formatted
            binding.textview.clearFocus()
            binding.textview.doOnLayout {
                if (_binding != null) {
                    binding.scroolview.scrollTo(0, binding.textview.height)
                }
            }
        }
    }

    private fun buildFormattedLog(context: Context, raw: String, baseText: Int): CharSequence {
        val items = LogReadabilityFormatter.summarize(raw)
        if (items.isEmpty()) {
            return context.getString(R.string.log_readable_no_issues)
        }
        val errorColor = 0xFFEF5350.toInt()   // Red 400
        val warningColor = 0xFFFFA726.toInt() // Orange 400
        val hintColor = (baseText and 0x00FFFFFF) or (0x99 shl 24)

        val ssb = SpannableStringBuilder()
        items.forEach { item ->
            val headerStart = ssb.length
            ssb.append(
                context.getString(
                    when (item.severity) {
                        LogReadabilitySeverity.ERROR -> R.string.log_readable_error
                        LogReadabilitySeverity.WARNING -> R.string.log_readable_warning
                    },
                ),
            )
            ssb.append(" · ")
            ssb.append(
                context.getString(
                    when (item.category) {
                        LogReadabilityCategory.VPN -> R.string.log_readable_vpn_title
                        LogReadabilityCategory.CONFIG -> R.string.log_readable_config_title
                        LogReadabilityCategory.ROUTER -> R.string.log_readable_router_title
                        LogReadabilityCategory.DNS -> R.string.log_readable_dns_title
                        LogReadabilityCategory.CONNECTION -> R.string.log_readable_connection_title
                        LogReadabilityCategory.RECONNECT -> R.string.log_readable_reconnect_title
                        LogReadabilityCategory.URL_TEST -> R.string.log_readable_url_test_title
                        LogReadabilityCategory.OTHER -> R.string.log_readable_other_title
                    },
                ),
            )
            if (item.occurrences > 1) {
                ssb.append(" · ").append(context.getString(R.string.log_readable_occurrences, item.occurrences))
            }
            val headerEnd = ssb.length
            val headerColor = when (item.severity) {
                LogReadabilitySeverity.ERROR -> errorColor
                LogReadabilitySeverity.WARNING -> warningColor
            }
            ssb.setSpan(ForegroundColorSpan(headerColor), headerStart, headerEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(StyleSpan(Typeface.BOLD), headerStart, headerEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            val maxDetailsPerItem = 50
            val displayDetails = item.details.take(maxDetailsPerItem)
            displayDetails.forEach { detail ->
                ssb.append('\n')
                detail.timestamp?.let { ts ->
                    val tsStart = ssb.length
                    ssb.append(ts).append(" · ")
                    ssb.setSpan(ForegroundColorSpan(hintColor), tsStart, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                ssb.append(detail.message)
                if (detail.occurrences > 1 && detail.occurrences != item.occurrences) {
                    ssb.append(" · ").append(context.getString(R.string.log_readable_occurrences, detail.occurrences))
                }
            }
            if (item.details.size > maxDetailsPerItem) {
                ssb.append('\n')
                val overflowStart = ssb.length
                ssb.append("... +${item.details.size - maxDetailsPerItem} more")
                ssb.setSpan(ForegroundColorSpan(hintColor), overflowStart, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (item.category != LogReadabilityCategory.OTHER) {
                ssb.append('\n')
                val msgStart = ssb.length
                ssb.append(context.getString(item.messageRes))
                ssb.setSpan(ForegroundColorSpan(hintColor), msgStart, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            ssb.append('\n')
            val actionStart = ssb.length
            ssb.append("→ ").append(context.getString(item.nextActionRes))
            ssb.setSpan(ForegroundColorSpan(hintColor), actionStart, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(StyleSpan(Typeface.ITALIC), actionStart, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            ssb.append("\n\n")
        }
        while (ssb.isNotEmpty() && ssb[ssb.length - 1] == '\n') {
            ssb.delete(ssb.length - 1, ssb.length)
        }
        return ssb
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_logcat -> {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        Libcore.nekoLogClear()
                        Runtime.getRuntime().exec("/system/bin/logcat -c")
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            if (_binding != null) {
                                snackbar(e.readableMessage).show()
                            }
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        if (_binding != null) {
                            reloadSession()
                        }
                    }
                }

            }

            R.id.action_send_logcat -> {
                val context = requireContext()
                runOnDefaultDispatcher {
                    SendLog.sendLog(context, "EgoX")
                }
            }

            R.id.action_refresh -> {
                reloadSession()
            }
        }
        return true
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

}
