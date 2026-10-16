package com.winlator.core;

import android.content.res.Resources;

/** Quest Steam: the two helpers TouchpadView uses from Winlator's AppUtils. */
public final class AppUtils {
    public static int getScreenWidth() { return Resources.getSystem().getDisplayMetrics().widthPixels; }
    public static int getScreenHeight() { return Resources.getSystem().getDisplayMetrics().heightPixels; }
}
