package com.arslan.textgrab

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import com.arslan.textgrab.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

open class MainActivity : AppCompatActivity(), SelectableOcrView.Listener {

    companion object {
        const val EXTRA_CAPTURED_SCREEN = "com.arslan.textgrab.CAPTURED_SCREEN"
        const val ACTION_CAMERA_PHOTO = "com.arslan.textgrab.action.CAMERA_PHOTO"

        private const val STATE_LAUNCHED_FOR_CAMERA = "launched_for_camera"
        private const val STATE_CAMERA_OPEN = "camera_open"

        private const val SPLASH_FADE_MS = 150L

        private const val TOOLBAR_DELAY = 0.35f
        private const val TOOLBAR_DURATION = 0.5f
        private const val TOOLBAR_SLIDE = 0.4f

        private const val TOOLBAR_SLIDE_FALLBACK_PX = 96

        /** What a QR code may hand to another app; anything else is only shown as text. */
        private val OPENABLE_SCHEMES = setOf("http", "https", "mailto", "tel", "sms", "smsto", "geo")
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var ttsManager: TtsManager

    private var currentBitmap: Bitmap? = null
    private var currentResult: OcrEngine.Result? = null
    private var launchedWithImage = false
    private var translateJob: Job? = null
    private var ocrJob: Job? = null

    private var contentReady = false

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) openImage(uri)
        }

    private val cameraFile by lazy { CameraActivity.photoFile(this) }

    /** Opened by the camera shortcut's photo: the home screen is never part of this session. */
    private var launchedForCamera = false
    private var cameraOpen = false
    private var viewingCameraPhoto = false

    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            cameraOpen = false
            when {
                result.resultCode == RESULT_OK -> openImage(Uri.fromFile(cameraFile), fromCamera = true)
                launchedForCamera -> finish()
                else -> showHome()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val capturing = intent?.getBooleanExtra(EXTRA_CAPTURED_SCREEN, false) == true
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        splash.setKeepOnScreenCondition { !contentReady }

        if (capturing) {
            splash.setOnExitAnimationListener { provider -> provider.remove() }
        } else if (Build.VERSION.SDK_INT < 31) {
            splash.setOnExitAnimationListener { provider ->
                provider.view.animate()
                    .alpha(0f)
                    .setDuration(SPLASH_FADE_MS)
                    .withEndAction { provider.remove() }
            }
        }
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ttsManager = TtsManager(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val base = (20 * resources.displayMetrics.density).toInt()
            binding.homeGroup.updatePadding(top = base + bars.top, bottom = base + bars.bottom)
            insets
        }

        binding.ocrView.listener = this

        binding.topBar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            binding.ocrView.topInset = v.bottom.toFloat()
            binding.ocrProgress.translationY = v.bottom.toFloat()
        }

        setupNavigationTabs()
        setupStudioActions()
        setupToolsActions()

        OcrEngine.warmUp()

        binding.btnAccessibility.setOnClickListener {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        binding.btnSetAssistant.setOnClickListener {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }.onFailure {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            }
        }

        val adbCommand = "adb shell am broadcast --receiver-foreground " +
            "-a ${CaptureReceiver.ACTION_CAPTURE} -p $packageName"
        binding.adbCommand.text = adbCommand
        binding.adbRow.setOnClickListener { copyToClipboard(adbCommand) }

        val adbCameraCommand =
            "adb shell am start -n $packageName/${CameraActivity::class.java.name}"
        binding.adbCameraCommand.text = adbCameraCommand
        binding.adbCameraRow.isVisible = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
        binding.adbCameraRow.setOnClickListener { copyToClipboard(adbCameraCommand) }
        applySegmentShapes(binding.adbRow, binding.adbCameraRow)

        binding.rowTargetLanguage.setOnClickListener { chooseTargetLanguage() }
        binding.rowLanguagePacks.setOnClickListener {
            LanguagePacksSheet(this) { refreshTranslationRows() }.show()
        }
        binding.rowEngine.setOnClickListener {
            TranslationEngineSheet(this) { refreshTranslationRows() }.show()
        }

        // Top Floating Toolbar in Viewer
        binding.btnBack.setOnClickListener { onBackFromViewer() }
        binding.btnSelectAll.setOnClickListener { binding.ocrView.selectAll() }
        binding.btnCopyAll.setOnClickListener {
            currentResult?.fullText?.takeIf { it.isNotBlank() }?.let { copyToClipboard(it) }
        }
        binding.btnShareAll.setOnClickListener {
            currentResult?.fullText?.takeIf { it.isNotBlank() }?.let { shareText(it) }
        }
        binding.btnTextMode.setOnClickListener { showSmartStudioSheet(currentResult?.fullText) }
        binding.btnVoiceReader.setOnClickListener {
            currentResult?.fullText?.takeIf { it.isNotBlank() }?.let { text ->
                if (ttsManager.isSpeaking) {
                    ttsManager.stop()
                    Snackbar.make(binding.root, R.string.tts_stopped, Snackbar.LENGTH_SHORT).show()
                } else {
                    ttsManager.speak(text)
                    Snackbar.make(binding.root, R.string.tts_playing, Snackbar.LENGTH_SHORT).show()
                }
            } ?: Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
        }

        binding.btnTranslateAll.setOnClickListener {
            if (binding.ocrView.translations != null) resetTranslation() else translateScreen()
        }
        binding.btnTranslateAll.setOnLongClickListener {
            chooseTargetLanguage()
            true
        }

        // Selection Toolbar
        binding.toolbarCopy.setOnClickListener {
            binding.ocrView.selectedText?.let {
                copyToClipboard(it)
                binding.ocrView.clearSelection()
            }
        }
        binding.toolbarSelectAll.setOnClickListener { binding.ocrView.selectAll() }
        binding.toolbarVoice.setOnClickListener {
            binding.ocrView.selectedText?.let { text ->
                ttsManager.speak(text)
                Snackbar.make(binding.root, R.string.tts_playing, Snackbar.LENGTH_SHORT).show()
            }
        }
        binding.toolbarShare.setOnClickListener { binding.ocrView.selectedText?.let { shareText(it) } }
        binding.toolbarSearch.setOnClickListener { binding.ocrView.selectedText?.let { searchWeb(it) } }

        // Google Circle to Search Bottom Dock
        binding.dockBtnSearch.setOnClickListener {
            val text = binding.ocrView.selectedText ?: currentResult?.fullText
            if (!text.isNullOrBlank()) {
                searchWeb(text)
            } else {
                searchWeb("Google Lens Visual Search")
            }
        }
        binding.dockBtnTranslate.setOnClickListener {
            if (binding.ocrView.translations != null) resetTranslation() else translateScreen()
        }
        binding.dockBtnCopy.setOnClickListener {
            binding.ocrView.selectedText?.let {
                copyToClipboard(it)
                binding.ocrView.clearSelection()
            }
        }
        binding.dockBtnVoice.setOnClickListener {
            binding.ocrView.selectedText?.let { text ->
                ttsManager.speak(text)
                Snackbar.make(binding.root, R.string.tts_playing, Snackbar.LENGTH_SHORT).show()
            }
        }
        binding.dockBtnStudio.setOnClickListener {
            showSmartStudioSheet(binding.ocrView.selectedText ?: currentResult?.fullText)
        }
        binding.dockBtnDismiss.setOnClickListener {
            binding.ocrView.clearSelection()
            binding.circleSearchDock.isVisible = false
        }

        binding.btnClearHistory.setOnClickListener { confirmClearHistory() }

        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.ocrView.hasSelection() -> binding.ocrView.clearSelection()
                binding.viewerGroup.isVisible -> onBackFromViewer()
                else -> finish()
            }
        }

        if (savedInstanceState != null) {
            if (intent.action == ACTION_CAMERA_PHOTO) intent.action = Intent.ACTION_MAIN
            cameraOpen = savedInstanceState.getBoolean(STATE_CAMERA_OPEN)
            launchedForCamera = cameraOpen && savedInstanceState.getBoolean(STATE_LAUNCHED_FOR_CAMERA)
        }
        handleIntent(intent)

        binding.root.post { contentReady = true }
    }

    private fun setupNavigationTabs() {
        binding.mainTabsGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            when {
                checkedIds.contains(R.id.tabStudio) -> switchTab(0)
                checkedIds.contains(R.id.tabHistory) -> switchTab(1)
                checkedIds.contains(R.id.tabTools) -> switchTab(2)
                checkedIds.contains(R.id.tabEngines) -> switchTab(3)
            }
        }
    }

    private fun switchTab(tab: Int) {
        binding.sectionStudio.isVisible = (tab == 0)
        binding.sectionHistory.isVisible = (tab == 1)
        binding.sectionTools.isVisible = (tab == 2)
        binding.sectionEngines.isVisible = (tab == 3)

        if (tab == 1) {
            renderHistoryList()
        }
    }

    private fun setupStudioActions() {
        binding.cardCamera.setOnClickListener { launchCamera() }
        binding.cardPickImage.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.cardClipboard.setOnClickListener { grabFromClipboard() }
    }

    private fun setupToolsActions() {
        binding.cardToolStudio.setOnClickListener {
            val text = currentResult?.fullText
                ?: HistoryManager.getAll(this).firstOrNull()?.text
            if (text != null) showSmartStudioSheet(text)
            else Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
        }

        binding.cardToolVoice.setOnClickListener {
            val text = currentResult?.fullText
                ?: HistoryManager.getAll(this).firstOrNull()?.text
            if (text != null) {
                showSmartStudioSheet(text, initialTab = R.id.chipTabVoice)
            } else {
                Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.cardToolFormat.setOnClickListener {
            val text = currentResult?.fullText
                ?: HistoryManager.getAll(this).firstOrNull()?.text
            if (text != null) {
                showSmartStudioSheet(text, initialTab = R.id.chipTabFormat)
            } else {
                Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun grabFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val item = clip.getItemAt(0)
            val uri = item.uri
            val text = item.text?.toString()
            if (uri != null) {
                openImage(uri)
                return
            } else if (!text.isNullOrBlank()) {
                showSmartStudioSheet(text)
                return
            }
        }
        Snackbar.make(binding.root, R.string.no_clipboard_image, Snackbar.LENGTH_SHORT).show()
    }

    private fun renderHistoryList() {
        val items = HistoryManager.getAll(this)
        binding.historyHeaderCount.text = getString(R.string.text_stats, items.size, 0, 0)
            .let { "${items.size} Saved Scans" }
        binding.historyEmptyView.isVisible = items.isEmpty()
        binding.historyItemsContainer.isVisible = items.isNotEmpty()
        binding.historyItemsContainer.removeAllViews()

        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val card = inflater.inflate(R.layout.item_history, binding.historyItemsContainer, false)
            val tag = card.findViewById<TextView>(R.id.historyTag)
            val date = card.findViewById<TextView>(R.id.historyDate)
            val title = card.findViewById<TextView>(R.id.historyTitle)
            val snippet = card.findViewById<TextView>(R.id.historySnippet)
            val stats = card.findViewById<TextView>(R.id.historyStats)
            val btnFav = card.findViewById<ImageView>(R.id.historyBtnFavorite)
            val btnDel = card.findViewById<ImageView>(R.id.historyBtnDelete)
            val btnCopy = card.findViewById<MaterialButton>(R.id.historyBtnCopy)

            tag.text = item.source.uppercase()
            date.text = android.text.format.DateFormat.format("MMM d, yyyy · HH:mm", item.timestamp)
            title.text = item.previewTitle
            snippet.text = item.text
            stats.text = "${item.wordCount} words · ${item.charCount} chars"

            btnFav.setImageResource(if (item.isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            btnFav.setOnClickListener {
                val isFav = HistoryManager.toggleFavorite(this, item.id)
                btnFav.setImageResource(if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            }

            btnDel.setOnClickListener {
                HistoryManager.delete(this, item.id)
                renderHistoryList()
                refreshHeroMetrics()
            }

            btnCopy.setOnClickListener {
                copyToClipboard(item.text)
            }

            card.setOnClickListener {
                showSmartStudioSheet(item.text)
            }

            binding.historyItemsContainer.addView(card)
        }
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_history)
            .setMessage(R.string.clear_history_confirm)
            .setPositiveButton(R.string.language_delete) { _, _ ->
                HistoryManager.clearAll(this)
                renderHistoryList()
                refreshHeroMetrics()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshHeroMetrics() {
        val totalScans = HistoryManager.getAll(this).size
        binding.heroMetricScans.text = "$totalScans Scans"
        val engine = EngineSettings.selected(this)
        binding.heroMetricEngine.text = getString(engine.title)
    }

    private fun showSmartStudioSheet(initialText: String?, initialTab: Int = R.id.chipTabText) {
        if (initialText.isNullOrBlank()) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            return
        }

        val textHolder = object {
            var value: String = initialText
        }
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_smart_studio, null)

        val statsText = view.findViewById<TextView>(R.id.studioStatsText)
        val fullTextView = view.findViewById<TextView>(R.id.studioFullText)
        val btnCopy = view.findViewById<View>(R.id.studioBtnCopy)
        val btnShare = view.findViewById<View>(R.id.studioBtnShare)
        val btnExport = view.findViewById<View>(R.id.studioBtnExport)

        val tabsGroup = view.findViewById<ChipGroup>(R.id.studioTabsGroup)
        val pageText = view.findViewById<View>(R.id.pageText)
        val pageEntities = view.findViewById<View>(R.id.pageEntities)
        val pageFormat = view.findViewById<View>(R.id.pageFormat)
        val pageVoice = view.findViewById<View>(R.id.pageVoice)

        fun updateTextDisplay() {
            fullTextView.text = textHolder.value
            val stats = TextFormatter.computeStats(textHolder.value)
            statsText.text = getString(R.string.text_stats, stats.wordCount, stats.charCountWithSpaces, stats.lineCount)
        }

        updateTextDisplay()

        btnCopy.setOnClickListener {
            copyToClipboard(textHolder.value)
            sheet.dismiss()
        }

        btnShare.setOnClickListener {
            shareText(textHolder.value)
        }

        btnExport.setOnClickListener {
            shareText(textHolder.value)
        }

        // Tab Switching
        fun switchStudioTab(tabId: Int) {
            pageText.isVisible = (tabId == R.id.chipTabText)
            pageEntities.isVisible = (tabId == R.id.chipTabEntities)
            pageFormat.isVisible = (tabId == R.id.chipTabFormat)
            pageVoice.isVisible = (tabId == R.id.chipTabVoice)
        }

        tabsGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            checkedIds.firstOrNull()?.let { switchStudioTab(it) }
        }

        // Smart Entities Setup
        val entitiesContainer = view.findViewById<LinearLayout>(R.id.entitiesContainer)
        val entitiesCountText = view.findViewById<TextView>(R.id.entitiesCountText)
        val entities = SmartEntityExtractor.extract(textHolder.value)

        if (entities.isEmpty()) {
            entitiesCountText.setText(R.string.no_entities_found)
        } else {
            entitiesCountText.text = getString(R.string.entities_found, entities.size)
            entitiesContainer.removeAllViews()
            val inflater = LayoutInflater.from(this)
            for (ent in entities) {
                val itemView = inflater.inflate(R.layout.item_entity, entitiesContainer, false)
                val iconView = itemView.findViewById<ImageView>(R.id.entityIcon)
                val labelView = itemView.findViewById<TextView>(R.id.entityLabel)
                val valView = itemView.findViewById<TextView>(R.id.entityValue)
                val actionBtn = itemView.findViewById<MaterialButton>(R.id.btnEntityAction)

                when (ent.type) {
                    EntityType.URL -> iconView.setImageResource(R.drawable.ic_link)
                    EntityType.EMAIL -> iconView.setImageResource(R.drawable.ic_email)
                    EntityType.PHONE -> iconView.setImageResource(R.drawable.ic_phone)
                    EntityType.DATE -> iconView.setImageResource(R.drawable.ic_sparkle)
                    EntityType.CURRENCY -> iconView.setImageResource(R.drawable.ic_sparkle)
                }
                labelView.text = ent.label
                valView.text = ent.value
                actionBtn.text = ent.actionLabel
                actionBtn.setOnClickListener {
                    if (ent.type == EntityType.CURRENCY) {
                        copyToClipboard(ent.value)
                    } else {
                        SmartEntityExtractor.executeAction(this, ent)
                    }
                }
                entitiesContainer.addView(itemView)
            }
        }

        // Formatter buttons setup
        view.findViewById<View>(R.id.btnFormatMerge).setOnClickListener {
            textHolder.value = TextFormatter.mergeLines(textHolder.value)
            updateTextDisplay()
        }
        view.findViewById<View>(R.id.btnFormatSpaces).setOnClickListener {
            textHolder.value = TextFormatter.trimExtraSpaces(textHolder.value)
            updateTextDisplay()
        }
        view.findViewById<View>(R.id.btnFormatUpper).setOnClickListener {
            textHolder.value = TextFormatter.toUpperCase(textHolder.value)
            updateTextDisplay()
        }
        view.findViewById<View>(R.id.btnFormatLower).setOnClickListener {
            textHolder.value = TextFormatter.toLowerCase(textHolder.value)
            updateTextDisplay()
        }
        view.findViewById<View>(R.id.btnFormatTitle).setOnClickListener {
            textHolder.value = TextFormatter.toTitleCase(textHolder.value)
            updateTextDisplay()
        }
        view.findViewById<View>(R.id.btnFormatBullets).setOnClickListener {
            textHolder.value = TextFormatter.toMarkdownBullets(textHolder.value)
            updateTextDisplay()
        }

        // Voice tab setup
        val ttsStatusText = view.findViewById<TextView>(R.id.ttsStatusText)
        val btnTtsPlay = view.findViewById<MaterialButton>(R.id.btnTtsPlay)
        val btnTtsStop = view.findViewById<MaterialButton>(R.id.btnTtsStop)
        val ttsSpeedLabel = view.findViewById<TextView>(R.id.ttsSpeedLabel)
        var selectedRate = 1.0f

        val speedGroup = view.findViewById<ChipGroup>(R.id.ttsSpeedGroup)
        speedGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedRate = when (checkedIds.firstOrNull()) {
                R.id.speedChip075 -> 0.75f
                R.id.speedChip125 -> 1.25f
                R.id.speedChip150 -> 1.50f
                else -> 1.0f
            }
            ttsSpeedLabel.text = getString(R.string.tts_speed, selectedRate)
        }

        ttsManager.onStatusChanged = { isSpeaking ->
            runOnUiThread {
                ttsStatusText.setText(if (isSpeaking) R.string.tts_playing else R.string.tts_stopped)
            }
        }

        btnTtsPlay.setOnClickListener {
            ttsManager.speak(textHolder.value, selectedRate)
        }
        btnTtsStop.setOnClickListener {
            ttsManager.stop()
        }

        sheet.setOnDismissListener {
            ttsManager.stop()
        }

        // Check the requested initial tab
        tabsGroup.check(initialTab)
        switchStudioTab(initialTab)

        sheet.setContentView(view)
        sheet.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LAUNCHED_FOR_CAMERA, launchedForCamera)
        outState.putBoolean(STATE_CAMERA_OPEN, cameraOpen)
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        refreshTranslationRows()
        refreshHeroMetrics()
    }

    override fun onStop() {
        super.onStop()
        if (ocrJob?.isActive == true || translateJob?.isActive == true) return
        OcrEngine.release()
        Translator.release()
    }

    override fun onDestroy() {
        clearViewerContent()
        ttsManager.shutdown()
        super.onDestroy()
    }

    private fun refreshSetup() {
        binding.btnAccessibility.isVisible = ScreenCapture.supported && !ScreenCapture.isEnabled(this)
        binding.btnSetAssistant.isVisible = !isAssistantApp()
        val needsSetup = binding.btnAccessibility.isVisible || binding.btnSetAssistant.isVisible
        binding.setupHeader.isVisible = needsSetup
        binding.setupCard.isVisible = needsSetup
        applySegmentShapes(binding.btnAccessibility, binding.btnSetAssistant)
    }

    private fun applySegmentShapes(vararg rows: MaterialCardView) {
        val shown = rows.filter { it.isVisible }
        shown.forEachIndexed { index, row ->
            val shape = when {
                shown.size == 1 -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Single
                index == 0 -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_First
                index == shown.lastIndex -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Last
                else -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Middle
            }
            row.shapeAppearanceModel = ShapeAppearanceModel.builder(
                this,
                shape,
                com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Single
            ).build()
        }
    }

    private fun refreshTranslationRows() {
        binding.textTargetLanguage.text = Translator.displayName(Translator.defaultTarget(this))
        val engine = EngineSettings.selected(this)
        binding.textEngine.text = getString(engine.title).let {
            if (engine.isCloud) getString(R.string.engine_row_summary_cloud, it) else it
        }

        binding.rowLanguagePacks.isVisible = !engine.isCloud
        lifecycleScope.launch {
            val count = runCatching { Translator.downloadedLanguages().size }.getOrNull() ?: return@launch
            binding.textLanguagePacks.text = getString(R.string.language_packs_summary, count)
        }
    }

    private fun isAssistantApp(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val roles = getSystemService(android.app.role.RoleManager::class.java) ?: return false
        return roles.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }

        val photoTaken = intent.action == ACTION_CAMERA_PHOTO
        val captureRequested = intent.getBooleanExtra(EXTRA_CAPTURED_SCREEN, false)
        intent.removeExtra(EXTRA_CAPTURED_SCREEN)
        intent.action = Intent.ACTION_MAIN
        intent.data = null

        when {
            uri != null -> {
                launchedWithImage = true
                openImage(uri)
            }
            captureRequested -> {
                val captured = CaptureHolder.take()
                if (captured != null) {
                    launchedWithImage = true
                    openBitmap(captured)
                } else if (this is CaptureViewerActivity) finish() else showHome()
            }
            else -> {
                CaptureHolder.take()
                when {
                    photoTaken -> {
                        launchedForCamera = true
                        openImage(Uri.fromFile(cameraFile), fromCamera = true)
                    }
                    cameraOpen -> showCameraPending()
                    else -> showHome()
                }
            }
        }
    }

    private fun launchCamera() {
        cameraOpen = true
        showCameraPending()
        takePhoto.launch(Intent(this, CameraActivity::class.java))
    }

    private fun showCameraPending() {
        clearViewerContent()
        viewingCameraPhoto = false
        binding.homeGroup.isVisible = false
        binding.viewerGroup.isVisible = false
        binding.progressGroup.isVisible = false
    }

    private fun openImage(uri: Uri, fromCamera: Boolean = false) {
        viewingCameraPhoto = fromCamera
        binding.ocrView.capturePreview = false

        showViewer(loading = true)
        startOcr {
            try {
                val bitmap = try {
                    ImageLoader.load(this@MainActivity, uri)
                } finally {
                    if (fromCamera) cameraFile.delete()
                }
                processBitmap(bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TextGrab", "Failed to open $uri", e)
                showHome()
                Snackbar.make(
                    binding.root,
                    getString(R.string.load_failed, e.localizedMessage ?: ""),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openBitmap(bitmap: Bitmap) {
        viewingCameraPhoto = false
        binding.ocrView.capturePreview = true
        clearOpenTransition()

        showImage(bitmap)
        binding.ocrView.playCaptureEntry()
        playToolbarEntry()
        startOcr { recognize(bitmap) }
    }

    private fun clearOpenTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.no_anim, R.anim.no_anim)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.no_anim, R.anim.no_anim)
        }
    }

    private fun finishWithoutTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.no_anim, R.anim.no_anim)
            finish()
        } else {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.no_anim, R.anim.no_anim)
        }
    }

    private fun playToolbarEntry() {
        val bar = binding.topBar
        bar.alpha = 0f
        bar.translationY = -bar.height.coerceAtLeast(TOOLBAR_SLIDE_FALLBACK_PX) * TOOLBAR_SLIDE
        bar.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DELAY).toLong())
            .setDuration((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DURATION).toLong())
            .setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)

        binding.ocrProgress.alpha = 0f
        binding.ocrProgress.animate()
            .alpha(1f)
            .setStartDelay((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DELAY).toLong())
            .setDuration((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DURATION).toLong())
            .setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)
    }

    private fun startOcr(block: suspend () -> Unit) {
        ocrJob?.cancel()
        ocrJob = lifecycleScope.launch { block() }
    }

    private suspend fun processBitmap(bitmap: Bitmap) {
        showImage(bitmap)
        recognize(bitmap)
    }

    private fun showImage(bitmap: Bitmap) {
        resetTranslation()
        binding.selectionToolbar.isVisible = false
        for (v in arrayOf(binding.topBar, binding.ocrProgress, binding.selectionToolbar)) {
            v.animate().cancel()
            v.alpha = 1f
        }

        binding.topBar.translationY = 0f
        currentResult = null
        currentBitmap = bitmap
        binding.ocrView.setImage(bitmap)
        showViewer(loading = false)
    }

    private suspend fun recognize(bitmap: Bitmap) {
        binding.ocrProgress.show()
        try {
            val codes = try {
                QrScanner.scan(bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("TextGrab", "QR scan failed", e)
                emptyList()
            }
            val result = OcrEngine.recognize(bitmap, codes.map { it.box })
            currentResult = result
            binding.ocrView.setContent(bitmap, result, codes)
            if (result.isEmpty && codes.isEmpty()) {
                Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_LONG).show()
            } else if (!result.isEmpty) {
                val src = when {
                    viewingCameraPhoto -> "Camera"
                    binding.ocrView.capturePreview -> "Screen"
                    else -> "Image"
                }
                HistoryManager.add(this@MainActivity, result.fullText, src)
                refreshHeroMetrics()
            }
        } finally {
            binding.ocrProgress.hide()
        }
    }

    private fun showHome() {
        clearViewerContent()
        binding.homeGroup.isVisible = true
        binding.viewerGroup.isVisible = false
        binding.progressGroup.isVisible = false
        launchedWithImage = false
        viewingCameraPhoto = false
        refreshHeroMetrics()
    }

    private fun showViewer(loading: Boolean) {
        binding.homeGroup.isVisible = false
        binding.viewerGroup.isVisible = true
        binding.progressGroup.isVisible = loading
        if (loading) {
            binding.selectionToolbar.isVisible = false
            clearViewerContent()
        }
    }

    private fun clearViewerContent() {
        ocrJob?.cancel()
        ocrJob = null
        ttsManager.stop()
        resetTranslation()
        binding.selectionToolbar.isVisible = false
        binding.circleSearchDock.isVisible = false
        binding.ocrProgress.hide()
        binding.ocrView.clear()
        currentBitmap = null
        currentResult = null
    }

    private fun onBackFromViewer() {
        if (viewingCameraPhoto) {
            launchCamera()
            return
        }

        if (launchedWithImage && binding.ocrView.canPlayCaptureExit) {
            val fadeOut = (SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DURATION).toLong()
            for (v in arrayOf(binding.topBar, binding.ocrProgress, binding.selectionToolbar, binding.circleSearchDock)) {
                v.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setDuration(fadeOut)
                    .setInterpolator(SelectableOcrView.EMPHASIZED_ACCELERATE)
            }
            ocrJob?.cancel()
            binding.ocrProgress.hide()
            binding.ocrView.playCaptureExit { finishWithoutTransition() }
            return
        }

        clearViewerContent()
        if (launchedWithImage) finish() else showHome()
    }

    override fun onSelectionChanged(text: String?, anchor: RectF?) {
        if (text == null || anchor == null) {
            binding.selectionToolbar.isVisible = false
            binding.circleSearchDock.isVisible = false
            return
        }
        binding.selectionToolbar.isVisible = true
        binding.selectionToolbar.post { positionToolbar(anchor) }

        // Animate Circle to Search dock up from bottom
        binding.circleSearchDock.isVisible = true
        binding.circleSearchDock.alpha = 0f
        binding.circleSearchDock.translationY = 60f
        binding.circleSearchDock.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240)
            .setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)
            .start()
    }

    override fun onRegionCircled(region: RectF) {
        // Area or object circled (Google Circle to Search gesture)
        binding.circleSearchDock.isVisible = true
        binding.circleSearchDock.alpha = 0f
        binding.circleSearchDock.translationY = 60f
        binding.circleSearchDock.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240)
            .setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)
            .start()

        Snackbar.make(binding.root, "Area circled · Tap Search to look up online", Snackbar.LENGTH_SHORT).show()
    }

    override fun onCodeTapped(code: QrScanner.Code) {
        val text = code.text
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_text, null)
        view.findViewById<TextView>(R.id.sheetTitle).setText(R.string.qr_code)
        view.findViewById<TextView>(R.id.sheetText).text = text
        view.findViewById<View>(R.id.sheetCopy).setOnClickListener {
            copyToClipboard(text)
            sheet.dismiss()
        }
        view.findViewById<View>(R.id.sheetShare).setOnClickListener { shareText(text) }
        val link = Uri.parse(text.trim()).takeIf { it.scheme?.lowercase() in OPENABLE_SCHEMES }
        view.findViewById<View>(R.id.sheetOpen).apply {
            isVisible = link != null
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, link)) }
                    .onSuccess { sheet.dismiss() }
            }
        }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun positionToolbar(anchor: RectF) {
        val toolbar = binding.selectionToolbar
        val container = binding.viewerGroup
        val margin = resources.displayMetrics.density * 12f

        var x = anchor.centerX() - toolbar.width / 2f
        x = max(margin, min(x, container.width - toolbar.width - margin))

        var y = anchor.top - toolbar.height - margin * 1.5f
        if (y < container.height * 0.12f) y = anchor.bottom + margin * 1.5f
        y = max(margin, min(y, container.height - toolbar.height - margin))

        toolbar.translationX = x
        toolbar.translationY = y
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))

        if (Build.VERSION.SDK_INT < 33) {
            Snackbar.make(binding.root, R.string.copied, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, null))
    }

    private fun searchWeb(text: String) {
        val search = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, text)
        }
        if (search.resolveActivity(packageManager) != null) {
            startActivity(search)
        } else {
            val browse = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://duckduckgo.com/?q=${Uri.encode(text)}")
            )
            runCatching { startActivity(browse) }
        }
    }

    private fun translateScreen() {
        val result = currentResult ?: return
        if (result.isEmpty) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            return
        }
        val target = Translator.defaultTarget(this)
        translateJob?.cancel()
        translateJob = lifecycleScope.launch {
            binding.ocrProgress.show()
            var downloadNote: Snackbar? = null
            try {
                val outcome = Translator.translate(this@MainActivity, result, target) {
                    downloadNote = Snackbar.make(
                        binding.root, R.string.downloading_models, Snackbar.LENGTH_INDEFINITE
                    ).also { it.show() }
                }
                if (currentResult !== result) return@launch
                if (outcome.sources.isEmpty()) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.already_in_language, Translator.displayName(target)),
                        Snackbar.LENGTH_LONG
                    ).setAction(R.string.change_language) { chooseTargetLanguage() }.show()
                    return@launch
                }
                binding.ocrView.translations = outcome.lines
                setTranslateActive(true)
                Snackbar.make(
                    binding.root,
                    getString(
                        R.string.translated_from,
                        outcome.sources.joinToString(", ") { Translator.displayName(it) },
                        Translator.displayName(target)
                    ),
                    Snackbar.LENGTH_LONG
                ).setAction(R.string.change_language) { chooseTargetLanguage() }.show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TextGrab", "Translation failed", e)
                Snackbar.make(
                    binding.root,
                    getString(R.string.translation_failed, e.localizedMessage ?: ""),
                    Snackbar.LENGTH_LONG
                ).show()
            } finally {
                downloadNote?.dismiss()
                binding.ocrProgress.hide()
            }
        }
    }

    private fun resetTranslation() {
        translateJob?.cancel()
        translateJob = null
        binding.ocrView.translations = null
        setTranslateActive(false)
    }

    private fun setTranslateActive(active: Boolean) {
        binding.btnTranslateAll.isChecked = active
        binding.btnTranslateAll.contentDescription =
            getString(if (active) R.string.show_original else R.string.translate)
    }

    private fun chooseTargetLanguage() {
        val codes = Translator.languages.sortedBy { Translator.displayName(it) }
        val names = codes.map { Translator.displayName(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.translate_to)
            .setSingleChoiceItems(names, codes.indexOf(Translator.defaultTarget(this))) { dialog, which ->
                dialog.dismiss()
                Translator.saveTarget(this, codes[which])
                refreshTranslationRows()
                if (binding.viewerGroup.isVisible) translateScreen()
            }
            .show()
    }
}
