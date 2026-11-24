package com.authnotes.ui.pages

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.authnotes.R
import com.authnotes.data.AccountCard
import com.authnotes.data.AccountRepository
import com.authnotes.data.SettingsRepository
import com.authnotes.ui.EditorViews
import com.authnotes.ui.EncryptedDatabaseIconView

internal enum class AppPage { IMPORT, PARSER, SETTINGS, RECYCLE, ABOUT, MIGRATION }

/** Builds non-home application pages; privileged operations and navigation remain host callbacks. */
internal class AppPageController(
    private val activity: Activity,
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
    private val notify: (String) -> Unit,
    private val actionButton: (String, (View) -> Unit) -> android.widget.TextView,
    private val alert: () -> AlertDialog.Builder,
    private val onCreateOrUnlock: (String, Boolean, (Boolean) -> Unit) -> Unit,
    private val onChangePassword: (String, () -> Unit) -> Unit,
    private val onImport: (Uri, String, (Boolean) -> Unit) -> Unit,
    private val onChooseDatabase: () -> Unit,
    private val onOpenSubpage: (AppPage) -> Unit,
    private val onConfirmTextImport: (List<Triple<String, String, String>>) -> Unit,
    private val onTimeCorrection: (String, (Long) -> Unit) -> Unit,
    private val onOffsetChanged: (Long) -> Unit,
) : EditorViews(activity) {
    private var selectedImportUri: Uri? = null
    private var importPasswordDraft = ""

    fun resetImport() {
        selectedImportUri = null
        importPasswordDraft = ""
    }

    fun selectedDatabase(uri: Uri) {
        selectedImportUri = uri
    }

    fun showSetup(unlock: Boolean, openImport: () -> Unit, transition: (View, Float) -> Unit) {
        val body = column().apply {
            setBackgroundColor(context.getColor(R.color.surface))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), 0, dp(28), 0)
        }
        body.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        body.addView(text("AuthNotes", 30, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        body.addView(View(context).apply { setBackgroundColor(RULE) }, LinearLayout.LayoutParams(dp(180), dp(1)).apply {
            topMargin = dp(14); bottomMargin = dp(24)
        })
        body.addView(text(if (unlock) "鉴权失败，请重新输入数据主密码" else "", 14, color = MUTED).apply { gravity = Gravity.CENTER })
        body.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        val form = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val password = input("数据主密码", secret = true).apply { gravity = Gravity.CENTER }
        val confirmation = input("再次输入数据主密码", secret = true).apply { gravity = Gravity.CENTER }
        form.addView(password, LinearLayout.LayoutParams(-1, dp(58)))
        if (!unlock) form.addView(confirmation, LinearLayout.LayoutParams(-1, dp(58)))
        form.addView(actionButton(if (unlock) "解锁数据库" else "创建数据库") {
            val value = password.text.toString()
            when {
                value.isEmpty() -> notify("请输入数据主密码")
                !unlock && (value.length < 4 || value != confirmation.text.toString()) ->
                notify("主密码至少 4 个字符，两次输入须一致")
                else -> onCreateOrUnlock(value, unlock) { ready ->
                    password.text.clear(); confirmation.text.clear()
                    if (!ready) notify("鉴权成功，但本机自动解锁暂不可用。下次启动时请手动输入数据主密码。")
                }
            }
        }.apply { setTextColor(context.getColor(R.color.accent_green)); textSize = 18f }, LinearLayout.LayoutParams(-1, dp(58)))
        body.addView(form, LinearLayout.LayoutParams(-1, -2))
        body.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        body.addView(actionButton("导入备份") { openImport() }.apply { setTextColor(BLUE) })
        body.addView(text(if (unlock) "鉴权失败时，可重新输入主密码" else "设置数据主密码，或导入已有 AuthNotes 数据库。", 12, color = MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(22))
        })
        transition(body, -1f)
    }

    fun createPage(page: AppPage): View {
        val body = column().apply { pad(if (page == AppPage.IMPORT) 28 else 20) }
        if (page == AppPage.IMPORT) buildImportPage(body) else when (page) {
            AppPage.PARSER -> buildParserPage(body)
            AppPage.SETTINGS -> buildSettingsPage(body)
            AppPage.RECYCLE -> buildRecyclePage(body)
            AppPage.ABOUT -> buildAboutPage(body)
            AppPage.MIGRATION -> error("Migration page is hosted by MainActivity")
            AppPage.IMPORT -> Unit
        }
        return if (page == AppPage.IMPORT) body.apply { gravity = Gravity.CENTER_HORIZONTAL }
            else ScrollView(activity).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; addView(body) }
    }

    private fun buildImportPage(body: LinearLayout) {
        body.addView(View(context), LinearLayout.LayoutParams(1, 0, .22f))
        val choose = column().apply { gravity = Gravity.CENTER; isClickable = true; isFocusable = true }
        choose.addView(EncryptedDatabaseIconView(activity).apply { setLoaded(selectedImportUri != null) }, LinearLayout.LayoutParams(dp(112), dp(112)))
        choose.addView(text(if (selectedImportUri == null) "选择数据库文件" else "已加载数据库文件", 17, true, BLUE).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(42)))
        choose.setOnClickListener { onChooseDatabase() }
        body.addView(choose)
        body.addView(View(context), LinearLayout.LayoutParams(1, 0, .42f))
        val password = input("数据主密码", secret = true).apply {
            setText(importPasswordDraft); gravity = Gravity.CENTER; textSize = 20f
        }
        body.addView(password, LinearLayout.LayoutParams(-1, dp(62)))
        val restore = actionButton("恢复") {
            val uri = selectedImportUri
            val value = password.text.toString()
            if (uri != null && value.isNotEmpty()) {
                onImport(uri, value) { ready ->
                    password.text.clear(); resetImport()
                    if (!ready) notify("数据库已恢复。下次启动时请手动输入数据主密码。")
                }
            }
        }.apply { setTextColor(context.getColor(R.color.accent_green)); textSize = 18f; isEnabled = false; alpha = .45f }
        body.addView(restore, LinearLayout.LayoutParams(dp(180), dp(58)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })
        val updateButton = {
            val enabled = selectedImportUri != null && importPasswordDraft.isNotEmpty()
            restore.isEnabled = enabled; restore.alpha = if (enabled) 1f else .45f
        }
        password.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                importPasswordDraft = s?.toString().orEmpty(); updateButton()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        updateButton()
        body.addView(View(context), LinearLayout.LayoutParams(1, 0, .55f))
        body.addView(text("选择 AuthNotes 加密数据库备份并输入备份时设置的主密码。恢复会替换此设备当前保存的数据。", 13, color = MUTED).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(18))
        })
        body.addView(View(context), LinearLayout.LayoutParams(1, 0, .10f))
    }

    private fun buildParserPage(body: LinearLayout) {
        body.addView(text("每条记录依次填写标签、账号、密码；记录之间空一行。标签必填，备注和自定义字段不会从文本导入。", 15))
        val value = EditText(activity).apply {
            configureInput(this, "标签\n账号\n密码\n\n另一个标签")
            setSingleLine(false); minLines = 12; gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        body.addView(value)
        body.addView(actionButton("填入示例") { value.setText("Google\nname@gmail.com\nexample-password\n\n手机号登录\n13800000000") })
        body.addView(actionButton("解析并添加") {
            runCatching {
                val blocks = value.text.toString().replace("\r\n", "\n").trim('\n', '\r').split(Regex("\n[\\t ]*\n+"))
                    .filter { it.isNotBlank() }
                require(blocks.isNotEmpty()) { "没有可解析的内容" }
                blocks.map { block ->
                    val lines = block.split('\n')
                    require(lines.size <= 3 && lines[0].isNotBlank()) { "每条记录最多三行，并且必须填写标签" }
                    Triple(lines[0].trim(), lines.getOrElse(1) { "" }, lines.getOrElse(2) { "" })
                }
            }.onSuccess(onConfirmTextImport).onFailure { notify(it.message ?: "文本格式有误") }
        })
    }

    private fun buildSettingsPage(body: LinearLayout) {
        body.addView(text("数据主密码", 18, true))
        val password = input("新数据主密码", secret = true)
        val confirmation = input("确认新数据主密码", secret = true)
        body.addView(password); body.addView(confirmation)
        body.addView(text("至少 4 个字符。已导出的旧备份仍使用导出时的数据主密码。", 13, color = MUTED))
        body.addView(actionButton("修改数据主密码") {
            val value = password.text.toString()
            if (value.length < 4 || value != confirmation.text.toString()) {
                notify("主密码至少 4 个字符，两次输入须一致")
            } else {
                onChangePassword(value) {
                    password.text.clear(); confirmation.text.clear()
                    notify("主密码已修改")
                }
            }
        })
        body.addView(text("卡片字段预览", 18, true).apply { setPadding(0, dp(20), 0, 0) })
        body.addView(CheckBox(activity).apply {
            text = "预览首个自定义字段内容（关闭时以密码点显示）"
            isChecked = settings.get("show_card_preview", "0") == "1"
            buttonTintList = android.content.res.ColorStateList.valueOf(BLUE)
            setOnCheckedChangeListener { _, checked -> settings.set("show_card_preview", if (checked) "1" else "0") }
        })
        body.addView(text("TOTP 时间校正", 18, true).apply { setPadding(0, dp(24), 0, 0) })
        body.addView(text("仅调整 AuthNotes 生成验证码时使用的时间，不修改手机系统时间。", 14))
        val host = input("时间服务器").apply { setText(settings.get("ntp_server", "time.cloudflare.com")) }
        val offset = input("手动偏移（毫秒）").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(settings.get("time_offset_ms", "0"))
        }
        body.addView(host); body.addView(offset)
        body.addView(actionButton("自动校正时间") {
            val server = host.text.toString().trim()
            if (server.isBlank()) {
                notify("请输入时间服务器")
            } else {
                onTimeCorrection(server) { value ->
                    settings.set("ntp_server", server)
                    settings.set("time_offset_ms", value.toString())
                    offset.setText(value.toString()); onOffsetChanged(value); notify("时间已校正，偏移 $value 毫秒")
                }
            }
        })
        body.addView(actionButton("保存手动偏移") {
            val value = offset.text.toString().toLongOrNull()
            if (value == null || value !in -86400000L..86400000L) notify("偏移必须在正负一天以内")
            else { settings.set("time_offset_ms", value.toString()); onOffsetChanged(value) }
        })
        body.addView(actionButton("重置为系统时间") {
            offset.setText("0"); settings.set("time_offset_ms", "0"); onOffsetChanged(0L)
        })
        body.addView(actionButton("回收站") { onOpenSubpage(AppPage.RECYCLE) })
    }

    private fun buildRecyclePage(body: LinearLayout) {
        val items: List<AccountCard> = accounts.deletedCards()
        if (items.isEmpty()) body.addView(text("回收站为空", color = MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(72), 0, dp(72)) })
        items.forEach { card ->
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(context.getColor(R.color.white)); pad(14) }
            row.addView(text("●", 17, color = card.color).apply { setPadding(0, 0, dp(12), 0) })
            row.addView(text(card.tag, 16, true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(actionButton("操作") {
                alert().setTitle(card.tag)
                    .setNegativeButton("永久删除") { _, _ -> accounts.delete(card.id, permanent = true); onOpenSubpage(AppPage.RECYCLE) }
                    .setPositiveButton("恢复") { _, _ -> accounts.restore(card.id); onOpenSubpage(AppPage.RECYCLE) }
                    .setNeutralButton("取消", null).show()
            })
            row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            body.addView(row)
        }
    }

    private fun buildAboutPage(body: LinearLayout) {
        body.addView(text("AuthNotes", 24, true))
        body.addView(text("本地账号信息与 TOTP / HOTP 动态验证码管理工具。", 16))
        body.addView(text("账号卡片及 TOTP / HOTP 认证数据保存在 SQLCipher 加密数据库中。数据库主密码由你自行保管；本机使用 Android Keystore 加密保存自动解锁凭据。", 15))
        body.addView(text("AuthNotes 1.0\n数据仅保存在本机及你主动导出的加密备份中。", 14, color = MUTED))
    }
}
