package com.edib.openwhispr

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.edib.openwhispr.OpenWisprUi.dp

/**
 * Adapter for the Notes timeline.
 *
 * Presentation only: this class never modifies a transcript. The preview is
 * the stored string handed straight to the TextView, and BiDi is handled by
 * [OpenWisprUi.applyTranscriptDirection] at the view level, so Copy, Share and
 * the database all keep returning the exact raw text.
 *
 * Row design (transcript-first, flat, no boxed cards):
 *
 *   ▶  Meeting with Yasser                       ★  ⋮
 *      نعم يا أستاذ نونو، عايز من بابا إيه؟
 *      What do you want from Baba? Tell me...
 *      AR + EN  ·  04:32  ·  Local
 */
internal class NotesAdapter(
    private val onOpen: (Note) -> Unit,
    private val onPinToggle: (Note) -> Unit,
    private val onRetranscribe: (Note) -> Unit,
    private val onCopy: (Note) -> Unit,
    private val onDelete: (Note) -> Unit,
    private val onPlay: (Note) -> Unit,
) : ListAdapter<NotesAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    /** One list entry: either a date/pinned header, or a note. */
    sealed class Row {
        data class Header(val label: String) : Row()
        data class Item(val note: Note) : Row()
    }

    class HeaderVH(val view: TextView) : RecyclerView.ViewHolder(view)
    class NoteVH(val root: View) : RecyclerView.ViewHolder(root)

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Row.Header -> TYPE_HEADER
        is Row.Item -> TYPE_NOTE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return if (viewType == TYPE_HEADER) {
            HeaderVH(OpenWisprUi.sectionHeader(ctx, ""))
        } else {
            NoteVH(buildNoteRow(ctx))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderVH).view.text = row.label
            is Row.Item -> bind((holder as NoteVH).root, row.note)
        }
    }

    /** Build the row view tree once per recycled row. */
    private fun buildNoteRow(ctx: Context): View {
        val gutter = ctx.dp(OpenWisprUi.GUTTER)

        // Flat list row: no card fill, separation comes from a 1dp hairline.
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gutter, ctx.dp(OpenWisprUi.SPACE_MD), gutter, ctx.dp(OpenWisprUi.SPACE_MD))
            background = OpenWisprUi.rounded(0x00000000, 0)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
        }

        // Hairline at the top of every row except the visual first (cheap and
        // uniform: a divider under each row reads the same at any list length).
        root.addView(View(ctx).apply {
            setBackgroundColor(OpenWisprUi.border(ctx))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(1)
            )
        })

        // Line 1: [play] title .......................... [star] [overflow]
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val play = ImageButton(ctx).apply {
            tag = TAG_PLAY
            setImageResource(R.drawable.ic_play)
            setColorFilter(OpenWisprUi.textSecondary(ctx))
            background = OpenWisprUi.rounded(OpenWisprUi.surfaceElevated(ctx), ctx.dp(10))
            contentDescription = "Play recording"
            val s = ctx.dp(9)
            setPadding(s, s, s, s)
        }
        val playLp = LinearLayout.LayoutParams(ctx.dp(38), ctx.dp(38)).apply {
            bottomMargin = ctx.dp(2)
        }
        top.addView(play, playLp)

        val title = TextView(ctx).apply {
            tag = TAG_TITLE
            textSize = OpenWisprUi.NOTE_TITLE_SP
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(OpenWisprUi.text(ctx))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ctx.dp(OpenWisprUi.SPACE_MD)
                marginEnd = ctx.dp(OpenWisprUi.SPACE_SM)
            }
        }
        top.addView(title)

        val pin = ImageButton(ctx).apply {
            tag = TAG_PIN
            setImageResource(R.drawable.ic_push_pin)
            setColorFilter(OpenWisprUi.RED_QUIET)
            background = OpenWisprUi.rounded(0x00000000, ctx.dp(10))
            contentDescription = "Pinned"
            val s = ctx.dp(11)
            setPadding(s, s, s, s)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40))
        }
        top.addView(pin)

        val more = ImageButton(ctx).apply {
            tag = TAG_MORE
            setImageResource(R.drawable.ic_more_vert)
            setColorFilter(OpenWisprUi.textMuted(ctx))
            background = OpenWisprUi.rounded(0x00000000, ctx.dp(10))
            contentDescription = "Note options"
            val s = ctx.dp(11)
            setPadding(s, s, s, s)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40))
        }
        top.addView(more)
        root.addView(top)

        // Line 2: transcript preview, up to 3 lines, right-aligned when Arabic.
        // The stored string is passed through untouched.
        val body = TextView(ctx).apply {
            tag = TAG_BODY
            textSize = OpenWisprUi.PREVIEW_SP
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.32f)
            setTextColor(OpenWisprUi.textSecondary(ctx))
            setPadding(ctx.dp(38 + OpenWisprUi.SPACE_MD), ctx.dp(OpenWisprUi.SPACE_XS), 0, 0)
            OpenWisprUi.applyTranscriptDirection(this)
        }
        root.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Line 3: [waveform hint] language · duration · source
        val meta = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(38 + OpenWisprUi.SPACE_MD), ctx.dp(OpenWisprUi.SPACE_SM), 0, 0)
        }

        val spark = ImageView(ctx).apply {
            tag = TAG_SPARK
            setImageResource(R.drawable.ic_waveform)
            setColorFilter(OpenWisprUi.textMuted(ctx))
            alpha = 0.55f
            val h = ctx.dp(14)
            layoutParams = LinearLayout.LayoutParams(h, h).apply {
                marginEnd = ctx.dp(OpenWisprUi.SPACE_SM)
            }
        }
        meta.addView(spark)

        val metaText = TextView(ctx).apply {
            tag = TAG_META
            textSize = OpenWisprUi.META_SP
            setTextColor(OpenWisprUi.textMuted(ctx))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        meta.addView(metaText)
        root.addView(meta)

        return root
    }

    private fun bind(root: View, note: Note) {
        val ctx = root.context
        val title = root.findViewWithTag<TextView>(TAG_TITLE)
        val body = root.findViewWithTag<TextView>(TAG_BODY)
        val meta = root.findViewWithTag<TextView>(TAG_META)
        val more = root.findViewWithTag<ImageButton>(TAG_MORE)
        val pin = root.findViewWithTag<ImageButton>(TAG_PIN)
        val play = root.findViewWithTag<ImageButton>(TAG_PLAY)
        val spark = root.findViewWithTag<ImageView>(TAG_SPARK)

        val text = note.displayTranscript
        val hasText = !text.isNullOrBlank()

        // Title: the first line of the transcript, but only when the preview
        // below has enough text that the title is adding information rather
        // than repeating it. A title is always shown so the row keeps a stable
        // shape; the check only decides *what* it says.
        title.text = firstLine(text) ?: "Voice note"
        title.alpha = if (hasText) 1f else 0.6f

        when (note.transcriptionState) {
            Note.State.PENDING -> {
                body.visibility = View.VISIBLE
                body.setTextColor(OpenWisprUi.textMuted(ctx))
                body.text = "Transcribing locally…"
            }
            Note.State.FAILED -> {
                body.visibility = View.VISIBLE
                body.setTextColor(OpenWisprUi.RED)
                body.text = "Transcription failed · tap to retry"
            }
            Note.State.COMPLETE -> {
                body.setTextColor(OpenWisprUi.textSecondary(ctx))
                // The title already carries the first line, so the preview
                // shows only what follows it, and is hidden entirely when
                // there is nothing left. Same stored string, sliced for
                // display only.
                val preview = previewAfterFirstLine(text)
                body.text = if (preview.isEmpty()) "" else preview
                body.visibility = if (preview.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        meta.text = buildMeta(note)
        spark.setColorFilter(
            if (note.transcriptionState == Note.State.FAILED) OpenWisprUi.RED_QUIET
            else OpenWisprUi.textMuted(ctx)
        )
        spark.alpha = if (note.transcriptionState == Note.State.PENDING) 0.85f else 0.5f

        pin.visibility = if (note.isPinned) View.VISIBLE else View.INVISIBLE
        pin.setColorFilter(OpenWisprUi.RED)
        pin.setOnClickListener { onPinToggle(note) }
        pin.contentDescription =
            if (note.isPinned) "Unpin note" else "Pin note"

        play.setOnClickListener { onPlay(note) }
        more.setOnClickListener { anchor -> showNoteMenu(anchor, note) }
        root.setOnClickListener { onOpen(note) }
    }

    private fun firstLine(text: String?): String? = text
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() }
        ?.let { if (it.length > 72) it.take(72) + "…" else it }

    /**
     * Everything after the first non-empty line, for the list preview. This is
     * display slicing only: it never reorders or rewrites anything.
     *
     * Returns "" when there is no meaningful remainder, and the caller then
     * hides the preview row entirely rather than repeating the title.
     */
    private fun previewAfterFirstLine(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val lines = text.lines()
        val firstIdx = lines.indexOfFirst { it.isNotBlank() }
        if (firstIdx < 0) return ""
        return lines.drop(firstIdx + 1)
            .joinToString(" ") { it.trim() }
            .trim()
    }

    /** "AR + EN · 04:32 · Local" — detected from the stored text, not stored. */
    private fun buildMeta(note: Note): String {
        val t = note.displayTranscript.orEmpty()
        val ar = t.any { it.code in 0x0600..0x06FF }
        val la = t.any { it.isLetter() && it.code < 0x0600 }
        val lang = when {
            ar && la -> "AR + EN"
            ar -> "AR"
            la -> "EN"
            else -> "—"
        }
        val dur = Note.formatDuration(note.audioDurationMs)
        val state = when (note.transcriptionState) {
            Note.State.PENDING -> "Transcribing"
            Note.State.FAILED -> "Failed"
            Note.State.COMPLETE -> "Local"
        }
        return "$lang  ·  $dur  ·  $state"
    }

    private fun showNoteMenu(anchor: View, note: Note) {
        val ctx = anchor.context
        PopupMenu(ctx, anchor).apply {
            menu.add(if (note.isPinned) "Unpin note" else "Pin note")
            menu.add("Copy transcript")
            menu.add("Share transcript")
            menu.add("Retranscribe")
            menu.add("Delete")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Pin note", "Unpin note" -> onPinToggle(note)
                    "Copy transcript" -> onCopy(note)
                    "Share transcript" -> NoteShareHelper.shareTranscript(ctx, note)
                    "Retranscribe" -> onRetranscribe(note)
                    "Delete" -> onDelete(note)
                }
                true
            }
            show()
        }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_NOTE = 1

        private const val TAG_TITLE = "ow_title"
        private const val TAG_BODY = "ow_body"
        private const val TAG_META = "ow_meta"
        private const val TAG_MORE = "ow_more"
        private const val TAG_PIN = "ow_pin"
        private const val TAG_PLAY = "ow_play"
        private const val TAG_SPARK = "ow_spark"

        /**
         * Group notes exactly as before: pinned first, then unpinned grouped by
         * day, so the order a user already sees is preserved.
         */
        fun buildRows(notes: List<Note>, searchQuery: String): List<Row> {
            if (notes.isEmpty()) return emptyList()
            val rows = ArrayList<Row>(notes.size + 4)
            val pinned = notes.filter { it.isPinned }
            val unpinned = notes.filter { !it.isPinned }
            if (pinned.isNotEmpty()) {
                rows.add(Row.Header("Pinned"))
                pinned.forEach { rows.add(Row.Item(it)) }
            }
            val grouped = unpinned.groupBy { Note.formatDateHeader(it.createdAt) }
            for ((day, items) in grouped) {
                rows.add(Row.Header(day))
                items.forEach { rows.add(Row.Item(it)) }
            }
            return rows
        }

        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = when {
                a is Row.Header && b is Row.Header -> a.label == b.label
                a is Row.Item && b is Row.Item -> a.note.id == b.note.id
                else -> false
            }
            override fun areContentsTheSame(a: Row, b: Row) = when {
                a is Row.Header && b is Row.Header -> true
                a is Row.Item && b is Row.Item ->
                    a.note == b.note ||
                        a.note.transcriptionState == b.note.transcriptionState &&
                            a.note.displayTranscript == b.note.displayTranscript
                else -> false
            }
        }
    }
}
