#define _GNU_SOURCE

#include <shm/shm-client.h>

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>

#ifndef MSG_CMSG_CLOEXEC
#define MSG_CMSG_CLOEXEC 0
#endif

struct SHMClient {
    int socketFd;
    int sharedMemoryFd;
    void *sharedMemory;
    size_t sharedMemorySize;
    uint32_t lifecycleState;
};

#define SHM_CLIENT_STATE_DISCONNECTED UINT32_C(0x00000000)
#define SHM_CLIENT_STATE_CONNECTING UINT32_C(0x40000000)
#define SHM_CLIENT_STATE_CONNECTED UINT32_C(0x80000000)
#define SHM_CLIENT_STATE_CLOSING UINT32_C(0xc0000000)
#define SHM_CLIENT_STATE_MASK UINT32_C(0xc0000000)
#define SHM_CLIENT_REFERENCE_MASK UINT32_C(0x3fffffff)

CLIENT_REQUEST_SIZE_TYPE get_request_size(const void *request) {
    const uint8_t *bytes = request;

    if (!bytes)
        return CLIENT_REQUEST_SIZE_INVALID;
    return (CLIENT_REQUEST_SIZE_TYPE) bytes[CLIENT_REQUEST_SIZE_OFFSET] |
           ((CLIENT_REQUEST_SIZE_TYPE) bytes[CLIENT_REQUEST_SIZE_OFFSET + 1] << 8);
}

void set_request_size(void *request, CLIENT_REQUEST_SIZE_TYPE size) {
    uint8_t *bytes = request;

    if (!bytes)
        return;
    bytes[CLIENT_REQUEST_SIZE_OFFSET] = (uint8_t) (size & 0xff);
    bytes[CLIENT_REQUEST_SIZE_OFFSET + 1] = (uint8_t) (size >> 8);
}

CLIENT_REQUEST_VERSION_TYPE get_request_version(const void *request) {
    const uint8_t *bytes = request;

    if (!bytes)
        return CLIENT_REQUEST_VERSION_INVALID;
    return bytes[CLIENT_REQUEST_VERSION_OFFSET];
}

void set_request_version(void *request, CLIENT_REQUEST_VERSION_TYPE version) {
    uint8_t *bytes = request;

    if (bytes)
        bytes[CLIENT_REQUEST_VERSION_OFFSET] = version;
}

CLIENT_REQUEST_TYPE_TYPE get_request_type(const void *request) {
    const uint8_t *bytes = request;

    if (!bytes)
        return CLIENT_REQUEST_TYPE_INVALID;
    return (CLIENT_REQUEST_TYPE_TYPE) bytes[CLIENT_REQUEST_TYPE_OFFSET] |
           ((CLIENT_REQUEST_TYPE_TYPE) bytes[CLIENT_REQUEST_TYPE_OFFSET + 1] << 8);
}

void set_request_type(void *request, CLIENT_REQUEST_TYPE_TYPE type) {
    uint8_t *bytes = request;

    if (!bytes)
        return;
    bytes[CLIENT_REQUEST_TYPE_OFFSET] = (uint8_t) (type & 0xff);
    bytes[CLIENT_REQUEST_TYPE_OFFSET + 1] = (uint8_t) (type >> 8);
}

static int create_client_socket(void) {
    int socket_fd;
    int flags;
#ifdef SO_NOSIGPIPE
    int no_sigpipe = 1;
#endif

#ifdef SOCK_CLOEXEC
    socket_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (socket_fd < 0 && errno == EINVAL)
#endif
        socket_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (socket_fd < 0)
        return -1;

    flags = fcntl(socket_fd, F_GETFD);
    if (flags == -1 || fcntl(socket_fd, F_SETFD, flags | FD_CLOEXEC) == -1)
        goto error;
#ifdef SO_NOSIGPIPE
    if (setsockopt(socket_fd, SOL_SOCKET, SO_NOSIGPIPE,
                   &no_sigpipe, sizeof(no_sigpipe)) == -1)
        goto error;
#endif
    return socket_fd;

error:
    flags = errno;
    close(socket_fd);
    errno = flags;
    return -1;
}

static bool connect_socket(int socket_fd, const char *socket_path) {
    struct sockaddr_un address;
    socklen_t address_length;
    size_t path_length;
    int result;

    path_length = strlen(socket_path);
    if (!path_length || path_length >= sizeof(address.sun_path)) {
        errno = ENAMETOOLONG;
        return false;
    }

    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path, socket_path, path_length + 1);
    address_length = (socklen_t) (offsetof(struct sockaddr_un, sun_path) +
                                  path_length + 1);
    result = connect(socket_fd, (struct sockaddr *) &address,
                     address_length);
    return result == 0;
}

static bool send_request(int socket_fd, CLIENT_REQUEST_TYPE_TYPE client_type) {
    uint8_t request[CLIENT_REQUEST_SIZE_1] = {0};
    size_t sent = 0;
    ssize_t result;
    int send_flags = 0;

    set_request_size(request, CLIENT_REQUEST_SIZE_1);
    set_request_version(request, CLIENT_REQUEST_VERSION_1);
    set_request_type(request, client_type);
#ifdef MSG_NOSIGNAL
    send_flags = MSG_NOSIGNAL;
#endif

    while (sent < sizeof(request)) {
        result = send(socket_fd, request + sent, sizeof(request) - sent,
                      send_flags);
        if (result > 0) {
            sent += (size_t) result;
            continue;
        }
        if (result == -1 && errno == EINTR)
            continue;
        if (!result)
            errno = EPIPE;
        return false;
    }
    return true;
}

static int receive_shared_memory_fd(int socket_fd) {
    struct msghdr message;
    struct iovec io;
    struct cmsghdr *control_message;
    union {
        struct cmsghdr alignment;
        char buffer[CMSG_SPACE(sizeof(int) * 4)];
    } control;
    char response = 0;
    int received_fd = -1;
    int *fds;
    size_t fd_count;
    ssize_t received;
    bool invalid = false;
    int saved_errno;

    memset(&message, 0, sizeof(message));
    memset(&control, 0, sizeof(control));
    io.iov_base = &response;
    io.iov_len = sizeof(response);
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    message.msg_control = control.buffer;
    message.msg_controllen = sizeof(control.buffer);

    do {
        received = recvmsg(socket_fd, &message, MSG_CMSG_CLOEXEC);
    } while (received == -1 && errno == EINTR);
    if (received != sizeof(response)) {
        if (!received)
            errno = ECONNRESET;
        else if (received > 0)
            errno = EPROTO;
        return -1;
    }

    if (message.msg_flags & (MSG_CTRUNC | MSG_TRUNC))
        invalid = true;
    if (response != SHM_SERVER_FD_RESPONSE)
        invalid = true;

    for (control_message = CMSG_FIRSTHDR(&message);
         control_message;
         control_message = CMSG_NXTHDR(&message, control_message)) {
        if (control_message->cmsg_level != SOL_SOCKET ||
            control_message->cmsg_type != SCM_RIGHTS ||
            control_message->cmsg_len < CMSG_LEN(sizeof(int))) {
            invalid = true;
            continue;
        }

        if ((control_message->cmsg_len - CMSG_LEN(0)) % sizeof(int))
            invalid = true;
        fd_count = (control_message->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        fds = (int *) CMSG_DATA(control_message);
        for (size_t i = 0; i < fd_count; i++) {
            if (received_fd < 0)
                received_fd = fds[i];
            else {
                close(fds[i]);
                invalid = true;
            }
        }
    }

    if (received_fd < 0)
        invalid = true;
    if (invalid) {
        if (received_fd >= 0)
            close(received_fd);
        errno = EPROTO;
        return -1;
    }

    int flags = fcntl(received_fd, F_GETFD);
    if (flags == -1 ||
        fcntl(received_fd, F_SETFD, flags | FD_CLOEXEC) == -1) {
        saved_errno = errno;
        close(received_fd);
        errno = saved_errno;
        return -1;
    }
    return received_fd;
}

SHMClient *shm_client_create(void) {
    SHMClient *client = calloc(1, sizeof(*client));

    if (!client)
        return NULL;
    client->socketFd = -1;
    client->sharedMemoryFd = -1;
    return client;
}

bool shm_client_try_destroy(SHMClient *client) {
    if (!client)
        return true;
    if (!shm_client_try_disconnect(client))
        return false;
    free(client);
    return true;
}

void shm_client_destroy(SHMClient *client) {
    shm_client_try_destroy(client);
}

bool shm_client_connect(SHMClient *client, const char *socket_path,
                        CLIENT_REQUEST_TYPE_TYPE client_type,
                        size_t shared_memory_size) {
    struct stat memory_stat;
    void *shared_memory;
    int socket_fd;
    int memory_fd;
    int saved_errno;
    uint32_t expected_state;

    if (!client || !socket_path || !shared_memory_size ||
        client_type < CLIENT_REQUEST_TYPE_MIN ||
        client_type > CLIENT_REQUEST_TYPE_MAX) {
        errno = EINVAL;
        return false;
    }
    expected_state = SHM_CLIENT_STATE_DISCONNECTED;
    if (!__atomic_compare_exchange_n(&client->lifecycleState,
                                     &expected_state,
                                     SHM_CLIENT_STATE_CONNECTING,
                                     false, __ATOMIC_ACQ_REL,
                                     __ATOMIC_ACQUIRE)) {
        errno = EALREADY;
        return false;
    }

    socket_fd = create_client_socket();
    if (socket_fd < 0) {
        __atomic_store_n(&client->lifecycleState,
                         SHM_CLIENT_STATE_DISCONNECTED, __ATOMIC_RELEASE);
        return false;
    }
    if (!connect_socket(socket_fd, socket_path) ||
        !send_request(socket_fd, client_type))
        goto error_socket;

    memory_fd = receive_shared_memory_fd(socket_fd);
    if (memory_fd < 0)
        goto error_socket;
    if (fstat(memory_fd, &memory_stat) == -1)
        goto error_memory;
    if (memory_stat.st_size < 0 ||
        (!memory_stat.st_size && !S_ISCHR(memory_stat.st_mode)) ||
        (memory_stat.st_size > 0 &&
         (uintmax_t) memory_stat.st_size < shared_memory_size)) {
        errno = EINVAL;
        goto error_memory;
    }

    shared_memory = mmap(NULL, shared_memory_size,
                         PROT_READ | PROT_WRITE, MAP_SHARED, memory_fd, 0);
    if (shared_memory == MAP_FAILED)
        goto error_memory;

    client->socketFd = socket_fd;
    client->sharedMemoryFd = memory_fd;
    client->sharedMemory = shared_memory;
    client->sharedMemorySize = shared_memory_size;
    __atomic_store_n(&client->lifecycleState,
                     SHM_CLIENT_STATE_CONNECTED, __ATOMIC_RELEASE);
    return true;

error_memory:
    saved_errno = errno;
    close(memory_fd);
    errno = saved_errno;
error_socket:
    saved_errno = errno;
    close(socket_fd);
    __atomic_store_n(&client->lifecycleState,
                     SHM_CLIENT_STATE_DISCONNECTED, __ATOMIC_RELEASE);
    errno = saved_errno;
    return false;
}

bool shm_client_connect_from_env(SHMClient *client,
                                 CLIENT_REQUEST_TYPE_TYPE client_type,
                                 size_t shared_memory_size) {
    const char *socket_path = getenv(WINFUSION_SHM_ENV);

    if (!socket_path || !socket_path[0]) {
        errno = ENOENT;
        return false;
    }
    return shm_client_connect(client, socket_path, client_type,
                              shared_memory_size);
}

bool shm_client_is_connected(const SHMClient *client) {
    return client &&
           (__atomic_load_n(&client->lifecycleState, __ATOMIC_ACQUIRE) &
            SHM_CLIENT_STATE_MASK) == SHM_CLIENT_STATE_CONNECTED;
}

bool shm_client_try_disconnect(SHMClient *client) {
    uint32_t expected_state;
    int saved_errno;

    if (!client)
        return true;
    expected_state = SHM_CLIENT_STATE_CONNECTED;
    if (!__atomic_compare_exchange_n(&client->lifecycleState,
                                     &expected_state,
                                     SHM_CLIENT_STATE_CLOSING,
                                     false, __ATOMIC_ACQ_REL,
                                     __ATOMIC_ACQUIRE)) {
        if (expected_state == SHM_CLIENT_STATE_DISCONNECTED)
            return true;
        errno = EBUSY;
        return false;
    }
    saved_errno = errno;
    if (client->sharedMemory && client->sharedMemorySize)
        munmap(client->sharedMemory, client->sharedMemorySize);
    if (client->sharedMemoryFd >= 0)
        close(client->sharedMemoryFd);
    if (client->socketFd >= 0)
        close(client->socketFd);
    client->socketFd = -1;
    client->sharedMemoryFd = -1;
    client->sharedMemory = NULL;
    client->sharedMemorySize = 0;
    __atomic_store_n(&client->lifecycleState,
                     SHM_CLIENT_STATE_DISCONNECTED, __ATOMIC_RELEASE);
    errno = saved_errno;
    return true;
}

void shm_client_disconnect(SHMClient *client) {
    shm_client_try_disconnect(client);
}

int shm_client_duplicate_socket_fd(SHMClient *client) {
    void *mapping;
    size_t mapping_size;
    int duplicate_fd;
    int saved_errno;

    if (!shm_client_acquire_mapping(client, &mapping, &mapping_size))
        return -1;
    (void) mapping;
    (void) mapping_size;
#ifdef F_DUPFD_CLOEXEC
    duplicate_fd = fcntl(client->socketFd, F_DUPFD_CLOEXEC, 0);
    if (duplicate_fd < 0 && errno == EINVAL)
#endif
    {
        int flags;

        duplicate_fd = dup(client->socketFd);
        if (duplicate_fd >= 0) {
            flags = fcntl(duplicate_fd, F_GETFD);
            if (flags == -1 ||
                fcntl(duplicate_fd, F_SETFD,
                      flags | FD_CLOEXEC) == -1) {
                saved_errno = errno;
                close(duplicate_fd);
                duplicate_fd = -1;
                errno = saved_errno;
            }
        }
    }
    saved_errno = errno;
    shm_client_release_mapping(client);
    errno = saved_errno;
    return duplicate_fd;
}

void *shm_client_get_shared_memory(const SHMClient *client) {
    return shm_client_is_connected(client) ? client->sharedMemory : NULL;
}

size_t shm_client_get_shared_memory_size(const SHMClient *client) {
    return shm_client_is_connected(client) ? client->sharedMemorySize : 0;
}

bool shm_client_acquire_mapping(SHMClient *client, void **mapping_out,
                                size_t *size_out) {
    uint32_t state;

    if (!client || !mapping_out || !size_out) {
        errno = EINVAL;
        return false;
    }
    state = __atomic_load_n(&client->lifecycleState, __ATOMIC_ACQUIRE);
    while ((state & SHM_CLIENT_STATE_MASK) == SHM_CLIENT_STATE_CONNECTED) {
        if ((state & SHM_CLIENT_REFERENCE_MASK) ==
            SHM_CLIENT_REFERENCE_MASK) {
            errno = EOVERFLOW;
            return false;
        }
        if (__atomic_compare_exchange_n(&client->lifecycleState,
                                        &state, state + 1, false,
                                        __ATOMIC_ACQ_REL,
                                        __ATOMIC_ACQUIRE)) {
            *mapping_out = client->sharedMemory;
            *size_out = client->sharedMemorySize;
            return true;
        }
    }
    errno = ENOTCONN;
    return false;
}

void shm_client_release_mapping(SHMClient *client) {
    uint32_t state;

    if (!client)
        return;
    state = __atomic_load_n(&client->lifecycleState, __ATOMIC_ACQUIRE);
    while ((state & SHM_CLIENT_STATE_MASK) == SHM_CLIENT_STATE_CONNECTED &&
           (state & SHM_CLIENT_REFERENCE_MASK) &&
           !__atomic_compare_exchange_n(&client->lifecycleState,
                                        &state, state - 1, false,
                                        __ATOMIC_ACQ_REL,
                                        __ATOMIC_ACQUIRE)) {}
}
