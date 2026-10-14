package com.winlator.xenvironment.components;

import androidx.annotation.Keep;

import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.widget.XServerView;
import com.winlator.xconnector.Client;
import com.winlator.xconnector.ConnectionHandler;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.XInputStream;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Window;
import com.winlator.xserver.XServer;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Vortek: the game's Vulkan calls (from the guest ICD libvulkan_vortek.so) come over a Unix socket
 * and run on the headset's own Vulkan driver in this process (libvortekrenderer.so, from Winlator 11.2).
 * Frames land in AHardwareBuffers attached to the X window, which the X server's renderer draws.
 * The native method names and the Options fields must match libvortekrenderer.so exactly.
 */
public class VortekRendererComponent implements ConnectionHandler, RequestHandler {
    public static final int VK_MAX_VERSION = vkMakeVersion(1, 3, 128);
    private XConnectorEpoll connector;
    private final Options options;
    private final UnixSocketConfig socketConfig;
    private final XServer xServer;
    private final XServerView xServerView;
    /** Where connection events go (the game log). */
    public static volatile Consumer<String> logger = null;

    private static void log(String line) {
        Consumer<String> l = logger;
        if (l != null) l.accept(line);
    }

    static {
        System.loadLibrary("vortekrenderer");
    }

    @Keep
    public static class Options {
        @Keep public int vkMaxVersion = VK_MAX_VERSION;
        @Keep public short maxDeviceMemory = 0;
        @Keep public short imageCacheSize = 256;
        @Keep public byte resourceMemoryType = 0;
        @Keep public String[] exposedDeviceExtensions = null;
        @Keep public String libvulkanPath = null;
    }

    public VortekRendererComponent(XServer xServer, XServerView xServerView, UnixSocketConfig socketConfig, Options options, String nativeLibraryDir) {
        this.xServer = xServer;
        this.xServerView = xServerView;
        this.socketConfig = socketConfig;
        this.options = options;
        initVulkanWrapper(nativeLibraryDir, options.libvulkanPath);
    }

    public static int vkMakeVersion(int major, int minor, int patch) {
        return (major << 22) | (minor << 12) | patch;
    }

    public synchronized void start() {
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, this, this);
        connector.setInitialInputBufferCapacity(8);
        connector.setInitialOutputBufferCapacity(0);
        connector.start();
    }

    public synchronized void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }
    }

    @Keep
    private int getWindowWidth(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        return window != null ? window.getWidth() : 0;
    }

    @Keep
    private int getWindowHeight(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        return window != null ? window.getHeight() : 0;
    }

    @Keep
    private long getWindowHardwareBuffer(int windowId, boolean useHALPixelFormatBGRA8888) {
        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) return 0;
        Drawable drawable = window.getContent();
        if (drawable == null) return 0;
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            if (texture instanceof GPUImage && ((GPUImage)texture).getHardwareBufferPtr() != 0) {
                return ((GPUImage)texture).getHardwareBufferPtr();
            }
            GPUImage gpuImage = new GPUImage(drawable.width, drawable.height, false, useHALPixelFormatBGRA8888);
            if (gpuImage.getHardwareBufferPtr() == 0) return 0;
            if (texture != null) xServerView.queueEvent(texture::destroy);
            drawable.setTexture(gpuImage);
            return gpuImage.getHardwareBufferPtr();
        }
    }

    @Keep
    private void updateWindowContent(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) return;
        Drawable drawable = window.getContent();
        if (drawable == null) return;
        drawable.getTexture().setNeedsUpdate(true);
        Runnable onDraw = drawable.getOnDrawListener();
        if (onDraw != null) onDraw.run();
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        Object tag = client.getTag();
        if (tag != null) {
            client.setTag(null);
            destroyVkContext((Long)tag);
        }
        log("Vortek: the game's Vulkan disconnected.");
    }

    @Override
    public void handleNewConnection(Client client) {
        client.createIOStreams();
        log("Vortek: the game's Vulkan connected.");
    }

    @Override
    public boolean handleRequest(Client client) throws IOException {
        XInputStream inputStream = client.getInputStream();
        if (inputStream.available() < 8) return false;
        int requestCode = inputStream.readInt();
        int requestLength = inputStream.readInt();
        if (requestCode == 1) {
            long contextPtr = createVkContext(client.clientSocket.fd, options);
            if (contextPtr > 0) {
                client.setTag(contextPtr);
                log("Vortek: Vulkan device ready.");
            }
            else {
                log("Vortek: couldn't create the Vulkan device on the headset's driver.");
                connector.killConnection(client);
            }
        }
        else if (requestCode > 32767 && (requestCode >> 16) == 2) {
            Object tag = client.getTag();
            if (tag == null || !handleExtraDataRequest((Long)tag, requestCode & 0xffff, requestLength)) {
                log("Vortek: request " + (requestCode & 0xffff) + " failed.");
                throw new IOException("Failed to handle extra data request.");
            }
        }
        return true;
    }

    private native long createVkContext(int clientFd, Options options);

    private native void destroyVkContext(long contextPtr);

    private native boolean handleExtraDataRequest(long contextPtr, int requestId, int requestLength);

    private native void initVulkanWrapper(String nativeLibraryDir, String libvulkanPath);
}
