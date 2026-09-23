package com.localizafamilia.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    if (isFinishing) {
                        result?.cancel()
                        return true
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(view?.title ?: url ?: "Localiza Família")
                        .setMessage(message)
                        .setPositiveButton("OK") { _, _ -> result?.confirm() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?
                ): Boolean {
                    if (isFinishing) {
                        result?.cancel()
                        return true
                    }
                    val input = EditText(this@MainActivity)
                    if (!defaultValue.isNullOrEmpty()) input.setText(defaultValue)
                    val textoMsg = message ?: ""
                    if (textoMsg.contains("PIN") || textoMsg.contains("SENHA") || textoMsg.contains("senha")) {
                        input.inputType =
                            android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    }
                    val dlg = AlertDialog.Builder(this@MainActivity)
                        .setTitle(message)
                        .setView(input)
                        .setPositiveButton("OK") { _, _ -> result?.confirm(input.text.toString()) }
                        .setNegativeButton("Cancelar") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }
                        .create()
                    dlg.show()
                    return true
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
            enviarDiagFirebase("aberta", diagAtual().take(2000))
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
                val stack = Log.getStackTraceString(throwable)
                getSharedPreferences("localizafamilia", MODE_PRIVATE)
                    .edit()
                    .putString("ultimoErro", stack)
                    .apply()
                enviarDiagFirebase("excecao", stack.take(2000))
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

    private fun enviarDiagFirebase(tipo: String, detalhe: String) {
        Thread {
            try {
                val chave = prefs.getString("nomeEnc", "") ?: "sem_nome"
                val url = URL("https://localizafamilia-df735-default-rtdb.firebaseio.com/_diag/$chave.json")
                val corpo =
                    "{\"$tipo\":${org.json.JSONObject.quote(detalhe)},\"data\":${System.currentTimeMillis()}}"
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
                conn.inputStream.close()
                conn.disconnect()
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun hashPin(pin: String): String {
        return try {
            var salt = prefs.getString("pinSalt", "") ?: ""
            if (salt.isEmpty()) {
                salt = java.util.UUID.randomUUID().toString()
                prefs.edit().putString("pinSalt", salt).apply()
            }
            val msg = pin + salt
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(msg.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            pin
        }
    }

    fun temPin(): Boolean = prefs.getString("pinPais", "")?.isNotEmpty() == true

    fun verificarPin(pin: String): Boolean {
        val salvo = prefs.getString("pinPais", "")
        return !salvo.isNullOrEmpty() && salvo == hashPin(pin)
    }

    fun definirPin(pin: String): Boolean {
        val limpo = pin.trim()
        prefs.edit()
            .putString("pinPais", if (limpo.isEmpty()) "" else hashPin(limpo))
            .apply()
        return limpo.isNotEmpty()
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
        fun temPin(): Boolean = this@MainActivity.temPin()

        @JavascriptInterface
        fun verificarPin(pin: String) = this@MainActivity.verificarPin(pin)

        @JavascriptInterface
        fun definirPin(pin: String): Boolean = this@MainActivity.definirPin(pin)

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