package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.TypedValue
import com.xcloud.tclnative.R
import com.xcloud.tclnative.settings.ConsoleScene
import com.xcloud.tclnative.settings.SeriesGuideSide
import com.xcloud.tclnative.settings.SeriesPersonalizationStore
import java.util.Locale
import kotlin.math.roundToInt

data class ConsoleAction(val label: String, val action: () -> Unit) { var longAction: (() -> Unit)?=null }
enum class ConsolePanelPurpose { GENERAL, SEARCH, SETTINGS_HOME, GUIDE, CONTROL_CENTER, PROFILE }

/** Shared native panels for search, account menus and messages. No platform alert buttons/rows. */
class ConsoleDialog(context: Context, private val heading: String,private val purpose: ConsolePanelPurpose=ConsolePanelPurpose.GENERAL) : Dialog(context, R.style.ConsoleDialog) {
    init { DashboardTheme.applyOverlay(this.context) }
    private val navigationContext=context
    private val activity = context as? Activity
    private val skin=ConsolePanelSkin()
    private val fullScreen=skin.scene.playStation && purpose !in listOf(ConsolePanelPurpose.GUIDE,ConsolePanelPurpose.CONTROL_CENTER) && !(purpose==ConsolePanelPurpose.PROFILE && skin.scene==ConsoleScene.PS5)
    private val screenWidth = activity?.window?.decorView?.width?.takeIf { it > 0 }
        ?: context.resources.displayMetrics.widthPixels
    private val screenHeight = activity?.window?.decorView?.height?.takeIf { it > 0 }
        ?: context.resources.displayMetrics.heightPixels
    private val phone=skin.scene.playStation && consolePhone(context,screenWidth,screenHeight)
    val scale = (if(skin.enabled) consoleReferenceScale(screenWidth, screenHeight) else screenWidth/1280f).coerceAtLeast(if(skin.scene.playStation) .5f else maxOf(.5f,context.resources.displayMetrics.density*.65f))
    val panel: LinearLayout = object : LinearLayout(context) {
        override fun dispatchTouchEvent(event: android.view.MotionEvent)=ConsoleHitTargets.dispatch(this,event) || super.dispatchTouchEvent(event)
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limit = (screenHeight*if(fullScreen) 1f else .90f).roundToInt()
            val requested = MeasureSpec.getSize(heightMeasureSpec)
            val bound = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) limit else minOf(limit, requested)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(bound, if(fullScreen) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        background = if(skin.enabled) skin.background() else DashboardTheme.panel(16*scale)
        setPadding(px(if(fullScreen) 64 else 32), px(if(fullScreen) 32 else 26), px(if(fullScreen) 64 else 32), px(if(phone) 20 else 26))
    }
    val body = object: LinearLayout(context) {
        override fun dispatchTouchEvent(event: android.view.MotionEvent)=ConsoleHitTargets.dispatch(this,event) || super.dispatchTouchEvent(event)
    }.apply {
        orientation = LinearLayout.VERTICAL
        if(fullScreen) setPadding(px(100),0,px(100),0)
    }
    private val actions = object: LinearLayout(context) {
        override fun dispatchTouchEvent(event: android.view.MotionEvent)=ConsoleHitTargets.dispatch(this,event) || super.dispatchTouchEvent(event)
    }.apply { orientation = LinearLayout.HORIZONTAL;isBaselineAligned=false }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = skin.scene.playStation
        overScrollMode = View.OVER_SCROLL_NEVER
        DashboardTheme.customFocus(this)
        addView(body, ViewGroup.LayoutParams(-1, -2))
    }
    private var initialFocus: View? = null
    private var explicitFocus=false
    private val focusKey=focusHistory[navigationContext]?.get(heading)
    private var returningFocus: View?=null
    companion object {
        private val focusHistory=java.util.WeakHashMap<Context,MutableMap<String,String>>()
        private val visiblePanels=mutableListOf<java.lang.ref.WeakReference<ConsoleDialog>>()
    }
    private fun closePanels() {
        // Some previews keep their parent open. X closes the entire owned panel group;
        // dismiss never invokes the back/cancel callback or a destructive action.
        visiblePanels.mapNotNull { it.get() }.filter {
            it.navigationContext===navigationContext || (activity!=null && it.activity===activity)
        }.asReversed().forEach { it.dismiss() }
    }
    private var menuItems=0
    private var tileRow: LinearLayout?=null
    var shortcut: ((KeyEvent) -> Boolean)?=null
    private val cleanups=mutableListOf<() -> Unit>()
    fun addCleanup(action: () -> Unit) { cleanups+=action }
    override fun onStop() {
        visiblePanels.removeAll { it.get()==null || it.get()===this }
        window?.let(AppControllerWindows::detach)
        val pending=cleanups.toList();cleanups.clear();pending.forEach { it() }
        super.onStop()
    }
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        activity?.let(::setOwnerActivity)
        val headingContent=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        if(purpose==ConsolePanelPurpose.CONTROL_CENTER || purpose==ConsolePanelPurpose.GUIDE && skin.scene.playStation) {
            headingContent.addView(text(heading,24),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=px(14) })
            if(purpose==ConsolePanelPurpose.GUIDE) panel.minimumHeight=(screenHeight*.88f).roundToInt()
        } else if(skin.scene.playStation) {
            headingContent.addView(text(heading,34),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=px(22) })
        } else if(purpose==ConsolePanelPurpose.GUIDE) {
            val brand=ConsoleBrandView(context)
            headingContent.addView(brand,LinearLayout.LayoutParams(px(176),px(42)))
            headingContent.addView(text(heading,27,true),LinearLayout.LayoutParams(-1,-2).apply { topMargin=px(16);bottomMargin=px(16) })
            panel.minimumHeight=(screenHeight*.88f).roundToInt()
        } else if(skin.enabled && screenWidth/context.resources.displayMetrics.density>=600 && screenHeight/context.resources.displayMetrics.density>=480) {
            val header=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
            val brand=ConsoleBrandView(context)
            header.addView(brand,LinearLayout.LayoutParams(px(if(skin.scene==ConsoleScene.XBOX_ORIGINAL) 204 else 176),px(62)))
            val title=text(heading,35,true).apply { maxLines=2;gravity=Gravity.CENTER_VERTICAL }
            header.addView(title,LinearLayout.LayoutParams(0,px(82),1f).apply { marginStart=px(22);marginEnd=px(14) })
            val icon=object: View(context) {
                private val icons=ConsoleIcons()
                override fun onDraw(canvas: android.graphics.Canvas) {
                    icons.draw(canvas,if(purpose==ConsolePanelPurpose.SEARCH) ConsoleGlyph.SEARCH else ConsoleGlyph.SETTINGS,0f,0f,minOf(width,height).toFloat(),skin.ink)
                }
            }
            header.addView(icon,LinearLayout.LayoutParams(px(32),px(32)))
            headingContent.addView(header,LinearLayout.LayoutParams(-1,px(94)).apply { bottomMargin=px(14) })
        } else {
            headingContent.addView(text("xCLOUD", 16, true, DashboardTheme.accent), LinearLayout.LayoutParams(-1, -2))
            headingContent.addView(text(heading, 35, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin=px(5); bottomMargin=px(23) })
        }
        val header=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP }
        header.addView(headingContent,LinearLayout.LayoutParams(0,-2,1f))
        val close=keyButton("×") { closePanels() }.apply {
            tag="close-settings"
            if(skin.scene.playStation) background=ConsoleControlPlate(skin.scene,ConsoleControl.TAB)
            contentDescription=UiText.text("Fechar ajustes e voltar")
            if(skin.scene.playStation) setTextSize(TypedValue.COMPLEX_UNIT_PX,28*scale) else setTextSize(TypedValue.COMPLEX_UNIT_SP,28f)
            setPadding(0,0,0,0)
        }
        val closeSize=if(skin.scene.playStation) px(48) else touchSize()
        header.addView(close,LinearLayout.LayoutParams(closeSize,closeSize).apply { marginStart=px(10) })
        panel.addView(header,LinearLayout.LayoutParams(-1,-2))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin=px(22) })
        if(skin.scene.playStation) ConsoleHitTargets.install(panel,listOf(close))
        setContentView(panel)
        setOnKeyListener { _, key, event ->
            if(shortcut?.invoke(event)==true) true else when (key) {
                KeyEvent.KEYCODE_BUTTON_X -> {
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) closePanels()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) cancel()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) window?.currentFocus?.performClick()
                    true
                }
                else -> false
            }
        }
    }
    fun touchSize()=(48*context.resources.displayMetrics.density).roundToInt()
    fun addSection(label: String) {
        body.addView(text(label,20,true,DashboardTheme.accent),LinearLayout.LayoutParams(-1,-2).apply { topMargin=px(14);bottomMargin=px(9) })
    }
    /** Compact shortcuts keep text labels and use real native focus/accessibility nodes. */
    internal fun addShortcuts(items: List<Pair<ConsoleGlyph,ConsoleAction>>) {
        val sideMenu=purpose==ConsolePanelPurpose.GUIDE && skin.scene==ConsoleScene.PS4
        val center=purpose==ConsolePanelPurpose.CONTROL_CENTER && screenWidth>screenHeight
        val compact=sideMenu || screenWidth>screenHeight && screenHeight/context.resources.displayMetrics.density<480
        val hitTargets=mutableListOf<View>()
        fun addGroup(groupItems: List<Pair<ConsoleGlyph,ConsoleAction>>,columns: Int,verticalIcon: Boolean,height: Int,small: Boolean=false) {
            groupItems.chunked(columns).forEach { group ->
                val row=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;isBaselineAligned=false }
                group.forEach { (icon,action) ->
                    val button=keyButton(action.label) { dismiss();action.action() }.apply {
                        val padding=if(center) 8 else 10
                        gravity=Gravity.CENTER;setPadding(px(8),px(padding),px(8),px(padding));maxLines=2
                        if(center) ellipsize=android.text.TextUtils.TruncateAt.END
                        setTextSize(TypedValue.COMPLEX_UNIT_PX,(if(small) 17 else 21)*scale)
                        val mark=ConsoleIconDrawable(icon,px(28),if(skin.enabled) skin.textColors() else android.content.res.ColorStateList.valueOf(Color.WHITE))
                        setCompoundDrawablesWithIntrinsicBounds(if(verticalIcon) null else mark,if(verticalIcon) mark else null,null,null)
                        compoundDrawablePadding=px(7);minimumHeight=if(sideMenu) px(64) else touchSize();contentDescription=action.label
                        action.longAction?.let { longAction ->
                            setOnLongClickListener { dismiss();longAction();true }
                            if(android.os.Build.VERSION.SDK_INT>=26) tooltipText=UiText.text("Personalizar estatísticas")
                        }
                    }
                    hitTargets+=button
                    row.addView(button,LinearLayout.LayoutParams(0,if(sideMenu) px(height) else maxOf(touchSize(),px(height)),1f).apply { marginEnd=px(7) })
                    if(initialFocus==null) initialFocus=button
                }
                repeat(columns-group.size) { row.addView(View(context),LinearLayout.LayoutParams(0,1,1f)) }
                body.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=px(7) })
            }
        }
        if(center) {
            val prominent=listOf(ConsoleGlyph.CONTROLLER,ConsoleGlyph.AUDIO,ConsoleGlyph.IMAGE).mapNotNull { glyph -> items.firstOrNull { it.first==glyph } }
            addGroup(prominent,3,true,112)
            addGroup(items.filterNot { it in prominent },3,false,56,true)
        } else {
            val columns=if(sideMenu) 1 else if(purpose==ConsolePanelPurpose.GUIDE || screenWidth<screenHeight) 2 else 3
            addGroup(items,columns,!compact,if(sideMenu) 64 else if(compact) 0 else 100)
        }
        if(sideMenu) ConsoleHitTargets.install(body,hitTargets)
    }
    fun px(value: Int) = (value*scale).roundToInt()
    fun text(value: String, size: Int = 22, heavy: Boolean = false, color: Int = Color.WHITE) = com.xcloud.tclnative.i18n.LocalizedTextView(context).apply {
        text=value; typeface=if(skin.enabled) skin.font(heavy) else DashboardTheme.font(context, heavy); includeFontPadding=false
        setTextColor(if(skin.enabled) skin.textColor(color) else color); setTextSize(TypedValue.COMPLEX_UNIT_PX, size*scale)
    }
    fun focusFirst(view: View) { initialFocus = view;explicitFocus=true }
    internal fun keyButton(label: String,action: () -> Unit): TextView = DashboardTheme.button(context,label,scale,action).apply {
        if(skin.enabled) { background=skin.row();setTextColor(skin.textColors());typeface=skin.font() }
    }
    fun addAction(label: String, action: () -> Unit): TextView = actionButton(label,true,action)
    fun addPersistentAction(label: String, action: () -> Unit): TextView = actionButton(label,false,action)
    private fun actionButton(label: String,dismissOnClick: Boolean,action: () -> Unit): TextView {
        val button = DashboardTheme.button(context, UiText.uppercase(label), scale) { if(dismissOnClick) dismiss(); action() }
        if(skin.enabled) button.apply { background=if(skin.scene.playStation) ConsoleControlPlate(skin.scene,ConsoleControl.TAB) else skin.row();setTextColor(skin.textColors());typeface=skin.font(true) }
        val ps4Guide=purpose==ConsolePanelPurpose.GUIDE && skin.scene==ConsoleScene.PS4
        if(ps4Guide) button.setTextSize(TypedValue.COMPLEX_UNIT_PX,21*scale)
        if(phone) {
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX,21*scale)
            button.setPadding(px(15),px(8),px(15),px(8));button.minimumHeight=px(58)
        }
        val compactBack=fullScreen && label==UiText.text("Voltar")
        actions.addView(button, LinearLayout.LayoutParams(if(compactBack) px(170) else 0, if(phone) ViewGroup.LayoutParams.WRAP_CONTENT else maxOf(px(if(ps4Guide) 80 else 58),touchSize()), if(compactBack) 0f else 1f).apply {
            if (actions.childCount > 1) marginStart = px(10)
        })
        if (initialFocus == null) initialFocus = button
        if(phone) ConsoleHitTargets.install(actions,(0 until actions.childCount).map { actions.getChildAt(it) })
        return button
    }
    fun addMenuItem(label: String, action: () -> Unit): TextView {
        val tile=skin.enabled && purpose==ConsolePanelPurpose.SETTINGS_HOME && skin.columns>1
        val stableLabel=label.substringBefore(":")
        val item = DashboardTheme.button(context, label, scale) {
            val history=focusHistory.getOrPut(navigationContext) { linkedMapOf() }
            if(history.size>=24) history.clear()
            history[heading]=stableLabel
            dismiss();action()
        }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(px(20), px(10), px(20), px(10))
            if(android.os.Build.VERSION.SDK_INT>=26) setAutoSizeTextTypeUniformWithConfiguration(
                px(13).coerceAtLeast(1),px(23).coerceAtLeast(2),1,TypedValue.COMPLEX_UNIT_PX)
            if(skin.enabled) {
                background=skin.row(tile,menuItems);typeface=skin.font();setTextColor(skin.textColors(tile))
                if(skin.scene.playStation && purpose==ConsolePanelPurpose.SETTINGS_HOME) {
                    val glyphs=listOf(ConsoleGlyph.DISPLAY,ConsoleGlyph.CONTROLLER,ConsoleGlyph.AUDIO,ConsoleGlyph.PALETTE,ConsoleGlyph.ACCOUNT,ConsoleGlyph.DIAGNOSTICS)
                    setCompoundDrawablesWithIntrinsicBounds(ConsoleIconDrawable(glyphs[menuItems%glyphs.size],px(28),skin.textColors()),null,null,null)
                    compoundDrawablePadding=px(26)
                }
                if(skin.scene==ConsoleScene.XBOX_ORIGINAL) { setPadding(px(38),px(10),px(20),px(10));letterSpacing=.04f }
                if(tile) { gravity=Gravity.BOTTOM or Gravity.START;setPadding(px(20),px(46),px(15),px(13)) }
            }
        }
        if(tile) {
            if(menuItems%skin.columns==0) {
                tileRow=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
                body.addView(tileRow,LinearLayout.LayoutParams(-1,px(88)).apply { bottomMargin=px(7) })
            }
            tileRow!!.addView(item,LinearLayout.LayoutParams(0,-1,1f).apply { if(menuItems%skin.columns!=0) marginStart=px(10) })
        } else body.addView(item, LinearLayout.LayoutParams(-1, if(phone) ViewGroup.LayoutParams.WRAP_CONTENT else maxOf(px(62),touchSize())).apply { bottomMargin = px(if(skin.scene.playStation) 0 else 7) })
        if(phone) {
            item.minimumHeight=px(62);item.maxLines=3
            if(android.os.Build.VERSION.SDK_INT>=26) item.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            item.setTextSize(TypedValue.COMPLEX_UNIT_PX,23*scale)
        }
        if(stableLabel==focusKey) returningFocus=item
        menuItems++
        if(phone) ConsoleHitTargets.install(body,(0 until body.childCount).map { body.getChildAt(it) }.filter { it.isClickable })
        if (initialFocus == null) initialFocus = item
        return item
    }
    override fun show() {
        window?.let(com.xcloud.tclnative.ui.ImmersiveWindow::apply)
        super.show()
        visiblePanels.removeAll { it.get()==null || it.get()===this }
        visiblePanels.add(java.lang.ref.WeakReference(this))
        activity?.let { owner -> window?.let { AppControllerWindows.attach(owner,it) } }
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0,0,0,0)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(if(skin.enabled) .48f else .72f)
            if(purpose==ConsolePanelPurpose.CONTROL_CENTER) {
                setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                setLayout((screenWidth*.96f).roundToInt(),WindowManager.LayoutParams.WRAP_CONTENT)
            } else if(purpose==ConsolePanelPurpose.GUIDE) {
                val side=if(skin.scene==ConsoleScene.PS4) SeriesGuideSide.LEFT else SeriesPersonalizationStore(context).read().guide
                setGravity(Gravity.CENTER_VERTICAL or if(side==SeriesGuideSide.RIGHT) Gravity.RIGHT else Gravity.LEFT)
                attributes=attributes.apply { x=px(12) }
                setLayout(minOf(px(410),(screenWidth*.90f).roundToInt()),(screenHeight*.90f).roundToInt())
            } else if(purpose==ConsolePanelPurpose.PROFILE && skin.scene==ConsoleScene.PS5) {
                setGravity(Gravity.CENTER_VERTICAL or Gravity.RIGHT)
                attributes=attributes.apply { x=px(40) }
                setLayout(minOf(px(440),(screenWidth*.90f).roundToInt()),WindowManager.LayoutParams.WRAP_CONTENT)
            } else if(fullScreen) {
                setGravity(Gravity.CENTER)
                setDimAmount(0f)
                setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT)
            } else {
                setGravity(Gravity.CENTER)
                setLayout((screenWidth*(if(skin.enabled) .90f else .82f)).roundToInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            com.xcloud.tclnative.ui.ImmersiveWindow.apply(this)
        }
        val target=if(explicitFocus) initialFocus else returningFocus ?: initialFocus
        target?.requestFocus()
        target?.post { if(isShowing) target.requestRectangleOnScreen(android.graphics.Rect(0,0,target.width,target.height)) }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus) window?.let(com.xcloud.tclnative.ui.ImmersiveWindow::apply)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        com.xcloud.tclnative.webrtc.AndroidControllerMonitor.key(event)
        if(AppControllerWindows.suppressed(activity,event.deviceId)) return true
        return super.dispatchKeyEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        com.xcloud.tclnative.webrtc.AndroidControllerMonitor.motion(event)
        if(AppControllerWindows.suppressed(activity,event.deviceId)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}

object ConsoleDialogs {
    fun search(context: Context, query: String, filter: ConsoleAction?=null, onSearch: (String) -> Unit): ConsoleDialog = searchField(context,query,
        UiText.text("Pesquisar jogos"),UiText.text("Nome do jogo"),UiText.text("Encontre um jogo pelo nome."),onSearch,filter)
    fun searchWallpapers(context: Context,query: String,onSearch: (String) -> Unit): ConsoleDialog = searchField(context,query,
        UiText.text("Pesquisar fundos"),UiText.text("Nome do fundo"),UiText.text("Encontre um fundo pelo nome."),onSearch)
    private fun searchField(context: Context,query: String,title: String,placeholder: String,help: String,onSearch: (String) -> Unit,filter: ConsoleAction?=null): ConsoleDialog {
        val dialog = ConsoleDialog(context,title,ConsolePanelPurpose.SEARCH)
        val localKeyboard=AppControllerWindows.usbActive(context as? Activity)
        val skin=ConsolePanelSkin()
        val field = EditText(dialog.context).apply {
            setText(query); hint=placeholder; isSingleLine=true
            typeface=if(skin.enabled) skin.font() else DashboardTheme.font(context, false)
            setTextColor(if(skin.enabled) skin.ink else Color.WHITE); setHintTextColor(if(skin.enabled) skin.muted else DashboardTheme.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 28*dialog.scale)
            setPadding(dialog.px(20), dialog.px(12), dialog.px(20), dialog.px(12))
            highlightColor=DashboardTheme.color(UiColor.EDIT_SELECTION)
            DashboardTheme.customFocus(this)
            fun outline(color: UiColor)=DashboardTheme.shape(UiColor.EDIT_FILL,8*dialog.scale,dialog.px(2),color)
            background=StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), outline(UiColor.ACCENT))
                addState(intArrayOf(), outline(UiColor.BORDER))
            }
            if(skin.enabled) background=skin.input(dialog.scale)
            imeOptions=EditorInfo.IME_ACTION_SEARCH
            if(localKeyboard) showSoftInputOnFocus=false
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { dialog.dismiss(); onSearch(text.toString()); true } else false
            }
            selectAll()
        }
        dialog.body.addView(dialog.text(help, 21, color=DashboardTheme.muted), LinearLayout.LayoutParams(-1,-2)
            .apply { bottomMargin=dialog.px(17) })
        if(filter!=null) dialog.addMenuItem(filter.label) { onSearch(field.text.toString());filter.action() }
        dialog.body.addView(field, LinearLayout.LayoutParams(-1,dialog.px(65)))
        val keyboard=if(localKeyboard) ControllerSearchKeyboard.attach(dialog,field) else null
        dialog.addAction(UiText.text("Limpar busca")) { onSearch("") }
        dialog.addAction(UiText.text("Voltar")) {}
        dialog.addAction(UiText.text("Buscar")) { onSearch(field.text.toString()) }
        dialog.shortcut={ event ->
            if(event.keyCode==KeyEvent.KEYCODE_BUTTON_X) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount>0) { field.text.clear();field.requestFocus() }
                true
            } else false
        }
        dialog.focusFirst(keyboard ?: field)
        dialog.show()
        if(!localKeyboard && context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)) {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            field.post {
                if(dialog.isShowing) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(field,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        return dialog
    }
    fun menu(context: Context, title: String, items: List<ConsoleAction>, onBack: () -> Unit = {}): ConsoleDialog = ConsoleDialog(context,title,
        if(title==UiText.text("Configurações")) ConsolePanelPurpose.SETTINGS_HOME else ConsolePanelPurpose.GENERAL).apply {
        items.forEach { addMenuItem(it.label, it.action) }
        addAction(UiText.text("Voltar"),onBack)
        setOnCancelListener { onBack() }
        show()
    }
    fun message(context: Context, title: String, message: String, actions: List<ConsoleAction> = listOf(ConsoleAction(UiText.text("Fechar")) {}),
                onCancel: (() -> Unit)? = null): ConsoleDialog = ConsoleDialog(context,title).apply {
        body.addView(text(message,22), LinearLayout.LayoutParams(-1,-2))
        actions.forEach { addAction(it.label,it.action) }
        if (onCancel != null) setOnCancelListener { onCancel() }
        show()
    }
}
