// SPDX-License-Identifier: MIT OR Apache-2.0
#define _GNU_SOURCE

#include "serve_flat_host.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

enum {
  DEFAULT_PORT = 7977,
  INHERITED_LISTEN_FD = 3,
  LISTEN_BACKLOG = 128
};

typedef struct daemon_config {
  uint16_t port;
  const char *log_path;
  const char *space_id;
} daemon_config;

typedef struct writer_authority {
  int fd;
  char *canonical_log_path;
  char *lock_path;
} writer_authority;

static volatile sig_atomic_t stop_requested = 0;

static void print_usage(void) {
  fputs("usage: fram-daemon-native [serve] [port] [log] [space-id]\n",
        stderr);
}

static bool nonempty(const char *value) {
  return value != NULL && value[0] != '\0';
}

static int parse_port(const char *text, uint16_t *port_out) {
  char *end = NULL;
  long value;

  errno = 0;
  value = strtol(text, &end, 10);
  if (errno != 0 || end == text || *end != '\0' || value < 1 || value > 65535) {
    fprintf(stderr, "fram-daemon-native: invalid port: %s\n", text);
    return -1;
  }
  *port_out = (uint16_t)value;
  return 0;
}

static int load_config(int argc, char **argv, daemon_config *config) {
  int index = 1;
  const char *port_text;
  const char *log_path;
  const char *space_id;

  if (index < argc && strcmp(argv[index], "serve") == 0) {
    index += 1;
  }
  if (argc - index > 3) {
    print_usage();
    return -1;
  }

  port_text = index < argc && nonempty(argv[index])
                  ? argv[index]
                  : getenv("FRAM_PORT");
  if (!nonempty(port_text)) {
    config->port = DEFAULT_PORT;
  } else {
    if (parse_port(port_text, &config->port) != 0) {
      return -1;
    }
  }

  log_path = index + 1 < argc && nonempty(argv[index + 1])
                 ? argv[index + 1]
                 : getenv("FRAM_LOG");
  config->log_path = nonempty(log_path) ? log_path : "coordination.log";

  space_id = index + 2 < argc && nonempty(argv[index + 2])
                 ? argv[index + 2]
                 : getenv("FRAM_SPACE_ID");
  config->space_id = nonempty(space_id) ? space_id : NULL;
  return 0;
}

static int reject_unsupported_environment(void) {
  static const char *const tls_variables[] = {
      "FRAM_TLS_KEYSTORE", "FRAM_TLS_TRUSTSTORE", "FRAM_TLS_PASS",
      "FRAM_TLS_PASS_FILE"};
  const char *bind = getenv("FRAM_BIND");
  const char *role = getenv("FRAM_COORD_ROLE");
  size_t index;

  for (index = 0; index < sizeof(tls_variables) / sizeof(tls_variables[0]);
       index += 1) {
    if (nonempty(getenv(tls_variables[index]))) {
      fprintf(stderr,
              "fram-daemon-native: %s is unsupported by the deployed native "
              "host; refusing plaintext fallback\n",
              tls_variables[index]);
      return -1;
    }
  }
  if (nonempty(bind) && strcmp(bind, "loopback") != 0 &&
      strcmp(bind, "127.0.0.1") != 0) {
    fprintf(stderr,
            "fram-daemon-native: FRAM_BIND=%s is unsupported; this host is "
            "loopback-only\n",
            bind);
    return -1;
  }
  if (nonempty(role) && strcmp(role, "active") != 0) {
    fprintf(stderr,
            "fram-daemon-native: FRAM_COORD_ROLE=%s is unsupported; this host "
            "requires active writer authority\n",
            role);
    return -1;
  }
  return 0;
}

static void handle_stop_signal(int signal_number) {
  (void)signal_number;
  stop_requested = 1;
}

static int install_signal_handlers(void) {
  struct sigaction action;

  memset(&action, 0, sizeof(action));
  action.sa_handler = handle_stop_signal;
  if (sigemptyset(&action.sa_mask) != 0 ||
      sigaction(SIGINT, &action, NULL) != 0 ||
      sigaction(SIGTERM, &action, NULL) != 0 || signal(SIGPIPE, SIG_IGN) == SIG_ERR) {
    fprintf(stderr, "fram-daemon-native: cannot install signal handlers: %s\n",
            strerror(errno));
    return -1;
  }
  return 0;
}

static void release_writer_authority(writer_authority *authority) {
  if (authority->fd >= 0) {
    (void)close(authority->fd);
  }
  free(authority->canonical_log_path);
  free(authority->lock_path);
  authority->fd = -1;
  authority->canonical_log_path = NULL;
  authority->lock_path = NULL;
}

static int ensure_regular_log(const char *path) {
  struct stat status;
  int fd = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0666);

  if (fd < 0) {
    fprintf(stderr, "fram-daemon-native: cannot open log %s: %s\n", path,
            strerror(errno));
    return -1;
  }
  if (fstat(fd, &status) != 0 || !S_ISREG(status.st_mode)) {
    fprintf(stderr, "fram-daemon-native: log is not a regular file: %s\n", path);
    (void)close(fd);
    return -1;
  }
  if (close(fd) != 0) {
    fprintf(stderr, "fram-daemon-native: cannot close log %s: %s\n", path,
            strerror(errno));
    return -1;
  }
  return 0;
}

static int acquire_writer_authority(const char *log_path,
                                    writer_authority *authority) {
  struct flock lock;
  struct stat status;
  size_t lock_path_size;
  int lock_fd;

  if (ensure_regular_log(log_path) != 0) {
    return -1;
  }
  authority->canonical_log_path = realpath(log_path, NULL);
  if (authority->canonical_log_path == NULL) {
    fprintf(stderr, "fram-daemon-native: cannot canonicalize log %s: %s\n",
            log_path, strerror(errno));
    return -1;
  }

  lock_path_size = strlen(authority->canonical_log_path) +
                   sizeof(".writer-authority.lock");
  authority->lock_path = malloc(lock_path_size);
  if (authority->lock_path == NULL) {
    fputs("fram-daemon-native: cannot allocate writer lock path\n", stderr);
    return -1;
  }
  (void)snprintf(authority->lock_path, lock_path_size, "%s.writer-authority.lock",
                 authority->canonical_log_path);

  if (lstat(authority->lock_path, &status) == 0) {
    if (S_ISLNK(status.st_mode)) {
      fprintf(stderr,
              "fram-daemon-native: writer authority path is a symlink: %s\n",
              authority->lock_path);
      return -1;
    }
  } else if (errno != ENOENT) {
    fprintf(stderr, "fram-daemon-native: cannot inspect writer lock %s: %s\n",
            authority->lock_path, strerror(errno));
    return -1;
  }

  lock_fd = open(authority->lock_path,
                 O_WRONLY | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0666);
  if (lock_fd < 0) {
    fprintf(stderr, "fram-daemon-native: cannot open writer lock %s: %s\n",
            authority->lock_path, strerror(errno));
    return -1;
  }
  if (fstat(lock_fd, &status) != 0 || !S_ISREG(status.st_mode)) {
    fprintf(stderr, "fram-daemon-native: writer lock is not a regular file: %s\n",
            authority->lock_path);
    (void)close(lock_fd);
    return -1;
  }

  memset(&lock, 0, sizeof(lock));
  lock.l_type = F_WRLCK;
  lock.l_whence = SEEK_SET;
  // FileChannel.tryLock uses fcntl locks; flock would not fence the live JVM.
  if (fcntl(lock_fd, F_SETLK, &lock) != 0) {
    if (errno == EACCES || errno == EAGAIN) {
      fprintf(stderr,
              "fram-daemon-native: another coordinator holds writer authority "
              "for %s\n",
              authority->canonical_log_path);
    } else {
      fprintf(stderr, "fram-daemon-native: cannot lock %s: %s\n",
              authority->lock_path, strerror(errno));
    }
    (void)close(lock_fd);
    return -1;
  }
  authority->fd = lock_fd;
  return 0;
}

static int set_close_on_exec(int fd) {
  int flags = fcntl(fd, F_GETFD);

  if (flags < 0 || fcntl(fd, F_SETFD, flags | FD_CLOEXEC) != 0) {
    return -1;
  }
  return 0;
}

static int parse_inherited_fd(void) {
  const char *text = getenv("FRAM_LISTEN_FD");
  char *end = NULL;
  long value;

  if (text == NULL) {
    return -1;
  }
  errno = 0;
  value = strtol(text, &end, 10);
  if (errno != 0 || end == text || *end != '\0' || value != INHERITED_LISTEN_FD) {
    fprintf(stderr,
            "fram-daemon-native: FRAM_LISTEN_FD must be exactly %d, got %s\n",
            INHERITED_LISTEN_FD, text);
    return -2;
  }
  return INHERITED_LISTEN_FD;
}

static int inherited_socket_port(int fd, uint16_t *port_out) {
  struct sockaddr_storage address;
  socklen_t address_length = sizeof(address);

  memset(&address, 0, sizeof(address));
  if (getsockname(fd, (struct sockaddr *)&address, &address_length) != 0) {
    return -1;
  }
  if (address.ss_family == AF_INET) {
    const struct sockaddr_in *ipv4 = (const struct sockaddr_in *)&address;
    if (ntohl(ipv4->sin_addr.s_addr) != INADDR_LOOPBACK) {
      errno = EADDRNOTAVAIL;
      return -1;
    }
    *port_out = ntohs(ipv4->sin_port);
    return 0;
  }
  if (address.ss_family == AF_INET6) {
    const struct sockaddr_in6 *ipv6 = (const struct sockaddr_in6 *)&address;
    if (!IN6_IS_ADDR_LOOPBACK(&ipv6->sin6_addr)) {
      errno = EADDRNOTAVAIL;
      return -1;
    }
    *port_out = ntohs(ipv6->sin6_port);
    return 0;
  }
  errno = EAFNOSUPPORT;
  return -1;
}

static int validate_inherited_socket(int fd, uint16_t expected_port) {
  int socket_type = 0;
  int accepting = 0;
  socklen_t option_length = sizeof(socket_type);
  uint16_t actual_port = 0;

  if (fcntl(fd, F_GETFD) < 0 ||
      getsockopt(fd, SOL_SOCKET, SO_TYPE, &socket_type, &option_length) != 0 ||
      socket_type != SOCK_STREAM) {
    fprintf(stderr,
            "fram-daemon-native: FRAM_LISTEN_FD=%d is not an open stream socket\n",
            fd);
    return -1;
  }
  option_length = sizeof(accepting);
  if (getsockopt(fd, SOL_SOCKET, SO_ACCEPTCONN, &accepting, &option_length) != 0 ||
      accepting == 0) {
    fprintf(stderr,
            "fram-daemon-native: FRAM_LISTEN_FD=%d is not a listening socket\n",
            fd);
    return -1;
  }
  if (inherited_socket_port(fd, &actual_port) != 0) {
    fprintf(stderr,
            "fram-daemon-native: inherited listener must be a loopback IP "
            "socket: %s\n",
            strerror(errno));
    return -1;
  }
  if (actual_port != expected_port) {
    fprintf(stderr,
            "fram-daemon-native: inherited listener port %u does not match "
            "FRAM_PORT %u\n",
            (unsigned int)actual_port, (unsigned int)expected_port);
    return -1;
  }
  if (set_close_on_exec(fd) != 0) {
    fprintf(stderr,
            "fram-daemon-native: cannot set close-on-exec on inherited listener: "
            "%s\n",
            strerror(errno));
    return -1;
  }
  return 0;
}

static int create_loopback_listener(uint16_t port) {
  struct sockaddr_in address;
  int reuse = 1;
  int fd = socket(AF_INET, SOCK_STREAM, 0);

  if (fd < 0) {
    fprintf(stderr, "fram-daemon-native: cannot create listener: %s\n",
            strerror(errno));
    return -1;
  }
  if (set_close_on_exec(fd) != 0 ||
      setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse)) != 0) {
    fprintf(stderr, "fram-daemon-native: cannot configure listener: %s\n",
            strerror(errno));
    (void)close(fd);
    return -1;
  }

  memset(&address, 0, sizeof(address));
  address.sin_family = AF_INET;
  address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  address.sin_port = htons(port);
  if (bind(fd, (const struct sockaddr *)&address, sizeof(address)) != 0 ||
      listen(fd, LISTEN_BACKLOG) != 0) {
    fprintf(stderr, "fram-daemon-native: cannot listen on 127.0.0.1:%u: %s\n",
            (unsigned int)port, strerror(errno));
    (void)close(fd);
    return -1;
  }
  return fd;
}

static int open_listener(uint16_t port) {
  int inherited_fd = parse_inherited_fd();

  if (inherited_fd == -2) {
    return -1;
  }
  if (inherited_fd >= 0) {
    if (validate_inherited_socket(inherited_fd, port) != 0) {
      return -1;
    }
    return inherited_fd;
  }
  return create_loopback_listener(port);
}

static const char *hook_detail(const char *error) {
  return nonempty(error) ? error : "generated hook returned no detail";
}

static void terminate_hook_error(char *error) {
  error[FRAM_SERVE_FLAT_ERROR_CAPACITY - 1u] = '\0';
}

static int serve_client(int client_fd, fram_serve_flat_store *store) {
  char error[FRAM_SERVE_FLAT_ERROR_CAPACITY];
  fram_serve_flat_request *request = NULL;
  fram_serve_flat_response *response = NULL;
  int status;

  error[0] = '\0';
  status = fram_serve_flat_codec_read_request(
      client_fd, &request, error, sizeof(error));
  terminate_hook_error(error);
  if (status == FRAM_SERVE_FLAT_PEER_CLOSED && request == NULL) {
    return 0;
  }
  if (status != FRAM_SERVE_FLAT_OK || request == NULL) {
    fprintf(stderr,
            "fram-daemon-native: fram_serve_flat_codec_read_request failed "
            "(%d): %s\n",
            status, hook_detail(error));
    if (request != NULL) {
      fram_serve_flat_codec_release_request(request);
    }
    return -1;
  }

  error[0] = '\0';
  status = fram_serve_flat_store_dispatch(store, request, &response, error,
                                          sizeof(error));
  terminate_hook_error(error);
  fram_serve_flat_codec_release_request(request);
  if (status != FRAM_SERVE_FLAT_OK || response == NULL) {
    fprintf(stderr,
            "fram-daemon-native: fram_serve_flat_store_dispatch failed (%d): "
            "%s\n",
            status, hook_detail(error));
    if (response != NULL) {
      fram_serve_flat_codec_release_response(response);
    }
    return -1;
  }

  error[0] = '\0';
  status = fram_serve_flat_codec_write_response(client_fd, response, error,
                                                sizeof(error));
  terminate_hook_error(error);
  fram_serve_flat_codec_release_response(response);
  if (status == FRAM_SERVE_FLAT_PEER_CLOSED) {
    return 0;
  }
  if (status != FRAM_SERVE_FLAT_OK) {
    fprintf(stderr,
            "fram-daemon-native: fram_serve_flat_codec_write_response failed "
            "(%d): %s\n",
            status, hook_detail(error));
    return -1;
  }
  return 0;
}

static int accept_loop(int listener_fd, fram_serve_flat_store *store) {
  while (stop_requested == 0) {
    int client_fd = accept(listener_fd, NULL, NULL);

    if (client_fd < 0) {
      if (errno == EINTR) {
        continue;
      }
      if (errno == ECONNABORTED) {
        continue;
      }
      fprintf(stderr, "fram-daemon-native: accept failed: %s\n",
              strerror(errno));
      return -1;
    }
    if (set_close_on_exec(client_fd) != 0) {
      fprintf(stderr,
              "fram-daemon-native: cannot set close-on-exec on client: %s\n",
              strerror(errno));
      (void)close(client_fd);
      return -1;
    }
    if (serve_client(client_fd, store) != 0) {
      (void)close(client_fd);
      return -1;
    }
    if (close(client_fd) != 0) {
      fprintf(stderr, "fram-daemon-native: cannot close client: %s\n",
              strerror(errno));
      return -1;
    }
  }
  return 0;
}

int main(int argc, char **argv) {
  daemon_config config;
  writer_authority authority = {.fd = -1,
                                .canonical_log_path = NULL,
                                .lock_path = NULL};
  fram_serve_flat_store *store = NULL;
  char error[FRAM_SERVE_FLAT_ERROR_CAPACITY];
  int listener_fd = -1;
  int result = 1;
  int status;
  uint32_t generated_abi = fram_serve_flat_generated_abi();

  if (generated_abi != FRAM_SERVE_FLAT_GENERATED_ABI) {
    fprintf(stderr,
            "fram-daemon-native: generated host ABI mismatch; expected %u, "
            "got %u\n",
            (unsigned int)FRAM_SERVE_FLAT_GENERATED_ABI,
            (unsigned int)generated_abi);
    return 2;
  }
  if (load_config(argc, argv, &config) != 0 ||
      reject_unsupported_environment() != 0 || install_signal_handlers() != 0) {
    return 2;
  }
  if (acquire_writer_authority(config.log_path, &authority) != 0) {
    release_writer_authority(&authority);
    return 1;
  }

  error[0] = '\0';
  status = fram_serve_flat_store_boot(authority.canonical_log_path,
                                      config.space_id, &store, error,
                                      sizeof(error));
  terminate_hook_error(error);
  if (status != FRAM_SERVE_FLAT_OK || store == NULL) {
    fprintf(stderr,
            "fram-daemon-native: fram_serve_flat_store_boot failed (%d): %s\n",
            status, hook_detail(error));
    goto cleanup;
  }

  listener_fd = open_listener(config.port);
  if (listener_fd < 0) {
    goto cleanup;
  }
  fprintf(stderr,
          "fram-daemon-native: listening on 127.0.0.1:%u, log=%s\n",
          (unsigned int)config.port, authority.canonical_log_path);
  result = accept_loop(listener_fd, store) == 0 ? 0 : 1;

cleanup:
  if (listener_fd >= 0) {
    (void)close(listener_fd);
  }
  if (store != NULL) {
    error[0] = '\0';
    status = fram_serve_flat_store_shutdown(store, error, sizeof(error));
    terminate_hook_error(error);
    if (status != FRAM_SERVE_FLAT_OK) {
      fprintf(stderr,
              "fram-daemon-native: fram_serve_flat_store_shutdown failed (%d): "
              "%s\n",
              status, hook_detail(error));
      result = 1;
    }
  }
  release_writer_authority(&authority);
  return result;
}
