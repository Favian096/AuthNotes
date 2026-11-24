package com.authnotes

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.authnotes.data.AccountRepository
import com.authnotes.data.AuthenticatorRepository
import com.authnotes.data.SettingsRepository
import com.authnotes.data.TotpAccount
import com.authnotes.data.VaultStore
import com.authnotes.security.TimeCorrection
import com.authnotes.security.Totp
import com.authnotes.security.TotpConfig
import com.authnotes.security.GoogleMigration
import com.authnotes.ui.SmoothDrawable
import com.authnotes.ui.UnderlineDrawable
import com.authnotes.ui.home.AccountListPage
import com.authnotes.ui.home.AuthenticatorListPage
import com.authnotes.ui.home.DrawerController
import com.authnotes.ui.migration.MigrationPage
import com.authnotes.ui.FloatingEditorDialog
import com.authnotes.ui.ActionIconView
import com.authnotes.ui.account.AccountDetailController
import com.authnotes.ui.account.AttachmentController
import com.authnotes.ui.authenticator.OtpEditor
import com.authnotes.ui.pages.AppPage
import com.authnotes.ui.pages.AppPageController
import com.authnotes.ui.account.AccountEditor
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity() {
    private sealed interface LoadedList {
        data class Accounts(val entries: List<com.authnotes.data.AccountListEntry>, val showPreview: Boolean) : LoadedList
        data class Authenticators(val entries: List<TotpAccount>) : LoadedList
    }

    private lateinit var store: VaultStore
    private val db get() = checkNotNull(store.database)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listRequestVersions = intArrayOf(0, 0)
    private val searchTasks = arrayOfNulls<Runnable>(2)
    private var busy = false
    private var activityResumed = false
    private var homeVisible = false
    private var page = 0
    private var subPage: AppPage? = null
    private var setupUnlock = false
    private val detailShowing get() = accountDetails.isShowing
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
    private lateinit var accountPage: AccountListPage
    private lateinit var authenticatorPage: AuthenticatorListPage
    private fun listPage(index: Int) = if (index == 0) accountPage else authenticatorPage
    private val scroll get() = listPage(page).view
    private val drawer get() = drawerController.overlay
    private val drawerClosing get() = drawerController.isClosing
    private var pageTransition = false
    private var panelAnimator: ValueAnimator? = null
    private var addExpanded = false
    private var inlineTotp = false
    private var searchAnimator: ValueAnimator? = null
    private var editorShowing = false
    private var activeEditor: FloatingEditorDialog? = null
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
    private var secureTransition = false
    private var tickerRunning = false
    private val palette = iosPalette()
    private val accountRepository by lazy { AccountRepository { db } }
    private val authenticatorRepository by lazy { AuthenticatorRepository { db } }
    private val settingsRepository by lazy { SettingsRepository { db } }
    private val attachments by lazy {
        AttachmentController(this, accountRepository, io, main, ::notify,
            launchPick = {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }, ATTACHMENT_REQUEST)
            },
            launchSave = { name, mimeType ->
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mimeType
                    putExtra(Intent.EXTRA_TITLE, name)
                }, ATTACHMENT_SAVE_REQUEST)
            })
    }
    private val accountDetails by lazy {
        AccountDetailController(this, accountRepository, attachments, { accountEditor(it) }, ::notify,
            onChanged = { render() }, onVisibilityChanged = ::onDetailVisibilityChanged)
    }
    private val drawerController by lazy {
        DrawerController(this, onNavigate = { index ->
            when (index) {
                0, 1 -> selectPage(index)
                2 -> openSubPage(AppPage.MIGRATION)
                3 -> openSubPage(AppPage.PARSER)
                4 -> shareDatabase()
                5 -> openSubPage(AppPage.IMPORT)
                6 -> openSubPage(AppPage.SETTINGS)
                7 -> openSubPage(AppPage.ABOUT)
            }
        }, onHeaderChanged = { showing ->
            if (::menuButton.isInitialized) showHeaderIcon(menuButton, backButton, showing, if (showing) -1 else 1)
        }, onVisibilityChanged = { showing ->
            val color = if (showing) 0xffd2d4d3.toInt() else SURFACE
            window.statusBarColor = color; window.navigationBarColor = color
            syncTicker()
        })
    }
    private val appPages: AppPageController by lazy {
        AppPageController(this, accountRepository, settingsRepository, ::notify, ::button, ::alertBuilder,
            onCreateOrUnlock = { value, unlock, done ->
                work("正在打开加密数据库…", { if (unlock) store.unlock(value) else store.create(value) }) { ready ->
                    homeVisible = true
                    showHome()
                    done(ready)
                }
            },
            onChangePassword = { value, done -> work("正在修改数据库主密码…", { store.changePassword(value) }) { done() } },
            onImport = { uri, value, done ->
                work("正在验证并恢复数据库…", { store.importDatabase(uri, value) }) { ready ->
                    appPages.resetImport(); queries.fill(""); page = 0; subPage = null; showHome(); done(ready)
                    if (ready) notify("数据库已恢复")
                }
            },
            onChooseDatabase = ::chooseDatabase,
            onOpenSubpage = ::openSubPage,
            onConfirmTextImport = ::confirmTextImport,
            onTimeCorrection = { server, done -> work("正在校正时间…", { TimeCorrection.offsetMillis(server) }, done) },
            onOffsetChanged = { value -> offsetMillis = value; updateCodes() })
    }
    private val tick = object : Runnable {
        override fun run() {
            if (shouldTickCodes()) {
                updateCodes()
                main.postDelayed(this, 33)
            } else tickerRunning = false
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
                detailShowing -> accountDetails.close { handleShortcut() }
                drawer != null -> closeDrawer { handleShortcut() }
                subPage != null -> { subPage = null; showHome() }
                else -> handleShortcut()
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("page", page); super.onSaveInstanceState(outState) }
    override fun onPause() {
        activityResumed = false
        accountDetails.concealFields()
        stopTicker()
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        activityResumed = true
        syncTicker()
    }
    override fun onDestroy() {
        activeEditor?.dismissImmediately()
        accountDetails.dismissImmediately()
        attachments.dispose()
        clearMigrationExport()
        panelAnimator?.cancel(); searchAnimator?.cancel()
        drawerController.dismissImmediately()
        velocityTracker?.recycle(); velocityTracker = null
        main.removeCallbacks(tick)
        searchTasks.forEach { it?.let(main::removeCallbacks) }
        searchTasks.fill(null)
        tickerRunning = false
        io.execute { store.close() }
        io.shutdown()
        super.onDestroy()
    }

    private fun <T> work(message: String, task: () -> T, done: (T) -> Unit) {
        if (busy || isDestroyed) return
        busy = true
        syncTicker()
        val progress = alertBuilder().setMessage(message).setCancelable(false).create()
        progress.show()
        io.execute {
            val result = runCatching(task)
            main.post {
                if (isDestroyed || isFinishing) return@post
                busy = false
                syncTicker()
                progress.dismiss()
                result.onSuccess(done).onFailure {
                    val message = if (subPage == AppPage.IMPORT) "鉴权失败或数据库文件无效，请重新输入密码并确认选择的是 AuthNotes 备份。原数据库未被更改。"
                    else if (store.exists && store.database == null) "鉴权失败，请重新输入密码。数据库文件未被更改。"
                    else it.message ?: "操作未完成，请重试"
                    notify(message)
                    if (store.database == null && subPage != AppPage.IMPORT) showSetup(store.exists)
                }
            }
        }
    }

    private fun showSetup(unlock: Boolean) {
        homeVisible = false
        subPage = null
        setupUnlock = unlock
        syncTicker()
        appPages.showSetup(unlock, { openSubPage(AppPage.IMPORT) }, ::transitionContent)
    }

    private fun showHome(direction: Float = 1f) {
        panelAnimator?.cancel(); searchAnimator?.cancel()
        clearMigrationExport()
        homeVisible = true
        accountDetails.dismissImmediately()
        drawerController.dismissImmediately(); pageTransition = false
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
                if (!updatingSearch && queries[page] != value) {
                    queries[page] = value
                    scheduleSearchRender(page)
                }
            })
        }
        addPanel = root.findViewById<LinearLayout>(R.id.home_add_panel).apply {
            background = SmoothDrawable(SURFACE, resources.displayMetrics.density, 24f, RULE, .5f, roundTop = false)
        }
        addReveal = root.findViewById<View>(R.id.home_add_reveal).apply { setBackgroundColor(palette.random()) }
        val pageHost = root.findViewById<FrameLayout>(R.id.home_page_host)
        accountPage = AccountListPage(this, pageHost, accountRepository, { dragging = it }, ::notify, ::showCard)
        authenticatorPage = AuthenticatorListPage(this, pageHost, authenticatorRepository, { dragging = it }, ::notify, ::showTotpCard)
        for (index in 0..1) {
            val view = listPage(index).view
            view.visibility = if (index == page) View.VISIBLE else View.INVISIBLE
            pageHost.addView(view)
        }
        pageScrubInProgress = false
        offsetMillis = settingsRepository.get("time_offset_ms", "0").toLongOrNull() ?: 0L

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
        syncTicker()
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
        syncTicker()
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
            cancelSearchRender(page)
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
            cancelSearchRender(page)
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
            val editor = accountEditor(null)
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
                        drawerController.panel?.let { panel ->
                            panel.animate().cancel()
                            drawerDragOrigin = panel.translationX
                        }
                        drawerController.shade?.animate()?.cancel()
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
                drawerController.panel?.let { updateDrawerDrag(dx - it.layoutParams.width) }
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
                    if (abs(drawerController.panel?.translationX ?: -1f) < (drawerController.panel?.width ?: 1) * .58f || velocityX > dp(700)) animateDrawerOpen()
                    else closeDrawer()
                } else showDrawer()
                5 -> closeAddPanel()
                6 -> if (drawerScrubInProgress) {
                    drawerScrubInProgress = false
                    if (abs(drawerController.panel?.translationX ?: 0f) > (drawerController.panel?.width ?: 1) * .42f || velocityX < -dp(700)) closeDrawer() else animateDrawerOpen()
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

    private fun scheduleSearchRender(index: Int) {
        cancelSearchRender(index)
        val shownRoot = root
        val query = queries[index]
        lateinit var task: Runnable
        task = Runnable {
            if (searchTasks[index] === task) {
                searchTasks[index] = null
                if (root === shownRoot && homeVisible && subPage == null && queries[index] == query) {
                    render(animate = false, index = index)
                }
            }
        }
        searchTasks[index] = task
        main.postDelayed(task, SEARCH_DEBOUNCE_MILLIS)
    }

    private fun cancelSearchRender(index: Int) {
        searchTasks[index]?.let(main::removeCallbacks)
        searchTasks[index] = null
    }

    private fun shouldTickCodes() = activityResumed && homeVisible && subPage == null && !busy &&
        page == 1 && !editorShowing && drawer == null

    private fun syncTicker() {
        if (!shouldTickCodes()) { stopTicker(); return }
        if (!tickerRunning) {
            tickerRunning = true
            main.removeCallbacks(tick)
            main.post(tick)
        }
    }

    private fun stopTicker() {
        main.removeCallbacks(tick)
        tickerRunning = false
    }

    private fun render(animate: Boolean = true, index: Int = page) {
        if (!homeVisible || subPage != null || store.database == null) return
        val query = queries[index]
        val version = ++listRequestVersions[index]
        val shownRoot = root
        listPage(index).requestRefresh(query)
        io.execute {
            val loaded = runCatching {
                if (index == 0) LoadedList.Accounts(accountRepository.list(query), accountRepository.showPreviewValues())
                else LoadedList.Authenticators(authenticatorRepository.list(query))
            }
            main.post {
                if (isDestroyed || isFinishing || root !== shownRoot || !homeVisible || subPage != null ||
                    listRequestVersions[index] != version || queries[index] != query) return@post
                loaded.onSuccess { rows ->
                    when (rows) {
                        is LoadedList.Accounts -> accountPage.bindPrepared(query, rows.entries, rows.showPreview, animate)
                        is LoadedList.Authenticators -> authenticatorPage.bindPrepared(query, rows.entries, animate)
                    }
                    if (index == 1) updateCodes()
                }.onFailure {
                    listPage(index).invalidate()
                    notify(it.message ?: "列表加载失败")
                }
            }
        }
    }

    private fun updateCodes() {
        if (::authenticatorPage.isInitialized) authenticatorPage.updateCodes(offsetMillis)
    }

    private fun showCard(card: AccountCard, source: View) {
        if (detailShowing || editorShowing) return
        closeAddPanel(immediate = true); hideKeyboard()
        accountDetails.show(root, card, source)
    }

    private fun onDetailVisibilityChanged(showing: Boolean) {
        if (::accountPage.isInitialized) scroll.setRenderEffect(
            if (showing) RenderEffect.createBlurEffect(10f, 10f, Shader.TileMode.CLAMP) else null
        )
        val color = if (showing) 0xffb0b3b2.toInt() else SURFACE
        window.statusBarColor = color; window.navigationBarColor = color
        if (!showing) {
            attachments.dismissPreview()
            editingTouch = false; dragging = false
        }
        syncSecureFlag()
    }

    private fun syncSecureFlag() {
        val secure = homeVisible &&
            (((page == 1 || pageTransition || pageScrubInProgress) && subPage == null) || subPage == AppPage.MIGRATION)
        if (secure || secureTransition || detailShowing) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun accountEditor(card: AccountCard?) = AccountEditor(
        activity = this,
        repository = accountRepository,
        card = card,
        initialColor = selectedAccountColor.takeIf { it != 0 } ?: palette.random(),
        palette = palette,
        notify = ::notify,
        readAttachment = attachments::read,
        pickAttachments = { editor -> attachments.pickFiles(editor.body, editor::addAttachments) },
        onDragging = { dragging = it },
        onEditingTouch = { editingTouch = it },
    )

    private fun otpEditor(entry: TotpAccount?, scanned: TotpConfig? = null) = OtpEditor(
        context = this,
        repository = authenticatorRepository,
        entry = entry,
        scanned = scanned,
        initialColor = selectedTotpColor.takeIf { it != 0 } ?: palette.random(),
        palette = palette,
        onEditingTouch = { editingTouch = it },
        notify = ::notify,
    )

    private fun showInlineTotp() {
        if (!addExpanded || inlineTotp) return
        inlineTotp = true
        val actions = addPanelBody.getChildAt(0) ?: return
        actions.animate().alpha(0f).translationY(-dp(6).toFloat()).setDuration(130).withEndAction {
            if (!addExpanded || !inlineTotp || page != 1) return@withEndAction
            addPanelBody.removeView(actions)
            val editor = otpEditor(null)
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
        val editor = otpEditor(entry, scanned)
        val (dialog, bodySlot, footer) = floatingEditor(if (entry == null) "添加认证账户" else "编辑卡片", source)
        bodySlot.addView(scroller(editor.body), LinearLayout.LayoutParams(-1, 0, 1f))
        if (entry != null) {
            val delete = button("删除") { view ->
                if (view.tag == "confirm") { authenticatorRepository.delete(entry.id); dialog.dismiss(); render() }
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
                    authenticatorRepository.import(configs, List(configs.size) { palette.random() })
                }) {
                    if (subPage == AppPage.MIGRATION) renderSubPage() else showHome()
                    notify("迁移账户已导入")
                }
            }.show()
    }

    private fun confirmTextImport(entries: List<Triple<String, String, String>>) {
        alertBuilder().setTitle("共 ${entries.size} 条记录")
            .setMessage(entries.joinToString("、") { it.first }).setNegativeButton("取消", null)
            .setPositiveButton("确认添加") { _, _ ->
                work("正在添加记录…", { accountRepository.import(entries, palette.random()) }) {
                    subPage = null; showHome(); selectPage(0); notify("已添加 ${entries.size} 条记录")
                }
            }.show()
    }
    private fun buildMigrationPage(body: LinearLayout) {
        clearMigrationExport()
        migrationPage = MigrationPage(body, authenticatorRepository.all(), ::openScanner, ::notify)
    }

    private fun showDrawer(initialDrag: Float = 0f) {
        if (pageTransition) return
        closeAddPanel(immediate = true); hideKeyboard()
        drawerController.show(root, initialDrag)
    }

    private fun animateDrawerOpen() = drawerController.animateOpen()
    private fun updateDrawerDrag(translation: Float) = drawerController.updateDrag(translation)
    private fun closeDrawer(afterClosed: (() -> Unit)? = null) = drawerController.close(afterClosed)

    private fun openSubPage(target: AppPage) {
        closeAddPanel()
        if (searchShown[page]) toggleSearch()
        subPage = target
        syncTicker()
        renderSubPage()
    }

    private fun renderSubPage(direction: Float = 1f) {
        val target = subPage ?: return showHome()
        val pageRoot = layoutInflater.inflate(R.layout.view_subpage, null, false) as LinearLayout
        pageRoot.alpha = 0f; pageRoot.translationY = dp(10).toFloat()
        pageRoot.findViewById<ImageView>(R.id.subpage_back).setOnClickListener { leaveSubPage() }
        pageRoot.findViewById<TextView>(R.id.subpage_title).setText(when (target) {
            AppPage.IMPORT -> "导入恢复数据库"
            AppPage.PARSER -> "文本解析"
            AppPage.SETTINGS -> "设置"
            AppPage.RECYCLE -> "回收站"
            AppPage.ABOUT -> "关于 AuthNotes"
            AppPage.MIGRATION -> "认证账户迁移"
        })
        val pageContent = if (target == AppPage.MIGRATION) column().also(::buildMigrationPage) else appPages.createPage(target)
        pageRoot.findViewById<FrameLayout>(R.id.subpage_content).addView(pageContent, FrameLayout.LayoutParams(-1, -1))
        transitionContent(pageRoot, direction)
        syncSecureFlag()
    }

    private fun leaveSubPage() {
        if (subPage == AppPage.IMPORT) {
            appPages.resetImport()
            if (store.database == null) { showSetup(setupUnlock); return }
        }
        if (subPage == AppPage.RECYCLE) {
            subPage = AppPage.SETTINGS; renderSubPage(-1f); return
        }
        if (subPage == AppPage.MIGRATION) clearMigrationExport()
        subPage = null
        showHome(-1f)
    }

    private fun clearMigrationExport() {
        migrationPage?.clearExport()
        migrationPage = null
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

    private fun chooseDatabase() {
        hideKeyboard()
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_REQUEST)
    }
    @Deprecated("Framework Activity result API keeps AndroidX Activity out of the APK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ATTACHMENT_REQUEST) {
            attachments.onPickResult(resultCode, data)
            return
        }
        if (requestCode == ATTACHMENT_SAVE_REQUEST) {
            attachments.onSaveResult(resultCode, data?.data)
            return
        }
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            SCAN_REQUEST -> {
                val raw = data?.getStringExtra(QrScannerActivity.EXTRA_VALUE) ?: return
                if (GoogleMigration.isMigrationUri(raw)) {
                    runCatching { GoogleMigration.decodeUri(raw) }.onSuccess(::confirmMigrationImport)
                        .onFailure { notify(it.message ?: "迁移二维码无效或包含不支持的账户") }
                } else {
                    runCatching { Totp.parseUri(raw) }.onSuccess {
                        if (subPage == AppPage.MIGRATION) confirmMigrationImport(listOf(it)) else editTotp(null, it)
                    }
                        .onFailure { notify(it.message ?: "不是支持的 TOTP 或 HOTP 二维码") }
                }
            }
            IMPORT_REQUEST -> {
                val uri = data?.data ?: return
                appPages.selectedDatabase(uri)
                subPage = AppPage.IMPORT
                renderSubPage()
            }
        }
    }

    override fun onBackPressed() {
        if (pageTransition) return
        when {
            detailShowing -> accountDetails.close()
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
        syncTicker()
        dialog.setOnDismissListener {
            editorShowing = false; editingTouch = false; dragging = false; activeEditor = null
            window.statusBarColor = SURFACE; window.navigationBarColor = SURFACE
            scroll.setRenderEffect(null)
            syncTicker()
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
        private const val SEARCH_DEBOUNCE_MILLIS = 180L
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
