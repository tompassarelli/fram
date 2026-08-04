#!/usr/bin/env bash
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "${scratch:?}"' EXIT INT TERM
cc="${CC:-cc}"

for command in "$cc" awk cmp nm sed sort; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "fram native generated adapter smoke: missing $command" >&2
    exit 1
  }
done

cat >"$scratch/native_shim.h" <<'HEADER'
#ifndef NATIVE_SHIM_H
#define NATIVE_SHIM_H
#include <stddef.h>
#include <stdint.h>

typedef struct native_arena {
  uint8_t *bytes;
  size_t capacity;
  size_t offset;
} native_arena;

typedef struct native_capability {
  uint64_t token;
} native_capability;

typedef struct native_vec {
  void *elements;
  int64_t length;
  int64_t capacity;
} native_vec;

void native_arena_init(native_arena *arena, uint8_t *storage, size_t capacity);
void *native_arena_alloc(native_arena *arena, size_t size, size_t alignment);
uint64_t native_text_alloc(native_arena *arena, uint64_t length, uint8_t **out);
uint64_t native_text_length(uint64_t handle);
const uint8_t *native_text_bytes(uint64_t handle);
native_vec *native_vec_new(native_arena *arena, int64_t capacity,
                           int64_t stride, size_t alignment);
int64_t native_vec_length(const native_vec *vector);
const void *native_vec_at(const native_vec *vector, int64_t index,
                          int64_t stride);
native_vec *native_vec_push(native_arena *arena, native_vec *vector,
                            const void *value, int64_t stride,
                            size_t alignment);
#endif
HEADER

cat >"$scratch/module_0.h" <<'HEADER'
#ifndef NATIVE_MODULE_0_ABI_H
#define NATIVE_MODULE_0_ABI_H
#include "native_shim.h"

typedef int64_t native_m0_type_0;
typedef uint64_t native_m0_type_1;
typedef native_vec *native_m0_type_2;
typedef uint64_t native_m0_type_3;

typedef struct native_m0_type_4 {
  native_m0_type_0 field_0;
  native_m0_type_3 field_1;
  native_m0_type_3 field_2;
  native_m0_type_0 field_3;
  native_m0_type_2 field_4;
} native_m0_type_4;

typedef struct native_m0_type_5 {
  native_m0_type_0 field_0;
  native_m0_type_3 field_1;
  native_m0_type_3 field_2;
} native_m0_type_5;

typedef struct native_m0_type_6 {
  native_m0_type_0 field_0;
  native_m0_type_3 field_1;
  native_m0_type_3 field_2;
  native_m0_type_2 field_3;
} native_m0_type_6;

typedef struct native_m0_type_7 {
  native_m0_type_0 field_0;
  native_m0_type_2 field_1;
} native_m0_type_7;

typedef struct native_m0_type_8 {
  native_m0_type_0 field_0;
  native_m0_type_3 field_1;
} native_m0_type_8;

native_m0_type_0 fram_stub_generated_abi(void);
native_m0_type_4 fram_stub_store_boot(
    native_arena *arena, const native_capability *capability,
    native_m0_type_1 canonical_log_path, native_m0_type_1 space_id,
    native_m0_type_2 log_bytes);
native_m0_type_6 fram_stub_store_dispatch(
    const native_capability *capability, native_m0_type_4 store,
    native_m0_type_5 request, native_m0_type_0 now_milliseconds);
native_m0_type_8 fram_stub_store_shutdown(native_m0_type_4 store);
native_m0_type_5 fram_stub_codec_read_request(native_arena *arena,
                                               native_m0_type_2 frame);
native_m0_type_7 fram_stub_codec_write_response(
    native_arena *arena, const native_capability *capability,
    native_m0_type_6 response);
native_m0_type_3 fram_stub_codec_release_request(native_m0_type_5 request);
native_m0_type_3 fram_stub_codec_release_response(
    const native_capability *capability, native_m0_type_6 response);
#endif
HEADER

cat >"$scratch/serve_flat_symbols.h" <<'HEADER'
#ifndef FRAM_SERVE_FLAT_SYMBOLS_H
#define FRAM_SERVE_FLAT_SYMBOLS_H
#include "module_0.h"

#define FRAM_SERVE_FLAT_SYMBOL_GENERATED_ABI fram_stub_generated_abi
#define FRAM_SERVE_FLAT_SYMBOL_STORE_BOOT fram_stub_store_boot
#define FRAM_SERVE_FLAT_SYMBOL_STORE_DISPATCH fram_stub_store_dispatch
#define FRAM_SERVE_FLAT_SYMBOL_STORE_SHUTDOWN fram_stub_store_shutdown
#define FRAM_SERVE_FLAT_SYMBOL_CODEC_READ_REQUEST fram_stub_codec_read_request
#define FRAM_SERVE_FLAT_SYMBOL_CODEC_WRITE_RESPONSE fram_stub_codec_write_response
#define FRAM_SERVE_FLAT_SYMBOL_CODEC_RELEASE_REQUEST                         \
  fram_stub_codec_release_request
#define FRAM_SERVE_FLAT_SYMBOL_CODEC_RELEASE_RESPONSE                        \
  fram_stub_codec_release_response

typedef native_m0_type_0 fram_serve_flat_generated_abi_return;
typedef native_m0_type_4 fram_serve_flat_store_boot_return;
typedef native_m0_type_1 fram_serve_flat_store_boot_arg_0;
typedef native_m0_type_1 fram_serve_flat_store_boot_arg_1;
typedef native_m0_type_2 fram_serve_flat_store_boot_arg_2;
typedef native_m0_type_6 fram_serve_flat_store_dispatch_return;
typedef native_m0_type_4 fram_serve_flat_store_dispatch_arg_0;
typedef native_m0_type_5 fram_serve_flat_store_dispatch_arg_1;
typedef native_m0_type_0 fram_serve_flat_store_dispatch_arg_2;
typedef native_m0_type_8 fram_serve_flat_store_shutdown_return;
typedef native_m0_type_4 fram_serve_flat_store_shutdown_arg_0;
typedef native_m0_type_5 fram_serve_flat_codec_read_request_return;
typedef native_m0_type_2 fram_serve_flat_codec_read_request_arg_0;
typedef native_m0_type_7 fram_serve_flat_codec_write_response_return;
typedef native_m0_type_6 fram_serve_flat_codec_write_response_arg_0;
typedef native_m0_type_3 fram_serve_flat_codec_release_request_return;
typedef native_m0_type_5 fram_serve_flat_codec_release_request_arg_0;
typedef native_m0_type_3 fram_serve_flat_codec_release_response_return;
typedef native_m0_type_6 fram_serve_flat_codec_release_response_arg_0;

#define FRAM_SERVE_FLAT_CALL_GENERATED_ABI(arena, capability)                \
  FRAM_SERVE_FLAT_SYMBOL_GENERATED_ABI()
#define FRAM_SERVE_FLAT_CALL_STORE_BOOT(arena, capability, arg_0, arg_1,     \
                                        arg_2)                               \
  FRAM_SERVE_FLAT_SYMBOL_STORE_BOOT((arena), (capability), (arg_0), (arg_1), \
                                    (arg_2))
#define FRAM_SERVE_FLAT_CALL_STORE_DISPATCH(                                 \
    arena, capability, arg_0, arg_1, arg_2)                                  \
  FRAM_SERVE_FLAT_SYMBOL_STORE_DISPATCH((capability), (arg_0), (arg_1),      \
                                        (arg_2))
#define FRAM_SERVE_FLAT_CALL_STORE_SHUTDOWN(arena, capability, arg_0)        \
  FRAM_SERVE_FLAT_SYMBOL_STORE_SHUTDOWN((arg_0))
#define FRAM_SERVE_FLAT_CALL_CODEC_READ_REQUEST(arena, capability, arg_0)    \
  FRAM_SERVE_FLAT_SYMBOL_CODEC_READ_REQUEST((arena), (arg_0))
#define FRAM_SERVE_FLAT_CALL_CODEC_WRITE_RESPONSE(arena, capability, arg_0)  \
  FRAM_SERVE_FLAT_SYMBOL_CODEC_WRITE_RESPONSE((arena), (capability), (arg_0))
#define FRAM_SERVE_FLAT_CALL_CODEC_RELEASE_REQUEST(arena, capability, arg_0) \
  FRAM_SERVE_FLAT_SYMBOL_CODEC_RELEASE_REQUEST((arg_0))
#define FRAM_SERVE_FLAT_CALL_CODEC_RELEASE_RESPONSE(arena, capability,       \
                                                    arg_0)                   \
  FRAM_SERVE_FLAT_SYMBOL_CODEC_RELEASE_RESPONSE((capability), (arg_0))
#endif
HEADER

cat >"$scratch/native_shim.c" <<'C'
#include "native_shim.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

void native_arena_init(native_arena *arena, uint8_t *storage, size_t capacity) {
  arena->bytes = storage;
  arena->capacity = capacity;
  arena->offset = 0u;
}

void *native_arena_alloc(native_arena *arena, size_t size, size_t alignment) {
  uintptr_t current = (uintptr_t)(arena->bytes + arena->offset);
  uintptr_t aligned;
  size_t offset;

  if (alignment == 0u || (alignment & (alignment - 1u)) != 0u) {
    abort();
  }
  aligned = (current + alignment - 1u) & ~(uintptr_t)(alignment - 1u);
  offset = (size_t)(aligned - (uintptr_t)arena->bytes);
  if (offset > arena->capacity || size > arena->capacity - offset) {
    abort();
  }
  arena->offset = offset + size;
  return arena->bytes + offset;
}

uint64_t native_text_alloc(native_arena *arena, uint64_t length, uint8_t **out) {
  uint8_t *storage = native_arena_alloc(
      arena, sizeof(length) + (size_t)length, _Alignof(uint64_t));

  memcpy(storage, &length, sizeof(length));
  *out = storage + sizeof(length);
  return (uint64_t)(uintptr_t)storage;
}

uint64_t native_text_length(uint64_t handle) {
  uint64_t length;
  memcpy(&length, (const void *)(uintptr_t)handle, sizeof(length));
  return length;
}

const uint8_t *native_text_bytes(uint64_t handle) {
  return (const uint8_t *)(uintptr_t)handle + sizeof(uint64_t);
}

native_vec *native_vec_new(native_arena *arena, int64_t capacity,
                           int64_t stride, size_t alignment) {
  native_vec *vector = native_arena_alloc(arena, sizeof(*vector),
                                           _Alignof(native_vec));
  size_t bytes;

  if (capacity < INT64_C(0) || stride <= INT64_C(0) ||
      capacity > INT64_MAX / stride) {
    abort();
  }
  bytes = (size_t)(capacity * stride);
  vector->elements = bytes == 0u ? NULL
                                 : native_arena_alloc(arena, bytes, alignment);
  vector->length = INT64_C(0);
  vector->capacity = capacity;
  return vector;
}

int64_t native_vec_length(const native_vec *vector) { return vector->length; }

const void *native_vec_at(const native_vec *vector, int64_t index,
                          int64_t stride) {
  if (index < INT64_C(0) || index >= vector->length) {
    abort();
  }
  return (const uint8_t *)vector->elements + (size_t)(index * stride);
}

native_vec *native_vec_push(native_arena *arena, native_vec *vector,
                            const void *value, int64_t stride,
                            size_t alignment) {
  (void)arena;
  (void)alignment;
  if (vector->length >= vector->capacity) {
    abort();
  }
  memcpy((uint8_t *)vector->elements + (size_t)(vector->length * stride),
         value, (size_t)stride);
  vector->length += INT64_C(1);
  return vector;
}
C

cat >"$scratch/generated_stub.c" <<'C'
#include "module_0.h"

#include <stdbool.h>
#include <stdint.h>
#include <string.h>

enum { OK = 0, FATAL = 2 };

static unsigned int boot_calls;
static unsigned int dispatch_calls;
static unsigned int shutdown_calls;
static unsigned int read_calls;
static unsigned int write_calls;
static unsigned int release_request_calls;
static unsigned int release_response_calls;

static int64_t tail_items[] = {'T', 'A', 'I', 'L'};
static native_vec tail_append = {tail_items, INT64_C(4), INT64_C(4)};

static const uint8_t response_frame[] = {
    0x46, 0x52, 0x41, 0x4d, 0x52, 0x50, 0x43, 0x00, 0x01, 0x00,
    0x00, 0x00, 0x02, 0x00, 0x02, 0x00, 0x00, 0x00, 0x07, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xdd, 0xee};

static bool text_is(uint64_t text, const char *expected) {
  size_t length = strlen(expected);
  return native_text_length(text) == (uint64_t)length &&
         memcmp(native_text_bytes(text), expected, length) == 0;
}

static bool vector_is(const native_vec *vector, const uint8_t *expected,
                      size_t count) {
  size_t index;

  if (vector == NULL || native_vec_length(vector) != (int64_t)count) {
    return false;
  }
  for (index = 0u; index < count; index += 1u) {
    const int64_t *value = native_vec_at(vector, (int64_t)index, INT64_C(8));
    if (*value != (int64_t)expected[index]) {
      return false;
    }
  }
  return true;
}

static native_vec *make_vector(native_arena *arena, const uint8_t *bytes,
                               size_t count) {
  native_vec *vector = native_vec_new(arena, (int64_t)count, INT64_C(8),
                                      _Alignof(int64_t));
  size_t index;

  for (index = 0u; index < count; index += 1u) {
    int64_t value = (int64_t)bytes[index];
    vector = native_vec_push(arena, vector, &value, INT64_C(8),
                             _Alignof(int64_t));
  }
  return vector;
}

native_m0_type_0 fram_stub_generated_abi(void) { return INT64_C(1); }

native_m0_type_4 fram_stub_store_boot(
    native_arena *arena, const native_capability *capability,
    native_m0_type_1 canonical_log_path, native_m0_type_1 space_id,
    native_m0_type_2 log_bytes) {
  static const uint8_t old_log[] = {'O', 'L', 'D', '!', 'x'};
  static const uint8_t boot_bytes[] = {'B', 'O', 'O', 'T'};
  native_m0_type_4 result = {FATAL, UINT64_C(0), UINT64_C(0), INT64_C(0),
                             NULL};

  boot_calls += 1u;
  if (capability->token == UINT64_C(1) &&
      text_is(canonical_log_path, "SMOKE_LOG_PATH") &&
      text_is(space_id, "smoke-space") &&
      vector_is(log_bytes, old_log, sizeof(old_log))) {
    result.field_0 = OK;
    result.field_1 = UINT64_C(11);
    result.field_3 = INT64_C(4);
    result.field_4 = make_vector(arena, boot_bytes, sizeof(boot_bytes));
  }
  return result;
}

native_m0_type_6 fram_stub_store_dispatch(
    const native_capability *capability, native_m0_type_4 store,
    native_m0_type_5 request, native_m0_type_0 now_milliseconds) {
  native_m0_type_6 result = {FATAL, UINT64_C(0), UINT64_C(0), NULL};

  dispatch_calls += 1u;
  if (capability->token == UINT64_C(1) && store.field_0 == OK &&
      store.field_1 == UINT64_C(11) && request.field_0 == OK &&
      request.field_1 != UINT64_C(0) && now_milliseconds > INT64_C(0)) {
    result.field_0 = OK;
    result.field_1 = request.field_1;
    result.field_3 = &tail_append;
  }
  return result;
}

native_m0_type_8 fram_stub_store_shutdown(native_m0_type_4 store) {
  native_m0_type_8 result = {FATAL, UINT64_C(0)};
  shutdown_calls += 1u;
  if (store.field_0 == OK && store.field_1 == UINT64_C(11)) {
    result.field_0 = OK;
  }
  return result;
}

native_m0_type_5 fram_stub_codec_read_request(native_arena *arena,
                                               native_m0_type_2 frame) {
  native_m0_type_5 result = {FATAL, UINT64_C(0), UINT64_C(0)};
  const int64_t *body;
  (void)arena;

  read_calls += 1u;
  if (frame != NULL && native_vec_length(frame) == INT64_C(29)) {
    body = native_vec_at(frame, INT64_C(26), INT64_C(8));
    if (*body == INT64_C(0xaa)) {
      result.field_0 = OK;
      result.field_1 = (uint64_t)(uintptr_t)frame;
    }
  }
  return result;
}

native_m0_type_7 fram_stub_codec_write_response(
    native_arena *arena, const native_capability *capability,
    native_m0_type_6 response) {
  native_m0_type_7 result = {FATAL, NULL};

  write_calls += 1u;
  if (capability->token == UINT64_C(1) && response.field_0 == OK &&
      response.field_1 != UINT64_C(0)) {
    const native_vec *borrowed = (const native_vec *)(uintptr_t)response.field_1;
    const int64_t *body = native_vec_at(borrowed, INT64_C(26), INT64_C(8));
    if (*body == INT64_C(0xaa)) {
      result.field_0 = OK;
      result.field_1 = make_vector(arena, response_frame,
                                   sizeof(response_frame));
    }
  }
  return result;
}

native_m0_type_3 fram_stub_codec_release_request(native_m0_type_5 request) {
  if (request.field_0 == OK || request.field_0 == FATAL) {
    release_request_calls += 1u;
  }
  return UINT64_C(0);
}

native_m0_type_3 fram_stub_codec_release_response(
    const native_capability *capability, native_m0_type_6 response) {
  if (capability->token == UINT64_C(1) && response.field_0 == OK) {
    release_response_calls += 1u;
  }
  return UINT64_C(0);
}

bool generated_stub_observed_exact_calls(void) {
  return boot_calls == 1u && dispatch_calls == 1u && shutdown_calls == 1u &&
         read_calls == 2u && write_calls == 1u &&
         release_request_calls == 2u && release_response_calls == 1u;
}
C

sed -i "s|SMOKE_LOG_PATH|$scratch/fram.log|" "$scratch/generated_stub.c"

cat >"$scratch/main.c" <<'C'
#include "serve_flat_host.h"

#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

bool generated_stub_observed_exact_calls(void);

static const uint8_t request_frame[] = {
    0x46, 0x52, 0x41, 0x4d, 0x52, 0x50, 0x43, 0x00, 0x01, 0x00,
    0x00, 0x00, 0x01, 0x00, 0x03, 0x00, 0x00, 0x00, 0x07, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xaa, 0xbb, 0xcc};

static const uint8_t response_frame[] = {
    0x46, 0x52, 0x41, 0x4d, 0x52, 0x50, 0x43, 0x00, 0x01, 0x00,
    0x00, 0x00, 0x02, 0x00, 0x02, 0x00, 0x00, 0x00, 0x07, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xdd, 0xee};

static bool write_all(int fd, const uint8_t *bytes, size_t length) {
  size_t position = 0u;
  while (position < length) {
    ssize_t count = write(fd, bytes + position, length - position);
    if (count <= 0) {
      return false;
    }
    position += (size_t)count;
  }
  return true;
}

static bool read_all(int fd, uint8_t *bytes, size_t length) {
  size_t position = 0u;
  while (position < length) {
    ssize_t count = read(fd, bytes + position, length - position);
    if (count <= 0) {
      return false;
    }
    position += (size_t)count;
  }
  return true;
}

static bool file_is(const char *path, const char *expected) {
  uint8_t bytes[64];
  size_t length = strlen(expected);
  int fd = open(path, O_RDONLY);
  ssize_t extra;

  if (fd < 0 || length > sizeof(bytes) || !read_all(fd, bytes, length)) {
    if (fd >= 0) {
      (void)close(fd);
    }
    return false;
  }
  extra = read(fd, bytes + length, 1u);
  (void)close(fd);
  return extra == 0 && memcmp(bytes, expected, length) == 0;
}

static int request_from_bytes(const uint8_t *bytes, size_t length,
                              fram_serve_flat_request **request, char *error,
                              size_t error_capacity) {
  int pair[2];
  int status;

  if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0 ||
      !write_all(pair[1], bytes, length) || shutdown(pair[1], SHUT_WR) != 0) {
    return -1;
  }
  status = fram_serve_flat_codec_read_request(pair[0], request, error,
                                               error_capacity);
  (void)close(pair[0]);
  (void)close(pair[1]);
  return status;
}

int main(int argc, char **argv) {
  fram_serve_flat_store *store = NULL;
  fram_serve_flat_request *request = NULL;
  fram_serve_flat_request *failed = NULL;
  fram_serve_flat_response *response = NULL;
  uint8_t bad_frame[sizeof(request_frame)];
  uint8_t oversized_header[26];
  uint8_t received[sizeof(response_frame)];
  char error[FRAM_SERVE_FLAT_ERROR_CAPACITY];
  int pair[2];

  if (argc != 2 ||
      fram_serve_flat_generated_abi() != FRAM_SERVE_FLAT_GENERATED_ABI ||
      fram_serve_flat_store_boot(argv[1], "smoke-space", &store, error,
                                 sizeof(error)) != FRAM_SERVE_FLAT_OK ||
      store == NULL || error[0] != '\0' || !file_is(argv[1], "OLD!BOOT")) {
    return 1;
  }

  if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0) {
    return 2;
  }
  (void)close(pair[1]);
  if (fram_serve_flat_codec_read_request(pair[0], &failed, error,
                                          sizeof(error)) !=
          FRAM_SERVE_FLAT_PEER_CLOSED ||
      failed != NULL || error[0] != '\0') {
    return 3;
  }
  (void)close(pair[0]);

  if (request_from_bytes(request_frame, 3u, &failed, error, sizeof(error)) !=
          FRAM_SERVE_FLAT_FATAL ||
      failed != NULL ||
      strcmp(error, "generated request frame ended inside its header") != 0) {
    return 4;
  }

  memcpy(oversized_header, request_frame, sizeof(oversized_header));
  oversized_header[14] = 0x01;
  oversized_header[15] = 0x00;
  oversized_header[16] = 0x10;
  oversized_header[17] = 0x00;
  if (request_from_bytes(oversized_header, sizeof(oversized_header), &failed,
                         error, sizeof(error)) != FRAM_SERVE_FLAT_FATAL ||
      failed != NULL ||
      strcmp(error, "generated request frame exceeds the body limit") != 0) {
    return 5;
  }

  memcpy(bad_frame, request_frame, sizeof(bad_frame));
  bad_frame[26] = 0xee;
  if (request_from_bytes(bad_frame, sizeof(bad_frame), &failed, error,
                         sizeof(error)) != FRAM_SERVE_FLAT_FATAL ||
      failed != NULL ||
      strcmp(error, "generated request decode failed") != 0) {
    return 6;
  }

  if (request_from_bytes(request_frame, sizeof(request_frame), &request, error,
                         sizeof(error)) != FRAM_SERVE_FLAT_OK ||
      request == NULL ||
      fram_serve_flat_store_dispatch(store, request, &response, error,
                                     sizeof(error)) != FRAM_SERVE_FLAT_OK ||
      response == NULL || !file_is(argv[1], "OLD!BOOTTAIL")) {
    return 7;
  }
  fram_serve_flat_codec_release_request(request);

  if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0 ||
      fram_serve_flat_codec_write_response(pair[0], response, error,
                                           sizeof(error)) !=
          FRAM_SERVE_FLAT_OK ||
      !read_all(pair[1], received, sizeof(received)) ||
      memcmp(received, response_frame, sizeof(received)) != 0) {
    return 8;
  }
  (void)close(pair[0]);
  (void)close(pair[1]);
  fram_serve_flat_codec_release_response(response);

  if (fram_serve_flat_store_shutdown(store, error, sizeof(error)) !=
          FRAM_SERVE_FLAT_OK ||
      !file_is(argv[1], "OLD!BOOTTAIL") ||
      !generated_stub_observed_exact_calls()) {
    return 9;
  }
  return 0;
}
C

common_flags=(-std=c17 -pedantic -Wall -Wextra -Werror -I "$scratch" -I "$repo/native")
"$cc" "${common_flags[@]}" -c "$repo/native/serve_flat_generated.c" \
  -o "$scratch/adapter.o"

nm -u "$scratch/adapter.o" \
  | awk '{print $NF}' \
  | sed -n '/^fram_stub_/p' \
  | sort >"$scratch/actual-exports"
cat >"$scratch/expected-exports" <<'EXPORTS'
fram_stub_codec_read_request
fram_stub_codec_release_request
fram_stub_codec_release_response
fram_stub_codec_write_response
fram_stub_generated_abi
fram_stub_store_boot
fram_stub_store_dispatch
fram_stub_store_shutdown
EXPORTS
cmp "$scratch/expected-exports" "$scratch/actual-exports"

"$cc" "${common_flags[@]}" "$scratch/adapter.o" \
  "$scratch/native_shim.c" "$scratch/generated_stub.c" "$scratch/main.c" \
  -o "$scratch/smoke"
printf 'OLD!x' >"$scratch/fram.log"
"$scratch/smoke" "$scratch/fram.log"

echo "fram native generated adapter smoke: PASS"
