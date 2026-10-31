package com.gamefibre

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gamefibre.stores.GameStore
import com.gamefibre.stores.StoreAccounts
import com.gamefibre.stores.StoreLoginResponse
import org.json.JSONTokener

/** GOG returns a URL code; Epic may instead render its code in the final JSON page. */
class StoreLoginActivity : AppCompatActivity() {
    private var web: WebView? = null
    private var completed = false
    private var navigation = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = GameStore.entries.firstOrNull { it.name == intent.getStringExtra("store") }
        if (store == null || store == GameStore.XBOX) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ui = NativeUi(this)
        val root = ui.column().apply { setBackgroundColor(ui.background) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        val heading = ui.row().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(8), ui.dp(8)) }
        heading.addView(ui.text("Sign in to ${store.label}", 22, medium = true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(ui.iconButton(R.drawable.ic_x, "Close ${store.label} sign-in") { finish() })
        ui.add(root, heading)
        val status = ui.text("Complete sign-in below. This window closes automatically when your account is ready to connect.", 13, ui.secondary)
            .apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) }
        ui.add(root, status)
        val retry = ui.button("Try sign-in again") {
            status.text = "Complete sign-in below. This window closes automatically when your account is ready to connect."
            status.setTextColor(ui.secondary)
            web?.stopLoading(); web?.loadUrl(store.loginUrl)
        }.apply { visibility = View.GONE }
        ui.add(root, retry)
        fun showError(message: String) {
            status.text = message; status.setTextColor(ui.error); retry.visibility = View.VISIBLE
        }
        fun connect(code: String) {
            if (completed || isFinishing || isDestroyed) return
            StoreAccounts.get(this).signIn(store, code)
            completed = true
            web?.stopLoading()
            CookieManager.getInstance().flush()
            finish()
        }
        fun receive(address: String): Boolean {
            if (!StoreLoginResponse.isCallback(store, address)) return false
            if (completed) return true
            return try {
                val code = StoreLoginResponse.codeFromUrl(store, address)
                if (code != null) { connect(code); true }
                else if (store == GameStore.GOG) {
                    showError("GOG did not return a sign-in code. Try signing in again."); true
                } else false // Let Epic render its JSON in the authenticated browser session.
            } catch (_: Exception) {
                showError("${store.label} could not complete sign-in. Try signing in again."); true
            }
        }
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    if (receive(request.url.toString())) return true
                    // HTTPS identity-provider redirects stay in this browser, preserving social-login sessions.
                    val uri = request.url
                    val allowed = uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.port == -1
                    if (!allowed) showError("This link cannot be opened in the sign-in window. Use another sign-in method on the store page.")
                    return !allowed
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    navigation++
                    if (receive(url)) view.stopLoading()
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (completed || receive(url) || store != GameStore.EPIC || !StoreLoginResponse.isCallback(store, url) || view.url != url) return
                    val revision = navigation
                    // No JavaScript bridge and no second HTTP request. Read only the verified final page.
                    val address = org.json.JSONObject.quote(url)
                    view.evaluateJavascript("(function(){if(location.href !== $address) return null; return document.body ? document.body.innerText.slice(0, 16385) : null;})()") { result ->
                        if (completed || isFinishing || isDestroyed || revision != navigation || view.url != url) return@evaluateJavascript
                        try {
                            val body = JSONTokener(result).nextValue() as? String ?: ""
                            val code = StoreLoginResponse.epicPageCode(url, body)
                            if (code != null) connect(code)
                            else showError("Epic did not return a sign-in code. Try signing in again.")
                        } catch (_: Exception) {
                            showError("Epic could not complete sign-in. Check your account on Epic Games, then try again.")
                        }
                    }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame && !completed) showError("The store could not load the sign-in page. Try signing in again.")
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && !completed) showError("The sign-in page could not be reached. Check your connection and try again.")
                }
            }
        }
        web = view
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (savedInstanceState == null || view.restoreState(savedInstanceState) == null) view.loadUrl(store.loginUrl)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (view.canGoBack()) view.goBack() else finish() }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val store = GameStore.entries.firstOrNull { it.name == intent.getStringExtra("store") }
        web?.let { view ->
            if (!completed && store != null && !StoreLoginResponse.isCallback(store, view.url.orEmpty())) view.saveState(outState)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        navigation++
        web?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
        web = null
        super.onDestroy()
    }
}
