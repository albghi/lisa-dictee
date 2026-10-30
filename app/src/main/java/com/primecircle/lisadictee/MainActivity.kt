package com.primecircle.lisadictee

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Écran de réglage : la clé, les autorisations, et un bouton pour tester la dictée. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var rec: Enregistreur
    private var enTest = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rond(c: String) = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = dp(12).toFloat() }
    private fun titre(t: String) = TextView(this).apply { text = t; textSize = 15f; setTextColor(Color.parseColor("#141210")); setPadding(0, dp(18), 0, dp(6)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
    private fun note(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(Color.parseColor("#6B655D")) }
    private fun bouton(t: String, c: String = "#F0674B") = Button(this).apply { text = t; isAllCaps = false; setTextColor(Color.WHITE); background = rond(c) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        rec = Enregistreur(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(40)); setBackgroundColor(Color.parseColor("#FAF8F4")) }
        col.addView(TextView(this).apply { text = "🎙 Lisa Dictée"; textSize = 26f; setTextColor(Color.parseColor("#141210")); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(note("Tu parles, Lisa écrit, proprement, là où se trouve ton curseur."))

        col.addView(titre("1. Ta clé"))
        col.addView(note("Colle la clé que Claude t'a envoyée dans Telegram. Une seule fois."))
        val cle = EditText(this).apply { hint = "dk_…"; setText(Prefs.cle(this@MainActivity)); setSingleLine(true) }
        col.addView(cle)
        val enreg = bouton("Enregistrer la clé"); col.addView(enreg)
        enreg.setOnClickListener { Prefs.setCle(this, cle.text.toString()); enreg.text = "Clé enregistrée ✓" }

        col.addView(titre("2. Le micro"))
        val micro = bouton("Autoriser le micro"); col.addView(micro)
        micro.setOnClickListener {
            val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(p.toTypedArray(), 1)
        }

        col.addView(titre("3. Le clavier Lisa"))
        col.addView(note("Active « Clavier Lisa », puis choisis-le. Le bouton 🌐 du clavier te ramène à ton clavier habituel."))
        val activer = bouton("Activer le clavier Lisa", "#3A3530"); col.addView(activer)
        activer.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        val choisir = bouton("Choisir le clavier", "#3A3530"); col.addView(choisir)
        choisir.setOnClickListener { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }

        col.addView(titre("4. La bulle (comme Wispr)"))
        col.addView(note("Dans Accessibilité, ouvre « Bulle Lisa Dictée » et active-la. La bulle apparaît quand tu touches une zone de texte, avec ton clavier habituel."))
        val bulle = bouton("Activer la bulle", "#3A3530"); col.addView(bulle)
        bulle.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        col.addView(titre("5. Tester"))
        val res = TextView(this).apply { textSize = 15f; setTextColor(Color.parseColor("#141210")); setPadding(0, dp(10), 0, 0); setTextIsSelectable(true) }
        val test = bouton("🎙 Appuie, parle, puis appuie à nouveau", "#2E8B62"); col.addView(test); col.addView(res)
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
        col.addView(note("\nAstuce : commence par « Lisa, tâche, Tano, … » et la tâche part directement dans Lisa Operator.").apply { gravity = Gravity.START })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onPause() { if (enTest) { rec.arreter(false); enTest = false }; super.onPause() }
}
