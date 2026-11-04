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

int main(void) {
    test_slot_stream();
    test_tracking_namespace();
    test_fail_closed();
    test_idle_and_io();
    free(g_test_events);
    puts("touchmux stream tests: OK");
    return 0;
}
