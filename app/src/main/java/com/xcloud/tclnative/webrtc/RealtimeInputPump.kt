package com.xcloud.tclnative.webrtc

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** Preserve short button presses while coalescing analog motion into its latest position. */
class GamepadInputBuffer {
    private var latest = GamepadState()
    private data class Edge(val buttons: Int,var leftTrigger: Float,var rightTrigger: Float)
    private val edges = ArrayDeque<Edge>()
    private var motionPending=false
    var coalescedEdges = 0L
        private set
    @Synchronized fun update(state: GamepadState): Boolean {
        val edge = state.buttons != latest.buttons || (state.leftTrigger>0)!=(latest.leftTrigger>0) || (state.rightTrigger>0)!=(latest.rightTrigger>0)
        val motion=state.leftX!=latest.leftX || state.leftY!=latest.leftY || state.rightX!=latest.rightX || state.rightY!=latest.rightY || state.leftTrigger!=latest.leftTrigger || state.rightTrigger!=latest.rightTrigger
        motionPending=motionPending || motion
        // Strength changes within the same held press are analog motion, not a replay queue.
        if(state.leftTrigger>0 && latest.leftTrigger>0) {
            val pending=edges.descendingIterator()
            while(pending.hasNext()) { val e=pending.next(); if(e.leftTrigger==0f) break; e.leftTrigger=state.leftTrigger }
        }
        if(state.rightTrigger>0 && latest.rightTrigger>0) {
            val pending=edges.descendingIterator()
            while(pending.hasNext()) { val e=pending.next(); if(e.rightTrigger==0f) break; e.rightTrigger=state.rightTrigger }
        }
        latest = state
        if (edge) {
            if (edges.size >= 8) { coalescedEdges += edges.size; edges.clear() }
            edges.addLast(Edge(state.buttons,state.leftTrigger,state.rightTrigger))
        }
        return edge || motion
    }
    @Synchronized fun hasEdges() = edges.isNotEmpty()
    @Synchronized fun hasUrgent() = edges.isNotEmpty() || motionPending
    @Synchronized fun next(): GamepadState {
        motionPending=false
        val edge=edges.pollFirst() ?: return latest
        return latest.copy(buttons=edge.buttons,leftTrigger=edge.leftTrigger,rightTrigger=edge.rightTrigger)
    }
    @Synchronized fun discardBacklog() {
        coalescedEdges += edges.size
        edges.clear()
        motionPending=false
    }
}

data class InputMetrics(val bufferedBytes: Long = 0, val maxSchedulerDelayMs: Double = 0.0,
                        val backpressureSkips: Long = 0, val coalescedEdges: Long = 0, val sentState: GamepadState = GamepadState(),
                        val pointerEventsSent: Long=0,val sentBytes: Long=0,val idleReportsSent: Long=0,val initialStateReportsSent: Long=0,
                        val xboxPresses: Long=0,val xboxReleases: Long=0)

/** Native sends run independently from decoder/default-coroutine CPU load. Never catch up by replaying old ticks. */
class RealtimeInputPump(
    private val bufferedAmount: () -> Long,
    private val send: (ByteArray) -> Boolean,
    private val onFailure: () -> Unit,
    private val prioritize: () -> Unit = {},
    initialGamepads: Set<Int> = setOf(0),
    private val touch: NativeTouchBuffer = NativeTouchBuffer(),
    private val inputHz: Int = INPUT_HZ,
    initialTouchDimensions: TouchInputDimensions? = null,
    private val desktop: DesktopInputBuffer = DesktopInputBuffer(),
    private val idleReports: Boolean = false,
    private val initializeTouchSlot: Boolean = false
) {
    init { require(inputHz in 60..INPUT_HZ) }
    private val running = AtomicBoolean(false)
    private val buffers = Array(4) { GamepadInputBuffer() }
    private val active = initialGamepads.toMutableSet()
    private val revisions = LongArray(4)
    private var officialTouchSlots = 0
    private val retireAfterNeutral = mutableMapOf<Int,Long>()
    private val lock = Any()
    @Volatile private var touchDimensions = initialTouchDimensions
    @Volatile private var thread: Thread? = null
    @Volatile var metrics = InputMetrics()
        private set
    fun update(state: GamepadState, index: Int = 0, officialTouch: Boolean = false) {
        require(index in 0..3)
        synchronized(lock) {
            val bit = 1 shl index
            officialTouchSlots = if (officialTouch) officialTouchSlots or bit else officialTouchSlots and bit.inv()
            revisions[index]++;retireAfterNeutral.remove(index)
            active.add(index); if (buffers[index].update(state)) thread?.let(LockSupport::unpark)
        }
    }
    fun desktopKey(key: NativeKey) { desktop.key(key);thread?.let(LockSupport::unpark) }
    fun desktopMouse(mouse: NativeMouse) { desktop.mouse(mouse);thread?.let(LockSupport::unpark) }
    fun releaseDesktop() { desktop.release();thread?.let(LockSupport::unpark) }
    fun resetGamepads() = synchronized(lock) {
        val neutral = GamepadState()
        for (index in active) {
            buffers[index].update(neutral)
            buffers[index].discardBacklog()
        }
        thread?.let(LockSupport::unpark)
    }
    fun remove(index: Int) { synchronized(lock) { active.remove(index);retireAfterNeutral.remove(index);revisions[index]++; buffers[index].update(GamepadState()); buffers[index].discardBacklog() } }
    /** Auxiliary aim must release its stick before becoming idle. The touch slot stays connected. */
    fun releaseAfterNeutral(index: Int=0, preserveButtonEdges: Boolean = false) {
        require(index in 0..3)
        synchronized(lock) {
            if(index !in active || index in retireAfterNeutral) return
            buffers[index].update(GamepadState())
            if (!preserveButtonEdges) buffers[index].discardBacklog()
            retireAfterNeutral[index]=++revisions[index]
            thread?.let(LockSupport::unpark)
        }
    }
    fun pointers(events: List<NativePointer>) { touch.add(events); thread?.let(LockSupport::unpark) }
    fun setTouchDimensions(dimensions: TouchInputDimensions) {
        touchDimensions = dimensions
        thread?.let(LockSupport::unpark)
    }
    fun releaseTouch() { touch.release(); thread?.let(LockSupport::unpark) }
    fun start() {
        check(running.compareAndSet(false, true))
        thread = Thread({
            try {
                prioritize()
                val period = 1_000_000_000L / inputHz
                val started = System.nanoTime()
                var lastReportAt=started
                var initialStatePending=initializeTouchSlot
                var deadline = started
                var sequence = 1 // Client metadata has already used sequence 0.
                var skipped = 0L
                var maxDelay = 0.0
                var windowStarted = started
                var backpressured = false
                val states = HashMap<Int, GamepadState>(4)
                val retirementRevisions = LongArray(4)
                val neutral = GamepadState()
                var sentState = neutral
                var pointerEventsSent = 0L
                var sentBytes = 0L
                var idleReportsSent = 0L
                var initialStateReportsSent = 0L
                var xboxPresses = 0L
                var xboxReleases = 0L
                var xboxHeld = 0
                while (running.get()) {
                    val now = System.nanoTime()
                    val dimensions = touchDimensions
                    val hasEdges=synchronized(lock) { retireAfterNeutral.isNotEmpty() || active.any { buffers[it].hasUrgent() } }
                    if (now < deadline && (backpressured || (!hasEdges && !desktop.pending() && (dimensions == null || !touch.pending())))) {
                        LockSupport.parkNanos(deadline - now)
                        continue
                    }
                    maxDelay = maxOf(maxDelay, (now - deadline).coerceAtLeast(0) / 1_000_000.0)
                    val queued = bufferedAmount()
                    val gamepadBytes=synchronized(lock) { if(active.isEmpty()) 0 else 1+23*active.size }
                    val contactCount=if(dimensions == null) 0 else touch.nextCount()
                    val maximum=maxOf(MAX_BUFFERED_BYTES,2L*(14+gamepadBytes+(if(contactCount==0) 0 else 2+20*contactCount)))
                    if (queued >= maximum) {
                        backpressured=true
                        skipped++
                        synchronized(lock) { active.forEach { buffers[it].discardBacklog() } }
                    } else {
                        backpressured=false
                        var retiringMask = 0
                        val touchSlots = synchronized(lock) {
                            for (index in buffers.indices) {
                                if (index in active) states[index] = buffers[index].next() else states.remove(index)
                                val revision = retireAfterNeutral[index] ?: continue
                                if (states[index] != neutral || buffers[index].hasEdges()) continue
                                retirementRevisions[index] = revision
                                retiringMask = retiringMask or (1 shl index)
                            }
                            officialTouchSlots
                        }
                        // Coordinates have no meaning until ServerMetadata establishes their scale.
                        val pointers=if(dimensions == null) emptyList() else touch.next()
                        val desktopFrames=desktop.next()
                        val hasInput=states.isNotEmpty() || pointers.isNotEmpty() || desktopFrames.first.isNotEmpty() || desktopFrames.second.isNotEmpty()
                        // Native touch can start with no physical pads and no finger contacts.
                        // Experimental idle transport report; None never represents a user action.
                        // Compatibility experiment: initialize the already registered touch slot once.
                        // No held control or synthetic finger, and no extra state after real input starts.
                        val initial=initialStatePending && dimensions!=null && !hasInput
                        val idle=idleReports && !hasInput && !initial && now-lastReportAt>=IDLE_REPORT_NS
                        val packet=if(initial) GamepadPacket.neutralTouchSlot(sequence++,(now-started)/1_000_000.0)
                            else if(hasInput || idle) GamepadPacket.encode(states, sequence++,
                            (now - started) / 1_000_000.0,pointers,dimensions?.width ?: 1,dimensions?.height ?: 1,
                            desktopFrames.first,desktopFrames.second,touchSlots) else null
                        if (packet!=null && !send(packet)) {
                            if (running.get()) onFailure()
                            break
                        }
                        if(packet!=null) {
                            lastReportAt=now
                            if(initial || hasInput) initialStatePending=false
                            var nextXboxHeld = 0
                            for ((index, state) in states) {
                                val slot = 1 shl index
                                val pressed = state.buttons and ControllerRouter.XBOX_BUTTON != 0
                                val wasPressed = xboxHeld and slot != 0
                                if (pressed && !wasPressed) xboxPresses++
                                if (!pressed && wasPressed) xboxReleases++
                                if (pressed) nextXboxHeld = nextXboxHeld or slot
                            }
                            xboxHeld = nextXboxHeld
                        }
                        if(pointers.isNotEmpty()) touch.accepted(pointers)
                        if (retiringMask != 0) synchronized(lock) {
                            for (index in buffers.indices) {
                                if (retiringMask and (1 shl index) != 0 && retireAfterNeutral[index] == retirementRevisions[index]) {
                                    // A sensor update racing the send keeps the new aim active.
                                    active.remove(index)
                                    retireAfterNeutral.remove(index)
                                }
                            }
                        }
                        sentState = states[0] ?: neutral
                        pointerEventsSent += pointers.size
                        sentBytes += packet?.size ?: 0
                        if (idle) idleReportsSent++
                        if (initial) initialStateReportsSent++
                    }
                    metrics = InputMetrics(bufferedBytes = queued, maxSchedulerDelayMs = maxDelay,
                        backpressureSkips = skipped, coalescedEdges = synchronized(lock) { buffers.sumOf { it.coalescedEdges } },
                        sentState = sentState, pointerEventsSent = pointerEventsSent, sentBytes = sentBytes,
                        idleReportsSent = idleReportsSent, initialStateReportsSent = initialStateReportsSent,
                        xboxPresses = xboxPresses, xboxReleases = xboxReleases)
                    if (now - windowStarted >= 1_000_000_000) { maxDelay = 0.0; windowStarted = now }
                    // Button edges can wake this thread early; regular reports remain at 250Hz.
                    deadline = if (now >= deadline) now + period else deadline
                }
            } catch (error: Exception) {
                if (running.get()) onFailure()
            } finally {
                running.set(false)
            }
        }, "xCloud-Input").apply { isDaemon = true; start() }
    }
    fun requestStop() {
        running.set(false)
        thread?.let(LockSupport::unpark)
    }
    fun close() {
        requestStop()
        thread?.let { if (it !== Thread.currentThread()) it.join(250) }
    }
    companion object {
        const val INPUT_HZ = 250
        const val IDLE_REPORT_NS=1_000_000_000L
        // At most two reports queued locally before suppressing additional state snapshots.
        const val MAX_BUFFERED_BYTES = 2 * 38L
    }
}
