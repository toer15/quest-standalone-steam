package com.winlator;

/**
 * Quest Steam: stand-in for Winlator's XR activity. The game window here is drawn in a normal
 * Android view; VR games use Quest Steam's own SteamVR bridge instead.
 */
public final class XrActivity {
    public static boolean isSupported() { return false; }
    public static XrActivity getInstance() { return null; }
    public static boolean getImmersive() { return false; }
    public static boolean getSBS() { return false; }
    public static void updateControllers() {}
    public void init() {}
    public int getWidth() { return 0; }
    public int getHeight() { return 0; }
    public boolean beginFrame(boolean immersive, boolean sbs) { return false; }
    public void endFrame() {}
    public void bindFramebuffer() {}
}
