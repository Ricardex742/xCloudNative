package com.xcloud.tclnative.ui.dashboard

import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** Keep console artwork proportions while allowing taps around small native controls. */
internal object ConsoleHitTargets {
    fun bounds(root: ViewGroup,target: View): Rect {
        val r=Rect(0,0,target.width,target.height)
        root.offsetDescendantRectToMyCoords(target,r)
        val size=maxOf((48*target.resources.displayMetrics.density).roundToInt(),if(target is SearchButton && target.phoneTarget) 72 else 0)
        val dx=(size-r.width()).coerceAtLeast(0);val dy=(size-r.height()).coerceAtLeast(0)
        r.inset(-(dx+1)/2,-(dy+1)/2)
        return r
    }
    fun install(root: ViewGroup,targets: List<View>) {
        root.touchDelegate=ExpandedDelegate(root,targets)
    }
    /** A ScrollView consumes empty gutters too; route a margin tap before it gets that event. */
    fun dispatch(root: ViewGroup,event: MotionEvent): Boolean =
        (root.touchDelegate as? ExpandedDelegate)?.beforeChildren(event)==true

    private class ExpandedDelegate(val root: ViewGroup,val targets: List<View>): TouchDelegate(Rect(),root) {
        private var active: TouchDelegate?=null
        private fun actualControlAt(view: View,x: Int,y: Int): Boolean {
            if(!view.isShown) return false
            val r=Rect(0,0,view.width,view.height)
            if(view!==root) root.offsetDescendantRectToMyCoords(view,r)
            if(view!==root && view.isEnabled && view.isClickable && r.contains(x,y)) return true
            if(view is ViewGroup && (view===root || !view.clipChildren || r.contains(x,y)))
                for(i in 0 until view.childCount) if(actualControlAt(view.getChildAt(i),x,y)) return true
            return false
        }
        fun beforeChildren(event: MotionEvent): Boolean {
            if(event.actionMasked==MotionEvent.ACTION_DOWN) {
                active=null
                targets.firstOrNull { target ->
                    if(!target.isShown || !target.isEnabled) false else {
                        val r=Rect(0,0,target.width,target.height);root.offsetDescendantRectToMyCoords(target,r)
                        r.contains(event.x.toInt(),event.y.toInt())
                    }
                }?.let { if(it.isFocusableInTouchMode) it.requestFocus() }
                if(actualControlAt(root,event.x.toInt(),event.y.toInt())) return false
            }
            return if(active!=null || event.actionMasked==MotionEvent.ACTION_DOWN) onTouchEvent(event) else false
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if(event.actionMasked==MotionEvent.ACTION_DOWN) {
                active=targets.filter { it.isShown && it.isEnabled && bounds(root,it).contains(event.x.toInt(),event.y.toInt()) }
                    .minByOrNull {
                        val r=Rect(0,0,it.width,it.height);root.offsetDescendantRectToMyCoords(it,r)
                        val x=event.x-r.exactCenterX();val y=event.y-r.exactCenterY();x*x+y*y
                    }?.let {
                        // Focus on down: a focusable TextView otherwise consumes its first up only
                        // to obtain focus, instead of activating the close/action button.
                        if(it.isFocusableInTouchMode) it.requestFocus()
                        TouchDelegate(bounds(root,it),it)
                    }
            }
            val copy=MotionEvent.obtain(event)
            val handled=try { active?.onTouchEvent(copy)==true } finally { copy.recycle() }
            if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) active=null
            return handled
        }
    }
}
