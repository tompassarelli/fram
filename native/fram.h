// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef FRAM_H
#define FRAM_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32) && defined(FRAM_SHARED)
#if defined(FRAM_BUILDING_SHARED)
#define FRAM_API __declspec(dllexport)
#else
#define FRAM_API __declspec(dllimport)
#endif
#elif defined(__GNUC__) || defined(__clang__)
#define FRAM_API __attribute__((visibility("default")))
#else
#define FRAM_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define FRAM_ABI_VERSION 1u
#define FRAM_ERROR_MESSAGE_CAPACITY 512u

typedef struct fram_database fram_database;

typedef enum fram_status {
  FRAM_OK = 0,
  FRAM_INVALID_ARGUMENT = 1,
  FRAM_CLIENT_ERROR = 2,
  FRAM_ENGINE_ERROR = 3,
  FRAM_HOST_ERROR = 4,
  FRAM_OUT_OF_MEMORY = 5
} fram_status;

typedef struct fram_slice {
  const uint8_t *data;
  size_t length;
} fram_slice;

typedef void *(*fram_allocate_fn)(void *context, size_t size);
typedef void (*fram_deallocate_fn)(void *context, void *allocation);

typedef struct fram_buffer {
  uint8_t *data;
  size_t length;
  void *release_context;
  fram_deallocate_fn release;
} fram_buffer;

typedef struct fram_error {
  int32_t code;
  char message[FRAM_ERROR_MESSAGE_CAPACITY];
} fram_error;

/*
 * Host callbacks return zero on success and nonzero on failure. A custom
 * storage context must already own exclusive writer authority for its entire
 * open-to-close lifetime.
 */
typedef int (*fram_clock_milliseconds_fn)(void *context,
                                          int64_t *milliseconds_out);
typedef int (*fram_storage_size_fn)(void *context, uint64_t *size_out);
typedef int (*fram_storage_read_fn)(void *context, uint64_t offset,
                                    uint8_t *destination, size_t length);
typedef int (*fram_storage_truncate_fn)(void *context, uint64_t length);
typedef int (*fram_storage_append_fn)(void *context, const uint8_t *bytes,
                                      size_t length);
typedef int (*fram_storage_sync_fn)(void *context);
typedef int (*fram_storage_close_fn)(void *context);

typedef struct fram_host_v1 {
  uint32_t abi_version;
  uint32_t struct_size;
  void *allocation_context;
  void *clock_context;
  void *storage_context;
  fram_allocate_fn allocate;
  fram_deallocate_fn deallocate;
  fram_clock_milliseconds_fn clock_milliseconds;
  fram_storage_size_fn storage_size;
  fram_storage_read_fn storage_read;
  fram_storage_truncate_fn storage_truncate;
  fram_storage_append_fn storage_append;
  fram_storage_sync_fn storage_sync;
  fram_storage_close_fn storage_close;
} fram_host_v1;

typedef struct fram_open_options_v1 {
  uint32_t abi_version;
  uint32_t struct_size;
  const char *space_id;
  const char *log_path;
  const fram_host_v1 *host;
} fram_open_options_v1;

FRAM_API uint32_t fram_abi_version(void);

/*
 * With HOST == NULL, LOG_PATH is opened as the canonical local FRAMLOG and
 * Fram supplies libc allocation, realtime clock, and POSIX durability. With a
 * host, every callback is required; LOG_PATH is then only a stable diagnostic
 * label. A successful open transfers storage-close responsibility to Fram.
 * Allocation context must remain valid until every returned buffer is freed.
 */
FRAM_API fram_status fram_open(const fram_open_options_v1 *options,
                               fram_database **database_out,
                               fram_error *error);

/*
 * Each call consumes exactly one canonical FRAMRPC v1 request frame and
 * returns exactly one canonical FRAMRPC v1 response frame. The three entry
 * points name host intent; the typed Fram dispatcher remains the sole
 * authority for operation validity and returns protocol errors in RESPONSE.
 */
FRAM_API fram_status fram_transact(fram_database *database,
                                   fram_slice request,
                                   fram_buffer *response,
                                   fram_error *error);
FRAM_API fram_status fram_query(fram_database *database, fram_slice request,
                                fram_buffer *response, fram_error *error);
FRAM_API fram_status fram_snapshot(fram_database *database,
                                   fram_slice request,
                                   fram_buffer *response,
                                   fram_error *error);

/* BUFFER remains owned until this function; it may outlive DATABASE. */
FRAM_API void fram_buffer_release(fram_buffer *buffer);

/* CLOSE always consumes DATABASE, including when durability close fails. */
FRAM_API fram_status fram_close(fram_database *database, fram_error *error);

#ifdef __cplusplus
}
#endif

#endif
