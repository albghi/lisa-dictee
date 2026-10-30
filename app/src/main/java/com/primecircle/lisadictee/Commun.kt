package com.primecircle.lisadictee

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
    /** Style d'écriture par famille d'applis (comme Wispr) : formel, decontracte, tres. */
    fun style(c: Context, famille: String) = sp(c).getString("style_$famille", "formel") ?: "formel"
    fun setStyle(c: Context, famille: String, v: String) = sp(c).edit().putString("style_$famille", v).apply()
}

/** Famille de style selon l'appli où l'on écrit. */
fun familleDe(app: String) = when (app) { "whatsapp", "telegram" -> "messages"; "mail" -> "email"; "x" -> "reseaux"; else -> "autres" }

/** Mise en forme finale : jamais de tiret en début de ligne, des puces à la place. */
fun puces(t: String) = t.lines().joinToString("\n") { l -> Regex("^(\\s*)[-–—]\\s+").replace(l) { m -> m.groupValues[1] + "• " } }

/** Historique des dictées, gardé seulement sur le téléphone (le serveur ne stocke jamais le texte). */
object Historique {
    private fun sp(c: Context) = c.getSharedPreferences("lisa_histo", Context.MODE_PRIVATE)
    fun liste(c: Context): List<Pair<Long, String>> {
        val a = try { org.json.JSONArray(sp(c).getString("h", "[]")) } catch (_: Exception) { org.json.JSONArray() }
        return (0 until a.length()).map { val o = a.getJSONObject(it); o.optLong("t") to o.optString("x") }
    }
    fun ajouter(c: Context, texte: String) {
        if (texte.isBlank()) return
        val l = listOf(System.currentTimeMillis() to texte) + liste(c).take(199)
        val a = org.json.JSONArray(); l.forEach { a.put(JSONObject().put("t", it.first).put("x", it.second)) }
        val mots = texte.split(Regex("\\s+")).count { it.isNotBlank() }
        sp(c).edit().putString("h", a.toString()).putLong("mots", motsTotal(c) + mots).apply()
    }
    fun motsTotal(c: Context) = sp(c).getLong("mots", 0L)
    fun vider(c: Context) = sp(c).edit().putString("h", "[]").apply()
}

/** Les langues proposées par la bulle. */
val LANGUES_BULLE = listOf("fr" to "🇫🇷 FR", "nl" to "🇳🇱 NL", "en" to "🇬🇧 EN", "it" to "🇮🇹 IT", "es" to "🇪🇸 ES", "zh" to "🇨🇳 中文")
fun libelleLangue(code: String) = LANGUES_BULLE.firstOrNull { it.first == code }?.second ?: code.uppercase()

/**
 * Enregistre la voix (PCM 16 kHz) et l'envoie en direct à ElevenLabs Scribe v2 Realtime pendant qu'on parle.
 * Au ✓, il ne reste qu'à valider la dernière seconde : le texte arrive presque tout de suite.
 * Si le direct n'a pas marché (réseau, jeton), on garde toute la voix et on repasse par l'ancien chemin.
 */
class Enregistreur(private val ctx: Context) {
    private var ar: AudioRecord? = null
    private var fil: Thread? = null
    @Volatile private var niv = 0f
    @Volatile private var actif = false
    private var session: Direct? = null
    var debut = 0L; private set

    fun demarrer(): Boolean {
        arreter(false)
        return try {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val a = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400) * 2)
            if (a.state != AudioRecord.STATE_INITIALIZED) { a.release(); return false }
            val s = Direct(ctx); session = s; s.ouvrir()
            a.startRecording(); ar = a; actif = true; debut = System.currentTimeMillis()
            fil = Thread {
                val buf = ShortArray(1600) // 100 ms
                while (actif) {
                    val n = a.read(buf, 0, buf.size)
                    if (n > 0) {
                        var m = 0
                        val o = ByteArray(n * 2)
                        for (i in 0 until n) { val v = buf[i].toInt(); val av = if (v < 0) -v else v; if (av > m) m = av; o[2 * i] = (v and 0xff).toByte(); o[2 * i + 1] = ((v shr 8) and 0xff).toByte() }
                        niv = (m / 32767f).coerceIn(0f, 1f)
                        s.audio(o)
                    }
                }
            }.also { it.start() }
            true
        } catch (e: Exception) { ar = null; session?.fermer(); session = null; false }
    }

    fun niveau(): Float = niv

    /** Arrête. Renvoie la prise de voix si on la garde, sinon ferme tout. */
    fun arreter(garder: Boolean): Direct? {
        val a = ar ?: return null
        ar = null; actif = false
        try { fil?.join(400) } catch (_: Exception) { }
        try { a.stop() } catch (_: Exception) { }
        try { a.release() } catch (_: Exception) { }
        val s = session ?: return null
        session = null; niv = 0f
        if (!garder || System.currentTimeMillis() - debut < 400) { s.fermer(); return null }
        return s
    }
}

/** Une prise de voix en direct : transcription au fil de la parole, puis le texte final. */
class Direct(private val ctx: Context) {
    private val pcm = java.io.ByteArrayOutputStream()
    private val attente = ArrayList<ByteArray>()
    private val verrou = Object()
    private var ws: WebSocket? = null
    @Volatile private var ouvert = false
    @Volatile private var echec = false
    @Volatile private var dernierEngage = 0L
    @Volatile private var partiel = ""
    private val engages = StringBuilder()

    fun ouvrir() {
        Thread {
            try {
                val jeton = Api.jeton(ctx) ?: throw Exception("jeton")
                val l = Prefs.langue(ctx)
                val url = "wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&audio_format=pcm_16000&commit_strategy=manual&token=" + jeton + (if (l.isNotBlank()) "&language_code=$l" else "")
                ws = CLIENT.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(w: WebSocket, r: Response) {
                        synchronized(verrou) { ouvert = true; for (b in attente) envoyer(w, b, false); attente.clear() }
                    }
                    override fun onMessage(w: WebSocket, t: String) {
                        val j = try { JSONObject(t) } catch (_: Exception) { return }
                        when (j.optString("message_type")) {
                            "partial_transcript" -> partiel = j.optString("text")
                            "committed_transcript" -> synchronized(engages) { val x = j.optString("text").trim(); if (x.isNotEmpty()) engages.append(x).append(' '); partiel = ""; dernierEngage = System.currentTimeMillis() }
                            "session_started" -> {}
                            else -> if (j.has("error")) echec = true
                        }
                    }
                    override fun onFailure(w: WebSocket, t: Throwable, r: Response?) { echec = true }
                })
            } catch (e: Exception) { echec = true }
        }.start()
    }

    private fun envoyer(w: WebSocket?, b: ByteArray, commit: Boolean) {
        w?.send(JSONObject().put("message_type", "input_audio_chunk").put("audio_base_64", Base64.encodeToString(b, Base64.NO_WRAP))
            .put("commit", commit).put("sample_rate", 16000).toString())
    }

    fun audio(b: ByteArray) {
        synchronized(verrou) { pcm.write(b); if (ouvert) envoyer(ws, b, false) else attente.add(b) }
    }

    fun fermer() { try { ws?.close(1000, null) } catch (_: Exception) { } }

    /** Bloquant (à appeler hors de l'écran) : valide la fin, récupère le texte et le fait mettre au propre. */
    fun resultat(app: String): Resultat {
        var brut: String? = null
        val t0 = System.currentTimeMillis()
        while (!ouvert && !echec && System.currentTimeMillis() - t0 < 2500) Thread.sleep(25)
        if (ouvert && !echec) {
            val tc = System.currentTimeMillis()
            synchronized(verrou) { envoyer(ws, ByteArray(3200), true) } // 100 ms de silence, et on valide
            while (!echec && dernierEngage < tc && System.currentTimeMillis() - tc < 4000) Thread.sleep(20)
            synchronized(engages) {
                if (dernierEngage >= tc || (partiel.isBlank() && engages.isNotEmpty())) brut = engages.toString().trim()
            }
        }
        fermer()
        val b = brut ?: return Api.envoyer(ctx, wav(), app) // secours : l'ancien chemin, avec toute la voix
        if (b.isBlank()) return Resultat("", null, null)
        return Api.nettoyer(ctx, b, app)
    }

    private fun wav(): File {
        val data = synchronized(verrou) { pcm.toByteArray() }
        val f = File(ctx.cacheDir, "dictee-${System.currentTimeMillis()}.wav")
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(data.size)
        f.outputStream().use { it.write(h.array()); it.write(data) }
        return f
    }

    companion object { val CLIENT: OkHttpClient by lazy { OkHttpClient.Builder().pingInterval(15, java.util.concurrent.TimeUnit.SECONDS).build() } }
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
                val wav = f.name.endsWith(".wav")
                o.writeBytes("--$b\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"" + (if (wav) "voix.wav" else "voix.m4a") + "\"\r\nContent-Type: " + (if (wav) "audio/wav" else "audio/mp4") + "\r\n\r\n")
                o.write(f.readBytes()); o.writeBytes("\r\n--$b--\r\n")
            }
            val code = c.responseCode
            val txt = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
            val j = JSONObject(txt)
            if (code >= 400) return Resultat("", null, j.optString("error", "Erreur $code"))
            val propre = puces(j.optString("texte", "")); Historique.ajouter(ctx, propre)
            Resultat(propre, annonce(j), null)
        } catch (e: Exception) {
            Resultat("", null, "Pas de connexion : ${e.message ?: ""}".take(120))
        } finally { c.disconnect(); f.delete() }
    }

    /** Message affiché quand la dictée n'est pas un texte à coller : « Lisa, retiens … » ou « Lisa, tâche … ». */
    private fun annonce(j: JSONObject): String? {
        j.optString("appris").takeIf { it.isNotBlank() }?.let { return it }
        val t = j.optJSONObject("tache") ?: return null
        return "Tâche ajoutée : #${t.optInt("id")} ${t.optString("titre")} · ${t.optString("projet")}"
    }

    /** Jeton à usage unique pour la transcription en direct (la clé ElevenLabs reste sur le serveur). */
    fun jeton(ctx: Context): String? {
        val c = (URL("$URL_DICTEE?a=jeton").openConnection() as HttpURLConnection)
        return try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 10000
            c.setRequestProperty("x-dictee-key", Prefs.cle(ctx)); c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("{}".toByteArray()) }
            if (c.responseCode >= 400) null else JSONObject(c.inputStream.bufferedReader().readText()).optString("token").ifBlank { null }
        } catch (e: Exception) { null } finally { c.disconnect() }
    }

    /** Texte transcrit en direct : le serveur le met au propre (ou crée la tâche si « Lisa, tâche… »). */
    fun nettoyer(ctx: Context, brut: String, app: String): Resultat {
        val c = (URL(URL_DICTEE).openConnection() as HttpURLConnection)
        return try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 30000
            c.setRequestProperty("x-dictee-key", Prefs.cle(ctx)); c.setRequestProperty("Content-Type", "application/json")
            val l = Prefs.langue(ctx)
            c.outputStream.use { it.write(JSONObject().put("brut", brut).put("app", app).put("source", "android-direct").put("langue", l).put("style", Prefs.style(ctx, familleDe(app))).toString().toByteArray()) }
            val code = c.responseCode
            val j = JSONObject((if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText())
            if (code >= 400) return Resultat(brut, null, null) // le serveur coince : on colle quand même le texte brut
            val txt = puces(j.optString("texte", "")); Historique.ajouter(ctx, txt)
            Resultat(txt, annonce(j), null)
        } catch (e: Exception) { Resultat(brut, null, null) } finally { c.disconnect() }
    }

    /** Dictionnaire et raccourcis (partagés avec le serveur). Bloquant : à appeler hors de l'écran. */
    fun dico(ctx: Context, action: String, corps: JSONObject = JSONObject()): JSONObject? {
        val c = (URL("$URL_DICTEE?a=$action").openConnection() as HttpURLConnection)
        return try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 20000
            c.setRequestProperty("x-dictee-key", Prefs.cle(ctx)); c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(corps.toString().toByteArray()) }
            JSONObject((if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText())
        } catch (e: Exception) { null } finally { c.disconnect() }
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
