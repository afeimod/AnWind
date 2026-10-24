#ifndef WINFUSION_SHM_RING_H
#define WINFUSION_SHM_RING_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <shm/shm-client.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SHM_RING_MAGIC UINT32_C(0x52465357)
#define SHM_RING_ENDIAN_TAG UINT32_C(0x01020304)
#define SHM_RING_VERSION_MAJOR UINT16_C(1)
#define SHM_RING_VERSION_MINOR UINT16_C(0)
#define SHM_RING_METADATA_SIZE UINT32_C(512)
#define SHM_RING_QUEUE_CONTROL_SIZE UINT32_C(128)
#define SHM_RING_SLOT_SIZE UINT32_C(256)
#define SHM_RING_EVENT_HEADER_SIZE UINT32_C(32)
#define SHM_RING_EVENT_PAYLOAD_SIZE UINT32_C(224)
#define SHM_RING_CAPACITY_MIN UINT32_C(2)
#define SHM_RING_CAPACITY_MAX UINT32_C(65536)
#define SHM_RING_TIMEOUT_INFINITE UINT64_MAX

#define SHM_RING_FEATURE_FIXED_SLOTS UINT64_C(0x01)
#define SHM_RING_FEATURE_SHARED_FUTEX UINT64_C(0x02)
#define SHM_RING_FEATURE_BIDIRECTIONAL UINT64_C(0x04)
#define SHM_RING_FEATURES_V1 (SHM_RING_FEATURE_FIXED_SLOTS | \
                              SHM_RING_FEATURE_SHARED_FUTEX | \
                              SHM_RING_FEATURE_BIDIRECTIONAL)

#define SHM_RING_EVENT_REPLY UINT16_C(0x0001)
#define SHM_RING_EVENT_ERROR UINT16_C(0x0002)
#define SHM_RING_EVENT_ONE_WAY UINT16_C(0x0004)
#define SHM_RING_EVENT_FLAGS_MASK UINT16_C(0x0007)
#define SHM_RING_OPCODE_USER_MIN UINT32_C(256)

typedef enum {
    SHM_RING_ROLE_SERVER = 1,
    SHM_RING_ROLE_CLIENT = 2
} SHMRingRole;

typedef enum {
    SHM_RING_OK = 0,
    SHM_RING_WOULD_BLOCK,
    SHM_RING_BUSY,
    SHM_RING_TIMED_OUT,
    SHM_RING_DISCONNECTED,
    SHM_RING_PROTOCOL_ERROR,
    SHM_RING_INVALID_ARGUMENT,
    SHM_RING_SYSTEM_ERROR
} SHMRingResult;

typedef enum {
    SHM_RING_STATE_INITIALIZING = 0,
    SHM_RING_STATE_READY = 1,
    SHM_RING_STATE_CLOSED = 2,
    SHM_RING_STATE_ERROR = 3
} SHMRingState;

typedef struct {
    uint32_t opcode;
    uint16_t flags;
    uint16_t reserved;
    uint32_t payloadSize;
    uint64_t serial;
    uint64_t objectId;
    uint8_t payload[SHM_RING_EVENT_PAYLOAD_SIZE];
} SHMRingEvent;

typedef struct SHMRingRegion SHMRingRegion;

bool shm_ring_region_size(uint32_t capacity, size_t *sizeOut);
SHMRingResult shm_ring_server_initialize(SHMRingRegion **regionOut,
                                         void *mapping, size_t mappingSize,
                                         uint32_t capacity,
                                         uint64_t generation);
SHMRingResult shm_ring_client_attach(SHMRingRegion **regionOut,
                                     void *mapping, size_t mappingSize);
SHMRingResult shm_ring_client_attach_from_shm_client(
        SHMRingRegion **regionOut, SHMClient *client);
SHMRingResult shm_ring_wait_peer_ready(SHMRingRegion *region,
                                       uint64_t timeoutNs);
SHMRingResult shm_ring_try_send(SHMRingRegion *region,
                                const SHMRingEvent *event);
SHMRingResult shm_ring_send(SHMRingRegion *region,
                            const SHMRingEvent *event,
                            uint64_t timeoutNs);
SHMRingResult shm_ring_try_receive(SHMRingRegion *region,
                                   SHMRingEvent *eventOut);
SHMRingResult shm_ring_receive(SHMRingRegion *region,
                               SHMRingEvent *eventOut,
                               uint64_t timeoutNs);
void shm_ring_close(SHMRingRegion *region, uint32_t reason);
void shm_ring_notify_peer_lost(SHMRingRegion *region);
SHMRingResult shm_ring_detach(SHMRingRegion *region);
uint32_t shm_ring_capacity(const SHMRingRegion *region);
uint64_t shm_ring_generation(const SHMRingRegion *region);

#ifdef __cplusplus
}
#endif

#endif
