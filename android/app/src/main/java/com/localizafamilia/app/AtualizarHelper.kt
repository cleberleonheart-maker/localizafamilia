package com.localizafamilia.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class AtualizarHelper(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("localizafamilia", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun verificar(force: Boolean = false) {
        val ultimo = prefs.getLong(ULTIMA_CHECAGEM, 0L)
        val agora = System.currentTimeMillis()
        if (!force && agora - ultimo < INTERVALO_MS) return
        prefs.edit().putLong(ULTIMA_CHECAGEM, agora).apply()

        scope.launch {
            try {
                val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val linhas = conn.inputStream.bufferedReader().use { it.readLines() }
                conn.disconnect()

                val cab = linhas.firstOrNull() ?: return@launch
                val partes = cab.split("|")
                if (partes.size < 2) return@launch
                val nova = partes[0].trim().toIntOrNull() ?: return@launch
                if (nova > versaoAtual()) {
                    val urlApk = partes.getOrNull(2)?.trim() ?: return@launch
                    baixarEInstalar(nova, urlApk)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun versaoAtual(): Int {
        return try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        } catch (_: Exception) {
            0
        }
    }

    private fun baixarEInstalar(nova: Int, urlApk: String) {
        scope.launch {
            try {
                val conn = URL(urlApk).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                val total = conn.contentLength
                val arquivo = File(ctx.cacheDir, "atualizacao-$nova.apk")
                val escrito = conn.inputStream.use { input ->
                    arquivo.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        var baixado = 0L
                        var ultimoProgresso = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            baixado += n
                            if (total > 0) {
                                val p = ((baixado * 100) / total).toInt()
                                if (p != ultimoProgresso) {
                                    ultimoProgresso = p
                                    notificarBaixando(nova, p)
                                }
                            }
                        }
                        baixado
                    }
                }
                conn.disconnect()
                if (escrito > 0 && arquivo.length() == total.toLong()) {
                    instalar(nova, arquivo)
                } else {
                    notificarManual(nova, urlApk)
                }
            } catch (_: Exception) {
                notificarManual(nova, urlApk)
            }
        }
    }

    private fun instalar(nova: Int, arquivo: File) {
        try {
            val instalador = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            params.setAppIcon(android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.ic_launcher))
            params.setAppLabel(ctx.getString(R.string.app_name))
            val sid = instalador.createSession(params)
            instalador.openSession(sid).use { sessao ->
                sessao.openWrite("pkg", 0, arquivo.length()).use { saida ->
                    (saida as FileOutputStream).use { fs ->
                        arquivo.inputStream().use { it.copyTo(fs) }
                        sessao.fsync(fs)
                    }
                }
                val intencao = PendingIntent.getBroadcast(
                    ctx,
                    REQ_INSTALAR,
                    Intent(ACCAO_INSTALADO)
                        .setPackage(ctx.packageName)
                        .putExtra("extra_nova_build", nova),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                sessao.commit(intencao.intentSender)
            }
        } catch (e: SecurityException) {
            pedirPermissaoInstalarNovos()
            notificarManual(nova, instalarUrl(nova))
        } catch (_: Exception) {
            notificarManual(nova, instalarUrl(nova))
        }
    }

    private fun pedirPermissaoInstalarNovos() {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun instalarUrl(nova: Int): String =
        "https://github.com/cleberleonheart-maker/localizafamilia/releases/download/nativo-build-$nova/app-release.apk"

    private fun notificarBaixando(nova: Int, progresso: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        garantirCanal(nm)
        val not = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Baixando atualização (build $nova)")
            .setContentText("$progresso%")
            .setProgress(100, progresso, false)
            .setOngoing(true)
            .build()
        nm.notify(NOTIFICACAO_ID, not)
    }

    private fun notificarManual(nova: Int, urlApk: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        garantirCanal(nm)
        val abrir = Intent(Intent.ACTION_VIEW, Uri.parse(urlApk))
        val pendente = PendingIntent.getActivity(
            ctx, 0, abrir,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val not = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Nova versão disponível (build $nova)")
            .setContentText("Toque para baixar e instalar")
            .setContentIntent(pendente)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIFICACAO_ID, not)
    }

    private fun garantirCanal(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CANAL, "Atualizações", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    class InstaladorReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            val nova = intent.getIntExtra("extra_nova_build", 0)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(confirm) }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(NOTIFICACAO_ID)
                }
                else -> {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(NOTIFICACAO_ID)
                }
            }
        }
    }

    companion object {
        private const val CANAL = "canal_atualizacao"
        private const val NOTIFICACAO_ID = 2
        private const val ULTIMA_CHECAGEM = "ultimaChecagem"
        private const val INTERVALO_MS = 30 * 60 * 1000L
        private const val REQ_INSTALAR = 3
        const val ACCAO_INSTALADO = "com.localizafamilia.app.INSTALADO"
        private const val VERSION_URL =
            "https://raw.githubusercontent.com/cleberleonheart-maker/localizafamilia/main/version"
    }
}