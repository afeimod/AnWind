#if 0
#pragma makedep unix
#endif

#include "config.h"

#ifndef __WINE_PE_BUILD

#include <pthread.h>
#include <stdint.h>
#include <string.h>

#include "ntstatus.h"
#define WIN32_NO_STATUS
#include "windef.h"
#include "winbase.h"
#include "winternl.h"
#include "wine/unixlib.h"

#include <shm/drivers/task_manager_driver.h>
#include "unixlib.h"

#define WINFUSIONTM_TIMEOUT UINT64_C(2000000000)
#define WINFUSIONTM_POLL_TIMEOUT UINT64_C(100000000)

static pthread_mutex_t client_mutex = PTHREAD_MUTEX_INITIALIZER;
static TaskManagerIPCClient *client;

static UINT map_result(TaskManagerIPCResult result)
{
    switch (result)
    {
    case TASK_MANAGER_IPC_OK:
        return STATUS_SUCCESS;
    case TASK_MANAGER_IPC_TIMED_OUT:
    case TASK_MANAGER_IPC_WOULD_BLOCK:
        return STATUS_TIMEOUT;
    case TASK_MANAGER_IPC_DISCONNECTED:
        return STATUS_CONNECTION_DISCONNECTED;
    case TASK_MANAGER_IPC_INVALID_ARGUMENT:
        return STATUS_INVALID_PARAMETER;
    case TASK_MANAGER_IPC_PROTOCOL_ERROR:
        return STATUS_PROTOCOL_UNREACHABLE;
    case TASK_MANAGER_IPC_BUSY:
        return STATUS_DEVICE_BUSY;
    case TASK_MANAGER_IPC_SYSTEM_ERROR:
    default:
        return STATUS_UNSUCCESSFUL;
    }
}

static NTSTATUS winfusiontm_connect(void *args)
{
    struct winfusiontm_result_params *params = args;
    TaskManagerIPCClient *new_client = NULL;
    TaskManagerIPCResult result;

    pthread_mutex_lock(&client_mutex);
    if (client)
    {
        params->result = STATUS_SUCCESS;
        pthread_mutex_unlock(&client_mutex);
        return STATUS_SUCCESS;
    }
    result = task_manager_ipc_client_connect_from_env(&new_client, WINFUSIONTM_TIMEOUT);
    if (result == TASK_MANAGER_IPC_OK)
        client = new_client;
    params->result = map_result(result);
    pthread_mutex_unlock(&client_mutex);
    return STATUS_SUCCESS;
}

static NTSTATUS winfusiontm_disconnect(void *args)
{
    struct winfusiontm_result_params *params = args;
    TaskManagerIPCClient *old_client;

    pthread_mutex_lock(&client_mutex);
    old_client = client;
    client = NULL;
    pthread_mutex_unlock(&client_mutex);

    if (old_client)
    {
        task_manager_ipc_client_close(old_client, WINFUSIONTM_TIMEOUT);
        task_manager_ipc_client_destroy(old_client);
    }
    params->result = STATUS_SUCCESS;
    return STATUS_SUCCESS;
}

static NTSTATUS winfusiontm_publish_process(void *args)
{
    struct winfusiontm_process_params *params = args;
    TaskManagerIPCProcess process;
    TaskManagerIPCResult result;

    memset(&process, 0, sizeof(process));
    process.pid = params->pid;
    process.affinityMask = params->affinity_mask;
    process.memoryUsage = params->memory_usage;
    process.wow64Process = params->wow64 ? 1 : 0;
    memcpy(process.name, params->name, sizeof(process.name));
    process.name[sizeof(process.name) - 1] = 0;

    pthread_mutex_lock(&client_mutex);
    if (!client)
    {
        params->result = STATUS_CONNECTION_DISCONNECTED;
        pthread_mutex_unlock(&client_mutex);
        return STATUS_SUCCESS;
    }
    result = task_manager_ipc_client_publish_process(client, &process,
            params->index, params->count, WINFUSIONTM_TIMEOUT);
    params->result = map_result(result);
    if (result == TASK_MANAGER_IPC_DISCONNECTED ||
        result == TASK_MANAGER_IPC_PROTOCOL_ERROR)
    {
        task_manager_ipc_client_destroy(client);
        client = NULL;
    }
    pthread_mutex_unlock(&client_mutex);
    return STATUS_SUCCESS;
}

static NTSTATUS winfusiontm_list_done(void *args)
{
    struct winfusiontm_list_done_params *params = args;
    TaskManagerIPCResult result;

    pthread_mutex_lock(&client_mutex);
    if (!client)
    {
        params->result = STATUS_CONNECTION_DISCONNECTED;
        pthread_mutex_unlock(&client_mutex);
        return STATUS_SUCCESS;
    }
    result = task_manager_ipc_client_list_done(client, params->count, WINFUSIONTM_TIMEOUT);
    params->result = map_result(result);
    if (result == TASK_MANAGER_IPC_DISCONNECTED ||
        result == TASK_MANAGER_IPC_PROTOCOL_ERROR)
    {
        task_manager_ipc_client_destroy(client);
        client = NULL;
    }
    pthread_mutex_unlock(&client_mutex);
    return STATUS_SUCCESS;
}

static NTSTATUS winfusiontm_poll_command(void *args)
{
    struct winfusiontm_command_params *params = args;
    SHMRingEvent event;
    TaskManagerIPCResult result;

    memset(params, 0, sizeof(*params));
    params->opcode = WINFUSIONTM_OPCODE_NONE;

    pthread_mutex_lock(&client_mutex);
    if (!client)
    {
        params->result = STATUS_CONNECTION_DISCONNECTED;
        pthread_mutex_unlock(&client_mutex);
        return STATUS_SUCCESS;
    }
    result = task_manager_ipc_client_receive_command(client, &event, WINFUSIONTM_POLL_TIMEOUT);
    if (result == TASK_MANAGER_IPC_TIMED_OUT || result == TASK_MANAGER_IPC_WOULD_BLOCK)
    {
        params->result = STATUS_TIMEOUT;
        pthread_mutex_unlock(&client_mutex);
        return STATUS_SUCCESS;
    }
    if (result != TASK_MANAGER_IPC_OK)
    {
        params->result = map_result(result);
        if (result == TASK_MANAGER_IPC_DISCONNECTED ||
            result == TASK_MANAGER_IPC_PROTOCOL_ERROR)
        {
            task_manager_ipc_client_destroy(client);
            client = NULL;
        }
        pthread_mutex_unlock(&client_mutex);
        return STATUS_SUCCESS;
    }

    switch (event.opcode)
    {
    case TASK_MANAGER_IPC_OPCODE_KILL:
        params->opcode = WINFUSIONTM_OPCODE_KILL;
        params->mode = event.payload[0] | (event.payload[1] << 8) |
                       (event.payload[2] << 16) | (event.payload[3] << 24);
        params->pid = event.payload[4] | (event.payload[5] << 8) |
                      (event.payload[6] << 16) | (event.payload[7] << 24);
        memcpy(params->name, event.payload + 8, sizeof(params->name));
        break;
    case TASK_MANAGER_IPC_OPCODE_EXEC:
        params->opcode = WINFUSIONTM_OPCODE_EXEC;
        memcpy(params->name, event.payload, 64);
        memcpy(params->parameters, event.payload + 64, 64);
        break;
    case TASK_MANAGER_IPC_OPCODE_AFFINITY:
        params->opcode = WINFUSIONTM_OPCODE_AFFINITY;
        params->pid = event.payload[0] | (event.payload[1] << 8) |
                      (event.payload[2] << 16) | (event.payload[3] << 24);
        params->affinity_mask = event.payload[4] | (event.payload[5] << 8) |
                                (event.payload[6] << 16) | (event.payload[7] << 24);
        memcpy(params->name, event.payload + 8, sizeof(params->name));
        break;
    case TASK_MANAGER_IPC_OPCODE_BRING_FRONT:
        params->opcode = WINFUSIONTM_OPCODE_BRING_FRONT;
        memcpy(params->name, event.payload, 64);
        params->window_handle = 0;
        for (int i = 0; i < 8; i++)
            params->window_handle |= (UINT64)event.payload[64 + i] << (8 * i);
        break;
    default:
        params->result = STATUS_PROTOCOL_UNREACHABLE;
        pthread_mutex_unlock(&client_mutex);
        return STATUS_SUCCESS;
    }
    params->name[sizeof(params->name) - 1] = 0;
    params->parameters[sizeof(params->parameters) - 1] = 0;
    params->result = STATUS_SUCCESS;
    pthread_mutex_unlock(&client_mutex);
    return STATUS_SUCCESS;
}

const unixlib_entry_t __wine_unix_call_funcs[] =
{
    winfusiontm_connect,
    winfusiontm_disconnect,
    winfusiontm_publish_process,
    winfusiontm_list_done,
    winfusiontm_poll_command,
};

C_ASSERT(ARRAY_SIZE(__wine_unix_call_funcs) == unix_winfusiontm_funcs_count);

#ifdef _WIN64

const unixlib_entry_t __wine_unix_call_wow64_funcs[] =
{
    winfusiontm_connect,
    winfusiontm_disconnect,
    winfusiontm_publish_process,
    winfusiontm_list_done,
    winfusiontm_poll_command,
};

C_ASSERT(ARRAY_SIZE(__wine_unix_call_wow64_funcs) == unix_winfusiontm_funcs_count);

#endif /* _WIN64 */

#endif /* !__WINE_PE_BUILD */
