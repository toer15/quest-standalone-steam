#include <jni.h>
#include <android/log.h>
#include <android_native_app_glue.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include "qss_files.hpp"
#include "qss_scene.hpp"
#include <algorithm>
#include <chrono>
#include <deque>
#include <sstream>
#include <thread>

namespace {
void check(XrResult r,const char* op) {
    if(XR_FAILED(r)) throw std::runtime_error(std::string(op)+" failed: "+std::to_string(r));
}
void require(bool good,const char* text) { if(!good) throw std::runtime_error(text); }
template<class T> T function(XrInstance instance,const char* name) {
    PFN_xrVoidFunction f=nullptr; check(xrGetInstanceProcAddr(instance,name,&f),name); return reinterpret_cast<T>(f);
}
struct Eye {
    XrSwapchain swap=XR_NULL_HANDLE;
    int32_t width=0,height=0;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
};
struct SavedTracking {
    qss::Tracking tracking;
    std::chrono::steady_clock::time_point when;
};
struct Host {
    android_app* app;
    JNIEnv* env=nullptr;
    bool resumed=false, running=false, focused=false, exiting=false;
    bool guest=false;
    std::string directory;
    XrInstance instance=XR_NULL_HANDLE;
    XrSystemId system=XR_NULL_SYSTEM_ID;
    XrSession session=XR_NULL_HANDLE;
    XrSpace local=XR_NULL_HANDLE;
    XrActionSet actions=XR_NULL_HANDLE;
    XrAction aim=XR_NULL_HANDLE,trigger=XR_NULL_HANDLE,exitAction=XR_NULL_HANDLE;
    std::array<XrPath,2> handPaths{};
    std::array<XrSpace,2> handSpaces{};
    EGLDisplay display=EGL_NO_DISPLAY;
    EGLContext context=EGL_NO_CONTEXT;
    EGLSurface surface=EGL_NO_SURFACE;
    std::array<Eye,2> eyes;
    GLuint texture=0,framebuffer=0,program=0,vao=0;
    std::deque<SavedTracking> history;
    qss::Scene scene;
    uint64_t sequence=0,submitted=0,lastGuestSequence=0,drawn=0,stale=0;
    std::string problem;
    explicit Host(android_app* a):app(a) {}
    void report(const std::string& s) {
        __android_log_print(ANDROID_LOG_INFO,"QssXrLab","%s",s.c_str());
        jclass cls=env->GetObjectClass(app->activity->clazz);
        jmethodID method=env->GetMethodID(cls,"onNativeReport","(Ljava/lang/String;)V");
        if(method) { jstring text=env->NewStringUTF(s.c_str()); env->CallVoidMethod(app->activity->clazz,method,text); env->DeleteLocalRef(text); }
        if(env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(cls);
    }
    void stats() {
        std::ostringstream s;
        s<<"QSS VR lab — "<<(guest?"Windows guest":"native diagnostic")
         <<"\nNative frames: "<<drawn<<"\nGuest frames submitted: "<<submitted
         <<"\nLast guest sequence: "<<lastGuestSequence<<"\nRejected stale frames: "<<stale
         <<"\nResolution: 192 x 192 per eye (CPU test; not a performance target)"
         <<"\nTarget score: "<<(guest?"see guest logic":std::to_string(scene.score))
         <<"\n"<<problem;
        report(s.str());
    }
    XrPath path(const char* p) { XrPath result; check(xrStringToPath(instance,p,&result),"xrStringToPath"); return result; }
    void init() {
        require(app->activity->vm->AttachCurrentThread(&env,nullptr)==JNI_OK,"Cannot attach native thread");
        jclass cls=env->GetObjectClass(app->activity->clazz);
        auto allowed=env->GetMethodID(cls,"getSessionAllowed","()Z");
        require(allowed && env->CallBooleanMethod(app->activity->clazz,allowed),"Another game session is already open");
        auto getDir=env->GetMethodID(cls,"getBridgeDirectory","()Ljava/lang/String;");
        auto getGuest=env->GetMethodID(cls,"getGuestMode","()Z");
        require(getDir && getGuest,"Missing VR activity configuration");
        auto text=static_cast<jstring>(env->CallObjectMethod(app->activity->clazz,getDir));
        require(text!=nullptr,"Missing session directory");
        const char* raw=env->GetStringUTFChars(text,nullptr); directory=raw;
        env->ReleaseStringUTFChars(text,raw); env->DeleteLocalRef(text);
        guest=env->CallBooleanMethod(app->activity->clazz,getGuest); env->DeleteLocalRef(cls);
        auto initialize=function<PFN_xrInitializeLoaderKHR>(XR_NULL_HANDLE,"xrInitializeLoaderKHR");
        XrLoaderInitInfoAndroidKHR loader{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        loader.applicationVM=app->activity->vm; loader.applicationContext=app->activity->clazz;
        check(initialize(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR*>(&loader)),"xrInitializeLoaderKHR");
        const char* extensions[]={XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
        XrInstanceCreateInfoAndroidKHR android{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
        android.applicationVM=app->activity->vm; android.applicationActivity=app->activity->clazz;
        XrInstanceCreateInfo create{XR_TYPE_INSTANCE_CREATE_INFO}; create.next=&android;
        std::strcpy(create.applicationInfo.applicationName,"QSS VR lab");
        create.applicationInfo.apiVersion=XR_API_VERSION_1_0;
        create.enabledExtensionCount=2; create.enabledExtensionNames=extensions;
        check(xrCreateInstance(&create,&instance),"xrCreateInstance");
        XrSystemGetInfo info{XR_TYPE_SYSTEM_GET_INFO}; info.formFactor=XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        check(xrGetSystem(instance,&info,&system),"xrGetSystem");
        XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
        check(function<PFN_xrGetOpenGLESGraphicsRequirementsKHR>(instance,"xrGetOpenGLESGraphicsRequirementsKHR")(instance,system,&requirements),"OpenGL ES requirements");
        display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
        require(display!=EGL_NO_DISPLAY && eglInitialize(display,nullptr,nullptr),"EGL initialization failed");
        const EGLint attributes[]={EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT_KHR,EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,
            EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
        EGLConfig config{}; EGLint count=0;
        require(eglChooseConfig(display,attributes,&config,1,&count) && count==1,"No EGL configuration");
        const EGLint ctx[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
        context=eglCreateContext(display,config,EGL_NO_CONTEXT,ctx);
        const EGLint pb[]={EGL_WIDTH,16,EGL_HEIGHT,16,EGL_NONE};
        surface=eglCreatePbufferSurface(display,config,pb);
        require(context!=EGL_NO_CONTEXT && surface!=EGL_NO_SURFACE && eglMakeCurrent(display,surface,surface,context),"EGL context failed");
        GLint major=0,minor=0; glGetIntegerv(GL_MAJOR_VERSION,&major); glGetIntegerv(GL_MINOR_VERSION,&minor);
        auto version=XR_MAKE_VERSION(major,minor,0);
        require(version>=requirements.minApiVersionSupported && version<=requirements.maxApiVersionSupported,"Unsupported GLES version");
        XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
        binding.display=display; binding.config=config; binding.context=context;
        XrSessionCreateInfo sci{XR_TYPE_SESSION_CREATE_INFO}; sci.next=&binding; sci.systemId=system;
        check(xrCreateSession(instance,&sci,&session),"xrCreateSession");
        XrReferenceSpaceCreateInfo space{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
        space.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_LOCAL; space.poseInReferenceSpace.orientation.w=1;
        check(xrCreateReferenceSpace(session,&space,&local),"xrCreateReferenceSpace");
        createActions(); createEyes(); createRenderer();
        report("Native OpenXR session created. Waiting for runtime READY. Press B to leave the lab.");
    }
    void createActions() {
        XrActionSetCreateInfo set{XR_TYPE_ACTION_SET_CREATE_INFO};
        std::strcpy(set.actionSetName,"lab"); std::strcpy(set.localizedActionSetName,"QSS lab");
        check(xrCreateActionSet(instance,&set,&actions),"xrCreateActionSet");
        handPaths={path("/user/hand/left"),path("/user/hand/right")};
        auto make=[&](const char* name,XrActionType type,XrAction* out) {
            XrActionCreateInfo a{XR_TYPE_ACTION_CREATE_INFO}; std::strcpy(a.actionName,name); std::strcpy(a.localizedActionName,name);
            a.actionType=type; a.countSubactionPaths=2; a.subactionPaths=handPaths.data();
            check(xrCreateAction(actions,&a,out),"xrCreateAction");
        };
        make("aim",XR_ACTION_TYPE_POSE_INPUT,&aim); make("trigger",XR_ACTION_TYPE_FLOAT_INPUT,&trigger);
        XrActionCreateInfo a{XR_TYPE_ACTION_CREATE_INFO}; std::strcpy(a.actionName,"leave"); std::strcpy(a.localizedActionName,"Leave lab");
        a.actionType=XR_ACTION_TYPE_BOOLEAN_INPUT; check(xrCreateAction(actions,&a,&exitAction),"Create leave action");
        std::vector<XrActionSuggestedBinding> bindings;
        for(auto hand:{std::string("left"),std::string("right")}) {
            std::string base="/user/hand/"+hand+"/input/";
            bindings.push_back({aim,path((base+"aim/pose").c_str())});
            bindings.push_back({trigger,path((base+"trigger/value").c_str())});
        }
        bindings.push_back({exitAction,path("/user/hand/right/input/b/click")});
        XrInteractionProfileSuggestedBinding b{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
        b.interactionProfile=path("/interaction_profiles/oculus/touch_controller");
        b.countSuggestedBindings=uint32_t(bindings.size()); b.suggestedBindings=bindings.data();
        check(xrSuggestInteractionProfileBindings(instance,&b),"Touch controller bindings");
        for(unsigned i=0;i<2;++i) {
            XrActionSpaceCreateInfo space{XR_TYPE_ACTION_SPACE_CREATE_INFO}; space.action=aim;
            space.subactionPath=handPaths[i]; space.poseInActionSpace.orientation.w=1;
            check(xrCreateActionSpace(session,&space,&handSpaces[i]),"xrCreateActionSpace");
        }
        XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO}; attach.countActionSets=1; attach.actionSets=&actions;
        check(xrAttachSessionActionSets(session,&attach),"xrAttachSessionActionSets");
    }
    void createEyes() {
        uint32_t count=0; check(xrEnumerateViewConfigurationViews(instance,system,XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,0,&count,nullptr),"Count stereo views");
        require(count==2,"Expected exactly two stereo views");
        std::vector<XrViewConfigurationView> views(count,{XR_TYPE_VIEW_CONFIGURATION_VIEW});
        check(xrEnumerateViewConfigurationViews(instance,system,XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,count,&count,views.data()),"Stereo views");
        check(xrEnumerateSwapchainFormats(session,0,&count,nullptr),"Count formats");
        std::vector<int64_t> formats(count); check(xrEnumerateSwapchainFormats(session,count,&count,formats.data()),"Swapchain formats");
        int64_t format=GL_RGBA8;
        if(std::find(formats.begin(),formats.end(),format)==formats.end()) format=GL_SRGB8_ALPHA8;
        require(std::find(formats.begin(),formats.end(),format)!=formats.end(),"No RGBA swapchain format");
        for(unsigned i=0;i<2;++i) {
            auto& e=eyes[i]; e.width=int32_t(std::min(views[i].recommendedImageRectWidth,1024u));
            e.height=int32_t(std::min(views[i].recommendedImageRectHeight,1024u));
            XrSwapchainCreateInfo create{XR_TYPE_SWAPCHAIN_CREATE_INFO}; create.usageFlags=XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
            create.format=format; create.sampleCount=1; create.width=e.width; create.height=e.height; create.faceCount=1; create.arraySize=1; create.mipCount=1;
            check(xrCreateSwapchain(session,&create,&e.swap),"xrCreateSwapchain");
            check(xrEnumerateSwapchainImages(e.swap,0,&count,nullptr),"Count swapchain images");
            e.images.resize(count,{XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
            check(xrEnumerateSwapchainImages(e.swap,count,&count,reinterpret_cast<XrSwapchainImageBaseHeader*>(e.images.data())),"Swapchain images");
        }
    }
    GLuint shader(GLenum type,const char* text) {
        GLuint s=glCreateShader(type); glShaderSource(s,1,&text,nullptr); glCompileShader(s);
        GLint ok; glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
        if(!ok) { glDeleteShader(s); throw std::runtime_error("Lab blit shader failed to compile"); } return s;
    }
    void createRenderer() {
        const char* vs="#version 300 es\nout vec2 uv; void main(){ vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2)); uv=p; gl_Position=vec4(p*2.0-1.0,0,1); }";
        const char* fs="#version 300 es\nprecision highp float; in vec2 uv; uniform sampler2D image; out vec4 color; void main(){ color=texture(image,vec2(uv.x,1.0-uv.y)); }";
        auto v=shader(GL_VERTEX_SHADER,vs),f=shader(GL_FRAGMENT_SHADER,fs);
        program=glCreateProgram(); glAttachShader(program,v); glAttachShader(program,f); glLinkProgram(program);
        glDeleteShader(v); glDeleteShader(f); GLint ok; glGetProgramiv(program,GL_LINK_STATUS,&ok); require(ok,"Lab blit shader failed to link");
        glGenTextures(1,&texture); glBindTexture(GL_TEXTURE_2D,texture);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glGenFramebuffers(1,&framebuffer); glGenVertexArrays(1,&vao);
    }
    void events() {
        XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
        while(true) {
            auto result=xrPollEvent(instance,&event);
            if(result==XR_EVENT_UNAVAILABLE) break;
            check(result,"xrPollEvent");
            if(event.type==XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                const auto state=reinterpret_cast<XrEventDataSessionStateChanged*>(&event)->state;
                focused=state==XR_SESSION_STATE_FOCUSED;
                if(state==XR_SESSION_STATE_READY) {
                    XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO}; begin.primaryViewConfigurationType=XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                    check(xrBeginSession(session,&begin),"xrBeginSession"); running=true;
                } else if(state==XR_SESSION_STATE_STOPPING) { check(xrEndSession(session),"xrEndSession"); running=false; history.clear(); }
                else if(state==XR_SESSION_STATE_EXITING || state==XR_SESSION_STATE_LOSS_PENDING) exiting=true;
            } else if(event.type==XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) exiting=true;
            else if(event.type==XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING) history.clear();
            event={XR_TYPE_EVENT_DATA_BUFFER};
        }
    }
    qss::Tracking locate(const XrFrameState& frame,const std::array<XrView,2>& views) {
        qss::Tracking t; t.sequence=++sequence; t.focused=focused && resumed;
        for(unsigned i=0;i<2;++i) {
            const auto& p=views[i].pose; const auto& f=views[i].fov;
            t.eyes[i].pose={{p.orientation.x,p.orientation.y,p.orientation.z,p.orientation.w},{p.position.x,p.position.y,p.position.z}};
            t.eyes[i].left=f.angleLeft; t.eyes[i].right=f.angleRight; t.eyes[i].up=f.angleUp; t.eyes[i].down=f.angleDown;
        }
        if(t.focused) {
            XrActiveActionSet active{actions,XR_NULL_PATH}; XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO}; sync.countActiveActionSets=1; sync.activeActionSets=&active;
            auto synced=xrSyncActions(session,&sync);
            check(synced,"xrSyncActions");
            if(synced==XR_SUCCESS) for(unsigned i=0;i<2;++i) {
                XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO}; get.action=aim; get.subactionPath=handPaths[i];
                XrActionStatePose state{XR_TYPE_ACTION_STATE_POSE}; check(xrGetActionStatePose(session,&get,&state),"Aim action state");
                XrSpaceLocation position{XR_TYPE_SPACE_LOCATION};
                if(state.isActive) check(xrLocateSpace(handSpaces[i],local,frame.predictedDisplayTime,&position),"Aim pose");
                auto valid=XR_SPACE_LOCATION_POSITION_VALID_BIT|XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
                if(state.isActive && (position.locationFlags&valid)==valid) {
                    auto& p=position.pose; auto& h=t.hands[i]; h.active=true;
                    h.aim={{p.orientation.x,p.orientation.y,p.orientation.z,p.orientation.w},{p.position.x,p.position.y,p.position.z}};
                    get.action=trigger; XrActionStateFloat value{XR_TYPE_ACTION_STATE_FLOAT}; check(xrGetActionStateFloat(session,&get,&value),"Trigger action state");
                    if(value.isActive) h.trigger=value.currentState;
                }
            }
            if(synced==XR_SUCCESS) {
                XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO}; get.action=exitAction;
                XrActionStateBoolean b{XR_TYPE_ACTION_STATE_BOOLEAN}; check(xrGetActionStateBoolean(session,&get,&b),"Leave action state");
                if(b.isActive && b.currentState && b.changedSinceLastSync) exiting=true;
            }
        }
        return t;
    }
    void renderEye(unsigned i,const qss::Frame& f) {
        auto& e=eyes[i]; uint32_t index;
        XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
        check(xrAcquireSwapchainImage(e.swap,&acquire,&index),"Acquire eye image");
        XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO}; wait.timeout=XR_INFINITE_DURATION;
        check(xrWaitSwapchainImage(e.swap,&wait),"Wait for eye image");
        glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,e.images.at(index).image,0);
        require(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"Incomplete eye framebuffer");
        glViewport(0,0,e.width,e.height); glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glDisable(GL_BLEND); glDisable(GL_SCISSOR_TEST);
        glUseProgram(program); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,texture);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,GLsizei(f.width),GLsizei(f.height),0,GL_RGBA,GL_UNSIGNED_BYTE,
            f.rgba.data()+size_t(i)*f.width*f.height*4);
        glUniform1i(glGetUniformLocation(program,"image"),0); glBindVertexArray(vao); glDrawArrays(GL_TRIANGLES,0,3);
        glFlush(); require(glGetError()==GL_NO_ERROR,"OpenGL eye upload failed");
        XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        check(xrReleaseSwapchainImage(e.swap,&release),"Release eye image");
    }
    void frame() {
        XrFrameWaitInfo wait{XR_TYPE_FRAME_WAIT_INFO}; XrFrameState state{XR_TYPE_FRAME_STATE};
        check(xrWaitFrame(session,&wait,&state),"xrWaitFrame");
        XrFrameBeginInfo begin{XR_TYPE_FRAME_BEGIN_INFO}; check(xrBeginFrame(session,&begin),"xrBeginFrame");
        std::array<XrCompositionLayerProjectionView,2> projection{{{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW},{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}}};
        XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION}; layer.space=local;
        bool haveFrame=false;
        if(state.shouldRender) {
            XrViewLocateInfo info{XR_TYPE_VIEW_LOCATE_INFO}; info.viewConfigurationType=XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            info.displayTime=state.predictedDisplayTime; info.space=local;
            XrViewState valid{XR_TYPE_VIEW_STATE}; std::array<XrView,2> views{{{XR_TYPE_VIEW},{XR_TYPE_VIEW}}}; uint32_t count=0;
            check(xrLocateViews(session,&info,&valid,2,&count,views.data()),"xrLocateViews");
            auto flags=XR_VIEW_STATE_POSITION_VALID_BIT|XR_VIEW_STATE_ORIENTATION_VALID_BIT;
            if(count==2 && (valid.viewStateFlags&flags)==flags) {
                auto t=locate(state,views); qss::Frame pixels;
                if(guest) {
                    auto now=std::chrono::steady_clock::now();
                    history.push_back({t,now}); while(history.size()>64) history.pop_front();
                    if(!qss::writeFile(directory+"/tracking.qss",qss::encode(t))) throw std::runtime_error("Cannot publish tracking");
                    qss::Bytes bytes;
                    if(qss::readFile(directory+"/frame.qss",bytes)) try {
                        pixels=qss::frame(bytes);
                        auto old=std::find_if(history.begin(),history.end(),[&](const SavedTracking& s){return s.tracking.sequence==pixels.sequence;});
                        if(old!=history.end() && now-old->when<std::chrono::milliseconds(200)) {
                            // Submit the ORIGINAL source poses/FOV. Runtime reprojection must not be lied to.
                            t=old->tracking; haveFrame=true;
                        } else { ++stale; problem="Guest frame is stale; no guest image submitted."; }
                    } catch(const std::exception& e) { problem=e.what(); }
                } else { scene.update(t); pixels=scene.render(t); haveFrame=true; }
                if(haveFrame) for(unsigned i=0;i<2;++i) {
                    renderEye(i,pixels); const auto& v=t.eyes[i]; auto& p=projection[i];
                    p.pose={{v.pose.orientation.x,v.pose.orientation.y,v.pose.orientation.z,v.pose.orientation.w},
                        {v.pose.position.x,v.pose.position.y,v.pose.position.z}};
                    p.fov={v.left,v.right,v.up,v.down}; p.subImage.swapchain=eyes[i].swap;
                    p.subImage.imageRect={{0,0},{eyes[i].width,eyes[i].height}};
                }
                if(haveFrame && guest) { ++submitted; lastGuestSequence=pixels.sequence; problem="Guest stereo frame received; game compatibility is still unproven."; }
            }
        }
        layer.viewCount=2; layer.views=projection.data();
        const auto* base=reinterpret_cast<const XrCompositionLayerBaseHeader*>(&layer);
        XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO}; end.displayTime=state.predictedDisplayTime;
        end.environmentBlendMode=XR_ENVIRONMENT_BLEND_MODE_OPAQUE; end.layerCount=haveFrame?1:0; end.layers=haveFrame?&base:nullptr;
        check(xrEndFrame(session,&end),"xrEndFrame"); ++drawn;
        if(drawn%120==0) stats();
    }
    void close() noexcept {
        try { qss::writeFile(directory+"/stop",{1}); } catch(...) {}
        if(env) { try { stats(); } catch(...) {} }
        if(display!=EGL_NO_DISPLAY && context!=EGL_NO_CONTEXT) {
            if(program) glDeleteProgram(program);
            if(texture) glDeleteTextures(1,&texture);
            if(framebuffer) glDeleteFramebuffers(1,&framebuffer);
            if(vao) glDeleteVertexArrays(1,&vao);
        }
        for(auto& eye:eyes) if(eye.swap) xrDestroySwapchain(eye.swap);
        for(auto space:handSpaces) if(space) xrDestroySpace(space);
        if(local) xrDestroySpace(local);
        if(session) xrDestroySession(session);
        if(actions) xrDestroyActionSet(actions);
        if(instance) xrDestroyInstance(instance);
        if(display!=EGL_NO_DISPLAY) {
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
            if(surface!=EGL_NO_SURFACE) eglDestroySurface(display,surface);
            if(context!=EGL_NO_CONTEXT) eglDestroyContext(display,context);
            eglTerminate(display);
        }
        if(env) { app->activity->vm->DetachCurrentThread(); env=nullptr; }
    }
};
void command(android_app* app,int32_t cmd) {
    auto* h=static_cast<Host*>(app->userData);
    if(cmd==APP_CMD_RESUME) h->resumed=true;
    if(cmd==APP_CMD_PAUSE) h->resumed=false;
}
int32_t input(android_app* app,AInputEvent* e) {
    if(AInputEvent_getType(e)==AINPUT_EVENT_TYPE_KEY && AKeyEvent_getKeyCode(e)==AKEYCODE_BACK && AKeyEvent_getAction(e)==AKEY_EVENT_ACTION_UP) {
        static_cast<Host*>(app->userData)->exiting=true; return 1;
    }
    return 0;
}
void pump(android_app* app,int timeout) {
    int events; android_poll_source* source=nullptr;
    if(ALooper_pollOnce(timeout,nullptr,&events,reinterpret_cast<void**>(&source))>=0 && source) source->process(app,source);
}
} // namespace
extern "C" void android_main(android_app* app) {
    app_dummy(); Host host(app); app->userData=&host; app->onAppCmd=command; app->onInputEvent=input;
    try {
        while(!app->destroyRequested && (!app->window || !host.resumed)) pump(app,-1);
        if(!app->destroyRequested) {
            host.init();
            while(!app->destroyRequested && !host.exiting) {
                pump(app,host.running?0:10); host.events();
                if(host.running && !host.exiting) host.frame();
            }
        }
    } catch(const std::exception& e) { host.problem=e.what(); __android_log_print(ANDROID_LOG_ERROR,"QssXrLab","%s",e.what()); }
    host.close();
    ANativeActivity_finish(app->activity);
    // NativeActivity owns the glue thread. Continue servicing destroy so Java can return safely.
    while(!app->destroyRequested) pump(app,100);
}
