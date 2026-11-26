package com.xcloud.tclnative.ui.dashboard

import android.content.res.Resources
import android.graphics.drawable.DrawableWrapper
import androidx.annotation.DrawableRes

/** Use the platform vector renderer with independent tint and state for each badge. */
internal class CatalogMarkDrawable(resources: Resources, @DrawableRes resource: Int) :
    DrawableWrapper(resources.getDrawable(resource, null).mutate())
