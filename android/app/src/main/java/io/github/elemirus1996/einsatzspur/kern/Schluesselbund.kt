package io.github.elemirus1996.einsatzspur.kern

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Zugangsdaten nur verschlüsselt speichern: AES-GCM mit einem Schlüssel aus dem Android Keystore
 * (verlässt das Gerät nie). Abgelegt wird nur der verschlüsselte Text in den SharedPreferences.
 */
class Schluesselbund(context: Context) {
    private val ablage = context.getSharedPreferences("geschuetzt", Context.MODE_PRIVATE)

    fun speichern(konto: String, text: String) {
        val chiffre = Cipher.getInstance(VERFAHREN).apply { init(Cipher.ENCRYPT_MODE, schluessel()) }
        val daten = chiffre.doFinal(text.toByteArray())
        val wert = Base64.encodeToString(chiffre.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(daten, Base64.NO_WRAP)
        ablage.edit().putString(konto, wert).apply()
    }

    fun lesen(konto: String): String? {
        val wert = ablage.getString(konto, null) ?: return null
        return runCatching {
            val (iv, daten) = wert.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val chiffre = Cipher.getInstance(VERFAHREN)
                .apply { init(Cipher.DECRYPT_MODE, schluessel(), GCMParameterSpec(128, iv)) }
            String(chiffre.doFinal(daten))
        }.getOrNull()
    }

    fun loeschen(konto: String) {
        ablage.edit().remove(konto).apply()
    }

    fun sitzungSpeichern(sitzung: TeamSitzung) = speichern(KONTO_TEAM, JSONObject().apply {
        put("server", sitzung.server.basis)
        put("token", sitzung.token)
        put("teamId", sitzung.teamId)
        put("teamName", sitzung.teamName)
        put("expiresAtMs", sitzung.expiresAtMs)
    }.toString())

    fun sitzungLesen(): TeamSitzung? = lesen(KONTO_TEAM)?.let { text ->
        runCatching {
            val json = JSONObject(text)
            TeamSitzung(
                server = ServerAdresse.aus(json.getString("server")) ?: return null,
                token = json.getString("token"),
                teamId = json.optString("teamId").ifEmpty { null },
                teamName = json.getString("teamName"),
                expiresAtMs = json.getLong("expiresAtMs"),
            )
        }.getOrNull()
    }

    private fun schluessel(): SecretKey {
        val speicher = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (speicher.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    /** Server und Anmelde-Code des laufenden Einsatzes, für „Team wechseln“ ohne neuen Scan. */
    fun zugangSpeichern(zugang: TeamQRCode) = speichern(KONTO_ZUGANG, zugang.alsText())
    fun zugangLesen(): TeamQRCode? = lesen(KONTO_ZUGANG)?.let(TeamQRCode::ausText)
    fun zugangLoeschen() = loeschen(KONTO_ZUGANG)

    companion object {
        const val KONTO_ZUGANG = "einsatz-zugang"
        const val KONTO_TEAM = "team"
        private const val ALIAS = "einsatzspur-zugang"
        private const val VERFAHREN = "AES/GCM/NoPadding"
    }
}
