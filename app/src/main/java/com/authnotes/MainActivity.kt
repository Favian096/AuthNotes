package com.authnotes

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.VelocityTracker
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.authnotes.data.AccountCard
import com.authnotes.data.CardAttachment
import com.authnotes.data.NewCardAttachment
import com.authnotes.data.TotpAccount
import com.authnotes.data.otpConfig
import com.authnotes.data.VaultDatabase
import com.authnotes.data.VaultStore
import com.authnotes.security.TimeCorrection
import com.authnotes.security.Totp
import com.authnotes.security.TotpConfig
import com.authnotes.security.GoogleMigration
import com.authnotes.ui.SmoothDrawable
import com.authnotes.ui.UnderlineDrawable
import com.authnotes.ui.home.AccountListPage
import com.authnotes.ui.home.AuthenticatorListPage
import com.authnotes.ui.migration.MigrationPage
import com.authnotes.ui.ColorStripView
import com.authnotes.ui.EncryptedDatabaseIconView
import com.authnotes.ui.ReorderColumn
import com.authnotes.ui.FloatingEditorDialog
import com.authnotes.ui.ActionIconView
import com.authnotes.ui.decodeAttachmentPreview
import com.authnotes.ui.ZoomRotateImageView
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var store: VaultStore
    private val db get() = checkNotNull(store.database)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var homeVisible = false
    private var page = 0
    private enum class SubPage { IMPORT, PARSER, SETTINGS, RECYCLE, ABOUT, MIGRATION }
    private var subPage: SubPage? = null
    private var selectedImportUri: Uri? = null
    private var importPasswordDraft = ""
    private var setupUnlock = false
    private var detailShowing = false
    private val queries = arrayOf("", "")
    private val searchShown = booleanArrayOf(false, false)
    private var offsetMillis = 0L
    private lateinit var root: FrameLayout
    private lateinit var bar: LinearLayout
    private lateinit var titleSlot: FrameLayout
    private lateinit var title: TextView
    private lateinit var search: EditText
    private lateinit var addPanel: LinearLayout
    private lateinit var addPanelBody: LinearLayout
    private lateinit var addReveal: View
    private lateinit var menuButton: ImageView
    private lateinit var backButton: ImageView
    private lateinit var searchButton: ImageView
    private lateinit var closeSearchButton: ImageView
    private var detailHeaderTitle: TextView? = null
    private var detailDeleteButton: TextView? = null
    private lateinit var accountPage: AccountListPage
    private lateinit var authenticatorPage: AuthenticatorListPage
    private fun listPage(index: Int) = if (index == 0) accountPage else authenticatorPage
    private val scroll get() = listPage(page).view
    private var drawer: FrameLayout? = null
    private var drawerClosing = false
    private var pageTransition = false
    private var panelAnimator: ValueAnimator? = null
    private var addExpanded = false
    private var inlineTotp = false
    private var searchAnimator: ValueAnimator? = null
    private var editorShowing = false
    private var activeEditor: FloatingEditorDialog? = null
    private var pendingAttachmentEditor: CardEditor? = null
    private var pendingAttachmentSave: NewCardAttachment? = null
    private var migrationPage: MigrationPage? = null
    private var updatingSearch = false
    private var downX = 0f
    private var downY = 0f
    private var gesture = 0
    private var pageScrubInProgress = false
    private var pageScrubWidth = 1f
    private var drawerScrubInProgress = false
    private var drawerDragOrigin = 0f
    private var startsOnHeader = false
    private var startsOnList = false
    private var startsAtTop = false
    private var dragging = false
    private var editingTouch = false
    private var velocityTracker: VelocityTracker? = null
    private var selectedAccountColor = 0
    private var selectedTotpColor = 0
    private var pullReveal = false
    private var detailOverlay: FrameLayout? = null
    private var detailSurface: LinearLayout? = null
    private var detailClosing = false
    private val protectedFields = mutableListOf<TextView>()
    private var secureTransition = false
    private var detailSourceRect: android.graphics.Rect? = null
    private val palette = iosPalette()
    private data class PreparedAttachment(val item: CardAttachment, val bytes: ByteArray?, val thumbnail: Bitmap?)
    private val tick = object : Runnable {
        override fun run() {
            if (homeVisible && subPage == null && !busy && page == 1) updateCodes()
            main.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getInt("page") ?: 0
        store = VaultStore(applicationContext)
        screen(column().apply { addView(text("AuthNotes", 28, true)); addView(text("正在打开数据…")) })
        work("正在解锁数据库…", { store.autoUnlock() }) { unlocked ->
            if (unlocked) showHome() else showSetup(unlock = store.exists)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (store.database != null) {
            activeEditor?.dismissImmediately()
            when {
                detailOverlay != null -> closeDetailOverlay { handleShortcut() }
                drawer != null -> closeDrawer { handleShortcut() }
                subPage != null -> { subPage = null; showHome() }
                else -> handleShortcut()
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("page", page); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); main.removeCallbacks(tick); main.post(tick) }
    override fun onPause() {
        protectedFields.forEach { it.text = "••••••" }
        main.removeCallbacks(tick)
        super.onPause()
    }
    override fun onDestroy() {
        activeEditor?.dismissImmediately()
        clearMigrationExport()
        panelAnimator?.cancel(); searchAnimator?.cancel()
        drawer = null
        velocityTracker?.recycle(); velocityTracker = null
        main.removeCallbacks(tick)
        io.execute { store.close() }
        io.shutdown()
        super.onDestroy()
    }

    private fun <T> work(message: String, task: () -> T, done: (T) -> Unit) {
        if (busy || isDestroyed) return
        busy = true
        val progress = alertBuilder().setMessage(message).setCancelable(false).create()
        progress.show()
        io.execute {
            val result = runCatching(task)
            main.post {
                if (isDestroyed || isFinishing) return@post
                busy = false
                progress.dismiss()
                result.onSuccess(done).onFailure {
                    val message = if (subPage == SubPage.IMPORT) "鉴权失败或数据库文件无效，请重新输入密码并确认选择的是 AuthNotes 备份。原数据库未被更改。"
                    else if (store.exists && store.database == null) "鉴权失败，请重新输入密码。数据库文件未被更改。"
                    else it.message ?: "操作未完成，请重试"
                    notify(message)
                    if (store.database == null && subPage != SubPage.IMPORT) showSetup(store.exists)
                }
            }
        }
    }

    private fun showSetup(unlock: Boolean) {
        homeVisible = false
        subPage = null
        setupUnlock = unlock
        val body = column().apply { setBackgroundColor(SURFACE); gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(28), 0, dp(28), 0) }
        val topSpace = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) }
        body.addView(topSpace)
        val brand = text("AuthNotes", 30, true).apply { gravity = Gravity.CENTER }
        body.addView(brand, LinearLayout.LayoutParams(-1, -2))
        val rule = View(this).apply { setBackgroundColor(RULE) }
        body.addView(rule, LinearLayout.LayoutParams(dp(180), dp(1)).apply { topMargin = dp(14); bottomMargin = dp(24) })
        body.addView(text(if (unlock) "鉴权失败，请重新输入数据主密码" else "", 14, color = MUTED).apply { gravity = Gravity.CENTER })
        body.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val form = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val password = input("数据主密码", secret = true)
        val confirmation = input("再次输入数据主密码", secret = true)
        password.gravity = Gravity.CENTER
        confirmation.gravity = Gravity.CENTER
        form.addView(password, LinearLayout.LayoutParams(-1, dp(58)))
        if (!unlock) form.addView(confirmation, LinearLayout.LayoutParams(-1, dp(58)))
        form.addView(button(if (unlock) "解锁数据库" else "创建数据库") {
            val value = password.text.toString()
            if (value.isEmpty()) { notify("请输入数据主密码"); return@button }
            if (!unlock && (value.length < 4 || value != confirmation.text.toString())) {
                notify("主密码至少 4 个字符，两次输入须一致"); return@button
            }
            work("正在打开加密数据库…", {
                if (unlock) store.unlock(value) else store.create(value)
            }) { autoUnlockReady ->
                password.text.clear(); confirmation.text.clear(); showHome()
                if (!autoUnlockReady) notify("鉴权成功，但本机自动解锁暂不可用。下次启动时请手动输入数据主密码。")
            }
        }.apply { setTextColor(ACCENT); textSize = 18f }, LinearLayout.LayoutParams(-1, dp(58)))
        body.addView(form, LinearLayout.LayoutParams(-1, -2))
        val bottomSpace = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) }
        body.addView(bottomSpace)
        body.addView(button("导入备份") { openSubPage(SubPage.IMPORT) }.apply { setTextColor(BLUE) })
        body.addView(text(if (unlock) "鉴权失败时，可重新输入主密码" else "设置数据主密码，或导入已有 AuthNotes 数据库。", 12, color = MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(22))
        })
        transitionContent(body, -1f)
    }

    private fun showHome(direction: Float = 1f) {
        panelAnimator?.cancel(); searchAnimator?.cancel()
        clearMigrationExport()
        homeVisible = true
        detailShowing = false; detailOverlay = null; detailSurface = null; detailClosing = false
        drawer = null; drawerClosing = false; pageTransition = false
        window.statusBarColor = SURFACE; window.navigationBarColor = SURFACE
        root = layoutInflater.inflate(R.layout.activity_home, null) as FrameLayout
        bar = root.findViewById(R.id.home_toolbar)
        titleSlot = root.findViewById(R.id.home_title_slot)
        title = root.findViewById(R.id.home_title)
        menuButton = root.findViewById<ImageView>(R.id.home_menu).apply { setOnClickListener { showDrawer() } }
        backButton = root.findViewById<ImageView>(R.id.home_back).apply { setOnClickListener { closeDrawer() } }
        searchButton = root.findViewById<ImageView>(R.id.home_search_open).apply { setOnClickListener { toggleSearch() } }
        closeSearchButton = root.findViewById<ImageView>(R.id.home_search_close).apply { setOnClickListener { toggleSearch() } }
        search = root.findViewById<EditText>(R.id.home_search).apply {
            configureInput(this, "")
            addTextChangedListener(TextChanged { value ->
                if (!updatingSearch && queries[page] != value) { queries[page] = value; render(false) }
            })
        }
        addPanel = root.findViewById<LinearLayout>(R.id.home_add_panel).apply {
            background = SmoothDrawable(SURFACE, resources.displayMetrics.density, 24f, RULE, .5f, roundTop = false)
        }
        addReveal = root.findViewById<View>(R.id.home_add_reveal).apply { setBackgroundColor(palette.random()) }
        val pageHost = root.findViewById<FrameLayout>(R.id.home_page_host)
        accountPage = AccountListPage(this, pageHost, { db }, { dragging = it }, ::notify, ::showCard)
        authenticatorPage = AuthenticatorListPage(this, pageHost, { db }, { dragging = it }, ::notify, ::showTotpCard)
        for (index in 0..1) {
            val view = listPage(index).view
            view.visibility = if (index == page) View.VISIBLE else View.INVISIBLE
            pageHost.addView(view)
        }
        pageScrubInProgress = false
        offsetMillis = db.setting("time_offset_ms", "0").toLongOrNull() ?: 0L

        addExpanded = false; inlineTotp = false; pullReveal = false
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (addExpanded && !pullReveal && panelAnimator?.isRunning != true) {
                val height = panelTargetHeight()
                if (height != addPanel.layoutParams.height) animatePanelHeight(height)
            }
        }
        transitionContent(root, direction)
        syncSecureFlag()
        restoreHeader()
        render()
        val shownRoot = root
        shownRoot.postOnAnimation {
            shownRoot.postOnAnimation {
                if (root === shownRoot && shownRoot.isAttachedToWindow && homeVisible && subPage == null && !busy) {
                    prepareList(1 - page)
                }
            }
        }
        handleShortcut()
    }

    private fun handleShortcut() {
        val action = intent.action
        intent.action = Intent.ACTION_MAIN
        when (action) {
            ACTION_ADD -> { selectPage(0); toggleAdd(forceOpen = true) }
            ACTION_SCAN -> { selectPage(1); openScanner() }
        }
    }

    private fun iosPalette(): IntArray {
        val base = intArrayOf(0xffff3b30.toInt(), 0xffff9500.toInt(), 0xffffcc00.toInt(), 0xff34c759.toInt(),
            0xff00c7be.toInt(), 0xff30b0c7.toInt(), 0xff32ade6.toInt(), 0xff007aff.toInt(),
            0xff5856d6.toInt(), 0xffaf52de.toInt(), 0xffff2d55.toInt(), 0xffa2845e.toInt())
        fun mix(from: Int, to: Int, ratio: Float) = Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio).toInt())
        return base.flatMap { color -> listOf(color, mix(color, Color.WHITE, .24f), mix(color, Color.BLACK, .18f)) }.toIntArray()
    }

    private fun showHeaderIcon(old: ImageView, next: ImageView, useNext: Boolean, direction: Int) {
        val outgoing = if (useNext) old else next
        val incoming = if (useNext) next else old
        outgoing.animate().cancel(); incoming.animate().cancel()
        outgoing.isClickable = false; incoming.isClickable = false
        outgoing.animate().alpha(0f).translationX(dp(12 * direction).toFloat()).setDuration(120).withEndAction {
            outgoing.visibility = View.GONE; outgoing.translationX = 0f
        }.start()
        incoming.visibility = View.VISIBLE
        incoming.translationX = dp(12 * direction).toFloat(); incoming.alpha = 0f
        incoming.animate().alpha(1f).translationX(0f).setDuration(180).setInterpolator(DecelerateInterpolator())
            .withEndAction { incoming.isClickable = true }.start()
    }

    private fun restoreHeader() {
        title.animate().cancel(); search.animate().cancel(); searchAnimator?.cancel()
        title.setText(if (page == 0) R.string.account_page_title else R.string.otp_page_title)
        title.alpha = if (searchShown[page]) 0f else 1f
        title.translationX = 0f; title.translationY = 0f
        updatingSearch = true
        search.setText(queries[page])
        updatingSearch = false
        search.setHint(if (page == 0) R.string.account_search_hint else R.string.otp_search_hint)
        search.visibility = if (searchShown[page]) View.VISIBLE else View.INVISIBLE
        search.translationX = 0f; search.alpha = if (searchShown[page]) 1f else 0f
        (search.background as? UnderlineDrawable)?.reveal = 1f
        showHeaderIcon(searchButton, closeSearchButton, searchShown[page], 1)
    }

    private fun selectPage(selected: Int) {
        if (selected == page || pageTransition) return
        val fromScrub = pageScrubInProgress
        val direction = if (selected > page) -1f else 1f
        val outgoing = scroll
        val needsSecure = page == 1 || selected == 1
        closeAddPanel(immediate = true)
        hideKeyboard()
        page = selected
        pageTransition = true
        if (needsSecure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val outgoingTitle = text(title.text.toString(), 20, true).apply {
            gravity = Gravity.CENTER; alpha = title.alpha
            translationX = title.translationX; translationY = title.translationY
        }
        titleSlot.addView(outgoingTitle, FrameLayout.LayoutParams(-1, dp(56)))
        outgoingTitle.animate().translationX(direction * dp(12)).alpha(0f).setDuration(95).withEndAction {
            titleSlot.removeView(outgoingTitle)
        }.start()
        restoreHeader()
        if (searchShown[page]) {
            search.translationX = -direction * dp(12); search.alpha = 0f
            search.animate().translationX(0f).alpha(1f).setDuration(110).start()
        }
        title.animate().cancel()
        title.translationX = -direction * dp(12)
        title.alpha = 0f
        title.animate().translationX(0f).alpha(if (searchShown[page]) 0f else 1f).setDuration(100).start()
        prepareList(page)
        if (page == 1) updateCodes()
        val incoming = scroll
        incoming.visibility = View.VISIBLE
        if (!fromScrub) {
            incoming.translationX = -direction * dp(28)
            incoming.alpha = 0f
        }
        val travel = if (fromScrub) pageScrubWidth else dp(28).toFloat()
        outgoing.animate().translationX(direction * travel).alpha(0f).setDuration(210).start()
        incoming.animate().translationX(0f).alpha(1f).setDuration(230).setInterpolator(DecelerateInterpolator()).withEndAction {
            outgoing.visibility = View.INVISIBLE; outgoing.translationX = 0f; outgoing.alpha = 1f
            pageTransition = false; pageScrubInProgress = false; syncSecureFlag()
        }.start()
    }

    private fun settlePageSwipe() {
        pageScrubInProgress = false
        pageTransition = true
        val incoming = listPage(1 - page).view
        val outgoing = scroll
        val direction = if (page == 0) 1f else -1f
        outgoing.animate().translationX(0f).alpha(1f).setDuration(170).start()
        incoming.animate().translationX(direction * pageScrubWidth).alpha(.72f).setDuration(170).withEndAction {
            incoming.visibility = View.INVISIBLE; incoming.translationX = 0f; incoming.alpha = 1f
            pageTransition = false; syncSecureFlag()
        }.start()
        title.animate().translationX(0f).alpha(if (searchShown[page]) 0f else 1f).setDuration(170).start()
    }

    private fun toggleSearch() {
        if (pageTransition) return
        val opening = !searchShown[page]
        closeAddPanel(immediate = true)
        searchShown[page] = opening
        search.animate().cancel(); title.animate().cancel(); searchAnimator?.cancel()
        showHeaderIcon(searchButton, closeSearchButton, opening, 1)
        val line = search.background as? UnderlineDrawable
        if (opening) {
            title.animate().translationY(-dp(10).toFloat()).alpha(0f).setDuration(170).start()
            search.visibility = View.VISIBLE; search.alpha = 0f; search.translationX = dp(18).toFloat()
            line?.reveal = 0f
            searchAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220; interpolator = DecelerateInterpolator()
                addUpdateListener { line?.reveal = it.animatedValue as Float }
                start()
            }
            val targetPage = page
            search.animate().translationX(0f).alpha(1f).setDuration(220).withEndAction {
                if (page == targetPage && searchShown[page]) { search.requestFocus(); keyboard(search) }
            }.start()
        } else {
            search.clearFocus(); hideKeyboard()
            search.animate().alpha(0f).translationX(dp(12).toFloat()).setDuration(140).withEndAction {
                if (!searchShown[page]) search.visibility = View.INVISIBLE
            }.start()
            title.animate().translationY(0f).alpha(1f).setDuration(180).start()
            queries[page] = ""
            updatingSearch = true; search.setText(""); updatingSearch = false
            render(false)
        }
    }

    private fun toggleAdd(forceOpen: Boolean = false, followTouch: Boolean = false) {
        if (pageTransition && !forceOpen) return
        if (addExpanded && !forceOpen) { closeAddPanel(); return }
        if (searchShown[page]) {
            searchShown[page] = false; queries[page] = ""
            restoreHeader(); render(false)
        }
        search.clearFocus()
        panelAnimator?.cancel(); addPanel.animate().cancel()
        if (!pullReveal) {
            if (page == 0) selectedAccountColor = palette.random() else selectedTotpColor = palette.random()
        }
        pullReveal = followTouch; addExpanded = !followTouch; inlineTotp = false
        addPanel.alpha = 1f; addPanel.translationY = 0f
        addReveal.setBackgroundColor(if (page == 0) selectedAccountColor else selectedTotpColor)
        addPanel.removeAllViews()
        addPanel.addView(addReveal, LinearLayout.LayoutParams(-1, dp(4)))
        // Keep the contents at their final size; the outer panel clips the revealed height.
        // Otherwise small pull heights squeeze the weighted scroller behind the footer.
        addPanelBody = column().apply {
            importantForAccessibility = if (followTouch) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        addPanel.addView(addPanelBody, LinearLayout.LayoutParams(-1, panelTargetHeight() - dp(4)))
        if (page == 0) {
            val editor = CardEditor(null)
            addPanelBody.addView(scroller(editor.body), LinearLayout.LayoutParams(-1, 0, 1f))
            val actions = LinearLayout(this)
            actions.addView(button("取消") { closeAddPanel() }.apply { setTextColor(MUTED) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(button("增加标签") { editor.addCustomField() }.apply { setTextColor(BLUE) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(button("添加") { editor.save { closeAddPanel(); render() } }.apply { setTextColor(ACCENT) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addPanelBody.addView(actions)
        } else {
            val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; pad(10) }
            actions.addView(iconAction(false, "扫描二维码") { openScanner() }, LinearLayout.LayoutParams(0, dp(104), 1f))
            actions.addView(iconAction(true, "输入设置密钥") { showInlineTotp() }, LinearLayout.LayoutParams(0, dp(104), 1f))
            addPanelBody.addView(actions)
        }
        addPanel.visibility = View.VISIBLE
        if (followTouch) {
            addPanel.layoutParams = addPanel.layoutParams.apply { height = 0 }
            addPanel.requestLayout()
        } else animatePanelHeight(panelTargetHeight())
        addReveal.scaleX = if (followTouch) 0f else 1f
        addReveal.post { addReveal.pivotX = addReveal.width / 2f }
        if (page == 0 && !followTouch) focusPanelInput()
    }

    private fun focusPanelInput() {
        val expectedPage = page
        addPanel.postDelayed({
            if (addExpanded && page == expectedPage && subPage == null && !editorShowing) {
                (addPanel.findViewWithTag<View>("initial-input") as? EditText)?.let { it.requestFocus(); keyboard(it) }
            }
        }, 260)
    }

    private fun panelTargetHeight(): Int {
        val available = (root.height - bar.height).coerceAtLeast(dp(120))
        return if (page == 1 && !inlineTotp) minOf(dp(128), available)
        else minOf(dp(390), (available * .76f).toInt())
    }

    private fun closeAddPanel(immediate: Boolean = false) {
        if (!::addPanel.isInitialized) return
        addExpanded = false; inlineTotp = false; pullReveal = false
        panelAnimator?.cancel(); addPanel.animate().cancel()
        addPanel.clearFocus(); hideKeyboard()
        if (immediate) {
            addPanel.visibility = View.GONE; addPanel.layoutParams.height = 0; addPanel.requestLayout()
            addPanel.alpha = 1f; addPanel.translationY = 0f; addReveal.scaleX = 0f
        } else if (addPanel.visibility == View.VISIBLE) animatePanelHeight(0)
    }

    private fun animatePanelHeight(height: Int) {
        panelAnimator?.cancel()
        if (height > 0 && ::addPanelBody.isInitialized) {
            addPanelBody.layoutParams = addPanelBody.layoutParams.apply { this.height = height - dp(4) }
        }
        val from = addPanel.layoutParams.height.coerceAtLeast(0)
        panelAnimator = ValueAnimator.ofInt(from, height).apply {
            duration = 240; interpolator = DecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Int
                addPanel.layoutParams = addPanel.layoutParams.apply { this.height = value }
                if (value == height && addExpanded && !pullReveal) addPanel.post {
                    if (addExpanded && !pullReveal && panelTargetHeight() != addPanel.layoutParams.height) animatePanelHeight(panelTargetHeight())
                }
                if (height == 0 && value == 0 && !addExpanded && !pullReveal) {
                    addPanel.visibility = View.GONE; addReveal.scaleX = 0f
                }
            }
            start()
        }
    }

    // Directional gestures are handled above child scrolling, including when a form is open.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!homeVisible || subPage != null || busy || detailShowing || editorShowing || dragging || editingTouch || pageTransition || drawerClosing) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                velocityTracker?.recycle(); velocityTracker = null
            }
            return super.dispatchTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            velocityTracker?.recycle(); velocityTracker = VelocityTracker.obtain()
        } else if (velocityTracker == null) return super.dispatchTouchEvent(event)
        velocityTracker?.addMovement(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.rawX; downY = event.rawY; gesture = 0
            val pos = IntArray(2)
            bar.getLocationOnScreen(pos)
            startsOnHeader = downY >= pos[1] && downY < pos[1] + bar.height
            scroll.getLocationOnScreen(pos)
            startsOnList = downY >= pos[1] && downY < pos[1] + scroll.height
            startsAtTop = scroll.scrollY == 0
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = event.rawX - downX; val dy = event.rawY - downY
            if (gesture == 0) {
                val position = IntArray(2); addPanel.getLocationOnScreen(position)
                val inPanel = addExpanded && downY >= position[1] && downY < position[1] + addPanel.height
                velocityTracker?.computeCurrentVelocity(1000)
                val velocity = -(velocityTracker?.yVelocity ?: 0f)
                gesture = when {
                    drawer != null && dx < -dp(32) && abs(dx) > abs(dy) * 1.25f -> 6
                    drawer != null -> 0
                    abs(dx) > dp(44) && abs(dx) > abs(dy) * 1.25f && (startsOnHeader || startsOnList || inPanel) ->
                        if (dx < 0 && page == 0 || dx > 0 && page == 1) 8 else if (dx > 0 && page == 0) 4 else 0
                    dy < -dp(50) && velocity > dp(850) && abs(dy) > abs(dx) * 1.35f && addExpanded && (inPanel || startsOnList) -> 5
                    dy > dp(44) && dy > abs(dx) * 1.5f && startsOnList && startsAtTop && scroll.scrollY == 0 && !addExpanded -> 3
                    else -> 0
                }
                if (gesture != 0) {
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                    if (gesture == 3) preparePullReveal()
                    if (gesture == 8) {
                        pageScrubInProgress = true
                        pageScrubWidth = root.width.coerceAtLeast(1).toFloat()
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        prepareList(1 - page)
                        if (page == 0) updateCodes()
                        val incoming = listPage(1 - page).view
                        incoming.visibility = View.VISIBLE
                        incoming.translationX = if (page == 0) pageScrubWidth else -pageScrubWidth
                        incoming.alpha = .72f
                    }
                    if (gesture == 4) { showDrawer(initialDrag = dx); drawerScrubInProgress = true }
                    if (gesture == 6) {
                        drawer?.getChildAt(1)?.let { panel ->
                            panel.animate().cancel()
                            drawerDragOrigin = panel.translationX
                        }
                        drawer?.getChildAt(0)?.animate()?.cancel()
                        drawerScrubInProgress = true
                    }
                }
            }
            if (gesture == 3) {
                val distance = (dy - dp(16)).coerceAtLeast(0f)
                val progress = (distance / dp(208).toFloat()).coerceIn(0f, 1f)
                addReveal.scaleX = progress
                addPanel.layoutParams = addPanel.layoutParams.apply { height = distance.toInt().coerceAtMost(panelTargetHeight()) }
                addPanel.alpha = .45f + .55f * addReveal.scaleX
                if (progress >= 1f) {
                    pullReveal = false; addExpanded = true
                    addPanelBody.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    gesture = 7
                    animatePanelHeight(panelTargetHeight())
                    if (page == 0) focusPanelInput()
                }
                return true
            }
            // Once the pull commits, leave the remainder of this touch to the reveal animation.
            if (gesture == 7) return true
            if (gesture == 8) {
                val direction = if (page == 0) -1f else 1f
                val translated = (dx * direction).coerceIn(0f, pageScrubWidth)
                val outgoing = scroll
                val incoming = listPage(1 - page).view
                outgoing.translationX = dx
                incoming.translationX = dx + if (page == 0) pageScrubWidth else -pageScrubWidth
                outgoing.alpha = 1f - translated / pageScrubWidth * .18f
                incoming.alpha = .72f + translated / pageScrubWidth * .28f
                val titleProgress = ((translated / pageScrubWidth) / .28f).coerceIn(0f, 1f)
                title.translationX = dx * .3f
                title.alpha = if (searchShown[page]) 0f else 1f - titleProgress
                return true
            }
            if (gesture == 4 && drawerScrubInProgress) {
                drawer?.getChildAt(1)?.let { updateDrawerDrag(dx - it.layoutParams.width) }
                return true
            }
            if (gesture == 6 && drawerScrubInProgress) {
                updateDrawerDrag(drawerDragOrigin + dx)
                return true
            }
            if (gesture != 0) return true
        } else if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) && gesture != 0) {
            val velocityX = velocityTracker?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
            if (event.actionMasked == MotionEvent.ACTION_UP) when (gesture) {
                1 -> if (page == 0) selectPage(1)
                2 -> selectPage(0)
                3 -> closeAddPanel()
                7 -> Unit
                8 -> {
                    val commit = abs(event.rawX - downX) >= pageScrubWidth * .28f || abs(velocityX) > dp(720)
                    if (commit) selectPage(if (page == 0) 1 else 0) else settlePageSwipe()
                }
                4 -> if (drawerScrubInProgress) {
                    drawerScrubInProgress = false
                    if (abs(drawer?.getChildAt(1)?.translationX ?: -1f) < (drawer?.getChildAt(1)?.width ?: 1) * .58f || velocityX > dp(700)) animateDrawerOpen()
                    else closeDrawer()
                } else showDrawer()
                5 -> closeAddPanel()
                6 -> if (drawerScrubInProgress) {
                    drawerScrubInProgress = false
                    if (abs(drawer?.getChildAt(1)?.translationX ?: 0f) > (drawer?.getChildAt(1)?.width ?: 1) * .42f || velocityX < -dp(700)) closeDrawer() else animateDrawerOpen()
                } else closeDrawer()
            } else {
                if (gesture == 3 || gesture == 7) closeAddPanel()
                if (gesture == 8) { pageScrubInProgress = false; settlePageSwipe() }
                if (drawerScrubInProgress) {
                    drawerScrubInProgress = false
                    if (gesture == 6) animateDrawerOpen() else closeDrawer()
                }
            }
            gesture = 0
            velocityTracker?.recycle(); velocityTracker = null
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            velocityTracker?.recycle(); velocityTracker = null
        }
        return super.dispatchTouchEvent(event)
    }

    private fun preparePullReveal() {
        panelAnimator?.cancel()
        pullReveal = true
        if (page == 0) selectedAccountColor = palette.random() else selectedTotpColor = palette.random()
        toggleAdd(forceOpen = true, followTouch = true)
    }

    private fun transitionContent(next: View, direction: Float) {
        hideKeyboard()
        val host = findViewById<ViewGroup>(android.R.id.content)
        val previous = if (host.childCount > 0) host.getChildAt(host.childCount - 1) else null
        for (index in host.childCount - 1 downTo 0) {
            val child = host.getChildAt(index)
            child.animate().cancel()
            if (child !== previous) host.removeView(child)
        }
        secureTransition = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        host.addView(next, ViewGroup.LayoutParams(-1, -1))
        next.translationY = 0f; next.alpha = 0f; next.translationX = direction * dp(14).toFloat()
        previous?.animate()?.alpha(0f)?.translationX(-direction * dp(10).toFloat())?.setDuration(130)?.start()
        next.animate().alpha(1f).translationX(0f).setDuration(230).setInterpolator(DecelerateInterpolator()).withEndAction {
            if (previous?.parent === host) host.removeView(previous)
            secureTransition = false; syncSecureFlag()
        }.start()
    }

    private fun prepareList(index: Int) {
        if (!listPage(index).isLoaded) render(animate = false, index = index)
    }

    private fun render(animate: Boolean = true, index: Int = page) {
        if (!homeVisible || subPage != null || store.database == null) return
        listPage(index).refresh(queries[index], animate)
        if (index == 1) updateCodes()
    }

    private fun updateCodes() {
        if (::authenticatorPage.isInitialized) authenticatorPage.updateCodes(offsetMillis)
    }

    private fun showCard(card: AccountCard, source: View) {
        if (detailShowing || editorShowing) return
        closeAddPanel(immediate = true); hideKeyboard()
        detailShowing = true; detailClosing = false
        protectedFields.clear()
        val location = IntArray(2)
        source.getLocationOnScreen(location)
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        detailSourceRect = android.graphics.Rect(location[0] - rootLocation[0], location[1] - rootLocation[1],
            location[0] - rootLocation[0] + source.width, location[1] - rootLocation[1] + source.height)
        syncSecureFlag()
        val overlay = FrameLayout(this).apply { setBackgroundColor(0x00000000); alpha = 0f }
        val shade = View(this).apply { setBackgroundColor(0x4d202624); setOnClickListener { closeDetailOverlay() } }
        overlay.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val cardView = column().apply {
            isClickable = true
            background = smooth(FLOATING, 26f, RULE, .6f)
            elevation = dp(18).toFloat()
            clipToOutline = true
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; pad(18) }
        detailHeaderTitle = text("卡片详情", 19, true)
        header.addView(detailHeaderTitle, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("×") { closeDetailOverlay() }.apply { textSize = 24f; setTextColor(MUTED) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        cardView.addView(header)
        val body = column().apply { pad(18) }
        body.addView(text("序号: ${card.id}", 13, color = MUTED))
        body.addView(text("时间: ${detailDate(card.updateTime)}", 13, color = MUTED).apply { setPadding(0, dp(5), 0, dp(12)) })
        val colorLine = View(this).apply { setBackgroundColor(card.color) }
        body.addView(colorLine, LinearLayout.LayoutParams(-1, dp(3)))
        body.addView(text("标签: ${card.tag}", 19, true).apply { setPadding(0, dp(16), 0, dp(6)) })
        db.fields(card.id).forEach { field ->
            val fieldRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            fieldRow.addView(text("${field.name}:", 14, true, MUTED), LinearLayout.LayoutParams(dp(88), dp(44)))
            val protectedValue = text("••••••", 17).apply {
                isClickable = true
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                contentDescription = "按住显示，点击复制字段内容"
                var pressedAt = 0L
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { pressedAt = event.eventTime; this.text = field.value; true }
                        MotionEvent.ACTION_UP -> {
                            this.text = "••••••"
                            if (event.eventTime - pressedAt < android.view.ViewConfiguration.getLongPressTimeout()) view.performClick()
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> { this.text = "••••••"; true }
                        MotionEvent.ACTION_MOVE -> true
                        else -> true
                    }
                }
                setOnClickListener {
                    val clip = ClipData.newPlainText(field.name, field.value)
                    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                    notify("${field.name} 已复制")
                }
            }
            protectedFields += protectedValue
            fieldRow.addView(protectedValue, LinearLayout.LayoutParams(0, dp(44), 1f))
            body.addView(fieldRow)
        }
        body.addView(text("备注: ${card.remark}", 16).apply { setPadding(0, dp(14), 0, dp(8)); minHeight = dp(48) })
        val attachmentSection = column().apply { setPadding(0, dp(8), 0, 0) }
        val attachmentHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        attachmentHeader.addView(text("附件", 14, true, MUTED), LinearLayout.LayoutParams(0, dp(36), 1f))
        attachmentHeader.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(MUTED)
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
        attachmentSection.addView(attachmentHeader)
        body.addView(attachmentSection)
        cardView.addView(scroller(body), LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; pad(12) }
        detailDeleteButton = button("删除") {
            if (it.tag == "confirm") {
                db.deleteCard(card.id); closeDetailOverlay { render() }
            } else {
                it.tag = "confirm"; (it as TextView).text = "确认删除"; it.setTextColor(0xffd70015.toInt())
                it.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
            }
        }.apply { setTextColor(RED) }
        actions.addView(detailDeleteButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        actions.addView(button("编辑") { editCard(card, cardView) }.apply { setTextColor(BLUE) }, LinearLayout.LayoutParams(0, dp(50), 1f))
        cardView.addView(actions)
        val detailHeight = minOf(dp(620), (root.height - dp(48)).coerceAtLeast(dp(120)))
        val params = FrameLayout.LayoutParams(-1, detailHeight, Gravity.CENTER).apply { leftMargin = dp(18); rightMargin = dp(18) }
        overlay.addView(cardView, params)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        detailOverlay = overlay
        detailSurface = cardView
        scroll.setRenderEffect(RenderEffect.createBlurEffect(10f, 10f, Shader.TileMode.CLAMP))
        window.statusBarColor = 0xffb0b3b2.toInt()
        window.navigationBarColor = 0xffb0b3b2.toInt()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        cardView.post {
            val sourceRect = detailSourceRect ?: return@post
            cardView.pivotX = 0f; cardView.pivotY = 0f
            cardView.scaleX = sourceRect.width().toFloat() / cardView.width.coerceAtLeast(1)
            cardView.scaleY = sourceRect.height().toFloat() / cardView.height.coerceAtLeast(1)
            cardView.translationX = sourceRect.left - (root.width - cardView.width) / 2f
            cardView.translationY = sourceRect.top - (root.height - cardView.height) / 2f
            overlay.animate().alpha(1f).setDuration(190).start()
            cardView.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(280).setInterpolator(DecelerateInterpolator())
                .withEndAction { loadCardAttachments(card.id, overlay, attachmentSection) }.start()
        }
    }

    private fun loadCardAttachments(cardId: Long, overlay: FrameLayout, section: LinearLayout) {
        val previewWidth = dp(92)
        val previewHeight = dp(82)
        io.execute {
            val result = runCatching {
                db.attachments(cardId).map { attachment ->
                    if (attachment.mimeType !in SUPPORTED_IMAGE_TYPES) PreparedAttachment(attachment, null, null)
                    else {
                        val bytes = db.attachmentContent(attachment.id)
                        PreparedAttachment(attachment, bytes, bytes?.let { decodeAttachmentPreview(it, previewWidth, previewHeight) })
                    }
                }
            }
            main.post {
                val prepared = result.getOrNull().orEmpty()
                if (isDestroyed || detailOverlay !== overlay) {
                    prepared.forEach { it.thumbnail?.recycle() }
                    return@post
                }
                section.removeAllViews()
                val items = result.getOrElse {
                    section.addView(text("附件加载失败", 13, color = MUTED))
                    return@post
                }
                if (items.isEmpty()) {
                    section.visibility = View.GONE
                    return@post
                }
                section.addView(text("附件", 14, true, MUTED).apply { setPadding(0, 0, 0, dp(4)) })
                val previews = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                items.forEach { item ->
                    if (item.thumbnail != null && item.bytes != null) {
                        previews.addView(ImageView(this).apply {
                            setImageBitmap(item.thumbnail); scaleType = ImageView.ScaleType.FIT_CENTER
                            background = smooth(Color.WHITE, 10f, RULE, 1f); clipToOutline = true
                            contentDescription = item.item.name
                            setOnClickListener { showAttachmentPreview(item.item) }
                        }, LinearLayout.LayoutParams(dp(92), dp(82)).apply { rightMargin = dp(8) })
                    } else section.addView(attachmentFileRow(item.item))
                }
                if (previews.childCount > 0) section.addView(ScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(previews)
                }, LinearLayout.LayoutParams(-1, dp(90)))
            }
        }
    }

    private fun attachmentFileRow(attachment: CardAttachment) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val name = text(attachment.name, 14, color = FOREGROUND).apply {
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1; isSelected = true
        }
        addView(name, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(text(formatSize(attachment.size), 12, color = MUTED), LinearLayout.LayoutParams(dp(56), dp(48)))
        addView(button("分享") { shareAttachment(attachment) }.apply { setTextColor(BLUE); textSize = 13f }, LinearLayout.LayoutParams(dp(52), dp(48)))
        addView(button("保存") { saveAttachment(attachment) }.apply { setTextColor(BLUE); textSize = 13f }, LinearLayout.LayoutParams(dp(52), dp(48)))
    }

    private fun closeDetailOverlay(afterClosed: (() -> Unit)? = null) {
        val overlay = detailOverlay ?: return
        if (detailClosing) return
        detailClosing = true
        val card = overlay.getChildAt(1) ?: return
        val source = detailSourceRect
        card.animate()
            .scaleX(if (source != null) source.width().toFloat() / card.width.coerceAtLeast(1) else .9f)
            .scaleY(if (source != null) source.height().toFloat() / card.height.coerceAtLeast(1) else .9f)
            .translationX(if (source != null) source.left - (root.width - card.width) / 2f else 0f)
            .translationY(if (source != null) source.top - (root.height - card.height) / 2f else 0f)
            .setDuration(190).setInterpolator(DecelerateInterpolator()).withEndAction {
                root.removeView(overlay); detailOverlay = null; detailSurface = null; detailShowing = false; detailClosing = false; detailSourceRect = null
                window.statusBarColor = SURFACE; window.navigationBarColor = SURFACE
                protectedFields.clear(); detailHeaderTitle = null; detailDeleteButton = null
                scroll.setRenderEffect(null); syncSecureFlag(); afterClosed?.invoke()
            }.start()
        overlay.animate().alpha(0f).setDuration(180).start()
    }

    private fun editCard(card: AccountCard, source: View? = null) {
        if (editorShowing) return
        if (detailShowing) {
            editCardInDetail(card)
            return
        }
        closeAddPanel(immediate = true)
        val editor = CardEditor(card)
        val (dialog, bodySlot, footer) = floatingEditor("编辑卡片", source)
        bodySlot.addView(scroller(editor.body), LinearLayout.LayoutParams(-1, 0, 1f))
        footer.addView(button("取消") { dialog.dismiss() }.apply { setTextColor(MUTED) }, LinearLayout.LayoutParams(0, dp(50), 1f))
        footer.addView(button("保存") { editor.save { dialog.dismiss(); render() } }.apply { setTextColor(BLUE) }, LinearLayout.LayoutParams(0, dp(50), 1f))
        showEditor(dialog)
    }

    private fun editCardInDetail(card: AccountCard) {
        val surface = detailSurface ?: return
        surface.animate().cancel()
        surface.animate().alpha(.45f).setDuration(100).withEndAction {
            if (detailSurface !== surface || detailClosing) return@withEndAction
            surface.removeAllViews()
            val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; pad(18) }
            detailHeaderTitle = text("编辑卡片", 19, true)
            header.addView(detailHeaderTitle, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(button("×") { closeDetailOverlay() }.apply { textSize = 24f; setTextColor(MUTED) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            surface.addView(header)

            val editor = CardEditor(card)
            surface.addView(scroller(editor.body), LinearLayout.LayoutParams(-1, 0, 1f))
            val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; pad(12) }
            footer.addView(button("取消") { closeDetailOverlay() }.apply { setTextColor(MUTED) }, LinearLayout.LayoutParams(0, dp(50), 1f))
            footer.addView(button("保存") { editor.save { closeDetailOverlay { render() } } }.apply { setTextColor(BLUE) }, LinearLayout.LayoutParams(0, dp(50), 1f))
            surface.addView(footer)
            surface.alpha = 0f
            surface.animate().alpha(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    private inner class CardEditor(private val card: AccountCard?) {
        val body = column().apply { pad(14) }
        private val tag = input("必填").apply { setText(card?.tag.orEmpty()); this.tag = "initial-input"; textCursorDrawable = getDrawable(R.drawable.cursor_required) }
        private val remark = input("").apply { setSingleLine(false); minLines = 2; setText(card?.remark.orEmpty()) }
        private var color = card?.color ?: selectedAccountColor.takeIf { it != 0 } ?: palette.random()
        private val fields = ReorderColumn(this@MainActivity).apply {
            onDragStateChanged = { dragging = it }
            onEdgeDrag = { rawY -> (body.parent as? ScrollView)?.let { autoScrollDuringDrag(it, rawY) } }
        }
        private val retainedAttachments = card?.let { db.attachments(it.id).toMutableList() } ?: mutableListOf()
        private val newAttachments = mutableListOf<NewCardAttachment>()
        private val attachmentRows = column()
        init {
            body.addView(ColorStripView(this@MainActivity, palette, color) { color = it }.apply {
                onGesture = { editingTouch = it }
            }, LinearLayout.LayoutParams(-1, dp(64)))
            body.addView(labeledInput("标签:", tag))
            val initial = if (card == null) listOf("账号" to "", "密码" to "") else db.fields(card.id).map { it.name to it.value }
            initial.forEach { addField(it.first, it.second) }
            body.addView(fields)
            if (card != null) body.addView(button("增加标签") { addCustomField() }.apply { setTextColor(BLUE) })
            body.addView(labeledInput("备注:", remark))
            body.addView(text("附件（单个最大 8 MiB，每卡最多 8 个、共 24 MiB）", 13, color = MUTED).apply { setPadding(0, dp(12), 0, dp(4)) })
            body.addView(button("添加附件") { pickCardAttachments(this@CardEditor) }.apply { setTextColor(BLUE) })
            body.addView(attachmentRows)
            refreshAttachmentRows()
        }
        fun addCustomField() { addField("", "") }
        private fun addField(nameText: String, valueText: String) {
            val name = input("名称").apply { setText(nameText) }
            val value = input("内容").apply {
                setText(valueText); isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; tag = name to value }
            val handle = text("•", 22, true, MUTED).apply {
                gravity = Gravity.CENTER
                contentDescription = "上下拖动调整字段顺序"
                var y = 0f
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { y = event.rawY; editingTouch = true; parent.requestDisallowInterceptTouchEvent(true) }
                        MotionEvent.ACTION_MOVE -> if (abs(event.rawY - y) > dp(6)) {
                            editingTouch = false; fields.begin(row, event.rawY)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { editingTouch = false; if (!fields.isReordering) parent.requestDisallowInterceptTouchEvent(false) }
                    }
                    true
                }
            }
            row.addView(handle, LinearLayout.LayoutParams(dp(30), dp(48)))
            row.addView(name, LinearLayout.LayoutParams(0, dp(48), .37f))
            row.addView(text(":", 16, color = MUTED))
            row.addView(value, LinearLayout.LayoutParams(0, dp(48), .63f))
            row.addView(button("移除") {
                it.isEnabled = false
                ValueAnimator.ofInt(row.height, 0).apply {
                    duration = 180; interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        val h = it.animatedValue as Int
                        row.layoutParams = row.layoutParams.apply { height = h }
                        row.alpha = (h.toFloat() / dp(48)).coerceIn(0f, 1f)
                        if (h == 0 && row.parent === fields) fields.removeView(row)
                    }
                    start()
                }
            }.apply { setTextColor(RED); textSize = 13f }, LinearLayout.LayoutParams(dp(48), dp(48)))
            fields.addView(row, LinearLayout.LayoutParams(-1, if (fields.isLaidOut) 0 else dp(48)))
            if (fields.isLaidOut) {
                ValueAnimator.ofInt(0, dp(48)).apply {
                    duration = 180; interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        val h = it.animatedValue as Int
                        row.layoutParams = row.layoutParams.apply { height = h }
                        row.alpha = (h.toFloat() / dp(48)).coerceIn(0f, 1f)
                    }
                    start()
                }
            }
        }
        fun save(done: () -> Unit) {
            val values = (0 until fields.childCount).map { index ->
                @Suppress("UNCHECKED_CAST")
                val pair = fields.getChildAt(index).tag as Pair<EditText, EditText>
                pair.first.text.toString() to pair.second.text.toString()
            }
            runCatching {
                db.saveCard(card?.id, tag.text.toString(), color, remark.text.toString(), values,
                    retainedAttachments.map { it.id }.toSet(), newAttachments.toList())
            }
                .onSuccess { done() }.onFailure { notify(it.message ?: "保存失败") }
        }

        fun addImages(uris: List<Uri>) {
            var added = 0
            for (uri in uris) {
                if (retainedAttachments.size + newAttachments.size >= VaultDatabase.MAX_ATTACHMENTS_PER_CARD) {
                    notify("每张卡片最多添加 ${VaultDatabase.MAX_ATTACHMENTS_PER_CARD} 个附件")
                    break
                }
                runCatching { readCardAttachment(uri) }
                    .onSuccess { attachment ->
                        val total = retainedAttachments.sumOf { it.size } + newAttachments.sumOf { it.content.size.toLong() }
                        if (total + attachment.content.size.toLong() > VaultDatabase.MAX_ATTACHMENT_BYTES_PER_CARD) {
                            notify("单张卡片附件总大小不能超过 24 MiB")
                        } else { newAttachments += attachment; added++ }
                    }
                    .onFailure { notify(it.message ?: "无法读取图片") }
            }
            if (added > 0) refreshAttachmentRows()
        }

        private fun refreshAttachmentRows() {
            attachmentRows.removeAllViews()
            retainedAttachments.forEach { attachment ->
                attachmentRows.addView(attachmentRow(attachment.name, attachment.size) {
                    retainedAttachments.remove(attachment); refreshAttachmentRows()
                })
            }
            newAttachments.forEach { attachment ->
                attachmentRows.addView(attachmentRow(attachment.name, attachment.content.size.toLong()) {
                    newAttachments.remove(attachment); refreshAttachmentRows()
                })
            }
        }

        private fun attachmentRow(name: String, size: Long, remove: () -> Unit) = LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("$name  ·  ${size / 1024} KiB", 13, color = FOREGROUND).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(button("移除") { remove() }.apply { setTextColor(RED) }, LinearLayout.LayoutParams(dp(64), dp(42)))
        }
    }

    private inner class TotpEditor(private val entry: TotpAccount?, scanned: TotpConfig? = null) {
        private val defaults = scanned ?: entry?.otpConfig()
        val body = column().apply { pad(16) }
        private var color = entry?.color ?: selectedTotpColor.takeIf { it != 0 } ?: palette.random()
        private val issuer = AutoCompleteTextView(this@MainActivity).apply {
            hint = "服务商"; tag = "initial-input"; setSingleLine(true)
            setText(defaults?.issuer.orEmpty()); threshold = 1
            val issuers = db.totpAccounts().map { it.issuer }.filter { it.isNotBlank() }.distinct().toTypedArray()
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, issuers))
            setOnClickListener { if (issuers.isNotEmpty()) showDropDown() }
            val line = underline(); background = line
            setOnFocusChangeListener { _, focused -> line.setFocused(focused) }
            textSize = 16f; setTextColor(FOREGROUND); setHintTextColor(MUTED); setPadding(dp(8), dp(8), dp(8), dp(5))
        }
        private val account = input("账户").apply { setText(defaults?.account.orEmpty()) }
        private val secret = input("Base32 密钥").apply {
            setText(defaults?.secret.orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        private val types = listOf("TOTP", "HOTP")
        private val type = Spinner(this@MainActivity).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, types)
            setSelection(types.indexOf(defaults?.type ?: "TOTP").coerceAtLeast(0))
            background = underline()
        }
        private val algorithms = listOf("SHA1", "SHA256", "SHA512")
        private val algorithm = Spinner(this@MainActivity).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, algorithms)
            setSelection(algorithms.indexOf(defaults?.algorithm ?: "SHA1").coerceAtLeast(0))
            background = underline()
        }
        private val digits = Spinner(this@MainActivity).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("6 位", "8 位"))
            setSelection(if (defaults?.digits == 8) 1 else 0)
            background = underline()
        }
        private val period = input("秒").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText((defaults?.period ?: 30).toString())
        }
        private val counter = input("计数器").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((defaults?.counter ?: 0L).toString())
        }
        private val periodRow = labeledInput("周期:", period)
        private val counterRow = labeledInput("计数器:", counter)
        init {
            body.addView(ColorStripView(this@MainActivity, palette, color) { color = it }.apply {
                onGesture = { editingTouch = it }
            }, LinearLayout.LayoutParams(-1, dp(64)))
            body.addView(labeledInput("标签:", issuer))
            body.addView(labeledInput("账户:", account))
            body.addView(labeledInput("密钥:", secret))
            val typeRow = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            typeRow.addView(text("类型:", 18, true, FOREGROUND), LinearLayout.LayoutParams(dp(78), dp(58)))
            typeRow.addView(type, LinearLayout.LayoutParams(0, dp(48), 1f))
            body.addView(typeRow)
            val options = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            options.addView(text("算法:", 14, color = MUTED))
            options.addView(algorithm, LinearLayout.LayoutParams(0, dp(48), 1f))
            options.addView(text("位数:", 14, color = MUTED))
            options.addView(digits, LinearLayout.LayoutParams(0, dp(48), .8f))
            body.addView(options)
            body.addView(periodRow)
            body.addView(counterRow)
            type.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = updateTypeFields()
            }
            updateTypeFields()
        }
        private fun updateTypeFields() {
            val isHotp = type.selectedItemPosition == 1
            periodRow.visibility = if (isHotp) View.GONE else View.VISIBLE
            counterRow.visibility = if (isHotp) View.VISIBLE else View.GONE
        }
        fun save(done: () -> Unit) {
            runCatching {
                require(issuer.text.isNotBlank() || account.text.isNotBlank()) { "请填写服务商或账户" }
                val selectedType = type.selectedItem.toString()
                val selectedCounter = if (selectedType == "HOTP") counter.text.toString().toLongOrNull()
                    ?: throw IllegalArgumentException("HOTP 计数器无效") else 0L
                val config = TotpConfig(issuer.text.toString(), account.text.toString(), Totp.normalizeSecret(secret.text.toString()),
                    algorithm.selectedItem.toString(), if (digits.selectedItemPosition == 1) 8 else 6,
                    period.text.toString().toIntOrNull() ?: 0, selectedType, selectedCounter)
                Totp.code(config)
                db.saveTotp(config.issuer, config.account, config.secret, config.algorithm, config.digits, config.period,
                    color, config.type, config.counter, entry?.id)
            }.onSuccess { done() }.onFailure { notify(it.message ?: "密钥或参数无效") }
        }
    }

    private fun showInlineTotp() {
        if (!addExpanded || inlineTotp) return
        inlineTotp = true
        val actions = addPanelBody.getChildAt(0) ?: return
        actions.animate().alpha(0f).translationY(-dp(6).toFloat()).setDuration(130).withEndAction {
            if (!addExpanded || !inlineTotp || page != 1) return@withEndAction
            addPanelBody.removeView(actions)
            val editor = TotpEditor(null)
            val form = scroller(editor.body).apply { alpha = 0f; translationY = -dp(8).toFloat() }
            addPanelBody.addView(form, LinearLayout.LayoutParams(-1, 0, 1f))
            val footer = LinearLayout(this)
            footer.addView(button("取消") { closeAddPanel() }.apply { setTextColor(MUTED) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            footer.addView(button("添加") { editor.save { closeAddPanel(); render() } }.apply { setTextColor(ACCENT) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addPanelBody.addView(footer)
            animatePanelHeight(panelTargetHeight())
            form.animate().alpha(1f).translationY(0f).setDuration(230).setInterpolator(DecelerateInterpolator()).start()
            focusPanelInput()
        }.start()
    }

    private fun editTotp(entry: TotpAccount?, scanned: TotpConfig? = null, source: View? = null) {
        if (editorShowing) return
        closeAddPanel(immediate = true)
        val editor = TotpEditor(entry, scanned)
        val (dialog, bodySlot, footer) = floatingEditor(if (entry == null) "添加认证账户" else "编辑卡片", source)
        bodySlot.addView(scroller(editor.body), LinearLayout.LayoutParams(-1, 0, 1f))
        if (entry != null) {
            val delete = button("删除") { view ->
                if (view.tag == "confirm") { db.deleteTotp(entry.id); dialog.dismiss(); render() }
                else { view.tag = "confirm"; (view as TextView).text = "确认删除"; view.setTextColor(0xffd70015.toInt()) }
            }.apply { setTextColor(RED) }
            footer.addView(delete, LinearLayout.LayoutParams(0, dp(50), 1f))
        } else footer.addView(button("取消") { dialog.dismiss() }.apply { setTextColor(MUTED) }, LinearLayout.LayoutParams(0, dp(50), 1f))
        footer.addView(button(if (entry == null) "添加" else "保存") { editor.save {
            dialog.dismiss()
            render(animate = page == 1, index = 1)
            if (page != 1) selectPage(1)
        } }.apply { setTextColor(BLUE) }, LinearLayout.LayoutParams(0, dp(50), 1f))
        showEditor(dialog)
    }

    private fun showTotpCard(entry: TotpAccount, source: View) {
        editTotp(entry, source = source)
    }

    private fun openScanner() { selectedTotpColor = palette.random(); closeAddPanel(immediate = true); hideKeyboard(); startActivityForResult(Intent(this, QrScannerActivity::class.java), SCAN_REQUEST) }

    private fun confirmMigrationImport(configs: List<TotpConfig>) {
        val details = configs.joinToString("\n") { config ->
            val label = listOf(config.issuer, config.account).filter { it.isNotBlank() }.joinToString(" · ")
            "$label  (${config.type})"
        }
        alertBuilder().setTitle("导入 ${configs.size} 个认证账户")
            .setMessage(details)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入") { _, _ ->
                work("正在导入迁移账户…", {
                    db.importTotp(configs, List(configs.size) { palette.random() })
                }) {
                    if (subPage == SubPage.MIGRATION) renderSubPage() else showHome()
                    notify("迁移账户已导入")
                }
            }.show()
    }

    private fun buildMigrationPage(body: LinearLayout) {
        clearMigrationExport()
        migrationPage = MigrationPage(body, db.totpAccounts(), ::openScanner, ::notify)
    }

    private fun showDrawer(initialDrag: Float = 0f) {
        if (pageTransition) return
        if (drawer != null) { closeDrawer(); return }
        closeAddPanel(immediate = true); hideKeyboard()
        showHeaderIcon(menuButton, backButton, true, -1)
        val overlay = FrameLayout(this)
        val shade = View(this).apply { setBackgroundColor(0x33202624); alpha = 0f; setOnClickListener { closeDrawer() } }
        overlay.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val panel = column().apply { setBackgroundColor(SURFACE); elevation = dp(10).toFloat(); pad(20); isClickable = true }
        val width = minOf(dp(310), resources.displayMetrics.widthPixels - dp(44))
        overlay.addView(panel, FrameLayout.LayoutParams(width, -1, Gravity.START))
        panel.addView(text("Auth Notes", 25, true).apply { setPadding(dp(8), dp(32), 0, dp(28)) })
        val entries = column()
        panel.addView(scroller(entries), LinearLayout.LayoutParams(-1, 0, 1f))
        val labels = listOf("Account Info", "Authenticator", "认证账户迁移", "文本解析", "导出备份", "导入恢复", "设置", "关于")
        val icons = listOf(R.drawable.nav_account, R.drawable.nav_authenticator, R.drawable.nav_authenticator,
            R.drawable.nav_parser, R.drawable.nav_export, R.drawable.nav_import, R.drawable.nav_settings, R.drawable.nav_about)
        labels.forEachIndexed { index, label ->
            val item = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; alpha = 0f; translationX = -dp(10).toFloat(); isFocusable = true
                val value = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                setBackgroundResource(value.resourceId)
                setPadding(dp(8), 0, dp(8), 0)
                contentDescription = label
            }
            item.addView(ImageView(this).apply { setImageResource(icons[index]); setColorFilter(DRAWER_TEXT); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(18) })
            item.addView(text(label, 16, color = DRAWER_TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            val container = if (index == 7) panel else entries
            if (index == 7) container.addView(View(this).apply { setBackgroundColor(RULE) }, LinearLayout.LayoutParams(-1, dp(1)))
            container.addView(item, LinearLayout.LayoutParams(-1, dp(56)))
            if (index < 6) container.addView(View(this).apply { setBackgroundColor(RULE) }, LinearLayout.LayoutParams(-1, dp(1)))
            item.setOnClickListener { closeDrawer {
                when (index) {
                    0, 1 -> selectPage(index)
                    2 -> openSubPage(SubPage.MIGRATION)
                    3 -> openSubPage(SubPage.PARSER)
                    4 -> shareDatabase()
                    5 -> openSubPage(SubPage.IMPORT)
                    6 -> openSubPage(SubPage.SETTINGS)
                    7 -> openSubPage(SubPage.ABOUT)
                }
            } }
            item.animate().alpha(1f).translationX(0f).setStartDelay(index * 18L).setDuration(210).start()
        }
        drawer = overlay; drawerClosing = false
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        panel.translationX = if (initialDrag == 0f) -width.toFloat() else (initialDrag - width).coerceAtMost(0f)
        if (initialDrag == 0f) animateDrawerOpen() else updateDrawerDrag(initialDrag - width)
        window.statusBarColor = 0xffd2d4d3.toInt(); window.navigationBarColor = 0xffd2d4d3.toInt()
    }

    private fun animateDrawerOpen() {
        val panel = drawer?.getChildAt(1) ?: return
        drawer?.getChildAt(0)?.animate()?.alpha(1f)?.setDuration(220)?.start()
        panel.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun updateDrawerDrag(translation: Float) {
        val overlay = drawer ?: return
        val panel = overlay.getChildAt(1) ?: return
        val width = panel.layoutParams.width.coerceAtLeast(1).toFloat()
        panel.translationX = translation.coerceIn(-width, 0f)
        overlay.getChildAt(0).apply {
            animate().cancel()
            alpha = 1f + panel.translationX / width
        }
    }

    private fun closeDrawer(afterClosed: (() -> Unit)? = null) {
        val overlay = drawer ?: return
        if (drawerClosing) return
        drawerClosing = true
        val panel = overlay.getChildAt(1)
        showHeaderIcon(menuButton, backButton, false, -1)
        overlay.getChildAt(0).animate().alpha(0f).setDuration(180).start()
        fun fadeChildren(group: ViewGroup) {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child is TextView) child.animate().alpha(0f).translationX(-dp(7).toFloat()).setDuration(140).start()
                if (child is ViewGroup) fadeChildren(child)
            }
        }
        fadeChildren(panel as ViewGroup)
        panel.animate().translationX(-panel.width.toFloat()).setDuration(210).setInterpolator(DecelerateInterpolator()).withEndAction {
            root.removeView(overlay); drawer = null; drawerClosing = false
            window.statusBarColor = SURFACE; window.navigationBarColor = SURFACE
            afterClosed?.invoke()
        }.start()
    }

    private fun openSubPage(target: SubPage) {
        closeAddPanel()
        if (searchShown[page]) toggleSearch()
        subPage = target
        renderSubPage()
    }

    private fun renderSubPage(direction: Float = 1f) {
        val target = subPage ?: return showHome()
        val pageRoot = column().apply { setBackgroundColor(SURFACE); alpha = 0f; translationY = dp(10).toFloat() }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(SURFACE) }
        header.addView(FrameLayout(this).apply {
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.nav_back)
                contentDescription = "返回"
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setOnClickListener { leaveSubPage() }
            }, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        header.addView(text(when (target) {
            SubPage.IMPORT -> "导入恢复数据库"
            SubPage.PARSER -> "文本解析"
            SubPage.SETTINGS -> "设置"
            SubPage.RECYCLE -> "回收站"
            SubPage.ABOUT -> "关于 AuthNotes"
            SubPage.MIGRATION -> "认证账户迁移"
        }, 19, true))
        pageRoot.addView(header)
        val body = column().apply { pad(if (target == SubPage.IMPORT) 28 else 20) }
        if (target == SubPage.IMPORT) pageRoot.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        else {
            val scroller = ScrollView(this).apply { isFillViewport = true; addView(body); overScrollMode = View.OVER_SCROLL_NEVER }
            pageRoot.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        when (target) {
            SubPage.IMPORT -> buildImportPage(body)
            SubPage.PARSER -> buildParserPage(body)
            SubPage.SETTINGS -> buildSettingsPage(body)
            SubPage.RECYCLE -> buildRecyclePage(body)
            SubPage.ABOUT -> buildAboutPage(body)
            SubPage.MIGRATION -> buildMigrationPage(body)
        }
        transitionContent(pageRoot, direction)
        syncSecureFlag()
    }

    private fun leaveSubPage() {
        if (subPage == SubPage.IMPORT) {
            selectedImportUri = null
            importPasswordDraft = ""
            if (store.database == null) { showSetup(setupUnlock); return }
        }
        if (subPage == SubPage.RECYCLE) {
            subPage = SubPage.SETTINGS; renderSubPage(-1f); return
        }
        if (subPage == SubPage.MIGRATION) clearMigrationExport()
        subPage = null
        showHome(-1f)
    }

    private fun clearMigrationExport() {
        migrationPage?.clearExport()
        migrationPage = null
    }

    private fun buildImportPage(body: LinearLayout) {
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.addView(View(this), LinearLayout.LayoutParams(1, 0, .22f))
        val choose = column().apply { gravity = Gravity.CENTER; isClickable = true; isFocusable = true }
        val icon = EncryptedDatabaseIconView(this).apply { setLoaded(selectedImportUri != null) }
        choose.addView(icon, LinearLayout.LayoutParams(dp(112), dp(112)))
        val fileLabel = text(if (selectedImportUri == null) "选择数据库文件" else "已加载数据库文件", 17, true, BLUE).apply { gravity = Gravity.CENTER }
        choose.addView(fileLabel, LinearLayout.LayoutParams(-1, dp(42)))
        choose.setOnClickListener { chooseDatabase() }
        body.addView(choose)
        body.addView(View(this), LinearLayout.LayoutParams(1, 0, .42f))
        val password = input("数据主密码", secret = true).apply { setText(importPasswordDraft); gravity = Gravity.CENTER; textSize = 20f }
        body.addView(password, LinearLayout.LayoutParams(-1, dp(62)))
        val restore = button("恢复") { restoreSelectedDatabase(password) }.apply {
            setTextColor(ACCENT); textSize = 18f; isEnabled = false; alpha = .45f
        }
        body.addView(restore, LinearLayout.LayoutParams(dp(180), dp(58)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })
        val updateButton: () -> Unit = {
            val enabled = selectedImportUri != null && importPasswordDraft.isNotEmpty()
            restore.isEnabled = enabled; restore.alpha = if (enabled) 1f else .45f
        }
        password.addTextChangedListener(TextChanged { importPasswordDraft = it; updateButton() })
        if (selectedImportUri != null) updateButton()
        body.addView(View(this), LinearLayout.LayoutParams(1, 0, .55f))
        body.addView(text("选择 AuthNotes 加密数据库备份并输入备份时设置的主密码。恢复会替换此设备当前保存的数据。", 13, color = MUTED).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(18))
        })
        body.addView(View(this), LinearLayout.LayoutParams(1, 0, .10f))
    }

    private fun restoreSelectedDatabase(password: EditText) {
        val uri = selectedImportUri ?: return
        val value = password.text.toString()
        if (value.isEmpty()) return
        work("正在验证并恢复数据库…", { store.importDatabase(uri, value) }) { autoUnlockReady ->
            password.text.clear()
            importPasswordDraft = ""
            selectedImportUri = null
            queries.fill("")
            page = 0
            subPage = null
            showHome()
            notify(if (autoUnlockReady) "数据库已恢复" else "数据库已恢复。下次启动时请手动输入数据主密码。")
        }
    }

    private fun buildParserPage(body: LinearLayout) {
        body.addView(text("每条记录依次填写标签、账号、密码；记录之间空一行。标签必填，备注和自定义字段不会从文本导入。", 15))
        val value = input("标签\n账号\n密码\n\n另一个标签").apply {
            setSingleLine(false); minLines = 12; gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        body.addView(value)
        body.addView(button("填入示例") { value.setText("Google\nname@gmail.com\nexample-password\n\n手机号登录\n13800000000") })
        body.addView(button("解析并添加") {
            runCatching {
                val blocks = value.text.toString().replace("\r\n", "\n").trim('\n', '\r').split(Regex("\n[\\t ]*\n+"))
                    .filter { it.isNotBlank() }
                require(blocks.isNotEmpty()) { "没有可解析的内容" }
                blocks.map { block ->
                    val lines = block.split('\n')
                    require(lines.size <= 3 && lines[0].isNotBlank()) { "每条记录最多三行，并且必须填写标签" }
                    Triple(lines[0].trim(), lines.getOrElse(1) { "" }, lines.getOrElse(2) { "" })
                }
            }.onSuccess { entries ->
                alertBuilder().setTitle("共 ${entries.size} 条记录")
                    .setMessage(entries.joinToString("、") { it.first }).setNegativeButton("取消", null)
                    .setPositiveButton("确认添加") { _, _ ->
                        work("正在添加记录…", { db.importCards(entries, palette.random()) }) {
                            subPage = null; showHome(); selectPage(0); notify("已添加 ${entries.size} 条记录")
                        }
                    }.show()
            }.onFailure { notify(it.message ?: "文本格式有误") }
        })
    }

    private fun buildSettingsPage(body: LinearLayout) {
        body.addView(text("数据主密码", 18, true))
        val password = input("新数据主密码", secret = true)
        val confirmation = input("确认新数据主密码", secret = true)
        body.addView(password)
        body.addView(confirmation)
        body.addView(text("至少 4 个字符。已导出的旧备份仍使用导出时的数据主密码。", 13, color = MUTED))
        body.addView(button("修改数据主密码") {
            val value = password.text.toString()
            if (value.length < 4 || value != confirmation.text.toString()) {
                notify("主密码至少 4 个字符，两次输入须一致")
                return@button
            }
            work("正在修改数据库主密码…", { store.changePassword(value) }) {
                password.text.clear(); confirmation.text.clear(); notify("主密码已修改")
            }
        })
        body.addView(text("卡片字段预览", 18, true).apply { setPadding(0, dp(20), 0, 0) })
        val previewMode = CheckBox(this).apply {
            text = "预览首个自定义字段内容（关闭时以密码点显示）"
            isChecked = db.setting("show_card_preview", "0") == "1"
            buttonTintList = android.content.res.ColorStateList.valueOf(BLUE)
            setOnCheckedChangeListener { _, checked ->
                db.updateSetting("show_card_preview", if (checked) "1" else "0")
            }
        }
        body.addView(previewMode)
        body.addView(text("TOTP 时间校正", 18, true).apply { setPadding(0, dp(24), 0, 0) })
        body.addView(text("仅调整 AuthNotes 生成验证码时使用的时间，不修改手机系统时间。", 14))
        val host = input("时间服务器").apply { setText(db.setting("ntp_server", "time.cloudflare.com")) }
        val offset = input("手动偏移（毫秒）").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(db.setting("time_offset_ms", "0"))
        }
        body.addView(host); body.addView(offset)
        body.addView(button("自动校正时间") {
            val server = host.text.toString().trim()
            if (server.isBlank()) { notify("请输入时间服务器"); return@button }
            work("正在校正时间…", { TimeCorrection.offsetMillis(server) }) { value ->
                db.updateSetting("ntp_server", server)
                db.updateSetting("time_offset_ms", value.toString())
                offset.setText(value.toString()); offsetMillis = value; notify("时间已校正，偏移 $value 毫秒")
            }
        })
        body.addView(button("保存手动偏移") {
            val value = offset.text.toString().toLongOrNull()
            if (value == null || value !in -86400000L..86400000L) notify("偏移必须在正负一天以内")
            else { db.updateSetting("time_offset_ms", value.toString()); offsetMillis = value; updateCodes(); notify("偏移已保存") }
        })
        body.addView(button("重置为系统时间") {
            offset.setText("0"); offsetMillis = 0; db.updateSetting("time_offset_ms", "0"); updateCodes()
        })
        body.addView(button("回收站") { openSubPage(SubPage.RECYCLE) })
    }

    private fun buildRecyclePage(body: LinearLayout) {
        val items = db.cards(deleted = true)
        if (items.isEmpty()) body.addView(empty("回收站为空"))
        items.forEach { card ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE); pad(14) }
            row.addView(text("●", 17, color = card.color).apply { setPadding(0, 0, dp(12), 0) })
            row.addView(text(card.tag, 16, true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("操作") {
                alertBuilder().setTitle(card.tag)
                    .setNegativeButton("永久删除") { _, _ -> db.deleteCard(card.id, permanent = true); openSubPage(SubPage.RECYCLE) }
                    .setPositiveButton("恢复") { _, _ -> db.restoreCard(card.id); openSubPage(SubPage.RECYCLE) }
                    .setNeutralButton("取消", null).show()
            })
            body.addView(row, cardParams())
        }
    }

    private fun buildAboutPage(body: LinearLayout) {
        body.addView(text("AuthNotes", 24, true))
        body.addView(text("本地账号信息与 TOTP / HOTP 动态验证码管理工具。", 16))
        body.addView(text("账号卡片及 TOTP / HOTP 认证数据保存在 SQLCipher 加密数据库中。数据库主密码由你自行保管；本机使用 Android Keystore 加密保存自动解锁凭据。", 15))
        body.addView(text("AuthNotes 1.0\n数据仅保存在本机及你主动导出的加密备份中。", 14, color = MUTED))
    }

    private fun syncSecureFlag() {
        val secure = homeVisible && (((page == 1 || pageTransition || pageScrubInProgress) && subPage == null) || subPage == SubPage.MIGRATION)
        if (secure || secureTransition || detailShowing) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun shareDatabase() {
        hideKeyboard()
        work("正在准备加密备份…", { store.exportSnapshot() }) { file ->
            val uri = Uri.Builder().scheme("content").authority("$packageName.backups").appendPath(file.name).build()
            val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri("AuthNotes encrypted database", uri)
            startActivity(Intent.createChooser(send, "分享 AuthNotes 加密数据库"))
        }
    }

    private fun pickCardAttachments(editor: CardEditor) {
        pendingAttachmentEditor = editor
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, ATTACHMENT_REQUEST)
    }

    private fun readCardAttachment(uri: Uri): NewCardAttachment {
        val mimeType = contentResolver.getType(uri)?.lowercase() ?: "application/octet-stream"
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: "附件"
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        contentResolver.openInputStream(uri)?.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= VaultDatabase.MAX_ATTACHMENT_BYTES_PER_FILE) { "单个附件不能超过 8 MiB" }
                output.write(buffer, 0, count)
            }
        } ?: throw IllegalArgumentException("无法打开附件文件")
        val bytes = output.toByteArray()
        require(bytes.isNotEmpty()) { "附件文件为空" }
        if (mimeType in SUPPORTED_IMAGE_TYPES) {
            require(decodeAttachmentPreview(bytes, dp(16), dp(16)) != null) { "图片文件无法预览" }
        }
        return NewCardAttachment(name, mimeType, bytes)
    }

    private fun showAttachmentPreview(attachment: CardAttachment) {
        val previewWidth = (root.width - dp(40)).coerceAtLeast(dp(180)).coerceAtMost(dp(440))
        val previewHeight = (root.height - dp(240)).coerceAtLeast(dp(220)).coerceAtMost(dp(680))
        val image = ZoomRotateImageView(this).apply {
            contentDescription = "${attachment.name}，双指缩放并旋转，单指移动"
            background = smooth(Color.WHITE, 18f)
            clipToOutline = true
        }
        val loading = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(MUTED)
        }
        val frame = FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(previewWidth, previewHeight, Gravity.CENTER))
            addView(loading, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }
        val dialog = alertBuilder().setView(frame)
            .setNegativeButton("分享", null)
            .setNeutralButton("保存", null)
            .setPositiveButton("关闭", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(smooth(Color.WHITE, 24f))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(BLUE); setOnClickListener { shareAttachment(attachment) }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setTextColor(BLUE); setOnClickListener { saveAttachment(attachment) }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(MUTED)
        }
        dialog.setOnDismissListener { image.setImageBitmap(null) }
        dialog.show()
        dialog.window?.setLayout(previewWidth + dp(32), WindowManager.LayoutParams.WRAP_CONTENT)
        io.execute {
            val loaded = runCatching {
                val bytes = db.attachmentContent(attachment.id) ?: error("附件不存在")
                decodeAttachmentPreview(bytes, previewWidth, previewHeight) ?: error("图片预览失败")
            }
            main.post {
                if (!dialog.isShowing || isDestroyed) {
                    loaded.getOrNull()?.recycle()
                    return@post
                }
                loading.visibility = View.GONE
                loaded.onSuccess(image::setImageBitmap).onFailure {
                    frame.removeView(image)
                    frame.addView(text("图片预览失败", 14, color = MUTED).apply { gravity = Gravity.CENTER },
                        FrameLayout.LayoutParams(-1, -1))
                }
            }
        }
    }

    private fun shareAttachment(attachment: CardAttachment) = withAttachmentContent(attachment) { bytes ->
        val token = VaultShareProvider.registerAttachment(attachment.name, attachment.mimeType, bytes)
        val uri = Uri.Builder().scheme("content").authority("$packageName.backups")
            .appendPath("attachments").appendPath(token).build()
        val send = Intent(Intent.ACTION_SEND).setType(attachment.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(attachment.name, uri)
        startActivity(Intent.createChooser(send, "分享附件"))
    }

    private fun saveAttachment(attachment: CardAttachment) = withAttachmentContent(attachment) { bytes ->
        pendingAttachmentSave = NewCardAttachment(attachment.name, attachment.mimeType, bytes)
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = attachment.mimeType
            putExtra(Intent.EXTRA_TITLE, attachment.name)
        }, ATTACHMENT_SAVE_REQUEST)
    }

    private fun withAttachmentContent(attachment: CardAttachment, use: (ByteArray) -> Unit) {
        notify("正在读取附件…")
        io.execute {
            val result = runCatching { db.attachmentContent(attachment.id) ?: error("附件不存在") }
            main.post {
                if (isDestroyed || isFinishing) return@post
                result.onSuccess(use).onFailure { notify(it.message ?: "附件读取失败") }
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private fun chooseDatabase() {
        hideKeyboard()
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_REQUEST)
    }
    @Deprecated("Framework Activity result API keeps AndroidX Activity out of the APK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ATTACHMENT_REQUEST && resultCode != RESULT_OK) pendingAttachmentEditor = null
        if (requestCode == ATTACHMENT_SAVE_REQUEST && resultCode != RESULT_OK) pendingAttachmentSave = null
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            SCAN_REQUEST -> {
                val raw = data?.getStringExtra(QrScannerActivity.EXTRA_VALUE) ?: return
                if (GoogleMigration.isMigrationUri(raw)) {
                    runCatching { GoogleMigration.decodeUri(raw) }.onSuccess(::confirmMigrationImport)
                        .onFailure { notify(it.message ?: "迁移二维码无效或包含不支持的账户") }
                } else {
                    runCatching { Totp.parseUri(raw) }.onSuccess {
                        if (subPage == SubPage.MIGRATION) confirmMigrationImport(listOf(it)) else editTotp(null, it)
                    }
                        .onFailure { notify(it.message ?: "不是支持的 TOTP 或 HOTP 二维码") }
                }
            }
            IMPORT_REQUEST -> {
                val uri = data?.data ?: return
                selectedImportUri = uri
                subPage = SubPage.IMPORT
                renderSubPage()
            }
            ATTACHMENT_REQUEST -> {
                val editor = pendingAttachmentEditor
                pendingAttachmentEditor = null
                if (editor != null && data != null) {
                    val uris = buildList {
                        data.clipData?.let { clip ->
                            for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
                        }
                        data.data?.let { add(it) }
                    }.distinct()
                    editor.addImages(uris)
                }
            }
            ATTACHMENT_SAVE_REQUEST -> {
                val attachment = pendingAttachmentSave
                pendingAttachmentSave = null
                val uri = data?.data
                if (attachment != null && uri != null) io.execute {
                    val result = runCatching {
                        contentResolver.openOutputStream(uri, "w")?.use { it.write(attachment.content) }
                            ?: error("无法写入所选位置")
                    }
                    main.post {
                        if (!isDestroyed) result.onSuccess { notify("附件已保存") }.onFailure { notify(it.message ?: "附件保存失败") }
                    }
                }
            }
        }
    }

    override fun onBackPressed() {
        if (pageTransition) return
        when {
            detailOverlay != null -> closeDetailOverlay()
            drawer != null -> closeDrawer()
            subPage != null -> leaveSubPage()
            homeVisible && addPanel.visibility == View.VISIBLE -> closeAddPanel()
            homeVisible && searchShown[page] -> toggleSearch()
            homeVisible && page == 1 -> selectPage(0)
            else -> super.onBackPressed()
        }
    }

    private fun floatingEditor(titleText: String, source: View? = null): Triple<FloatingEditorDialog, LinearLayout, LinearLayout> {
        val dialog = FloatingEditorDialog(this, source)
        val rootCard = column().apply { background = smooth(FLOATING, 26f, RULE, .6f); clipToOutline = true; elevation = dp(18).toFloat() }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; pad(16) }
        header.addView(text(titleText, 18, true), LinearLayout.LayoutParams(0, dp(50), 1f))
        header.addView(button("×") { dialog.dismiss() }.apply { textSize = 23f; setTextColor(MUTED) }, LinearLayout.LayoutParams(dp(42), dp(48)))
        rootCard.addView(header)
        val bodySlot = column()
        rootCard.addView(bodySlot, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; pad(8) }
        rootCard.addView(footer)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.surface = rootCard
        rootCard.isClickable = true
        val overlay = FrameLayout(this).apply {
            clipChildren = false
            setOnClickListener { dialog.dismiss() }
        }
        overlay.addView(rootCard, FrameLayout.LayoutParams(-1, minOf(dp(650), (root.height - dp(32)).coerceAtLeast(dp(160))), Gravity.CENTER).apply {
            leftMargin = dp(14); rightMargin = dp(14)
        })
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val available = minOf(dp(650), (overlay.height - dp(32)).coerceAtLeast(dp(120)))
            if (rootCard.layoutParams.height != available) rootCard.layoutParams = rootCard.layoutParams.apply { height = available }
        }
        dialog.setContentView(overlay)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(.12f)
        dialog.window?.setWindowAnimations(0)
        dialog.window?.setBackgroundBlurRadius(dp(18))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return Triple(dialog, bodySlot, footer)
    }

    private fun showEditor(dialog: FloatingEditorDialog) {
        editorShowing = true; activeEditor = dialog
        dialog.setOnDismissListener {
            editorShowing = false; editingTouch = false; dragging = false; activeEditor = null
            window.statusBarColor = SURFACE; window.navigationBarColor = SURFACE
            scroll.setRenderEffect(null)
        }
        dialog.show()
        window.statusBarColor = 0xffb0b3b2.toInt(); window.navigationBarColor = 0xffb0b3b2.toInt()
        dialog.window?.setLayout(-1, -1)
        dialog.animateEntrance()
        scroll.setRenderEffect(RenderEffect.createBlurEffect(10f, 10f, Shader.TileMode.CLAMP))
    }

    private fun alertBuilder(): AlertDialog.Builder = object : AlertDialog.Builder(this@MainActivity) {
        override fun create(): AlertDialog = super.create().apply {
            setOnShowListener {
                window?.setBackgroundDrawable(smooth(Color.WHITE, 26f))
                getButton(AlertDialog.BUTTON_POSITIVE)?.let { it.setTextColor(if (it.text.contains("删除")) RED else ACCENT) }
                getButton(AlertDialog.BUTTON_NEGATIVE)?.let { it.setTextColor(if (it.text.contains("删除")) RED else MUTED) }
            }
        }
    }

    private fun autoScrollDuringDrag(list: ScrollView, rawY: Float) {
        val position = IntArray(2); list.getLocationOnScreen(position)
        val edge = dp(56)
        val distance = when {
            rawY < position[1] + edge -> -((position[1] + edge - rawY) / edge).coerceIn(0f, 1f)
            rawY > position[1] + list.height - edge -> ((rawY - position[1] - list.height + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        list.scrollBy(0, (distance * dp(5)).toInt())
    }

    private fun labeledInput(label: String, field: EditText): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val required = label.contains("标签")
        addView(text(label, 18, true, FOREGROUND), LinearLayout.LayoutParams(dp(78), dp(58)))
        field.textSize = 19f
        field.textCursorDrawable = getDrawable(if (required) R.drawable.cursor_required else R.drawable.cursor_optional)
        addView(field, LinearLayout.LayoutParams(0, if (field.minLines > 1) dp(72) else dp(58), 1f))
    }

    private fun iconAction(key: Boolean, label: String, action: () -> Unit): View = column().apply {
        gravity = Gravity.CENTER
        background = smooth(SURFACE, 16f)
        isClickable = true; isFocusable = true
        addView(ActionIconView(this@MainActivity, key, ACCENT), LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(text(label, 14, color = FOREGROUND).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(36)))
        setOnClickListener { alpha = .86f; animate().alpha(1f).setDuration(150).start(); action() }
    }

    private fun smooth(color: Int, radius: Float, stroke: Int = Color.TRANSPARENT, strokeWidth: Float = 0f) =
        SmoothDrawable(color, resources.displayMetrics.density, radius, stroke, strokeWidth)

    private fun underline(): UnderlineDrawable = UnderlineDrawable(resources.displayMetrics.density, 0xff969696.toInt(), FOREGROUND)

    private fun screen(body: View) {
        val outer = column().apply { pad(24); setBackgroundColor(BACKGROUND); gravity = Gravity.CENTER_VERTICAL; addView(body) }
        setContentView(scroller(outer))
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Int = 15, bold: Boolean = false, color: Int = FOREGROUND) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun input(hintValue: String, secret: Boolean = false) =
        EditText(this).also { configureInput(it, hintValue, secret) }

    private fun configureInput(field: EditText, hintValue: String, secret: Boolean = false) = with(field) {
        hint = hintValue; textSize = 18f; setSingleLine(true); setTextColor(FOREGROUND); setHintTextColor(MUTED); setPadding(dp(8), dp(10), dp(8), dp(8))
        val line = underline()
        background = line
        setOnFocusChangeListener { _, focused -> line.setFocused(focused) }
        inputType = android.text.InputType.TYPE_CLASS_TEXT or if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
        textCursorDrawable = getDrawable(R.drawable.cursor_optional)
        if (secret) { isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
    }
    private fun button(label: String, action: (View) -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; setTextColor(FOREGROUND); gravity = Gravity.CENTER; minHeight = dp(48)
        setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { action(it) }; isFocusable = true
        val value = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true); setBackgroundResource(value.resourceId)
    }
    private fun scroller(body: View) = ScrollView(this).apply { isFillViewport = true; addView(body) }
    private fun empty(message: String) = text(message, color = MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(72), 0, dp(72)) }
    private fun View.pad(value: Int) { val px = dp(value); setPadding(px, px, px, px) }
    private fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    private fun detailDate(value: Long) = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(Date(value))
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun notify(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0) }
    private fun keyboard(view: View) { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }

    companion object {
        const val ACTION_ADD = "com.authnotes.QUICK_ADD"
        const val ACTION_SCAN = "com.authnotes.QUICK_SCAN"
        private const val IMPORT_REQUEST = 100
        private const val SCAN_REQUEST = 101
        private const val ATTACHMENT_REQUEST = 102
        private const val ATTACHMENT_SAVE_REQUEST = 103
        private val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif")
        private val BACKGROUND = 0xfffafafa.toInt()
        private val SURFACE = 0xfffafafa.toInt()
        private val RULE = 0xffededed.toInt()
        private val FOREGROUND = 0xff3c3f41.toInt()
        private val MUTED = 0xff717171.toInt()
        private val DRAWER_TEXT = 0xff55585a.toInt()
        private val ACCENT = 0xff34c759.toInt()
        private val BLUE = 0xff007aff.toInt()
        private val FLOATING = 0xf2ffffff.toInt()
        private val RED = 0xffb87574.toInt()
    }
}

private class TextChanged(private val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
