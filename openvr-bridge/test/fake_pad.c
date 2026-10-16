/* Linux stand-in for the Quest app's controller writer: creates gamepad.shm, presses buttons,
 * moves sticks and waits for the game to rumble. Exit 0 = the game saw it all and rumbled. */
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include "qss_pad_shm.h"

int main(int argc, char** argv) {
    if (argc < 2) { fprintf(stderr, "usage: fake_pad <shm>\n"); return 2; }
    int fd = open(argv[1], O_RDWR | O_CREAT | O_TRUNC, 0666);
    if (fd < 0 || ftruncate(fd, QSS_PAD_SHM_SIZE) != 0) { perror("shm"); return 2; }
    QssPadShm* s = mmap(0, QSS_PAD_SHM_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (s == MAP_FAILED) { perror("mmap"); return 2; }
    memset(s, 0, QSS_PAD_SHM_SIZE);
    s->version = QSS_PAD_VERSION; s->count = QSS_PAD_MAX;
    QssPad* p = &s->pads[0];
    p->type = QSS_PAD_TYPE_PLAYSTATION;
    p->buttons = 0x1000 | 0x0001; /* A + D-pad up */
    p->left_trigger = 0; p->right_trigger = 200;
    p->lx = 12345; p->ly = -32768; p->rx = 32767; p->ry = 0;
    p->packet = 1; p->connected = 1;
    __sync_synchronize();
    s->magic = QSS_PAD_MAGIC; /* published last */
    printf("fake pad: published\n"); fflush(stdout);
    for (int i = 0; i < 600; i++) { /* 60 s */
        if (p->rumble_seq && p->rumble_low == 0x8000 && p->rumble_high == 0x4000) {
            printf("fake pad: game rumbled (low %u high %u)\n", p->rumble_low, p->rumble_high);
            return 0;
        }
        usleep(100000);
    }
    printf("fake pad: no rumble from the game\n");
    return 1;
}
