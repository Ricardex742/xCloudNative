package com.xcloud.tclnative.renderer

/** Content rate is separate from RTP/decoder FPS. Ambiguity always keeps the real frame. */
class ContentFrameCadence {
    enum class Action { PASS, DUPLICATE, INTERPOLATE, RESET }
    data class Decision(val action: Action,val intervalNs: Long=0,val difference: Double=0.0,
                        val uniqueFastFrame: Boolean=false)
    private var previous: ByteArray?=null
    private var previousTimestamp=0L
    private var contentTimestamp=0L
    private var previousDuplicate: Boolean?=null
    private var alternations=0
    private var nativeRun=0
    var duplicateCadence=false;private set
    fun reset(){previous=null;previousTimestamp=0;contentTimestamp=0;previousDuplicate=null;alternations=0;nativeRun=0;duplicateCadence=false}
    fun observe(timestampNs: Long,signature: ByteArray): Decision {
        val old=previous;val delta=timestampNs-previousTimestamp
        previous=signature.clone();previousTimestamp=timestampNs
        if(old==null||old.size!=signature.size||delta !in 10_000_000L..70_000_000L){
            contentTimestamp=timestampNs;alternations=0;nativeRun=0;duplicateCadence=false;previousDuplicate=null
            return Decision(Action.RESET)
        }
        val difference=old.indices.sumOf { kotlin.math.abs((old[it].toInt() and 255)-(signature[it].toInt() and 255)).toLong() }.toDouble()/old.size
        if(difference>45){
            contentTimestamp=timestampNs;alternations=0;nativeRun=0;duplicateCadence=false;previousDuplicate=null
            return Decision(Action.RESET,difference=difference)
        }
        if(delta in 31_000_000L..39_000_000L){
            duplicateCadence=false;alternations=0;previousDuplicate=null;nativeRun++
            val interval=timestampNs-contentTimestamp;contentTimestamp=timestampNs
            return Decision(if(nativeRun>=5&&difference>1.0&&interval in 31_000_000L..40_000_000L) Action.INTERPOLATE else Action.PASS,interval,difference)
        }
        nativeRun=0
        if(delta !in FAST_INTERVAL_NS || difference in 1.0..3.0){
            alternations=0;duplicateCadence=false;previousDuplicate=null;contentTimestamp=timestampNs
            return Decision(Action.PASS,difference=difference)
        }
        val duplicate=difference<1.0
        alternations=if(previousDuplicate!=null&&previousDuplicate!=duplicate) alternations+1 else 0
        previousDuplicate=duplicate
        if(alternations>=12)duplicateCadence=true
        else if(alternations==0)duplicateCadence=false
        if(!duplicateCadence){contentTimestamp=timestampNs;return Decision(Action.PASS,difference=difference,uniqueFastFrame=!duplicate)}
        if(duplicate)return Decision(Action.DUPLICATE,difference=difference)
        val interval=timestampNs-contentTimestamp;contentTimestamp=timestampNs
        return Decision(if(interval in 30_000_000L..42_000_000L) Action.INTERPOLATE else Action.PASS,interval,difference)
    }
    companion object {
        internal val FAST_INTERVAL_NS = 13_000_000L..20_000_000L
    }
}
