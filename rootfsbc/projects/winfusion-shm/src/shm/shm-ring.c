#define _GNU_SOURCE

#include <shm/shm-ring.h>

#include <errno.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <linux/futex.h>
#include <poll.h>
#include <sched.h>
#include <sys/socket.h>
#include <sys/syscall.h>

#define HEADER_MAGIC_OFFSET 0
#define HEADER_MAJOR_OFFSET 4
#define HEADER_MINOR_OFFSET 6
#define HEADER_ENDIAN_OFFSET 8
#define HEADER_METADATA_SIZE_OFFSET 12
#define HEADER_REGION_SIZE_OFFSET 16
#define HEADER_AVAILABLE_FEATURES_OFFSET 24
#define HEADER_REQUIRED_FEATURES_OFFSET 32
#define HEADER_SLOT_SIZE_OFFSET 40
#define HEADER_CAPACITY_OFFSET 44
#define HEADER_EVENT_HEADER_SIZE_OFFSET 48
#define HEADER_QUEUE_CONTROL_SIZE_OFFSET 52
#define HEADER_C2S_CONTROL_OFFSET 56
#define HEADER_C2S_SLOTS_OFFSET 64
#define HEADER_S2C_CONTROL_OFFSET 72
#define HEADER_S2C_SLOTS_OFFSET 80
#define HEADER_GENERATION_OFFSET 88
#define HEADER_RESERVED_OFFSET 96
#define HEADER_RESERVED_SIZE 32

#define SERVER_STATE_OFFSET 128
#define CLIENT_STATE_OFFSET 192
#define ENDPOINT_STATE_OFFSET 0
#define ENDPOINT_EVENT_OFFSET 4
#define ENDPOINT_REASON_OFFSET 8
#define ENDPOINT_RESERVED_OFFSET 12
#define ENDPOINT_RESERVED_SIZE 52

#define C2S_CONTROL_OFFSET 256
#define S2C_CONTROL_OFFSET 384
#define QUEUE_WRITE_OFFSET 0
#define QUEUE_DATA_EVENT_OFFSET 4
#define QUEUE_READ_OFFSET 64
#define QUEUE_SPACE_EVENT_OFFSET 68
#define QUEUE_WRITER_RESERVED_OFFSET 8
#define QUEUE_WRITER_RESERVED_SIZE 56
#define QUEUE_READER_RESERVED_OFFSET 72
#define QUEUE_READER_RESERVED_SIZE 56

#define SLOT_OPCODE_OFFSET 0
#define SLOT_FLAGS_OFFSET 4
#define SLOT_HEADER_SIZE_OFFSET 6
#define SLOT_PAYLOAD_SIZE_OFFSET 8
#define SLOT_RESERVED_OFFSET 12
#define SLOT_SERIAL_OFFSET 16
#define SLOT_OBJECT_ID_OFFSET 24
#define SLOT_PAYLOAD_OFFSET 32
#define REGION_DETACHING UINT32_C(0x80000000)
#define REGION_REFERENCE_MASK UINT32_C(0x7fffffff)

struct SHMRingRegion {
    uint8_t *mapping;
    size_t mappingSize;
    uint32_t capacity;
    uint64_t generation;
    uint8_t *localState;
    uint8_t *peerState;
    uint8_t *txControl;
    uint8_t *rxControl;
    uint8_t *txSlots;
    uint8_t *rxSlots;
    SHMClient *clientOwner;
    int peerSocketFd;
    uint32_t peerLost;
    uint32_t stateLock;
    uint32_t sendActive;
    uint32_t receiveActive;
    uint32_t lifecycleState;
};

typedef struct {
    bool infinite;
    struct timespec end;
} SHMRingDeadline;

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

static void put_u64(uint8_t *target, uint64_t value) {
    for (int i = 0; i < 8; i++)
        target[i] = (uint8_t) (value >> (i * 8));
}

static uint64_t get_u64(const uint8_t *source) {
    uint64_t value = 0;

    for (int i = 0; i < 8; i++)
        value |= (uint64_t) source[i] << (i * 8);
    return value;
}

static uint32_t *atomic_word(uint8_t *base, size_t offset) {
    return (uint32_t *) (void *) (base + offset);
}

static const uint32_t *atomic_const_word(const uint8_t *base, size_t offset) {
    return (const uint32_t *) (const void *) (base + offset);
}

static uint32_t atomic_load_word(const uint32_t *word, int order) {
    uint32_t value;

    (void) order;
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
    value = __atomic_load_n(word, __ATOMIC_RELAXED);
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
    return value;
}

static void atomic_store_word(uint32_t *word, uint32_t value, int order) {
    (void) order;
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
    __atomic_store_n(word, value, __ATOMIC_RELAXED);
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
}

static void wake_word(uint32_t *word, int count) {
    syscall(SYS_futex, word, FUTEX_WAKE, count, NULL, NULL, 0);
}

static void signal_word(uint32_t *word, int count) {
    __atomic_fetch_add(word, 1, __ATOMIC_SEQ_CST);
    wake_word(word, count);
}

static bool timespec_add_ns(struct timespec *time, uint64_t nanoseconds) {
    uint64_t seconds = nanoseconds / UINT64_C(1000000000);
    uint64_t remainder = nanoseconds % UINT64_C(1000000000);

    if (seconds > (uint64_t) (INT64_MAX - time->tv_sec))
        return false;
    time->tv_sec += (time_t) seconds;
    time->tv_nsec += (long) remainder;
    if (time->tv_nsec >= 1000000000L) {
        if (time->tv_sec == INT64_MAX)
            return false;
        time->tv_sec++;
        time->tv_nsec -= 1000000000L;
    }
    return true;
}

static SHMRingResult deadline_init(SHMRingDeadline *deadline,
                                   uint64_t timeout_ns) {
    deadline->infinite = timeout_ns == SHM_RING_TIMEOUT_INFINITE;
    if (deadline->infinite)
        return SHM_RING_OK;
    if (clock_gettime(CLOCK_MONOTONIC, &deadline->end) == -1)
        return SHM_RING_SYSTEM_ERROR;
    if (!timespec_add_ns(&deadline->end, timeout_ns)) {
        errno = EOVERFLOW;
        return SHM_RING_SYSTEM_ERROR;
    }
    return SHM_RING_OK;
}

static SHMRingResult deadline_remaining(const SHMRingDeadline *deadline,
                                        struct timespec *remaining) {
    struct timespec now;

    if (deadline->infinite)
        return SHM_RING_OK;
    if (clock_gettime(CLOCK_MONOTONIC, &now) == -1)
        return SHM_RING_SYSTEM_ERROR;
    remaining->tv_sec = deadline->end.tv_sec - now.tv_sec;
    remaining->tv_nsec = deadline->end.tv_nsec - now.tv_nsec;
    if (remaining->tv_nsec < 0) {
        remaining->tv_sec--;
        remaining->tv_nsec += 1000000000L;
    }
    if (remaining->tv_sec < 0 ||
        (!remaining->tv_sec && !remaining->tv_nsec))
        return SHM_RING_TIMED_OUT;
    return SHM_RING_OK;
}

static SHMRingResult wait_word(uint32_t *word, uint32_t expected,
                               const SHMRingDeadline *deadline) {
    struct timespec remaining;
    struct timespec *timeout;
    SHMRingResult result;
    long wait_result;
    bool sliced;

    while (true) {
        sliced = false;
        if (deadline->infinite) {
            remaining.tv_sec = 1;
            remaining.tv_nsec = 0;
            timeout = &remaining;
        } else {
            result = deadline_remaining(deadline, &remaining);
            if (result != SHM_RING_OK)
                return result;
            if (remaining.tv_sec > 1 ||
                (remaining.tv_sec == 1 && remaining.tv_nsec)) {
                remaining.tv_sec = 1;
                remaining.tv_nsec = 0;
                sliced = true;
            }
            timeout = &remaining;
        }

        wait_result = syscall(SYS_futex, word, FUTEX_WAIT, expected,
                              timeout, NULL, 0);
        if (!wait_result || errno == EAGAIN)
            return SHM_RING_OK;
        if (errno == EINTR)
            continue;
        if (errno == ETIMEDOUT)
            return deadline->infinite || sliced ?
                   SHM_RING_OK : SHM_RING_TIMED_OUT;
        return SHM_RING_SYSTEM_ERROR;
    }
}

static bool is_little_endian(void) {
    const uint16_t value = 1;
    return *(const uint8_t *) &value == 1;
}

static bool bytes_are_zero(const uint8_t *bytes, size_t size) {
    for (size_t i = 0; i < size; i++) {
        if (bytes[i])
            return false;
    }
    return true;
}

bool shm_ring_region_size(uint32_t capacity, size_t *size_out) {
    size_t slots_size;

    if (!size_out || capacity < SHM_RING_CAPACITY_MIN ||
        capacity > SHM_RING_CAPACITY_MAX ||
        (capacity & (capacity - 1)))
        return false;
    slots_size = (size_t) capacity * SHM_RING_SLOT_SIZE;
    *size_out = SHM_RING_METADATA_SIZE + 2 * slots_size;
    return true;
}

static SHMRingResult configure_region(SHMRingRegion **region_out,
                                      void *mapping, size_t mapping_size,
                                      uint32_t capacity, uint64_t generation,
                                      SHMRingRole role) {
    SHMRingRegion *region;
    size_t slots_size;

    if (!region_out || !mapping || ((uintptr_t) mapping & 3) ||
        !__atomic_always_lock_free(sizeof(uint32_t), NULL))
        return SHM_RING_INVALID_ARGUMENT;

    region = calloc(1, sizeof(*region));
    if (!region)
        return SHM_RING_SYSTEM_ERROR;

    slots_size = (size_t) capacity * SHM_RING_SLOT_SIZE;
    region->mapping = mapping;
    region->mappingSize = mapping_size;
    region->capacity = capacity;
    region->generation = generation;
    region->peerSocketFd = -1;
    if (role == SHM_RING_ROLE_SERVER) {
        region->localState = region->mapping + SERVER_STATE_OFFSET;
        region->peerState = region->mapping + CLIENT_STATE_OFFSET;
        region->txControl = region->mapping + S2C_CONTROL_OFFSET;
        region->rxControl = region->mapping + C2S_CONTROL_OFFSET;
        region->txSlots = region->mapping + SHM_RING_METADATA_SIZE + slots_size;
        region->rxSlots = region->mapping + SHM_RING_METADATA_SIZE;
    } else if (role == SHM_RING_ROLE_CLIENT) {
        region->localState = region->mapping + CLIENT_STATE_OFFSET;
        region->peerState = region->mapping + SERVER_STATE_OFFSET;
        region->txControl = region->mapping + C2S_CONTROL_OFFSET;
        region->rxControl = region->mapping + S2C_CONTROL_OFFSET;
        region->txSlots = region->mapping + SHM_RING_METADATA_SIZE;
        region->rxSlots = region->mapping + SHM_RING_METADATA_SIZE + slots_size;
    } else {
        free(region);
        return SHM_RING_INVALID_ARGUMENT;
    }

    *region_out = region;
    return SHM_RING_OK;
}

static void publish_state(uint8_t *state, SHMRingState value,
                          uint32_t reason) {
    put_u32(state + ENDPOINT_REASON_OFFSET, reason);
    atomic_store_word(atomic_word(state, ENDPOINT_STATE_OFFSET),
                      value, __ATOMIC_RELEASE);
    signal_word(atomic_word(state, ENDPOINT_EVENT_OFFSET), INT_MAX);
}

static void transition_state(SHMRingRegion *region, SHMRingState value,
                             uint32_t reason) {
    uint32_t state;

    while (__atomic_exchange_n(&region->stateLock, 1, __ATOMIC_ACQUIRE))
        sched_yield();
    state = atomic_load_word(
            atomic_word(region->localState, ENDPOINT_STATE_OFFSET),
            __ATOMIC_ACQUIRE);
    if (state == SHM_RING_STATE_READY)
        publish_state(region->localState, value, reason);
    __atomic_store_n(&region->stateLock, 0, __ATOMIC_RELEASE);
}

static void wake_all_queues(SHMRingRegion *region) {
    signal_word(atomic_word(region->txControl, QUEUE_DATA_EVENT_OFFSET), INT_MAX);
    signal_word(atomic_word(region->txControl, QUEUE_SPACE_EVENT_OFFSET), INT_MAX);
    signal_word(atomic_word(region->rxControl, QUEUE_DATA_EVENT_OFFSET), INT_MAX);
    signal_word(atomic_word(region->rxControl, QUEUE_SPACE_EVENT_OFFSET), INT_MAX);
}

SHMRingResult shm_ring_server_initialize(SHMRingRegion **region_out,
                                         void *mapping, size_t mapping_size,
                                         uint32_t capacity,
                                         uint64_t generation) {
    uint8_t *bytes = mapping;
    SHMRingRegion *region;
    SHMRingResult result;
    size_t region_size;
    size_t slots_size;

    if (!region_out)
        return SHM_RING_INVALID_ARGUMENT;
    *region_out = NULL;
    if (!mapping || ((uintptr_t) mapping & 3) || !generation ||
        !is_little_endian() ||
        !__atomic_always_lock_free(sizeof(uint32_t), NULL) ||
        !shm_ring_region_size(capacity, &region_size) ||
        mapping_size < region_size)
        return SHM_RING_INVALID_ARGUMENT;

    memset(mapping, 0, region_size);
    slots_size = (size_t) capacity * SHM_RING_SLOT_SIZE;
    put_u32(bytes + HEADER_MAGIC_OFFSET, SHM_RING_MAGIC);
    put_u16(bytes + HEADER_MAJOR_OFFSET, SHM_RING_VERSION_MAJOR);
    put_u16(bytes + HEADER_MINOR_OFFSET, SHM_RING_VERSION_MINOR);
    put_u32(bytes + HEADER_ENDIAN_OFFSET, SHM_RING_ENDIAN_TAG);
    put_u32(bytes + HEADER_METADATA_SIZE_OFFSET, SHM_RING_METADATA_SIZE);
    put_u64(bytes + HEADER_REGION_SIZE_OFFSET, region_size);
    put_u64(bytes + HEADER_AVAILABLE_FEATURES_OFFSET, SHM_RING_FEATURES_V1);
    put_u64(bytes + HEADER_REQUIRED_FEATURES_OFFSET, SHM_RING_FEATURES_V1);
    put_u32(bytes + HEADER_SLOT_SIZE_OFFSET, SHM_RING_SLOT_SIZE);
    put_u32(bytes + HEADER_CAPACITY_OFFSET, capacity);
    put_u32(bytes + HEADER_EVENT_HEADER_SIZE_OFFSET,
            SHM_RING_EVENT_HEADER_SIZE);
    put_u32(bytes + HEADER_QUEUE_CONTROL_SIZE_OFFSET,
            SHM_RING_QUEUE_CONTROL_SIZE);
    put_u64(bytes + HEADER_C2S_CONTROL_OFFSET, C2S_CONTROL_OFFSET);
    put_u64(bytes + HEADER_C2S_SLOTS_OFFSET, SHM_RING_METADATA_SIZE);
    put_u64(bytes + HEADER_S2C_CONTROL_OFFSET, S2C_CONTROL_OFFSET);
    put_u64(bytes + HEADER_S2C_SLOTS_OFFSET,
            SHM_RING_METADATA_SIZE + slots_size);
    put_u64(bytes + HEADER_GENERATION_OFFSET, generation);

    result = configure_region(&region, mapping, mapping_size, capacity,
                              generation, SHM_RING_ROLE_SERVER);
    if (result != SHM_RING_OK)
        return result;
    publish_state(region->localState, SHM_RING_STATE_READY, 0);
    *region_out = region;
    return SHM_RING_OK;
}

static SHMRingResult validate_region(void *mapping, size_t mapping_size,
                                     uint32_t *capacity_out,
                                     uint64_t *generation_out) {
    const uint8_t *bytes = mapping;
    uint64_t available_features;
    uint64_t required_features;
    uint64_t region_size;
    uint64_t s2c_slots;
    size_t expected_size;
    size_t slots_size;
    uint32_t capacity;
    uint32_t write_sequence;
    uint32_t read_sequence;
    uint32_t server_state;
    uint32_t client_state;

    if (!mapping || ((uintptr_t) mapping & 3) ||
        mapping_size < SHM_RING_METADATA_SIZE || !is_little_endian() ||
        !__atomic_always_lock_free(sizeof(uint32_t), NULL))
        return SHM_RING_INVALID_ARGUMENT;
    server_state = atomic_load_word(atomic_word(mapping, SERVER_STATE_OFFSET),
                                    __ATOMIC_ACQUIRE);
    if (server_state == SHM_RING_STATE_INITIALIZING)
        return SHM_RING_WOULD_BLOCK;
    if (server_state == SHM_RING_STATE_CLOSED)
        return SHM_RING_DISCONNECTED;
    if (server_state != SHM_RING_STATE_READY)
        return SHM_RING_PROTOCOL_ERROR;
    client_state = atomic_load_word(atomic_word(mapping, CLIENT_STATE_OFFSET),
                                    __ATOMIC_ACQUIRE);
    if (client_state != SHM_RING_STATE_INITIALIZING)
        return client_state == SHM_RING_STATE_READY ?
               SHM_RING_BUSY : SHM_RING_PROTOCOL_ERROR;

    if (get_u32(bytes + HEADER_MAGIC_OFFSET) != SHM_RING_MAGIC ||
        get_u16(bytes + HEADER_MAJOR_OFFSET) != SHM_RING_VERSION_MAJOR ||
        get_u16(bytes + HEADER_MINOR_OFFSET) > SHM_RING_VERSION_MINOR ||
        get_u32(bytes + HEADER_ENDIAN_OFFSET) != SHM_RING_ENDIAN_TAG ||
        get_u32(bytes + HEADER_METADATA_SIZE_OFFSET) != SHM_RING_METADATA_SIZE ||
        get_u32(bytes + HEADER_SLOT_SIZE_OFFSET) != SHM_RING_SLOT_SIZE ||
        get_u32(bytes + HEADER_EVENT_HEADER_SIZE_OFFSET) !=
                SHM_RING_EVENT_HEADER_SIZE ||
        get_u32(bytes + HEADER_QUEUE_CONTROL_SIZE_OFFSET) !=
                SHM_RING_QUEUE_CONTROL_SIZE ||
        get_u64(bytes + HEADER_C2S_CONTROL_OFFSET) != C2S_CONTROL_OFFSET ||
        get_u64(bytes + HEADER_C2S_SLOTS_OFFSET) != SHM_RING_METADATA_SIZE ||
        get_u64(bytes + HEADER_S2C_CONTROL_OFFSET) != S2C_CONTROL_OFFSET ||
        !bytes_are_zero(bytes + HEADER_RESERVED_OFFSET,
                        HEADER_RESERVED_SIZE) ||
        !bytes_are_zero(bytes + SERVER_STATE_OFFSET + ENDPOINT_RESERVED_OFFSET,
                        ENDPOINT_RESERVED_SIZE) ||
        !bytes_are_zero(bytes + CLIENT_STATE_OFFSET + ENDPOINT_RESERVED_OFFSET,
                        ENDPOINT_RESERVED_SIZE) ||
        !bytes_are_zero(bytes + C2S_CONTROL_OFFSET +
                        QUEUE_WRITER_RESERVED_OFFSET,
                        QUEUE_WRITER_RESERVED_SIZE) ||
        !bytes_are_zero(bytes + C2S_CONTROL_OFFSET +
                        QUEUE_READER_RESERVED_OFFSET,
                        QUEUE_READER_RESERVED_SIZE) ||
        !bytes_are_zero(bytes + S2C_CONTROL_OFFSET +
                        QUEUE_WRITER_RESERVED_OFFSET,
                        QUEUE_WRITER_RESERVED_SIZE) ||
        !bytes_are_zero(bytes + S2C_CONTROL_OFFSET +
                        QUEUE_READER_RESERVED_OFFSET,
                        QUEUE_READER_RESERVED_SIZE))
        return SHM_RING_PROTOCOL_ERROR;

    capacity = get_u32(bytes + HEADER_CAPACITY_OFFSET);
    if (!shm_ring_region_size(capacity, &expected_size))
        return SHM_RING_PROTOCOL_ERROR;
    region_size = get_u64(bytes + HEADER_REGION_SIZE_OFFSET);
    slots_size = (size_t) capacity * SHM_RING_SLOT_SIZE;
    s2c_slots = get_u64(bytes + HEADER_S2C_SLOTS_OFFSET);
    if (region_size != expected_size || region_size > mapping_size ||
        s2c_slots != SHM_RING_METADATA_SIZE + slots_size)
        return SHM_RING_PROTOCOL_ERROR;

    available_features = get_u64(bytes + HEADER_AVAILABLE_FEATURES_OFFSET);
    required_features = get_u64(bytes + HEADER_REQUIRED_FEATURES_OFFSET);
    if ((available_features & SHM_RING_FEATURES_V1) !=
            SHM_RING_FEATURES_V1 ||
        required_features != SHM_RING_FEATURES_V1)
        return SHM_RING_PROTOCOL_ERROR;
    if (!get_u64(bytes + HEADER_GENERATION_OFFSET))
        return SHM_RING_PROTOCOL_ERROR;

    write_sequence = atomic_load_word(
            atomic_const_word(bytes, C2S_CONTROL_OFFSET + QUEUE_WRITE_OFFSET),
            __ATOMIC_ACQUIRE);
    read_sequence = atomic_load_word(
            atomic_const_word(bytes, C2S_CONTROL_OFFSET + QUEUE_READ_OFFSET),
            __ATOMIC_ACQUIRE);
    if ((uint32_t) (write_sequence - read_sequence) > capacity)
        return SHM_RING_PROTOCOL_ERROR;
    write_sequence = atomic_load_word(
            atomic_const_word(bytes, S2C_CONTROL_OFFSET + QUEUE_WRITE_OFFSET),
            __ATOMIC_ACQUIRE);
    read_sequence = atomic_load_word(
            atomic_const_word(bytes, S2C_CONTROL_OFFSET + QUEUE_READ_OFFSET),
            __ATOMIC_ACQUIRE);
    if ((uint32_t) (write_sequence - read_sequence) > capacity)
        return SHM_RING_PROTOCOL_ERROR;

    *capacity_out = capacity;
    *generation_out = get_u64(bytes + HEADER_GENERATION_OFFSET);
    return SHM_RING_OK;
}

static SHMRingResult claim_client_state(SHMRingRegion *region) {
    uint32_t expected = SHM_RING_STATE_INITIALIZING;

    if (!__atomic_compare_exchange_n(
            atomic_word(region->localState, ENDPOINT_STATE_OFFSET),
            &expected, SHM_RING_STATE_READY, false,
            __ATOMIC_RELEASE, __ATOMIC_ACQUIRE))
        return expected == SHM_RING_STATE_READY ?
               SHM_RING_BUSY : SHM_RING_PROTOCOL_ERROR;
    signal_word(atomic_word(region->localState, ENDPOINT_EVENT_OFFSET),
                INT_MAX);
    return SHM_RING_OK;
}

SHMRingResult shm_ring_client_attach(SHMRingRegion **region_out,
                                     void *mapping, size_t mapping_size) {
    SHMRingRegion *region;
    SHMRingResult result;
    uint32_t capacity;
    uint64_t generation;

    if (!region_out)
        return SHM_RING_INVALID_ARGUMENT;
    *region_out = NULL;
    result = validate_region(mapping, mapping_size, &capacity, &generation);
    if (result != SHM_RING_OK)
        return result;
    result = configure_region(&region, mapping, mapping_size, capacity,
                              generation, SHM_RING_ROLE_CLIENT);
    if (result != SHM_RING_OK)
        return result;
    result = claim_client_state(region);
    if (result != SHM_RING_OK) {
        free(region);
        return result;
    }
    *region_out = region;
    return SHM_RING_OK;
}

SHMRingResult shm_ring_client_attach_from_shm_client(
        SHMRingRegion **region_out, SHMClient *client) {
    SHMRingResult result;
    SHMRingRegion *region;
    void *mapping;
    size_t mapping_size;
    int socket_fd;

    if (!region_out)
        return SHM_RING_INVALID_ARGUMENT;
    *region_out = NULL;
    if (!client || !shm_client_acquire_mapping(client, &mapping,
                                                &mapping_size))
        return SHM_RING_INVALID_ARGUMENT;
    socket_fd = shm_client_duplicate_socket_fd(client);
    if (socket_fd < 0) {
        shm_client_release_mapping(client);
        return SHM_RING_INVALID_ARGUMENT;
    }
    result = shm_ring_client_attach(&region, mapping, mapping_size);
    if (result != SHM_RING_OK) {
        close(socket_fd);
        shm_client_release_mapping(client);
        return result;
    }
    region->clientOwner = client;
    region->peerSocketFd = socket_fd;
    *region_out = region;
    return SHM_RING_OK;
}

static bool socket_peer_lost(int socket_fd) {
    struct pollfd descriptor;
    char byte;
    int result;

    if (socket_fd < 0)
        return false;
    memset(&descriptor, 0, sizeof(descriptor));
    descriptor.fd = socket_fd;
    descriptor.events = POLLIN | POLLHUP | POLLERR;
    do {
        result = poll(&descriptor, 1, 0);
    } while (result == -1 && errno == EINTR);
    if (result <= 0)
        return false;
    if (descriptor.revents & (POLLHUP | POLLERR | POLLNVAL))
        return true;
    if (descriptor.revents & POLLIN) {
        do {
            result = (int) recv(socket_fd, &byte, 1,
                                MSG_PEEK | MSG_DONTWAIT);
        } while (result == -1 && errno == EINTR);
        return result == 0;
    }
    return false;
}

static SHMRingResult peer_status(SHMRingRegion *region) {
    uint32_t state;

    if (socket_peer_lost(region->peerSocketFd))
        atomic_store_word(&region->peerLost, 1, __ATOMIC_RELEASE);
    if (atomic_load_word(&region->peerLost, __ATOMIC_ACQUIRE))
        return SHM_RING_DISCONNECTED;
    state = atomic_load_word(
            atomic_word(region->peerState, ENDPOINT_STATE_OFFSET),
            __ATOMIC_ACQUIRE);
    if (state == SHM_RING_STATE_READY)
        return SHM_RING_OK;
    if (state == SHM_RING_STATE_INITIALIZING)
        return SHM_RING_WOULD_BLOCK;
    if (state == SHM_RING_STATE_CLOSED)
        return SHM_RING_DISCONNECTED;
    return SHM_RING_PROTOCOL_ERROR;
}

static bool local_is_ready(SHMRingRegion *region) {
    return atomic_load_word(
            atomic_word(region->localState, ENDPOINT_STATE_OFFSET),
            __ATOMIC_ACQUIRE) == SHM_RING_STATE_READY;
}

static bool acquire_region(SHMRingRegion *region);
static void release_region(SHMRingRegion *region);

static SHMRingResult wait_peer_ready(SHMRingRegion *region,
                                     uint64_t timeout_ns) {
    SHMRingDeadline deadline;
    SHMRingResult result;
    uint32_t event;
    uint32_t *event_word;

    if (!region)
        return SHM_RING_INVALID_ARGUMENT;
    result = deadline_init(&deadline, timeout_ns);
    if (result != SHM_RING_OK)
        return result;
    event_word = atomic_word(region->peerState, ENDPOINT_EVENT_OFFSET);

    while (true) {
        if (!local_is_ready(region))
            return SHM_RING_DISCONNECTED;
        result = peer_status(region);
        if (result != SHM_RING_WOULD_BLOCK)
            return result;
        if (!timeout_ns)
            return SHM_RING_WOULD_BLOCK;
        event = atomic_load_word(event_word, __ATOMIC_RELAXED);
        result = peer_status(region);
        if (result != SHM_RING_WOULD_BLOCK)
            return result;
        result = wait_word(event_word, event, &deadline);
        if (result != SHM_RING_OK)
            return result;
    }
}

SHMRingResult shm_ring_wait_peer_ready(SHMRingRegion *region,
                                       uint64_t timeout_ns) {
    SHMRingResult result;

    if (!region)
        return SHM_RING_INVALID_ARGUMENT;
    if (!acquire_region(region))
        return SHM_RING_BUSY;
    result = wait_peer_ready(region, timeout_ns);
    release_region(region);
    return result;
}

static bool acquire_region(SHMRingRegion *region) {
    uint32_t state = atomic_load_word(&region->lifecycleState,
                                      __ATOMIC_ACQUIRE);

    while (!(state & REGION_DETACHING)) {
        if ((state & REGION_REFERENCE_MASK) == REGION_REFERENCE_MASK)
            return false;
        if (__atomic_compare_exchange_n(&region->lifecycleState,
                                        &state, state + 1, false,
                                        __ATOMIC_ACQ_REL,
                                        __ATOMIC_ACQUIRE))
            return true;
    }
    return false;
}

static void release_region(SHMRingRegion *region) {
    __atomic_fetch_sub(&region->lifecycleState, 1, __ATOMIC_ACQ_REL);
}

static bool begin_operation(SHMRingRegion *region, uint32_t *active) {
    uint32_t expected = 0;

    if (!acquire_region(region))
        return false;
    if (!__atomic_compare_exchange_n(active, &expected, 1, false,
                                     __ATOMIC_ACQ_REL,
                                     __ATOMIC_ACQUIRE)) {
        release_region(region);
        return false;
    }
    return true;
}

static void end_operation(SHMRingRegion *region, uint32_t *active) {
    atomic_store_word(active, 0, __ATOMIC_RELEASE);
    release_region(region);
}

static bool event_is_valid(const SHMRingEvent *event) {
    return event && event->opcode >= SHM_RING_OPCODE_USER_MIN &&
           !(event->flags & ~SHM_RING_EVENT_FLAGS_MASK) &&
           !event->reserved &&
           event->payloadSize <= SHM_RING_EVENT_PAYLOAD_SIZE;
}

static SHMRingResult try_send(SHMRingRegion *region,
                              const SHMRingEvent *event) {
    SHMRingResult status;
    uint32_t write_sequence;
    uint32_t read_sequence;
    uint32_t used;
    uint8_t *slot;

    if (!region || !event_is_valid(event))
        return SHM_RING_INVALID_ARGUMENT;
    if (!local_is_ready(region))
        return SHM_RING_DISCONNECTED;
    status = peer_status(region);
    if (status != SHM_RING_OK)
        return status;

    write_sequence = atomic_load_word(
            atomic_word(region->txControl, QUEUE_WRITE_OFFSET),
            __ATOMIC_RELAXED);
    read_sequence = atomic_load_word(
            atomic_word(region->txControl, QUEUE_READ_OFFSET),
            __ATOMIC_ACQUIRE);
    used = (uint32_t) (write_sequence - read_sequence);
    if (used > region->capacity) {
        transition_state(region, SHM_RING_STATE_ERROR, EPROTO);
        wake_all_queues(region);
        return SHM_RING_PROTOCOL_ERROR;
    }
    if (used == region->capacity)
        return SHM_RING_WOULD_BLOCK;

    slot = region->txSlots +
           (size_t) (write_sequence & (region->capacity - 1)) *
           SHM_RING_SLOT_SIZE;
    memset(slot, 0, SHM_RING_SLOT_SIZE);
    put_u32(slot + SLOT_OPCODE_OFFSET, event->opcode);
    put_u16(slot + SLOT_FLAGS_OFFSET, event->flags);
    put_u16(slot + SLOT_HEADER_SIZE_OFFSET, SHM_RING_EVENT_HEADER_SIZE);
    put_u32(slot + SLOT_PAYLOAD_SIZE_OFFSET, event->payloadSize);
    put_u64(slot + SLOT_SERIAL_OFFSET, event->serial);
    put_u64(slot + SLOT_OBJECT_ID_OFFSET, event->objectId);
    if (event->payloadSize)
        memcpy(slot + SLOT_PAYLOAD_OFFSET, event->payload,
               event->payloadSize);

    atomic_store_word(atomic_word(region->txControl, QUEUE_WRITE_OFFSET),
                      write_sequence + 1, __ATOMIC_RELEASE);
    signal_word(atomic_word(region->txControl, QUEUE_DATA_EVENT_OFFSET), 1);
    return SHM_RING_OK;
}

SHMRingResult shm_ring_try_send(SHMRingRegion *region,
                                const SHMRingEvent *event) {
    SHMRingResult result;

    if (!region || !event_is_valid(event))
        return SHM_RING_INVALID_ARGUMENT;
    if (!begin_operation(region, &region->sendActive))
        return SHM_RING_BUSY;
    result = try_send(region, event);
    end_operation(region, &region->sendActive);
    return result;
}

static SHMRingResult send_blocking(SHMRingRegion *region,
                                   const SHMRingEvent *event,
                                   uint64_t timeout_ns) {
    SHMRingDeadline deadline;
    SHMRingResult result;
    uint32_t event_value;
    uint32_t *event_word;
    uint32_t peer_event;
    uint32_t *peer_event_word;

    if (!region || !event_is_valid(event))
        return SHM_RING_INVALID_ARGUMENT;
    result = deadline_init(&deadline, timeout_ns);
    if (result != SHM_RING_OK)
        return result;
    event_word = atomic_word(region->txControl, QUEUE_SPACE_EVENT_OFFSET);
    peer_event_word = atomic_word(region->peerState, ENDPOINT_EVENT_OFFSET);

    while (true) {
        result = try_send(region, event);
        if (result != SHM_RING_WOULD_BLOCK)
            return result;
        if (!timeout_ns)
            return SHM_RING_WOULD_BLOCK;

        if (peer_status(region) == SHM_RING_WOULD_BLOCK) {
            peer_event = atomic_load_word(peer_event_word, __ATOMIC_RELAXED);
            if (peer_status(region) != SHM_RING_WOULD_BLOCK)
                continue;
            result = wait_word(peer_event_word, peer_event, &deadline);
        } else {
            event_value = atomic_load_word(event_word, __ATOMIC_RELAXED);
            result = try_send(region, event);
            if (result != SHM_RING_WOULD_BLOCK)
                return result;
            result = wait_word(event_word, event_value, &deadline);
        }
        if (result != SHM_RING_OK)
            return result;
    }
}

SHMRingResult shm_ring_send(SHMRingRegion *region,
                            const SHMRingEvent *event,
                            uint64_t timeout_ns) {
    SHMRingResult result;

    if (!region || !event_is_valid(event))
        return SHM_RING_INVALID_ARGUMENT;
    if (!begin_operation(region, &region->sendActive))
        return SHM_RING_BUSY;
    result = send_blocking(region, event, timeout_ns);
    end_operation(region, &region->sendActive);
    return result;
}

static void release_receive_slot(SHMRingRegion *region,
                                 uint32_t read_sequence) {
    atomic_store_word(atomic_word(region->rxControl, QUEUE_READ_OFFSET),
                      read_sequence + 1, __ATOMIC_RELEASE);
    signal_word(atomic_word(region->rxControl, QUEUE_SPACE_EVENT_OFFSET), 1);
}

static SHMRingResult try_receive(SHMRingRegion *region,
                                 SHMRingEvent *event_out) {
    SHMRingResult status;
    uint32_t write_sequence;
    uint32_t read_sequence;
    uint32_t used;
    uint32_t payload_size;
    uint8_t *slot;

    if (!region || !event_out)
        return SHM_RING_INVALID_ARGUMENT;
    if (!local_is_ready(region))
        return SHM_RING_DISCONNECTED;

    read_sequence = atomic_load_word(
            atomic_word(region->rxControl, QUEUE_READ_OFFSET),
            __ATOMIC_RELAXED);
    write_sequence = atomic_load_word(
            atomic_word(region->rxControl, QUEUE_WRITE_OFFSET),
            __ATOMIC_ACQUIRE);
    used = (uint32_t) (write_sequence - read_sequence);
    if (used > region->capacity) {
        transition_state(region, SHM_RING_STATE_ERROR, EPROTO);
        wake_all_queues(region);
        return SHM_RING_PROTOCOL_ERROR;
    }
    if (!used) {
        status = peer_status(region);
        return status == SHM_RING_OK ? SHM_RING_WOULD_BLOCK : status;
    }

    slot = region->rxSlots +
           (size_t) (read_sequence & (region->capacity - 1)) *
           SHM_RING_SLOT_SIZE;
    payload_size = get_u32(slot + SLOT_PAYLOAD_SIZE_OFFSET);
    if (get_u32(slot + SLOT_OPCODE_OFFSET) < SHM_RING_OPCODE_USER_MIN ||
        get_u16(slot + SLOT_FLAGS_OFFSET) & ~SHM_RING_EVENT_FLAGS_MASK ||
        get_u16(slot + SLOT_HEADER_SIZE_OFFSET) !=
                SHM_RING_EVENT_HEADER_SIZE ||
        payload_size > SHM_RING_EVENT_PAYLOAD_SIZE ||
        get_u32(slot + SLOT_RESERVED_OFFSET)) {
        release_receive_slot(region, read_sequence);
        transition_state(region, SHM_RING_STATE_ERROR, EPROTO);
        wake_all_queues(region);
        return SHM_RING_PROTOCOL_ERROR;
    }

    memset(event_out, 0, sizeof(*event_out));
    event_out->opcode = get_u32(slot + SLOT_OPCODE_OFFSET);
    event_out->flags = get_u16(slot + SLOT_FLAGS_OFFSET);
    event_out->payloadSize = payload_size;
    event_out->serial = get_u64(slot + SLOT_SERIAL_OFFSET);
    event_out->objectId = get_u64(slot + SLOT_OBJECT_ID_OFFSET);
    if (payload_size)
        memcpy(event_out->payload, slot + SLOT_PAYLOAD_OFFSET,
               payload_size);
    release_receive_slot(region, read_sequence);
    return SHM_RING_OK;
}

SHMRingResult shm_ring_try_receive(SHMRingRegion *region,
                                   SHMRingEvent *event_out) {
    SHMRingResult result;

    if (!region || !event_out)
        return SHM_RING_INVALID_ARGUMENT;
    if (!begin_operation(region, &region->receiveActive))
        return SHM_RING_BUSY;
    result = try_receive(region, event_out);
    end_operation(region, &region->receiveActive);
    return result;
}

static SHMRingResult receive_blocking(SHMRingRegion *region,
                                      SHMRingEvent *event_out,
                                      uint64_t timeout_ns) {
    SHMRingDeadline deadline;
    SHMRingResult result;
    uint32_t event_value;
    uint32_t peer_event;
    uint32_t *event_word;
    uint32_t *peer_event_word;

    if (!region || !event_out)
        return SHM_RING_INVALID_ARGUMENT;
    result = deadline_init(&deadline, timeout_ns);
    if (result != SHM_RING_OK)
        return result;
    event_word = atomic_word(region->rxControl, QUEUE_DATA_EVENT_OFFSET);
    peer_event_word = atomic_word(region->peerState, ENDPOINT_EVENT_OFFSET);

    while (true) {
        result = try_receive(region, event_out);
        if (result != SHM_RING_WOULD_BLOCK)
            return result;
        if (!timeout_ns)
            return SHM_RING_WOULD_BLOCK;
        if (peer_status(region) == SHM_RING_WOULD_BLOCK) {
            peer_event = atomic_load_word(peer_event_word, __ATOMIC_RELAXED);
            if (peer_status(region) != SHM_RING_WOULD_BLOCK)
                continue;
            result = wait_word(peer_event_word, peer_event, &deadline);
        } else {
            event_value = atomic_load_word(event_word, __ATOMIC_RELAXED);
            result = try_receive(region, event_out);
            if (result != SHM_RING_WOULD_BLOCK)
                return result;
            result = wait_word(event_word, event_value, &deadline);
        }
        if (result != SHM_RING_OK)
            return result;
    }
}

SHMRingResult shm_ring_receive(SHMRingRegion *region,
                               SHMRingEvent *event_out,
                               uint64_t timeout_ns) {
    SHMRingResult result;

    if (!region || !event_out)
        return SHM_RING_INVALID_ARGUMENT;
    if (!begin_operation(region, &region->receiveActive))
        return SHM_RING_BUSY;
    result = receive_blocking(region, event_out, timeout_ns);
    end_operation(region, &region->receiveActive);
    return result;
}

void shm_ring_close(SHMRingRegion *region, uint32_t reason) {
    if (!region || !acquire_region(region))
        return;
    transition_state(region, SHM_RING_STATE_CLOSED, reason);
    wake_all_queues(region);
    signal_word(atomic_word(region->peerState, ENDPOINT_EVENT_OFFSET), INT_MAX);
    release_region(region);
}

void shm_ring_notify_peer_lost(SHMRingRegion *region) {
    if (!region || !acquire_region(region))
        return;
    atomic_store_word(&region->peerLost, 1, __ATOMIC_RELEASE);
    wake_all_queues(region);
    signal_word(atomic_word(region->peerState, ENDPOINT_EVENT_OFFSET), INT_MAX);
    release_region(region);
}

SHMRingResult shm_ring_detach(SHMRingRegion *region) {
    uint32_t expected = 0;

    if (!region)
        return SHM_RING_OK;
    if (!__atomic_compare_exchange_n(&region->lifecycleState, &expected,
                                     REGION_DETACHING, false,
                                     __ATOMIC_ACQ_REL,
                                     __ATOMIC_ACQUIRE))
        return SHM_RING_BUSY;
    if (region->peerSocketFd >= 0)
        close(region->peerSocketFd);
    if (region->clientOwner)
        shm_client_release_mapping(region->clientOwner);
    free(region);
    return SHM_RING_OK;
}

uint32_t shm_ring_capacity(const SHMRingRegion *region) {
    SHMRingRegion *mutable_region = (SHMRingRegion *) region;
    uint32_t capacity;

    if (!mutable_region || !acquire_region(mutable_region))
        return 0;
    capacity = mutable_region->capacity;
    release_region(mutable_region);
    return capacity;
}

uint64_t shm_ring_generation(const SHMRingRegion *region) {
    SHMRingRegion *mutable_region = (SHMRingRegion *) region;
    uint64_t generation;

    if (!mutable_region || !acquire_region(mutable_region))
        return 0;
    generation = mutable_region->generation;
    release_region(mutable_region);
    return generation;
}
