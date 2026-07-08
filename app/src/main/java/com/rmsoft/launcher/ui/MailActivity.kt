package com.rmsoft.launcher.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.rmsoft.launcher.R

/**
 * RMSoft Mail — a full-screen WebView wrapper around the webmail at [MAIL_URL], shown as its own
 * launchable app (a home-screen icon). Gives users a native-feeling mailbox without a full browser;
 * the website is the source of truth, so the app never needs updating when the mail UI changes.
 */
class MailActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mail)

        val progress = findViewById<ProgressBar>(R.id.mailProgress)
        val offline = findViewById<View>(R.id.mailOffline)
        web = findViewById(R.id.mailWebView)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                offline.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                // Only the main-frame failure means "can't load the mailbox" — ignore sub-resource errors.
                if (request?.isForMainFrame == true) {
                    progress.visibility = View.GONE
                    offline.visibility = View.VISIBLE
                }
            }
        }

        findViewById<Button>(R.id.mailRetry).setOnClickListener {
            offline.visibility = View.GONE
            web.loadUrl(MAIL_URL)
        }

        // Back navigates the mailbox history instead of leaving the app on the first tap.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl(MAIL_URL)
    }

    companion object {
        private const val MAIL_URL = "https://mail.rmsoft.rw/mail"
    }
}
