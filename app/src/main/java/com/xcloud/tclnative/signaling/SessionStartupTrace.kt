package com.xcloud.tclnative.signaling

/** Protocol state and monotonic durations only; no session URL, account data or service payload. */
class SessionStartupTrace(private val clock: () -> Long) {
    private var state="NOT_STARTED"
    private var replies=0L
    private var confirmedAt: Long?=null
    private var queuedAt: Long?=null
    private var queueMs=0L
    private var estimateSeconds: Long?=null
    private var estimateAt: Long?=null
    private var estimateFailed=false
    @Synchronized fun reset() { state="NOT_STARTED";replies=0;confirmedAt=null;queuedAt=null;queueMs=0;estimateSeconds=null;estimateAt=null;estimateFailed=false }
    @Synchronized fun estimate(value: QueueStatus) {
        estimateSeconds=value.estimate?.seconds
        estimateAt=value.estimateAtMs
        estimateFailed=value.estimateFailed
    }
    @Synchronized fun observe(value: String) {
        val now=clock()
        if(state=="WaitingForResources" && value!="WaitingForResources") {
            queueMs+=(now-(queuedAt ?: now)).coerceAtLeast(0);queuedAt=null
        }
        if(value=="WaitingForResources" && queuedAt==null) queuedAt=now
        state=if(value in STATES) value else "OTHER"
        confirmedAt=now;replies++
    }
    @Synchronized fun diagnostics(): String {
        val now=clock()
        val waiting=queueMs+(queuedAt?.let { (now-it).coerceAtLeast(0) } ?: 0)
        val age=confirmedAt?.let { "${(now-it).coerceAtLeast(0)}ms" } ?: "n/d"
        return "Xbox /state: $state; replies=$replies; age=$age; queue=${waiting}ms"+
            "\nQUEUE_ESTIMATE/seconds=${estimateSeconds ?: "n/d"}; age=${estimateAt?.let { "${(now-it).coerceAtLeast(0)}ms" } ?: "n/d"}; failed=$estimateFailed"
    }
    companion object {
        private val STATES=setOf("WaitingForResources","Provisioning","New","ReadyToConnect","Provisioned","Failed","Error","Ended","Cancelled","Closed")
    }
}
