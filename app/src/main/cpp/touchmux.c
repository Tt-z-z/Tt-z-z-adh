/*
 * TurboMacro TouchMux — original implementation (Linux MT-B + EVIOCGRAB + uinput).
 *
 * Why: Android's dispatcher tracks one input device per window. Injecting a tap
 * via a second device (shell `input tap`, synthetic MotionEvent, separate uinput
 * touchscreen) cancels the player's in-progress MOVE stream — aim/strafe dies.
 *
 * Fix: grab the real touchscreen, forward every contact into one virtual device,
 * and place macro fire taps in an extra MT slot on that SAME device.
 *
 * Protocol (stdin lines → stdout lines):
 *   ENGAGE <displayW> <displayH>
 *   TAP <x> <y> <holdMs>
 *   PING
 *   RELEASE
 *   QUIT
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define MAX_SLOTS 16
#define MAX_EVENTS 64
#define HEARTBEAT_MS 2500
#define IDLE_WAIT_MS 4000
#define POST_GRAB_QUIET_MS 700

#ifndef ABS_MT_SLOT
#define ABS_MT_SLOT 0x2f
#endif
#ifndef ABS_MT_TRACKING_ID
#define ABS_MT_TRACKING_ID 0x39
#endif
#ifndef ABS_MT_POSITION_X
#define ABS_MT_POSITION_X 0x35
#endif
#ifndef ABS_MT_POSITION_Y
#define ABS_MT_POSITION_Y 0x36
#endif
#ifndef ABS_MT_TOUCH_MAJOR
#define ABS_MT_TOUCH_MAJOR 0x30
#endif
#ifndef ABS_MT_PRESSURE
#define ABS_MT_PRESSURE 0x3a
#endif
#ifndef INPUT_PROP_DIRECT
#define INPUT_PROP_DIRECT 0x01
#endif
#ifndef UI_SET_PROPBIT
#define UI_SET_PROPBIT _IOW(UINPUT_IOCTL_BASE, 110, int)
#endif

struct slot_state {
    int tracking_id;
    int x;
    int y;
    bool active;
};

struct pending_tap {
    bool active;
    bool down_sent;
    int x;
    int y;
    int64_t up_deadline_ms;
};

static int g_real_fd = -1;
static int g_ui_fd = -1;
static int g_real_slots = 10;
static int g_macro_slot = 10;
static int g_abs_x_min, g_abs_x_max, g_abs_y_min, g_abs_y_max;
static int g_display_w = 1080, g_display_h = 2400;
static int g_next_tracking = 1000;
static int g_current_slot = 0;
static bool g_btn_touch = false;
static bool g_merging = false;
static bool g_in_packet = false;
static int64_t g_last_ping_ms = 0;
static int64_t g_grabbed_at_ms = 0;
static struct slot_state g_slots[MAX_SLOTS];
static struct pending_tap g_tap;

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void respond(const char *line) {
    fputs(line, stdout);
    fputc('\n', stdout);
    fflush(stdout);
}

static int bit_test(const unsigned long *bits, int bit) {
    return (bits[bit / (sizeof(unsigned long) * 8)] >> (bit % (sizeof(unsigned long) * 8))) & 1UL;
}

static int open_touchscreen(char *path_out, size_t path_len) {
    unsigned long abs_bits[(ABS_MAX / (sizeof(unsigned long) * 8)) + 1];
    for (int i = 0; i < 32; i++) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        memset(abs_bits, 0, sizeof(abs_bits));
        if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) >= 0 &&
            bit_test(abs_bits, ABS_MT_POSITION_X) &&
            bit_test(abs_bits, ABS_MT_POSITION_Y) &&
            bit_test(abs_bits, ABS_MT_SLOT)) {
            if (path_out && path_len) {
                snprintf(path_out, path_len, "%s", path);
            }
            return fd;
        }
        close(fd);
    }
    return -1;
}

static int read_absinfo(int fd, int code, int *min_v, int *max_v) {
    struct input_absinfo info;
    memset(&info, 0, sizeof(info));
    if (ioctl(fd, EVIOCGABS(code), &info) < 0) return -1;
    *min_v = info.minimum;
    *max_v = info.maximum;
    return 0;
}

static int create_virtual(int real_fd, const char *name_suffix) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) fd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;

    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOOL_FINGER);
#ifdef UI_SET_PROPBIT
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
#endif

    ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);

    struct uinput_abs_setup abs;
    memset(&abs, 0, sizeof(abs));

    abs.code = ABS_MT_SLOT;
    abs.absinfo.maximum = g_macro_slot;
    ioctl(fd, UI_ABS_SETUP, &abs);

    abs.code = ABS_MT_TRACKING_ID;
    abs.absinfo.minimum = 0;
    abs.absinfo.maximum = 65535;
    ioctl(fd, UI_ABS_SETUP, &abs);

    abs.code = ABS_MT_POSITION_X;
    abs.absinfo.minimum = g_abs_x_min;
    abs.absinfo.maximum = g_abs_x_max;
    ioctl(fd, UI_ABS_SETUP, &abs);

    abs.code = ABS_MT_POSITION_Y;
    abs.absinfo.minimum = g_abs_y_min;
    abs.absinfo.maximum = g_abs_y_max;
    ioctl(fd, UI_ABS_SETUP, &abs);

    abs.code = ABS_MT_TOUCH_MAJOR;
    abs.absinfo.maximum = 255;
    ioctl(fd, UI_ABS_SETUP, &abs);

    abs.code = ABS_MT_PRESSURE;
    abs.absinfo.maximum = 255;
    ioctl(fd, UI_ABS_SETUP, &abs);

    char name[80];
    char real_name[64];
    memset(real_name, 0, sizeof(real_name));
    ioctl(real_fd, EVIOCGNAME(sizeof(real_name) - 1), real_name);
    if (real_name[0] == '\0') snprintf(real_name, sizeof(real_name), "Touchscreen");
    snprintf(name, sizeof(name), "%.60s%s", real_name, name_suffix);

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x54d0;
    setup.id.product = 0x4d58;
    setup.id.version = 1;
    snprintf(setup.name, sizeof(setup.name), "%s", name);

    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0) {
        close(fd);
        return -1;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        close(fd);
        return -1;
    }
    /* Give InputReader a moment to register the new device. */
    usleep(200 * 1000);
    (void)real_fd;
    return fd;
}

static int emit(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    gettimeofday(&ev.time, NULL);
    ev.type = (uint16_t)type;
    ev.code = (uint16_t)code;
    ev.value = value;
    ssize_t n = write(g_ui_fd, &ev, sizeof(ev));
    return n == (ssize_t)sizeof(ev) ? 0 : -1;
}

static int any_real_active(void) {
    for (int i = 0; i < g_real_slots && i < MAX_SLOTS; i++) {
        if (g_slots[i].active) return 1;
    }
    return 0;
}

static int any_active(void) {
    return any_real_active() || (g_tap.active && g_tap.down_sent);
}

static void sync_btn_touch(void) {
    int want = any_active() ? 1 : 0;
    if (want != (g_btn_touch ? 1 : 0)) {
        emit(EV_KEY, BTN_TOUCH, want);
        g_btn_touch = want != 0;
    }
}

static int px_to_abs_x(int x) {
    if (g_display_w <= 1) return g_abs_x_min;
    long long span = (long long)g_abs_x_max - g_abs_x_min;
    long long v = g_abs_x_min + (span * x) / (g_display_w - 1);
    if (v < g_abs_x_min) v = g_abs_x_min;
    if (v > g_abs_x_max) v = g_abs_x_max;
    return (int)v;
}

static int px_to_abs_y(int y) {
    if (g_display_h <= 1) return g_abs_y_min;
    long long span = (long long)g_abs_y_max - g_abs_y_min;
    long long v = g_abs_y_min + (span * y) / (g_display_h - 1);
    if (v < g_abs_y_min) v = g_abs_y_min;
    if (v > g_abs_y_max) v = g_abs_y_max;
    return (int)v;
}

static void emit_macro_down(void) {
    int ax = px_to_abs_x(g_tap.x);
    int ay = px_to_abs_y(g_tap.y);
    int tid = g_next_tracking++;
    emit(EV_ABS, ABS_MT_SLOT, g_macro_slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, tid);
    emit(EV_ABS, ABS_MT_POSITION_X, ax);
    emit(EV_ABS, ABS_MT_POSITION_Y, ay);
    emit(EV_ABS, ABS_MT_TOUCH_MAJOR, 9);
    emit(EV_ABS, ABS_MT_PRESSURE, 40);
    g_current_slot = g_macro_slot;
    g_tap.down_sent = true;
    sync_btn_touch();
    emit(EV_SYN, SYN_REPORT, 0);
}

static void emit_macro_up(void) {
    if (!g_tap.down_sent) {
        g_tap.active = false;
        return;
    }
    emit(EV_ABS, ABS_MT_SLOT, g_macro_slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
    g_current_slot = g_macro_slot;
    g_tap.down_sent = false;
    g_tap.active = false;
    sync_btn_touch();
    emit(EV_SYN, SYN_REPORT, 0);
}

static void flush_pending_macro_at_syn(void) {
    if (!g_tap.active) return;
    int64_t t = now_ms();
    if (!g_tap.down_sent) {
        if (t - g_grabbed_at_ms < POST_GRAB_QUIET_MS) return;
        emit_macro_down();
        return;
    }
    if (t >= g_tap.up_deadline_ms) {
        emit_macro_up();
    }
}

static void handle_real_event(const struct input_event *ev) {
    /* Drop BTN_TOUCH from real — we derive it after merge. */
    if (ev->type == EV_KEY && ev->code == BTN_TOUCH) {
        g_in_packet = true;
        return;
    }

    if (ev->type == EV_ABS && ev->code == ABS_MT_SLOT) {
        g_current_slot = ev->value;
        if (g_current_slot < 0) g_current_slot = 0;
        if (g_current_slot >= g_real_slots) {
            /* Ignore bogus slot indices beyond real capacity. */
            g_in_packet = true;
            return;
        }
        emit(EV_ABS, ABS_MT_SLOT, g_current_slot);
        g_in_packet = true;
        return;
    }

    if (ev->type == EV_ABS && ev->code == ABS_MT_TRACKING_ID) {
        int s = g_current_slot;
        if (s >= 0 && s < MAX_SLOTS && s < g_real_slots) {
            if (ev->value < 0) {
                g_slots[s].active = false;
                g_slots[s].tracking_id = -1;
            } else {
                g_slots[s].active = true;
                g_slots[s].tracking_id = ev->value;
            }
        }
        emit(ev->type, ev->code, ev->value);
        g_in_packet = true;
        return;
    }

    if (ev->type == EV_ABS &&
        (ev->code == ABS_MT_POSITION_X || ev->code == ABS_MT_POSITION_Y)) {
        int s = g_current_slot;
        if (s >= 0 && s < MAX_SLOTS && s < g_real_slots) {
            if (ev->code == ABS_MT_POSITION_X) g_slots[s].x = ev->value;
            else g_slots[s].y = ev->value;
        }
        emit(ev->type, ev->code, ev->value);
        g_in_packet = true;
        return;
    }

    if (ev->type == EV_SYN && ev->code == SYN_REPORT) {
        sync_btn_touch();
        emit(EV_SYN, SYN_REPORT, 0);
        g_in_packet = false;
        /* After a full real frame, inject/advance macro finger if needed. */
        flush_pending_macro_at_syn();
        return;
    }

    if (ev->type == EV_SYN && ev->code == SYN_DROPPED) {
        /* Ask kernel to resend — still forward marker. */
        emit(EV_SYN, SYN_DROPPED, 0);
        g_in_packet = false;
        return;
    }

    /* Pass through remaining events. */
    emit(ev->type, ev->code, ev->value);
    g_in_packet = true;
}

static int wait_until_idle(int fd, int timeout_ms) {
    int64_t deadline = now_ms() + timeout_ms;
    struct input_event ev;
    int contacts = 0;
    /* Drain and count; also require a quiet stretch. */
    int64_t quiet_since = now_ms();
    while (now_ms() < deadline) {
        ssize_t n = read(fd, &ev, sizeof(ev));
        if (n == (ssize_t)sizeof(ev)) {
            if (ev.type == EV_ABS && ev.code == ABS_MT_TRACKING_ID) {
                if (ev.value >= 0) contacts++;
                else if (contacts > 0) contacts--;
            }
            if (ev.type == EV_KEY && ev.code == BTN_TOUCH) {
                contacts = ev.value ? 1 : 0;
            }
            quiet_since = now_ms();
            continue;
        }
        if (contacts <= 0 && now_ms() - quiet_since >= 180) {
            return 0;
        }
        usleep(10 * 1000);
    }
    return contacts <= 0 ? 0 : -1;
}

static void ungrab_and_destroy(void) {
    if (g_tap.active && g_tap.down_sent) {
        emit_macro_up();
    }
    g_tap.active = false;
    g_tap.down_sent = false;
    if (g_merging && g_real_fd >= 0) {
        ioctl(g_real_fd, EVIOCGRAB, 0);
    }
    g_merging = false;
    if (g_ui_fd >= 0) {
        ioctl(g_ui_fd, UI_DEV_DESTROY);
        close(g_ui_fd);
        g_ui_fd = -1;
    }
}

static int do_engage(int display_w, int display_h) {
    if (g_merging) {
        respond("OK ALREADY");
        return 0;
    }
    if (display_w > 0) g_display_w = display_w;
    if (display_h > 0) g_display_h = display_h;

    if (g_real_fd < 0) {
        char path[64];
        g_real_fd = open_touchscreen(path, sizeof(path));
        if (g_real_fd < 0) {
            respond("ERR no_touchscreen");
            return -1;
        }
    }

    int slot_min = 0, slot_max = 9;
    if (read_absinfo(g_real_fd, ABS_MT_SLOT, &slot_min, &slot_max) == 0) {
        g_real_slots = slot_max + 1;
        if (g_real_slots < 1) g_real_slots = 1;
        if (g_real_slots > MAX_SLOTS - 1) g_real_slots = MAX_SLOTS - 1;
    } else {
        g_real_slots = 10;
    }
    g_macro_slot = g_real_slots; /* one past last real slot */

    if (read_absinfo(g_real_fd, ABS_MT_POSITION_X, &g_abs_x_min, &g_abs_x_max) < 0 ||
        read_absinfo(g_real_fd, ABS_MT_POSITION_Y, &g_abs_y_min, &g_abs_y_max) < 0) {
        respond("ERR absinfo");
        return -1;
    }
    if (g_abs_x_max <= g_abs_x_min) g_abs_x_max = g_abs_x_min + g_display_w;
    if (g_abs_y_max <= g_abs_y_min) g_abs_y_max = g_abs_y_min + g_display_h;

    memset(g_slots, 0, sizeof(g_slots));
    for (int i = 0; i < MAX_SLOTS; i++) g_slots[i].tracking_id = -1;
    g_btn_touch = false;
    g_current_slot = 0;
    g_in_packet = false;

    if (wait_until_idle(g_real_fd, IDLE_WAIT_MS) < 0) {
        respond("ERR screen_busy");
        return -1;
    }

    g_ui_fd = create_virtual(g_real_fd, "-tm");
    if (g_ui_fd < 0) {
        respond("ERR uinput");
        return -1;
    }

    if (ioctl(g_real_fd, EVIOCGRAB, 1) < 0) {
        respond("ERR grab");
        ungrab_and_destroy();
        return -1;
    }

    g_merging = true;
    g_grabbed_at_ms = now_ms();
    g_last_ping_ms = g_grabbed_at_ms;
    char buf[128];
    snprintf(buf, sizeof(buf), "OK MERGING slots=%d macro=%d", g_real_slots, g_macro_slot);
    respond(buf);
    return 0;
}

static void do_release(void) {
    ungrab_and_destroy();
    respond("OK RELEASED");
}

static void do_tap(int x, int y, int hold_ms) {
    if (!g_merging) {
        respond("ERR not_merging");
        return;
    }
    if (g_tap.active && g_tap.down_sent) {
        /* Force previous up first. */
        emit_macro_up();
    }
    if (hold_ms < 8) hold_ms = 8;
    if (hold_ms > 80) hold_ms = 80;
    g_tap.active = true;
    g_tap.down_sent = false;
    g_tap.x = x;
    g_tap.y = y;
    g_tap.up_deadline_ms = now_ms() + hold_ms;
    /* If we are between packets, try immediately (after quiet window). */
    if (!g_in_packet) {
        flush_pending_macro_at_syn();
    }
    respond("OK TAP");
}

static void handle_command(char *line) {
    while (*line == ' ' || *line == '\t') line++;
    if (*line == '\0') return;

    if (strncmp(line, "PING", 4) == 0) {
        g_last_ping_ms = now_ms();
        respond("OK PONG");
        return;
    }
    if (strncmp(line, "QUIT", 4) == 0) {
        do_release();
        respond("OK BYE");
        exit(0);
    }
    if (strncmp(line, "RELEASE", 7) == 0) {
        do_release();
        return;
    }
    if (strncmp(line, "ENGAGE", 6) == 0) {
        int w = g_display_w, h = g_display_h;
        sscanf(line + 6, "%d %d", &w, &h);
        do_engage(w, h);
        return;
    }
    if (strncmp(line, "TAP", 3) == 0) {
        int x = 0, y = 0, hold = 16;
        if (sscanf(line + 3, "%d %d %d", &x, &y, &hold) >= 2) {
            do_tap(x, y, hold);
        } else {
            respond("ERR tap_args");
        }
        return;
    }
    respond("ERR unknown_cmd");
}

int main(int argc, char **argv) {
    (void)argc;
    (void)argv;
    setvbuf(stdin, NULL, _IONBF, 0);
    setvbuf(stdout, NULL, _IONBF, 0);

    char path[64];
    g_real_fd = open_touchscreen(path, sizeof(path));
    if (g_real_fd < 0) {
        respond("ERR no_touchscreen");
        return 1;
    }
    {
        char msg[96];
        snprintf(msg, sizeof(msg), "OK READY device=%s", path);
        respond(msg);
    }

    char line[256];
    size_t line_len = 0;
    g_last_ping_ms = now_ms();

    for (;;) {
        struct pollfd fds[2];
        fds[0].fd = STDIN_FILENO;
        fds[0].events = POLLIN;
        fds[1].fd = g_real_fd;
        fds[1].events = POLLIN;

        int timeout = 20;
        if (g_tap.active && g_tap.down_sent) {
            int64_t remain = g_tap.up_deadline_ms - now_ms();
            if (remain < timeout) timeout = remain > 0 ? (int)remain : 0;
        }

        int pr = poll(fds, g_merging ? 2 : 1, timeout);
        (void)pr;

        if (now_ms() - g_last_ping_ms > HEARTBEAT_MS) {
            /* App silent — give the screen back. */
            if (g_merging) {
                ungrab_and_destroy();
                respond("ERR heartbeat");
            }
            g_last_ping_ms = now_ms();
        }

        if (fds[0].revents & POLLIN) {
            char buf[128];
            ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n <= 0) {
                do_release();
                break;
            }
            for (ssize_t i = 0; i < n; i++) {
                char c = buf[i];
                if (c == '\n' || c == '\r') {
                    if (line_len > 0) {
                        line[line_len] = '\0';
                        handle_command(line);
                        line_len = 0;
                    }
                } else if (line_len + 1 < sizeof(line)) {
                    line[line_len++] = c;
                }
            }
        }

        if (g_merging && (fds[1].revents & POLLIN)) {
            struct input_event evs[MAX_EVENTS];
            ssize_t n = read(g_real_fd, evs, sizeof(evs));
            if (n > 0) {
                size_t count = (size_t)n / sizeof(struct input_event);
                for (size_t i = 0; i < count; i++) {
                    handle_real_event(&evs[i]);
                }
            }
        }

        /* Advance macro UP even if screen is idle (no SYN from real). */
        if (g_merging && g_tap.active && !g_in_packet) {
            flush_pending_macro_at_syn();
        }
    }

    if (g_real_fd >= 0) close(g_real_fd);
    return 0;
}
