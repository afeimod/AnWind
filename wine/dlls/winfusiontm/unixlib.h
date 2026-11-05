#ifndef __WINE_WINFUSIONTM_UNIXLIB_H
#define __WINE_WINFUSIONTM_UNIXLIB_H

#include <stdint.h>
#include "windef.h"
#include "winternl.h"
#include "wine/unixlib.h"

struct winfusiontm_result_params
{
    UINT result;
};

struct winfusiontm_process_params
{
    UINT pid;
    UINT affinity_mask;
    UINT64 memory_usage;
    UINT wow64;
    UINT index;
    UINT count;
    char name[64];
    UINT result;
};

struct winfusiontm_list_done_params
{
    UINT count;
    UINT result;
};

struct winfusiontm_command_params
{
    UINT opcode;
    UINT mode;
    UINT pid;
    UINT affinity_mask;
    UINT64 window_handle;
    char name[64];
    char parameters[64];
    UINT result;
};

enum winfusiontm_unix_funcs
{
    unix_winfusiontm_connect,
    unix_winfusiontm_disconnect,
    unix_winfusiontm_publish_process,
    unix_winfusiontm_list_done,
    unix_winfusiontm_poll_command,
    unix_winfusiontm_funcs_count
};

#define WINFUSIONTM_OPCODE_NONE        0
#define WINFUSIONTM_OPCODE_KILL        1
#define WINFUSIONTM_OPCODE_EXEC        2
#define WINFUSIONTM_OPCODE_AFFINITY    3
#define WINFUSIONTM_OPCODE_BRING_FRONT 4

#endif
