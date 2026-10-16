// Test stand-in for the Quest app's OpenXR side: creates the shared memory, publishes a moving
// head pose and a pressed right trigger at 72 Hz, and checks the frames the game submits.
// Usage: fake_quest <shm path> <seconds>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#include "qss_vr_shm.h"

static void sleep_ms(int ms) { struct timespec t = {ms / 1000, (ms % 1000) * 1000000L}; nanosleep(&t, NULL); }

int main(int argc, char** argv) {
    if (argc < 3) { fprintf(stderr, "usage: fake_quest <shm> <seconds>\n"); return 2; }
    int fd = open(argv[1], O_RDWR | O_CREAT | O_TRUNC, 0600);
    if (fd < 0 || ftruncate(fd, QSS_VR_SHM_SIZE) != 0) { perror("shm"); return 1; }
    QssVrShm* s = mmap(NULL, QSS_VR_SHM_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (s == MAP_FAILED) { perror("mmap"); return 1; }
    memset(s, 0, sizeof *s);
    s->total_size = QSS_VR_SHM_SIZE;
    s->rec_width = 640;
    s->rec_height = 704;
    s->version = QSS_VR_VERSION;
    __sync_synchronize();
    s->magic = QSS_VR_MAGIC;
    s->xr_running = 1;

    int frames = atoi(argv[2]) * 72;
    uint64_t lastFrame = 0;
    int got = 0, bad = 0;
    for (int i = 1; i <= frames; ++i) {
        __atomic_add_fetch(&s->pose_seqlock, 1, __ATOMIC_SEQ_CST);
        QssPoseState* p = &s->pose;
        p->frame_id = (uint64_t)i;
        p->head.pos[0] = 0; p->head.pos[1] = 1.6f; p->head.pos[2] = -0.001f * i;
        p->head.rot[3] = 1;
        for (int e = 0; e < 2; ++e) {
            p->eye[e] = p->head;
            p->eye[e].pos[0] = e ? 0.032f : -0.032f;
            p->fov[e].left = -0.9f; p->fov[e].right = 0.8f; p->fov[e].up = 0.85f; p->fov[e].down = -0.95f;
        }
        p->hand[1].valid = 1;
        p->hand[1].trigger = 1.0f;
        p->hand[1].buttons = QSS_BTN_A | QSS_TOUCH_A;
        p->hand[1].grip_pose.pos[0] = 0.2f; p->hand[1].grip_pose.pos[1] = 1.2f; p->hand[1].grip_pose.rot[3] = 1;
        p->hand[0].valid = 1;
        p->hand[0].grip_pose.rot[3] = 1;
        p->ipd = 0.064f;
        p->refresh_hz = 72.0f;
        __atomic_add_fetch(&s->pose_seqlock, 1, __ATOMIC_SEQ_CST);
        __atomic_add_fetch(&s->pose_seq, 1, __ATOMIC_SEQ_CST);

        uint64_t fs = __atomic_load_n(&s->frame_seq, __ATOMIC_ACQUIRE);
        if (fs != lastFrame) {
            uint32_t b = (uint32_t)((fs - 1) % 2);
            s->quest_reading = b + 1;
            QssFrameInfo info = s->frame_info[b];
            uint8_t* l = qss_vr_pixels(s, b, 0);
            uint8_t* r = qss_vr_pixels(s, b, 1);
            // The test game clears the left eye red and the right eye blue.
            size_t mid = ((size_t)info.height / 2 * info.width + info.width / 2) * 4;
            int okL = l[mid + 0] > 200 && l[mid + 2] < 50;
            int okR = r[mid + 2] > 200 && r[mid + 0] < 50;
            if (info.format == QSS_FMT_BGRA8) { okL = l[mid + 2] > 200 && l[mid] < 50; okR = r[mid] > 200 && r[mid + 2] < 50; }
            if (!okL || !okR) bad++;
            if (got == 0 || fs % 30 == 0)
                printf("[fake-quest] frame %llu: %ux%u fmt=%u flip=%u renderPose=%llu left=(%u,%u,%u) right=(%u,%u,%u) %s\n",
                       (unsigned long long)fs, info.width, info.height, info.format, info.flip_y,
                       (unsigned long long)info.pose.frame_id, l[mid], l[mid + 1], l[mid + 2], r[mid], r[mid + 1], r[mid + 2],
                       okL && okR ? "OK" : "WRONG COLORS");
            s->quest_reading = 0;
            got += (int)(fs - lastFrame);
            lastFrame = fs;
        }
        static uint32_t lastHaptic = 0;
        if (s->haptic_seq[1] != lastHaptic) { lastHaptic = s->haptic_seq[1]; printf("[fake-quest] haptic right %.2f for %.3fs\n", s->haptic_amplitude[1], s->haptic_seconds[1]); }
        sleep_ms(14);
        if (got >= 60) break;
    }
    printf("[fake-quest] game connected=%u app=%s frames received=%d bad=%d\n", s->game_connected, s->app_name, got, bad);
    return got > 0 && bad == 0 ? 0 : 1;
}
