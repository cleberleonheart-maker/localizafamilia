package com.localizafamilia.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private var voz: VozHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("localizafamilia", MODE_PRIVATE)
        registrar("inicio")
        try {
            super.onCreate(savedInstanceState)
            registrar("super_create")
            registrarErros()
            setContentView(R.layout.activity_main)
            registrar("set_content")

            webView = findViewById(R.id.webView)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.databaseEnabled = true
            webView.settings.mediaPlaybackRequiresUserGesture = false
            webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            webView.settings.setGeolocationEnabled(true)
            webView.settings.setGeolocationDatabasePath(applicationContext.filesDir.path)
            if (Build.VERSION.SDK_INT >= 26) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
            }
            webView.addJavascriptInterface(Bridge(), "AndroidMic")
            webView.webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    callback?.invoke(origin, true, false)
                }
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    registrar("pagina_comecou")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    registrar("pagina_pronta")
                    injetarVoz()
                    sincronizarNome()
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    val fatal = detail?.didCrash() == true
                    registrar("render_morreu_fatal=$fatal")
                    prefs.edit().putLong("renderCrash", System.currentTimeMillis()).apply()
                    runOnUiThread {
                        try {
                            reiniciarTela()
                        } catch (_: Exception) {
                            finishAndRemoveTask()
                        }
                    }
                    return true
                }
            }
            webView.loadUrl(APP_URL)
            registrar("pagina_pedida")

            pedirPermissoes()
            AtualizarHelper(this).verificar(force = true)
            registrar("fim_create")
        } catch (t: Throwable) {
            registrar("ERRO_create:" + trace(t))
        }
    }

    override fun onResume() {
        registrar("resume")
        try {
            super.onResume()
            webView.onResume()
            if (temLocalizacaoPermitida() && prefs.getString("nomeEnc", "")?.isNotEmpty() == true) {
                iniciarServico()
            }
            registrar("resume_ok")
        } catch (t: Throwable) {
            registrar("ERRO_resume:" + trace(t))
        }
    }

    override fun onPause() {
        try {
            webView.onPause()
        } catch (_: Throwable) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        try {
            voz?.parar()
            webView.destroy()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        try {
            super.onSaveInstanceState(outState)
            webView.saveState(outState)
        } catch (_: Throwable) {
        }
    }

    private fun reiniciarTela() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
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

    private fun registrarErros() {
        val padrao = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                getSharedPreferences("localizafamilia", MODE_PRIVATE)
                    .edit()
                    .putString("ultimoErro", Log.getStackTraceString(throwable))
                    .apply()
            } catch (_: Exception) {
            }
            padrao?.uncaughtException(Thread.currentThread(), throwable)
        }
    }

    private fun registrar(passo: String) {
        try {
            getSharedPreferences("localizafamilia", MODE_PRIVATE)
                .edit()
                .putLong("init_" + passo, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun trace(t: Throwable): String {
        return try {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sw.toString()
        } catch (_: Throwable) {
            t.javaClass.simpleName
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
        try {
            val intent = Intent(this, LocalizacaoServico::class.java)
            ContextCompat.startForegroundService(this, intent)
            registrar("servico_iniciado")
        } catch (t: Throwable) {
            registrar("ERRO_servico:" + trace(t))
        }
    }

    private fun diagAtual(): String {
        val sb = StringBuilder()
        val e = prefs.getString("ultimoErro", "") ?: ""
        if (e.isNotEmpty()) sb.append("ultimoErro: ").append(e.take(400)).append('\n')
        val rc = prefs.getLong("renderCrash", 0L)
        if (rc > 0) sb.append("renderCrashed em: ").append(rc).append('\n')
        val passos = prefs.all
            .filterKeys { it.startsWith("init_") }
            .toSortedMap()
        for ((k, v) in passos) sb.append(k).append('=').append(v).append('\n')
        return sb.toString().ifEmpty { "sem dados de diagnóstico ainda" }
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

        @JavascriptInterface
        fun ultimoErro(): String = prefs.getString("ultimoErro", "") ?: ""

        @JavascriptInterface
        fun diag(): String = diagAtual()

        @JavascriptInterface
        fun abrirLink(url: String) {
            if (!url.startsWith("http")) return
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        private const val APP_URL = "https://cleberleonheart-maker.github.io/localizafamilia/"
        private const val REQ_PERMISSOES = 100
    }
}