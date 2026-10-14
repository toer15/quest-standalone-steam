package com.winlator.winhandler;

/**
 * Quest Steam: stand-in for Winlator's WinHandler (its helper program inside Wine for relative
 * mouse input and window focus). Not used here, so these do nothing.
 */
public class WinHandler {
    public void mouseEvent(int flags, int dx, int dy, int wheelDelta) {}
    public void bringToFront(String className, long handle) {}
}
