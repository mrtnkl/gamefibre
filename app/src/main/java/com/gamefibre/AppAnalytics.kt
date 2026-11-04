package com.gamefibre

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** Runs the web tag separately from account sign-in and the native interface. */
internal class AppAnalytics(private val context: Context) {
    private var webView: WebView? = null
    private var closed = false

    @SuppressLint("SetJavaScriptEnabled") // Only the bundled analytics document runs here; no native bridge.
    fun start() {
        if (closed || webView != null) return
        try {
            val html = context.assets.open("app-analytics.html").bufferedReader().use { it.readText() }
            val view = WebView(context)
            webView = view
            view.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            // An HTTPS origin gives the web tag normal first-party cookie storage.
            // The document is bundled: this does not load the website or require publishing it.
            view.loadDataWithBaseURL("https://gamefibre.com/app/", html, "text/html", "UTF-8", null)
        } catch (error: Exception) {
            // Analytics must never prevent use of the app, including without a WebView provider.
            Log.w("AppAnalytics", "Could not start web analytics", error)
            close()
        }
    }

    fun close() {
        closed = true
        webView?.destroy()
        webView = null
    }
}
