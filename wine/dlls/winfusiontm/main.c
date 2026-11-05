#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include "ntstatus.h"
#define WIN32_NO_STATUS
#include "windef.h"
#include "winbase.h"
#include "winuser.h"
#include "winternl.h"
#include "tlhelp32.h"
#include "psapi.h"
#include "shellapi.h"
#include "wine/debug.h"
#include "wine/unixlib.h"

#include "unixlib.h"

WINE_DEFAULT_DEBUG_CHANNEL(winfusiontm);

static BOOL running = TRUE;
static HANDLE stop_event;

static void ascii_from_wide(char *dst, size_t dst_size, const WCHAR *src)
{
    size_t i;

    if (!dst_size)
        return;
    for (i = 0; i + 1 < dst_size && src && src[i]; i++)
        dst[i] = (src[i] < 128) ? (char)src[i] : '?';
    dst[i] = 0;
}

static BOOL is_wow64_process(HANDLE process)
{
    BOOL wow64 = FALSE;

    if (!IsWow64Process(process, &wow64))
        return FALSE;
    return wow64;
}

static UINT64 process_memory_usage(HANDLE process)
{
    PROCESS_MEMORY_COUNTERS pmc;

    memset(&pmc, 0, sizeof(pmc));
    pmc.cb = sizeof(pmc);
    if (!GetProcessMemoryInfo(process, &pmc, sizeof(pmc)))
        return 0;
    return pmc.WorkingSetSize;
}

static UINT process_affinity_mask(HANDLE process)
{
    DWORD_PTR process_mask = 0, system_mask = 0;

    if (!GetProcessAffinityMask(process, &process_mask, &system_mask))
        return 0;
    return (UINT)process_mask;
}

static void publish_processes(void)
{
    HANDLE snapshot;
    PROCESSENTRY32W entry;
    struct winfusiontm_process_params process_params;
    struct winfusiontm_list_done_params done_params;
    struct
    {
        UINT pid;
        UINT affinity_mask;
        UINT64 memory_usage;
        UINT wow64;
        char name[64];
    } list[64];
    UINT count = 0;

    snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE)
        return;

    memset(&entry, 0, sizeof(entry));
    entry.dwSize = sizeof(entry);
    if (Process32FirstW(snapshot, &entry))
    {
        do
        {
            HANDLE process;
            if (count >= ARRAY_SIZE(list))
                break;
            memset(&list[count], 0, sizeof(list[count]));
            list[count].pid = entry.th32ProcessID;
            ascii_from_wide(list[count].name, sizeof(list[count].name), entry.szExeFile);
            process = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ,
                                  FALSE, entry.th32ProcessID);
            if (process)
            {
                list[count].memory_usage = process_memory_usage(process);
                list[count].affinity_mask = process_affinity_mask(process);
                list[count].wow64 = is_wow64_process(process) ? 1 : 0;
                CloseHandle(process);
            }
            count++;
        } while (Process32NextW(snapshot, &entry));
    }
    CloseHandle(snapshot);

    for (UINT i = 0; i < count; i++)
    {
        memset(&process_params, 0, sizeof(process_params));
        process_params.pid = list[i].pid;
        process_params.affinity_mask = list[i].affinity_mask;
        process_params.memory_usage = list[i].memory_usage;
        process_params.wow64 = list[i].wow64;
        process_params.index = i;
        process_params.count = count;
        memcpy(process_params.name, list[i].name, sizeof(process_params.name));
        WINE_UNIX_CALL(unix_winfusiontm_publish_process, &process_params);
    }

    memset(&done_params, 0, sizeof(done_params));
    done_params.count = count;
    WINE_UNIX_CALL(unix_winfusiontm_list_done, &done_params);
}

static void kill_process_by_pid(UINT pid)
{
    HANDLE process = OpenProcess(PROCESS_TERMINATE, FALSE, pid);

    if (!process)
        return;
    TerminateProcess(process, 1);
    CloseHandle(process);
}

static void kill_process_by_name(const char *name)
{
    HANDLE snapshot;
    PROCESSENTRY32W entry;
    WCHAR wide[64];
    UINT i;

    if (!name || !name[0])
        return;
    for (i = 0; i + 1 < ARRAY_SIZE(wide) && name[i]; i++)
        wide[i] = (unsigned char)name[i];
    wide[i] = 0;

    snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE)
        return;
    memset(&entry, 0, sizeof(entry));
    entry.dwSize = sizeof(entry);
    if (Process32FirstW(snapshot, &entry))
    {
        do
        {
            if (!lstrcmpiW(entry.szExeFile, wide))
                kill_process_by_pid(entry.th32ProcessID);
        } while (Process32NextW(snapshot, &entry));
    }
    CloseHandle(snapshot);
}

static void exec_command(const char *command, const char *parameters)
{
    WCHAR cmd_w[64], params_w[64];
    UINT i;

    if (!command || !command[0])
        return;
    for (i = 0; i + 1 < ARRAY_SIZE(cmd_w) && command[i]; i++)
        cmd_w[i] = (unsigned char)command[i];
    cmd_w[i] = 0;
    for (i = 0; i + 1 < ARRAY_SIZE(params_w) && parameters && parameters[i]; i++)
        params_w[i] = (unsigned char)parameters[i];
    params_w[i] = 0;
    ShellExecuteW(NULL, L"open", cmd_w, params_w[0] ? params_w : NULL, NULL, SW_SHOWNORMAL);
}

static void set_affinity(UINT pid, UINT mask, const char *name)
{
    HANDLE process;

    if (!pid && name && name[0])
    {
        HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        PROCESSENTRY32W entry;
        WCHAR wide[64];
        UINT i;

        for (i = 0; i + 1 < ARRAY_SIZE(wide) && name[i]; i++)
            wide[i] = (unsigned char)name[i];
        wide[i] = 0;
        if (snapshot == INVALID_HANDLE_VALUE)
            return;
        memset(&entry, 0, sizeof(entry));
        entry.dwSize = sizeof(entry);
        if (Process32FirstW(snapshot, &entry))
        {
            do
            {
                if (!lstrcmpiW(entry.szExeFile, wide))
                {
                    pid = entry.th32ProcessID;
                    break;
                }
            } while (Process32NextW(snapshot, &entry));
        }
        CloseHandle(snapshot);
    }
    if (!pid)
        return;
    process = OpenProcess(PROCESS_SET_INFORMATION, FALSE, pid);
    if (!process)
        return;
    SetProcessAffinityMask(process, mask);
    CloseHandle(process);
}

static BOOL CALLBACK enum_windows_proc(HWND hwnd, LPARAM lparam)
{
    WCHAR *target = (WCHAR *)lparam;
    DWORD window_pid = 0;
    HANDLE snapshot;
    PROCESSENTRY32W entry;
    BOOL match = FALSE;

    GetWindowThreadProcessId(hwnd, &window_pid);
    if (!window_pid)
        return TRUE;
    if (!IsWindowVisible(hwnd))
        return TRUE;

    snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE)
        return TRUE;
    memset(&entry, 0, sizeof(entry));
    entry.dwSize = sizeof(entry);
    if (Process32FirstW(snapshot, &entry))
    {
        do
        {
            if (entry.th32ProcessID == window_pid && !lstrcmpiW(entry.szExeFile, target))
            {
                match = TRUE;
                break;
            }
        } while (Process32NextW(snapshot, &entry));
    }
    CloseHandle(snapshot);
    if (!match)
        return TRUE;

    ShowWindow(hwnd, SW_RESTORE);
    SetForegroundWindow(hwnd);
    return FALSE;
}

static void bring_to_front(const char *name)
{
    WCHAR wide[64];
    UINT i;

    if (!name || !name[0])
        return;
    for (i = 0; i + 1 < ARRAY_SIZE(wide) && name[i]; i++)
        wide[i] = (unsigned char)name[i];
    wide[i] = 0;
    EnumWindows(enum_windows_proc, (LPARAM)wide);
}

static void handle_command(const struct winfusiontm_command_params *command)
{
    switch (command->opcode)
    {
    case WINFUSIONTM_OPCODE_KILL:
        if (command->mode == 1)
            kill_process_by_pid(command->pid);
        else
            kill_process_by_name(command->name);
        break;
    case WINFUSIONTM_OPCODE_EXEC:
        exec_command(command->name, command->parameters);
        break;
    case WINFUSIONTM_OPCODE_AFFINITY:
        set_affinity(command->pid, command->affinity_mask, command->name);
        break;
    case WINFUSIONTM_OPCODE_BRING_FRONT:
        bring_to_front(command->name);
        break;
    default:
        break;
    }
}

static DWORD WINAPI worker_thread(void *arg)
{
    struct winfusiontm_result_params result_params;
    struct winfusiontm_command_params command_params;
    DWORD last_publish = 0;

    (void)arg;
    memset(&result_params, 0, sizeof(result_params));
    if (WINE_UNIX_CALL(unix_winfusiontm_connect, &result_params) ||
        result_params.result != STATUS_SUCCESS)
    {
        ERR("Failed to connect TaskManager IPC: %#x\n", result_params.result);
        return 1;
    }

    while (WaitForSingleObject(stop_event, 0) != WAIT_OBJECT_0)
    {
        DWORD now = GetTickCount();
        if (now - last_publish >= 1000)
        {
            publish_processes();
            last_publish = now;
        }

        memset(&command_params, 0, sizeof(command_params));
        if (!WINE_UNIX_CALL(unix_winfusiontm_poll_command, &command_params) &&
            command_params.result == STATUS_SUCCESS &&
            command_params.opcode != WINFUSIONTM_OPCODE_NONE)
            handle_command(&command_params);
        else
            Sleep(50);
    }

    memset(&result_params, 0, sizeof(result_params));
    WINE_UNIX_CALL(unix_winfusiontm_disconnect, &result_params);
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, void *reserved)
{
    TRACE("(%p, %lu, %p)\n", instance, reason, reserved);
    if (reason == DLL_PROCESS_ATTACH)
        DisableThreadLibraryCalls(instance);
    return TRUE;
}

int WINAPI WinfusionTaskManager_Run(void)
{
    HANDLE thread;
    DWORD wait;

    if (__wine_init_unix_call())
    {
        ERR("Unix library unavailable.\n");
        return 1;
    }

    stop_event = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (!stop_event)
        return 1;
    running = TRUE;
    thread = CreateThread(NULL, 0, worker_thread, NULL, 0, NULL);
    if (!thread)
    {
        CloseHandle(stop_event);
        return 1;
    }

    wait = WaitForSingleObject(thread, INFINITE);
    if (wait == WAIT_FAILED)
        ERR("Worker wait failed.\n");
    CloseHandle(thread);
    CloseHandle(stop_event);
    stop_event = NULL;
    return 0;
}
