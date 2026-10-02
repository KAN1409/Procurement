package com.edib.openwhispr

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * Design tokens for OpenWhispr's dual-tone "satin minimalism" system.
 *
 * Two families only:
 *   - graphite / black, in several tonal steps, establishes all hierarchy
 *   - red, the single chromatic accent, also in tonal steps
 *
 * There are no gradients anywhere. Depth comes from tonal surface differences,
 * spacing, 1dp borders, type scale and state changes.
 */
internal object OpenWisprUi {

    // ---------- spacing (4dp-derived) ----------
    const val SPACE_XS = 4
    const val SPACE_SM = 8
    const val SPACE_MD = 12
    const val SPACE_LG = 16
    const val SPACE_XL = 24
    const val SPACE_2XL = 32
    /** Horizontal screen gutter, 20dp per the spacing brief. */
    const val GUTTER = 20

    // ---------- shape ----------
    const val RADIUS_LARGE = 16
    const val RADIUS_CONTROL = 11
    const val HAIRLINE = 1

    // ---------- graphite family ----------
    const val BG = 0xFF090909.toInt()
    const val SURFACE = 0xFF111111.toInt()
    const val SURFACE_ELEVATED = 0xFF171717.toInt()
    const val SURFACE_PRESSED = 0xFF1D1D1D.toInt()
    const val BORDER = 0xFF292929.toInt()

    // ---------- text on graphite ----------
    const val TEXT = 0xFFF2F2F2.toInt()
    const val TEXT_SECONDARY = 0xFFA8A8A8.toInt()
    const val TEXT_MUTED = 0xFF6E6E6E.toInt()
    const val TEXT_FAINT = 0xFF4A4A4A.toInt()

    // ---------- red family (the only chromatic accent) ----------
    const val RED_DEEP = 0xFF551518.toInt()
    const val RED_CRIMSON = 0xFF781E23.toInt()
    const val RED = 0xFFB52B32.toInt()
    const val RED_ACTIVE = 0xFFD43A40.toInt()
    /** Desaturated red for secondary/destructive emphasis. */
    const val RED_QUIET = 0xFF8C4A4E.toInt()
    /** Muted green-grey for an "already satisfied" setup dot, not Material green. */
    const val SUCCESS_DOT = 0xFF6E8B72.toInt()

    // ---------- light mode (kept functional, not deleted) ----------
    private const val BG_LIGHT = 0xFFFAFAFA.toInt()
    private const val SURFACE_LIGHT = 0xFFFFFFFF.toInt()
    private const val SURFACE_ELEVATED_LIGHT = 0xFFF2F2F2.toInt()
    private const val BORDER_LIGHT = 0xFFE0E0E0.toInt()
    private const val TEXT_LIGHT = 0xFF141414.toInt()
    private const val TEXT_SECONDARY_LIGHT = 0xFF4A4A4A.toInt()
    private const val TEXT_MUTED_LIGHT = 0xFF7A7A7A.toInt()

    // ---------- type scale ----------
    const val TITLE_SP = 28f      // app title
    const val NOTE_TITLE_SP = 16f  // note title
    const val PREVIEW_SP = 15f     // transcript preview
    const val TRANSCRIPT_SP = 18f  // full transcript
    const val BODY_SP = 15f
    const val META_SP = 12f
    const val LABEL_SP = 11f

    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun Context.sp(value: Float) = (value * resources.displayMetrics.scaledDensity)

    fun isLight(context: Context): Boolean {
        val night = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return night != android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    // ---------- theme-aware surface colours ----------

    fun bg(context: Context): Int = if (isLight(context)) BG_LIGHT else BG
    fun surface(context: Context): Int = if (isLight(context)) SURFACE_LIGHT else SURFACE
    fun surfaceElevated(context: Context): Int =
        if (isLight(context)) SURFACE_ELEVATED_LIGHT else SURFACE_ELEVATED
    fun surfacePressed(context: Context): Int =
        if (isLight(context)) SURFACE_ELEVATED_LIGHT else SURFACE_PRESSED
    fun border(context: Context): Int = if (isLight(context)) BORDER_LIGHT else BORDER

    fun text(context: Context): Int = if (isLight(context)) TEXT_LIGHT else TEXT
    fun textSecondary(context: Context): Int =
        if (isLight(context)) TEXT_SECONDARY_LIGHT else TEXT_SECONDARY
    fun textMuted(context: Context): Int =
        if (isLight(context)) TEXT_MUTED_LIGHT else TEXT_MUTED

    // ---------- back-compat aliases ----------
    // Earlier revisions used these names. They remain as thin aliases so
    // existing call sites keep working while the palette is reworked.
    fun primaryText(context: Context): Int = text(context)
    fun secondaryText(context: Context): Int = textSecondary(context)
    fun mutedText(context: Context): Int = textMuted(context)
    fun card(context: Context): Int = surfaceElevated(context)

    /** Legacy names kept as aliases for the red accent / background. */
    const val RECORD = RED
    const val ACCENT = RED
    const val BACKGROUND = BG

    // ---------- shapes ----------

    fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp.toFloat()
        }

    fun surface(context: Context, radiusDp: Int = RADIUS_LARGE): GradientDrawable =
        rounded(surface(context), radiusDp)

    /**
     * A surface with a 1dp graphite-on-graphite border. This is how depth is
     * created in the dark theme; no shadows and no gradients.
     */
    fun outlinedSurface(context: Context, radiusDp: Int = RADIUS_LARGE): GradientDrawable =
        rounded(surface(context), radiusDp).apply {
            setStroke(context.dp(HAIRLINE), border(context))
        }

    fun outlinedElevated(context: Context, radiusDp: Int = RADIUS_LARGE): GradientDrawable =
        rounded(surfaceElevated(context), radiusDp).apply {
            setStroke(context.dp(HAIRLINE), border(context))
        }

    /**
     * Pressable background for a list/settings row: flat and transparent at
     * rest, tinting only while pressed. Filling the row at rest turned every
     * settings entry into a card.
     */
    fun pressableRow(context: Context): android.graphics.drawable.RippleDrawable =
        android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x1FFFFFFF),
            rounded(0x00000000, 0),
            rounded(0xFFFFFFFF.toInt(), 0)
        )

    /** Pressable fill for a real control (a button, not a row). */
    fun pressable(
        context: Context,
        normal: Int = surfaceElevated(context),
        radiusDp: Int = RADIUS_CONTROL
    ): android.graphics.drawable.RippleDrawable =
        android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(RED_DEEP),
            rounded(normal, radiusDp),
            rounded(0xFFFFFFFF.toInt(), radiusDp)
        )

    // ---------- typography ----------

    /** Small all-caps group label: "TODAY", "TRANSCRIPTION". */
    fun sectionHeader(context: Context, title: String): TextView =
        TextView(context).apply {
            text = title.uppercase()
            textSize = LABEL_SP
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.09f
            setTextColor(textMuted(context))
            setPadding(0, context.dp(SPACE_LG), 0, context.dp(SPACE_SM))
        }

    /** App title. */
    fun screenTitle(context: Context, title: String): TextView =
        TextView(context).apply {
            text = title
            textSize = TITLE_SP
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -0.02f
            setTextColor(text(context))
        }

    /** Note title in a list. */
    fun noteTitle(context: Context): TextView =
        TextView(context).apply {
            textSize = NOTE_TITLE_SP
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(text(context))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    /**
     * Apply correct bidirectional rendering to a TextView showing transcript
     * text.
     *
     * Transcripts routinely mix Egyptian Arabic and English, so the base
     * direction must come from the content and the view must be laid out
     * RTL-aware for Android's bidi algorithm to order the runs correctly.
     *
     * Measured on a real transcript
     * ("نعم يا استاز نونو عاوز من بابا ايه؟ What do you want from ... Tell me"),
     * on a device whose locale is en-US:
     *
     *   FIRST_STRONG + VIEW_START        -> English run dragged to the front  x
     *   LOCALE        + VIEW_START       -> English run dragged to the front  x
     *   FIRST_STRONG + TEXT_START        -> correct order, but pure English
     *                                        was right-aligned                 x
     *   layoutDirection RTL + TEXT_START  -> correct for mixed, pure English
     *                                        and pure Arabic                  v
     *
     * The string is never modified: no reordering, no directional marks, no
     * normalisation, no splitting. Copy, Share and the database all continue
     * to receive the exact raw transcript.
     */
    fun applyTranscriptDirection(tv: TextView) {
        tv.layoutDirection = View.LAYOUT_DIRECTION_RTL
        tv.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        tv.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
        tv.breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
        tv.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
    }

    fun iconImageButton(
        context: Context,
        iconRes: Int,
        description: String,
        onClick: (View) -> Unit
    ): android.widget.ImageButton = android.widget.ImageButton(context).apply {
        setImageResource(iconRes)
        setColorFilter(text(context))
        contentDescription = description
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        val p = context.dp(10)
        setPadding(p, p, p, p)
        minimumWidth = context.dp(48)
        minimumHeight = context.dp(48)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x22FFFFFF),
            rounded(0x00000000, 24),
            rounded(0xFFFFFFFF.toInt(), 24)
        )
        stateListAnimator = null
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { v -> onClick(v) }
    }
}
