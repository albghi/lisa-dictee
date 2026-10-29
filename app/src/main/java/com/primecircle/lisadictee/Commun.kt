package com.primecircle.lisadictee

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Réglages : la clé de dictée est collée une seule fois dans l'application. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("lisa", Context.MODE_PRIVATE)
    fun cle(c: Context) = sp(c).getString("cle", "") ?: ""
    fun setCle(c: Context, v: String) = sp(c).edit().putString("cle", v.trim()).apply()
    fun langue(c: Context) = sp(c).getString("langue", "") ?: ""
    fun setLangue(c: Context, v: String) = sp(c).edit().putString("langue", v).apply()
    /** Langue dans laquelle la bulle traduit (fr = pas de traduction, collage direct). */
    fun cible(c: Context) = sp(c).getString("cible", "fr") ?: "fr"
    fun setCible(c: Context, v: String) = sp(c).edit().putString("cible", v).apply()
    /** Position de la bulle : côté (gauche ou droite) et hauteur. */
    fun bulleGauche(c: Context) = sp(c).getBoolean("bulle_gauche", false)
    fun bulleY(c: Context, defaut: Int) = sp(c).getInt("bulle_y", defaut)
    fun setBulle(c: Context, gauche: Boolean, y: Int) = sp(c).edit().putBoolean("bulle_gauche", gauche).putInt("bulle_y", y).apply()
}

/** Les langues proposées par la bulle. */
val LANGUES_BULLE = listOf("fr" to "🇫🇷 FR", "nl" to "🇳🇱 NL", "en" to "🇬🇧 EN", "it" to "🇮🇹 IT", "es" to "🇪🇸 ES", "zh" to "🇨🇳 中文")
fun libelleLangue(code: String) = LANGUES_BULLE.firstOrNull { it.first == code }?.second ?: code.uppercase()

/** Enregistre la voix en AAC (m4a), léger et bien compris par la transcription. */
class Enregistreur(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    var fichier: File = File(ctx.cacheDir, "dictee.m4a"); private set
    var debut = 0L; private set

    fun demarrer(): Boolean {
        arreter(false)
        fichier = File(ctx.cacheDir, "dictee-${System.currentTimeMillis()}.m4a")
        return try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(48000)
            r.setOutputFile(fichier.absolutePath)
            r.prepare(); r.start()
            rec = r; debut = System.currentTimeMillis(); true
        } catch (e: Exception) { rec = null; false }
    }

    fun niveau(): Float = try { ((rec?.maxAmplitude ?: 0) / 32767f).coerceIn(0f, 1f) } catch (e: Exception) { 0f }

    /** Arrête. Renvoie le fichier si on garde l'enregistrement, sinon l'efface. */
    fun arreter(garder: Boolean): File? {
        val r = rec ?: return null
        rec = null
        try { r.stop() } catch (_: Exception) { }
        try { r.release() } catch (_: Exception) { }
        if (!garder || System.currentTimeMillis() - debut < 400) { fichier.delete(); return null }
        return fichier
    }
}

data class Resultat(val texte: String, val tache: String?, val erreur: String?)

/** Envoie la voix au cerveau (fonction Supabase « dictee ») et récupère le texte propre. */
object Api {
    private const val URL_DICTEE = "https://dyazqpgqwlvbkcpaxtvq.supabase.co/functions/v1/dictee"

    fun envoyer(ctx: Context, f: File, app: String): Resultat {
        val cle = Prefs.cle(ctx)
        if (cle.isBlank()) return Resultat("", null, "Colle d'abord ta clé dans l'application Lisa Dictée.")
        val b = "----lisa${System.currentTimeMillis()}"
        val c = (URL(URL_DICTEE).openConnection() as HttpURLConnection)
        return try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 15000; c.readTimeout = 60000
            c.setRequestProperty("x-dictee-key", cle)
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
            DataOutputStream(c.outputStream).use { o ->
                fun champ(n: String, v: String) {
                    o.writeBytes("--$b\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n"); o.write(v.toByteArray()); o.writeBytes("\r\n")
                }
                champ("app", app); champ("source", "android")
                val l = Prefs.langue(ctx); if (l.isNotBlank()) champ("langue", l)
                o.writeBytes("--$b\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"voix.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n")
                o.write(f.readBytes()); o.writeBytes("\r\n--$b--\r\n")
            }
            val code = c.responseCode
            val txt = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
            val j = JSONObject(txt)
            if (code >= 400) return Resultat("", null, j.optString("error", "Erreur $code"))
            val t = j.optJSONObject("tache")
            Resultat(j.optString("texte", ""), t?.let { "#${it.optInt("id")} ${it.optString("titre")} · ${it.optString("projet")}" }, null)
        } catch (e: Exception) {
            Resultat("", null, "Pas de connexion : ${e.message ?: ""}".take(120))
        } finally { c.disconnect(); f.delete() }
    }

    /** Traduit un texte déjà dicté, de façon naturelle (fonction « dictee », mode traduction). */
    fun traduire(ctx: Context, texte: String, cible: String, app: String): Resultat {
        val c = (URL(URL_DICTEE).openConnection() as HttpURLConnection)
        return try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 45000
            c.setRequestProperty("x-dictee-key", Prefs.cle(ctx)); c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("texte", texte).put("cible", cible).put("app", app).toString().toByteArray()) }
            val code = c.responseCode
            val j = JSONObject((if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText())
            if (code >= 400) Resultat("", null, j.optString("error", "Erreur $code")) else Resultat(j.optString("traduction", ""), null, null)
        } catch (e: Exception) { Resultat("", null, "Pas de connexion") } finally { c.disconnect() }
    }

    /** Ton du texte selon l'application où l'on écrit. */
    fun appDe(pkg: String?): String {
        val p = pkg ?: return ""
        return when {
            p.contains("whatsapp") || p.contains("telegram") || p.contains("messag") || p.contains("signal") -> "whatsapp"
            p.contains("android.gm") || p.contains("outlook") || p.contains("mail") -> "mail"
            p.contains("twitter") || p == "com.x.android" -> "x"
            p.contains("keep") || p.contains("notes") -> "notes"
            else -> ""
        }
    }
}
