// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef FRAM_SERVE_FLAT_HOST_H
#define FRAM_SERVE_FLAT_HOST_H

#include <stddef.h>
#include <stdint.h>

#define FRAM_SERVE_FLAT_GENERATED_ABI 1u
#define FRAM_SERVE_FLAT_ERROR_CAPACITY 512u

typedef struct fram_serve_flat_store fram_serve_flat_store;
typedef struct fram_serve_flat_request fram_serve_flat_request;
typedef struct fram_serve_flat_response fram_serve_flat_response;

enum fram_serve_flat_status {
  FRAM_SERVE_FLAT_OK = 0,
  FRAM_SERVE_FLAT_PEER_CLOSED = 1,
  FRAM_SERVE_FLAT_FATAL = 2,
  FRAM_SERVE_FLAT_CLIENT_ERROR = 3
};

/* Every declaration below is a required generated-module export. */
uint32_t fram_serve_flat_generated_abi(void);

/* SPACE_ID is NULL when the deployed flat-log service did not configure one. */
int fram_serve_flat_store_boot(const char *canonical_log_path,
                               const char *space_id,
                               fram_serve_flat_store **store_out,
                               char *error,
                               size_t error_capacity);

int fram_serve_flat_store_dispatch(fram_serve_flat_store *store,
                                   const fram_serve_flat_request *request,
                                   fram_serve_flat_response **response_out,
                                   char *error,
                                   size_t error_capacity);

int fram_serve_flat_store_shutdown(fram_serve_flat_store *store,
                                   char *error,
                                   size_t error_capacity);

int fram_serve_flat_codec_read_request(int client_fd,
                                       fram_serve_flat_request **request_out,
                                       char *error,
                                       size_t error_capacity);

int fram_serve_flat_codec_write_response(
    int client_fd,
    const fram_serve_flat_response *response,
    char *error,
    size_t error_capacity);

void fram_serve_flat_codec_release_request(fram_serve_flat_request *request);
void fram_serve_flat_codec_release_response(fram_serve_flat_response *response);

#endif
