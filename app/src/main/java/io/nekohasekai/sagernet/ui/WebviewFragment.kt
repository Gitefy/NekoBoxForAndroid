package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import moe.matsuri.nb4a.utils.WebViewUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

internal fun localPanelUrl(url: String): String? = runCatching {
    val uri = URI(url).normalize()
    if (uri.path?.split('/')?.any { it == "." || it == ".." } == true) return@runCatching null
    if (uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port != 9090 || uri.userInfo != null ||
        (uri.path != "/ui" && !uri.path.startsWith("/ui/"))) return@runCatching null
    // These yacd parameters can redirect the backend and expose its credential.
    val query = uri.query?.split('&')?.filterNot {
        it.substringBefore('=') in setOf("hostname", "port", "secret")
    }?.joinToString("&")?.takeIf { it.isNotEmpty() }
    URI("http", null, "127.0.0.1", 9090, uri.path, query, uri.fragment).toASCIIString()
}.getOrNull()

internal fun panelBootstrap(secret: String): String = """
    <html><head><script>
    const key = 'yacd.metacubex.one';
    let cfg = {};
    try { cfg = JSON.parse(localStorage.getItem(key)) || {}; } catch (_) {}
    const configs = Array.isArray(cfg.clashAPIConfigs) ? cfg.clashAPIConfigs : [];
    let index = configs.findIndex(c => c && c.baseURL === 'http://127.0.0.1:9090');
    if (index < 0) index = configs.length;
    configs[index] = {baseURL: 'http://127.0.0.1:9090', secret: ${Gson().toJson(secret)}, addedAt: 0};
    cfg.clashAPIConfigs = configs;
    cfg.selectedClashAPIConfigIndex = index;
    localStorage.setItem(key, JSON.stringify(cfg));
    </script></head><body></body></html>
""".trimIndent()

// Fragment必须有一个无参public的构造函数，否则在数据恢复的时候，会报crash

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

    lateinit var mWebView: WebView
    private var panelLoadJob: Job? = null
    private var pendingLocalPanel: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // layout
        toolbar.setTitle(R.string.menu_dashboard)
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.setOnMenuItemClickListener(this)

        val binding = LayoutWebviewBinding.bind(view)

        // webview
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        mWebView = binding.webview
        mWebView.settings.domStorageEnabled = true
        mWebView.settings.javaScriptEnabled = true
        mWebView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                WebViewUtil.onReceivedError(view, request, error)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val pending = pendingLocalPanel
                if (pending != null && url == "http://127.0.0.1:9090/ui/") {
                    pendingLocalPanel = null
                    view?.loadUrl(pending)
                }
            }
        }
        loadPanel(DataStore.yacdURL)
    }

    private fun loadPanel(url: String) {
        panelLoadJob?.cancel()
        pendingLocalPanel = null
        mWebView.stopLoading()
        val local = localPanelUrl(url)
        if (local == null) {
            mWebView.loadUrl(url)
            return
        }
        panelLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DataStore.ensureClashApiSecret() } }
            val secret = result.getOrElse {
                android.widget.Toast.makeText(requireContext(), R.string.settings_flush_failed, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            pendingLocalPanel = local
            // Bootstrap the bundled yacd storage at its own origin. No token is
            // placed in a request URL or injected into a user-supplied remote page.
            mWebView.loadDataWithBaseURL(
                "http://127.0.0.1:9090/ui/", panelBootstrap(secret), "text/html", "UTF-8", null,
            )
        }
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_set_url -> {
                val view = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(DataStore.yacdURL)
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.set_panel_url)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        DataStore.yacdURL = view.text.toString()
                        loadPanel(DataStore.yacdURL)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            R.id.close -> {
                panelLoadJob?.cancel()
                pendingLocalPanel = null
                mWebView.stopLoading()
                mWebView.loadUrl("about:blank")
            }
        }
        return true
    }

    override fun onDestroyView() {
        panelLoadJob?.cancel()
        pendingLocalPanel = null
        if (::mWebView.isInitialized) {
            mWebView.onPause()
            mWebView.stopLoading()
            mWebView.loadUrl("about:blank")
            mWebView.removeAllViews()
            mWebView.destroy()
        }
        super.onDestroyView()
    }
}
