// SPDX-License-Identifier: MIT OR Apache-2.0
#define _GNU_SOURCE

#include "server_host.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
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
  LISTEN_BACKLOG = 128,
  MAX_ACTIVE_CLIENTS = 128,
  ACCEPT_POLL_MILLISECONDS = 100
};

typedef enum server_bind {
  SERVER_BIND_LOOPBACK,
  SERVER_BIND_IPV4_LOOPBACK,
  SERVER_BIND_ANY
} server_bind;

typedef struct server_config {
  uint16_t port;
  const char *log_path;
  const char *space_id;
  server_bind bind;
  const char *bind_text;
} server_config;

typedef struct writer_authority {
  int fd;
  char *canonical_log_path;
  char *lock_path;
} writer_authority;

typedef struct server_context server_context;

typedef struct client_job {
  int fd;
  server_context *server;
  struct client_job *next;
} client_job;

struct server_context {
  fram_server_store *store;
  pthread_mutex_t dispatch_mutex;
  pthread_mutex_t clients_mutex;
  pthread_cond_t clients_idle;
  pthread_attr_t worker_attributes;
  client_job *clients;
  size_t client_count;
  bool fatal;
};

static volatile sig_atomic_t stop_requested = 0;

static void print_usage(void) {
  fputs("usage: fram-server-native [serve] [port] [log] [space-id]\n",
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
    fprintf(stderr, "fram-server-native: invalid port: %s\n", text);
    return -1;
  }
  *port_out = (uint16_t)value;
  return 0;
}

static int parse_bind(server_config *config) {
  const char *bind = getenv("FRAM_BIND");

  if (!nonempty(bind) || strcmp(bind, "loopback") == 0) {
    config->bind = SERVER_BIND_LOOPBACK;
    config->bind_text = "127.0.0.1";
    return 0;
  }
  if (strcmp(bind, "127.0.0.1") == 0) {
    config->bind = SERVER_BIND_IPV4_LOOPBACK;
    config->bind_text = "127.0.0.1";
    return 0;
  }
  if (strcmp(bind, "0.0.0.0") == 0) {
    config->bind = SERVER_BIND_ANY;
    config->bind_text = "0.0.0.0";
    return 0;
  }
  fprintf(stderr,
          "fram-server-native: FRAM_BIND=%s is unsupported; expected "
          "loopback, 127.0.0.1, or 0.0.0.0\n",
          bind);
  return -1;
}

static int load_config(int argc, char **argv, server_config *config) {
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
                  : getenv("FRAM_SERVER_PORT");
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
  return parse_bind(config);
}

static int reject_unsupported_environment(void) {
  static const char *const tls_variables[] = {
      "FRAM_TLS_KEYSTORE", "FRAM_TLS_TRUSTSTORE", "FRAM_TLS_PASS",
      "FRAM_TLS_PASS_FILE"};
  const char *role = getenv("FRAM_SERVER_ROLE");
  size_t index;

  for (index = 0; index < sizeof(tls_variables) / sizeof(tls_variables[0]);
       index += 1) {
    if (nonempty(getenv(tls_variables[index]))) {
      fprintf(stderr,
              "fram-server-native: %s is unsupported by the deployed native "
              "host; refusing plaintext fallback\n",
              tls_variables[index]);
      return -1;
    }
  }
  if (nonempty(role) && strcmp(role, "active") != 0) {
    fprintf(stderr,
            "fram-server-native: FRAM_SERVER_ROLE=%s is unsupported; this host "
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
    fprintf(stderr, "fram-server-native: cannot install signal handlers: %s\n",
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
    fprintf(stderr, "fram-server-native: cannot open log %s: %s\n", path,
            strerror(errno));
    return -1;
  }
  if (fstat(fd, &status) != 0 || !S_ISREG(status.st_mode)) {
    fprintf(stderr, "fram-server-native: log is not a regular file: %s\n", path);
    (void)close(fd);
    return -1;
  }
  if (close(fd) != 0) {
    fprintf(stderr, "fram-server-native: cannot close log %s: %s\n", path,
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
    fprintf(stderr, "fram-server-native: cannot canonicalize log %s: %s\n",
            log_path, strerror(errno));
    return -1;
  }

  lock_path_size = strlen(authority->canonical_log_path) +
                   sizeof(".writer-authority.lock");
  authority->lock_path = malloc(lock_path_size);
  if (authority->lock_path == NULL) {
    fputs("fram-server-native: cannot allocate writer lock path\n", stderr);
    return -1;
  }
  (void)snprintf(authority->lock_path, lock_path_size, "%s.writer-authority.lock",
                 authority->canonical_log_path);

  if (lstat(authority->lock_path, &status) == 0) {
    if (S_ISLNK(status.st_mode)) {
      fprintf(stderr,
              "fram-server-native: writer authority path is a symlink: %s\n",
              authority->lock_path);
      return -1;
    }
  } else if (errno != ENOENT) {
    fprintf(stderr, "fram-server-native: cannot inspect writer lock %s: %s\n",
            authority->lock_path, strerror(errno));
    return -1;
  }

  lock_fd = open(authority->lock_path,
                 O_WRONLY | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0666);
  if (lock_fd < 0) {
    fprintf(stderr, "fram-server-native: cannot open writer lock %s: %s\n",
            authority->lock_path, strerror(errno));
    return -1;
  }
  if (fstat(lock_fd, &status) != 0 || !S_ISREG(status.st_mode)) {
    fprintf(stderr, "fram-server-native: writer lock is not a regular file: %s\n",
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
              "fram-server-native: another server holds writer authority "
              "for %s\n",
              authority->canonical_log_path);
    } else {
      fprintf(stderr, "fram-server-native: cannot lock %s: %s\n",
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
            "fram-server-native: FRAM_LISTEN_FD must be exactly %d, got %s\n",
            INHERITED_LISTEN_FD, text);
    return -2;
  }
  return INHERITED_LISTEN_FD;
}

static int inherited_socket_port(int fd, server_bind bind_mode,
                                 uint16_t *port_out) {
  struct sockaddr_storage address;
  socklen_t address_length = sizeof(address);

  memset(&address, 0, sizeof(address));
  if (getsockname(fd, (struct sockaddr *)&address, &address_length) != 0) {
    return -1;
  }
  if (address.ss_family == AF_INET) {
    const struct sockaddr_in *ipv4 = (const struct sockaddr_in *)&address;
    uint32_t expected =
        bind_mode == SERVER_BIND_ANY ? INADDR_ANY : INADDR_LOOPBACK;
    if (ntohl(ipv4->sin_addr.s_addr) != expected) {
      errno = EADDRNOTAVAIL;
      return -1;
    }
    *port_out = ntohs(ipv4->sin_port);
    return 0;
  }
  if (address.ss_family == AF_INET6) {
    const struct sockaddr_in6 *ipv6 = (const struct sockaddr_in6 *)&address;
    if (bind_mode != SERVER_BIND_LOOPBACK ||
        !IN6_IS_ADDR_LOOPBACK(&ipv6->sin6_addr)) {
      errno = EADDRNOTAVAIL;
      return -1;
    }
    *port_out = ntohs(ipv6->sin6_port);
    return 0;
  }
  errno = EAFNOSUPPORT;
  return -1;
}

static int validate_inherited_socket(int fd, const server_config *config) {
  int socket_type = 0;
  int accepting = 0;
  socklen_t option_length = sizeof(socket_type);
  uint16_t actual_port = 0;

  if (fcntl(fd, F_GETFD) < 0 ||
      getsockopt(fd, SOL_SOCKET, SO_TYPE, &socket_type, &option_length) != 0 ||
      socket_type != SOCK_STREAM) {
    fprintf(stderr,
            "fram-server-native: FRAM_LISTEN_FD=%d is not an open stream socket\n",
            fd);
    return -1;
  }
  option_length = sizeof(accepting);
  if (getsockopt(fd, SOL_SOCKET, SO_ACCEPTCONN, &accepting, &option_length) != 0 ||
      accepting == 0) {
    fprintf(stderr,
            "fram-server-native: FRAM_LISTEN_FD=%d is not a listening socket\n",
            fd);
    return -1;
  }
  if (inherited_socket_port(fd, config->bind, &actual_port) != 0) {
    fprintf(stderr,
            "fram-server-native: inherited listener does not match "
            "FRAM_BIND=%s: %s\n",
            config->bind_text, strerror(errno));
    return -1;
  }
  if (actual_port != config->port) {
    fprintf(stderr,
            "fram-server-native: inherited listener port %u does not match "
            "FRAM_SERVER_PORT %u\n",
            (unsigned int)actual_port, (unsigned int)config->port);
    return -1;
  }
  if (set_close_on_exec(fd) != 0) {
    fprintf(stderr,
            "fram-server-native: cannot set close-on-exec on inherited listener: "
            "%s\n",
            strerror(errno));
    return -1;
  }
  return 0;
}

static int create_listener(const server_config *config) {
  struct sockaddr_in address;
  int reuse = 1;
  int fd = socket(AF_INET, SOCK_STREAM, 0);

  if (fd < 0) {
    fprintf(stderr, "fram-server-native: cannot create listener: %s\n",
            strerror(errno));
    return -1;
  }
  if (set_close_on_exec(fd) != 0 ||
      setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse)) != 0) {
    fprintf(stderr, "fram-server-native: cannot configure listener: %s\n",
            strerror(errno));
    (void)close(fd);
    return -1;
  }

  memset(&address, 0, sizeof(address));
  address.sin_family = AF_INET;
  address.sin_addr.s_addr = htonl(config->bind == SERVER_BIND_ANY
                                      ? INADDR_ANY
                                      : INADDR_LOOPBACK);
  address.sin_port = htons(config->port);
  if (bind(fd, (const struct sockaddr *)&address, sizeof(address)) != 0 ||
      listen(fd, LISTEN_BACKLOG) != 0) {
    fprintf(stderr, "fram-server-native: cannot listen on %s:%u: %s\n",
            config->bind_text, (unsigned int)config->port, strerror(errno));
    (void)close(fd);
    return -1;
  }
  return fd;
}

static int open_listener(const server_config *config) {
  int inherited_fd = parse_inherited_fd();

  if (inherited_fd == -2) {
    return -1;
  }
  if (inherited_fd >= 0) {
    if (validate_inherited_socket(inherited_fd, config) != 0) {
      return -1;
    }
    return inherited_fd;
  }
  return create_listener(config);
}

static const char *hook_detail(const char *error) {
  return nonempty(error) ? error : "generated hook returned no detail";
}

static void terminate_hook_error(char *error) {
  error[FRAM_SERVER_ERROR_CAPACITY - 1u] = '\0';
}

static int serialized_dispatch(
    server_context *server, const fram_server_request *request,
    fram_server_response **response, char *error,
    size_t error_capacity) {
  int thread_status = pthread_mutex_lock(&server->dispatch_mutex);
  int hook_status;

  if (thread_status != 0) {
    fprintf(stderr, "fram-server-native: cannot lock dispatch: %s\n",
            strerror(thread_status));
    return FRAM_SERVER_FATAL;
  }
  hook_status = fram_server_store_dispatch(
      server->store, request, response, error, error_capacity);
  thread_status = pthread_mutex_unlock(&server->dispatch_mutex);
  if (thread_status != 0) {
    fprintf(stderr, "fram-server-native: cannot unlock dispatch: %s\n",
            strerror(thread_status));
    return FRAM_SERVER_FATAL;
  }
  return hook_status;
}

static int serialized_release_response(
    server_context *server, fram_server_response *response) {
  int thread_status = pthread_mutex_lock(&server->dispatch_mutex);

  if (thread_status != 0) {
    fprintf(stderr,
            "fram-server-native: cannot lock response release: %s\n",
            strerror(thread_status));
    return -1;
  }
  fram_server_codec_release_response(response);
  thread_status = pthread_mutex_unlock(&server->dispatch_mutex);
  if (thread_status != 0) {
    fprintf(stderr,
            "fram-server-native: cannot unlock response release: %s\n",
            strerror(thread_status));
    return -1;
  }
  return 0;
}

static int serve_client(int client_fd, server_context *server) {
  char error[FRAM_SERVER_ERROR_CAPACITY];
  fram_server_request *request = NULL;
  fram_server_response *response = NULL;
  int status;
  int release_status;

  error[0] = '\0';
  status = fram_server_codec_read_request(
      client_fd, &request, error, sizeof(error));
  terminate_hook_error(error);
  if (status == FRAM_SERVER_PEER_CLOSED && request == NULL) {
    return 0;
  }
  if (status == FRAM_SERVER_CLIENT_ERROR) {
    fprintf(stderr,
            "fram-server-native: fram_server_codec_read_request failed "
            "(%d): %s\n",
            status, hook_detail(error));
    if (request != NULL) {
      fram_server_codec_release_request(request);
    }
    return 0;
  }
  if (status != FRAM_SERVER_OK || request == NULL) {
    fprintf(stderr,
            "fram-server-native: fram_server_codec_read_request failed "
            "(%d): %s\n",
            status, hook_detail(error));
    if (request != NULL) {
      fram_server_codec_release_request(request);
    }
    return -1;
  }

  error[0] = '\0';
  status = serialized_dispatch(server, request, &response, error,
                               sizeof(error));
  terminate_hook_error(error);
  fram_server_codec_release_request(request);
  if (status != FRAM_SERVER_OK || response == NULL) {
    fprintf(stderr,
            "fram-server-native: fram_server_store_dispatch failed (%d): "
            "%s\n",
            status, hook_detail(error));
    if (response != NULL) {
      if (serialized_release_response(server, response) != 0) {
        return -1;
      }
    }
    return -1;
  }

  error[0] = '\0';
  status = fram_server_codec_write_response(client_fd, response, error,
                                                sizeof(error));
  terminate_hook_error(error);
  release_status = serialized_release_response(server, response);
  if (release_status != 0) {
    return -1;
  }
  if (status == FRAM_SERVER_PEER_CLOSED) {
    return 0;
  }
  if (status == FRAM_SERVER_CLIENT_ERROR) {
    fprintf(stderr,
            "fram-server-native: fram_server_codec_write_response failed "
            "(%d): %s\n",
            status, hook_detail(error));
    return 0;
  }
  if (status != FRAM_SERVER_OK) {
    fprintf(stderr,
            "fram-server-native: fram_server_codec_write_response failed "
            "(%d): %s\n",
            status, hook_detail(error));
    return -1;
  }
  return 0;
}

static int initialize_server_context(server_context *server,
                                     fram_server_store *store) {
  int status;

  memset(server, 0, sizeof(*server));
  server->store = store;
  status = pthread_mutex_init(&server->dispatch_mutex, NULL);
  if (status != 0) {
    fprintf(stderr, "fram-server-native: cannot initialize dispatch lock: %s\n",
            strerror(status));
    return -1;
  }
  status = pthread_mutex_init(&server->clients_mutex, NULL);
  if (status != 0) {
    fprintf(stderr, "fram-server-native: cannot initialize client lock: %s\n",
            strerror(status));
    (void)pthread_mutex_destroy(&server->dispatch_mutex);
    return -1;
  }
  status = pthread_cond_init(&server->clients_idle, NULL);
  if (status != 0) {
    fprintf(stderr,
            "fram-server-native: cannot initialize client condition: %s\n",
            strerror(status));
    (void)pthread_mutex_destroy(&server->clients_mutex);
    (void)pthread_mutex_destroy(&server->dispatch_mutex);
    return -1;
  }
  status = pthread_attr_init(&server->worker_attributes);
  if (status != 0) {
    fprintf(stderr,
            "fram-server-native: cannot initialize worker attributes: %s\n",
            strerror(status));
    (void)pthread_cond_destroy(&server->clients_idle);
    (void)pthread_mutex_destroy(&server->clients_mutex);
    (void)pthread_mutex_destroy(&server->dispatch_mutex);
    return -1;
  }
  status = pthread_attr_setdetachstate(&server->worker_attributes,
                                       PTHREAD_CREATE_DETACHED);
  if (status != 0) {
    fprintf(stderr,
            "fram-server-native: cannot configure worker attributes: %s\n",
            strerror(status));
    (void)pthread_attr_destroy(&server->worker_attributes);
    (void)pthread_cond_destroy(&server->clients_idle);
    (void)pthread_mutex_destroy(&server->clients_mutex);
    (void)pthread_mutex_destroy(&server->dispatch_mutex);
    return -1;
  }
  /* musl defaults workers to 128 KiB; compaction replays the full log on a
     worker. */
  status = pthread_attr_setstacksize(&server->worker_attributes,
                                     (size_t)8u * 1024u * 1024u);
  if (status != 0) {
    fprintf(stderr, "fram-server-native: cannot size worker stacks: %s\n",
            strerror(status));
    (void)pthread_attr_destroy(&server->worker_attributes);
    (void)pthread_cond_destroy(&server->clients_idle);
    (void)pthread_mutex_destroy(&server->clients_mutex);
    (void)pthread_mutex_destroy(&server->dispatch_mutex);
    return -1;
  }
  return 0;
}

static void destroy_server_context(server_context *server) {
  (void)pthread_attr_destroy(&server->worker_attributes);
  (void)pthread_cond_destroy(&server->clients_idle);
  (void)pthread_mutex_destroy(&server->clients_mutex);
  (void)pthread_mutex_destroy(&server->dispatch_mutex);
}

static bool server_has_fatal_failure(server_context *server) {
  bool fatal;

  (void)pthread_mutex_lock(&server->clients_mutex);
  fatal = server->fatal;
  (void)pthread_mutex_unlock(&server->clients_mutex);
  return fatal;
}

static void remove_client_locked(server_context *server, client_job *job) {
  client_job **cursor = &server->clients;

  while (*cursor != NULL && *cursor != job) {
    cursor = &(*cursor)->next;
  }
  if (*cursor == job) {
    *cursor = job->next;
    server->client_count -= 1u;
  }
}

static void *serve_client_worker(void *argument) {
  client_job *job = argument;
  server_context *server = job->server;
  bool fatal = serve_client(job->fd, server) != 0;

  (void)pthread_mutex_lock(&server->clients_mutex);
  if (close(job->fd) != 0) {
    fprintf(stderr, "fram-server-native: cannot close client: %s\n",
            strerror(errno));
  }
  job->fd = -1;
  if (fatal) {
    server->fatal = true;
  }
  remove_client_locked(server, job);
  (void)pthread_cond_broadcast(&server->clients_idle);
  (void)pthread_mutex_unlock(&server->clients_mutex);
  free(job);
  return NULL;
}

static bool stop_clients_and_wait(server_context *server) {
  client_job *job;
  bool fatal;

  (void)pthread_mutex_lock(&server->clients_mutex);
  for (job = server->clients; job != NULL; job = job->next) {
    if (job->fd >= 0) {
      (void)shutdown(job->fd, SHUT_RDWR);
    }
  }
  while (server->client_count != 0u) {
    (void)pthread_cond_wait(&server->clients_idle, &server->clients_mutex);
  }
  fatal = server->fatal;
  (void)pthread_mutex_unlock(&server->clients_mutex);
  return fatal;
}

static int accept_loop(int listener_fd, fram_server_store *store) {
  server_context server;
  struct pollfd listener_poll = {.fd = listener_fd,
                                 .events = POLLIN,
                                 .revents = 0};
  bool failed = false;

  if (initialize_server_context(&server, store) != 0) {
    return -1;
  }
  while (stop_requested == 0 && !server_has_fatal_failure(&server)) {
    client_job *job;
    pthread_t worker;
    int client_fd;
    int status;

    listener_poll.revents = 0;
    status = poll(&listener_poll, 1, ACCEPT_POLL_MILLISECONDS);
    if (status < 0) {
      if (errno == EINTR) {
        continue;
      }
      fprintf(stderr, "fram-server-native: listener poll failed: %s\n",
              strerror(errno));
      failed = true;
      break;
    }
    if (status == 0) {
      continue;
    }
    if ((listener_poll.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
      fputs("fram-server-native: listener became unavailable\n", stderr);
      failed = true;
      break;
    }
    if ((listener_poll.revents & POLLIN) == 0) {
      continue;
    }
    client_fd = accept(listener_fd, NULL, NULL);

    if (client_fd < 0) {
      if (errno == EINTR) {
        continue;
      }
      if (errno == ECONNABORTED) {
        continue;
      }
      fprintf(stderr, "fram-server-native: accept failed: %s\n",
              strerror(errno));
      failed = true;
      break;
    }
    if (stop_requested != 0 || server_has_fatal_failure(&server)) {
      (void)close(client_fd);
      break;
    }
    if (set_close_on_exec(client_fd) != 0) {
      fprintf(stderr,
              "fram-server-native: cannot set close-on-exec on client: %s\n",
              strerror(errno));
      (void)close(client_fd);
      continue;
    }

    job = malloc(sizeof(*job));
    if (job == NULL) {
      fputs("fram-server-native: cannot allocate client worker\n", stderr);
      (void)close(client_fd);
      failed = true;
      break;
    }
    job->fd = client_fd;
    job->server = &server;
    (void)pthread_mutex_lock(&server.clients_mutex);
    if (server.client_count >= (size_t)MAX_ACTIVE_CLIENTS) {
      (void)pthread_mutex_unlock(&server.clients_mutex);
      fputs("fram-server-native: active client limit reached\n", stderr);
      (void)close(client_fd);
      free(job);
      continue;
    }
    job->next = server.clients;
    server.clients = job;
    server.client_count += 1u;
    status = pthread_create(&worker, &server.worker_attributes,
                            serve_client_worker, job);
    if (status != 0) {
      remove_client_locked(&server, job);
      (void)pthread_mutex_unlock(&server.clients_mutex);
      fprintf(stderr, "fram-server-native: cannot create client worker: %s\n",
              strerror(status));
      (void)close(client_fd);
      free(job);
      failed = true;
      break;
    }
    (void)pthread_mutex_unlock(&server.clients_mutex);
  }
  if (stop_clients_and_wait(&server)) {
    failed = true;
  }
  destroy_server_context(&server);
  return failed ? -1 : 0;
}

int main(int argc, char **argv) {
  server_config config;
  writer_authority authority = {.fd = -1,
                                .canonical_log_path = NULL,
                                .lock_path = NULL};
  fram_server_store *store = NULL;
  char error[FRAM_SERVER_ERROR_CAPACITY];
  int listener_fd = -1;
  int result = 1;
  int status;
  uint32_t generated_abi = fram_server_generated_abi();

  if (generated_abi != FRAM_SERVER_GENERATED_ABI) {
    fprintf(stderr,
            "fram-server-native: generated host ABI mismatch; expected %u, "
            "got %u\n",
            (unsigned int)FRAM_SERVER_GENERATED_ABI,
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
  status = fram_server_store_boot(authority.canonical_log_path,
                                      config.space_id, &store, error,
                                      sizeof(error));
  terminate_hook_error(error);
  if (status != FRAM_SERVER_OK || store == NULL) {
    fprintf(stderr,
            "fram-server-native: fram_server_store_boot failed (%d): %s\n",
            status, hook_detail(error));
    goto cleanup;
  }

  listener_fd = open_listener(&config);
  if (listener_fd < 0) {
    goto cleanup;
  }
  fprintf(stderr,
          "fram-server-native: listening on %s:%u, log=%s\n",
          config.bind_text, (unsigned int)config.port,
          authority.canonical_log_path);
  result = accept_loop(listener_fd, store) == 0 ? 0 : 1;

cleanup:
  if (listener_fd >= 0) {
    (void)close(listener_fd);
  }
  if (store != NULL) {
    error[0] = '\0';
    status = fram_server_store_shutdown(store, error, sizeof(error));
    terminate_hook_error(error);
    if (status != FRAM_SERVER_OK) {
      fprintf(stderr,
              "fram-server-native: fram_server_store_shutdown failed (%d): "
              "%s\n",
              status, hook_detail(error));
      result = 1;
    }
  }
  release_writer_authority(&authority);
  return result;
}
