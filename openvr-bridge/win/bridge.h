// Windows side of the Quest bridge: openvr_api.dll replacement that runs inside Wine/Box64.
// It talks to the Quest app's OpenXR activity through shared memory (qss_vr_shm.h).
#pragma once

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <string>

#include "openvr.h"
#include "qss_vr_shm.h"

namespace qss {

void Log(const char* fmt, ...);

/** Connection to the shared memory file. */
struct Shm {
    QssVrShm* s = nullptr;
    bool Open();                       // maps QSS_VR_SHM; returns false if unavailable
    bool ReadPose(QssPoseState* out);  // seqlock read; false if never written
    bool WaitNewPose(uint64_t* lastSeq, QssPoseState* out, DWORD timeoutMs);
};
extern Shm g_shm;

// Device indices we expose: HMD + two Touch controllers.
constexpr vr::TrackedDeviceIndex_t kHmd = 0, kLeft = 1, kRight = 2, kDeviceCount = 3;

/** Latest pose seen (updated by WaitGetPoses / pose reads). */
extern QssPoseState g_pose;
/** Pose returned by the last WaitGetPoses; frames are tagged with it. */
extern QssPoseState g_renderPose;
extern vr::ETrackingUniverseOrigin g_origin;
extern float g_seatedOrigin[3];

void RefreshPose();  // reads the newest pose into g_pose (non-blocking)

// Math helpers.
vr::HmdMatrix34_t PoseToMatrix(const QssPose& p);
QssPose RelativePose(const QssPose& parent, const QssPose& child);  // parent^-1 * child
void ToUniverse(QssPose& p, vr::ETrackingUniverseOrigin origin);
void FillDevicePose(vr::TrackedDevicePose_t* out, vr::TrackedDeviceIndex_t idx, const QssPoseState& st,
                    vr::ETrackingUniverseOrigin origin);
vr::TrackedDeviceIndex_t HandToDevice(int hand);
int DeviceToHand(vr::TrackedDeviceIndex_t idx);  // -1 for HMD/unknown

uint32_t CopyString(const char* src, char* dst, uint32_t size);

// Interfaces.
vr::IVRSystem* System();
vr::IVRCompositor* Compositor();
vr::IVRInput* Input();
vr::IVRChaperone* Chaperone();
bool InputUsesActions();
void InputLegacyButtons(int hand, uint64_t* pressed, uint64_t* touched, vr::VRControllerAxis_t axes[5]);

/** Queue an OpenVR event for PollNextEvent. */
void PushEvent(vr::EVREventType type, vr::TrackedDeviceIndex_t device, uint32_t button = 0);
/** Emits button press/release events for legacy-input games (called once per frame). */
void UpdateLegacyButtonEvents();

}  // namespace qss
