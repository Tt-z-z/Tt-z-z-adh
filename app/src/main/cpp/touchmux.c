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
    int output_tracking_id;
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
/* Input IDs are independent: map real lifetimes to 0..65534, macro to 65535. */
static int g_real_tracking_max = 65535;
static int g_next_output_tracking = 0;
static int g_real_current_slot = 0;
static int g_output_slot = 0;
static bool g_btn_touch = false;
static bool g_merging = false;
static bool g_in_packet = false;
static bool g_io_error = false;
static bool g_fatal = false;

static void ungrab_and_destroy(void);
static void fail_closed(void) {
    g_fatal = true;
    ungrab_and_destroy();
#ifndef TOUCHMUX_TEST
    /* Closing also releases the grab if EVIOCGRAB(0) itself failed. */
    if (g_real_fd >= 0) { close(g_real_fd); g_real_fd = -1; }
#endif
}

#ifdef TOUCHMUX_TEST
static struct input_event *g_test_events;
static size_t g_test_event_count;
static size_t g_test_event_capacity;
static int g_test_cleanup_count;
static bool g_test_write_failure;
#endif
/* Deadline starts at process entry, never at ENGAGE or PING. Cooperative only:
 * blocking syscalls still require an external Android timeout wrapper. */
static int64_t g_diagnostic_deadline_ms = 0;
static bool g_no_tap = false;
#ifdef TOUCHMUX_TEST
static int64_t g_test_now_ms = -1;
#endif
static int64_t g_last_ping_ms = 0;
static int64_t g_grabbed_at_ms = 0;
static struct slot_state g_slots[MAX_SLOTS];
static struct pending_tap g_tap;

static int64_t now_ms(void) {
#ifdef TOUCHMUX_TEST
    if (g_test_now_ms >= 0) return g_test_now_ms;
#endif
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void respond(const char *line) {
    fputs(line, stdout);
    fputc('\n', stdout);
    fflush(stdout);
}

/* Parse completely before changing configuration or touching a device. */
static bool parse_options(int argc, char **argv, int64_t started_ms) {
    int seconds = 0;
    bool no_tap = false;
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--no-tap") == 0 && !no_tap) {
            no_tap = true;
        } else if (strcmp(argv[i], "--diagnostic-seconds") == 0 && !seconds) {
            if (++i >= argc || !argv[i][0]) return false;
            /* Bounded digit parsing rejects signs, whitespace and overflow. */
            for (const char *p = argv[i]; *p; ++p) {
                if (*p < '0' || *p > '9') return false;
                seconds = seconds * 10 + (*p - '0');
                if (seconds > 30 * 60) return false;
            }
            if (seconds < 1) return false;
        } else {
            return false;
        }
    }
    g_no_tap = no_tap;
    g_diagnostic_deadline_ms = seconds ? started_ms + seconds * 1000 : 0;
    return true;
}

static bool check_diagnostic_deadline(void) {
    if (!g_diagnostic_deadline_ms || now_ms() < g_diagnostic_deadline_ms)
        return false;
    if (!g_fatal) {
        fail_closed();
        respond("ERR diagnostic_expired");
    }
    return true;
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

/* Configuration must finish successfully before the real device is grabbed. */
#define CHECK_UI(fd, request, arg) do { \
    if (ioctl(fd, request, arg) < 0) { close(fd); return -1; } \
} while (0)

static bool g_has_pressure = false;
static bool g_has_touch_major = false;

static void configure_optional_axes(const unsigned long *bits) {
    g_has_pressure = bit_test(bits, ABS_MT_PRESSURE) != 0;
    g_has_touch_major = bit_test(bits, ABS_MT_TOUCH_MAJOR) != 0;
}

static int create_virtual(int real_fd, const char *name_suffix) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) fd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;

    CHECK_UI(fd, UI_SET_EVBIT, EV_SYN);
    CHECK_UI(fd, UI_SET_EVBIT, EV_KEY);
    CHECK_UI(fd, UI_SET_EVBIT, EV_ABS);
    CHECK_UI(fd, UI_SET_KEYBIT, BTN_TOUCH);
    CHECK_UI(fd, UI_SET_KEYBIT, BTN_TOOL_FINGER);
#ifdef UI_SET_PROPBIT
    CHECK_UI(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
#endif

    CHECK_UI(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    CHECK_UI(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    CHECK_UI(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    CHECK_UI(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    struct uinput_abs_setup abs;
    memset(&abs, 0, sizeof(abs));

    abs.code = ABS_MT_SLOT;
    abs.absinfo.maximum = g_macro_slot;
    CHECK_UI(fd, UI_ABS_SETUP, &abs);

    abs.code = ABS_MT_TRACKING_ID;
    abs.absinfo.minimum = 0;
    abs.absinfo.maximum = 65535;
    CHECK_UI(fd, UI_ABS_SETUP, &abs);

    abs.code = ABS_MT_POSITION_X;
    abs.absinfo.minimum = g_abs_x_min;
    abs.absinfo.maximum = g_abs_x_max;
    CHECK_UI(fd, UI_ABS_SETUP, &abs);

    abs.code = ABS_MT_POSITION_Y;
    abs.absinfo.minimum = g_abs_y_min;
    abs.absinfo.maximum = g_abs_y_max;
    CHECK_UI(fd, UI_ABS_SETUP, &abs);

    /* Preserve every advertised per-contact axis (orientation, distance, etc.). */
    unsigned long bits[(ABS_MAX / (sizeof(unsigned long) * 8)) + 1] = {0};
    if (ioctl(real_fd, EVIOCGBIT(EV_ABS, sizeof(bits)), bits) < 0) {
        close(fd);
        return -1;
    }
    configure_optional_axes(bits);
    for (int code = ABS_MT_TOUCH_MAJOR; code <= ABS_MT_TOOL_Y; ++code) {
        if (code == ABS_MT_TRACKING_ID || !bit_test(bits, code)) continue;
        memset(&abs, 0, sizeof(abs));
        abs.code = (uint16_t)code;
        if (ioctl(real_fd, EVIOCGABS(code), &abs.absinfo) < 0) {
            close(fd);
            return -1;
        }
        CHECK_UI(fd, UI_SET_ABSBIT, code);
        CHECK_UI(fd, UI_ABS_SETUP, &abs);
    }

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
    if (g_fatal || g_io_error) return -1;
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    gettimeofday(&ev.time, NULL);
    ev.type = (uint16_t)type;
    ev.code = (uint16_t)code;
    ev.value = value;
    ssize_t n;
#ifdef TOUCHMUX_TEST
    if (g_test_write_failure) { g_io_error = true; return -1; }
    if (g_test_event_count == g_test_event_capacity) {
        size_t next = g_test_event_capacity ? g_test_event_capacity * 2 : 64;
        struct input_event *events = (struct input_event *)realloc(
            g_test_events, next * sizeof(*events));
        if (!events) {
            g_io_error = true;
            return -1;
        }
        g_test_events = events;
        g_test_event_capacity = next;
    }
    g_test_events[g_test_event_count++] = ev;
    n = (ssize_t)sizeof(ev);
#else
    do {
        n = write(g_ui_fd, &ev, sizeof(ev));
    } while (n < 0 && errno == EINTR);
#endif
    if (n != (ssize_t)sizeof(ev)) {
        g_io_error = true;
        return -1;
    }
    if (type == EV_ABS && code == ABS_MT_SLOT) g_output_slot = value;
    return 0;
}

static int select_output_slot(int slot) {
    return g_output_slot == slot ? 0 : emit(EV_ABS, ABS_MT_SLOT, slot);
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
    int tid = 65535;
    emit(EV_ABS, ABS_MT_SLOT, g_macro_slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, tid);
    emit(EV_ABS, ABS_MT_POSITION_X, ax);
    emit(EV_ABS, ABS_MT_POSITION_Y, ay);
    if (g_has_touch_major) emit(EV_ABS, ABS_MT_TOUCH_MAJOR, 9);
    if (g_has_pressure) emit(EV_ABS, ABS_MT_PRESSURE, 40);
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

static int allocate_real_output_id(void) {
    /* Skip all active IDs, including the old lifetime on a replacement slot. */
    for (int tries = 0; tries < 65535; ++tries) {
        int candidate = g_next_output_tracking;
        g_next_output_tracking = (candidate + 1) % 65535;
        bool used = false;
        for (int i = 0; i < g_real_slots; ++i) {
            if (g_slots[i].active && g_slots[i].output_tracking_id == candidate) {
                used = true;
                break;
            }
        }
        if (!used) return candidate;
    }
    return -1;
}

static void handle_real_event(const struct input_event *ev) {
    if (g_fatal || g_io_error) return;
    if (ev->type == EV_SYN && ev->code == SYN_DROPPED) {
        /* State cannot be reconstructed from subsequent delta events. */
        fail_closed();
        return;
    }
    if (ev->type == EV_ABS && ev->code > ABS_MT_SLOT &&
        ev->code <= ABS_MT_TOOL_Y) {
        if (g_real_current_slot < 0 || g_real_current_slot >= g_real_slots) {
            fail_closed();
            return;
        }
        if (select_output_slot(g_real_current_slot) < 0) return;
    }
    /* Drop BTN_TOUCH from real — we derive it after merge. */
    if (ev->type == EV_KEY && ev->code == BTN_TOUCH) {
        g_in_packet = true;
        return;
    }

    if (ev->type == EV_ABS && ev->code == ABS_MT_SLOT) {
        g_real_current_slot = ev->value;
        if (g_real_current_slot < 0 || g_real_current_slot >= g_real_slots) {
            fail_closed();
            return;
        }
        select_output_slot(g_real_current_slot);
        g_in_packet = true;
        return;
    }

    if (ev->type == EV_ABS && ev->code == ABS_MT_TRACKING_ID) {
        if (ev->value < -1 || ev->value > g_real_tracking_max) {
            fail_closed();
            return;
        }
        int s = g_real_current_slot;
        for (int i = 0; i < g_real_slots; ++i) {
            if (i != s && ev->value >= 0 && g_slots[i].active &&
                g_slots[i].tracking_id == ev->value) {
                fail_closed();
                return;
            }
        }
        int output_id = -1;
        if (ev->value >= 0) {
            if (g_slots[s].active && g_slots[s].tracking_id == ev->value) {
                output_id = g_slots[s].output_tracking_id;
            } else {
                output_id = allocate_real_output_id();
                if (output_id < 0) { fail_closed(); return; }
            }
        }
        if (emit(ev->type, ev->code, output_id) < 0) return;
        g_slots[s].active = ev->value >= 0;
        g_slots[s].tracking_id = ev->value;
        g_slots[s].output_tracking_id = output_id;
        g_in_packet = true;
        return;
    }

    if (ev->type == EV_ABS &&
        (ev->code == ABS_MT_POSITION_X || ev->code == ABS_MT_POSITION_Y)) {
        int s = g_real_current_slot;
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

    /* Pass through remaining events. */
    emit(ev->type, ev->code, ev->value);
    g_in_packet = true;
}

#ifdef TOUCHMUX_TEST
static int (*g_test_query_slots)(int32_t *ids, int count);
#endif

static int query_tracking_slots(int fd, int32_t *ids) {
    ids[0] = ABS_MT_TRACKING_ID;
#ifdef TOUCHMUX_TEST
    (void)fd;
    return g_test_query_slots ? g_test_query_slots(ids, g_real_slots) : -1;
#else
    return ioctl(fd, EVIOCGMTSLOTS((g_real_slots + 1) * sizeof(int32_t)), ids);
#endif
}

static int wait_until_idle(int fd, int timeout_ms) {
    int64_t deadline = now_ms() + timeout_ms;
    struct input_event ev;
    /* Query kernel state, not just deltas: a stationary finger emits nothing. */
    int64_t quiet_since = now_ms();
    while (now_ms() < deadline) {
        if (check_diagnostic_deadline()) return -1;
        ssize_t n = read(fd, &ev, sizeof(ev));
        if (n == (ssize_t)sizeof(ev)) {
            if (ev.type == EV_SYN && ev.code == SYN_DROPPED) return -1;
            quiet_since = now_ms();
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n >= 0 || (errno != EAGAIN && errno != EWOULDBLOCK)) return -1;
        int32_t ids[MAX_SLOTS + 1];
        if (query_tracking_slots(fd, ids) < 0) return -1;
        bool active = false;
        for (int i = 1; i <= g_real_slots; ++i) active |= ids[i] >= 0;
        if (active) quiet_since = now_ms();
        if (!active && now_ms() - quiet_since >= 180) {
            struct input_absinfo slot;
            if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &slot) < 0 ||
                slot.value < 0 || slot.value >= g_real_slots) return -1;
            g_real_current_slot = slot.value;
            return 0;
        }
        usleep(10 * 1000);
    }
    return -1;
}

static void ungrab_and_destroy(void) {
#ifdef TOUCHMUX_TEST
    ++g_test_cleanup_count;
    g_tap.active = false;
    g_tap.down_sent = false;
    g_merging = false;
    return;
#endif
    if (!g_fatal && !g_io_error && g_tap.active && g_tap.down_sent) {
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

static int finish_engage(void) {
    /* Recheck after all potentially slow setup, before announcing success. */
    if (check_diagnostic_deadline() || g_fatal) return -1;
    g_grabbed_at_ms = now_ms();
    g_last_ping_ms = g_grabbed_at_ms;
    char buf[128];
    snprintf(buf, sizeof(buf), "OK MERGING slots=%d macro=%d", g_real_slots, g_macro_slot);
    respond(buf);
    return 0;
}

static int do_engage(int display_w, int display_h) {
    if (check_diagnostic_deadline() || g_fatal) return -1;
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

    int slot_min, slot_max, tracking_min;
    if (read_absinfo(g_real_fd, ABS_MT_SLOT, &slot_min, &slot_max) < 0 ||
        slot_min != 0 || slot_max < 0 || slot_max >= MAX_SLOTS - 1) {
        respond("ERR unsupported_slots");
        return -1;
    }
    g_real_slots = slot_max + 1;
    if (read_absinfo(g_real_fd, ABS_MT_TRACKING_ID, &tracking_min,
                     &g_real_tracking_max) < 0 || tracking_min != 0 ||
        g_real_tracking_max < 0 || g_real_tracking_max > 65535) {
        respond("ERR unsupported_tracking_range");
        return -1;
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
    g_real_current_slot = 0;
    g_in_packet = false;
    g_output_slot = 0;
    g_next_output_tracking = 0;
    g_io_error = false;
    if (g_fatal) return -1;

    if (check_diagnostic_deadline()) return -1;
    int idle_result = wait_until_idle(g_real_fd, IDLE_WAIT_MS);
    if (check_diagnostic_deadline()) return -1;
    if (idle_result < 0) {
        respond("ERR screen_busy");
        return -1;
    }

    g_ui_fd = create_virtual(g_real_fd, "-tm");
    if (check_diagnostic_deadline()) return -1;
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
    if (check_diagnostic_deadline()) return -1;
    /* Recheck after grab: contacts may have arrived while uinput was created. */
    idle_result = wait_until_idle(g_real_fd, 250);
    if (check_diagnostic_deadline()) return -1;
    if (idle_result < 0) {
        ungrab_and_destroy();
        respond("ERR screen_busy_after_grab");
        return -1;
    }
    return finish_engage();
}

static void do_release(void) {
    ungrab_and_destroy();
    respond("OK RELEASED");
}

static void do_tap(int x, int y, int hold_ms) {
    if (g_no_tap) {
        respond("ERR tap_disabled");
        return;
    }
    if (!g_merging) {
        respond("ERR not_merging");
        return;
    }
    if (g_in_packet && g_tap.active && g_tap.down_sent) {
        /* Never insert a synthetic SYN_REPORT into a partial real frame. */
        respond("ERR frame_busy");
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
    if (check_diagnostic_deadline() || g_fatal) return;
    while (*line == ' ' || *line == '\t') line++;
    if (*line == '\0') return;

    if (strncmp(line, "PING", 4) == 0) {
        g_last_ping_ms = now_ms();
        respond("OK PONG");
        return;
    }
    if (strcmp(line, "CANCEL_TAP") == 0) {
        /* Preserve partial real frames: lift at the next SYN boundary. */
        if (g_tap.down_sent) {
            g_tap.up_deadline_ms = 0;
            if (!g_in_packet) {
                emit_macro_up();
                select_output_slot(g_real_current_slot);
            }
        } else {
            g_tap.active = false;
        }
        respond("OK CANCEL_TAP");
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

static bool check_poll_result(int result, int error, short control, short real) {
    if (result < 0 && error == EINTR) return true;
    if (result < 0 || ((control | real) & (POLLERR | POLLHUP | POLLNVAL))) {
        fail_closed();
        return false;
    }
    return true;
}

static bool valid_real_read(ssize_t n, int error) {
    if (n < 0 && (error == EINTR || error == EAGAIN || error == EWOULDBLOCK))
        return true;
    if (n <= 0 || n % (ssize_t)sizeof(struct input_event) != 0) {
        fail_closed();
        return false;
    }
    return true;
}

int main(int argc, char **argv) {
    int64_t started_ms = now_ms();
    if (!parse_options(argc, argv, started_ms)) {
        respond("ERR args: [--diagnostic-seconds 1..1800] [--no-tap]");
        return 2;
    }
    setvbuf(stdin, NULL, _IONBF, 0);
    setvbuf(stdout, NULL, _IONBF, 0);

    char path[64];
    g_real_fd = open_touchscreen(path, sizeof(path));
    if (check_diagnostic_deadline()) return 1;
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
        if (check_diagnostic_deadline() || g_fatal) break;
        struct pollfd fds[2] = {0};
        fds[0].fd = STDIN_FILENO;
        fds[0].events = POLLIN;
        fds[1].fd = g_real_fd;
        fds[1].events = POLLIN;

        int timeout = 20;
        if (g_tap.active && g_tap.down_sent) {
            int64_t remain = g_tap.up_deadline_ms - now_ms();
            if (remain < timeout) timeout = remain > 0 ? (int)remain : 0;
        }

        if (g_diagnostic_deadline_ms) {
            int64_t remain = g_diagnostic_deadline_ms - now_ms();
            if (remain < timeout) timeout = remain > 0 ? (int)remain : 0;
        }
        int pr = poll(fds, g_merging ? 2 : 1, timeout);
        if (check_diagnostic_deadline()) break;
        if (pr < 0 && errno == EINTR) continue;
        if (!check_poll_result(pr, errno, fds[0].revents,
                               g_merging ? fds[1].revents : 0)) break;

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
            if (n == 0) {
                do_release();
                break;
            }
            if (n < 0) {
                if (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) {
                    g_io_error = true;
                }
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

        if (check_diagnostic_deadline() || g_fatal) break;
        if (g_merging && (fds[1].revents & POLLIN)) {
            struct input_event evs[MAX_EVENTS];
            ssize_t n = read(g_real_fd, evs, sizeof(evs));
            if (!valid_real_read(n, errno)) break;
            if (n > 0) {
                size_t count = (size_t)n / sizeof(struct input_event);
                for (size_t i = 0; i < count && !g_fatal && !g_io_error; i++) {
                    if (check_diagnostic_deadline()) break;
                    handle_real_event(&evs[i]);
                }
            }
        }

        if (g_io_error || g_fatal) {
            fail_closed();
            respond("ERR stream_failed");
            break;
        }

        if (check_diagnostic_deadline()) break;
        /* Advance macro UP even if screen is idle (no SYN from real). */
        if (g_merging && g_tap.active && !g_in_packet) {
            flush_pending_macro_at_syn();
        }
        if (g_io_error) {
            fail_closed();
            break;
        }
    }

    ungrab_and_destroy();
    if (g_real_fd >= 0) close(g_real_fd);
    return g_fatal ? 1 : 0;
}
