/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 * Source: https://github.com/LWJGL/lwjgl3/tree/master/modules/samples/src/test/java/org/lwjgl/demo/openxr
 */
package com.jme3.system.lwjgl.openxr;

import org.joml.*;
import org.lwjgl.*;
import org.lwjgl.opengl.*;
import org.lwjgl.openxr.*;
import org.lwjgl.system.*;

import com.jme3.input.xr.XrHmd;
import com.jme3.system.AppSettings;

import java.nio.*;
import java.util.*;
import java.util.logging.Logger;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.openxr.EXTDebugUtils.*;
import static org.lwjgl.openxr.KHROpenGLEnable.*;
import static org.lwjgl.openxr.MNDXEGLEnable.*;
import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

public class HelloOpenXRGL {
	
    private static final Logger logger = Logger.getLogger(HelloOpenXRGL.class.getName());
    
    long window;
    AppSettings appSettings;
    
    //XR globals
    //Init
    XrInstance                     xrInstance;
    long                           systemID;
    XrSession                      xrSession;
    boolean                        missingXrDebug;
    boolean                        useEglGraphicsBinding;
    XrDebugUtilsMessengerEXT       xrDebugMessenger;
    XrSpace                        xrAppSpace;  //The real world space in which the program runs
    long                           glColorFormat;
    XrView.Buffer                  views;       //Each view reperesents an eye in the headset with views[0] being left and views[1] being right
    Swapchain[]                    swapchains;  //One swapchain per view
    XrViewConfigurationView.Buffer viewConfigs;
    int                            viewConfigType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;

    //Runtime
    XrEventDataBuffer eventDataBuffer;
    int               sessionState;
    boolean           sessionRunning;

    //GL globals
    Map<XrSwapchainImageOpenGLKHR, Integer> depthTextures; //Swapchain images only provide a color texture so we have to create depth textures seperatley

    int swapchainFramebuffer;
    int cubeVertexBuffer;
    int cubeIndexBuffer;
    int quadVertexBuffer;
    int cubeVAO;
    int quadVAO;
    int screenShader;
    int textureShader;
    int colorShader;
    XrHmd xrHmd;
    static class Swapchain {
        XrSwapchain                      handle;
        int                              width;
        int                              height;
        XrSwapchainImageOpenGLKHR.Buffer images;
    }

    //JME3: Split function main(args) to public functions constr+init+render+destroy
    public HelloOpenXRGL(AppSettings appSettings)
    {
    	this.appSettings = appSettings;
    	createOpenXRInstance();
        initializeOpenXRSystem();
        initializeAndBindOpenGL();
        createXRReferenceSpace();
        createXRSwapchains();
        createOpenGLResources();
        eventDataBuffer = XrEventDataBuffer.calloc()
            .type$Default();
    }
    
    /** Returns true for continue */
    public boolean renderFrame()
    {
    	if (pollEvents()) return false;
    	if (glfwWindowShouldClose(window)) return false;
        if (sessionRunning)
        {
        	try {
              renderFrameOpenXR();
        	}
        	catch (IllegalStateException e)
        	{
        		return false;
        	}
        }
        else
        {
            // Throttle loop since xrWaitFrame won't be called.
            try
            {
                Thread.sleep(250);
            }
            catch (InterruptedException e)
            {
            	e.printStackTrace();
            	return false;
            }
        }
        return true;
    }
    
    public void destroy()
    {
    	glFinish();

        // Destroy OpenXR
        eventDataBuffer.free();
        views.free();
        viewConfigs.free();
        for (Swapchain swapchain : swapchains) {
            xrDestroySwapchain(swapchain.handle);
            swapchain.images.free();
        }

        xrDestroySpace(xrAppSpace);
        if (xrDebugMessenger != null) {
            xrDestroyDebugUtilsMessengerEXT(xrDebugMessenger);
        }
        xrDestroySession(xrSession);
        xrDestroyInstance(xrInstance);

        //Destroy OpenGL
        for (int texture : depthTextures.values()) {
            glDeleteTextures(texture);
        }
        glDeleteFramebuffers(swapchainFramebuffer);
        glDeleteBuffers(cubeVertexBuffer);
        glDeleteBuffers(cubeIndexBuffer);
        glDeleteBuffers(quadVertexBuffer);
        glDeleteVertexArrays(cubeVAO);
        glDeleteVertexArrays(quadVAO);
        glDeleteProgram(screenShader);
        glDeleteProgram(textureShader);
        glDeleteProgram(colorShader);

        glfwTerminate();
    }
    
    //JME3: New public functions for positions/orientations
    public void getViewPosition(com.jme3.math.Vector3f store)
    {
    	store.set(viewPos);
    }
    public void getViewRotation(com.jme3.math.Quaternion store)
    {
    	store.set(viewRot);
    }
    public void getRenderSize(com.jme3.math.Vector2f store)
    {
    	if (swapchains == null || swapchains.length == 0 || swapchains[0] == null)
    	{
    		logger.warning("XR is not ready. Returning default renderSize of 1512x1680");
    		store.set(1512, 1680);
    	}
    	store.set(swapchains[0].width,swapchains[0].height);
    }
    public long getWindow() { return window; }
    public void setHmd(XrHmd xrHmd) { this.xrHmd = xrHmd; }

    private void createOpenXRInstance() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pi = stack.mallocInt(1);

            check(xrEnumerateInstanceExtensionProperties((ByteBuffer)null, pi, null));
            int numExtensions = pi.get(0);

            XrExtensionProperties.Buffer properties = XRHelper.prepareExtensionProperties(stack, numExtensions);

            check(xrEnumerateInstanceExtensionProperties((ByteBuffer)null, pi, properties));

            System.out.printf("OpenXR loaded with %d extensions:%n", numExtensions);
            System.out.println("~~~~~~~~~~~~~~~~~~");

            boolean missingOpenGL = true;
            missingXrDebug = true;

            useEglGraphicsBinding = false;
            for (int i = 0; i < numExtensions; i++) {
                XrExtensionProperties prop = properties.get(i);

                String extensionName = prop.extensionNameString();
                System.out.println(extensionName);

                if (extensionName.equals(XR_KHR_OPENGL_ENABLE_EXTENSION_NAME)) {
                    missingOpenGL = false;
                }
                if (extensionName.equals(XR_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
                    missingXrDebug = false;
                }
                if (extensionName.equals(XR_MNDX_EGL_ENABLE_EXTENSION_NAME)) {
                    useEglGraphicsBinding = true;
                }
            }

            if (missingOpenGL) {
                throw new IllegalStateException("OpenXR library does not provide required extension: " + XR_KHR_OPENGL_ENABLE_EXTENSION_NAME);
            }

            if (useEglGraphicsBinding) {
                System.out.println("Going to use cross-platform experimental EGL for session creation");
            } else {
                System.out.println("Going to use platform-specific session creation");
            }

            PointerBuffer extensions = stack.mallocPointer(2);
            extensions.put(stack.UTF8(XR_KHR_OPENGL_ENABLE_EXTENSION_NAME));
            if (useEglGraphicsBinding) {
                extensions.put(stack.UTF8(XR_MNDX_EGL_ENABLE_EXTENSION_NAME));
            } else if (!missingXrDebug) {
                // At the time of writing this, the OpenXR validation layers don't like EGL
                extensions.put(stack.UTF8(XR_EXT_DEBUG_UTILS_EXTENSION_NAME));
            }
            extensions.flip();
            System.out.println("~~~~~~~~~~~~~~~~~~");

            boolean useValidationLayer = false;

            check(xrEnumerateApiLayerProperties(pi, null));
            int numLayers = pi.get(0);

            XrApiLayerProperties.Buffer pLayers = XRHelper.prepareApiLayerProperties(stack, numLayers);
            check(xrEnumerateApiLayerProperties(pi, pLayers));
            System.out.println(numLayers + " XR layers are available:");
            for (int index = 0; index < numLayers; index++) {
                XrApiLayerProperties layer = pLayers.get(index);

                String layerName = layer.layerNameString();
                System.out.println(layerName);

                // At the time of wring this, the OpenXR validation layers don't like EGL
                if (!useEglGraphicsBinding && layerName.equals("XR_APILAYER_LUNARG_core_validation")) {
                    useValidationLayer = true;
                }
            }
            System.out.println("-----------");

            PointerBuffer wantedLayers;
            if (useValidationLayer) {
                wantedLayers = stack.callocPointer(1);
                wantedLayers.put(0, stack.UTF8("XR_APILAYER_LUNARG_core_validation"));
                System.out.println("Enabling XR core validation");
            } else {
                System.out.println("Running without validation layers");
                wantedLayers = null;
            }

            XrInstanceCreateInfo createInfo = XrInstanceCreateInfo.malloc(stack)
                .type$Default()
                .next(NULL)
                .createFlags(0)
                .applicationInfo(XrApplicationInfo.calloc(stack)
                    .applicationName(stack.UTF8("HelloOpenXR"))
                    .apiVersion(XR_CURRENT_API_VERSION))
                .enabledApiLayerNames(wantedLayers)
                .enabledExtensionNames(extensions);

            PointerBuffer pp = stack.mallocPointer(1);
            System.out.println("Creating OpenXR instance...");
            check(xrCreateInstance(createInfo, pp));
            xrInstance = new XrInstance(pp.get(0), createInfo);
            System.out.println("Created OpenXR instance");
        }
    }

    public void initializeOpenXRSystem() {
        try (MemoryStack stack = stackPush()) {
            //Get headset
            LongBuffer pl = stack.longs(0);

            check(xrGetSystem(
                xrInstance,
                XrSystemGetInfo.malloc(stack)
                    .type$Default()
                    .next(NULL)
                    .formFactor(XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY),
                pl
            ));

            systemID = pl.get(0);
            if (systemID == 0) {
                throw new IllegalStateException("No compatible headset detected");
            }
            System.out.printf("Headset found with System ID: %d\n", systemID);
        }
    }

    private void initializeAndBindOpenGL() {
        try (MemoryStack stack = stackPush()) {
            //Initialize OpenXR's OpenGL compatability
            XrGraphicsRequirementsOpenGLKHR graphicsRequirements = XrGraphicsRequirementsOpenGLKHR.malloc(stack)
                .type$Default()
                .next(NULL)
                .minApiVersionSupported(0)
                .maxApiVersionSupported(0);

            xrGetOpenGLGraphicsRequirementsKHR(xrInstance, systemID, graphicsRequirements);

            int minMajorVersion = XR_VERSION_MAJOR(graphicsRequirements.minApiVersionSupported());
            int minMinorVersion = XR_VERSION_MINOR(graphicsRequirements.minApiVersionSupported());

            int maxMajorVersion = XR_VERSION_MAJOR(graphicsRequirements.maxApiVersionSupported());
            int maxMinorVersion = XR_VERSION_MINOR(graphicsRequirements.maxApiVersionSupported());

            System.out.println("The OpenXR runtime supports OpenGL " + minMajorVersion + "." + minMinorVersion
                               + " to OpenGL " + maxMajorVersion + "." + maxMinorVersion);

            // This example needs at least OpenGL 4.0
            if (maxMajorVersion < 4) {
                throw new UnsupportedOperationException("This example requires at least OpenGL 4.0");
            }
            int majorVersionToRequest = 4;
            int minorVersionToRequest = 0;

            // But when the OpenXR runtime requires a later version, we should respect that.
            // As a matter of fact, the runtime on my current laptop does, so this code is actually needed.
            if (minMajorVersion == 4) {
                minorVersionToRequest = 5;
            }

            //Init glfw
            if (!glfwInit()) {
                throw new IllegalStateException("Failed to initialize GLFW.");
            }

            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, majorVersionToRequest);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, minorVersionToRequest);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_DOUBLEBUFFER, GL_FALSE);
            if (useEglGraphicsBinding) {
                glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_EGL_CONTEXT_API);
            }
            window = glfwCreateWindow(640, 480, "Hello World", NULL, NULL);
            glfwMakeContextCurrent(window);
            GL.createCapabilities();

            // Check if OpenGL version is supported by OpenXR runtime
            int actualMajorVersion = glGetInteger(GL_MAJOR_VERSION);
            int actualMinorVersion = glGetInteger(GL_MINOR_VERSION);

            if (minMajorVersion > actualMajorVersion || (minMajorVersion == actualMajorVersion && minMinorVersion > actualMinorVersion)) {
                throw new IllegalStateException(
                    "The OpenXR runtime supports only OpenGL " + minMajorVersion + "." + minMinorVersion +
                    " and later, but we got OpenGL " + actualMajorVersion + "." + actualMinorVersion
                );
            }

            if (actualMajorVersion > maxMajorVersion || (actualMajorVersion == maxMajorVersion && actualMinorVersion > maxMinorVersion)) {
                throw new IllegalStateException(
                    "The OpenXR runtime supports only OpenGL " + maxMajorVersion + "." + minMajorVersion +
                    " and earlier, but we got OpenGL " + actualMajorVersion + "." + actualMinorVersion
                );
            }
            logger.info("OnCreateXr: Win=" + window + " Egl=" + useEglGraphicsBinding);
            
            //Bind the OpenGL context to the OpenXR instance and create the session
            PointerBuffer pp = stack.mallocPointer(1);
            check(xrCreateSession(
                xrInstance,
                XRHelper.createGraphicsBindingOpenGL(
                    XrSessionCreateInfo.malloc(stack)
                        .type$Default()
                        .next(NULL)
                        .createFlags(0)
                        .systemId(systemID),
                    stack,
                    window,
                    useEglGraphicsBinding
                ),
                pp
            ));

            xrSession = new XrSession(pp.get(0), xrInstance);

            if (!missingXrDebug && !useEglGraphicsBinding) {
                XrDebugUtilsMessengerCreateInfoEXT ciDebugUtils = XrDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                    .type$Default()
                    .messageSeverities(
                        XR_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT |
                        XR_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                        XR_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
                    )
                    .messageTypes(
                        XR_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                        XR_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                        XR_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT |
                        XR_DEBUG_UTILS_MESSAGE_TYPE_CONFORMANCE_BIT_EXT
                    )
                    .userCallback((messageSeverity, messageTypes, pCallbackData, userData) -> {
                        XrDebugUtilsMessengerCallbackDataEXT callbackData = XrDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                        System.out.println("XR Debug Utils: " + callbackData.messageString());
                        return 0;
                    });

                System.out.println("Enabling OpenXR debug utils");
                check(xrCreateDebugUtilsMessengerEXT(xrInstance, ciDebugUtils, pp));
                xrDebugMessenger = new XrDebugUtilsMessengerEXT(pp.get(0), xrInstance);
            }
        }
    }

    public void createXRReferenceSpace() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);

            check(xrCreateReferenceSpace(
                xrSession,
                XrReferenceSpaceCreateInfo.malloc(stack)
                    .type$Default()
                    .next(NULL)
                    .referenceSpaceType(XR_REFERENCE_SPACE_TYPE_LOCAL)
                    .poseInReferenceSpace(XrPosef.malloc(stack)
                        .orientation(XrQuaternionf.malloc(stack)
                            .x(0)
                            .y(0)
                            .z(0)
                            .w(1))
                        .position$(XrVector3f.calloc(stack))),
                pp
            ));

            xrAppSpace = new XrSpace(pp.get(0), xrSession);
        }
    }

    public void createXRSwapchains() {
        try (MemoryStack stack = stackPush()) {
            XrSystemProperties systemProperties = XrSystemProperties.calloc(stack)
                .type$Default();
            check(xrGetSystemProperties(xrInstance, systemID, systemProperties));

            System.out.printf("Headset name:%s vendor:%d \n",
                memUTF8(memAddress(systemProperties.systemName())),
                systemProperties.vendorId());

            XrSystemTrackingProperties trackingProperties = systemProperties.trackingProperties();
            System.out.printf("Headset orientationTracking:%b positionTracking:%b \n",
                trackingProperties.orientationTracking(),
                trackingProperties.positionTracking());

            XrSystemGraphicsProperties graphicsProperties = systemProperties.graphicsProperties();
            System.out.printf("Headset MaxWidth:%d MaxHeight:%d MaxLayerCount:%d \n",
                graphicsProperties.maxSwapchainImageWidth(),
                graphicsProperties.maxSwapchainImageHeight(),
                graphicsProperties.maxLayerCount());

            IntBuffer pi = stack.mallocInt(1);

            check(xrEnumerateViewConfigurationViews(xrInstance, systemID, viewConfigType, pi, null));
            viewConfigs = XRHelper.fill(
                XrViewConfigurationView.calloc(pi.get(0)),
                XrViewConfigurationView.TYPE,
                XR_TYPE_VIEW_CONFIGURATION_VIEW
            );

            check(xrEnumerateViewConfigurationViews(xrInstance, systemID, viewConfigType, pi, viewConfigs));
            int viewCountNumber = pi.get(0);

            views = XRHelper.fill(
                XrView.calloc(viewCountNumber),
                XrView.TYPE,
                XR_TYPE_VIEW
            );

            if (viewCountNumber > 0) {
                check(xrEnumerateSwapchainFormats(xrSession, pi, null));
                LongBuffer swapchainFormats = stack.mallocLong(pi.get(0));
                check(xrEnumerateSwapchainFormats(xrSession, pi, swapchainFormats));

                long[] desiredSwapchainFormats = {
                    GL_RGB10_A2,
                    GL_RGBA16F,
                    // The two below should only be used as a fallback, as they are linear color formats without enough bits for color
                    // depth, thus leading to banding.
                    GL_RGBA8,
                    GL31.GL_RGBA8_SNORM
                };

                out:
                for (long glFormatIter : desiredSwapchainFormats) {
                    for (int i = 0; i < swapchainFormats.limit(); i++) {
                        if (glFormatIter == swapchainFormats.get(i)) {
                            glColorFormat = glFormatIter;
                            break out;
                        }
                    }
                }

                if (glColorFormat == 0) {
                    throw new IllegalStateException("No compatable swapchain / framebuffer format availible");
                }

                swapchains = new Swapchain[viewCountNumber];
                for (int i = 0; i < viewCountNumber; i++) {
                    XrViewConfigurationView viewConfig = viewConfigs.get(i);

                    Swapchain swapchainWrapper = new Swapchain();

                    XrSwapchainCreateInfo swapchainCreateInfo = XrSwapchainCreateInfo.malloc(stack)
                        .type$Default()
                        .next(NULL)
                        .createFlags(0)
                        .usageFlags(XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT)
                        .format(glColorFormat)
                        .sampleCount(viewConfig.recommendedSwapchainSampleCount())
                        .width(viewConfig.recommendedImageRectWidth())
                        .height(viewConfig.recommendedImageRectHeight())
                        .faceCount(1)
                        .arraySize(1)
                        .mipCount(1);

                    System.out.printf("Headset Eye:%d has Width:%d Height:%d\n",
                    		i,
                    		viewConfig.recommendedImageRectWidth(),
                    		viewConfig.recommendedImageRectHeight());
                    appSettings.setWidth(viewConfig.recommendedImageRectWidth());
                    appSettings.setHeight(viewConfig.recommendedImageRectHeight());
                    PointerBuffer pp = stack.mallocPointer(1);
                    check(xrCreateSwapchain(xrSession, swapchainCreateInfo, pp));

                    swapchainWrapper.handle = new XrSwapchain(pp.get(0), xrSession);
                    swapchainWrapper.width = swapchainCreateInfo.width();
                    swapchainWrapper.height = swapchainCreateInfo.height();

                    check(xrEnumerateSwapchainImages(swapchainWrapper.handle, pi, null));
                    int imageCount = pi.get(0);

                    XrSwapchainImageOpenGLKHR.Buffer swapchainImageBuffer = XRHelper.fill(
                        XrSwapchainImageOpenGLKHR.calloc(imageCount),
                        XrSwapchainImageOpenGLKHR.TYPE,
                        XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_KHR
                    );

                    check(xrEnumerateSwapchainImages(swapchainWrapper.handle, pi, XrSwapchainImageBaseHeader.create(swapchainImageBuffer)));
                    swapchainWrapper.images = swapchainImageBuffer;
                    swapchains[i] = swapchainWrapper;
                }
            }
        }
    }

    private void createOpenGLResources() {
        swapchainFramebuffer = glGenFramebuffers();
        depthTextures = new HashMap<>(0);
        for (Swapchain swapchain : swapchains) {
            for (XrSwapchainImageOpenGLKHR swapchainImage : swapchain.images) {
                int texture = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32, swapchain.width, swapchain.height, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer)null);
                depthTextures.put(swapchainImage, texture);
            }
        }
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private boolean pollEvents() {
        glfwPollEvents();
        XrEventDataBaseHeader event = readNextOpenXREvent();
        if (event == null) {
            return false;
        }

        do {
            switch (event.type()) {
                case XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING: {
                    XrEventDataInstanceLossPending instanceLossPending = XrEventDataInstanceLossPending.create(event);
                    System.err.printf("XrEventDataInstanceLossPending by %d\n", instanceLossPending.lossTime());
                    //*requestRestart = true;
                    return true;
                }
                case XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED: {
                    XrEventDataSessionStateChanged sessionStateChangedEvent = XrEventDataSessionStateChanged.create(event);
                    return OpenXRHandleSessionStateChangedEvent(sessionStateChangedEvent/*, requestRestart*/);
                }
                case XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED:
                    break;
                case XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING:
                default: {
                    System.out.printf("Ignoring event type %d\n", event.type());
                    break;
                }
            }
            event = readNextOpenXREvent();
        }
        while (event != null);

        return false;
    }

    private XrEventDataBaseHeader readNextOpenXREvent() {
        // It is sufficient to just clear the XrEventDataBuffer header to
        // XR_TYPE_EVENT_DATA_BUFFER rather than recreate it every time
        eventDataBuffer.clear();
        eventDataBuffer.type$Default();
        int result = xrPollEvent(xrInstance, eventDataBuffer);
        if (result == XR_SUCCESS) {
            XrEventDataBaseHeader header = XrEventDataBaseHeader.create(eventDataBuffer.address());
            if (header.type() == XR_TYPE_EVENT_DATA_EVENTS_LOST) {
                XrEventDataEventsLost dataEventsLost = XrEventDataEventsLost.create(header);
                System.out.printf("%d events lost\n", dataEventsLost.lostEventCount());
            }
            return header;
        }
        if (result == XR_EVENT_UNAVAILABLE) {
            return null;
        }
        throw new IllegalStateException(String.format("[XrResult failure %d in xrPollEvent]", result));
    }

    boolean OpenXRHandleSessionStateChangedEvent(XrEventDataSessionStateChanged stateChangedEvent) {
        int oldState = sessionState;
        sessionState = stateChangedEvent.state();

        System.out.printf("XrEventDataSessionStateChanged: state %s->%s session=%d time=%d\n", oldState, sessionState, stateChangedEvent.session(), stateChangedEvent.time());

        if ((stateChangedEvent.session() != NULL) && (stateChangedEvent.session() != xrSession.address())) {
            System.err.println("XrEventDataSessionStateChanged for unknown session");
            return false;
        }

        switch (sessionState) {
            case XR_SESSION_STATE_READY: {
                assert (xrSession != null);
                try (MemoryStack stack = stackPush()) {
                    check(xrBeginSession(
                        xrSession,
                        XrSessionBeginInfo.malloc(stack)
                            .type$Default()
                            .next(NULL)
                            .primaryViewConfigurationType(viewConfigType)
                    ));
                    sessionRunning = true;
                    return false;
                }
            }
            case XR_SESSION_STATE_STOPPING: {
                assert (xrSession != null);
                sessionRunning = false;
                check(xrEndSession(xrSession));
                return false;
            }
            case XR_SESSION_STATE_EXITING: {
                // Do not attempt to restart because user closed this session.
                //*requestRestart = false;
                return true;
            }
            case XR_SESSION_STATE_LOSS_PENDING: {
                // Poll for a new instance.
                //*requestRestart = true;
                return true;
            }
            default:
                return false;
        }
    }

    private void renderFrameOpenXR() {
        try (MemoryStack stack = stackPush()) {
            XrFrameState frameState = XrFrameState.calloc(stack)
                .type$Default();

            check(xrWaitFrame(
                xrSession,
                XrFrameWaitInfo.calloc(stack)
                    .type$Default(),
                frameState
            ));

            check(xrBeginFrame(
                xrSession,
                XrFrameBeginInfo.calloc(stack)
                    .type$Default()
            ));

            XrCompositionLayerProjection layerProjection = XrCompositionLayerProjection.calloc(stack)
                .type$Default();

            PointerBuffer layers = stack.callocPointer(1);

            boolean didRender = false;
            if (frameState.shouldRender()) {
                if (renderLayerOpenXR(stack, frameState.predictedDisplayTime(), layerProjection)) {
                    layers.put(0, layerProjection);
                    didRender = true;
                } else {
                    System.out.println("Didn't render");
                }
            } else {
                System.out.println("Shouldn't render");
            }

            check(xrEndFrame(
                xrSession,
                XrFrameEndInfo.malloc(stack)
                    .type$Default()
                    .next(NULL)
                    .displayTime(frameState.predictedDisplayTime())
                    .environmentBlendMode(XR_ENVIRONMENT_BLEND_MODE_OPAQUE)
                    .layers(didRender ? layers : null)
                    .layerCount(didRender ? layers.remaining() : 0)
            ));
        }
    }

    private boolean renderLayerOpenXR(MemoryStack stack, long predictedDisplayTime, XrCompositionLayerProjection layer) {
        XrViewState viewState = XrViewState.calloc(stack)
            .type$Default();

        IntBuffer pi = stack.mallocInt(1);
        check(xrLocateViews(
            xrSession,
            XrViewLocateInfo.malloc(stack)
                .type$Default()
                .next(NULL)
                .viewConfigurationType(viewConfigType)
                .displayTime(predictedDisplayTime)
                .space(xrAppSpace),
            viewState,
            pi,
            views
        ));

        if ((viewState.viewStateFlags() & XR_VIEW_STATE_POSITION_VALID_BIT) == 0 ||
            (viewState.viewStateFlags() & XR_VIEW_STATE_ORIENTATION_VALID_BIT) == 0) {
            return false;  // There is no valid tracking poses for the views.
        }

        int viewCountOutput = pi.get(0);
        assert (viewCountOutput == views.capacity());
        assert (viewCountOutput == viewConfigs.capacity());
        assert (viewCountOutput == swapchains.length);

        XrCompositionLayerProjectionView.Buffer projectionLayerViews = XRHelper.fill(
            XrCompositionLayerProjectionView.calloc(viewCountOutput, stack),
            XrCompositionLayerProjectionView.TYPE,
            XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW
        );

        // Render view to the appropriate part of the swapchain image.
        for (int viewIndex = 0; viewIndex < viewCountOutput; viewIndex++) {
            // Each view has a separate swapchain which is acquired, rendered to, and released.
            Swapchain viewSwapchain = swapchains[viewIndex];

            check(xrAcquireSwapchainImage(
                viewSwapchain.handle,
                XrSwapchainImageAcquireInfo.calloc(stack)
                    .type$Default(),
                pi
            ));
            int swapchainImageIndex = pi.get(0);

            check(xrWaitSwapchainImage(
                viewSwapchain.handle,
                XrSwapchainImageWaitInfo.malloc(stack)
                    .type$Default()
                    .next(NULL)
                    .timeout(XR_INFINITE_DURATION)
            ));

            XrCompositionLayerProjectionView projectionLayerView = projectionLayerViews.get(viewIndex)
                .pose(views.get(viewIndex).pose())
                .fov(views.get(viewIndex).fov())
                .subImage(si -> si
                    .swapchain(viewSwapchain.handle)
                    .imageRect(rect -> rect
                        .offset(offset -> offset
                            .x(0)
                            .y(0))
                        .extent(extent -> extent
                            .width(viewSwapchain.width)
                            .height(viewSwapchain.height)
                        )));

            OpenGLRenderView(projectionLayerView, viewSwapchain.images.get(swapchainImageIndex), viewIndex);

            check(xrReleaseSwapchainImage(
                viewSwapchain.handle,
                XrSwapchainImageReleaseInfo.calloc(stack)
                    .type$Default()
            ));
        }

        layer.space(xrAppSpace);
        layer.views(projectionLayerViews);
        return true;
    }

    private static Matrix4f projectionMatrix = new Matrix4f(); //TODO: Do we need this?
    private static Matrix4f viewMatrix       = new Matrix4f(); //TODO: Do we need this?
    private static com.jme3.math.Vector3f viewPos = new com.jme3.math.Vector3f();
    private static com.jme3.math.Quaternion viewRot = new com.jme3.math.Quaternion();

    private void OpenGLRenderView(XrCompositionLayerProjectionView layerView, XrSwapchainImageOpenGLKHR swapchainImage, int viewIndex) {
        glBindFramebuffer(GL_FRAMEBUFFER, swapchainFramebuffer);

        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, swapchainImage.image(), 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTextures.get(swapchainImage), 0);

        XrRect2Di imageRect = layerView.subImage().imageRect();
        glViewport(
            imageRect.offset().x(),
            imageRect.offset().y(),
            imageRect.extent().width(),
            imageRect.extent().height()
        );
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

        glFrontFace(GL_CCW);
        glCullFace(GL_BACK);
        glEnable(GL_DEPTH_TEST);

        XrPosef       pose        = layerView.pose();
        XrVector3f    pos         = pose.position$();
        XrQuaternionf orientation = pose.orientation();
        XRHelper.applyProjectionToMatrix(projectionMatrix.identity(), layerView.fov(), 0.1f, 100f, false);
        viewMatrix.translationRotateScaleInvert(
            pos.x(), pos.y(), pos.z(),
            orientation.x(), orientation.y(), orientation.z(), orientation.w(),
            1, 1, 1
        );
        viewPos.set(pos.x(), pos.y(), pos.z());
        viewRot.set(orientation.x(), orientation.y(), orientation.z(), orientation.w());
        xrHmd.onUpdateHmdOrientation(viewPos, viewRot);

        glDisable(GL_CULL_FACE); // Disable back-face culling so we can see the inside of the world-space cube and backside of the plane

        if (viewIndex == 0) { xrHmd.getLeftEye().render(); }
        else if (viewIndex == 1) { xrHmd.getRightEye().render(); }

        glEnable(GL_CULL_FACE);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        if (viewIndex == swapchains.length - 1) {
            glFlush();
        }
    }

    public void check(int result) throws IllegalStateException {
        if (XR_SUCCEEDED(result)) {
            return;
        }

        if (xrInstance != null) {
            ByteBuffer str = stackMalloc(XR_MAX_RESULT_STRING_SIZE);
            if (xrResultToString(xrInstance, result, str) >= 0) {
                throw new XrResultException(memUTF8(str, memLengthNT1(str)));
            }
        }

        throw new XrResultException("XR method returned " + result);
    }

    @SuppressWarnings("serial")
    public static class XrResultException extends RuntimeException {
        public XrResultException(String s) {
            super(s);
        }
    }

}
