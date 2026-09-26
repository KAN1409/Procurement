package com.edib.openwhispr

import android.app.Activity
import android.util.Log
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Primary Notes screen.
 * Dark. Minimal. Dense. Native Android. Consistent with OpenWispr.
 * No gradients, no glowing AI controls, no chatbot UI.
 */
class NotesTimelineView(
    context: Context,
    private val onOpenSettings: () -> Unit
) : FrameLayout(context) {

    private val repo = NotesRepository.getInstance(context)
    private val recorder = InAppNoteRecorder(context)

    private val container: LinearLayout
    private val notesRecycler: androidx.recyclerview.widget.RecyclerView
    private val notesAdapter: NotesAdapter
    private val searchBarLayout: LinearLayout
    private val searchEditText: EditText
    private val emptyView: TextView
    private val recordBar: LinearLayout
    private val recordFab: MaterialButton

    // Minimal In-App Recording Screen/Overlay
    private val recordingOverlay: LinearLayout
    private val recTimerText: TextView
    private val recLevelIndicator: LinearProgressIndicator
    private val recStopBtn: MaterialButton
    private val recCancelBtn: MaterialButton

    private var currentSearchQuery = ""
    private var isSearchVisible = false
    private val repositoryListener: () -> Unit = { post { refreshNotes() } }

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    companion object {
        /** Request code for the system document picker used by Import. */
        const val REQUEST_IMPORT_AUDIO = 0xA11

        private const val RECORD_BAR_INSET = 76
        private const val TAG = "NotesTimelineView"
    }

    init {
        setBackgroundColor(OpenWisprUi.bg(context))

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(OpenWisprUi.SPACE_LG), dp(OpenWisprUi.SPACE_MD), dp(OpenWisprUi.SPACE_LG), 0)
        }

        // ================= TOP BAR: title + Search + Settings =================
        // The system bar already shows the app name, and the screen below is
        // the notes list, so the app name is not repeated in content space.
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(OpenWisprUi.SPACE_SM)
            }
            layoutParams = lp
        }

        val brandTitle = TextView(context).apply {
            text = "Notes"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(OpenWisprUi.primaryText(context))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(brandTitle)

        val searchBtn = OpenWisprUi.iconImageButton(context, R.drawable.ic_search, "Search notes") {
            toggleSearch()
        }
        topBar.addView(searchBtn)

        val settingsBtn = OpenWisprUi.iconImageButton(context, R.drawable.ic_settings, "Settings") {
            onOpenSettings()
        }
        topBar.addView(settingsBtn)

        container.addView(topBar)

        // ================= SEARCH BAR (Expandable) =================
        searchBarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = OpenWisprUi.surface(context)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
            layoutParams = lp
            visibility = View.GONE
        }

        searchEditText = EditText(context).apply {
            hint = "Search notes…"
            setHintTextColor(OpenWisprUi.mutedText(context))
            setTextColor(OpenWisprUi.primaryText(context))
            textSize = 15f
            background = null
            OpenWisprUi.applyTranscriptDirection(this)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentSearchQuery = s?.toString()?.trim() ?: ""
                    refreshNotes()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchBarLayout.addView(searchEditText)

        val clearSearchBtn = TextView(context).apply {
            text = "×"
            contentDescription = "Close search"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                searchEditText.setText("")
                toggleSearch()
            }
        }
        searchBarLayout.addView(clearSearchBtn)
        container.addView(searchBarLayout)

        emptyView = TextView(context).apply {
            text = "No voice notes yet\n\nRecord your first note and it will be transcribed here."
            textSize = 16f
            setTextColor(OpenWisprUi.secondaryText(context))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            // Centred in the available space, not pinned under the list. A
            // weighted child with a small top padding sits high in the column;
            // this offset moves the message into the visual middle.
            setPadding(dp(OpenWisprUi.SPACE_XL), dp(48), dp(OpenWisprUi.SPACE_XL), dp(48))
            visibility = View.GONE
        }

        // The header is fixed and only the list scrolls. A RecyclerView nested
        // inside a ScrollView would inflate every row anyway, so the root is a
        // vertical column: fixed header, then a weighted scrolling list.
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = OpenWisprUi.surface(context, 0, OpenWisprUi.bg(context))
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        column.addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        notesAdapter = NotesAdapter(
            onOpen = { note -> NoteDetailDialog(context, note.id) { refreshNotes() }.show() },
            onPinToggle = { repo.togglePinned(it.id); refreshNotes() },
            onRetranscribe = { NoteTranscriber.transcribeNoteAsync(context, it.id); refreshNotes() },
            onCopy = {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("Transcript", it.displayTranscript.orEmpty())
                )
                android.widget.Toast.makeText(context, "Transcript copied", android.widget.Toast.LENGTH_SHORT).show()
            },
            onDelete = { note ->
                AlertDialog.Builder(context)
                    .setTitle("Delete note?")
                    .setMessage("This removes the recording and its transcript.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        repo.deleteNote(note.id)
                        refreshNotes()
                    }
                    .show()
            },
        )

        notesRecycler = androidx.recyclerview.widget.RecyclerView(context).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
            adapter = notesAdapter
            clipToPadding = false
            // Bottom inset clears the record bar so the last card can always
            // scroll fully into view instead of being clipped by it.
            setPadding(
                dp(OpenWisprUi.SPACE_LG), 0, dp(OpenWisprUi.SPACE_LG),
                dp(OpenWisprUi.SPACE_LG) + RECORD_BAR_INSET
            )
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        column.addView(notesRecycler, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f))
        column.addView(emptyView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f))

        // ================= BOTTOM RECORD BAR =================
        // Record is the app's primary action, so it gets a persistent,
        // always-visible full-width target instead of a corner FAB that
        // overlapped the last note and sat on top of the gesture bar.
        // One control only: an earlier revision showed both a FAB and a pill,
        // which read as two competing primary actions.
        recordBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(OpenWisprUi.SPACE_LG), dp(OpenWisprUi.SPACE_MD),
                        dp(OpenWisprUi.SPACE_LG), dp(OpenWisprUi.SPACE_MD))
            setBackgroundColor(OpenWisprUi.bg(context))
        }
        // Hairline so the bar reads as a separate surface rather than a gap.
        recordBar.addView(View(context).apply {
            setBackgroundColor(0x1FFFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
        })
        // The word names the action; a bare mic glyph was too ambiguous on its
        // own, and a decorative bullet character was worse. A full-bleed red
        // slab dominated every screenshot, so the button is inset and rounded.
        recordFab = MaterialButton(context).apply {
            text = "Record voice note"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = ColorStateList.valueOf(OpenWisprUi.RECORD)
            cornerRadius = dp(14)
            // Weighted so Record and Import share the bar, Record first.
            val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
            layoutParams = lp
            isClickable = true
            isFocusable = true
            contentDescription = "Record voice note"
            setOnClickListener { startRecordingFlow() }
        }

        // Secondary action in the same bar: bring an existing audio file in as a
        // Voice Note. The bar is a vertical column (hairline + actions), so the
        // two buttons sit in their own horizontal row.
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        actionRow.addView(recordFab, LinearLayout.LayoutParams(0, dp(48), 1f))
        val importBtn = MaterialButton(context).apply {
            text = "Import voice note"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(OpenWisprUi.primaryText(context))
            backgroundTintList = ColorStateList.valueOf(OpenWisprUi.card(context))
            cornerRadius = dp(14)
            val ilp = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(OpenWisprUi.SPACE_SM)
            }
            layoutParams = ilp
            isClickable = true
            isFocusable = true
            contentDescription = "Import voice note from a file"
            setOnClickListener { launchImportPicker() }
        }
        actionRow.addView(importBtn)
        recordBar.addView(actionRow)

        addView(recordBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        // ================= MINIMAL RECORDING UI =================
        // Layout:
        // 00:37
        // [ restrained audio level/waveform ]
        // Recording voice note
        // [ STOP ]
        // Cancel
        recordingOverlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(OpenWisprUi.BACKGROUND)
            setPadding(dp(32), dp(48), dp(32), dp(48))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        recTimerText = TextView(context).apply {
            text = "00:00"
            textSize = 44f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        recordingOverlay.addView(recTimerText)

        recLevelIndicator = LinearProgressIndicator(context).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            trackColor = 0xFF2A2A2A.toInt()
            setIndicatorColor(0xFFEF4444.toInt())
            trackCornerRadius = dp(2)
            val lp = LinearLayout.LayoutParams(dp(180), dp(4)).apply {
                topMargin = dp(20)
                bottomMargin = dp(16)
            }
            layoutParams = lp
        }
        recordingOverlay.addView(recLevelIndicator)

        val recLabel = TextView(context).apply {
            text = "Recording voice note\n\nTap stop when you're done"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(36)
            }
            layoutParams = lp
        }
        recordingOverlay.addView(recLabel)

        recStopBtn = MaterialButton(context).apply {
            text = "STOP"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(0xFFEF4444.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            cornerRadius = dp(24)
            minWidth = dp(140)
            setOnClickListener {
                stopAndSaveRecording()
            }
        }
        recordingOverlay.addView(recStopBtn)

        recCancelBtn = MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Cancel"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            // Quiet, non-destructive weighting, but still a full-size target.
            minHeight = dp(48)
            minWidth = dp(96)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
            layoutParams = lp
            setOnClickListener {
                cancelRecording()
            }
        }
        recordingOverlay.addView(recCancelBtn)

        addView(recordingOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Hook up recorder ticks to restrained audio level & timer
        recorder.onTickListener = { elapsedMs, amplitude ->
            val totalSec = elapsedMs / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            recTimerText.text = String.format(Locale.US, "%02d:%02d", min, sec)
            recLevelIndicator.progress = (amplitude * 100).toInt().coerceIn(0, 100)
        }

        // Listen for repository changes (insert, delete, update, transcribe)
        repo.addListener(repositoryListener)

        refreshNotes()
    }

    fun isRecording(): Boolean = recorder.isRecording

    /**
     * Open the system document picker to import an existing audio file as a
     * Voice Note. Uses the Storage Access Framework, so no broad storage
     * permission is requested. The result is delivered back through
     * [onImportPicked], which MainActivity forwards from onActivityResult.
     */
    fun launchImportPicker() {
        val activity = context as? Activity ?: return
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            // Audio containers only. The transcription engine decodes whatever
            // MediaExtractor supports, so the filter stays broad but audio-only.
            type = "audio/*"
            putExtra(
                android.content.Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "audio/*",
                    "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac",
                    "audio/wav", "audio/x-wav", "audio/ogg", "audio/opus",
                    "audio/flac", "audio/3gpp", "audio/amr", "audio/webm"
                )
            )
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivityForResult(intent, REQUEST_IMPORT_AUDIO)
        } catch (_: Exception) {
            Toast.makeText(context, "No file picker available", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Copy a picked document into private note storage and create the Voice
     * Note. The note is then handed to the exact same
     * [NoteTranscriber.transcribeNoteAsync] call the recorder uses, so imported
     * and recorded notes converge before transcription and share its progress
     * state, Local/Cloud routing and failure behaviour.
     */
    fun onImportPicked(resultCode: Int, data: android.content.Intent?) {
        if (resultCode != Activity.RESULT_OK) return   // cancelled: no state change
        val uri = data?.data
        if (uri == null) {
            Toast.makeText(context, "Import failed: no file selected", Toast.LENGTH_SHORT).show()
            return
        }
        importFromUri(uri)
    }

    private fun importFromUri(uri: android.net.Uri) {
        val resolver = context.contentResolver
        // Persist the read grant where the provider allows it; we copy
        // immediately regardless, so a refusal here is not fatal. A file:// URI
        // has no persistable grant at all, so the failure is expected there.
        try {
            resolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {
        }

        thread(name = "import-voice-note") {
            var noteId: String? = null
            try {
                val raw = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not open the selected file")
                if (raw.isEmpty()) throw IllegalStateException("Selected file is empty")

                // NotesRepository persists audio as "<id>.wav" and the rest of
                // the app (playback, share, duration) assumes a WAV. A picked
                // file may be any container the decoder supports, so anything
                // that is not already RIFF/WAVE is transcoded to 16 kHz mono
                // PCM WAV here, before the note is created. That keeps imported
                // notes indistinguishable from recorded ones and leaves the
                // transcription pipeline reading a normal WAV.
                val wav = AudioImport.ensureWav16kMono(context, raw)
                val note = NotesRepository.getInstance(context)
                    .createAndSaveNoteFromWav(wav, durationMsOf(wav))
                noteId = note.id
                NoteTranscriber.transcribeNoteAsync(context, note.id)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory importing audio", e)
                noteId?.let {
                    NotesRepository.getInstance(context)
                        .markTranscriptionFailed(it, "Not enough memory to import this file")
                }
                post { Toast.makeText(context, "Import failed: file too large", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                noteId?.let {
                    NotesRepository.getInstance(context)
                        .markTranscriptionFailed(it, e.message ?: "Import failed")
                }
                post {
                    Toast.makeText(
                        context,
                        "Import failed: ${e.message ?: "could not read the file"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun isWav(b: ByteArray): Boolean =
        b.size > 44 &&
            b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'A'.code.toByte() &&
            b[10] == 'V'.code.toByte() && b[11] == 'E'.code.toByte()

    /**
     * Duration in ms from a RIFF/WAVE header.
     *
     * Walks the chunk list rather than assuming a 44-byte canonical header, so
     * files carrying LIST/fact chunks (common in recorded m4a->wav conversions)
     * are measured correctly. Returns 0 when the header is not a usable WAV.
     */
    private fun durationMsOf(wav: ByteArray): Long {
        if (!isWav(wav) || wav.size < 44) return 0L
        // RIFF/WAVE integers are LITTLE-endian (this is also what WavWriter
        // emits). Reading them big-endian yields garbage, which is why an
        // imported note was showing ~3 s for a 12.5 s file.
        fun u32(o: Int): Long =
            ((wav[o + 3].toLong() and 0xFF) shl 24) or
            ((wav[o + 2].toLong() and 0xFF) shl 16) or
            ((wav[o + 1].toLong() and 0xFF) shl 8) or
            (wav[o].toLong() and 0xFF)

        var p = 12
        var byteRate = 0L
        var dataSize = 0L
        while (p + 8 <= wav.size) {
            val id = String(wav, p, 4, Charsets.ISO_8859_1)
            val size = u32(p + 4).toInt()
            val body = p + 8
            when (id) {
                // fmt body layout (offsets from the chunk body):
                //   audioFormat +0, channels +2, sampleRate +4, byteRate +8,
                //   blockAlign +12, bitsPerSample +14
                "fmt " -> if (body + 16 <= wav.size) byteRate = u32(body + 8)
                "data" -> { dataSize = size.toLong(); break }
            }
            p = body + size + (size and 1)   // chunks are word-aligned
        }
        if (byteRate <= 0L || dataSize <= 0L) return 0L
        return (dataSize * 1000L) / byteRate
    }

    private fun startRecordingFlow() {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (context is Activity) {
                ActivityCompat.requestPermissions(context as Activity, arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            }
            return
        }

        val ok = recorder.start { err ->
            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
        }
        if (ok) {
            recordFab.visibility = View.GONE
            recordingOverlay.visibility = View.VISIBLE
            recTimerText.text = "00:00"
            recLevelIndicator.progress = 0
        }
    }

    private fun stopAndSaveRecording() {
        // Critical invariant: recorder.stopAndSave() durably persists raw PCM as .wav to disk
        // and inserts the Note record BEFORE triggering asynchronous transcription.
        val note = recorder.stopAndSave()
        recordingOverlay.visibility = View.GONE
        recordFab.visibility = View.VISIBLE

        if (note != null) {
            // Durable persistence has succeeded!
            Toast.makeText(context, "Note saved", Toast.LENGTH_SHORT).show()
            refreshNotes()
            // Return/show note detail immediately in PENDING state
            NoteDetailDialog(context, note.id) { refreshNotes() }.show()
        } else {
            Toast.makeText(context, "No audio recorded", Toast.LENGTH_SHORT).show()
        }
    }

    fun cancelRecording() {
        recorder.cancel()
        recordingOverlay.visibility = View.GONE
        recordFab.visibility = View.VISIBLE
    }

    fun dispose() {
        if (recorder.isRecording) recorder.cancel()
        recorder.onTickListener = null
        repo.removeListener(repositoryListener)
    }

    private fun toggleSearch() {
        isSearchVisible = !isSearchVisible
        searchBarLayout.visibility = if (isSearchVisible) View.VISIBLE else View.GONE
        if (isSearchVisible) {
            searchEditText.requestFocus()
        } else {
            searchEditText.setText("")
            currentSearchQuery = ""
            refreshNotes()
        }
    }

    fun refreshNotes() {
        val allNotes = if (currentSearchQuery.isEmpty()) {
            repo.getAllNotes()
        } else {
            repo.searchNotes(currentSearchQuery)
        }

        if (allNotes.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            // The list and the empty view are both weighted children; leaving
            // the list visible splits the space so the message lands at the
            // bottom instead of in the middle.
            notesRecycler.visibility = View.GONE
            emptyView.text = if (currentSearchQuery.isNotEmpty()) {
                "No results\n\nTry another search."
            } else {
                "No voice notes yet\n\nRecord your first note and it will be transcribed here."
            }
            notesAdapter.submitList(emptyList())
            return
        }
        emptyView.visibility = View.GONE
        notesRecycler.visibility = View.VISIBLE
        // Diffed update: only changed rows rebind, instead of rebuilding every
        // note view on each refresh (and on each keystroke while searching).
        notesAdapter.submitList(NotesAdapter.buildRows(allNotes, currentSearchQuery))
    }

}
