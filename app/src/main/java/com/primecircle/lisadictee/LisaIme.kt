package com.primecircle.lisadictee

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Le clavier Lisa : clavier suisse romand QWERTZ complet, et en haut le micro.
 * Micro → Lisa écoute (onde) → ✗ annule, ✓ écrit le texte propre à l'endroit du curseur.
 */
class LisaIme : InputMethodService() {
    private lateinit var rec: Enregistreur
    private lateinit var onde: Onde
    private lateinit var anim: Animateur
    private lateinit var statut: TextView
    private lateinit var micro: MicroBouton
    private lateinit var annuler: BoutonRond
    private lateinit var valider: BoutonRond
    private lateinit var bandeauEcoute: LinearLayout
    private lateinit var touches: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var pkg: String? = null
    private var occupe = false
    private var maj = 0           // 0 minuscules, 1 une majuscule, 2 verrouillé
    private var chiffres = false
    private var dernierMaj = 0L

    private val LETTRES = listOf("qwertzuiopè", "asdfghjkléà", "yxcvbnm'")
    private val SYMBOLES = listOf("1234567890", "@#€&-+()/:", ";!?\"%*_=")
    private val ACCENTS = mapOf('e' to "ê", 'a' to "â", 'u' to "ü", 'o' to "ö", 'i' to "î", 'c' to "ç", 'è' to "ë", 'à' to "ä", 'é' to "è", 'n' to "ñ", '\'' to "’")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fond(c: String, r: Int = 9) = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = dp(r).toFloat() }

    override fun onCreateInputView(): View {
        rec = Enregistreur(this)
        onde = Onde(this)
        anim = Animateur(rec, onde)
        val racine = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#141210")); setPadding(dp(4), dp(4), dp(4), dp(6)) }

        // Bandeau du haut : texte d'état + micro, ou ✗ onde ✓ pendant l'écoute
        val haut = FrameLayout(this)
        val repos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statut = TextView(this).apply { setTextColor(Color.parseColor("#B8B1A8")); textSize = 13f; setPadding(dp(10), 0, 0, 0); text = "Touche le micro et parle" }
        micro = MicroBouton(this)
        repos.addView(statut, LinearLayout.LayoutParams(0, -1, 1f))
        repos.addView(micro, LinearLayout.LayoutParams(dp(58), dp(58)))
        bandeauEcoute = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        annuler = BoutonRond(this, false); valider = BoutonRond(this, true)
        bandeauEcoute.addView(annuler, LinearLayout.LayoutParams(dp(50), dp(50)))
        bandeauEcoute.addView(onde, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(10); marginEnd = dp(10) })
        bandeauEcoute.addView(valider, LinearLayout.LayoutParams(dp(50), dp(50)))
        haut.addView(repos, FrameLayout.LayoutParams(-1, dp(58)))
        haut.addView(bandeauEcoute, FrameLayout.LayoutParams(-1, dp(58)))
        racine.addView(haut, LinearLayout.LayoutParams(-1, dp(60)))

        touches = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        racine.addView(touches, LinearLayout.LayoutParams(-1, -2))
        dessinerTouches()

        micro.setOnClickListener { commencer() }
        annuler.setOnClickListener { stopEcoute(); etatRepos("Annulé") }
        valider.setOnClickListener { terminer() }
        return racine
    }

    // ---------- clavier ----------
    private fun touche(label: String, poids: Float, couleur: String = "#2C2925", taille: Float = 21f, action: () -> Unit, appuiLong: (() -> Unit)? = null): TextView =
        TextView(this).apply {
            text = label; textSize = taille; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = fond(couleur)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), poids).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); action() }
            if (appuiLong != null) setOnLongClickListener { appuiLong(); true }
        }

    private fun ligne(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }

    private fun dessinerTouches() {
        touches.removeAllViews()
        val rangs = if (chiffres) SYMBOLES else LETTRES
        for ((i, r) in rangs.withIndex()) {
            val l = ligne()
            if (i == 2) {
                if (chiffres) l.addView(touche("ABC", 1.5f, "#3A3530", 15f, { chiffres = false; dessinerTouches() }))
                else l.addView(touche(if (maj == 2) "⇪" else "⇧", 1.5f, if (maj > 0) "#F0674B" else "#3A3530", 20f, { basculerMaj() }))
            }
            for (ch in r) {
                val txt = if (!chiffres && maj > 0) ch.uppercaseChar().toString() else ch.toString()
                l.addView(touche(txt, 1f, action = { taper(txt) }, appuiLong = ACCENTS[ch.lowercaseChar()]?.let { a -> { taper(if (maj > 0) a.uppercase() else a) } }))
            }
            if (i == 2) l.addView(touche("⌫", 1.5f, "#3A3530", 19f, { effacer(1) }, { effacer(30) }))
            touches.addView(l, LinearLayout.LayoutParams(-1, -2).apply { if (i == 1 && !chiffres) setMargins(dp(0), 0, dp(0), 0) })
        }
        val bas = ligne()
        bas.addView(touche(if (chiffres) "ABC" else "?123", 1.4f, "#3A3530", 14f, { chiffres = !chiffres; dessinerTouches() }))
        bas.addView(touche("🌐", 1f, "#3A3530", 17f, { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }))
        bas.addView(touche(",", 1f, action = { taper(",") }))
        bas.addView(touche("espace", 4f, taille = 14f, action = { taper(" ") }))
        bas.addView(touche(".", 1f, action = { taper(".") }, appuiLong = { taper("…") }))
        bas.addView(touche("↵", 1.4f, "#F0674B", 19f, { entree() }))
        touches.addView(bas)
    }

    private fun basculerMaj() {
        val maintenant = System.currentTimeMillis()
        maj = if (maj == 0) (if (maintenant - dernierMaj < 350) 2 else 1) else 0
        if (maj == 1) dernierMaj = maintenant
        dessinerTouches()
    }

    private fun taper(t: String) {
        currentInputConnection?.commitText(t, 1)
        if (maj == 1) { maj = 0; dessinerTouches() }
    }

    private fun effacer(n: Int) {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(n, 0)
    }

    private fun entree() {
        val ic = currentInputConnection ?: return
        val opt = currentInputEditorInfo?.imeOptions ?: 0
        val action = opt and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && opt and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) ic.performEditorAction(action)
        else { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)) }
    }

    // ---------- dictée ----------
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        pkg = info?.packageName
        val cap = (info?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0
        val avant = currentInputConnection?.getTextBeforeCursor(2, 0)?.toString() ?: ""
        if (maj != 2) { maj = if (cap && (avant.isBlank() || avant.trimEnd().endsWith(".") || avant.trimEnd().endsWith("?") || avant.trimEnd().endsWith("!"))) 1 else 0; if (::touches.isInitialized) dessinerTouches() }
        if (!occupe && ::statut.isInitialized) etatRepos("Touche le micro et parle")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (::rec.isInitialized && !occupe) { stopEcoute(); etatRepos("Touche le micro et parle") }
        super.onFinishInputView(finishingInput)
    }

    private fun etatRepos(t: String) {
        bandeauEcoute.visibility = View.GONE; micro.visibility = View.VISIBLE; micro.actif = false; statut.text = t
        valider.attendre(false); touches.alpha = 1f
    }

    private fun commencer() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { statut.text = "Ouvre l'appli Lisa Dictée pour autoriser le micro"; return }
        if (!rec.demarrer()) { statut.text = "Micro indisponible"; return }
        micro.visibility = View.GONE; bandeauEcoute.visibility = View.VISIBLE; touches.alpha = 0.35f; anim.lancer()
    }

    private fun stopEcoute(): Direct? { anim.stop(); return rec.arreter(false) }

    private fun terminer() {
        anim.stop()
        val f = rec.arreter(true) ?: run { etatRepos("Trop court, recommence"); return }
        occupe = true; valider.attendre(true); annuler.isEnabled = false
        val app = Api.appDe(pkg)
        Thread {
            val r = f.resultat(app)
            ui.post {
                occupe = false; annuler.isEnabled = true
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
}
