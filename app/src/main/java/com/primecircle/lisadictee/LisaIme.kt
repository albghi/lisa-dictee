package com.primecircle.lisadictee

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Le clavier Lisa : un gros micro, puis ✗ pour annuler ou ✓ pour écrire le texte propre à l'endroit du curseur. */
class LisaIme : InputMethodService() {
    private lateinit var rec: Enregistreur
    private lateinit var onde: Onde
    private lateinit var anim: Animateur
    private lateinit var statut: TextView
    private lateinit var micro: Button
    private lateinit var ligneChoix: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var pkg: String? = null
    private var occupe = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rond(c: String, r: Int = 999) = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = dp(r).toFloat() }
    private fun bouton(t: String, fond: String, couleur: String = "#FFFFFF", taille: Float = 18f) = Button(this).apply {
        text = t; setTextColor(Color.parseColor(couleur)); textSize = taille; isAllCaps = false; background = rond(fond, 14)
    }

    override fun onCreateInputView(): View {
        rec = Enregistreur(this)
        onde = Onde(this)
        anim = Animateur(rec, onde)
        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#141210")); setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        statut = TextView(this).apply { setTextColor(Color.parseColor("#CFC9C1")); textSize = 13f; gravity = Gravity.CENTER; text = "Touche le micro et parle" }
        racine.addView(statut, LinearLayout.LayoutParams(-1, dp(22)))

        val centre = FrameLayout(this)
        micro = bouton("🎙", "#F0674B", taille = 30f).apply { background = rond("#F0674B") }
        centre.addView(micro, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
        ligneChoix = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        val annuler = bouton("✗", "#3A3530", taille = 24f)
        val valider = bouton("✓", "#2E8B62", taille = 24f)
        ligneChoix.addView(annuler, LinearLayout.LayoutParams(dp(64), dp(64)))
        ligneChoix.addView(onde, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
        ligneChoix.addView(valider, LinearLayout.LayoutParams(dp(64), dp(64)))
        centre.addView(ligneChoix, FrameLayout.LayoutParams(-1, dp(84), Gravity.CENTER))
        racine.addView(centre, LinearLayout.LayoutParams(-1, dp(100)))

        val bas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val globe = bouton("🌐", "#2C2925", taille = 16f)
        val espace = bouton("espace", "#2C2925", "#CFC9C1", 14f)
        val effacer = bouton("⌫", "#2C2925", taille = 16f)
        val entree = bouton("↵", "#2C2925", taille = 16f)
        val lp = { w: Float -> LinearLayout.LayoutParams(0, dp(46), w).apply { marginStart = dp(3); marginEnd = dp(3) } }
        bas.addView(globe, lp(1f)); bas.addView(espace, lp(3f)); bas.addView(effacer, lp(1f)); bas.addView(entree, lp(1f))
        racine.addView(bas, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        micro.setOnClickListener { commencer() }
        annuler.setOnClickListener { annuler() }
        valider.setOnClickListener { valider() }
        // 🌐 : revient au clavier précédent ; s'il n'y en a pas, ouvre la liste des claviers. Appui long : toujours la liste.
        val liste = { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker() }
        globe.setOnClickListener {
            val ok = try { if (Build.VERSION.SDK_INT >= 28) switchToPreviousInputMethod() || switchToNextInputMethod(false) else false } catch (_: Exception) { false }
            if (!ok) liste()
        }
        globe.setOnLongClickListener { liste(); true }
        espace.setOnClickListener { currentInputConnection?.commitText(" ", 1) }
        effacer.setOnClickListener { currentInputConnection?.deleteSurroundingText(1, 0) }
        effacer.setOnLongClickListener { currentInputConnection?.deleteSurroundingText(40, 0); true }
        entree.setOnClickListener { envoyerEntree() }
        return racine
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        pkg = info?.packageName
        if (!occupe) etatRepos("Touche le micro et parle")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (::rec.isInitialized && !occupe) { rec.arreter(false); anim.stop(); etatRepos("Touche le micro et parle") }
        super.onFinishInputView(finishingInput)
    }

    private fun etatRepos(t: String) { micro.visibility = View.VISIBLE; ligneChoix.visibility = View.GONE; statut.text = t }

    private fun commencer() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statut.text = "Ouvre l'appli Lisa Dictée pour autoriser le micro"; return
        }
        if (!rec.demarrer()) { statut.text = "Micro indisponible"; return }
        micro.visibility = View.GONE; ligneChoix.visibility = View.VISIBLE; statut.text = "Lisa t'écoute…"; anim.lancer()
    }

    private fun annuler() { anim.stop(); rec.arreter(false); etatRepos("Annulé") }

    private fun valider() {
        anim.stop()
        val f = rec.arreter(true) ?: run { etatRepos("Trop court, recommence"); return }
        occupe = true; ligneChoix.visibility = View.GONE; micro.visibility = View.VISIBLE; micro.isEnabled = false; statut.text = "Lisa écrit…"
        val app = Api.appDe(pkg)
        Thread {
            val r = Api.envoyer(this, f, app)
            ui.post {
                occupe = false; micro.isEnabled = true
                when {
                    r.erreur != null -> etatRepos("⚠️ ${r.erreur}")
                    r.tache != null -> etatRepos("✅ Tâche ajoutée : ${r.tache}")
                    r.texte.isBlank() -> etatRepos("Je n'ai rien entendu")
                    else -> {
                        val t = if (r.texte.endsWith("\n")) r.texte else r.texte + " "
                        currentInputConnection?.commitText(t, 1)
                        etatRepos("Touche le micro et parle")
                    }
                }
            }
        }.start()
    }

    private fun envoyerEntree() {
        val ic = currentInputConnection ?: return
        val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) ic.performEditorAction(action)
        else { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)) }
    }
}
