/*
 * Gamepad shared memory between the Quest app (Android side writes controller state) and the
 * game (Windows side: our xinput1_x.dll reads it). One small file in <rootfs>/tmp/gamepad.shm,
 * mapped by both. Little-endian, fixed layout; keep in sync with app GamepadBridge.kt.
 */
#ifndef QSS_PAD_SHM_H
#define QSS_PAD_SHM_H

#include <stdint.h>

#define QSS_PAD_MAGIC 0x50565351u /* "QSVP" */
#define QSS_PAD_VERSION 1u
#define QSS_PAD_MAX 4
#define QSS_PAD_SHM_SIZE 4096

/* Controller families, for the game's capabilities and the app's "connected" chip. */
#define QSS_PAD_TYPE_UNKNOWN 0
#define QSS_PAD_TYPE_XBOX 1
#define QSS_PAD_TYPE_PLAYSTATION 2
#define QSS_PAD_TYPE_NINTENDO 3

typedef struct QssPad {
    uint32_t connected;    /* 0 or 1 (Android side) */
    uint32_t packet;       /* bumped on every change (Android side) */
    uint16_t buttons;      /* XINPUT_GAMEPAD_* bits; 0x0400 = guide (Android side) */
    uint8_t left_trigger;  /* 0..255 */
    uint8_t right_trigger;
    int16_t lx, ly, rx, ry; /* XInput convention: up = positive */
    uint16_t rumble_low;   /* XInputSetState (game side) */
    uint16_t rumble_high;
    uint32_t rumble_seq;   /* bumped by the game on every XInputSetState */
    uint32_t type;         /* QSS_PAD_TYPE_* (Android side) */
} QssPad;                  /* 32 bytes */

typedef struct QssPadShm {
    uint32_t magic;
    uint32_t version;
    uint32_t count;        /* QSS_PAD_MAX */
    uint32_t reserved;
    QssPad pads[QSS_PAD_MAX];
} QssPadShm;

#ifdef __cplusplus
static_assert(sizeof(QssPad) == 32, "QssPad layout");
static_assert(sizeof(QssPadShm) == 16 + 32 * QSS_PAD_MAX, "QssPadShm layout");
#endif

#endif
