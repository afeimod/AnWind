#include <windows.h>
#include "wine/debug.h"

WINE_DEFAULT_DEBUG_CHANNEL(wftm);

int WINAPI WinfusionTaskManager_Run(void);

int __cdecl wmain(int argc, WCHAR *argv[])
{
    (void)argc;
    (void)argv;
    TRACE("starting WinFusion task manager agent\n");
    return WinfusionTaskManager_Run();
}
