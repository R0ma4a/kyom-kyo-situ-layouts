package com.example.rgyalrong_situ

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.roundToInt

class MyKeyboardService : InputMethodService() {

    enum class ShiftState { OFF, ONCE, LOCKED }

    enum class KeyboardPage { ALPHABET, SYMBOLS }

    data class KeySpec(
        val normal: String,
        val shifted: String = normal.uppercase(),
        val longPress: String? = null,
        val weight: Float = 1f
    )

    private data class RenderedKey(val button: Button, val spec: KeySpec)

    companion object {
        private const val DOUBLE_TAP_MS = 400L
    }

    private val alphabetRow1: List<KeySpec> = listOf(
        KeySpec("q"), KeySpec("w"), KeySpec("e"), KeySpec("r", "R", "ɽ"),
        KeySpec("t"), KeySpec("y"), KeySpec("u", "U", "ü"),
        KeySpec("i"), KeySpec("o", "O", "¤"), KeySpec("p")
    )
    private val alphabetRow2: List<KeySpec> = listOf(
        KeySpec("a"), KeySpec("s"), KeySpec("d"), KeySpec("f"), KeySpec("g"),
        KeySpec("h", "H", "ʰ"), KeySpec("j", "J", "ɟ"), KeySpec("k"), KeySpec("l")
    )
    private val alphabetRow3Letters: List<KeySpec> = listOf(
        KeySpec("z"), KeySpec("x"), KeySpec("c"), KeySpec("v"),
        KeySpec("b"), KeySpec("n", "N", "ŋ"), KeySpec("m")
    )

    private val symbolRow1: List<KeySpec> = listOf(
        KeySpec("1"), KeySpec("2"), KeySpec("3"), KeySpec("4"), KeySpec("5"),
        KeySpec("6"), KeySpec("7", "7", "/"), KeySpec("8"), KeySpec("9", "9", ">"),
        KeySpec("0")
    )
    private val symbolRow2: List<KeySpec> = listOf(
        KeySpec("@", "@", "~"),
        KeySpec("#", "#", "%"),
        KeySpec("$", "$", "^"),
        KeySpec("¥", "¥", "*"),
        KeySpec("&", "&"),
        KeySpec("-", "-", "_"),
        KeySpec("+", "+", "="),
        KeySpec("(", "(", "["),
        KeySpec(")", ")", "]"),
        KeySpec("\\", "\\", "|")
    )
    private val symbolRow3: List<KeySpec> = listOf(
        KeySpec("`", "`", "{"),
        KeySpec("*", "*", "}"),
        KeySpec("\"", "\""),
        KeySpec("'", "'", "‘"),
        KeySpec(":", ":"),
        KeySpec(";", ";"),
        KeySpec("!", "!", "！"),
        KeySpec("?", "?", "？")
    )

    private var keyboardPage: KeyboardPage = KeyboardPage.ALPHABET
    private var shiftState: ShiftState = ShiftState.OFF
    private var lastShiftTapUptimeMs: Long = 0L

    private var rootLayout: LinearLayout? = null
    private var shiftButton: Button? = null
    private val renderedCharacterKeys = mutableListOf<RenderedKey>()
    private var popupWindow: PopupWindow? = null

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#ECEFF1"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        installKeyboardRootPadding(root)
        rootLayout = root
        rebuildKeyboard()
        return root
    }

    private fun installKeyboardRootPadding(root: LinearLayout) {
        val padH = dp(6)
        val padTop = dp(6)
        val padBottomBase = dp(16)

        fun navigationBarInsetBottom(insets: WindowInsets): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
        }

        fun applyPadding(navBottomPx: Int) {
            val cushionIfNoInset = if (navBottomPx == 0) dp(12) else 0
            root.setPadding(padH, padTop, padH, padBottomBase + navBottomPx + cushionIfNoInset)
        }

        applyPadding(0)

        root.setOnApplyWindowInsetsListener { _, insets ->
            applyPadding(navigationBarInsetBottom(insets))
            insets
        }
        root.post { root.requestApplyInsets() }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        dismissPopup()
        shiftState = ShiftState.OFF
        keyboardPage = KeyboardPage.ALPHABET
        rebuildKeyboard()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        dismissPopup()
    }

    private fun rebuildKeyboard() {
        val root = rootLayout ?: return
        root.removeAllViews()
        renderedCharacterKeys.clear()
        shiftButton = null

        when (keyboardPage) {
            KeyboardPage.ALPHABET -> buildAlphabetPage(root)
            KeyboardPage.SYMBOLS -> buildSymbolPage(root)
        }
        refreshAllKeyLabels()
    }

    private fun buildAlphabetPage(root: LinearLayout) {
        root.addView(createCharacterRow(alphabetRow1))
        root.addView(createCharacterRow(alphabetRow2))
        root.addView(createAlphabetRow3WithShiftAndBackspace())
        root.addView(createAlphabetBottomRow())
    }

    private fun buildSymbolPage(root: LinearLayout) {
        root.addView(createCharacterRow(symbolRow1))
        root.addView(createCharacterRow(symbolRow2))
        root.addView(createSymbolRow3WithPageSwitchAndBackspace())
        root.addView(createSymbolBottomRow())
    }

    private fun createAlphabetRow3WithShiftAndBackspace(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = rowLayoutParams()

            val shift = createShiftButton()
            shiftButton = shift
            addView(shift, controlLayoutParams(1.15f))

            alphabetRow3Letters.forEach { spec ->
                val keyButton = createCharacterKeyButton(spec)
                renderedCharacterKeys += RenderedKey(keyButton, spec)
                addView(
                    keyButton,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(4)
                        marginEnd = dp(4)
                    }
                )
            }

            val backspace = createSpecialButton("⌫")
            backspace.setOnClickListener {
                dismissPopup()
                currentInputConnection?.deleteSurroundingText(1, 0)
            }
            addView(backspace, controlLayoutParams(1.25f))
        }
    }

    private fun createSymbolRow3WithPageSwitchAndBackspace(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = rowLayoutParams()

            val abc = createSpecialButton("ABC")
            abc.setOnClickListener {
                dismissPopup()
                keyboardPage = KeyboardPage.ALPHABET
                rebuildKeyboard()
            }
            addView(abc, controlLayoutParams(1.0f))

            symbolRow3.forEach { spec ->
                val keyButton = createCharacterKeyButton(spec)
                renderedCharacterKeys += RenderedKey(keyButton, spec)
                addView(
                    keyButton,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, spec.weight)
                        .apply {
                            marginStart = dp(4)
                            marginEnd = dp(4)
                        }
                )
            }

            val backspace = createSpecialButton("⌫")
            backspace.setOnClickListener {
                dismissPopup()
                currentInputConnection?.deleteSurroundingText(1, 0)
            }
            addView(backspace, controlLayoutParams(1.1f))
        }
    }

    private fun createAlphabetBottomRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = rowLayoutParams().apply { topMargin = dp(6) }

            val page123 = createSpecialButton("?123")
            page123.setOnClickListener {
                dismissPopup()
                keyboardPage = KeyboardPage.SYMBOLS
                rebuildKeyboard()
            }
            addView(page123, controlLayoutParams(1.1f))

            val comma = createCharacterKeyButton(KeySpec(",", "<"))
            renderedCharacterKeys += RenderedKey(comma, KeySpec(",", "<"))
            addView(comma, controlLayoutParams(0.9f))

            val space = createSpecialButton("")
            space.text = "Space"
            space.setOnClickListener {
                dismissPopup()
                commitText(" ", consumesOnce = false)
            }
            addView(space, controlLayoutParams(3.2f))

            val periodSpec = KeySpec(".", ">", "。")
            val period = createCharacterKeyButton(periodSpec)
            renderedCharacterKeys += RenderedKey(period, periodSpec)
            addView(period, controlLayoutParams(0.9f))

            val enter = createSpecialButton("Enter")
            enter.setOnClickListener {
                dismissPopup()
                currentInputConnection?.sendKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                )
                currentInputConnection?.sendKeyEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
                )
            }
            addView(enter, controlLayoutParams(1.35f))
        }
    }

    private fun createSymbolBottomRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = rowLayoutParams().apply { topMargin = dp(6) }

            val abc = createSpecialButton("ABC")
            abc.setOnClickListener {
                dismissPopup()
                keyboardPage = KeyboardPage.ALPHABET
                rebuildKeyboard()
            }
            addView(abc, controlLayoutParams(1.1f))

            val commaSpec = KeySpec(",", "<")
            val comma = createCharacterKeyButton(commaSpec)
            renderedCharacterKeys += RenderedKey(comma, commaSpec)
            addView(comma, controlLayoutParams(0.9f))

            val space = createSpecialButton("")
            space.text = "Space"
            space.setOnClickListener {
                dismissPopup()
                commitText(" ", consumesOnce = false)
            }
            addView(space, controlLayoutParams(3.2f))

            val periodSpecSymbol = KeySpec(".", ".", "。")
            val period = createCharacterKeyButton(periodSpecSymbol)
            renderedCharacterKeys += RenderedKey(period, periodSpecSymbol)
            addView(period, controlLayoutParams(0.9f))

            val enter = createSpecialButton("Enter")
            enter.setOnClickListener {
                dismissPopup()
                currentInputConnection?.sendKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                )
                currentInputConnection?.sendKeyEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
                )
            }
            addView(enter, controlLayoutParams(1.35f))
        }
    }

    private fun createCharacterRow(rowSpecs: List<KeySpec>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = rowLayoutParams()

            rowSpecs.forEach { spec ->
                val keyButton = createCharacterKeyButton(spec)
                renderedCharacterKeys += RenderedKey(keyButton, spec)
                addView(
                    keyButton,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, spec.weight)
                        .apply {
                            marginStart = dp(4)
                            marginEnd = dp(4)
                        }
                )
            }
        }
    }

    private fun createCharacterKeyButton(spec: KeySpec): Button {
        return createBaseKeyButton().apply {
            text = getDisplayForSpec(spec)
            setOnClickListener {
                dismissPopup()
                val out = getOutputForSpec(spec)
                val consumesOnce = keyboardPage == KeyboardPage.ALPHABET &&
                    spec.normal.length == 1 &&
                    spec.normal[0] in 'a'..'z'
                commitText(out, consumesOnce = consumesOnce)
            }
            setOnLongClickListener { anchor ->
                if (spec.longPress != null) {
                    showLongPressPopup(anchor, spec.longPress)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun createShiftButton(): Button {
        return createBaseKeyButton().apply {
            setOnClickListener {
                dismissPopup()
                handleShiftTap()
            }
        }
    }

    private fun handleShiftTap() {
        val now = SystemClock.uptimeMillis()
        val dt = now - lastShiftTapUptimeMs
        lastShiftTapUptimeMs = now

        when (shiftState) {
            ShiftState.OFF -> {
                shiftState = ShiftState.ONCE
            }
            ShiftState.ONCE -> {
                shiftState = if (dt in 1 until DOUBLE_TAP_MS) {
                    ShiftState.LOCKED
                } else {
                    ShiftState.OFF
                }
            }
            ShiftState.LOCKED -> {
                shiftState = if (dt in 1 until DOUBLE_TAP_MS) {
                    ShiftState.OFF
                } else {
                    ShiftState.LOCKED
                }
            }
        }
        refreshAllKeyLabels()
        refreshShiftButtonAppearance()
    }

    private fun createSpecialButton(label: String): Button {
        return createBaseKeyButton().apply {
            text = label
        }
    }

    private fun createBaseKeyButton(): Button {
        return Button(this).apply {
            isAllCaps = false
            textSize = 16f
            minHeight = dp(42)
            minimumHeight = dp(42)
            setPadding(0, dp(12), 0, dp(12))
            background = createDefaultKeyBackground()
        }
    }

    private fun rowLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
        }
    }

    private fun controlLayoutParams(weight: Float): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
    }

    private fun createDefaultKeyBackground(): StateListDrawable {
        val stroke = Color.parseColor("#B0BEC5")
        val corner = dp(10).toFloat()
        val pressed = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setColor(Color.parseColor("#CFD8DC"))
            setStroke(2, stroke)
        }
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setColor(Color.WHITE)
            setStroke(2, stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun shiftBaseColors(): Pair<Int, Int> {
        return when (shiftState) {
            ShiftState.OFF -> Color.WHITE to Color.parseColor("#B0BEC5")
            ShiftState.ONCE -> Color.parseColor("#E3F2FD") to Color.parseColor("#1976D2")
            ShiftState.LOCKED -> Color.parseColor("#BBDEFB") to Color.parseColor("#0D47A1")
        }
    }

    private fun createShiftKeyBackground(): StateListDrawable {
        val (fill, stroke) = shiftBaseColors()
        val corner = dp(10).toFloat()
        val strokeColor = stroke
        val pressedFill = Color.rgb(
            (Color.red(fill) * 0.85f).toInt().coerceIn(0, 255),
            (Color.green(fill) * 0.85f).toInt().coerceIn(0, 255),
            (Color.blue(fill) * 0.85f).toInt().coerceIn(0, 255)
        )
        val pressed = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setColor(pressedFill)
            setStroke(2, strokeColor)
        }
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setColor(fill)
            setStroke(2, strokeColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun refreshShiftButtonAppearance() {
        val btn = shiftButton ?: return
        btn.background = createShiftKeyBackground()
        btn.text = when (shiftState) {
            ShiftState.OFF -> "Shift"
            ShiftState.ONCE -> "⇧"
            ShiftState.LOCKED -> "⇪"
        }
        btn.contentDescription = when (shiftState) {
            ShiftState.OFF -> "Shift off"
            ShiftState.ONCE -> "Shift once"
            ShiftState.LOCKED -> "Caps lock"
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun showLongPressPopup(anchor: View, longPressChar: String) {
        dismissPopup()

        val popupText = TextView(this).apply {
            text = longPressChar
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(16), dp(28), dp(16))
            setBackgroundColor(Color.WHITE)
            setOnClickListener {
                commitText(longPressChar, consumesOnce = false)
                dismissPopup()
            }
        }

        popupWindow = PopupWindow(
            popupText,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = 8f
            setBackgroundDrawable(
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.WHITE)
                    setStroke(2, Color.parseColor("#90A4AE"))
                }
            )
            showAsDropDown(anchor, 0, -(anchor.height * 2))
        }
    }

    private fun dismissPopup() {
        popupWindow?.dismiss()
        popupWindow = null
    }

    private fun commitText(value: String, consumesOnce: Boolean) {
        currentInputConnection?.commitText(value, 1)
        if (consumesOnce && shiftState == ShiftState.ONCE) {
            shiftState = ShiftState.OFF
            refreshAllKeyLabels()
            refreshShiftButtonAppearance()
        }
    }

    private fun isShiftedForLetters(): Boolean {
        return shiftState == ShiftState.ONCE || shiftState == ShiftState.LOCKED
    }

    private fun getDisplayForSpec(spec: KeySpec): String {
        if (keyboardPage == KeyboardPage.SYMBOLS) {
            return spec.normal
        }
        return if (isShiftedForLetters()) spec.shifted else spec.normal
    }

    private fun getOutputForSpec(spec: KeySpec): String {
        if (keyboardPage == KeyboardPage.SYMBOLS) {
            return spec.normal
        }
        return if (isShiftedForLetters()) spec.shifted else spec.normal
    }

    private fun refreshAllKeyLabels() {
        renderedCharacterKeys.forEach { rendered ->
            rendered.button.text = getDisplayForSpec(rendered.spec)
        }
        if (keyboardPage == KeyboardPage.ALPHABET) {
            refreshShiftButtonAppearance()
        }
    }
}
