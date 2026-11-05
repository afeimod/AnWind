/*
 * WinFusion game controller bus support
 *
 * Copyright 2026 Tux-And-Wine
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 */

#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "ntstatus.h"
#define WIN32_NO_STATUS
#include "windef.h"
#include "winbase.h"
#include "winternl.h"
#include "ddk/hidtypes.h"
#include "ddk/hidsdi.h"
#include "hidusage.h"

#include "wine/debug.h"
#include "wine/hid.h"
#include "wine/unixlib.h"

#include "unix_private.h"

#if defined(__WINFUSION__) && defined(HAVE_SHM_DRIVERS_GAME_CONTROLLER_DRIVER_H)

#include <shm/drivers/game_controller_driver.h>

WINE_DEFAULT_DEBUG_CHANNEL(hid);

#define WINFUSION_RECEIVE_TIMEOUT UINT64_C(100000000)
#define WINFUSION_SEND_TIMEOUT UINT64_C(1000000000)

struct winfusion_device
{
    struct unix_device unix_device;
    uint32_t id;
    BOOL connected;
    BOOL started;
};

static pthread_mutex_t winfusion_cs = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t winfusion_send_cs = PTHREAD_MUTEX_INITIALIZER;
static struct list event_queue = LIST_INIT(event_queue);
static struct winfusion_device *devices[GAME_CONTROLLER_IPC_MAX_CONTROLLERS];
static GameControllerIPCClient *client;
static LONG stopping;
static BOOL disconnected;
static NTSTATUS disconnect_status;

static inline struct winfusion_device *impl_from_unix_device(struct unix_device *iface)
{
    return CONTAINING_RECORD(iface, struct winfusion_device, unix_device);
}

static NTSTATUS status_from_result(GameControllerIPCResult result)
{
    if (result == GAME_CONTROLLER_IPC_OK) return STATUS_SUCCESS;
    if (result == GAME_CONTROLLER_IPC_DISCONNECTED) return STATUS_DEVICE_NOT_CONNECTED;
    return STATUS_UNSUCCESSFUL;
}

static void winfusion_device_destroy(struct unix_device *iface)
{
}

static void queue_input_report(struct winfusion_device *impl)
{
    struct hid_device_state *report = &impl->unix_device.hid_device_state;

    bus_event_queue_input_report(&event_queue, &impl->unix_device,
                                 report->report_buf, report->report_len);
}

static NTSTATUS winfusion_device_start(struct unix_device *iface)
{
    struct winfusion_device *impl = impl_from_unix_device(iface);

    pthread_mutex_lock(&winfusion_cs);
    impl->started = TRUE;
    queue_input_report(impl);
    pthread_mutex_unlock(&winfusion_cs);
    return STATUS_SUCCESS;
}

static void winfusion_device_stop(struct unix_device *iface)
{
    struct winfusion_device *impl = impl_from_unix_device(iface);

    pthread_mutex_lock(&winfusion_cs);
    impl->started = FALSE;
    impl->connected = FALSE;
    if (impl->id < ARRAY_SIZE(devices) && devices[impl->id] == impl) devices[impl->id] = NULL;
    pthread_mutex_unlock(&winfusion_cs);
}

static NTSTATUS winfusion_device_haptics_start(struct unix_device *iface, UINT duration_ms,
                                               USHORT rumble_intensity, USHORT buzz_intensity,
                                               USHORT left_intensity, USHORT right_intensity)
{
    struct winfusion_device *impl = impl_from_unix_device(iface);
    GameControllerIPCRumble rumble =
    {
        .controllerId = impl->id,
        .low = rumble_intensity,
        .high = buzz_intensity,
        .leftTrigger = left_intensity,
        .rightTrigger = right_intensity,
        .durationMs = duration_ms,
    };
    GameControllerIPCClient *current_client;
    GameControllerIPCResult result;

    pthread_mutex_lock(&winfusion_send_cs);
    pthread_mutex_lock(&winfusion_cs);
    current_client = client;
    result = current_client && impl->connected ? GAME_CONTROLLER_IPC_OK :
             GAME_CONTROLLER_IPC_DISCONNECTED;
    pthread_mutex_unlock(&winfusion_cs);
    if (result == GAME_CONTROLLER_IPC_OK)
        result = game_controller_ipc_client_send_rumble(current_client, &rumble,
                                                         WINFUSION_SEND_TIMEOUT);
    pthread_mutex_unlock(&winfusion_send_cs);
    return status_from_result(result);
}

static NTSTATUS winfusion_device_haptics_stop(struct unix_device *iface)
{
    return winfusion_device_haptics_start(iface, 0, 0, 0, 0, 0);
}

static NTSTATUS winfusion_device_physical_device_control(struct unix_device *iface, USAGE control)
{
    return STATUS_NOT_SUPPORTED;
}

static NTSTATUS winfusion_device_physical_device_set_gain(struct unix_device *iface, BYTE percent)
{
    return STATUS_NOT_SUPPORTED;
}

static NTSTATUS winfusion_device_physical_effect_control(struct unix_device *iface, BYTE index,
                                                          USAGE control, BYTE iterations)
{
    return STATUS_NOT_SUPPORTED;
}

static NTSTATUS winfusion_device_physical_effect_update(struct unix_device *iface, BYTE index,
                                                         struct effect_params *params)
{
    return STATUS_NOT_SUPPORTED;
}

static const struct hid_device_vtbl winfusion_device_vtbl =
{
    winfusion_device_destroy,
    winfusion_device_start,
    winfusion_device_stop,
    winfusion_device_haptics_start,
    winfusion_device_haptics_stop,
    winfusion_device_physical_device_control,
    winfusion_device_physical_device_set_gain,
    winfusion_device_physical_effect_control,
    winfusion_device_physical_effect_update,
};

static NTSTATUS build_report_descriptor(struct unix_device *iface)
{
    const USAGE_AND_PAGE usage = {.UsagePage = HID_USAGE_PAGE_GENERIC, .Usage = HID_USAGE_GENERIC_GAMEPAD};
    static const USAGE stick_usages[] =
    {
        HID_USAGE_GENERIC_X, HID_USAGE_GENERIC_Y,
        HID_USAGE_GENERIC_RX, HID_USAGE_GENERIC_RY,
    };
    static const USAGE trigger_usages[] = {HID_USAGE_GENERIC_Z, HID_USAGE_GENERIC_RZ};

    if (!hid_device_begin_report_descriptor(iface, &usage)) return STATUS_NO_MEMORY;
    if (!hid_device_begin_input_report(iface, &usage)) return STATUS_NO_MEMORY;
    if (!hid_device_add_axes(iface, ARRAY_SIZE(stick_usages), HID_USAGE_PAGE_GENERIC,
                             stick_usages, FALSE, -32768, 32767)) return STATUS_NO_MEMORY;
    if (!hid_device_add_axes(iface, ARRAY_SIZE(trigger_usages), HID_USAGE_PAGE_GENERIC,
                             trigger_usages, FALSE, 0, 32767)) return STATUS_NO_MEMORY;
    if (!hid_device_add_hatswitch(iface, 1)) return STATUS_NO_MEMORY;
    if (!hid_device_add_buttons(iface, HID_USAGE_PAGE_BUTTON, 1, 10)) return STATUS_NO_MEMORY;
    if (!hid_device_end_input_report(iface)) return STATUS_NO_MEMORY;
    if (!hid_device_add_haptics(iface)) return STATUS_NO_MEMORY;
    if (!hid_device_end_report_descriptor(iface)) return STATUS_NO_MEMORY;
    return STATUS_SUCCESS;
}

static struct winfusion_device *create_device(uint32_t id)
{
    static const WCHAR manufacturer[] = {'M','i','c','r','o','s','o','f','t',0};
    static const WCHAR product[] =
    {
        'W','i','n','F','u','s','i','o','n',' ','X','b','o','x',' ','3','6','0',' ',
        'C','o','n','t','r','o','l','l','e','r',' ','1',0
    };
    static const WCHAR serial[] = {'W','I','N','F','U','S','I','O','N','-','1',0};
    struct device_desc desc =
    {
        .vid = 0x045e,
        .pid = 0x028e,
        .version = 0x0114,
        .input = -1,
        .uid = id + 1,
        .is_gamepad = TRUE,
        .is_hidraw = FALSE,
    };
    struct winfusion_device *impl;

    memcpy(desc.manufacturer, manufacturer, sizeof(manufacturer));
    memcpy(desc.product, product, sizeof(product));
    memcpy(desc.serialnumber, serial, sizeof(serial));
    desc.product[ARRAY_SIZE(product) - 2] = '1' + id;
    desc.serialnumber[ARRAY_SIZE(serial) - 2] = '1' + id;

    if (!(impl = hid_device_create(&winfusion_device_vtbl, sizeof(*impl)))) return NULL;
    impl->id = id;
    impl->connected = TRUE;
    if (build_report_descriptor(&impl->unix_device))
    {
        impl->unix_device.vtbl->destroy(&impl->unix_device);
        free(impl);
        return NULL;
    }
    if (!bus_event_queue_device_created(&event_queue, &impl->unix_device, &desc))
    {
        impl->unix_device.vtbl->destroy(&impl->unix_device);
        free(impl);
        return NULL;
    }
    return impl;
}

static void remove_device(uint32_t id)
{
    struct winfusion_device *impl = devices[id];

    if (!impl) return;
    if (!bus_event_queue_device_removed(&event_queue, &impl->unix_device)) return;
    devices[id] = NULL;
    impl->connected = FALSE;
}

static void remove_all_devices(void)
{
    unsigned int i;

    for (i = 0; i < ARRAY_SIZE(devices); ++i) remove_device(i);
}

static void set_hatswitch(struct unix_device *iface, uint32_t hat)
{
    static const int x[] = {0, 1, 1, 1, 0, -1, -1, -1};
    static const int y[] = {-1, -1, 0, 1, 1, 1, 0, -1};

    if (hat < 1 || hat > 8)
    {
        hid_device_set_hatswitch_x(iface, 0, 0);
        hid_device_set_hatswitch_y(iface, 0, 0);
        return;
    }
    hid_device_set_hatswitch_x(iface, 0, x[hat - 1]);
    hid_device_set_hatswitch_y(iface, 0, y[hat - 1]);
}

static void process_state(const GameControllerIPCState *state)
{
    struct winfusion_device *impl;
    unsigned int i;

    if (state->controllerId >= ARRAY_SIZE(devices))
    {
        WARN("ignoring WinFusion controller id %u\n", state->controllerId);
        return;
    }

    impl = devices[state->controllerId];
    if (!state->connected)
    {
        remove_device(state->controllerId);
        return;
    }

    if (!impl)
    {
        if (!(impl = create_device(state->controllerId)))
        {
            WARN("failed to create WinFusion controller id %u\n", state->controllerId);
            return;
        }
        devices[state->controllerId] = impl;
    }

    hid_device_set_abs_axis(&impl->unix_device, 0, state->leftX);
    hid_device_set_abs_axis(&impl->unix_device, 1, state->leftY);
    hid_device_set_abs_axis(&impl->unix_device, 2, state->rightX);
    hid_device_set_abs_axis(&impl->unix_device, 3, state->rightY);
    hid_device_set_abs_axis(&impl->unix_device, 4, state->leftTrigger >> 1);
    hid_device_set_abs_axis(&impl->unix_device, 5, state->rightTrigger >> 1);
    set_hatswitch(&impl->unix_device, state->hat);
    for (i = 0; i < 10; ++i)
        hid_device_set_button(&impl->unix_device, i, state->buttons & (1u << i));

    if (impl->started) queue_input_report(impl);
}

NTSTATUS winfusion_bus_init(void *args)
{
    GameControllerIPCClient *new_client = NULL;
    GameControllerIPCResult result;

    InterlockedExchange(&stopping, 0);
    pthread_mutex_lock(&winfusion_cs);
    list_init(&event_queue);
    memset(devices, 0, sizeof(devices));
    disconnected = FALSE;
    disconnect_status = STATUS_SUCCESS;
    pthread_mutex_unlock(&winfusion_cs);

    result = game_controller_ipc_client_connect_from_env(&new_client, WINFUSION_SEND_TIMEOUT);
    if (result != GAME_CONTROLLER_IPC_OK)
    {
        if (new_client) game_controller_ipc_client_destroy(new_client);
        WARN("failed to connect WinFusion controller IPC client, result %u\n", result);
        return status_from_result(result);
    }

    pthread_mutex_lock(&winfusion_cs);
    client = new_client;
    pthread_mutex_unlock(&winfusion_cs);
    return STATUS_SUCCESS;
}

NTSTATUS winfusion_bus_wait(void *args)
{
    struct bus_event *event = args;
    GameControllerIPCState state;
    GameControllerIPCClient *old_client;
    GameControllerIPCResult result;
    NTSTATUS status;
    unsigned int i;

    bus_event_cleanup(event);

    for (;;)
    {
        pthread_mutex_lock(&winfusion_cs);
        if (bus_event_queue_pop(&event_queue, event))
        {
            pthread_mutex_unlock(&winfusion_cs);
            return STATUS_PENDING;
        }
        if (disconnected || InterlockedOr(&stopping, 0))
        {
            status = disconnected ? disconnect_status : STATUS_SUCCESS;
            old_client = client;
            client = NULL;
            for (i = 0; i < ARRAY_SIZE(devices); ++i)
            {
                if (devices[i]) devices[i]->connected = FALSE;
                devices[i] = NULL;
            }
            bus_event_queue_destroy(&event_queue);
            pthread_mutex_unlock(&winfusion_cs);
            if (old_client)
            {
                pthread_mutex_lock(&winfusion_send_cs);
                game_controller_ipc_client_close(old_client, WINFUSION_SEND_TIMEOUT);
                game_controller_ipc_client_destroy(old_client);
                pthread_mutex_unlock(&winfusion_send_cs);
            }
            return status;
        }
        old_client = client;
        pthread_mutex_unlock(&winfusion_cs);

        result = game_controller_ipc_client_receive_state(old_client, &state,
                                                           WINFUSION_RECEIVE_TIMEOUT);
        if (result == GAME_CONTROLLER_IPC_WOULD_BLOCK ||
            result == GAME_CONTROLLER_IPC_TIMED_OUT) continue;

        pthread_mutex_lock(&winfusion_cs);
        if (result == GAME_CONTROLLER_IPC_OK) process_state(&state);
        else
        {
            disconnected = TRUE;
            disconnect_status = status_from_result(result);
            remove_all_devices();
        }
        pthread_mutex_unlock(&winfusion_cs);
    }
}

NTSTATUS winfusion_bus_stop(void *args)
{
    InterlockedExchange(&stopping, 1);
    return STATUS_SUCCESS;
}

#else

NTSTATUS winfusion_bus_init(void *args)
{
    return STATUS_NOT_IMPLEMENTED;
}

NTSTATUS winfusion_bus_wait(void *args)
{
    return STATUS_NOT_IMPLEMENTED;
}

NTSTATUS winfusion_bus_stop(void *args)
{
    return STATUS_SUCCESS;
}

#endif
