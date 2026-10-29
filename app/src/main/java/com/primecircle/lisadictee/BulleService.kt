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
import android.widget.Button
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
        val boite = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = rond("#E6141210"); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        val micro = Button(this).apply { text = "🎙"; textSize = 20f; background = rond("#F0674B"); setTextColor(Color.WHITE) }
        boite.addView(micro, LinearLayout.LayoutParams(dp(52), dp(52)))
        val p = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.END or Gravity.CENTER_VERTICAL; p.x = dp(8); p.y = dp(60)
        // On peut déplacer la bulle en la faisant glisser
        var y0 = 0; var ty = 0f; var bouge = false
        micro.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { y0 = p.y; ty = ev.rawY; bouge = false; false }
                MotionEvent.ACTION_MOVE -> { val d = (ev.rawY - ty).toInt(); if (kotlin.math.abs(d) > dp(8)) { bouge = true; p.y = y0 + d; wm.updateViewLayout(boite, p) }; bouge }
                else -> bouge
            }
        }
        micro.setOnClickListener { if (!occupe) ecouter(boite, micro) }
        try { wm.addView(boite, p); vue = boite; lp = p } catch (_: Exception) { }
    }

    private fun retirer() { vue?.let { try { wm.removeView(it) } catch (_: Exception) { } }; vue = null; onde = null }

    private fun ecouter(boite: LinearLayout, micro: Button) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Ouvre l'appli Lisa Dictée pour autoriser le micro", Toast.LENGTH_LONG).show(); return
        }
        try { startForegroundService(Intent(this, MicService::class.java)) } catch (_: Exception) { }
        ui.postDelayed({
            if (!rec.demarrer()) { stopService(Intent(this, MicService::class.java)); Toast.makeText(this, "Micro indisponible", Toast.LENGTH_SHORT).show(); return@postDelayed }
            ecoute = true
            boite.removeAllViews()
            val annuler = Button(this).apply { text = "✗"; textSize = 20f; background = rond("#3A3530"); setTextColor(Color.WHITE) }
            val valider = Button(this).apply { text = "✓"; textSize = 20f; background = rond("#2E8B62"); setTextColor(Color.WHITE) }
            val o = Onde(this); onde = o
            boite.addView(annuler, LinearLayout.LayoutParams(dp(52), dp(52)))
            boite.addView(o, LinearLayout.LayoutParams(dp(120), dp(40)).apply { marginStart = dp(8); marginEnd = dp(8) })
            boite.addView(valider, LinearLayout.LayoutParams(dp(52), dp(52)))
            anim = Animateur(rec, o).also { it.lancer() }
            annuler.setOnClickListener { arreterEcoute(false); remettre(boite, micro) }
            valider.setOnClickListener { terminer(boite, micro) }
        }, 150)
    }

    private fun arreterEcoute(garder: Boolean): java.io.File? {
        anim?.stop(); anim = null; ecoute = false
        val f = if (::rec.isInitialized) rec.arreter(garder) else null
        try { stopService(Intent(this, MicService::class.java)) } catch (_: Exception) { }
        return f
    }

    private fun remettre(boite: LinearLayout, micro: Button) {
        boite.removeAllViews(); boite.addView(micro, LinearLayout.LayoutParams(dp(52), dp(52))); micro.text = "🎙"; micro.isEnabled = true
    }

    private fun terminer(boite: LinearLayout, micro: Button) {
        val f = arreterEcoute(true) ?: run { remettre(boite, micro); return }
        occupe = true
        boite.removeAllViews()
        val t = TextView(this).apply { text = "  Lisa écrit…  "; setTextColor(Color.WHITE); textSize = 14f }
        boite.addView(t)
        val app = Api.appDe(pkg)
        Thread {
            val r = Api.envoyer(this, f, app)
            ui.post {
                occupe = false
                remettre(boite, micro)
                when {
                    r.erreur != null -> Toast.makeText(this, "⚠️ ${r.erreur}", Toast.LENGTH_LONG).show()
                    r.tache != null -> Toast.makeText(this, "✅ Tâche ajoutée : ${r.tache}", Toast.LENGTH_LONG).show()
                    r.texte.isBlank() -> Toast.makeText(this, "Je n'ai rien entendu", Toast.LENGTH_SHORT).show()
                    else -> coller(r.texte)
                }
            }
        }.start()
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
