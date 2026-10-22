package com.whitedns.whiteaesther.ui

import androidx.core.content.res.ResourcesCompat
import androidx.annotation.FontRes

import android.animation.ArgbEvaluator
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
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * WhiteDNS 1.6.9 / WhiteVPN connection control, adapted to Aesther's four states.
 * Reference: https://github.com/WhiteDNS/WhiteDNS-Android/blob/1.6.9/
 * app/src/main/java/shop/whitedns/client/ui/WhiteDnsConnectionOrb.kt
 *
 * Compose owns input, accessibility and TV focus; this View only draws the orb.
 */
internal class ConnectionOrbView(
    context: Context,
    private val palette: ConnectionOrbColors,
    containerSizeDp: Float,
    @FontRes fontResource: Int,
) : View(context) {
    private var actionLabel = ""
    private val evaluator = ArgbEvaluator()

    // Dimensions
    private val containerSize = dp(containerSizeDp)
    private val orbDiameter = containerSize * (224f / 300f)
    private val orbRadius = orbDiameter / 2f

    // Paints
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, fontResource)
        textAlign = Paint.Align.CENTER
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val orbPath = Path()
    private val iconBounds = RectF()

    // State
    private var state: ConnectState = ConnectState.IDLE
    private var transitionProgress = 1f
    private var fromAccent = palette.neutral
    private var toAccent = palette.neutral
    private var fromGradientStart = palette.textSecondary
    private var fromGradientEnd = palette.neutral
    private var toGradientStart = fromGradientStart
    private var toGradientEnd = fromGradientEnd
    private var orbPressed = false

    // Animation phases (0-1, smooth looping)
    private var blobPhase = 0f           // 9s - gentle blob morphing
    private var swirlPhase = 0f          // 20s - inner mist rotation
    private var breathePhase = 0f        // 4s - outer glow pulsing
    private var corePhase = 0f           // 7s - core glow pulsing
    private var pressScale = 1f
    private var shadeOpacity = 0f

    // Animators
    private var transitionAnimator: ValueAnimator? = null
    private var masterAnimator: ValueAnimator? = null
    private var pressAnimator: ValueAnimator? = null
    private var shadeAnimator: ValueAnimator? = null

    // Particle system
    private data class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var size: Float, var life: Float, var maxLife: Float,
        var alpha: Float, var color: Int, var slow: Boolean,
        // For circular motion (connecting state)
        var isCircular: Boolean = false,
        var angle: Float = 0f,
        var radius: Float = 0f,
        var angularSpeed: Float = 0f
    )

    private val particles = mutableListOf<Particle>()
    private var lastParticleTime = 0L
    private var lastFrameTime = 0L

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var isPaused = true

    fun setConnectionState(newState: ConnectState, label: String) {
        if (actionLabel != label) { actionLabel = label; invalidate() }
        if (state == newState) return
        fromAccent = currentAccent()
        fromGradientStart = currentGradientStart()
        fromGradientEnd = currentGradientEnd()
        state = newState
        toAccent = stateAccent(newState)
        stateGradient(newState).let { (start, end) ->
            toGradientStart = start
            toGradientEnd = end
        }

        transitionAnimator?.cancel()
        if (!isPaused && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
            transitionProgress = 0f
            transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420L
                interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
                addUpdateListener {
                    transitionProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            transitionProgress = 1f
        }
        invalidate()
    }

    /**
     * Call when app goes to background to stop animations and save battery.
     */
    fun pauseAnimation() {
        isPaused = true
        cancelAllAnimations()
    }

    /**
     * Call when app comes to foreground to resume animations.
     */
    fun resumeAnimation() {
        isPaused = false
        lastFrameTime = 0L
        startMasterAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startMasterAnimation()
    }

    override fun onDetachedFromWindow() {
        cancelAllAnimations()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && !isPaused) startMasterAnimation() else cancelAllAnimations()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = containerSize.toInt()
        setMeasuredDimension(
            resolveSize(size, widthMeasureSpec),
            resolveSize(size, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (!isPaused && ValueAnimator.areAnimatorsEnabled()) updateParticles()

        val cx = width / 2f
        val cy = height / 2f

        canvas.save()
        canvas.scale(pressScale, pressScale, cx, cy)
        if (orbPressed) {
            canvas.translate(0f, dp(3f) * (1f - pressScale) / 0.06f)
        }

        // 1. Draw particles behind orb
        drawParticles(canvas)

        // 2. Draw outer breathing glow
        drawOuterGlow(canvas, cx, cy)

        // 4. Build smooth blob path
        buildSmoothBlobPath(cx, cy)

        // 5. Draw main orb with gradient
        drawOrbBase(canvas, cx, cy)

        // 6. Draw inner effects (highlights, shadows, mist)
        drawOrbEffects(canvas, cx, cy)

        // 7. Draw press shade overlay
        if (shadeOpacity > 0f) {
            drawPressShade(canvas, cx, cy)
        }

        // 8. Draw icon and label
        drawIcon(canvas, cx, cy - dp(6f))
        drawLabel(canvas, cx, cy + dp(36f))

        canvas.restore()

    }

    fun setOrbPressed(pressed: Boolean) {
        if (orbPressed == pressed) return
        orbPressed = pressed
        if (pressed) {
            animatePress(0.94f)
            animateShade(1f)
            if (!isPaused && ValueAnimator.areAnimatorsEnabled()) emitDustBurst()
        } else {
            animatePressRelease()
            animateShade(0f)
        }
    }

    /**
     * Build a smooth organic blob using sine wave modulation.
     * This creates gentle, smooth curves without sharp points.
     * All phase multipliers are integers to ensure perfect looping.
     */
    private fun buildSmoothBlobPath(cx: Float, cy: Float) {
        orbPath.reset()

        val baseRadius = orbRadius
        val segments = 120  // More segments = smoother curve
        val phase = blobPhase * 2f * PI.toFloat()

        // Use multiple low-frequency sine waves for smooth organic shape
        // All phase multipliers are integers (1, 2, 3) for seamless looping
        for (i in 0..segments) {
            val angle = (i.toFloat() / segments) * 2f * PI.toFloat()

            // Combine multiple sine waves - all coefficients are integers for perfect loop
            val wave1 = sin(angle * 2f + phase) * dp(4f)
            val wave2 = sin(angle * 3f - phase) * dp(3f)
            val wave3 = cos(angle * 2f + phase * 2f) * dp(2.5f)

            val r = baseRadius + wave1 + wave2 + wave3
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)

            if (i == 0) {
                orbPath.moveTo(x, y)
            } else {
                orbPath.lineTo(x, y)
            }
        }
        orbPath.close()
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in particles) {
            val t = p.life / p.maxLife
            val alpha = if (p.slow) {
                (p.alpha * min(1f, t * 5f) * (1f - t) * 255).toInt()
            } else {
                (p.alpha * (1f - t).pow(1.5f) * 255).toInt()
            }
            if (alpha <= 0) continue

            particlePaint.color = withAlpha(p.color, alpha)
            canvas.drawCircle(p.x, p.y, p.size / 2f, particlePaint)
        }
    }

    private fun drawOuterGlow(canvas: Canvas, cx: Float, cy: Float) {
        val phase = sin(breathePhase * 2f * PI.toFloat())
        val scale = 1f + 0.08f * phase
        val opacity = 0.7f + 0.3f * phase
        val accent = currentAccent()
        val outerGlowRadius = orbRadius * 1.6f * scale
        glowPaint.shader = RadialGradient(
            cx, cy, outerGlowRadius,
            intArrayOf(
                withAlpha(accent, (0.36f * opacity * 255).toInt()),
                withAlpha(accent, (0.16f * opacity * 255).toInt()),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, outerGlowRadius, glowPaint)
    }

    private fun drawOrbBase(canvas: Canvas, cx: Float, cy: Float) {
        val gradientAngle = 160f * PI.toFloat() / 180f
        val gradientLength = orbRadius * 2.2f
        orbPaint.shader = LinearGradient(
            cx - cos(gradientAngle) * gradientLength / 2,
            cy - sin(gradientAngle) * gradientLength / 2 - orbRadius * 0.3f,
            cx + cos(gradientAngle) * gradientLength / 2,
            cy + sin(gradientAngle) * gradientLength / 2 + orbRadius * 0.3f,
            currentGradientStart(),
            currentGradientEnd(),
            Shader.TileMode.CLAMP,
        )

        canvas.drawPath(orbPath, orbPaint)
    }

    private fun drawOrbEffects(canvas: Canvas, cx: Float, cy: Float) {
        canvas.save()
        canvas.clipPath(orbPath)

        // 1. Inner swirling mist (subtle rotating gradient)
        drawInnerMist(canvas, cx, cy)

        // 2. Core glow (breathing center light)
        drawCoreGlow(canvas, cx, cy)

        // 3. Edge darkening (rim shadow)
        drawEdgeShadow(canvas, cx, cy)

        // 4. Top highlight (specular reflection)
        drawTopHighlight(canvas, cx, cy)

        // 5. Bottom reflection
        drawBottomReflection(canvas, cx, cy)

        canvas.restore()
    }

    private fun drawInnerMist(canvas: Canvas, cx: Float, cy: Float) {
        val rotation = swirlPhase * 360f
        val accent = currentAccent()

        canvas.save()
        canvas.rotate(rotation, cx, cy)

        // Soft radial gradient spots that rotate
        overlayPaint.shader = RadialGradient(
            cx - orbRadius * 0.25f, cy - orbRadius * 0.2f, orbRadius * 0.5f,
            intArrayOf(
                withAlpha(accent, 45),
                withAlpha(accent, 20),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, orbRadius, overlayPaint)

        canvas.restore()
    }

    private fun drawCoreGlow(canvas: Canvas, cx: Float, cy: Float) {
        val phase = sin(corePhase * 2f * PI.toFloat())
        val scale = 0.92f + 0.12f * phase
        val opacity = 0.35f + 0.35f * phase
        val accent = currentAccent()
        val highlight = evaluator.evaluate(0.65f, accent, palette.onStateFill) as Int

        val coreRadius = orbRadius * 0.6f * scale
        overlayPaint.shader = RadialGradient(
            cx, cy, coreRadius,
            intArrayOf(
                withAlpha(highlight, (opacity * 180).toInt()),
                withAlpha(accent, (opacity * 80).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius, overlayPaint)
    }

    private fun drawEdgeShadow(canvas: Canvas, cx: Float, cy: Float) {
        val shadowTarget = if (palette.isDark) palette.background else palette.textPrimary
        val shadow = evaluator.evaluate(0.55f, currentAccent(), shadowTarget) as Int
        // Edge shadow now follows the blob path since we're already clipped to orbPath
        overlayPaint.shader = RadialGradient(
            cx, cy, orbRadius * 1.1f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                withAlpha(shadow, 30),
                withAlpha(shadow, 80)
            ),
            floatArrayOf(0f, 0.7f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )
        // Draw on the clipped area (orbPath) not a fixed circle
        canvas.drawPaint(overlayPaint)
    }

    private fun drawTopHighlight(canvas: Canvas, cx: Float, cy: Float) {
        val highlightX = cx - orbRadius * 0.2f
        val highlightY = cy - orbRadius * 0.35f
        overlayPaint.shader = RadialGradient(
            highlightX, highlightY, orbRadius * 0.55f,
            intArrayOf(
                withAlpha(palette.onStateFill, 60),
                withAlpha(palette.onStateFill, 30),
                withAlpha(palette.onStateFill, 10),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.3f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, orbRadius, overlayPaint)
    }

    private fun drawBottomReflection(canvas: Canvas, cx: Float, cy: Float) {
        val accent = currentAccent()
        overlayPaint.shader = RadialGradient(
            cx, cy + orbRadius * 0.5f, orbRadius * 0.7f,
            intArrayOf(
                withAlpha(accent, 50),
                withAlpha(accent, 25),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, orbRadius, overlayPaint)
    }

    private fun drawPressShade(canvas: Canvas, cx: Float, cy: Float) {
        val shade = if (palette.isDark) palette.background else palette.textPrimary
        overlayPaint.shader = RadialGradient(
            cx, cy + orbRadius * 0.1f, orbRadius,
            intArrayOf(
                withAlpha(shade, (0.25f * shadeOpacity * 255).toInt()),
                withAlpha(shade, (0.10f * shadeOpacity * 255).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(orbPath, overlayPaint)
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float) {
        val isConnected = state == ConnectState.LIVE

        iconPaint.color = currentForeground()

        val size = dp(14f)

        when {
            isConnected -> {
                // Disconnect icon: power with slash
                iconBounds.set(cx - size, cy - size * 0.7f, cx + size, cy + size * 1.3f)
                canvas.drawArc(iconBounds, -55f, 290f, false, iconPaint)
                canvas.drawLine(cx, cy - size * 1.3f, cx, cy + dp(2f), iconPaint)
                canvas.drawLine(cx - size * 1.1f, cy + size * 1.1f, cx + size * 1.1f, cy - size * 1.1f, iconPaint)
            }
            else -> {
                // Power icon
                iconBounds.set(cx - size, cy - size * 0.7f, cx + size, cy + size * 1.3f)
                canvas.drawArc(iconBounds, -55f, 290f, false, iconPaint)
                canvas.drawLine(cx, cy - size * 1.3f, cx, cy + dp(2f), iconPaint)
            }
        }
    }

    private fun drawLabel(canvas: Canvas, cx: Float, cy: Float) {
        val label = actionLabel

        labelPaint.textSize = sp(15f)
        // Keep even large accessibility text inside the orb without clipping.
        val availableWidth = orbDiameter * 0.78f
        val measuredWidth = labelPaint.measureText(label)
        if (measuredWidth > availableWidth) {
            labelPaint.textSize *= availableWidth / measuredWidth
        }
        labelPaint.color = currentForeground()

        val metrics = labelPaint.fontMetrics
        val textY = cy - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, cx, textY, labelPaint)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Particle System
    // ═══════════════════════════════════════════════════════════════════════════

    private fun updateParticles() {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameTime > 0) min((now - lastFrameTime).toFloat(), 40f) else 16.67f
        lastFrameTime = now

        val cx = width / 2f
        val cy = height / 2f

        // Emit circular particles when connecting/disconnecting (slower rate for performance)
        val isConnecting = state == ConnectState.WORKING
        if (isConnecting && now - lastParticleTime >= 80L) {
            emitCircularParticles()
            lastParticleTime = now
        }
        // Emit ambient particles when connected (slower rate for performance)
        else if (state == ConnectState.LIVE && now - lastParticleTime >= 150L) {
            emitAmbientParticles()
            lastParticleTime = now
        }

        // Update particles
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.life += dt
            if (p.life >= p.maxLife) {
                iterator.remove()
                continue
            }

            if (p.isCircular) {
                // Circular motion around the orb
                p.angle += p.angularSpeed * (dt / 16.67f)
                p.x = cx + cos(p.angle) * p.radius
                p.y = cy + sin(p.angle) * p.radius
                // Gradually expand outward
                p.radius += 0.15f * (dt / 16.67f)
            } else {
                p.x += p.vx * (dt / 16.67f)
                p.y += p.vy * (dt / 16.67f)
                if (p.slow) {
                    p.vy -= 0.003f * (dt / 16.67f)
                } else {
                    p.vx *= 0.98f
                    p.vy *= 0.98f
                    p.vy += 0.01f * (dt / 16.67f)
                }
            }
        }

    }

    private fun emitAmbientParticles() {
        val cx = width / 2f
        val cy = height / 2f
        val colors = particleColors()

        // More particles but with longer life (fewer emissions needed)
        repeat(4 + Random.nextInt(4)) {
            val angle = Random.nextFloat() * 2f * PI.toFloat()
            val r = orbRadius + dp(5f + Random.nextFloat() * 12f)
            val speed = 0.25f + Random.nextFloat() * 0.4f

            particles.add(Particle(
                x = cx + cos(angle) * r,
                y = cy + sin(angle) * r,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed - 0.06f,
                size = dp(1f + Random.nextFloat() * 1.5f),
                life = 0f,
                maxLife = 2500f + Random.nextFloat() * 2000f,  // Longer life = more visible particles
                alpha = 0.3f + Random.nextFloat() * 0.35f,
                color = colors[Random.nextInt(colors.size)],
                slow = true
            ))
        }

        while (particles.size > 250) particles.removeAt(0)
    }

    private fun emitCircularParticles() {
        val colors = particleColors()

        // More circular particles for richer effect
        repeat(6 + Random.nextInt(5)) {
            val startAngle = Random.nextFloat() * 2f * PI.toFloat()
            val radius = orbRadius + dp(6f + Random.nextFloat() * 18f)
            val direction = if (Random.nextBoolean()) 1f else -1f
            val angularSpeed = direction * (0.035f + Random.nextFloat() * 0.03f)

            particles.add(Particle(
                x = 0f, y = 0f,
                vx = 0f, vy = 0f,
                size = dp(1f + Random.nextFloat() * 1.6f),
                life = 0f,
                maxLife = 1000f + Random.nextFloat() * 800f,
                alpha = 0.45f + Random.nextFloat() * 0.35f,
                color = colors[Random.nextInt(colors.size)],
                slow = false,
                isCircular = true,
                angle = startAngle,
                radius = radius,
                angularSpeed = angularSpeed
            ))
        }

        while (particles.size > 300) particles.removeAt(0)
    }

    private fun emitDustBurst() {
        val cx = width / 2f
        val cy = height / 2f
        val colors = particleColors()

        // More burst particles for impressive effect
        repeat(80) {
            val angle = Random.nextFloat() * 2f * PI.toFloat()
            val dist = dp(25f + Random.nextFloat() * 65f)
            val speed = dist / 12f

            particles.add(Particle(
                x = cx + cos(angle) * orbRadius,
                y = cy + sin(angle) * orbRadius,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed,
                size = dp(0.8f + Random.nextFloat() * 1.4f),
                life = 0f,
                maxLife = 600f + Random.nextFloat() * 500f,
                alpha = 0.4f + Random.nextFloat() * 0.3f,
                color = colors[Random.nextInt(colors.size)],
                slow = false
            ))
        }

        while (particles.size > 300) particles.removeAt(0)
        invalidate()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Animation
    // ═══════════════════════════════════════════════════════════════════════════

    private fun startMasterAnimation() {
        if (isPaused || !isAttachedToWindow || !isShown ||
            masterAnimator?.isRunning == true || !ValueAnimator.areAnimatorsEnabled()
        ) return

        masterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Long.MAX_VALUE
            interpolator = LinearInterpolator()

            var lastTime = SystemClock.elapsedRealtime()
            var lastDrawTime = 0L
            val frameInterval = 33L  // ~30fps for better battery life

            addUpdateListener {
                val now = SystemClock.elapsedRealtime()
                val dt = (now - lastTime) / 1000f
                lastTime = now

                // Update all animation phases (smooth looping)
                blobPhase = (blobPhase + dt / 9f) % 1f        // 9s blob morph
                swirlPhase = (swirlPhase + dt / 20f) % 1f     // 20s mist rotation
                breathePhase = (breathePhase + dt / 4f) % 1f  // 4s outer glow
                corePhase = (corePhase + dt / 7f) % 1f        // 7s core glow

                // Throttle redraws to ~30fps for better performance
                if (now - lastDrawTime >= frameInterval) {
                    lastDrawTime = now
                    invalidate()
                }
            }
            start()
        }
    }

    private fun animatePress(targetScale: Float) {
        if (!ValueAnimator.areAnimatorsEnabled()) { pressScale = targetScale; invalidate(); return }
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, targetScale).apply {
            duration = 100
            interpolator = PathInterpolator(0.1f, 0.9f, 0.2f, 1f)
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animatePressRelease() {
        if (!ValueAnimator.areAnimatorsEnabled()) { pressScale = 1f; invalidate(); return }
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, 1.02f, 1f).apply {
            duration = 280
            interpolator = PathInterpolator(0.2f, 0f, 0.2f, 1f)
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animateShade(targetOpacity: Float) {
        if (!ValueAnimator.areAnimatorsEnabled()) { shadeOpacity = targetOpacity; invalidate(); return }
        shadeAnimator?.cancel()
        shadeAnimator = ValueAnimator.ofFloat(shadeOpacity, targetOpacity).apply {
            duration = if (targetOpacity > 0) 100 else 250
            addUpdateListener {
                shadeOpacity = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun cancelAllAnimations() {
        transitionAnimator?.cancel()
        transitionProgress = 1f
        masterAnimator?.cancel()
        pressAnimator?.cancel()
        shadeAnimator?.cancel()
        pressScale = 1f
        shadeOpacity = 0f
        orbPressed = false
        particles.clear()
        lastFrameTime = 0L
        invalidate()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun stateAccent(targetState: ConnectState): Int = when (targetState) {
        ConnectState.LIVE -> palette.teal
        ConnectState.WORKING -> palette.amber
        ConnectState.IDLE -> palette.neutral
        ConnectState.FAILED -> palette.failed
    }

    private fun stateGradient(targetState: ConnectState): Pair<Int, Int> = when (targetState) {
        ConnectState.LIVE -> if (palette.isDark) {
            palette.tealGradientEnd to palette.tealGradientStart
        } else {
            palette.tealGradientStart to
                (evaluator.evaluate(0.2f, palette.teal, palette.textPrimary) as Int)
        }
        ConnectState.WORKING -> if (palette.isDark) {
            palette.amberGradientStart to palette.amberGradientEnd
        } else {
            (evaluator.evaluate(0.55f, palette.amberGradientStart, palette.textPrimary) as Int) to
                (evaluator.evaluate(0.4f, palette.amberGradientEnd, palette.textPrimary) as Int)
        }
        ConnectState.IDLE -> palette.textSecondary to palette.neutral
        ConnectState.FAILED ->
            (evaluator.evaluate(0.25f, palette.failed, palette.onStateFill) as Int) to palette.failed
    }

    private fun currentAccent(): Int =
        evaluator.evaluate(transitionProgress, fromAccent, toAccent) as Int

    private fun currentGradientStart(): Int =
        evaluator.evaluate(transitionProgress, fromGradientStart, toGradientStart) as Int

    private fun currentGradientEnd(): Int =
        evaluator.evaluate(transitionProgress, fromGradientEnd, toGradientEnd) as Int

    private fun currentForeground(): Int =
        if (palette.isDark) palette.onAccent else palette.onProminent

    private fun particleColors(): IntArray {
        val accent = currentAccent()
        return intArrayOf(
            accent,
            evaluator.evaluate(0.25f, accent, palette.onStateFill) as Int,
            evaluator.evaluate(0.5f, accent, palette.onStateFill) as Int,
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}

internal data class ConnectionOrbColors(
    val amber: Int,
    val amberGradientEnd: Int,
    val amberGradientStart: Int,
    val background: Int,
    val isDark: Boolean,
    val neutral: Int,
    val onAccent: Int,
    val onProminent: Int,
    val onStateFill: Int,
    val teal: Int,
    val tealGradientEnd: Int,
    val tealGradientStart: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val failed: Int,
) {
    companion object {
        // Same component palette as WhiteDNS 1.6.9 and WhiteVPN.
        val Dark = ConnectionOrbColors(
            amber = 0xFFE8AA4E.toInt(),
            amberGradientEnd = 0xFFD99A35.toInt(),
            amberGradientStart = 0xFFF0B96B.toInt(),
            background = 0xFF050E09.toInt(),
            isDark = true,
            neutral = 0xFF89968F.toInt(),
            onAccent = 0xFF02130C.toInt(),
            onProminent = 0xFF020705.toInt(),
            onStateFill = 0xFFF4FAF7.toInt(),
            teal = 0xFF3FBE90.toInt(),
            tealGradientEnd = 0xFF71CDA7.toInt(),
            tealGradientStart = 0xFF3FBE90.toInt(),
            textPrimary = 0xFFE4EEE9.toInt(),
            textSecondary = 0xFF9BA8A2.toInt(),
            failed = 0xFFF07F77.toInt(),
        )
        val Light = ConnectionOrbColors(
            amber = 0xFFD08B00.toInt(),
            amberGradientEnd = 0xFFC37F00.toInt(),
            amberGradientStart = 0xFFE8AA4E.toInt(),
            background = 0xFFF1F9F5.toInt(),
            isDark = false,
            neutral = 0xFF394640.toInt(),
            onAccent = 0xFFF4FAF7.toInt(),
            onProminent = 0xFFF4FAF7.toInt(),
            onStateFill = 0xFFF4FAF7.toInt(),
            teal = 0xFF007E50.toInt(),
            tealGradientEnd = 0xFF4BAE87.toInt(),
            tealGradientStart = 0xFF007E50.toInt(),
            textPrimary = 0xFF0A1410.toInt(),
            textSecondary = 0xFF495650.toInt(),
            failed = 0xFFCF4040.toInt(),
        )
    }
}
