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
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class LocalizacaoServico : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy { getSharedPreferences("localizafamilia", MODE_PRIVATE) }
    private val fused = LocationServices.getFusedLocationProviderClient(this)
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
        } catch (_: Exception) {
            stopSelf()
        }
        return START_STICKY
    }

    private fun iniciarAtualizacoes() {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!ok) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            INTERVALO_MS
        )
            .setMinUpdateIntervalMillis(INTERVALO_RAPIDO_MS)
            .setMaxUpdateDelayMillis(INTERVALO_MAXIMO_MS)
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
            val canal = NotificationChannel(
                CANAL_ID,
                "Rastreio da família",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
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
        private const val NOTIFICACAO_ID = 1
        private const val FIREBASE_DB = "https://localizafamilia-default-rtdb.firebaseio.com"
        private const val INTERVALO_MS = 20_000L
        private const val INTERVALO_RAPIDO_MS = 10_000L
        private const val INTERVALO_MAXIMO_MS = 40_000L
        private const val INTERVALO_MINIMO_SERVICO_MS = 15_000L
    }
}