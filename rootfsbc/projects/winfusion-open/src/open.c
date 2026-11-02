/*
 * open - 容器里点开的链接的落点程序。
 *
 * wine 的 winebrowser 在没有 Windows 浏览器时会把 http / https / mailto 交给它
 * （candidates 见 programs/winebrowser/main.c 的 open_helper_path()）。
 * 这里只做一件事：把整串目标经共享内存送给安卓侧（OpenDriver），由那边决定是不是
 * 真的用安卓浏览器打开。通道与协议见 shm/drivers/open_driver.h。
 *
 * 共享内存 socket 路径由容器启动时注入的 WINFUSION_SHM 给出，这个进程是从 wine
 * 继承环境变量启动的，不需要自己找路径。
 *
 * 退出码：给定状态（0=已打开、1=设置里关掉了、2=没有应用能处理、3=不是链接、
 * 4=打开失败），连不上前端时另给 3/4 之外的码并在 stderr 说明。
 */

#include <shm/drivers/open_driver.h>

#include <stdio.h>

/* 连不上 / 前端没起来时不要挂着：这个进程是 winebrowser 顺手 exec 出来的、
 * 没有人等它的退出码，卡住只会留下一个醒不来的进程。 */
#define OPEN_TIMEOUT_NS UINT64_C(5000000000)

static const char *status_text(uint32_t status)
{
    switch (status) {
    case OPEN_IPC_STATUS_OK:        return "opened";
    case OPEN_IPC_STATUS_DISABLED:  return "disabled in settings";
    case OPEN_IPC_STATUS_UNHANDLED: return "no app can handle it";
    case OPEN_IPC_STATUS_INVALID:   return "not a link";
    default:                        return "failed";
    }
}

int main(int argc, char **argv)
{
    OpenIPCClient *client = NULL;
    OpenIPCResult result;
    uint32_t status = OPEN_IPC_STATUS_FAILED;
    const char *target;

    if (argc != 2 || !argv[1][0]) {
        fprintf(stderr, "open: expected exactly one link, got %d argument(s)\n",
                argc - 1);
        return 5;
    }
    target = argv[1];

    result = open_ipc_client_connect_from_env(&client, OPEN_TIMEOUT_NS);
    if (result != OPEN_IPC_OK) {
        /* 容器没在跑，或者前端没起共享内存服务。 */
        fprintf(stderr, "open: cannot reach the Android side: %d\n", result);
        return 5;
    }
    result = open_ipc_client_open(client, target, &status, OPEN_TIMEOUT_NS);
    if (result != OPEN_IPC_OK) {
        fprintf(stderr, "open: failed to send \"%s\": %d\n", target, result);
        open_ipc_client_destroy(client);
        return 5;
    }
    open_ipc_client_close(client, OPEN_TIMEOUT_NS);
    open_ipc_client_destroy(client);

    if (status != OPEN_IPC_STATUS_OK)
        fprintf(stderr, "open: \"%s\": %s (%u)\n", target, status_text(status),
                status);
    return (int) status;
}
