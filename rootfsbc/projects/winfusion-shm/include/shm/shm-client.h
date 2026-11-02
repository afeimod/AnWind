#ifndef WINFUSION_SHM_CLIENT_H
#define WINFUSION_SHM_CLIENT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define WINFUSION_SHM_ENV "WINFUSION_SHM"
#define CLIENT_REQUEST_BUFFER_SIZE 16

#define CLIENT_REQUEST_SIZE_TYPE uint16_t
#define CLIENT_REQUEST_SIZE_INVALID 0
#define CLIENT_REQUEST_SIZE_1 5
#define CLIENT_REQUEST_SIZE_OFFSET 0

#define CLIENT_REQUEST_VERSION_TYPE uint8_t
#define CLIENT_REQUEST_VERSION_INVALID 0
#define CLIENT_REQUEST_VERSION_1 1
#define CLIENT_REQUEST_VERSION_OFFSET 2

#define CLIENT_REQUEST_TYPE_TYPE uint16_t
#define CLIENT_REQUEST_TYPE_INVALID 0
#define CLIENT_REQUEST_TYPE_MIN 1
/* 1=MIDISynth、4=TaskManager：都已下架（改为 rootfs 独立进程 / 文件映射直通），编号保留；
 * 3=GameController、5=Open 仍在用 */
#define CLIENT_REQUEST_TYPE_MAX 5
#define CLIENT_REQUEST_TYPE_OFFSET 3

#define SHM_SERVER_FD_RESPONSE 'A'

typedef struct SHMClient SHMClient;

CLIENT_REQUEST_SIZE_TYPE get_request_size(const void *request);
void set_request_size(void *request, CLIENT_REQUEST_SIZE_TYPE size);

CLIENT_REQUEST_VERSION_TYPE get_request_version(const void *request);
void set_request_version(void *request, CLIENT_REQUEST_VERSION_TYPE version);

CLIENT_REQUEST_TYPE_TYPE get_request_type(const void *request);
void set_request_type(void *request, CLIENT_REQUEST_TYPE_TYPE type);

SHMClient *shm_client_create(void);
void shm_client_destroy(SHMClient *client);
bool shm_client_try_destroy(SHMClient *client);
bool shm_client_connect(SHMClient *client, const char *socketPath,
                        CLIENT_REQUEST_TYPE_TYPE clientType,
                        size_t sharedMemorySize);
bool shm_client_connect_from_env(SHMClient *client,
                                 CLIENT_REQUEST_TYPE_TYPE clientType,
                                 size_t sharedMemorySize);
bool shm_client_is_connected(const SHMClient *client);
void shm_client_disconnect(SHMClient *client);
bool shm_client_try_disconnect(SHMClient *client);
int shm_client_duplicate_socket_fd(SHMClient *client);
void *shm_client_get_shared_memory(const SHMClient *client);
size_t shm_client_get_shared_memory_size(const SHMClient *client);
bool shm_client_acquire_mapping(SHMClient *client, void **mappingOut,
                                size_t *sizeOut);
void shm_client_release_mapping(SHMClient *client);

#ifdef __cplusplus
}
#endif

#endif //WINFUSION_SHM_CLIENT_H
