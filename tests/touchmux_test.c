/* Linux host only, no devices opened:
 * cc -std=gnu11 -Wall -Wextra -Werror -O1 -g -fsanitize=address,undefined
 *    tests/touchmux_test.c -o /tmp/touchmux_test && /tmp/touchmux_test
 */
#define TOUCHMUX_TEST
#define main touchmux_program_main
#include "../app/src/main/cpp/touchmux.c"
#undef main
#include <assert.h>

/* Interpret the emitted stream like a type-B device, independently of g_slots. */
struct device_model {
    int slot;
    int attributes[MAX_SLOTS][ABS_MAX + 1];
    size_t consumed;
};
static struct device_model device;

static void consume(void) {
    for (; device.consumed < g_test_event_count; ++device.consumed) {
        const struct input_event *ev = &g_test_events[device.consumed];
        if (ev->type != EV_ABS) continue;
        if (ev->code == ABS_MT_SLOT) {
            assert(ev->value >= 0 && ev->value < MAX_SLOTS);
            device.slot = ev->value;
        } else if (ev->code > ABS_MT_SLOT && ev->code <= ABS_MT_TOOL_Y) {
            device.attributes[device.slot][ev->code] = ev->value;
            if (ev->code == ABS_MT_TRACKING_ID && ev->value >= 0) {
                for (int s = 0; s < MAX_SLOTS; ++s) {
                    if (s != device.slot)
                        assert(device.attributes[s][ABS_MT_TRACKING_ID] != ev->value);
                }
            }
        }
    }
}

static void send_event(int type, int code, int value) {
    struct input_event ev = {.type = type, .code = code, .value = value};
    handle_real_event(&ev);
    consume();
}

static void reset_test_state(void) {
    g_has_pressure = true;
    g_has_touch_major = true;
    g_diagnostic_deadline_ms = 0;
    g_no_tap = false;
    g_test_now_ms = -1;
    g_last_ping_ms = 0;
    g_test_event_count = 0;
    g_test_cleanup_count = 0;
    g_test_write_failure = false;
    g_real_slots = 4;
    g_macro_slot = 4;
    g_real_tracking_max = 65535;
    g_next_output_tracking = 0;
    g_real_current_slot = 0;
    g_output_slot = 0;
    g_display_w = g_display_h = 100;
    g_abs_x_min = g_abs_y_min = 0;
    g_abs_x_max = g_abs_y_max = 99;
    g_btn_touch = g_in_packet = g_io_error = g_fatal = false;
    g_merging = true;
    memset(&g_tap, 0, sizeof(g_tap));
    memset(g_slots, 0, sizeof(g_slots));
    memset(&device, 0, sizeof(device));
    for (int i = 0; i < MAX_SLOTS; ++i)
        device.attributes[i][ABS_MT_TRACKING_ID] = -1;
}

static void test_slot_stream(void) {
    reset_test_state();
    send_event(EV_ABS, ABS_MT_SLOT, 2);
    send_event(EV_ABS, ABS_MT_TRACKING_ID, 77);
    send_event(EV_SYN, SYN_REPORT, 0);
    g_tap.active = true;
    g_tap.x = 10;
    g_tap.y = 20;
    emit_macro_down();
    consume();
    assert(device.attributes[4][ABS_MT_TRACKING_ID] == 65535);
    for (int phase = 0; phase < 2; ++phase) {
        /* No SLOT: both macro DOWN and UP leave the output cursor on slot 4. */
        send_event(EV_ABS, ABS_MT_POSITION_X, 31 + phase);
        send_event(EV_ABS, ABS_MT_POSITION_Y, 41 + phase);
        send_event(EV_ABS, ABS_MT_PRESSURE, 51 + phase);
        send_event(EV_ABS, ABS_MT_TOUCH_MAJOR, 61 + phase);
        send_event(EV_ABS, ABS_MT_TRACKING_ID, 78 + phase);
        assert(device.attributes[2][ABS_MT_POSITION_X] == 31 + phase);
        assert(device.attributes[2][ABS_MT_POSITION_Y] == 41 + phase);
        assert(device.attributes[2][ABS_MT_PRESSURE] == 51 + phase);
        assert(device.attributes[2][ABS_MT_TOUCH_MAJOR] == 61 + phase);
        assert(device.attributes[2][ABS_MT_TRACKING_ID] == 1 + phase);
        assert(device.attributes[4][ABS_MT_POSITION_X] == 10);
        assert(device.attributes[4][ABS_MT_POSITION_Y] == 20);
        assert(device.attributes[4][ABS_MT_PRESSURE] == 40);
        assert(device.attributes[4][ABS_MT_TOUCH_MAJOR] == 9);
        assert(device.attributes[4][ABS_MT_TRACKING_ID] == (phase ? -1 : 65535));
        emit_macro_up();
        consume();
    }
    send_event(EV_ABS, ABS_MT_TRACKING_ID, -1);
    assert(device.attributes[2][ABS_MT_TRACKING_ID] == -1);
}

static void test_tracking_namespace(void) {
    reset_test_state();
    /* Target goodix range: 10 real slots and full 16-bit source IDs. */
    g_real_slots = 10;
    g_macro_slot = 10;
    send_event(EV_ABS, ABS_MT_TRACKING_ID, 65535);
    assert(!g_fatal);
    assert(device.attributes[0][ABS_MT_TRACKING_ID] == 0);
    g_tap.active = true;
    emit_macro_down();
    consume();
    assert(device.attributes[10][ABS_MT_TRACKING_ID] == 65535);
    send_event(EV_ABS, ABS_MT_TRACKING_ID, 65535);
    assert(device.attributes[0][ABS_MT_TRACKING_ID] == 0); /* Same lifetime. */

    /* Exercise wrap and skip active output ID 0, not merely source IDs. */
    g_next_output_tracking = 65534;
    send_event(EV_ABS, ABS_MT_SLOT, 1);
    send_event(EV_ABS, ABS_MT_TRACKING_ID, 0);
    assert(device.attributes[1][ABS_MT_TRACKING_ID] == 65534);
    send_event(EV_ABS, ABS_MT_SLOT, 2);
    send_event(EV_ABS, ABS_MT_TRACKING_ID, 1);
    assert(device.attributes[2][ABS_MT_TRACKING_ID] == 1);
    assert(device.attributes[10][ABS_MT_TRACKING_ID] == 65535);

    send_event(EV_ABS, ABS_MT_SLOT, 0);
    send_event(EV_ABS, ABS_MT_TRACKING_ID, -1);
    assert(device.attributes[0][ABS_MT_TRACKING_ID] == -1);
    send_event(EV_ABS, ABS_MT_TRACKING_ID, 65535);
    assert(device.attributes[0][ABS_MT_TRACKING_ID] == 2); /* New lifetime. */
    emit_macro_up();
    consume();
    assert(device.attributes[10][ABS_MT_TRACKING_ID] == -1);
    assert(device.attributes[0][ABS_MT_TRACKING_ID] == 2);
    assert(device.attributes[1][ABS_MT_TRACKING_ID] == 65534);
    assert(device.attributes[2][ABS_MT_TRACKING_ID] == 1);
    assert(!g_fatal && !g_io_error);
}

static void assert_closed(void) {
    assert(g_fatal && !g_merging && g_test_cleanup_count > 0);
    size_t before = g_test_event_count;
    send_event(EV_SYN, SYN_REPORT, 0);
    send_event(EV_ABS, ABS_MT_POSITION_X, 999);
    assert(g_test_event_count == before);
}

static void test_fail_closed(void) {
    reset_test_state();
    send_event(EV_SYN, SYN_DROPPED, 0);
    assert_closed();
    for (int slot = -1; slot <= 4; slot += 5) {
        reset_test_state();
        send_event(EV_ABS, ABS_MT_SLOT, slot);
        assert_closed();
        assert(g_test_event_count == 0);
    }
    reset_test_state();
    send_event(EV_ABS, ABS_MT_TRACKING_ID, 65536);
    assert_closed();
    reset_test_state();
    send_event(EV_ABS, ABS_MT_TRACKING_ID, 77);
    send_event(EV_ABS, ABS_MT_SLOT, 1);
    send_event(EV_ABS, ABS_MT_TRACKING_ID, 77);
    assert_closed();
    reset_test_state();
    g_test_write_failure = true;
    send_event(EV_ABS, ABS_MT_SLOT, 2);
    assert(g_io_error);
    fail_closed();
    assert_closed();
}

static int stationary_finger(int32_t *ids, int count) {
    assert(ids[0] == ABS_MT_TRACKING_ID);
    for (int i = 1; i <= count; ++i) ids[i] = -1;
    ids[2] = 123;
    return 0;
}

static void test_idle_and_io(void) {
    int pipes[2];
    assert(pipe(pipes) == 0);
    assert(fcntl(pipes[0], F_SETFL, O_NONBLOCK) == 0);
    reset_test_state();
    g_test_query_slots = stationary_finger;
    /* Empty pipe simulates no events, but the kernel snapshot has a finger. */
    assert(wait_until_idle(pipes[0], 210) == -1);
    g_test_query_slots = NULL;
    assert(wait_until_idle(pipes[0], 210) == -1);
    close(pipes[0]);
    close(pipes[1]);

    const short flags[] = {POLLERR, POLLHUP, POLLNVAL};
    for (size_t i = 0; i < sizeof(flags) / sizeof(flags[0]); ++i) {
        for (int real = 0; real < 2; ++real) {
            reset_test_state();
            assert(!check_poll_result(1, 0, real ? 0 : flags[i], real ? flags[i] : 0));
            assert_closed();
        }
    }
    reset_test_state();
    assert(!check_poll_result(-1, EIO, 0, 0));
    assert_closed();
    const ssize_t sizes[] = {0, 1, -1};
    for (size_t i = 0; i < sizeof(sizes) / sizeof(sizes[0]); ++i) {
        reset_test_state();
        assert(!valid_real_read(sizes[i], EIO));
        assert_closed();
    }
    reset_test_state();
    assert(valid_real_read(-1, EAGAIN));
    assert(valid_real_read(-1, EINTR));
    assert(check_poll_result(-1, EINTR, 0, 0));
    assert(!g_fatal);
}

static void test_options(void) {
    reset_test_state();
    char *defaults[] = {"touchmux"};
    assert(parse_options(1, defaults, 1000));
    assert(!g_no_tap && !g_diagnostic_deadline_ms);
    char *low[] = {"touchmux", "--diagnostic-seconds", "1", "--no-tap"};
    assert(parse_options(4, low, 1000));
    assert(g_no_tap && g_diagnostic_deadline_ms == 2000);
    char *high[] = {"touchmux", "--no-tap", "--diagnostic-seconds", "15"};
    assert(parse_options(4, high, 1000));
    assert(g_no_tap && g_diagnostic_deadline_ms == 16000);
    const char *invalid[] = {"", "0", "16", "-1", "+1", " 1", "1 ",
                             "1.5", "1s", "99999999999999999999999"};
    for (size_t i = 0; i < sizeof(invalid) / sizeof(invalid[0]); ++i) {
        char *args[] = {"touchmux", "--diagnostic-seconds", (char *)invalid[i]};
        assert(!parse_options(3, args, 1000));
        assert(g_no_tap && g_diagnostic_deadline_ms == 16000);
        /* Invalid CLI returns before opening any device. */
        assert(touchmux_program_main(3, args) == 2);
    }
    char *missing[] = {"touchmux", "--diagnostic-seconds"};
    char *unknown[] = {"touchmux", "--unknown"};
    char *duplicate[] = {"touchmux", "--no-tap", "--no-tap"};
    char *duplicate_time[] = {"touchmux", "--diagnostic-seconds", "1",
                              "--diagnostic-seconds", "2"};
    assert(!parse_options(2, missing, 1000));
    assert(!parse_options(2, unknown, 1000));
    assert(!parse_options(3, duplicate, 1000));
    assert(!parse_options(5, duplicate_time, 1000));
    char *no_tap[] = {"touchmux", "--no-tap"};
    assert(parse_options(2, no_tap, 1000));
    assert(g_no_tap && !g_diagnostic_deadline_ms);
    assert(parse_options(1, defaults, 1000));
    assert(!g_no_tap && !g_diagnostic_deadline_ms);
}

static void test_no_tap(void) {
    for (int merging = 0; merging < 2; ++merging) {
        for (int active = 0; active < 2; ++active) {
            reset_test_state();
            g_no_tap = true;
            g_merging = merging != 0;
            g_tap.active = g_tap.down_sent = active != 0;
            g_tap.x = 12;
            g_tap.y = 34;
            g_tap.up_deadline_ms = 5678;
            struct pending_tap before;
            memcpy(&before, &g_tap, sizeof(before));
            char tap[] = "TAP 50 60 20";
            handle_command(tap);
            assert(memcmp(&before, &g_tap, sizeof(before)) == 0);
            assert(g_test_event_count == 0 && g_test_cleanup_count == 0);
        }
    }
}

static void test_diagnostic_deadline(void) {
    reset_test_state();
    g_test_now_ms = 1000;
    char *args[] = {"touchmux", "--diagnostic-seconds", "1"};
    assert(parse_options(3, args, g_test_now_ms));
    for (g_test_now_ms = 1100; g_test_now_ms < 2000; g_test_now_ms += 100) {
        char ping[] = "PING";
        handle_command(ping);
        assert(g_last_ping_ms == g_test_now_ms);
        assert(g_diagnostic_deadline_ms == 2000);
        assert(!check_diagnostic_deadline());
    }
    char ping[] = "PING";
    handle_command(ping); /* Exact boundary, even with a fresh heartbeat. */
    assert(g_last_ping_ms == 1900);
    assert_closed();
    int cleanups = g_test_cleanup_count;
    assert(check_diagnostic_deadline());
    assert(g_test_cleanup_count == cleanups);

    /* Simulate slow ENGAGE completion: no OK MERGING may escape. */
    reset_test_state();
    assert(parse_options(3, args, 1000));
    g_test_now_ms = 2001;
    int output[2];
    assert(pipe(output) == 0);
    fflush(stdout);
    int saved = dup(STDOUT_FILENO);
    assert(saved >= 0 && dup2(output[1], STDOUT_FILENO) >= 0);
    close(output[1]);
    assert(finish_engage() == -1);
    fflush(stdout);
    assert(dup2(saved, STDOUT_FILENO) >= 0);
    close(saved);
    char response[128] = {0};
    assert(read(output[0], response, sizeof(response) - 1) > 0);
    close(output[0]);
    assert(strcmp(response, "ERR diagnostic_expired\n") == 0);
    assert_closed();
    assert(g_last_ping_ms == 0);
    assert(do_engage(100, 100) == -1);

    reset_test_state();
    assert(parse_options(3, args, 1000));
    g_test_now_ms = 2000;
    /* Expired idle wait must stop before attempting a device read. */
    assert(wait_until_idle(-1, IDLE_WAIT_MS) == -1);
    assert_closed();

    reset_test_state();
    g_test_now_ms = 999999;
    assert(!check_diagnostic_deadline()); /* Default mode has no deadline. */
    assert(!g_fatal && g_test_cleanup_count == 0);
}

static void test_optional_axes(void) {
    reset_test_state();
    unsigned long bits[(ABS_MAX / (sizeof(unsigned long) * 8)) + 1] = {0};
    const int word_bits = sizeof(unsigned long) * 8;
    bits[ABS_MT_TOUCH_MAJOR / word_bits] |= 1UL << (ABS_MT_TOUCH_MAJOR % word_bits);
    configure_optional_axes(bits);
    assert(g_has_touch_major && !g_has_pressure);
    g_tap.active = true;
    g_tap.x = 10;
    g_tap.y = 20;
    emit_macro_down();
    bool saw_major = false;
    for (size_t i = 0; i < g_test_event_count; ++i) {
        const struct input_event *ev = &g_test_events[i];
        if (ev->type != EV_ABS) continue;
        assert(ev->code != ABS_MT_PRESSURE);
        if (ev->code == ABS_MT_TOUCH_MAJOR) saw_major = true;
    }
    assert(saw_major);
    memset(bits, 0, sizeof(bits));
    configure_optional_axes(bits);
    assert(!g_has_pressure && !g_has_touch_major);
}

static void test_cancel_tap(void) {
    reset_test_state();
    send_event(EV_ABS, ABS_MT_SLOT, 1);
    send_event(EV_ABS, ABS_MT_TRACKING_ID, 12);
    send_event(EV_SYN, SYN_REPORT, 0);
    int real_id = device.attributes[1][ABS_MT_TRACKING_ID];
    g_tap.active = true;
    emit_macro_down();
    char cancel[] = "CANCEL_TAP";
    handle_command(cancel);
    consume();
    assert(!g_tap.active && !g_tap.down_sent);
    assert(device.attributes[1][ABS_MT_TRACKING_ID] == real_id);
    assert(device.attributes[g_macro_slot][ABS_MT_TRACKING_ID] == -1);
    assert(g_test_cleanup_count == 0);
    size_t count = g_test_event_count;
    handle_command(cancel);
    assert(g_test_event_count == count);

    g_tap.active = true;
    emit_macro_down();
    send_event(EV_ABS, ABS_MT_POSITION_X, 100);
    count = g_test_event_count;
    handle_command(cancel);
    assert(g_test_event_count == count); /* No SYN inside a real frame. */
    send_event(EV_SYN, SYN_REPORT, 0);
    assert(!g_tap.active && !g_tap.down_sent);
    assert(device.attributes[1][ABS_MT_TRACKING_ID] == real_id);
    assert(g_test_cleanup_count == 0);
}

int main(void) {
    test_cancel_tap();
    test_optional_axes();
    test_options();
    test_no_tap();
    test_diagnostic_deadline();
    test_slot_stream();
    test_tracking_namespace();
    test_fail_closed();
    test_idle_and_io();
    free(g_test_events);
    puts("touchmux stream tests: OK");
    return 0;
}
