package com.localizafamilia.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private var voz: VozHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("localizafamilia", MODE_PRIVATE)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.addJavascriptInterface(Bridge(), "AndroidMic")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injetarVoz()
                sincronizarNome()
            }
        }
        webView.loadUrl(APP_URL)

        pedirPermissoes()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (temLocalizacaoPermitida() && prefs.getString("nomeEnc", "")?.isNotEmpty() == true) {
            iniciarServico()
        }
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        voz?.parar()
        webView.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun injetarVoz() {
        webView.evaluateJavascript(
            "window.nativeMicResult=function(t){try{window.lfMic(false);window.lfvoz(t);}catch(e){}};",
            null
        )
    }

    private fun sincronizarNome() {
        webView.evaluateJavascript(
            "(function(){try{return localStorage.getItem('nomeFamiliar')||''}catch(e){return ''}})()"
        ) { nome ->
            if (nome != null && nome != "null" && nome.isNotEmpty()) {
                val limpo = nome.removeSurrounding("\"").trim()
                if (limpo.isNotEmpty()) {
                    prefs.edit()
                        .putString("nome", limpo)
                        .putString("nomeEnc", limpo.replace(Regex("[^a-zA-Z0-9-_]"), "_"))
                        .apply()
                    if (temLocalizacaoPermitida()) iniciarServico()
                }
            }
        }
    }

    private fun temLocalizacaoPermitida(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun pedirPermissoes() {
        val precisam = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) precisam.add(Manifest.permission.POST_NOTIFICATIONS)
        val falta = precisam.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (falta.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, falta.toTypedArray(), REQ_PERMISSOES)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSOES && temLocalizacaoPermitida()) {
            if (prefs.getString("nomeEnc", "")?.isNotEmpty() == true) iniciarServico()
        }
    }

    private fun iniciarServico() {
        val intent = Intent(this, LocalizacaoServico::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun ouvir() {
            runOnUiThread {
                if (voz == null) {
                    voz = VozHelper(
                        context = this@MainActivity,
                        onResult = { texto ->
                            runOnUiThread {
                                val t = texto
                                    .replace("\\", "\\\\")
                                    .replace("'", "\\'")
                                    .replace("\"", "\\\"")
                                webView.evaluateJavascript("window.nativeMicResult('$t');", null)
                            }
                        },
                        onError = {
                            runOnUiThread {
                                webView.evaluateJavascript("window.lfMic(false);", null)
                            }
                        }
                    )
                }
                voz?.iniciar()
            }
        }

        @JavascriptInterface
        fun parar() {
            runOnUiThread { voz?.parar() }
        }
    }

    companion object {
        private const val APP_URL = "https://cleberleonheart-maker.github.io/localizafamilia/"
        private const val REQ_PERMISSOES = 100
    }
}