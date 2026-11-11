package com.plaintext.audible.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener

object AbbWebViewFetcher {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchHtml(url: String, timeoutMs: Long = 14_000L): String? {
        val ctx = appContext ?: return null
        return withContext(Dispatchers.Main) {
            var webView: WebView? = null
            try {
                val wv = WebView(ctx)
                webView = wv
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(wv, true)

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                }

                var pageFinished = false
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        pageFinished = true
                    }
                }

                wv.loadUrl(url)

                val resultHtml = withTimeoutOrNull(timeoutMs) {
                    var lastHtml = ""
                    var pollsAfterFinish = 0
                    while (true) {
                        delay(450L)
                        if (pageFinished) {
                            pollsAfterFinish++
                            val html = evaluateOuterHtml(wv)
                            if (html.isNotBlank()) {
                                lastHtml = html
                                val isCloudflareChallenge =
                                    html.contains("Just a moment...", ignoreCase = true) ||
                                        html.contains("cf-browser-verification", ignoreCase = true) ||
                                        html.contains("Checking your browser", ignoreCase = true) ||
                                        (html.contains("challenge-platform", ignoreCase = true) && !html.contains("postTitle", ignoreCase = true))
                                if (!isCloudflareChallenge && (
                                        html.contains("postTitle", ignoreCase = true) ||
                                            html.contains("Info Hash", ignoreCase = true) ||
                                            html.contains("audio-books", ignoreCase = true) ||
                                            html.contains("/abss/", ignoreCase = true) ||
                                            pollsAfterFinish >= 4
                                        )
                                ) {
                                    return@withTimeoutOrNull html
                                }
                            }
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    lastHtml
                }
                cookieManager.flush()
                resultHtml?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            } finally {
                runCatching {
                    webView?.stopLoading()
                    webView?.destroy()
                }
            }
        }
    }

    private suspend fun evaluateOuterHtml(webView: WebView): String =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript("(function(){return document.documentElement ? document.documentElement.outerHTML : '';})();") { rawJson ->
                val decoded = runCatching {
                    if (rawJson.isNullOrBlank() || rawJson == "null") ""
                    else JSONTokener(rawJson).nextValue()?.toString().orEmpty()
                }.getOrDefault("")
                if (cont.isActive) {
                    cont.resume(decoded, null)
                }
            }
        }
}
