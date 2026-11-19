package com.xcloud.tclnative.ui

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.xcloud.tclnative.settings.TouchMode
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.touch.*
import com.xcloud.tclnative.ui.dashboard.DashboardTheme
import com.xcloud.tclnative.webrtc.*

/** Native HUD rendered over the stream. Its virtual controls use the cloud gamepad input channel. */
class GameTouchView(context: Context,
    private val onGamepad: (GamepadState) -> Unit,
    private val onPointers: (List<NativePointer>) -> Unit,
    private val coordinates: (Float,Float) -> Pair<Float,Float>?,
    private val onMenu: () -> Unit
) : FrameLayout(context), com.xcloud.tclnative.i18n.LocaleAwareView {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=DashboardTheme.font(context,true); textAlign=Paint.Align.CENTER }
    private val skin=HudSkin()
    private var automatic=HudPreset.GAMEPAD
    var configuration=HudSettings()
        private set
    private var model=VirtualGamepad(1,1)
    val preset: HudPreset get()=model.preset
    private var mode=TouchMode.OFF
    private var autoHidden=false
    private var wakingGesture=false
    private var overlayFocused=false
    private val virtualPointers=mutableSetOf<Int>()
    val controlsVisible: Boolean get()=mode==TouchMode.VIRTUAL && !autoHidden
    private val autoHideTask=Runnable { if(canAutoHide() && configuration.autoHide.idle) hideAutomatically() }
    var immediateNativeDispatch=true
    private val native=mutableMapOf<Int,NativePointer>()
    private var nativeEvents=NativeEvents()
    private class NativeEvents {
        var down=0L;var up=0L;var moves=0L;var cancel=0L;var menu=0L;var outside=0L;var ignored=0L
        var releases=0L;var generatedDown=0L;var generatedUp=0L
        var immediate=0L;var refused=0L
        var samples=0L;var totalAge=0.0;var maxAge=0L;var hudUpdates=0L;var staleHudContacts=0L
    }
    /** Reset after the previous report was frozen, not when opening a menu or hiding the view. */
    fun beginSession() { releaseInputs();nativeEvents=NativeEvents();lastHudPress=UiText.text("nenhum") }
    private var lastHudPress=UiText.text("nenhum")
    fun nativeDiagnostics(): String {
        val s=nativeEvents
        val age=if(s.samples==0L) "n/d" else java.lang.String.format(java.util.Locale.US,"%.2f/%d ms",s.totalAge/s.samples,s.maxAge)
        return UiText.join(UiText.text("Toque Android → app: idade média/pico {0}; down/up/move/cancel {1}/{2}/{3}/{4}; ",UiText.nested(age),UiText.nested(s.down),UiText.nested(s.up),UiText.nested(s.moves),UiText.nested(s.cancel)),"gerados D/U ${s.generatedDown}/${s.generatedUp}; solturas pelo app ${s.releases}; ",UiText.text("inícios fora do vídeo {0}; contatos ignorados {1}; menu {2}; ",UiText.nested(s.outside),UiText.nested(s.ignored),UiText.nested(s.menu)),UiText.text("despacho imediato por gesto: pedidos {0}, recusados {1}",UiText.nested(s.immediate),UiText.nested(s.refused)))
    }
    fun hudDiagnostics(): String = UiText.join("HUD: ${preset.label}; ",(if(preset.football) UiText.text("esquema {0}; ",UiText.nested(configuration.footballScheme.label)) else ""),UiText.text("sensibilidade {0}%; zona morta {1}%; ",UiText.nested(configuration.sensitivity),UiText.nested(configuration.lookDeadzone)),(if(preset.family==HudPreset.CALL_OF_DUTY) UiText.text("mirar ao atirar {0}; ",UiText.nested(if(configuration.aimAndFire) UiText.text("ativado") else UiText.text("desativado")))+
            UiText.text("corrida por arrasto {0}; ",UiText.nested(if(configuration.sprintGesture) "ativada" else "desativada")) else ""),UiText.text("atualizações {0}; contatos interrompidos descartados {1}; último controle: {2}\n{3}",UiText.nested(nativeEvents.hudUpdates),UiText.nested(nativeEvents.staleHudContacts),UiText.nested(lastHudPress),UiText.nested(nativeDiagnostics())))
    private fun emitNative(events: List<NativePointer>) {
        nativeEvents.generatedDown+=events.count { it.type==1 };nativeEvents.generatedUp+=events.count { it.type==2 }
        onPointers(events)
    }
    private var menuPointer: Int?=null
    var isEditing=false
        private set
    private var beforeEdit=configuration
    private var beforeEditPreset=HudPreset.GAMEPAD
    private var selected: String?=null
    private var editPointer: Int?=null
    private var dragX=0f; private var dragY=0f
    private var editAction: String?=null
    var onHudSaved: (HudSettings) -> Boolean = { true }
    var onBindingRequested: (String,HudAction?) -> Unit = { _,_ -> }
    var onGesturesRequested: (String,Map<HudSwipe,HudGesture>) -> Unit = { _,_ -> }
    private val lookReset=Runnable { if(mode==TouchMode.VIRTUAL && !isEditing) publish() }
    private var menuButtonFocused=false
    private val menuButton=TextView(context).apply {
        setPadding(0,0,0,0)
        contentDescription=UiText.text("Atalhos da partida")
        background=object: android.graphics.drawable.Drawable() {
            private val brush=Paint(Paint.ANTI_ALIAS_FLAG)
            private val arrow=Path()
            override fun draw(canvas: Canvas) {
                val x=bounds.exactCenterX()
                val y=bounds.exactCenterY()
                val radius=minOf(this@GameTouchView.height*.031f,bounds.width()*.38f)
                brush.style=Paint.Style.FILL;brush.color=0x99101a13.toInt()
                canvas.drawCircle(x,y,radius,brush)
                brush.style=Paint.Style.STROKE;brush.strokeWidth=maxOf(1f,this@GameTouchView.height*.002f)
                brush.color=if(menuButtonFocused) Color.WHITE else 0xaa54de69.toInt()
                canvas.drawCircle(x,y,radius,brush)
                brush.color=Color.WHITE;brush.strokeWidth=maxOf(1.5f,radius*.13f)
                brush.strokeCap=Paint.Cap.ROUND;brush.strokeJoin=Paint.Join.ROUND
                arrow.rewind()
                arrow.moveTo(x-radius*.16f,y-radius*.35f)
                arrow.lineTo(x+radius*.22f,y)
                arrow.lineTo(x-radius*.16f,y+radius*.35f)
                canvas.drawPath(arrow,brush)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(filter: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
        }
        setOnFocusChangeListener { view,focused -> menuButtonFocused=focused;view.invalidate() }
        isClickable=true;isFocusable=true
        setOnClickListener { if(mode==TouchMode.NATIVE) nativeEvents.menu++;releaseInputs();onMenu() }
    }
    init {
        visibility=GONE;isFocusable=false;setWillNotDraw(false)
        val hitSize=kotlin.math.ceil(48*resources.displayMetrics.density).toInt()
        val margin=(4*resources.displayMetrics.density).toInt()
        addView(menuButton,LayoutParams(hitSize,hitSize,Gravity.TOP or Gravity.LEFT).apply {
            leftMargin=margin;topMargin=margin
        })
        ImmersiveWindow.observeCutout(this) { cutout ->
            val params=menuButton.layoutParams as LayoutParams
            val left=margin+cutout.left
            val top=margin+cutout.top
            if(params.leftMargin!=left || params.topMargin!=top) {
                params.leftMargin=left;params.topMargin=top;menuButton.layoutParams=params
            }
        }
    }
    fun configure(mode: TouchMode,game: GameTitle?=null,settings: HudSettings?=null) {
        releaseInputs(); isEditing=false;menuButton.visibility=VISIBLE
        autoHidden=false
        if(game!=null) automatic=GameHud.automatic(game.titleId,game.name)
        if(settings!=null) configuration=settings
        this.mode=mode
        rebuild(configuration.preset ?: automatic)
        val menuOnly=game!=null && com.xcloud.tclnative.settings.InputDeviceProfile.read(context).touchDevice
        visibility=if(mode==TouchMode.OFF && !menuOnly) GONE else VISIBLE
        scheduleAutoHide()
        invalidate()
    }
    fun applyHud(settings: HudSettings) {
        val oldPage=preset
        releaseInputs(); configuration=settings
        val resolved=settings.preset ?: automatic
        rebuild(if(resolved.family==oldPage.family) oldPage else resolved); invalidate()
        revealControls()
    }
    fun showPage(page: HudPreset) { releaseInputs(); model.setPage(page);scheduleAutoHide(); invalidate() }
    private fun rebuild(page: HudPreset=preset) { model=VirtualGamepad(width.coerceAtLeast(1),height.coerceAtLeast(1),page,configuration) }
    override fun refreshLanguage() { menuButton.contentDescription=UiText.text("Atalhos da partida");releaseInputs();rebuild();scheduleAutoHide();invalidate() }
    fun beginEditing() {
        if(mode!=TouchMode.VIRTUAL) return
        releaseInputs();autoHidden=false; beforeEdit=configuration; beforeEditPreset=preset; selected=null; isEditing=true;menuButton.visibility=GONE; invalidate()
    }
    fun cancelEditing() {
        if(!isEditing) return
        releaseInputs(); configuration=beforeEdit; rebuild(beforeEditPreset); isEditing=false;menuButton.visibility=VISIBLE;scheduleAutoHide(); invalidate()
    }
    fun setEditedBinding(id: String,binding: HudBinding) {
        if(!isEditing) return
        val control=model.controls.firstOrNull { it.id==id } ?: return
        placement(control) { it.copy(binding=binding,action=null) }
    }
    fun setEditedAction(id: String,action: HudAction) {
        if(!isEditing) return
        val control=model.controls.firstOrNull { it.id==id } ?: return
        placement(control) { it.copy(binding=null,action=action) }
    }
    fun setEditedGestures(id: String,gestures: Map<HudSwipe,HudGesture>) {
        if(!isEditing) return
        val control=model.controls.firstOrNull { it.id==id } ?: return
        placement(control) { it.copy(gestures=gestures) }
    }
    private fun placement(control: TouchControl,change: (HudPlacement) -> HudPlacement) {
        val old=configuration.placements[control.id] ?: HudPlacement(control.x/width,control.y/height)
        configuration=configuration.copy(placements=configuration.placements+(control.id to change(old)))
        rebuild(); invalidate()
    }
    fun releaseInputs() {
        removeCallbacks(lookReset)
        removeCallbacks(autoHideTask);virtualPointers.clear();wakingGesture=false
        model.clear(); onGamepad(GamepadState())
        if(native.isNotEmpty()) { nativeEvents.releases++;emitNative(native.values.map { it.copy(type=2,pressure=0f) }) }
        native.clear(); menuPointer=null; editPointer=null; editAction=null; invalidate()
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        val page=preset
        releaseInputs(); rebuild(page);scheduleAutoHide()
        menuButton.invalidate()
    }
    override fun onDetachedFromWindow() { releaseInputs(); super.onDetachedFromWindow() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus);overlayFocused=hasWindowFocus
        if(hasWindowFocus) scheduleAutoHide() else removeCallbacks(autoHideTask)
    }
    private fun canAutoHide()=mode==TouchMode.VIRTUAL && !isEditing && !autoHidden && !wakingGesture &&
        overlayFocused && visibility==VISIBLE && virtualPointers.isEmpty() && menuPointer==null
    private fun scheduleAutoHide() {
        removeCallbacks(autoHideTask)
        if(canAutoHide() && configuration.autoHide.idle) postDelayed(autoHideTask,10_000)
    }
    private fun hideAutomatically() {
        if(!canAutoHide()) return
        releaseInputs();autoHidden=true;invalidate()
    }
    fun revealControls() { autoHidden=false;wakingGesture=false;scheduleAutoHide();invalidate() }
    fun physicalInput(state: GamepadState) {
        val active=state.buttons!=0 || state.leftTrigger>.1f || state.rightTrigger>.1f ||
            listOf(state.leftX,state.leftY,state.rightX,state.rightY).any { kotlin.math.abs(it)>.15f }
        if(active && configuration.autoHide.physical) hideAutomatically()
    }
    private fun menuHit(x: Float,y: Float)=x>=menuButton.left && x<menuButton.right && y>=menuButton.top && y<menuButton.bottom
    private fun publish() {
        val now=SystemClock.uptimeMillis()
        nativeEvents.hudUpdates++;onGamepad(model.state(now)); removeCallbacks(lookReset)
        model.nextUpdate(now)?.let { postDelayed(lookReset,(it-now).coerceAtLeast(1)) }
        postInvalidateOnAnimation()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(isEditing) return editTouch(event)
        val receivedAt=SystemClock.uptimeMillis()
        if(mode==TouchMode.NATIVE || mode==TouchMode.VIRTUAL) {
            val s=nativeEvents
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> s.down++
                MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> s.up++
                MotionEvent.ACTION_MOVE -> s.moves++
                MotionEvent.ACTION_CANCEL -> s.cancel++
            }
            val age=receivedAt-event.eventTime
            if(age>=0) { s.samples++;s.totalAge+=age;s.maxAge=maxOf(s.maxAge,age) }
        }
        if(mode==TouchMode.NATIVE) {
            val s=nativeEvents
            if(event.actionMasked==MotionEvent.ACTION_DOWN) {
                // A fresh Android gesture must not reuse a contact left by an interrupted one.
                if(native.isNotEmpty()) releaseInputs()
                parent?.requestDisallowInterceptTouchEvent(true)
                if(immediateNativeDispatch) {
                    if(runCatching { requestUnbufferedDispatch(event) }.isSuccess) s.immediate++ else s.refused++
                }
            }
        }
        if(mode==TouchMode.VIRTUAL && event.actionMasked==MotionEvent.ACTION_DOWN) {
            nativeEvents.staleHudContacts+=model.startGesture()
            parent?.requestDisallowInterceptTouchEvent(true)
            if(runCatching { requestUnbufferedDispatch(event) }.isSuccess) nativeEvents.immediate++ else nativeEvents.refused++
        }
        val action=event.actionMasked
        val index=event.actionIndex
        val id=event.getPointerId(index)
        if((action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) && menuPointer==null && menuHit(event.getX(index),event.getY(index))) {
            if(mode==TouchMode.NATIVE) nativeEvents.menu++
            menuPointer=id; return true
        }
        if(action==MotionEvent.ACTION_CANCEL) { releaseInputs();scheduleAutoHide(); return true }
        if((action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP) && id==menuPointer) {
            menuPointer=null
            if(menuHit(event.getX(index),event.getY(index))) { releaseInputs(); performClick() }
            return true
        }
        if(mode==TouchMode.OFF) return false
        if(mode==TouchMode.VIRTUAL && (autoHidden || wakingGesture)) {
            if(menuPointer!=null) return true
            if(action==MotionEvent.ACTION_DOWN) { autoHidden=false;wakingGesture=true;removeCallbacks(autoHideTask);invalidate() }
            if(action==MotionEvent.ACTION_UP) { wakingGesture=false;scheduleAutoHide() }
            return true // The revealing gesture never presses a game control.
        }
        if(mode==TouchMode.VIRTUAL) {
            when(action) {
                MotionEvent.ACTION_DOWN -> { virtualPointers.clear();virtualPointers.add(id);removeCallbacks(autoHideTask) }
                MotionEvent.ACTION_POINTER_DOWN -> { virtualPointers.add(id);removeCallbacks(autoHideTask) }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> { virtualPointers.remove(id);scheduleAutoHide() }
            }
        }
        if(mode==TouchMode.NATIVE) {
            val events=mutableListOf<NativePointer>()
            fun pointer(i: Int,type: Int) {
                val pointerId=event.getPointerId(i)
                if(pointerId==menuPointer) return
                val position=coordinates(event.getX(i),event.getY(i))
                val old=native[pointerId]
                if(type==1 && (native.size>=10 || position==null)) {
                    if(position==null) nativeEvents.outside++ else nativeEvents.ignored++
                    return
                }
                if(type!=1 && old==null) { nativeEvents.ignored++;return }
                val (x,y)=position ?: old?.let { it.x to it.y } ?: return
                val pressure=event.getPressure(i).let { if(it.isFinite() && it>0) it.coerceAtMost(1f) else 1f }
                val diameter=event.getTouchMajor(i).takeIf { it.isFinite() && it>0 } ?: 1f
                // Android ids start at zero; DOM PointerEvents use stable positive contact ids.
                val p=NativePointer(pointerId+1,x,y,type,if(type==2) 0f else pressure,
                    diameter/width.coerceAtLeast(1),diameter/height.coerceAtLeast(1))
                if(type==2) native.remove(pointerId) else native[pointerId]=p
                events.add(p)
            }
            when(action) {
                MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> pointer(index,1)
                MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> pointer(index,2)
                MotionEvent.ACTION_MOVE -> repeat(event.pointerCount) { pointer(it,3) }
            }
            if(events.isNotEmpty()) emitNative(events)
        } else {
            when(action) {
                MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> {
                    model.down(id,event.getX(index),event.getY(index),event.eventTime,receivedAt)?.let {
                        lastHudPress=UiText.text("{0} [{1}] → {2}",UiText.nested(it.label),UiText.nested(it.id),UiText.nested(it.action?.label ?: it.kind.name))
                    }
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> {
                    model.move(id,event.getX(index),event.getY(index),event.eventTime,receivedAt)
                    model.up(id,event.eventTime,receivedAt)
                }
                MotionEvent.ACTION_MOVE -> {
                    // Estimate current velocity from the recent samples of a batched event.
                    // Publish only its final state, rather than replaying old motion reports.
                    if(preset.shooter) for(history in maxOf(0,event.historySize-2) until event.historySize) {
                        repeat(event.pointerCount) { i ->
                            if(event.getPointerId(i)!=menuPointer) model.move(event.getPointerId(i),event.getHistoricalX(i,history),
                                event.getHistoricalY(i,history),event.getHistoricalEventTime(history),receivedAt)
                        }
                    }
                    repeat(event.pointerCount) { i ->
                        if(event.getPointerId(i)!=menuPointer) model.move(event.getPointerId(i),event.getX(i),event.getY(i),event.eventTime,receivedAt)
                    }
                }
            }
            publish()
        }
        return true
    }
    private fun editorAction(x: Float,y: Float): String? = when {
        y in height*.035f..height*.105f && x in width*.31f..width*.44f -> "cancel"
        y in height*.035f..height*.105f && x in width*.44f..width*.57f -> "reset"
        y in height*.035f..height*.105f && x in width*.57f..width*.70f -> "save"
        y in height*.90f..height*.985f && selected!=null && x in width*.33f..width*.43f -> "smaller"
        y in height*.90f..height*.985f && selected!=null && x in width*.43f..width*.53f -> "larger"
        y in height*.90f..height*.985f && selected!=null && x in width*.53f..width*.69f -> "binding"
        y in height*.90f..height*.985f && selected!=null && x in width*.69f..width*.82f -> "gestures"
        else -> null
    }
    private fun editTouch(event: MotionEvent): Boolean {
        val action=event.actionMasked
        val index=event.actionIndex
        val id=event.getPointerId(index)
        val x=event.getX(index); val y=event.getY(index)
        when(action) {
            MotionEvent.ACTION_DOWN -> {
                editPointer=id; editAction=editorAction(x,y)
                if(editAction==null) {
                    val control=model.hit(x,y)?.takeIf { it.kind!=TouchKind.CAMERA }
                    selected=control?.id
                    if(control!=null) { dragX=x-control.x; dragY=y-control.y }
                }
            }
            MotionEvent.ACTION_MOVE -> if(editPointer!=null && editAction==null) {
                val i=event.findPointerIndex(editPointer!!)
                val control=model.controls.firstOrNull { it.id==selected }
                if(i>=0 && control!=null) placement(control) {
                    it.copy(x=((event.getX(i)-dragX)/width).coerceIn(.025f,.975f),
                        y=((event.getY(i)-dragY)/height).coerceIn(.14f,.88f))
                }
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> if(id==editPointer) {
                val command=editAction?.takeIf { it==editorAction(x,y) }
                editPointer=null; editAction=null
                when(command) {
                    "cancel" -> cancelEditing()
                    "reset" -> { configuration=HudSettings(preset=configuration.preset,footballScheme=configuration.footballScheme); rebuild(); selected=null }
                    "save" -> if(onHudSaved(configuration)) { releaseInputs(); isEditing=false;menuButton.visibility=VISIBLE;scheduleAutoHide() }
                    "smaller","larger" -> model.controls.firstOrNull { it.id==selected }?.let { control ->
                        placement(control) { it.copy(scale=(it.scale+if(command=="larger") .10f else -.10f).coerceIn(.55f,1.7f)) }
                    }
                    "binding" -> model.controls.firstOrNull { it.id==selected && it.action!=null }?.let { onBindingRequested(it.id,it.action) }
                    "gestures" -> model.controls.firstOrNull { it.id==selected && it.action!=null }?.let { onGesturesRequested(it.id,it.gestures) }
                }
            }
            MotionEvent.ACTION_CANCEL -> { editPointer=null; editAction=null }
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); onMenu(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state=model.state(SystemClock.uptimeMillis())
        if(mode==TouchMode.VIRTUAL && !autoHidden) {
            for(control in model.controls) if(control.kind!=TouchKind.CAMERA) drawControl(canvas,control,state)
            if(isEditing) drawEditor(canvas)
        }
    }

    private fun drawControl(canvas: Canvas,control: TouchControl,state: GamepadState) {
        skin.draw(canvas,control,preset,state,model.held(control),isEditing && selected==control.id,
            configuration.opacity,isEditing,model.gestureLabel(control))
    }
    private fun drawEditor(canvas: Canvas) {
        fun button(label: String,left: Float,right: Float,top: Float,bottom: Float,accent: Boolean=false) {
            paint.style=Paint.Style.FILL; paint.color=if(accent) 0xee268943.toInt() else 0xee17261d.toInt()
            canvas.drawRoundRect(width*left,height*top,width*right,height*bottom,height*.012f,height*.012f,paint)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=height*.002f; paint.color=DashboardTheme.accent
            canvas.drawRoundRect(width*left,height*top,width*right,height*bottom,height*.012f,height*.012f,paint)
            paint.style=Paint.Style.FILL; paint.color=Color.WHITE; paint.textSize=height*.027f
            val available=width*(right-left)*.88f
            if(paint.measureText(label)>available) paint.textSize*=available/paint.measureText(label)
            canvas.drawText(label,width*(left+right)/2,height*(top+bottom)/2-(paint.ascent()+paint.descent())/2,paint)
        }
        paint.style=Paint.Style.FILL; paint.color=Color.WHITE; paint.textSize=height*.021f
        canvas.drawText(UiText.text("EDITAR HUD • ARRASTE OS BOTÕES"),width*.505f,height*.025f,paint)
        button(UiText.text("Cancelar"),.31f,.44f,.035f,.105f)
        button(UiText.text("Restaurar"),.44f,.57f,.035f,.105f)
        button(UiText.text("Salvar"),.57f,.70f,.035f,.105f,true)
        selected?.let { id -> model.controls.firstOrNull { it.id==id }?.let { control ->
            button("−",.33f,.43f,.90f,.985f); button("+",.43f,.53f,.90f,.985f)
            if(control.action!=null) {
                button(UiText.text("Ação: {0}",UiText.nested(control.action.label)),.53f,.69f,.90f,.985f)
                button(UiText.text("Gestos"),.69f,.82f,.90f,.985f)
            }
        } }
    }
}
