package com.localizafamilia.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LocalizacaoServico : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy { getSharedPreferences("localizafamilia", MODE_PRIVATE) }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var ultimoEnvio = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::enviarParaFirebase)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            criarCanal()
            startForeground(NOTIFICACAO_ID, notificacao())
            iniciarAtualizacoes()
            scope.launch {
                while (isActive) {
                    try {
                        verificarAvisosFamilia()
                    } catch (_: Exception) {
                    }
                    delay(INTERVALO_VERIFICACAO_FAMILIA_MS)
                }
            }
        } catch (_: Exception) {
        }
        return START_STICKY
    }

    private fun iniciarAtualizacoes() {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!ok) return

        val bateria = bateria()
        val intervalo = if (bateria >= 0 && bateria < LIMITE_BATERIA_PERCENT.toInt()) INTERVALO_MS_ECONOMIA else INTERVALO_MS

        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            intervalo
        )
            .setMinUpdateIntervalMillis(if (bateria >= 0 && bateria < LIMITE_BATERIA_PERCENT.toInt()) INTERVALO_RAPIDO_ECONOMIA else INTERVALO_RAPIDO_MS)
            .setMaxUpdateDelayMillis(if (bateria >= 0 && bateria < LIMITE_BATERIA_PERCENT.toInt()) INTERVALO_MAXIMO_ECONOMIA else INTERVALO_MAXIMO_MS)
            .build()

        try {
            fused.requestLocationUpdates(request, callback, null)
            fused.lastLocation.addOnSuccessListener { loc: Location? ->
                if (loc != null) enviarParaFirebase(loc)
            }
        } catch (e: Exception) {
            android.util.Log.w("LF", "Falha ao iniciar GPS", e)
        }
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }

    private fun enviarParaFirebase(local: Location) {
        val agora = System.currentTimeMillis()
        if (agora - ultimoEnvio < INTERVALO_MINIMO_SERVICO_MS) return
        ultimoEnvio = agora

        val nome = prefs.getString("nome", "") ?: ""
        val enc = prefs.getString("nomeEnc", "") ?: ""
        if (enc.isEmpty()) return

        val bateria = bateria()
        scope.launch {
            try {
                val corpo = buildString {
                    append("{\"nome\":")
                    append(json(nome))
                    append(",\"lat\":${local.latitude},\"lng\":${local.longitude}")
                    if (bateria >= 0) append(",\"bateria\":$bateria")
                    append(",\"atualizado\":{\".sv\":\"timestamp\"}}")
                }
                val conn = URL("$FIREBASE_DB/familia/$enc.json").openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
                conn.inputStream.close()
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun json(valor: String): String {
        val esc = valor.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$esc\""
    }

    private fun bateria(): Int {
        return if (Build.VERSION.SDK_INT >= 21) {
            val manager = getSystemService(BATTERY_SERVICE) as BatteryManager
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else -1
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val canal = NotificationChannel(
                CANAL_ID,
                "Rastreio da família",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(canal)
            val canalAviso = NotificationChannel(
                CANAL_AVISO_ID,
                "Avisos importantes",
                NotificationManager.IMPORTANCE_HIGH
            )
            canalAviso.description = "Avisos de família parada e bateria fraca"
            nm.createNotificationChannel(canalAviso)
        }
    }

    private fun notificacoesPermitidas(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    private fun verificarAvisosFamilia() {
        if (!notificacoesPermitidas()) return
        val meuEnc = prefs.getString("nomeEnc", "") ?: ""
        val corpo = try {
            val conn = URL("$FIREBASE_DB/familia.json?orderBy=%22%24key%22&limitToLast=100")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val texto = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            texto
        } catch (_: Exception) {
            return
        }
        if (corpo.isBlank() || corpo == "null") return

        val agora = System.currentTimeMillis()
        try {
            val raiz = JSONObject(corpo)
            val chaves = raiz.keys()
            while (chaves.hasNext()) {
                val chave = chaves.next()
                if (chave == meuEnc) continue
                val m = raiz.optJSONObject(chave) ?: continue
                if (m.optBoolean("invisivel", false)) continue

                val atualizado = m.optLong("atualizado", 0L)
                val nome = m.optString("nome", chave)
                if (atualizado > 0 && agora - atualizado > LIMITE_PARADA_MS) {
                    avisarSeNecessario("avisoParada_" + chave, COOLDOWN_PARADA_MS) {
                        "⚠️ $nome parou de enviar posição (há ${((agora - atualizado) / 60000).coerceAtLeast(1)} min)"
                    }
                }

                val bateria = m.opt("bateria").let { b ->
                    when (b) {
                        is Number -> b.toDouble()
                        is String -> b.toDoubleOrNull() ?: -1.0
                        else -> -1.0
                    }
                }
                if (bateria >= 0 && bateria < LIMITE_BATERIA_PERCENT) {
                    avisarSeNecessario("avisoBateria_" + chave, COOLDOWN_BATERIA_MS) {
                        "🔋 $nome está com pouca bateria (${bateria.toInt()}%)"
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun avisarSeNecessario(chave: String, cooldownMs: Long, texto: () -> String) {
        val ultimo = prefs.getLong(chave, 0L)
        val agora = System.currentTimeMillis()
        if (agora - ultimo < cooldownMs) return
        prefs.edit().putLong(chave, agora).apply()
        try {
            val abrir = Intent(this, MainActivity::class.java)
            val pendente = PendingIntent.getActivity(
                this, 0, abrir,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, CANAL_AVISO_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Localiza Família")
                .setContentText(texto())
                .setStyle(NotificationCompat.BigTextStyle().bigText(texto()))
                .setContentIntent(pendente)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()
            getSystemService(NotificationManager::class.java).notify(AVISO_ID, notif)
        } catch (_: Exception) {
        }
    }

    private fun notificacao(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendente = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CANAL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_titulo))
            .setContentText(getString(R.string.notif_texto))
            .setOngoing(true)
            .setContentIntent(pendente)
            .build()
    }

    companion object {
        private const val CANAL_ID = "canal_rastreio"
        private const val CANAL_AVISO_ID = "canal_aviso"
        private const val NOTIFICACAO_ID = 1
        private const val AVISO_ID = 3
        private const val FIREBASE_DB = "https://localizafamilia-default-rtdb.firebaseio.com"
        private const val INTERVALO_MS = 20_000L
        private const val INTERVALO_RAPIDO_MS = 10_000L
        private const val INTERVALO_MAXIMO_MS = 40_000L
        private const val INTERVALO_MS_ECONOMIA = 60_000L
        private const val INTERVALO_RAPIDO_ECONOMIA = 30_000L
        private const val INTERVALO_MAXIMO_ECONOMIA = 120_000L
        private const val INTERVALO_MINIMO_SERVICO_MS = 15_000L
        private const val INTERVALO_VERIFICACAO_FAMILIA_MS = 60_000L
        private const val LIMITE_PARADA_MS = 6 * 60 * 1000L
        private const val COOLDOWN_PARADA_MS = 15 * 60 * 1000L
        private const val COOLDOWN_BATERIA_MS = 2 * 60 * 60 * 1000L
        private const val LIMITE_BATERIA_PERCENT = 20.0
    }
}