// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef FRAM_SERVER_HOST_H
#define FRAM_SERVER_HOST_H

#include <stddef.h>
#include <stdint.h>

#define FRAM_SERVER_GENERATED_ABI 2u
#define FRAM_SERVER_ERROR_CAPACITY 512u

typedef struct fram_server_store fram_server_store;
typedef struct fram_server_request fram_server_request;
typedef struct fram_server_response fram_server_response;

enum fram_server_status {
  FRAM_SERVER_OK = 0,
  FRAM_SERVER_PEER_CLOSED = 1,
  FRAM_SERVER_FATAL = 2,
  FRAM_SERVER_CLIENT_ERROR = 3
};

/* Every declaration below is a required generated-module export. */
uint32_t fram_server_generated_abi(void);

/* SPACE_ID is NULL when the deployed flat-log service did not configure one. */
int fram_server_store_boot(const char *canonical_log_path,
                               const char *space_id,
                               fram_server_store **store_out,
                               char *error,
                               size_t error_capacity);

int fram_server_store_dispatch(fram_server_store *store,
                                   const fram_server_request *request,
                                   fram_server_response **response_out,
                                   char *error,
                                   size_t error_capacity);

int fram_server_store_shutdown(fram_server_store *store,
                                   char *error,
                                   size_t error_capacity);

int fram_server_codec_read_request(int client_fd,
                                       fram_server_request **request_out,
                                       char *error,
                                       size_t error_capacity);

int fram_server_codec_write_response(
    int client_fd,
    const fram_server_response *response,
    char *error,
    size_t error_capacity);

void fram_server_codec_release_request(fram_server_request *request);
void fram_server_codec_release_response(fram_server_response *response);

#endif
