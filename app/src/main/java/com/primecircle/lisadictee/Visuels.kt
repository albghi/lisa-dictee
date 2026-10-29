package com.primecircle.lisadictee

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private fun dpx(c: Context, v: Float) = v * c.resources.displayMetrics.density

/** Dessine un micro blanc en trait fin, bien proportionné (capsule, arceau, pied, socle). */
fun dessinerMicro(c: Canvas, cx: Float, cy: Float, h: Float, p: Paint) {
    val w = h * 0.36f
    val capsule = RectF(cx - w / 2, cy - h * 0.5f, cx + w / 2, cy + h * 0.12f)
    c.drawRoundRect(capsule, w / 2, w / 2, p)
    val arc = RectF(cx - w * 0.95f, cy - h * 0.18f, cx + w * 0.95f, cy + h * 0.30f)
    c.drawArc(arc, 10f, 160f, false, p)
    c.drawLine(cx, cy + h * 0.30f, cx, cy + h * 0.46f, p)
    c.drawLine(cx - w * 0.55f, cy + h * 0.46f, cx + w * 0.55f, cy + h * 0.46f, p)
}

/** Le bouton micro : disque en dégradé corail, ombre douce, micro blanc fin et halo qui respire pendant l'écoute. */
class MicroBouton(ctx: Context) : View(ctx) {
    private val disque = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ombre = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    var actif = false; set(v) { field = v; if (v) anim.start() else { anim.cancel(); phase = 0f }; invalidate() }
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 1400; repeatCount = ValueAnimator.INFINITE; addUpdateListener { phase = it.animatedValue as Float; invalidate() } }
    init { setLayerType(LAYER_TYPE_SOFTWARE, null); isClickable = true }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f * 0.72f
        if (actif) {
            val k = phase
            halo.strokeWidth = dpx(context, 2f); halo.color = Color.argb((90 * (1 - k)).toInt(), 240, 103, 75)
            c.drawCircle(cx, cy, r * (1f + 0.35f * k), halo)
        }
        ombre.color = Color.argb(70, 0, 0, 0); ombre.setShadowLayer(r * 0.35f, 0f, r * 0.12f, Color.argb(110, 0, 0, 0))
        c.drawCircle(cx, cy, r, ombre)
        disque.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, intArrayOf(Color.parseColor("#FF8A66"), Color.parseColor("#F0674B"), Color.parseColor("#D9442E")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, disque)
        // reflet discret en haut
        disque.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.45f, r * 0.9f, Color.argb(70, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, disque)
        trait.strokeWidth = max(dpx(context, 1.6f), r * 0.07f)
        dessinerMicro(c, cx, cy, r * 0.95f, trait)
    }
}

/** L'onde : barres fines et arrondies, mouvement lissé, couleur qui va du corail à l'ambre selon le volume. */
class Onde(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val n = 34
    private val cible = FloatArray(n)
    private val vu = FloatArray(n)
    private var t = 0f
    fun pousser(v: Float) { System.arraycopy(cible, 1, cible, 0, n - 1); cible[n - 1] = min(1f, v * 1.6f) }
    fun vider() { cible.fill(0f); vu.fill(0f); invalidate() }
    fun tick() { t += 0.12f; for (i in 0 until n) vu[i] += (cible[i] - vu[i]) * 0.35f; invalidate() }
    private fun couleur(v: Float): Int {
        val a = Color.parseColor("#F0674B"); val b = Color.parseColor("#FFC15E"); val k = min(1f, v * 1.3f)
        return Color.rgb((Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val pas = w / n; val bw = max(dpx(context, 2f), pas * 0.38f)
        for (i in 0 until n) {
            val repos = 0.08f + 0.04f * sin(t + i * 0.5f)
            val v = max(repos, vu[i]); val bh = (0.1f + v * 0.9f) * h
            val x = i * pas + (pas - bw) / 2
            p.color = couleur(vu[i]); p.alpha = (140 + 115 * min(1f, vu[i] * 2)).toInt()
            c.drawRoundRect(RectF(x, (h - bh) / 2, x + bw, (h + bh) / 2), bw, bw, p)
        }
    }
}

/** Fait vivre l'onde à 60 images par seconde pendant l'écoute. */
class Animateur(private val e: Enregistreur, private val o: Onde) {
    private val h = Handler(Looper.getMainLooper())
    private var k = 0
    private val r = object : Runnable { override fun run() { if (k++ % 4 == 0) o.pousser(e.niveau()); o.tick(); h.postDelayed(this, 16) } }
    fun lancer() { o.vider(); h.post(r) }
    fun stop() { h.removeCallbacks(r) }
}

/** Petit bouton rond dessiné : croix (annuler) ou validation. La validation se transforme en cercle qui se referme pendant que Lisa écrit. */
class BoutonRond(ctx: Context, private val valider: Boolean) : View(ctx) {
    private val fond = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.WHITE }
    private var attente = 0f; private var tourne = false
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = DecelerateInterpolator(); addUpdateListener { attente = it.animatedValue as Float; invalidate() } }
    init { isClickable = true }
    fun attendre(oui: Boolean) { tourne = oui; if (oui) anim.start() else { anim.cancel(); attente = 0f }; invalidate() }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f * 0.78f
        fond.color = if (valider) Color.parseColor("#F0674B") else Color.parseColor("#3A3530")
        if (valider && tourne) fond.alpha = 60
        c.drawCircle(cx, cy, r, fond)
        trait.strokeWidth = r * 0.12f
        if (valider && tourne) {
            trait.color = Color.parseColor("#F0674B")
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f + 360f * attente, 90f + 200f * attente, false, trait)
            trait.color = Color.WHITE; return
        }
        val s = r * 0.42f
        if (valider) {
            val pa = Path(); pa.moveTo(cx - s, cy + s * 0.05f); pa.lineTo(cx - s * 0.25f, cy + s * 0.7f); pa.lineTo(cx + s, cy - s * 0.6f); c.drawPath(pa, trait)
        } else {
            c.drawLine(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f, trait); c.drawLine(cx + s * 0.8f, cy - s * 0.8f, cx - s * 0.8f, cy + s * 0.8f, trait)
        }
    }
}
