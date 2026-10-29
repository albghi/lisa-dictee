package com.primecircle.lisadictee

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * La bulle de Lisa : elle apparaît quand on touche une zone de texte, dans n'importe quelle appli.
 * Un appui = Lisa écoute (✗ annuler, ✓ valider). Au ✓, le texte propre est collé à l'endroit du curseur.
 */
class BulleService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private lateinit var rec: Enregistreur
    private val ui = Handler(Looper.getMainLooper())
    private var vue: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var onde: Onde? = null
    private var anim: Animateur? = null
    private var pkg: String? = null
    private var ecoute = false
    private var occupe = false
    private var posY = 0
    private val cacher = Runnable { if (!ecoute && !occupe) retirer() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rond(c: String, r: Int = 999) = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = dp(r).toFloat() }

    override fun onServiceConnected() { wm = getSystemService(WINDOW_SERVICE) as WindowManager; rec = Enregistreur(this) }
    override fun onInterrupt() {}
    override fun onDestroy() { arreterEcoute(false); retirer(); super.onDestroy() }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        if (e.packageName?.toString() == packageName) return
        val src = e.source
        val editable = src?.isEditable == true
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (editable) { pkg = e.packageName?.toString(); afficher() } else if (!ecoute && !occupe) ui.postDelayed(cacher, 600)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val f = try { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
                if (f?.isEditable == true) { pkg = e.packageName?.toString(); afficher() } else if (!ecoute && !occupe) ui.postDelayed(cacher, 600)
            }
        }
    }

    private fun afficher() {
        ui.removeCallbacks(cacher)
        if (vue != null) return
        val boite = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val micro = MicroBouton(this)
        boite.addView(micro, LinearLayout.LayoutParams(dp(60), dp(60)))
        val p = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        posY = Prefs.bulleY(this, resources.displayMetrics.heightPixels / 2); gauche = Prefs.bulleGauche(this)
        placer(p)
        // La bulle se déplace librement (haut, bas, gauche, droite), puis se range contre le bord le plus proche
        var x0 = 0; var y0 = 0; var tx = 0f; var ty = 0f; var bouge = false
        micro.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    val l = largeurEcran()
                    x0 = if (gauche) MARGE else l - boite.width - MARGE
                    y0 = p.y; tx = ev.rawX; ty = ev.rawY; bouge = false; false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - tx).toInt(); val dy = (ev.rawY - ty).toInt()
                    if (!bouge && (kotlin.math.abs(dx) > dp(8) || kotlin.math.abs(dy) > dp(8))) { bouge = true; p.gravity = Gravity.TOP or Gravity.START }
                    if (bouge) { p.x = (x0 + dx).coerceIn(0, largeurEcran() - boite.width); p.y = borneY(y0 + dy); try { wm.updateViewLayout(boite, p) } catch (_: Exception) { } }
                    bouge
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (bouge) ranger(boite, p)
                    bouge
                }
                else -> bouge
            }
        }
        micro.setOnClickListener { if (!occupe) ecouter(boite, micro) }
        try { wm.addView(boite, p); vue = boite; lp = p } catch (_: Exception) { }
    }

    private val MARGE_DP = 6
    private val MARGE get() = dp(MARGE_DP)
    private var gauche = false
    private fun largeurEcran() = resources.displayMetrics.widthPixels
    private fun borneY(y: Int) = y.coerceIn(dp(30), resources.displayMetrics.heightPixels - dp(150))

    /** Place la bulle contre son bord (gauche ou droite) à la hauteur retenue. */
    private fun placer(p: WindowManager.LayoutParams) {
        p.gravity = Gravity.TOP or (if (gauche) Gravity.START else Gravity.END); p.x = MARGE; p.y = borneY(posY)
    }

    /** Après un glissement : glisse en douceur vers le bord le plus proche et retient la position. */
    private fun ranger(boite: LinearLayout, p: WindowManager.LayoutParams) {
        val l = largeurEcran()
        gauche = p.x + boite.width / 2 < l / 2
        posY = p.y
        Prefs.setBulle(this, gauche, posY)
        val cible = if (gauche) MARGE else l - boite.width - MARGE
        android.animation.ValueAnimator.ofInt(p.x, cible).apply {
            duration = 220; interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { p.x = it.animatedValue as Int; try { wm.updateViewLayout(boite, p) } catch (_: Exception) { } }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { placer(p); try { wm.updateViewLayout(boite, p) } catch (_: Exception) { } }
            })
            start()
        }
    }

    private fun retirer() { vue?.let { try { wm.removeView(it) } catch (_: Exception) { } }; vue = null; onde = null }

    private fun ecouter(boite: LinearLayout, micro: MicroBouton) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Ouvre l'appli Lisa Dictée pour autoriser le micro", Toast.LENGTH_LONG).show(); return
        }
        try { startForegroundService(Intent(this, MicService::class.java)) } catch (_: Exception) { }
        ui.postDelayed({
            if (!rec.demarrer()) { stopService(Intent(this, MicService::class.java)); Toast.makeText(this, "Micro indisponible", Toast.LENGTH_SHORT).show(); return@postDelayed }
            ecoute = true
            boite.removeAllViews()
            boite.background = GradientDrawable().apply { setColor(Color.parseColor("#EE141210")); cornerRadius = dp(40).toFloat() }
            val annuler = BoutonRond(this, false)
            val valider = BoutonRond(this, true)
            val o = Onde(this); onde = o
            boite.addView(annuler, LinearLayout.LayoutParams(dp(50), dp(50)))
            boite.addView(o, LinearLayout.LayoutParams(dp(130), dp(36)).apply { marginStart = dp(6); marginEnd = dp(6) })
            boite.addView(valider, LinearLayout.LayoutParams(dp(50), dp(50)))
            anim = Animateur(rec, o).also { it.lancer() }
            annuler.setOnClickListener { arreterEcoute(false); remettre(boite, micro) }
            valider.setOnClickListener { terminer(boite, micro, valider, annuler) }
        }, 150)
    }

    private fun arreterEcoute(garder: Boolean): Direct? {
        anim?.stop(); anim = null; ecoute = false
        val f = if (::rec.isInitialized) rec.arreter(garder) else null
        try { stopService(Intent(this, MicService::class.java)) } catch (_: Exception) { }
        return f
    }

    private fun remettre(boite: LinearLayout, micro: MicroBouton) {
        boite.removeAllViews(); boite.background = null; boite.addView(micro, LinearLayout.LayoutParams(dp(60), dp(60)))
        lp?.let { placer(it); try { wm.updateViewLayout(boite, it) } catch (_: Exception) { } }
    }

    private fun terminer(boite: LinearLayout, micro: MicroBouton, valider: BoutonRond, annuler: BoutonRond) {
        val f = arreterEcoute(true) ?: run { remettre(boite, micro); return }
        occupe = true
        valider.attendre(true); annuler.isEnabled = false; onde?.vider()
        val app = Api.appDe(pkg)
        Thread {
            val r = f.resultat(app)
            ui.post {
                occupe = false
                valider.attendre(false)
                remettre(boite, micro)
                when {
                    r.erreur != null -> Toast.makeText(this, "⚠️ ${r.erreur}", Toast.LENGTH_LONG).show()
                    r.tache != null -> Toast.makeText(this, "✅ Tâche ajoutée : ${r.tache}", Toast.LENGTH_LONG).show()
                    r.texte.isBlank() -> Toast.makeText(this, "Je n'ai rien entendu", Toast.LENGTH_SHORT).show()
                    else -> carte(boite, micro, r.texte.trim(), app)
                }
            }
        }.start()
    }

    /**
     * La carte après la dictée : le texte en français, les langues en pastilles, l'aperçu de la traduction,
     * puis « Coller ». La dernière langue choisie est retenue et traduite tout de suite.
     */
    private fun carte(boite: LinearLayout, micro: MicroBouton, francais: String, app: String) {
        occupe = true
        boite.removeAllViews()
        boite.background = GradientDrawable().apply { setColor(Color.parseColor("#F2141210")); cornerRadius = dp(22).toFloat() }
        val largeur = (resources.displayMetrics.widthPixels * 0.86).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        fun etiquette(t: String) = TextView(this).apply { text = t; textSize = 11f; setTextColor(Color.parseColor("#8E877E")); letterSpacing = 0.08f }
        fun bloc(t: String, couleur: String) = TextView(this).apply { text = t; textSize = 15f; setTextColor(Color.parseColor(couleur)); setLineSpacing(0f, 1.15f); setTextIsSelectable(false) }

        col.addView(etiquette("FRANÇAIS"))
        val fr = bloc(francais, "#D9D3CB")
        fr.maxLines = 5; fr.movementMethod = android.text.method.ScrollingMovementMethod()
        col.addView(fr, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // Pastilles de langues
        val rangee = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val defil = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(rangee) }
        col.addView(defil, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val titreTrad = etiquette("")
        val trad = bloc("", "#FFFFFF").apply { textSize = 16f }
        val zoneTrad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        zoneTrad.addView(titreTrad)
        trad.maxLines = 8; trad.movementMethod = android.text.method.ScrollingMovementMethod()
        zoneTrad.addView(trad, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        col.addView(zoneTrad, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // Boutons
        val bas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val fermer = TextView(this).apply { text = "Fermer"; textSize = 14f; setTextColor(Color.parseColor("#B8B1A8")); gravity = Gravity.CENTER; background = rond("#2C2925"); setPadding(dp(16), dp(10), dp(16), dp(10)) }
        val collerB = TextView(this).apply { text = "Coller"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF8A5B"), Color.parseColor("#E8473A"))).apply { cornerRadius = dp(999).toFloat() } }
        bas.addView(fermer, LinearLayout.LayoutParams(0, -2, 1f))
        bas.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        bas.addView(collerB, LinearLayout.LayoutParams(0, -2, 1.4f))
        col.addView(bas, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        var choisie = Prefs.cible(this)
        val cache = HashMap<String, String>()
        var aColler = francais
        var demande = 0
        val pastilles = HashMap<String, TextView>()

        fun peindre() {
            for ((code, v) in pastilles) {
                val actif = code == choisie
                v.background = if (actif) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF8A5B"), Color.parseColor("#E8473A"))).apply { cornerRadius = dp(999).toFloat() } else rond("#2C2925")
                v.setTextColor(if (actif) Color.WHITE else Color.parseColor("#D9D3CB"))
            }
        }
        fun choisir(code: String) {
            choisie = code; Prefs.setCible(this, code); peindre()
            if (code == "fr") { zoneTrad.visibility = View.GONE; aColler = francais; collerB.alpha = 1f; collerB.isEnabled = true; return }
            zoneTrad.visibility = View.VISIBLE
            titreTrad.text = libelleLangue(code).substringAfter(" ").uppercase()
            cache[code]?.let { trad.text = it; trad.alpha = 1f; aColler = it; collerB.alpha = 1f; collerB.isEnabled = true; return }
            trad.text = "Lisa traduit…"; trad.alpha = 0.5f; collerB.alpha = 0.4f; collerB.isEnabled = false
            val n = ++demande
            Thread {
                val r = Api.traduire(this, francais, code, app)
                ui.post {
                    if (n != demande || vue == null) return@post
                    if (r.erreur != null || r.texte.isBlank()) { trad.text = "⚠️ ${r.erreur ?: "Traduction vide"}. Touche la langue pour réessayer."; trad.alpha = 1f; return@post }
                    cache[code] = r.texte.trim(); trad.text = r.texte.trim(); trad.alpha = 1f; aColler = r.texte.trim(); collerB.alpha = 1f; collerB.isEnabled = true
                }
            }.start()
        }
        for ((code, lib) in LANGUES_BULLE) {
            val v = TextView(this).apply { text = lib; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7)); setOnClickListener { choisir(code) } }
            pastilles[code] = v
            rangee.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }

        val finir = { occupe = false; demande++; boite.background = null; remettre(boite, micro) }
        fermer.setOnClickListener { finir() }
        collerB.setOnClickListener { val t = aColler; finir(); ui.postDelayed({ coller(t) }, 80) }

        boite.addView(col, LinearLayout.LayoutParams(largeur, -2))
        lp?.let { it.y = 0; it.gravity = Gravity.CENTER; try { wm.updateViewLayout(boite, it) } catch (_: Exception) { } }
        choisir(choisie)
    }

    /** Colle à l'endroit du curseur ; à défaut, ajoute le texte au champ ; à défaut, le laisse dans le presse papiers. */
    private fun coller(texte: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Lisa", texte))
        val champ = try { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
        if (champ != null && champ.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return
        if (champ != null && champ.isEditable) {
            val avant = champ.text?.toString() ?: ""
            val a = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, if (avant.isBlank()) texte else "$avant $texte") }
            if (champ.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a)) return
        }
        Toast.makeText(this, "Texte copié : appuie longuement dans le champ puis Coller", Toast.LENGTH_LONG).show()
    }
}
