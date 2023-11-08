/*
 * Copyright (c) 2009-2023 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.jme3.system.lwjgl;

import com.jme3.input.JoyInput;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.TouchInput;
import com.jme3.input.lwjgl.GlfwJoystickInput;
import com.jme3.math.Vector2f;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.system.JmeSystem;
import com.jme3.system.NanoTimer;
import com.jme3.util.BufferUtils;
import com.jme3.util.SafeArrayList;
import com.jme3.system.lwjgl.openxr.HelloOpenXRGL;

import org.lwjgl.Version;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWWindowFocusCallback;
import org.lwjgl.glfw.GLFWWindowSizeCallback;
import org.lwjgl.system.Platform;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * A wrapper class over the GLFW framework in LWJGL 3.
 *
 * @author Daniel Johansson
 */
public class LwjglWindowXr extends LwjglContextXr implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(LwjglWindowXr.class.getName());

    private static final Map<String, Runnable> RENDER_CONFIGS = new HashMap<>();

    static {
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL30, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL31, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL32, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL33, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL40, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL41, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL42, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL43, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL44, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 4);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL45, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
        });
    }

    protected final AtomicBoolean needClose = new AtomicBoolean(false);
    protected final AtomicBoolean needRestart = new AtomicBoolean(false);

    private final JmeContext.Type type;
    private final SafeArrayList<WindowSizeListener> windowSizeListeners = new SafeArrayList<>(WindowSizeListener.class);

    private GLFWErrorCallback errorCallback;
    private GLFWWindowSizeCallback windowSizeCallback;
    private GLFWFramebufferSizeCallback framebufferSizeCallback;
    private GLFWWindowFocusCallback windowFocusCallback;
    
    HelloOpenXRGL xr;

    private Thread mainThread;

    private long window = NULL;
    private int frameRateLimit = -1;

    protected boolean wasActive = false;
    protected boolean autoFlush = true;
    protected boolean allowSwapBuffers = false;

    // temp variables used for glfw calls
    private int width[] = new int[1];
    private int height[] = new int[1];

    public LwjglWindowXr() {
        this.type = JmeContext.Type.Display;
    }
    
    public HelloOpenXRGL getXr() { return xr; }

    /**
     * Registers the specified listener to get notified when window size changes.
     *
     * @param listener The WindowSizeListener to register.
     */
    public void registerWindowSizeListener(WindowSizeListener listener) {
        windowSizeListeners.add(listener);
    }

    /**
     * Removes the specified listener from the listeners list.
     *
     * @param listener The WindowSizeListener to remove.
     */
    public void removeWindowSizeListener(WindowSizeListener listener) {
        windowSizeListeners.remove(listener);
    }

    /**
     * @return Type.Display or Type.Canvas
     */
    @Override
    public JmeContext.Type getType() {
        return type;
    }

    /**
     * Set the title if it's a windowed display
     *
     * @param title the title to set
     */
    @Override
    public void setTitle(final String title) {
        if (created.get() && window != NULL) {
            glfwSetWindowTitle(window, title);
        }
    }

    /**
     * Restart if it's a windowed or full-screen display.
     */
    @Override
    public void restart() {
        if (created.get()) {
            needRestart.set(true);
        } else {
            LOGGER.warning("Display is not created, cannot restart window.");
        }
    }

    /**
     * Apply the settings, changing resolution, etc.
     *
     * @param settings the settings to apply when creating the context.
     */
    protected void createContext(final AppSettings settings) {

        glfwSetErrorCallback(errorCallback = new GLFWErrorCallback() {
            @Override
            public void invoke(int error, long description) {
                final String message = GLFWErrorCallback.getDescription(description);
                listener.handleError(message, new Exception(message));
            }
        });
System.out.println("Pre init xr");
xr = new HelloOpenXRGL(settings);
System.out.println("Post init xr");
window = xr.getWindow();
    }
    

    protected void showWindow() {
        glfwShowWindow(window);
    }

    /**
     * Set custom icons to the window of this application.
     *
     * @param settings settings for getting the icons
     */
    protected void setWindowIcon(final AppSettings settings) {

        final Object[] icons = settings.getIcons();
        if (icons == null) return;

        final GLFWImage[] images = imagesToGLFWImages(icons);

        try (final GLFWImage.Buffer iconSet = GLFWImage.malloc(images.length)) {

            for (int i = images.length - 1; i >= 0; i--) {
                final GLFWImage image = images[i];
                iconSet.put(i, image);
            }

            glfwSetWindowIcon(window, iconSet);
        }
    }

    /**
     * Convert array of images to array of {@link GLFWImage}.
     */
    private GLFWImage[] imagesToGLFWImages(final Object[] images) {

        final GLFWImage[] out = new GLFWImage[images.length];

        for (int i = 0; i < images.length; i++) {
            final BufferedImage image = (BufferedImage) images[i];
            out[i] = imageToGLFWImage(image);
        }

        return out;
    }

    /**
     * Convert the {@link BufferedImage} to the {@link GLFWImage}.
     */
    private GLFWImage imageToGLFWImage(BufferedImage image) {

        if (image.getType() != BufferedImage.TYPE_INT_ARGB_PRE) {

            final BufferedImage convertedImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB_PRE);
            final Graphics2D graphics = convertedImage.createGraphics();

            final int targetWidth = image.getWidth();
            final int targetHeight = image.getHeight();

            graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
            graphics.dispose();

            image = convertedImage;
        }

        final ByteBuffer buffer = BufferUtils.createByteBuffer(image.getWidth() * image.getHeight() * 4);

        for (int i = 0; i < image.getHeight(); i++) {
            for (int j = 0; j < image.getWidth(); j++) {
                int colorSpace = image.getRGB(j, i);
                buffer.put((byte) ((colorSpace << 8) >> 24));
                buffer.put((byte) ((colorSpace << 16) >> 24));
                buffer.put((byte) ((colorSpace << 24) >> 24));
                buffer.put((byte) (colorSpace >> 24));
            }
        }

        buffer.flip();

        final GLFWImage result = GLFWImage.create();
        result.set(image.getWidth(), image.getHeight(), buffer);

        return result;
    }

    /**
     * Destroy the context.
     */
    protected void destroyContext() {
        try {
            if (renderer != null) {
                renderer.cleanup();
            }

            if (errorCallback != null) {

                // We need to specifically set this to null as we might set a new callback before we reinit GLFW
                glfwSetErrorCallback(null);

                errorCallback.close();
                errorCallback = null;
            }

            if (windowSizeCallback != null) {
                windowSizeCallback.close();
                windowSizeCallback = null;
            }

            if (framebufferSizeCallback != null) {
                framebufferSizeCallback.close();
                framebufferSizeCallback = null;
            }

            if (windowFocusCallback != null) {
                windowFocusCallback.close();
                windowFocusCallback = null;
            }

            if (window != NULL) {
                glfwDestroyWindow(window);
                window = NULL;
            }

        } catch (final Exception ex) {
            listener.handleError("Failed to destroy context", ex);
        }
    }

    @Override
    public void create(boolean waitFor) {
        if (created.get()) {
            LOGGER.warning("create() called when display is already created!");
            return;
        }

        if (Platform.get() == Platform.MACOSX) {
            // NOTE: this is required for Mac OS X!
            mainThread = Thread.currentThread();
            mainThread.setName("jME3 Main");
            if (waitFor) {
                LOGGER.warning("create(true) is not supported for macOS!");
            }
            run();
        } else {
            mainThread = new Thread(this, "jME3 Main");
            mainThread.start();
            if (waitFor) {
                waitFor(true);
            }
        }

    }

    /**
     * Does LWJGL display initialization in the OpenGL thread
     *
     * @return returns {@code true} if the context initialization was successful
     */
    protected boolean initInThread() {
        try {
            if (!JmeSystem.isLowPermissions()) {
                // Enable uncaught exception handler only for current thread
                Thread.currentThread().setUncaughtExceptionHandler((thread, thrown) -> {
                    listener.handleError("Uncaught exception thrown in " + thread.toString(), thrown);
                    if (needClose.get()) {
                        // listener.handleError() has requested the
                        // context to close. Satisfy request.
                        deinitInThread();
                    }
                });
            }

            timer = new NanoTimer();

            // For canvas, this will create a PBuffer,
            // allowing us to query information.
            // When the canvas context becomes available, it will
            // be replaced seamlessly.
            createContext(settings);
            printContextInitInfo();

            created.set(true);
            super.internalCreate(); //Try
        } catch (Exception ex) {
            try {
                if (window != NULL) {
                    glfwDestroyWindow(window);
                    window = NULL;
                }
            } catch (Exception ex2) {
                LOGGER.log(Level.WARNING, null, ex2);
            }

            listener.handleError("Failed to create display", ex);
            return false; // if we failed to create display, do not continue
        }

        listener.initialize();

        return true;
    }


    /**
     * execute one iteration of the render loop in the OpenGL thread
     */
    protected void runLoop() {
        // If a restart is required, lets recreate the context.
        if (needRestart.getAndSet(false)) {
            restartContext();
        }

        if (!created.get()) {
            throw new IllegalStateException();
        }


        listener.update();

        // All this does is call glfwSwapBuffers().
        // If the canvas is not active, there's no need to waste time
        // doing that.
        if (renderable.get()) {
            // calls swap buffers, etc.
            try {
                if (allowSwapBuffers && autoFlush) {
                    glfwSwapBuffers(window);
                }
            } catch (Throwable ex) {
                listener.handleError("Error while swapping buffers", ex);
            }
        }
        xr.renderFrame();

        // Subclasses just call GLObjectManager. Clean up objects here.
        // It is safe ... for now.
        if (renderer != null) {
            renderer.postFrame();
        }

        if (autoFlush) {
            if (frameRateLimit != getSettings().getFrameRate()) {
                setFrameRateLimit(getSettings().getFrameRate());
            }
        } else if (frameRateLimit != 20) {
            setFrameRateLimit(20);
        }

        Sync.sync(frameRateLimit);

        glfwPollEvents();
    }

    private void restartContext() {
        try {
            destroyContext();
            createContext(settings);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Failed to set display settings!", ex);
        }
        // Reinitialize context flags and such
        reinitContext();

        // We need to reinit the mouse and keyboard input as they are tied to a window handle
        if (keyInput != null && keyInput.isInitialized()) {
            keyInput.resetContext();
        }
        if (mouseInput != null && mouseInput.isInitialized()) {
            mouseInput.resetContext();
        }

        LOGGER.fine("Display restarted.");
    }

    private void setFrameRateLimit(int frameRateLimit) {
        this.frameRateLimit = frameRateLimit;
    }

    /**
     * De-initialize in the OpenGL thread.
     */
    protected void deinitInThread() {
        listener.destroy();

        destroyContext();
        super.internalDestroy();
        glfwTerminate();

        LOGGER.fine("Display destroyed.");
    }

    @Override
    public void run() {
        if (listener == null) {
            throw new IllegalStateException("SystemListener is not set on context!"
                    + "Must set with JmeContext.setSystemListener().");
        }

        LOGGER.log(Level.FINE, "Using LWJGL {0}", Version.getVersion());

        if (!initInThread()) {
            LOGGER.log(Level.SEVERE, "Display initialization failed. Cannot continue.");
            return;
        }

        while (true) {

            runLoop();
            if (needClose.get()) {
                break;
            }

            if (glfwWindowShouldClose(window)) { //Try
                listener.requestClose(false); //Try
            } //Try
        }

        deinitInThread();
    }

    @Override
    public JoyInput getJoyInput() {
        if (joyInput == null) {
            joyInput = new GlfwJoystickInput();
        }
        return joyInput;
    }

    @Override
    public MouseInput getMouseInput() {
        if (mouseInput == null) {
            mouseInput = new EmptyMouseInput();
        }
        return mouseInput;
    }

    @Override
    public KeyInput getKeyInput() {
        if (keyInput == null) {
            keyInput = new EmptyKeyInput();
        }
        return keyInput;
    }

    @Override
    public TouchInput getTouchInput() {
        return null;
    }

    @Override
    public void setAutoFlushFrames(boolean enabled) {
        this.autoFlush = enabled;
    }

    @Override
    public void destroy(boolean waitFor) {
        needClose.set(true);

        if (mainThread == Thread.currentThread()) {
            // Ignore waitFor.
            return;
        }

        if (waitFor) {
            waitFor(false);
        }
    }

    public long getWindowHandle() {
        return window;
    }

    /**
     * Get the window content scale, for HiDPI support.
     *
     * The content scale is the ratio between the current DPI and the platform's default DPI.
     * This is especially important for text and any UI elements. If the pixel dimensions of
     * your UI scaled by this look appropriate on your machine then it should appear at a
     * reasonable size on other machines regardless of their DPI and scaling settings. This
     * relies on the system DPI and scaling settings being somewhat correct.
     *
     * @param store A vector2f to store the result
     * @return The window content scale
     * @see <a href="https://www.glfw.org/docs/latest/window_guide.html#window_scale">Window content scale</a>
     */
    public Vector2f getWindowContentScale(Vector2f store) {
        if (store == null) store = new Vector2f();

        glfwGetFramebufferSize(window, width, height);
        store.set(width[0], height[0]);

        glfwGetWindowSize(window, width, height);
        store.x /= width[0];
        store.y /= height[0];

        return store;
    }

    /**
     * Returns the height of the framebuffer.
     *
     * @return the height (in pixels)
     */
    @Override
    public int getFramebufferHeight() {
        glfwGetFramebufferSize(window, width, height);
        int result = height[0];
        return result;
    }

    /**
     * Returns the width of the framebuffer.
     *
     * @return the width (in pixels)
     */
    @Override
    public int getFramebufferWidth() {
        glfwGetFramebufferSize(window, width, height);
        int result = width[0];
        return result;
    }

    /**
     * Returns the screen X coordinate of the left edge of the content area.
     *
     * @return the screen X coordinate
     */
    @Override
    public int getWindowXPosition() {
        glfwGetWindowPos(window, width, height);
        int result = width[0];
        return result;
    }

    /**
     * Returns the screen Y coordinate of the top edge of the content area.
     *
     * @return the screen Y coordinate
     */
    @Override
    public int getWindowYPosition() {
        glfwGetWindowPos(window, width, height);
        int result = height[0];
        return result;
    }
}