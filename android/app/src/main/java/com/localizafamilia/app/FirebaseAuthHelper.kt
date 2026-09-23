package com.localizafamilia.app

import android.content.SharedPreferences
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object FirebaseAuthHelper {

    private const val URL_SIGNUP = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=%s"
    private const val URL_TOKEN = "https://securetoken.googleapis.com/v1/token?key=%s"
    private const val FALLBACK_API_KEY = "AIzaSyBYIgyojy12YtAGiQG2T5XGb2EsgIbR_Rk"

    private const val PREF_REFRESH = "fbRefreshToken"
    private const val PREF_UID = "fbUid"
    private const val PREF_IDTOKEN = "fbIdToken"
    private const val PREF_EXP = "fbExp"

    private val lock = Any()

    fun apiKey(prefs: SharedPreferences): String =
        prefs.getString("fbApiKey", "")?.takeIf { it.isNotEmpty() } ?: FALLBACK_API_KEY

    fun token(prefs: SharedPreferences): String {
        synchronized(lock) {
            val agora = System.currentTimeMillis()
            val idToken = prefs.getString(PREF_IDTOKEN, "") ?: ""
            if (idToken.isNotEmpty() && prefs.getLong(PREF_EXP, 0L) - agora > 60_000) {
                return idToken
            }

            val refresh = prefs.getString(PREF_REFRESH, "") ?: ""
            if (refresh.isNotEmpty()) {
                try {
                    val json = post(String.format(URL_TOKEN, apiKey(prefs)), JSONObject()
                        .put("grant_type", "refresh_token")
                        .put("refresh_token", refresh).toString())
                    if (json.has("id_token")) {
                        salvar(prefs, json, "id_token", "user_id", "expires_in")
                        return prefs.getString(PREF_IDTOKEN, "") ?: ""
                    }
                    prefs.edit().remove(PREF_REFRESH).apply()
                } catch (_: Exception) {
                }
            }

            val json = post(String.format(URL_SIGNUP, apiKey(prefs)), JSONObject().put("returnSecureToken", true).toString())
            if (!json.has("idToken")) {
                throw IOException("Falha ao criar conta anônima")
            }
            salvar(prefs, json, "idToken", "localId", "expiresIn")
            return prefs.getString(PREF_IDTOKEN, "") ?: throw IOException("Sem token")
        }
    }

    fun uid(prefs: SharedPreferences): String = prefs.getString(PREF_UID, "") ?: ""

    private fun salvar(prefs: SharedPreferences, json: JSONObject, idKey: String, uidKey: String, expKey: String) {
        val exp = json.optString(expKey).toLongOrNull()
            ?: json.optLong(expKey, 0L)
        val idToken = json.optString(idKey, "")
        if (idToken.isEmpty()) return
        prefs.edit()
            .putString(PREF_IDTOKEN, idToken)
            .putLong(PREF_EXP, System.currentTimeMillis() + exp * 1000L)
            .apply()
        val uid = json.optString(uidKey, "")
        if (uid.isNotEmpty()) {
            prefs.edit().putString(PREF_UID, uid).apply()
        }
        val refresh = json.optString("refreshToken", json.optString("refresh_token", ""))
        if (refresh.isNotEmpty()) {
            prefs.edit().putString(PREF_REFRESH, refresh).apply()
        }
    }

    private fun post(url: String, corpo: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code < 200 || code >= 300) {
                val erro = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IOException("HTTP $code $erro")
            }
            val texto = conn.inputStream.bufferedReader().use { it.readText() }
            if (texto.isBlank()) throw IOException("Resposta vazia")
            return JSONObject(texto)
        } finally {
            conn.disconnect()
        }
    }
}