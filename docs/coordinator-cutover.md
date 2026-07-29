# Coordinator blue/green cutover contract

Protocol: `fram-coordinator-cutover/v1`

This protocol transfers writer authority between two already-running
coordinator generations. The public selector owns connection holding and route
switching. Fram owns mutation admission, drain, durable-prefix proof, and the
per-log kernel writer lock.

## Private endpoints

Run each candidate on a private loopback TCP port:

```text
FRAM_COORD_ROLE=active|standby
FRAM_CUTOVER_TOKEN=<shared secret>
FRAM_REQUIRE_LOG_FENCE=1
```

The current inherited-listener implementation supports INET sockets. A system
selector may proxy them through a Unix-facing frontend, but must give Fram
private loopback TCP endpoints until the daemon listener itself gains Unix
`SocketChannel` support.

Every control request is log-fenced:

```clojure
{:op :for-log
 :expected-log "/canonical/path/to/log"
 :request <control request>}
```

Read the token from an owner-only file. Never put it on the command line.

## Steady-state promotion

The selector must apply this order independently to every public log endpoint,
then switch all public routes as one transaction:

1. `HOLD`: stop admitting new public requests and drain selector-owned
   connections.
2. Read both boot identities:

   ```clojure
   {:op :cutover-status :token T}
   ```

3. Demote each active generation:

   ```clojure
   {:op :cutover-demote
    :token T
    :cutover-id ID
    :expected-instance ACTIVE-BOOT-ID}
   ```

   Success is usable only when the response has all of:

   - `:phase :demoted`
   - `:drain {:queries 0 :reloads 0 :snapshots 0 :complete true ...}`
   - a `fram-coordinator-cutover-marker/v1` `:marker`
   - `[:writer-authority :write-authorized] false`

   The marker is the final durable logical version plus physical byte,
   file-identity, and boundary-hash proof. Retrying the same demotion ID is
   idempotent and returns the same marker.

4. Promote each warm standby:

   ```clojure
   {:op :cutover-promote
    :token T
    :cutover-id ID
    :marker DEMOTION-MARKER}
   ```

   Fram acquires the kernel writer lock, reloads the final tail, and compares
   the observed marker before reopening mutation admission. Success requires
   `:phase :active`, the marker version, and
   `[:writer-authority :write-authorized] true`.

5. Verify status on every newly active private endpoint.
6. Atomically `SWAP` all public routes, then `RESUME` held requests.

If any demotion or promotion fails, keep public traffic held. Never route to a
generation that lacks writer authority.

## Rollback

Rollback uses the same protocol, not a force flag:

1. Keep public traffic held.
2. Demote the newly promoted generation with a fresh cutover ID.
3. Promote the retired predecessor using that new marker.
4. Verify authority and version.
5. Atomically restore all public routes and resume.

A marker mismatch releases the attempted successor's writer lock and leaves it
read-only, so the predecessor remains eligible for exact rollback.

## One-time legacy bootstrap

A live coordinator predating this protocol owns neither the kernel writer lock
nor demotion verbs. There is deliberately no unauditable force-promotion
request.

The one-time safe migration is one bounded bounce:

1. Hold all public selectors.
2. Prove every legacy direct unit is stopped and its listener/process is gone.
3. Start the new generation as `FRAM_COORD_ROLE=active`; it acquires the writer
   lock and folds the quiescent final log before listening.
4. Verify instance, version, and writer authority for every log endpoint.
5. Atomically switch all public routes and resume.
6. Start the next revision's standby so all later cutovers use the steady-state
   marker protocol.

Never run an `active` protocol generation concurrently with a legacy writer
that does not participate in the lock.

## Operator command

`fram-cutover` emits one EDN response on stdout and uses these exit codes:

- `0`: acknowledged success
- `2`: usage or local input error
- `3`: daemon rejection
- `4`: transport or protocol error

```bash
fram-cutover status \
  --port 17977 --log /path/to/coordination.log \
  --token-file /run/credentials/fram-cutover.token

fram-cutover demote \
  --port 17977 --log /path/to/coordination.log \
  --token-file /run/credentials/fram-cutover.token \
  --cutover-id deploy-20260729-a \
  --expected-instance boot-... \
  --marker-out /run/fram/cutover/coordination.marker.edn

fram-cutover promote \
  --port 27977 --log /path/to/coordination.log \
  --token-file /run/credentials/fram-cutover.token \
  --cutover-id deploy-20260729-a \
  --marker-file /run/fram/cutover/coordination.marker.edn
```

Marker output is written atomically with mode `0600`. If local marker
publication fails after a successful demotion, retry the same demotion ID to
recover the identical marker.
