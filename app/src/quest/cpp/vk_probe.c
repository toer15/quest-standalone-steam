// Asks the headset's Vulkan driver for its real version and GPU name. Android's feature flag
// (FEATURE_VULKAN_HARDWARE_VERSION) can report less than the driver supports, and DXVK 2.x
// (which needs Vulkan 1.3) is chosen from this, like Winlator does.
#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

JNIEXPORT jstring JNICALL
Java_com_toer15_queststeam_display_VkProbe_probe(JNIEnv *env, jobject obj) {
    void *lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) return NULL;
    PFN_vkGetInstanceProcAddr gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
    if (!gipa) { dlclose(lib); return NULL; }
    PFN_vkCreateInstance createInstance = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
    PFN_vkEnumerateInstanceVersion enumVersion = (PFN_vkEnumerateInstanceVersion)gipa(NULL, "vkEnumerateInstanceVersion");
    uint32_t instanceVersion = VK_API_VERSION_1_0;
    if (enumVersion) enumVersion(&instanceVersion);

    VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .pApplicationName = "qss-probe",
                              .apiVersion = instanceVersion };
    VkInstanceCreateInfo ci = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app };
    VkInstance instance = VK_NULL_HANDLE;
    char out[512] = {0};
    if (createInstance && createInstance(&ci, NULL, &instance) == VK_SUCCESS) {
        PFN_vkEnumeratePhysicalDevices enumDevices = (PFN_vkEnumeratePhysicalDevices)gipa(instance, "vkEnumeratePhysicalDevices");
        PFN_vkGetPhysicalDeviceProperties getProps = (PFN_vkGetPhysicalDeviceProperties)gipa(instance, "vkGetPhysicalDeviceProperties");
        PFN_vkDestroyInstance destroyInstance = (PFN_vkDestroyInstance)gipa(instance, "vkDestroyInstance");
        uint32_t count = 1;
        VkPhysicalDevice device = VK_NULL_HANDLE;
        if (enumDevices && getProps && enumDevices(instance, &count, &device) >= 0 && count > 0 && device) {
            VkPhysicalDeviceProperties props;
            getProps(device, &props);
            snprintf(out, sizeof(out), "%u|%u|%s", props.apiVersion, instanceVersion, props.deviceName);
        }
        if (destroyInstance) destroyInstance(instance, NULL);
    }
    dlclose(lib);
    return out[0] ? (*env)->NewStringUTF(env, out) : NULL;
}
