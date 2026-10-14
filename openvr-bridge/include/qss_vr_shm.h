// Shared memory between the game (Windows openvr_api.dll inside Wine/Box64, x86_64)
// and the Quest app's OpenXR activity (Android arm64). Both map the same file:
//   <app files dir>/rootfs/tmp/vrbridge.shm   (path passed to the game in QSS_VR_SHM)
// Plain fixed-size C types only; layout must match on both sides.
//
// Flow per frame:
//   Quest  : xrWaitFrame -> locate head/eyes/controllers -> write `pose` (seqlock) -> pose_seq++
//   Game   : WaitGetPoses waits for a new pose_seq, returns it; renders; Submit(left/right)
//            copies pixels into frames[n], stores the pose it rendered with, frame_seq++
//   Quest  : sees new frame_seq -> uploads to XrSwapchain -> xrEndFrame with that pose (timewarp)
#ifndef QSS_VR_SHM_H
#define QSS_VR_SHM_H

#include <stdint.h>

#define QSS_VR_MAGIC 0x52565351u /* "QSVR" */
#define QSS_VR_VERSION 1u
#define QSS_VR_MAX_EYE_W 2048u
#define QSS_VR_MAX_EYE_H 2048u
#define QSS_VR_EYE_BYTES (QSS_VR_MAX_EYE_W * QSS_VR_MAX_EYE_H * 4u)

enum {
    QSS_FMT_RGBA8 = 0,
    QSS_FMT_BGRA8 = 1,
};

/* Button bits in QssController.buttons */
enum {
    QSS_BTN_A = 1u << 0,      /* right A / left X */
    QSS_BTN_B = 1u << 1,      /* right B / left Y */
    QSS_BTN_MENU = 1u << 2,   /* left menu button */
    QSS_BTN_STICK = 1u << 3,  /* thumbstick click */
    QSS_BTN_TRIGGER = 1u << 4,
    QSS_BTN_GRIP = 1u << 5,
    QSS_TOUCH_A = 1u << 8,
    QSS_TOUCH_B = 1u << 9,
    QSS_TOUCH_STICK = 1u << 10,
    QSS_TOUCH_TRIGGER = 1u << 11,
};

typedef struct QssPose {
    float pos[3];   /* metres, OpenXR stage space (+Y up, -Z forward) */
    float rot[4];   /* quaternion x, y, z, w */
} QssPose;

typedef struct QssFov {
    float left, right, up, down; /* radians, OpenXR convention (left/down negative) */
} QssFov;

typedef struct QssController {
    uint32_t valid;       /* pose tracked */
    uint32_t buttons;     /* QSS_BTN_* / QSS_TOUCH_* */
    float trigger;        /* 0..1 */
    float grip;           /* 0..1 */
    float stick[2];       /* -1..1 */
    QssPose grip_pose;    /* stage space */
    QssPose aim_pose;     /* stage space */
    float lin_vel[3];
    float ang_vel[3];
} QssController;

typedef struct QssPoseState {
    uint64_t frame_id;        /* increments every XR frame */
    int64_t display_time_ns;  /* XrTime of predicted display */
    QssPose head;             /* stage space */
    QssPose eye[2];           /* stage space */
    QssFov fov[2];
    QssController hand[2];    /* 0 = left, 1 = right */
    float ipd;
    float refresh_hz;
} QssPoseState;

typedef struct QssFrameInfo {
    uint32_t width, height;   /* per eye, <= MAX */
    uint32_t format;          /* QSS_FMT_* */
    uint32_t flip_y;          /* 1 if rows are bottom-up (OpenGL); D3D is top-down = 0 */
    QssPoseState pose;        /* the pose the game rendered this frame with */
} QssFrameInfo;

typedef struct QssVrShm {
    /* ---- header, written by the Quest side at creation ---- */
    uint32_t magic;
    uint32_t version;
    uint32_t total_size;
    uint32_t rec_width, rec_height;   /* recommended per-eye size we tell the game */

    /* ---- Quest -> game ---- */
    volatile uint32_t xr_running;     /* 1 while the OpenXR session is focused */
    volatile uint32_t quest_reading;  /* buffer index + 1 while the Quest side copies it, else 0 */
    volatile uint32_t pose_seqlock;   /* odd while writing `pose` */
    volatile uint64_t pose_seq;       /* bumped after each pose write */
    QssPoseState pose;

    /* ---- game -> Quest ---- */
    volatile uint32_t game_connected; /* 1 after VR_Init in the game */
    volatile uint32_t haptic_seq[2];  /* bumped per haptic request */
    float haptic_amplitude[2];
    float haptic_seconds[2];
    char app_name[128];
    volatile uint64_t frame_seq;      /* completed frames; latest is frames[(frame_seq-1) % 2] */
    QssFrameInfo frame_info[2];
    uint8_t _pad[64];
    /* followed by 2 frame buffers x 2 eyes x QSS_VR_EYE_BYTES, 4096-aligned (see qss_vr_pixels) */
} QssVrShm;

#define QSS_VR_PIXELS_OFFSET ((uint32_t)((sizeof(QssVrShm) + 4095u) & ~4095u))
#define QSS_VR_SHM_SIZE (QSS_VR_PIXELS_OFFSET + 4u * QSS_VR_EYE_BYTES)

static inline uint8_t* qss_vr_pixels(QssVrShm* s, uint32_t buffer, uint32_t eye) {
    return (uint8_t*)s + QSS_VR_PIXELS_OFFSET + (uint64_t)(buffer * 2u + eye) * QSS_VR_EYE_BYTES;
}

#endif
