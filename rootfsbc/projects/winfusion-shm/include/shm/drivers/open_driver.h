#ifndef WINFUSION_OPEN_DRIVER_H
#define WINFUSION_OPEN_DRIVER_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <shm/shm-ring.h>

#ifdef __cplusplus
extern "C" {
#endif

#define OPEN_IPC_MAGIC UINT32_C(0x504f4657)
#define OPEN_IPC_VERSION_MAJOR UINT16_C(1)
#define OPEN_IPC_VERSION_MINOR UINT16_C(0)
#define OPEN_IPC_CLIENT_TYPE UINT16_C(5)
#define OPEN_IPC_TIMEOUT_INFINITE UINT64_MAX

/* 一次请求最多携带这么长的目标串（URL / mailto:）。容器里点开的链接可能带很长
 * 的查询串，2048 不够用，所以留到 4096 并按分片发送。 */
#define OPEN_IPC_TARGET_MAX UINT32_C(4096)

/* 单条事件里能放的字节数：事件 payload 224，减去下面 20 字节的头。 */
#define OPEN_IPC_CHUNK_SIZE UINT32_C(204)

/* 容量必须是 2 的幂（ring 用 & (capacity-1) 取模）。
 * 4096 字节目标串要 21 个分片，再加服务端要回一条 RESULT，32 够用。 */
#define OPEN_IPC_RING_CAPACITY UINT32_C(32)

#define OPEN_IPC_OPCODE_OPEN UINT32_C(0x4f500100)
#define OPEN_IPC_OPCODE_RESULT UINT32_C(0x4f500101)
#define OPEN_IPC_OPCODE_CLOSE UINT32_C(0x4f500102)

#define OPEN_IPC_CLOSE_NORMAL UINT32_C(0)
#define OPEN_IPC_CLOSE_PROTOCOL UINT32_C(0x4f500001)
#define OPEN_IPC_CLOSE_INTERNAL UINT32_C(0x4f500002)
#define OPEN_IPC_CLOSE_SHUTDOWN UINT32_C(0x4f500003)

/* 服务端把整串目标交给宿主（安卓侧）后得到的结果，原样回给客户端。
 * 客户端（rootfs 里的 open 程序）据此决定退出码与日志。 */
typedef enum {
    OPEN_IPC_STATUS_OK = 0,
    OPEN_IPC_STATUS_DISABLED,
    OPEN_IPC_STATUS_UNHANDLED,
    OPEN_IPC_STATUS_INVALID,
    OPEN_IPC_STATUS_FAILED
} OpenIPCStatus;

typedef enum {
    OPEN_IPC_OK = 0,
    OPEN_IPC_WOULD_BLOCK,
    OPEN_IPC_BUSY,
    OPEN_IPC_TIMED_OUT,
    OPEN_IPC_DISCONNECTED,
    OPEN_IPC_PROTOCOL_ERROR,
    OPEN_IPC_INVALID_ARGUMENT,
    OPEN_IPC_SYSTEM_ERROR
} OpenIPCResult;

/* OPEN 事件的 payload（共 224 字节）：
 *   0  uint32 magic
 *   4  uint16 versionMajor
 *   6  uint16 versionMinor
 *   8  uint32 totalLength     整串长度，每片都带
 *   12 uint32 offset          本片在整串里的偏移
 *   16 uint16 chunkLength     本片字节数
 *   18 uint16 reserved        必须为 0
 *   20 uint8  chunk[204]
 */
#define OPEN_IPC_OPEN_HEADER_SIZE UINT32_C(20)
#define OPEN_IPC_OPEN_PAYLOAD_SIZE SHM_RING_EVENT_PAYLOAD_SIZE

/* RESULT 事件的 payload（16 字节）：
 *   0  uint32 magic
 *   4  uint16 versionMajor
 *   6  uint16 versionMinor
 *   8  uint32 status           OpenIPCStatus
 *   12 uint32 totalLength      与请求一致，供客户端自检
 */
#define OPEN_IPC_RESULT_PAYLOAD_SIZE UINT32_C(16)

typedef struct OpenIPCClient OpenIPCClient;
typedef struct OpenIPCServer OpenIPCServer;

typedef struct {
    /* 整串目标已收齐。返回 OpenIPCStatus，原样回给客户端。 */
    uint32_t (*target)(void *userData, const char *target, uint32_t length);
    void (*closed)(void *userData);
} OpenIPCSink;

bool open_ipc_region_size(size_t *sizeOut);

OpenIPCResult open_ipc_client_attach(
        OpenIPCClient **clientOut, SHMRingRegion *ring, uint64_t timeoutNs);
OpenIPCResult open_ipc_client_connect_from_env(
        OpenIPCClient **clientOut, uint64_t timeoutNs);
/* 发送整串目标并等宿主回 RESULT。statusOut 非空时带回宿主给的状态。 */
OpenIPCResult open_ipc_client_open(
        OpenIPCClient *client, const char *target, uint32_t *statusOut,
        uint64_t timeoutNs);
OpenIPCResult open_ipc_client_close(
        OpenIPCClient *client, uint64_t timeoutNs);
void open_ipc_client_destroy(OpenIPCClient *client);

OpenIPCResult open_ipc_server_create(
        OpenIPCServer **serverOut, SHMRingRegion *ring,
        const OpenIPCSink *sink, void *userData);
OpenIPCResult open_ipc_server_run(OpenIPCServer *server);
void open_ipc_server_stop(OpenIPCServer *server);
void open_ipc_server_destroy(OpenIPCServer *server);

#ifdef __cplusplus
}
#endif

#endif
