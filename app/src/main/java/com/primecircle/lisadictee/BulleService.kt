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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
                    r.tache != null -> Toast.makeText(this, "✅ ${r.tache}", Toast.LENGTH_LONG).show()
                    r.texte.isBlank() -> Toast.makeText(this, "Je n'ai rien entendu", Toast.LENGTH_SHORT).show()
                    else -> carte(boite, micro, r.texte.trim(), app)
                }
            }
        }.start()
    }

    /**
     * La carte après la dictée : le texte en français, modifiable au doigt avec n'importe quel clavier,
     * un micro pour redicter un morceau à l'endroit du curseur, les langues en pastilles, l'aperçu de la traduction
     * (lui aussi modifiable), puis « Coller ». La carte s'ouvre toujours sur le français : la traduction ne part que si on touche une langue.
     */
    private fun carte(boite: LinearLayout, micro: MicroBouton, texteInitial: String, app: String) {
        occupe = true
        boite.removeAllViews()
        boite.background = GradientDrawable().apply { setColor(Color.parseColor("#F2141210")); cornerRadius = dp(22).toFloat() }
        val largeur = (resources.displayMetrics.widthPixels * 0.9).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        fun etiquette(t: String) = TextView(this).apply { text = t; textSize = 11f; setTextColor(Color.parseColor("#8E877E")); letterSpacing = 0.08f }
        fun champ(t: String, couleur: String, taille: Float, lignes: Int) = EditText(this).apply {
            setText(t); textSize = taille; setTextColor(Color.parseColor(couleur)); setLineSpacing(0f, 1.15f)
            background = rond("#1E1B18", 12); setPadding(dp(10), dp(8), dp(10), dp(8)); gravity = Gravity.TOP or Gravity.START
            maxLines = lignes; isVerticalScrollBarEnabled = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false); highlightColor = Color.parseColor("#66F0674B")
        }

        // Mode saisie : la carte prend le clavier (n'importe lequel) le temps de corriger, puis le rend à l'appli
        var saisie = false
        fun modeSaisie(oui: Boolean) {
            val p = lp ?: return
            if (oui == saisie) return
            saisie = oui
            p.flags = if (oui) (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                      else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            p.gravity = if (oui) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER; p.x = 0; p.y = if (oui) dp(36) else 0
            try { wm.updateViewLayout(boite, p) } catch (_: Exception) { }
        }

        // Titre + micro pour redicter
        val tete = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tete.addView(etiquette("FRANÇAIS · touche le texte pour corriger"), LinearLayout.LayoutParams(0, -2, 1f))
        val redire = TextView(this).apply { text = "🎙 Redicter"; textSize = 12f; setTextColor(Color.WHITE); background = rond("#2C2925"); setPadding(dp(10), dp(5), dp(10), dp(5)) }
        tete.addView(redire)
        col.addView(tete)
        val fr = champ(texteInitial, "#E9E3DB", 15f, 6)
        col.addView(fr, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // Pastilles de langues
        val rangee = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val defil = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(rangee) }
        col.addView(defil, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val titreTrad = etiquette("")
        val trad = champ("", "#FFFFFF", 16f, 8)
        val zoneTrad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        zoneTrad.addView(titreTrad)
        zoneTrad.addView(trad, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
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

        // 30/10 : la carte s'ouvre toujours sur le français (le texte dicté). On ne traduit que si Albano touche une langue.
        var choisie = "fr"
        val cache = HashMap<String, String>()
        var demande = 0
        var remplissage = false
        val pastilles = HashMap<String, TextView>()
        fun pret(oui: Boolean) { collerB.alpha = if (oui) 1f else 0.4f; collerB.isEnabled = oui }

        fun peindre() {
            for ((code, v) in pastilles) {
                val actif = code == choisie
                v.background = if (actif) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF8A5B"), Color.parseColor("#E8473A"))).apply { cornerRadius = dp(999).toFloat() } else rond("#2C2925")
                v.setTextColor(if (actif) Color.WHITE else Color.parseColor("#D9D3CB"))
            }
        }
        fun choisir(code: String) {
            choisie = code; Prefs.setCible(this, code); peindre()
            if (code == "fr") { zoneTrad.visibility = View.GONE; pret(true); return }
            zoneTrad.visibility = View.VISIBLE
            titreTrad.text = libelleLangue(code).substringAfter(" ").uppercase() + " · modifiable aussi"
            val source = fr.text.toString().trim()
            cache[code]?.let { remplissage = true; trad.setText(it); remplissage = false; trad.alpha = 1f; pret(true); return }
            if (source.isBlank()) { remplissage = true; trad.setText(""); remplissage = false; pret(false); return }
            remplissage = true; trad.setText("Lisa traduit…"); remplissage = false; trad.alpha = 0.5f; pret(false)
            val n = ++demande
            Thread {
                val r = Api.traduire(this, source, code, app)
                ui.post {
                    if (n != demande || vue == null) return@post
                    remplissage = true
                    if (r.erreur != null || r.texte.isBlank()) { trad.setText("⚠️ ${r.erreur ?: "Traduction vide"}. Touche la langue pour réessayer."); trad.alpha = 1f; remplissage = false; return@post }
                    cache[code] = r.texte.trim(); trad.setText(r.texte.trim()); trad.alpha = 1f; remplissage = false; pret(true)
                }
            }.start()
        }
        for ((code, lib) in LANGUES_BULLE) {
            val v = TextView(this).apply { text = lib; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7)); setOnClickListener { cache.remove(code); choisir(code) } }
            pastilles[code] = v
            rangee.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }

        // Toucher un texte = on passe en saisie et le clavier s'ouvre
        for (e in listOf(fr, trad)) {
            e.setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_UP && !saisie) { modeSaisie(true); v.post { v.requestFocus(); imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) } }
                false
            }
        }
        // Le français a changé : la traduction se refait après une petite pause
        val retraduire = Runnable { cache.clear(); if (choisie != "fr") choisir(choisie) }
        fr.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                ui.removeCallbacks(retraduire)
                if (choisie != "fr") { demande++; pret(false); ui.postDelayed(retraduire, 900) }
            }
        })
        trad.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { if (!remplissage && choisie != "fr") cache[choisie] = s.toString() }
        })

        // Redicter : Lisa écoute, puis insère (ou remplace la sélection) là où est le curseur
        var ecouteCarte = false
        redire.setOnClickListener {
            if (!ecouteCarte) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@setOnClickListener
                try { startForegroundService(Intent(this, MicService::class.java)) } catch (_: Exception) { }
                ui.postDelayed({
                    if (!rec.demarrer()) { Toast.makeText(this, "Micro indisponible", Toast.LENGTH_SHORT).show(); return@postDelayed }
                    ecouteCarte = true; ecoute = true
                    redire.text = "● Je t'écoute · touche pour finir"; redire.background = rond("#E8473A")
                }, 150)
            } else {
                ecouteCarte = false
                val prise = arreterEcoute(true)
                redire.text = "Lisa écrit…"; redire.background = rond("#2C2925"); redire.isEnabled = false
                val debut = maxOf(0, minOf(fr.selectionStart, fr.selectionEnd)).let { if (fr.hasFocus() || it > 0) it else fr.length() }
                val fin = maxOf(0, maxOf(fr.selectionStart, fr.selectionEnd)).let { if (fr.hasFocus() || it > 0) it else fr.length() }
                if (prise == null) { redire.text = "🎙 Redicter"; redire.isEnabled = true; return@setOnClickListener }
                Thread {
                    val r = prise.resultat(app)
                    ui.post {
                        redire.text = "🎙 Redicter"; redire.isEnabled = true
                        if (vue == null) return@post
                        if (r.erreur != null || r.texte.isBlank()) { Toast.makeText(this, r.erreur ?: "Je n'ai rien entendu", Toast.LENGTH_SHORT).show(); return@post }
                        val ed = fr.text
                        val a2 = debut.coerceIn(0, ed.length); val b2 = fin.coerceIn(a2, ed.length)
                        val avant = if (a2 > 0 && !ed[a2 - 1].isWhitespace()) " " else ""
                        val apres = if (b2 < ed.length && !ed[b2].isWhitespace()) " " else ""
                        val ins = avant + r.texte.trim() + apres
                        ed.replace(a2, b2, ins)
                        fr.setSelection((a2 + ins.length).coerceAtMost(fr.length()))
                    }
                }.start()
            }
        }

        val finir = {
            ui.removeCallbacks(retraduire)
            if (ecouteCarte) { ecouteCarte = false; arreterEcoute(false) }
            imm.hideSoftInputFromWindow(boite.windowToken, 0)
            modeSaisie(false)
            occupe = false; demande++; boite.background = null; remettre(boite, micro)
        }
        fermer.setOnClickListener { finir() }
        collerB.setOnClickListener {
            val t = (if (choisie == "fr") fr.text.toString() else trad.text.toString()).trim()
            val avaitClavier = saisie
            finir()
            // Après une correction, on laisse le temps au champ de l'appli de reprendre la main
            ui.postDelayed({ coller(t) }, if (avaitClavier) 450 else 80)
        }

        boite.addView(col, LinearLayout.LayoutParams(largeur, -2))
        lp?.let { it.x = 0; it.y = 0; it.gravity = Gravity.CENTER; try { wm.updateViewLayout(boite, it) } catch (_: Exception) { } }
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
