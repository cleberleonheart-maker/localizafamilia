package com.localizafamilia.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
                    notificar(nova, urlApk)
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

    private fun notificar(nova: Int, urlApk: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CANAL, "Atualizações", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val abrir = Intent(Intent.ACTION_VIEW, Uri.parse(urlApk))
        val pendente = PendingIntent.getActivity(
            ctx, 0, abrir,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val not = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Nova versão disponível")
            .setContentText("Atualize o Localiza Família (build $nova) — toque para baixar")
            .setContentIntent(pendente)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIFICACAO_ID, not)
    }

    companion object {
        private const val CANAL = "canal_atualizacao"
        private const val NOTIFICACAO_ID = 2
        private const val ULTIMA_CHECAGEM = "ultimaChecagem"
        private const val INTERVALO_MS = 30 * 60 * 1000L
        private const val VERSION_URL =
            "https://raw.githubusercontent.com/cleberleonheart-maker/localizafamilia/main/version"
    }
}