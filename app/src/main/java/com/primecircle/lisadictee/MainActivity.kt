package com.primecircle.lisadictee

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Version 9 (30/10/2026), façon Wispr : Accueil (historique et compteur), Dictionnaire, Style, Raccourcis, Réglages.
 * L'historique reste sur le téléphone ; le dictionnaire et les raccourcis sont partagés avec le serveur (toutes les dictées en profitent).
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var rec: Enregistreur
    private var enTest = false
    private var onglet = "accueil"
    private lateinit var contenu: LinearLayout
    private lateinit var barre: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rond(c: String, r: Int = 12) = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = dp(r).toFloat() }
    private fun titre(t: String) = TextView(this).apply { text = t; textSize = 15f; setTextColor(Color.parseColor("#141210")); setPadding(0, dp(18), 0, dp(6)); setTypeface(typeface, Typeface.BOLD) }
    private fun note(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(Color.parseColor("#6B655D")) }
    private fun bouton(t: String, c: String = "#F0674B") = Button(this).apply { text = t; isAllCaps = false; setTextColor(Color.WHITE); background = rond(c) }
    private fun carte() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rond("#FFFFFF", 14); setPadding(dp(14), dp(12), dp(14), dp(12)) }
    private fun lp(top: Int = 10) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun fond(t: () -> Unit) = Thread { t() }.start()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        rec = Enregistreur(this)
        val racine = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#FAF8F4")) }
        racine.addView(TextView(this).apply { text = "🎙 Lisa Dictée"; textSize = 24f; setTextColor(Color.parseColor("#141210")); setTypeface(typeface, Typeface.BOLD); setPadding(dp(20), dp(24), dp(20), dp(8)) })
        barre = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), dp(8)) }
        racine.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(barre) })
        contenu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), dp(40)) }
        racine.addView(ScrollView(this).apply { addView(contenu) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(racine)
        if (Prefs.cle(this).isBlank()) onglet = "reglages"
        afficher()
    }

    override fun onResume() { super.onResume(); if (onglet == "accueil") afficher() }

    private fun afficher() {
        barre.removeAllViews()
        for ((k, lib) in listOf("accueil" to "Accueil", "dico" to "Dictionnaire", "style" to "Style", "raccourcis" to "Raccourcis", "reglages" to "Réglages")) {
            val actif = k == onglet
            barre.addView(TextView(this).apply {
                text = lib; textSize = 14f; setPadding(dp(14), dp(8), dp(14), dp(8))
                setTextColor(if (actif) Color.WHITE else Color.parseColor("#3A3530")); background = rond(if (actif) "#141210" else "#ECE7DF", 999)
                setOnClickListener { onglet = k; afficher() }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        contenu.removeAllViews()
        when (onglet) { "accueil" -> accueil(); "dico" -> dictionnaire(); "style" -> style(); "raccourcis" -> raccourcis(); else -> reglages() }
    }

    // ---------- Accueil : compteur et historique ----------
    private fun accueil() {
        val mots = Historique.motsTotal(this)
        val c = carte()
        c.addView(TextView(this).apply { text = "%,d".format(Locale.FRANCE, mots).replace(' ', ' ') + " mots dictés"; textSize = 26f; setTextColor(Color.parseColor("#141210")); setTypeface(typeface, Typeface.BOLD) })
        val pages = mots / 250
        c.addView(note(if (pages > 0) "Environ $pages pages écrites à la voix. Continue !" else "Tes dictées apparaîtront ici."))
        contenu.addView(c, lp(4))
        val h = Historique.liste(this)
        if (h.isEmpty()) { contenu.addView(note("\nAucune dictée pour l'instant. Touche la bulle et parle.")); return }
        val jour = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE); val heure = SimpleDateFormat("HH:mm", Locale.FRANCE)
        var dernier = ""
        for ((t, x) in h.take(100)) {
            val cal = Calendar.getInstance().apply { timeInMillis = t }
            val auj = Calendar.getInstance()
            val j = when {
                cal.get(Calendar.DAY_OF_YEAR) == auj.get(Calendar.DAY_OF_YEAR) && cal.get(Calendar.YEAR) == auj.get(Calendar.YEAR) -> "Aujourd'hui"
                auj.apply { add(Calendar.DAY_OF_YEAR, -1) }.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) -> "Hier"
                else -> jour.format(Date(t)).replaceFirstChar { it.uppercase() }
            }
            if (j != dernier) { contenu.addView(titre(j)); dernier = j }
            val k = carte()
            k.addView(TextView(this).apply { text = x; textSize = 15f; setTextColor(Color.parseColor("#141210")); setTextIsSelectable(true); setLineSpacing(0f, 1.15f) })
            val bas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            bas.addView(note(heure.format(Date(t))), LinearLayout.LayoutParams(0, -2, 1f))
            bas.addView(TextView(this).apply { text = "⧉ Copier"; textSize = 13f; setTextColor(Color.parseColor("#E8473A")); setPadding(dp(8), dp(6), 0, 0)
                setOnClickListener { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Lisa", x)); Toast.makeText(this@MainActivity, "Copié", Toast.LENGTH_SHORT).show() } })
            k.addView(bas)
            contenu.addView(k, lp(8))
        }
        contenu.addView(TextView(this).apply { text = "Effacer l'historique"; textSize = 13f; setTextColor(Color.parseColor("#8E877E")); setPadding(0, dp(20), 0, 0)
            setOnClickListener { AlertDialog.Builder(this@MainActivity).setMessage("Effacer l'historique de ce téléphone ?").setPositiveButton("Effacer") { _, _ -> Historique.vider(this@MainActivity); afficher() }.setNegativeButton("Annuler", null).show() } })
    }

    // ---------- Dictionnaire ----------
    private fun dictionnaire() {
        contenu.addView(note("Les noms et les mots que Lisa doit toujours bien écrire. Les noms ne sont jamais traduits. Astuce : dicte « Lisa, retiens que … » pour en ajouter à la voix."))
        val ajout = carte()
        val mot = EditText(this).apply { hint = "Mot ou nom (ex. Borgo Pineto)"; setSingleLine(true) }
        val entendu = EditText(this).apply { hint = "Souvent mal entendu comme… (facultatif)"; setSingleLine(true) }
        val nom = CheckBox(this).apply { text = "C'est un nom propre (ne jamais traduire)"; isChecked = true }
        val ok = bouton("＋ Ajouter")
        ajout.addView(mot); ajout.addView(entendu); ajout.addView(nom); ajout.addView(ok, lp(6))
        contenu.addView(ajout, lp(10))
        val liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contenu.addView(liste, lp(10))
        fun charger() {
            liste.removeAllViews(); liste.addView(note("Chargement…"))
            fond {
                val r = Api.dico(this@MainActivity, "dico")
                ui.post {
                    liste.removeAllViews()
                    val a = r?.optJSONArray("mots") ?: run { liste.addView(note("Pas de connexion. Réessaie.")); return@post }
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i); val m = o.optString("mot")
                        val det = when (o.optString("type")) { "nom" -> "nom, jamais traduit"; "equivalent" -> "en ${o.optString("langue")} : ${o.optString("traduction")}"; else -> o.optString("entendu").takeIf { it.isNotBlank() && it != m.lowercase() }?.let { "entendu « $it »" } ?: "" }
                        val ligne = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10)) }
                        val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                        col.addView(TextView(this@MainActivity).apply { text = m; textSize = 16f; setTextColor(Color.parseColor("#141210")) })
                        if (det.isNotBlank()) col.addView(note(det))
                        ligne.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                        ligne.addView(TextView(this@MainActivity).apply { text = "✕"; textSize = 16f; setTextColor(Color.parseColor("#B8B1A8")); setPadding(dp(12), 0, dp(4), 0)
                            setOnClickListener { AlertDialog.Builder(this@MainActivity).setMessage("Retirer « $m » ?").setPositiveButton("Retirer") { _, _ ->
                                fond { Api.dico(this@MainActivity, "dico_suppr", JSONObject().put("mot", m).put("langue", o.optString("langue").takeIf { it.isNotBlank() && it != "null" })); ui.post { charger() } } }.setNegativeButton("Annuler", null).show() } })
                        liste.addView(ligne)
                        liste.addView(View(this@MainActivity).apply { setBackgroundColor(Color.parseColor("#ECE7DF")) }, LinearLayout.LayoutParams(-1, dp(1)))
                    }
                }
            }
        }
        ok.setOnClickListener {
            val m = mot.text.toString().trim(); if (m.isBlank()) return@setOnClickListener
            val corps = JSONObject().put("mot", m).put("nom", nom.isChecked); entendu.text.toString().trim().takeIf { it.isNotBlank() }?.let { corps.put("entendu", it) }
            fond { val r = Api.dico(this@MainActivity, "dico_ajout", corps); ui.post { if (r?.optBoolean("ok") == true) { mot.setText(""); entendu.setText(""); Toast.makeText(this@MainActivity, "Ajouté", Toast.LENGTH_SHORT).show(); charger() } else Toast.makeText(this@MainActivity, "Échec, réessaie", Toast.LENGTH_SHORT).show() } }
        }
        charger()
    }

    // ---------- Style ----------
    private fun style() {
        contenu.addView(note("Le style d'écriture selon l'endroit où tu dictes."))
        val familles = listOf("messages" to "💬 Messages (WhatsApp, Telegram)", "email" to "✉️ E-mails", "reseaux" to "𝕏 Réseaux (X)", "autres" to "📝 Toutes les autres applis")
        val exemples = mapOf(
            "formel" to ("Formel" to "Salut, tu es libre demain midi ?\nOn dit 12 h si ça te va."),
            "decontracte" to ("Décontracté" to "Salut, tu es libre demain midi ?\nOn dit 12 h si ça te va"),
            "tres" to ("Très décontracté" to "salut tu es libre demain midi\non dit 12 h si ça te va"))
        for ((f, lib) in familles) {
            contenu.addView(titre(lib))
            for ((k, v) in exemples) {
                val actif = Prefs.style(this, f) == k
                val c = carte().apply { if (actif) background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat(); setStroke(dp(2), Color.parseColor("#E8473A")) } }
                c.addView(TextView(this).apply { text = (if (actif) "● " else "○ ") + v.first; textSize = 15f; setTextColor(Color.parseColor("#141210")); setTypeface(typeface, Typeface.BOLD) })
                c.addView(note(v.second))
                c.setOnClickListener { Prefs.setStyle(this, f, k); afficher() }
                contenu.addView(c, lp(6))
            }
        }
    }

    // ---------- Raccourcis ----------
    private fun raccourcis() {
        contenu.addView(note("Dis la phrase, Lisa écrit le texte. Par exemple « mon adresse mail » devient ton adresse."))
        val ajout = carte()
        val d = EditText(this).apply { hint = "Ce que tu dis (ex. mon adresse)"; setSingleLine(true) }
        val t = EditText(this).apply { hint = "Ce que Lisa écrit"; minLines = 2 }
        val ok = bouton("＋ Ajouter le raccourci")
        ajout.addView(d); ajout.addView(t); ajout.addView(ok, lp(6))
        contenu.addView(ajout, lp(10))
        val liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contenu.addView(liste, lp(10))
        fun charger() {
            liste.removeAllViews(); liste.addView(note("Chargement…"))
            fond {
                val r = Api.dico(this@MainActivity, "dico")
                ui.post {
                    liste.removeAllViews()
                    val a = r?.optJSONArray("raccourcis") ?: run { liste.addView(note("Pas de connexion. Réessaie.")); return@post }
                    if (a.length() == 0) liste.addView(note("Aucun raccourci."))
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        val c = carte()
                        val h = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                        h.addView(TextView(this@MainActivity).apply { text = "« ${o.optString("declencheur")} »"; textSize = 15f; setTextColor(Color.parseColor("#141210")); setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
                        h.addView(TextView(this@MainActivity).apply { text = "✕"; textSize = 16f; setTextColor(Color.parseColor("#B8B1A8"))
                            setOnClickListener { fond { Api.dico(this@MainActivity, "raccourci_suppr", JSONObject().put("id", o.optInt("id"))); ui.post { charger() } } } })
                        c.addView(h); c.addView(note(o.optString("texte")))
                        liste.addView(c, lp(8))
                    }
                }
            }
        }
        ok.setOnClickListener {
            val a = d.text.toString().trim(); val b = t.text.toString().trim(); if (a.isBlank() || b.isBlank()) return@setOnClickListener
            fond { val r = Api.dico(this@MainActivity, "raccourci_ajout", JSONObject().put("declencheur", a).put("texte", b)); ui.post { if (r?.optBoolean("ok") == true) { d.setText(""); t.setText(""); charger() } else Toast.makeText(this@MainActivity, "Échec, réessaie", Toast.LENGTH_SHORT).show() } }
        }
        charger()
    }

    // ---------- Réglages (l'ancien écran d'installation) ----------
    private fun reglages() {
        contenu.addView(titre("1. Ta clé"))
        contenu.addView(note("Colle la clé que Claude t'a envoyée dans Telegram. Une seule fois."))
        val cle = EditText(this).apply { hint = "dk_…"; setText(Prefs.cle(this@MainActivity)); setSingleLine(true) }
        contenu.addView(cle)
        val enreg = bouton("Enregistrer la clé"); contenu.addView(enreg)
        enreg.setOnClickListener { Prefs.setCle(this, cle.text.toString()); enreg.text = "Clé enregistrée ✓" }

        contenu.addView(titre("2. Le micro"))
        val micro = bouton("Autoriser le micro"); contenu.addView(micro)
        micro.setOnClickListener {
            val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(p.toTypedArray(), 1)
        }

        contenu.addView(titre("3. La bulle"))
        contenu.addView(note("Dans Accessibilité, ouvre « Bulle Lisa Dictée » et active-la. La bulle apparaît quand tu touches une zone de texte."))
        val bulle = bouton("Activer la bulle", "#3A3530"); contenu.addView(bulle)
        bulle.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        contenu.addView(titre("4. Le clavier Lisa (facultatif)"))
        contenu.addView(note("Active « Clavier Lisa », puis choisis-le. Le bouton 🌐 du clavier te ramène à ton clavier habituel."))
        val activer = bouton("Activer le clavier Lisa", "#3A3530"); contenu.addView(activer)
        activer.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        val choisir = bouton("Choisir le clavier", "#3A3530"); contenu.addView(choisir)
        choisir.setOnClickListener { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }

        contenu.addView(titre("5. Tester"))
        val res = TextView(this).apply { textSize = 15f; setTextColor(Color.parseColor("#141210")); setPadding(0, dp(10), 0, 0); setTextIsSelectable(true) }
        val test = bouton("🎙 Appuie, parle, puis appuie à nouveau", "#2E8B62"); contenu.addView(test); contenu.addView(res)
        test.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { res.text = "Autorise d'abord le micro (étape 2)."; return@setOnClickListener }
            if (!enTest) {
                if (rec.demarrer()) { enTest = true; test.text = "■ Arrêter et écrire"; res.text = "Lisa t'écoute…" }
            } else {
                enTest = false; test.text = "🎙 Appuie, parle, puis appuie à nouveau"
                val f = rec.arreter(true) ?: run { res.text = "Trop court, recommence."; return@setOnClickListener }
                res.text = "Lisa écrit…"
                Thread { val r = f.resultat(""); ui.post { res.text = r.erreur?.let { "⚠️ $it" } ?: r.tache?.let { "✅ $it" } ?: r.texte.ifBlank { "Je n'ai rien entendu." } } }.start()
            }
        }
        contenu.addView(note("\nÀ la voix :\n• « Lisa, tâche, Tano, … » envoie une tâche dans Lisa Operator.\n• « Lisa, retiens que … » apprend un nom ou un mot.").apply { gravity = Gravity.START })
    }

    override fun onPause() { if (enTest) { rec.arreter(false); enTest = false }; super.onPause() }
}
