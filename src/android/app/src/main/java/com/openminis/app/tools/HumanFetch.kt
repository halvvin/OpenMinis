package com.openminis.app.tools

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [T-human-fetch] Load a page in a REAL WebView to pass JS anti-bot walls
 * (Cloudflare "Just a moment…", generic JS challenges) that a plain
 * HttpURLConnection can never clear. Mirrors Vega-Agent's HumanFetch.
 *
 * The WebView runs the challenge's JavaScript like a browser, then we harvest
 * the rendered HTML. Cookies from the solved session are kept so a follow-up
 * download_file / web_extract can replay them. Returns null when the page
 * needs an interactive CAPTCHA (only a person can clear it) or WebView is
 * unavailable.
 */
object HumanFetch {

    @Volatile
    private var appContext: Context? = null

    /** Wire up an application Context once. */
    fun init(ctx: Context?) {
        if (appContext == null && ctx != null) {
            appContext = ctx.applicationContext
        }
    }

    fun available(): Boolean = appContext != null

    /** Cookies cached for a host (to replay on plain fetches). */
    fun cookiesFor(host: String?): String? {
        if (host.isNullOrEmpty() || appContext == null) return null
        return runCatching {
            CookieManager.getInstance().getCookie("https://$host/")
        }.getOrNull()
    }

    class Result(
        val html: String,
        val finalUrl: String?,
        val blocked: Boolean,
        val interactiveCaptcha: Boolean,
    )

    /**
     * Load [url] in a WebView on the main thread, let JS settle, return the
     * final HTML. Blocks the caller (worker thread) up to [timeoutMs].
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun fetch(url: String, timeoutMs: Long = 25000L): Result? {
        val ctx = appContext ?: return null
        val latch = CountDownLatch(1)
        val htmlRef = AtomicReference<String?>(null)
        val finalUrlRef = AtomicReference<String?>(null)
        val blockedRef = AtomicReference(false)
        val captchaRef = AtomicReference(false)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try {
                val webView = WebView(ctx)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Let JS challenges run a moment, then harvest.
                        view?.postDelayed({
                            val html = runCatching {
                                view.evaluateJavascript(
                                    "document.documentElement.outerHTML",
                                ) { value -> htmlRef.set(value?.trim('"')?.replace("\\\"", "\"") ?: "") }
                            }
                            // evaluateJavascript is async; poll briefly.
                            var tries = 0
                            while (htmlRef.get() == null && tries < 30) {
                                try { Thread.sleep(100) } catch (_: Exception) {}
                                tries++
                            }
                            finalUrlRef.set(view?.url)
                            val h = htmlRef.get() ?: ""
                            // Detect challenge markers.
                            if (h.contains("cf-challenge") || h.contains("challenge-form") ||
                                h.contains("Just a moment") || h.contains("cf-browser-verification")
                            ) {
                                blockedRef.set(true)
                            }
                            if (h.contains("recaptcha") || h.contains("hcaptcha") || h.contains("turnstile")) {
                                captchaRef.set(true)
                            }
                            latch.countDown()
                            view.destroy()
                        }, 2500)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            blockedRef.set(true)
                            latch.countDown()
                            view?.destroy()
                        }
                    }
                }
                webView.loadUrl(url)
                // Safety: never hang forever.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (latch.count > 0) { latch.countDown(); viewDestroy(webView) }
                }, timeoutMs)
            } catch (_: Exception) {
                latch.countDown()
            }
        }

        val finished = try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) { false }
        if (!finished || htmlRef.get() == null) return null
        return Result(
            html = htmlRef.get() ?: "",
            finalUrl = finalUrlRef.get(),
            blocked = blockedRef.get(),
            interactiveCaptcha = captchaRef.get(),
        )
    }

    private fun viewDestroy(view: WebView) {
        runCatching { android.os.Handler(android.os.Looper.getMainLooper()).post { view.destroy() } }
    }
}
