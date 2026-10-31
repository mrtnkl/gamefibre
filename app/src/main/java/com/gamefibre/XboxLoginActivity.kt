package com.gamefibre

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
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
import com.gamefibre.stores.xboxOAuthCode
import com.gamefibre.stores.XboxSignInException
import java.util.UUID

/** Microsoft's sign-in page returns an exact, state-bound callback. No passwords reach app code. */
class XboxLoginActivity : AppCompatActivity() {
    private var web: WebView? = null
    private var completed = false
    private lateinit var state: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        state = savedInstanceState?.getString("oauthState") ?: UUID.randomUUID().toString()
        val ui = NativeUi(this)
        val root = ui.column().apply { setBackgroundColor(ui.background) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        val heading = ui.row().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(8), ui.dp(8)) }
        heading.addView(ui.text("Sign in to Xbox", 22, medium = true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(ui.iconButton(R.drawable.ic_x, "Close Xbox sign-in") { finish() })
        ui.add(root, heading)
        val notice = ui.text("", 14, ui.error).apply {
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)); visibility = View.GONE
        }
        ui.add(root, notice)
        fun receive(uri: Uri): Boolean {
            try {
                val code = xboxOAuthCode(uri.toString(), state) ?: return false
                if (completed) return true
                StoreAccounts.get(this).signIn(GameStore.XBOX, code)
                completed = true
                finish()
            } catch (e: IllegalArgumentException) {
                notice.text = if (e is XboxSignInException) e.message else "Microsoft returned an unusable sign-in response. Close this window and try again."
                notice.visibility = View.VISIBLE
                // Static reason codes only: never log a callback URL, authorization code, token or username.
                android.util.Log.i("XboxSignIn", "Callback rejected: ${(e as? XboxSignInException)?.reason ?: "invalid_response"}")
            }
            return true
        }
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    if (receive(request.url)) return true
                    val uri = request.url
                    val allowed = uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
                        listOf("live.com", "microsoft.com", "microsoftonline.com", "msauth.net", "msftauth.net", "xbox.com")
                            .any { uri.host == it || uri.host.orEmpty().endsWith(".$it") }
                    if (!allowed) {
                        notice.text = "This page cannot be opened in the Microsoft sign-in window."
                        notice.visibility = View.VISIBLE
                    }
                    return !allowed
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (receive(Uri.parse(url))) view.stopLoading()
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    notice.text = "Microsoft's sign-in page could not be reached. Check the phone's internet connection, then close this window and try again."
                    notice.visibility = View.VISIBLE
                    android.util.Log.i("XboxSignIn", "Sign-in page load failed: ${error.errorCode}")
                }
            }
        }
        web = view
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (savedInstanceState == null || view.restoreState(savedInstanceState) == null) {
            view.loadUrl(Uri.parse(GameStore.XBOX.loginUrl).buildUpon().appendQueryParameter("state", state).build().toString())
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (view.canGoBack()) view.goBack() else finish() }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("oauthState", state)
        web?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        web?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
        web = null
        super.onDestroy()
    }
}
