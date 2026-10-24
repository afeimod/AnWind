#include <shm/drivers/open_driver.h>

#include <stdlib.h>
#include <string.h>

typedef char OpenIPCOpenChunkSize[
        OPEN_IPC_OPEN_HEADER_SIZE + OPEN_IPC_CHUNK_SIZE ==
        OPEN_IPC_OPEN_PAYLOAD_SIZE ? 1 : -1];

/* 客户端只被 rootfs 里的 open 程序用：一条连接发一次就退，单线程，故不加锁。
 * 服务端只被安卓侧的那个驱动用：一个驱动实例对应一条连接。 */
struct OpenIPCClient {
    SHMRingRegion *ring;
    SHMClient *transport;
    uint64_t nextSerial;
    uint32_t closed;
    bool ownsRing;
};

struct OpenIPCServer {
    SHMRingRegion *ring;
    OpenIPCSink sink;
    void *userData;
    char target[OPEN_IPC_TARGET_MAX + 1];
    uint32_t targetLength;
    uint32_t totalLength;
    uint64_t expectedSerial;
    uint64_t lastSerial;
    uint32_t stopping;
    uint32_t closed;
    bool sinkClosed;
};

static void put_u16(uint8_t *target, uint16_t value) {
    target[0] = (uint8_t) value;
    target[1] = (uint8_t) (value >> 8);
}

static uint16_t get_u16(const uint8_t *source) {
    return (uint16_t) source[0] | ((uint16_t) source[1] << 8);
}

static void put_u32(uint8_t *target, uint32_t value) {
    target[0] = (uint8_t) value;
    target[1] = (uint8_t) (value >> 8);
    target[2] = (uint8_t) (value >> 16);
    target[3] = (uint8_t) (value >> 24);
}

static uint32_t get_u32(const uint8_t *source) {
    return (uint32_t) source[0] |
           ((uint32_t) source[1] << 8) |
           ((uint32_t) source[2] << 16) |
           ((uint32_t) source[3] << 24);
}

static OpenIPCResult map_ring_result(SHMRingResult result) {
    switch (result) {
        case SHM_RING_OK:
            return OPEN_IPC_OK;
        case SHM_RING_WOULD_BLOCK:
            return OPEN_IPC_WOULD_BLOCK;
        case SHM_RING_BUSY:
            return OPEN_IPC_BUSY;
        case SHM_RING_TIMED_OUT:
            return OPEN_IPC_TIMED_OUT;
        case SHM_RING_DISCONNECTED:
            return OPEN_IPC_DISCONNECTED;
        case SHM_RING_PROTOCOL_ERROR:
            return OPEN_IPC_PROTOCOL_ERROR;
        case SHM_RING_INVALID_ARGUMENT:
            return OPEN_IPC_INVALID_ARGUMENT;
        case SHM_RING_SYSTEM_ERROR:
            return OPEN_IPC_SYSTEM_ERROR;
    }
    return OPEN_IPC_SYSTEM_ERROR;
}

bool open_ipc_region_size(size_t *size_out) {
    return shm_ring_region_size(OPEN_IPC_RING_CAPACITY, size_out);
}

static void put_magic(uint8_t *payload) {
    put_u32(payload, OPEN_IPC_MAGIC);
    put_u16(payload + 4, OPEN_IPC_VERSION_MAJOR);
    put_u16(payload + 6, OPEN_IPC_VERSION_MINOR);
}

static bool header_matches(const uint8_t *payload) {
    return get_u32(payload) == OPEN_IPC_MAGIC &&
           get_u16(payload + 4) == OPEN_IPC_VERSION_MAJOR &&
           get_u16(payload + 6) <= OPEN_IPC_VERSION_MINOR;
}

static OpenIPCResult client_terminal_result(
        OpenIPCClient *client, OpenIPCResult result) {
    if (result >= OPEN_IPC_DISCONNECTED) {
        __atomic_store_n(&client->closed, 1, __ATOMIC_RELEASE);
        shm_ring_close(client->ring,
                       result == OPEN_IPC_PROTOCOL_ERROR ?
                       OPEN_IPC_CLOSE_PROTOCOL : OPEN_IPC_CLOSE_INTERNAL);
    }
    return result;
}

OpenIPCResult open_ipc_client_attach(
        OpenIPCClient **client_out, SHMRingRegion *ring,
        uint64_t timeout_ns) {
    OpenIPCClient *client;
    OpenIPCResult result;

    if (!client_out || !ring)
        return OPEN_IPC_INVALID_ARGUMENT;
    *client_out = NULL;
    result = map_ring_result(shm_ring_wait_peer_ready(ring, timeout_ns));
    if (result != OPEN_IPC_OK)
        return result;
    client = calloc(1, sizeof(*client));
    if (!client)
        return OPEN_IPC_SYSTEM_ERROR;
    client->ring = ring;
    client->nextSerial = 1;
    *client_out = client;
    return OPEN_IPC_OK;
}

OpenIPCResult open_ipc_client_connect_from_env(
        OpenIPCClient **client_out, uint64_t timeout_ns) {
    OpenIPCClient *client = NULL;
    SHMClient *transport = NULL;
    SHMRingRegion *ring = NULL;
    OpenIPCResult result = OPEN_IPC_SYSTEM_ERROR;
    size_t region_size;

    if (!client_out || !open_ipc_region_size(&region_size))
        return OPEN_IPC_INVALID_ARGUMENT;
    *client_out = NULL;
    transport = shm_client_create();
    if (!transport)
        return OPEN_IPC_SYSTEM_ERROR;
    if (!shm_client_connect_from_env(transport, OPEN_IPC_CLIENT_TYPE,
                                     region_size))
        goto error;
    result = map_ring_result(
            shm_ring_client_attach_from_shm_client(&ring, transport));
    if (result != OPEN_IPC_OK)
        goto error;
    result = open_ipc_client_attach(&client, ring, timeout_ns);
    if (result != OPEN_IPC_OK)
        goto error;
    client->transport = transport;
    client->ownsRing = true;
    *client_out = client;
    return OPEN_IPC_OK;

error:
    if (ring) {
        shm_ring_close(ring, OPEN_IPC_CLOSE_PROTOCOL);
        shm_ring_detach(ring);
    }
    shm_client_try_disconnect(transport);
    shm_client_try_destroy(transport);
    return result;
}

OpenIPCResult open_ipc_client_open(
        OpenIPCClient *client, const char *target, uint32_t *status_out,
        uint64_t timeout_ns) {
    SHMRingEvent event;
    SHMRingEvent reply;
    OpenIPCResult result;
    uint32_t length;
    uint32_t offset;
    uint32_t chunk;
    uint64_t last_serial;

    if (!client || !target)
        return OPEN_IPC_INVALID_ARGUMENT;
    length = (uint32_t) strlen(target);
    if (!length || length > OPEN_IPC_TARGET_MAX)
        return OPEN_IPC_INVALID_ARGUMENT;
    if (__atomic_load_n(&client->closed, __ATOMIC_ACQUIRE))
        return OPEN_IPC_DISCONNECTED;

    for (offset = 0; offset < length; offset += chunk) {
        chunk = length - offset;
        if (chunk > OPEN_IPC_CHUNK_SIZE)
            chunk = OPEN_IPC_CHUNK_SIZE;
        /* 请求头 20 字节 + 分片，尾部补零——事件 payload 固定 224 字节，
         * 补齐能让服务端的 size 校验只看 payloadSize 就够。 */
        memset(&event, 0, sizeof(event));
        event.opcode = OPEN_IPC_OPCODE_OPEN;
        event.flags = SHM_RING_EVENT_ONE_WAY;
        event.payloadSize = OPEN_IPC_OPEN_PAYLOAD_SIZE;
        event.serial = client->nextSerial++;
        event.objectId = 0;
        put_magic(event.payload);
        put_u32(event.payload + 8, length);
        put_u32(event.payload + 12, offset);
        put_u16(event.payload + 16, (uint16_t) chunk);
        memcpy(event.payload + OPEN_IPC_OPEN_HEADER_SIZE, target + offset,
               chunk);
        result = map_ring_result(shm_ring_send(client->ring, &event,
                                               timeout_ns));
        if (result != OPEN_IPC_OK)
            return client_terminal_result(client, result);
    }
    last_serial = client->nextSerial - 1;

    /* 等宿主确认：客户端要在收到 RESULT 之后才断开，否则 Java 侧那条
     * 「socket 一关就 detach 驱动」的路径会把还没读走的请求一起丢掉。 */
    result = map_ring_result(shm_ring_receive(client->ring, &reply,
                                              timeout_ns));
    if (result != OPEN_IPC_OK)
        return client_terminal_result(client, result);
    if (reply.opcode != OPEN_IPC_OPCODE_RESULT ||
        reply.flags != SHM_RING_EVENT_REPLY ||
        reply.payloadSize != OPEN_IPC_RESULT_PAYLOAD_SIZE ||
        reply.serial != last_serial || reply.objectId ||
        !header_matches(reply.payload) ||
        get_u32(reply.payload + 12) != length ||
        get_u32(reply.payload + 8) > OPEN_IPC_STATUS_FAILED)
        return client_terminal_result(client, OPEN_IPC_PROTOCOL_ERROR);

    if (status_out)
        *status_out = get_u32(reply.payload + 8);
    return OPEN_IPC_OK;
}

OpenIPCResult open_ipc_client_close(
        OpenIPCClient *client, uint64_t timeout_ns) {
    SHMRingEvent event;
    OpenIPCResult result = OPEN_IPC_OK;

    if (!client)
        return OPEN_IPC_INVALID_ARGUMENT;
    if (!__atomic_load_n(&client->closed, __ATOMIC_ACQUIRE)) {
        memset(&event, 0, sizeof(event));
        event.opcode = OPEN_IPC_OPCODE_CLOSE;
        event.flags = SHM_RING_EVENT_ONE_WAY;
        event.serial = client->nextSerial++;
        event.objectId = 0;
        result = map_ring_result(shm_ring_send(client->ring, &event,
                                               timeout_ns));
        __atomic_store_n(&client->closed, 1, __ATOMIC_RELEASE);
        shm_ring_close(client->ring, OPEN_IPC_CLOSE_NORMAL);
    }
    return result;
}

void open_ipc_client_destroy(OpenIPCClient *client) {
    if (!client)
        return;
    if (client->ownsRing) {
        if (!__atomic_load_n(&client->closed, __ATOMIC_ACQUIRE))
            shm_ring_close(client->ring, OPEN_IPC_CLOSE_SHUTDOWN);
        shm_ring_detach(client->ring);
        shm_client_try_disconnect(client->transport);
        shm_client_try_destroy(client->transport);
    }
    free(client);
}

static void server_close_sink(OpenIPCServer *server) {
    if (!server->sinkClosed) {
        server->sinkClosed = true;
        if (server->sink.closed)
            server->sink.closed(server->userData);
    }
}

static OpenIPCResult server_protocol_error(OpenIPCServer *server) {
    shm_ring_close(server->ring, OPEN_IPC_CLOSE_PROTOCOL);
    __atomic_store_n(&server->closed, 1, __ATOMIC_RELEASE);
    return OPEN_IPC_PROTOCOL_ERROR;
}

static OpenIPCResult server_send_result(
        OpenIPCServer *server, uint64_t serial, uint32_t status,
        uint32_t total) {
    SHMRingEvent reply;

    memset(&reply, 0, sizeof(reply));
    reply.opcode = OPEN_IPC_OPCODE_RESULT;
    reply.flags = SHM_RING_EVENT_REPLY;
    reply.payloadSize = OPEN_IPC_RESULT_PAYLOAD_SIZE;
    reply.serial = serial;
    reply.objectId = 0;
    put_magic(reply.payload);
    put_u32(reply.payload + 8, status);
    put_u32(reply.payload + 12, total);
    return map_ring_result(shm_ring_send(server->ring, &reply,
                                         OPEN_IPC_TIMEOUT_INFINITE));
}

static OpenIPCResult server_handle_open(
        OpenIPCServer *server, const SHMRingEvent *request) {
    uint32_t total;
    uint32_t offset;
    uint32_t chunk;
    uint32_t status;
    OpenIPCResult result;

    if (!header_matches(request->payload) ||
        request->payloadSize != OPEN_IPC_OPEN_PAYLOAD_SIZE ||
        get_u16(request->payload + 18))
        return server_protocol_error(server);

    total = get_u32(request->payload + 8);
    offset = get_u32(request->payload + 12);
    chunk = get_u16(request->payload + 16);
    if (!total || total > OPEN_IPC_TARGET_MAX ||
        chunk > OPEN_IPC_CHUNK_SIZE ||
        offset != server->targetLength || offset + chunk > total ||
        (server->targetLength && total != server->totalLength))
        return server_protocol_error(server);

    server->totalLength = total;
    memcpy(server->target + offset,
           request->payload + OPEN_IPC_OPEN_HEADER_SIZE, chunk);
    server->targetLength = offset + chunk;
    server->lastSerial = request->serial;
    if (server->targetLength < total)
        return OPEN_IPC_OK;

    /* 收齐了：交给宿主。target 多留了一字节做 NUL，宿主可以直接当 C 串用。 */
    server->target[total] = 0;
    status = server->sink.target(server->userData, server->target, total);
    if (status > OPEN_IPC_STATUS_FAILED)
        status = OPEN_IPC_STATUS_FAILED;
    server->targetLength = 0;
    server->totalLength = 0;
    result = server_send_result(server, server->lastSerial, status, total);
    if (result == OPEN_IPC_DISCONNECTED)
        return OPEN_IPC_OK;
    return result;
}

static OpenIPCResult server_validate_request(
        OpenIPCServer *server, const SHMRingEvent *request) {
    if (server->closed || request->objectId ||
        request->flags != SHM_RING_EVENT_ONE_WAY ||
        request->serial != server->expectedSerial)
        return server_protocol_error(server);
    server->expectedSerial++;
    return OPEN_IPC_OK;
}

static OpenIPCResult server_handle_request(
        OpenIPCServer *server, const SHMRingEvent *request) {
    OpenIPCResult result;

    if (request->opcode == OPEN_IPC_OPCODE_CLOSE) {
        if (request->payloadSize || request->objectId)
            return server_protocol_error(server);
        server_close_sink(server);
        __atomic_store_n(&server->closed, 1, __ATOMIC_RELEASE);
        shm_ring_close(server->ring, OPEN_IPC_CLOSE_NORMAL);
        return OPEN_IPC_OK;
    }
    result = server_validate_request(server, request);
    if (result != OPEN_IPC_OK)
        return result;
    if (request->opcode != OPEN_IPC_OPCODE_OPEN)
        return server_protocol_error(server);
    return server_handle_open(server, request);
}

OpenIPCResult open_ipc_server_create(
        OpenIPCServer **server_out, SHMRingRegion *ring,
        const OpenIPCSink *sink, void *user_data) {
    OpenIPCServer *server;

    if (!server_out || !ring || !sink || !sink->target)
        return OPEN_IPC_INVALID_ARGUMENT;
    *server_out = NULL;
    server = calloc(1, sizeof(*server));
    if (!server)
        return OPEN_IPC_SYSTEM_ERROR;
    server->ring = ring;
    server->sink = *sink;
    server->userData = user_data;
    server->expectedSerial = 1;
    *server_out = server;
    return OPEN_IPC_OK;
}

static OpenIPCResult server_terminal_result(
        OpenIPCServer *server, OpenIPCResult result) {
    shm_ring_close(server->ring,
                   result == OPEN_IPC_PROTOCOL_ERROR ?
                   OPEN_IPC_CLOSE_PROTOCOL : OPEN_IPC_CLOSE_INTERNAL);
    __atomic_store_n(&server->closed, 1, __ATOMIC_RELEASE);
    server_close_sink(server);
    return result;
}

OpenIPCResult open_ipc_server_run(OpenIPCServer *server) {
    SHMRingEvent request;
    OpenIPCResult result;

    if (!server)
        return OPEN_IPC_INVALID_ARGUMENT;
    while (!__atomic_load_n(&server->stopping, __ATOMIC_ACQUIRE) &&
           !__atomic_load_n(&server->closed, __ATOMIC_ACQUIRE)) {
        result = map_ring_result(shm_ring_receive(server->ring, &request,
                                                  UINT64_C(100000000)));
        if (result == OPEN_IPC_TIMED_OUT)
            continue;
        if (result == OPEN_IPC_DISCONNECTED)
            break;
        if (result != OPEN_IPC_OK)
            return server_terminal_result(server, result);
        result = server_handle_request(server, &request);
        if (result != OPEN_IPC_OK)
            return server_terminal_result(server, result);
    }
    server_close_sink(server);
    return OPEN_IPC_OK;
}

void open_ipc_server_stop(OpenIPCServer *server) {
    if (!server)
        return;
    __atomic_store_n(&server->stopping, 1, __ATOMIC_RELEASE);
    shm_ring_close(server->ring, OPEN_IPC_CLOSE_SHUTDOWN);
}

void open_ipc_server_destroy(OpenIPCServer *server) {
    if (!server)
        return;
    server_close_sink(server);
    free(server);
}
