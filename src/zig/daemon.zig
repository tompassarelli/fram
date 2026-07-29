const builtin = @import("builtin");
const std = @import("std");
const flat_log = @import("log.zig");

const Allocator = std.mem.Allocator;
const Dir = std.Io.Dir;
const File = std.Io.File;
const Io = std.Io;
const Writer = std.Io.Writer;

const max_request_bytes = 64 * 1024;
const authority_format = "fram-coordinator-writer-authority/v1";

var shutdown_requested = std.atomic.Value(bool).init(false);

const Operation = enum {
    for_log,
    version,
    status,
    assert_fact,
    assert_existing,
    assert_batch,
    assert_at_version,
    assert_with_fence,
    assert_at_version_with_fence,
    retract_fact,
    retract_existing,
    retract_with_fence,
    acquire_lease,
    renew_lease,
    release_lease,
    fence_ok,
    unknown,
};

const EventOperation = enum {
    assert_fact,
    retract_fact,

    fn wireName(operation: EventOperation) []const u8 {
        return switch (operation) {
            .assert_fact => "assert",
            .retract_fact => "retract",
        };
    }
};

const StringField = union(enum) {
    missing,
    invalid,
    value: []u8,

    fn deinit(field: *StringField, allocator: Allocator) void {
        switch (field.*) {
            .value => |value| allocator.free(value),
            else => {},
        }
        field.* = .missing;
    }
};

const IntField = union(enum) {
    missing,
    nil,
    invalid,
    value: i64,
};

const field_op: u16 = 1 << 0;
const field_expected_log: u16 = 1 << 1;
const field_request: u16 = 1 << 2;
const field_te: u16 = 1 << 3;
const field_p: u16 = 1 << 4;
const field_r: u16 = 1 << 5;
const field_base: u16 = 1 << 6;
const field_facts: u16 = 1 << 7;
const field_res: u16 = 1 << 8;
const field_holder: u16 = 1 << 9;
const field_epoch: u16 = 1 << 10;
const field_ttl_ms: u16 = 1 << 11;

const MapFields = struct {
    op: Operation = .unknown,
    expected_log: StringField = .missing,
    request: ?[]const u8 = null,
    te: StringField = .missing,
    p: StringField = .missing,
    r: StringField = .missing,
    base: IntField = .missing,
    facts: ?[]const u8 = null,
    res: StringField = .missing,
    holder: StringField = .missing,
    epoch: IntField = .missing,
    ttl_ms: IntField = .missing,
    present: u16 = 0,
    duplicate: bool = false,
    unknown_field: bool = false,

    fn deinit(fields: *MapFields, allocator: Allocator) void {
        fields.expected_log.deinit(allocator);
        fields.te.deinit(allocator);
        fields.p.deinit(allocator);
        fields.r.deinit(allocator);
        fields.res.deinit(allocator);
        fields.holder.deinit(allocator);
    }

    fn noteField(fields: *MapFields, field: u16) void {
        if ((fields.present & field) != 0) fields.duplicate = true;
        fields.present |= field;
    }
};

const Parser = struct {
    input: []const u8,
    index: usize = 0,

    fn skipSeparators(parser: *Parser) void {
        while (parser.index < parser.input.len) : (parser.index += 1) {
            switch (parser.input[parser.index]) {
                ' ', '\t', '\r', '\n', ',' => {},
                else => return,
            }
        }
    }

    fn scanValue(parser: *Parser) error{InvalidEdn}![]const u8 {
        parser.skipSeparators();
        if (parser.index >= parser.input.len) return error.InvalidEdn;
        const start = parser.index;

        switch (parser.input[parser.index]) {
            '"' => {
                parser.index += 1;
                var escaped = false;
                while (parser.index < parser.input.len) : (parser.index += 1) {
                    const byte = parser.input[parser.index];
                    if (escaped) {
                        escaped = false;
                        continue;
                    }
                    if (byte == '\\') {
                        escaped = true;
                        continue;
                    }
                    if (byte == '"') {
                        parser.index += 1;
                        return parser.input[start..parser.index];
                    }
                }
                return error.InvalidEdn;
            },
            '{', '[', '(' => {
                var closes: [64]u8 = undefined;
                var depth: usize = 1;
                closes[0] = closingDelimiter(parser.input[parser.index]).?;
                parser.index += 1;
                var in_string = false;
                var escaped = false;

                while (parser.index < parser.input.len) : (parser.index += 1) {
                    const byte = parser.input[parser.index];
                    if (in_string) {
                        if (escaped) {
                            escaped = false;
                        } else if (byte == '\\') {
                            escaped = true;
                        } else if (byte == '"') {
                            in_string = false;
                        }
                        continue;
                    }
                    if (byte == '"') {
                        in_string = true;
                        continue;
                    }
                    if (closingDelimiter(byte)) |close| {
                        if (depth == closes.len) return error.InvalidEdn;
                        closes[depth] = close;
                        depth += 1;
                        continue;
                    }
                    if (byte == '}' or byte == ']' or byte == ')') {
                        if (depth == 0 or closes[depth - 1] != byte)
                            return error.InvalidEdn;
                        depth -= 1;
                        if (depth == 0) {
                            parser.index += 1;
                            return parser.input[start..parser.index];
                        }
                    }
                }
                return error.InvalidEdn;
            },
            '}', ']', ')' => return error.InvalidEdn,
            else => {
                while (parser.index < parser.input.len and
                    !isValueDelimiter(parser.input[parser.index]))
                {
                    parser.index += 1;
                }
                if (parser.index == start) return error.InvalidEdn;
                return parser.input[start..parser.index];
            },
        }
    }
};

const StoredEvent = struct {
    tx: i64,
    operation: EventOperation,
    l: []const u8,
    p: []const u8,
    r: []const u8,
};

const LatestDeclaration = struct {
    tx: i64,
    operation: EventOperation,
    single: bool,
};

const DaemonState = struct {
    allocator: Allocator,
    arena: std.heap.ArenaAllocator,
    events: std.ArrayList(StoredEvent),
    cardinality: std.StringHashMap(bool),
    configured_single: std.StringHashMap(void),
    latest: std.StringHashMap(usize),
    subjects: std.StringHashMap(void),
    version: i64,

    fn init(allocator: Allocator, environ: *const std.process.Environ.Map) !DaemonState {
        var state: DaemonState = .{
            .allocator = allocator,
            .arena = std.heap.ArenaAllocator.init(allocator),
            .events = .empty,
            .cardinality = std.StringHashMap(bool).init(allocator),
            .configured_single = std.StringHashMap(void).init(allocator),
            .latest = std.StringHashMap(usize).init(allocator),
            .subjects = std.StringHashMap(void).init(allocator),
            .version = 0,
        };
        errdefer state.deinit();
        if (environ.get("FRAM_SINGLE_VALUED")) |configured| {
            if (std.mem.trim(u8, configured, " \t\r\n").len != 0) {
                var tokens = std.mem.tokenizeAny(u8, configured, " \t\r\n");
                while (tokens.next()) |predicate| {
                    try state.configured_single.put(predicate, {});
                }
                return state;
            }
        }
        for (fallback_single_predicates) |predicate| {
            try state.configured_single.put(predicate, {});
        }
        return state;
    }

    fn deinit(state: *DaemonState) void {
        state.subjects.deinit();
        state.latest.deinit();
        state.configured_single.deinit();
        state.cardinality.deinit();
        state.events.deinit(state.allocator);
        state.arena.deinit();
        state.* = undefined;
    }

    fn copyEvent(
        state: *DaemonState,
        tx: i64,
        operation: EventOperation,
        l: []const u8,
        p: []const u8,
        r: []const u8,
    ) !StoredEvent {
        const arena = state.arena.allocator();
        return .{
            .tx = tx,
            .operation = operation,
            .l = try arena.dupe(u8, l),
            .p = try arena.dupe(u8, p),
            .r = try arena.dupe(u8, r),
        };
    }

    fn rebuildDerived(state: *DaemonState) !void {
        state.cardinality.clearRetainingCapacity();
        state.latest.clearRetainingCapacity();
        state.subjects.clearRetainingCapacity();

        for (meta_single_predicates) |predicate| {
            try state.cardinality.put(predicate, true);
        }

        var declarations = std.StringHashMap(LatestDeclaration).init(
            state.allocator,
        );
        defer declarations.deinit();
        for (state.events.items) |event| {
            if (!std.mem.eql(u8, event.p, "cardinality")) continue;
            const predicate = stripAt(event.l);
            const declaration: LatestDeclaration = .{
                .tx = event.tx,
                .operation = event.operation,
                .single = std.mem.eql(u8, event.r, "single"),
            };
            if (declarations.get(predicate)) |previous| {
                if (previous.tx > event.tx) continue;
            }
            try declarations.put(predicate, declaration);
        }
        var declaration_iterator = declarations.iterator();
        while (declaration_iterator.next()) |entry| {
            const declaration = entry.value_ptr.*;
            if (declaration.operation == .assert_fact) {
                try state.cardinality.put(
                    entry.key_ptr.*,
                    declaration.single,
                );
            } else {
                _ = state.cardinality.remove(entry.key_ptr.*);
                if (isMetaSingle(entry.key_ptr.*)) {
                    try state.cardinality.put(entry.key_ptr.*, true);
                }
            }
        }

        for (state.events.items, 0..) |event, index| {
            try state.applyEvent(index, event);
        }
    }

    fn applyEvent(
        state: *DaemonState,
        index: usize,
        event: StoredEvent,
    ) !void {
        try state.subjects.put(event.l, {});
        if (!std.mem.eql(u8, event.p, "v") and refShape(event.r)) {
            try state.subjects.put(event.r, {});
        }
        const key = try state.eventKey(event);
        if (state.latest.get(key)) |previous_index| {
            if (state.events.items[previous_index].tx > event.tx) return;
        }
        try state.latest.put(key, index);
    }

    fn appendCommitted(
        state: *DaemonState,
        event: StoredEvent,
    ) !void {
        try state.events.append(state.allocator, event);
        state.version = @max(state.version, event.tx);
        if (std.mem.eql(u8, event.p, "cardinality")) {
            try state.rebuildDerived();
        } else {
            try state.applyEvent(state.events.items.len - 1, event);
        }
    }

    fn appendCommittedBatch(
        state: *DaemonState,
        events: []const StoredEvent,
    ) !void {
        try state.events.ensureUnusedCapacity(state.allocator, events.len);
        var changes_cardinality = false;
        const start = state.events.items.len;
        for (events) |event| {
            state.events.appendAssumeCapacity(event);
            state.version = @max(state.version, event.tx);
            changes_cardinality = changes_cardinality or
                std.mem.eql(u8, event.p, "cardinality");
        }
        if (changes_cardinality) {
            try state.rebuildDerived();
            return;
        }
        for (events, start..) |event, index| {
            try state.applyEvent(index, event);
        }
    }

    fn isSingle(state: *const DaemonState, predicate: []const u8) bool {
        if (state.cardinality.get(predicate)) |single| return single;
        return state.configured_single.contains(predicate) or
            std.mem.startsWith(u8, predicate, "emoji_");
    }

    fn eventKey(state: *DaemonState, event: StoredEvent) ![]const u8 {
        return if (state.isSingle(event.p))
            groupKeyAlloc(state.arena.allocator(), event.l, event.p)
        else
            tripleKeyAlloc(state.arena.allocator(), event.l, event.p, event.r);
    }

    fn liveEvent(
        state: *DaemonState,
        scratch: Allocator,
        l: []const u8,
        p: []const u8,
        r: []const u8,
    ) !?StoredEvent {
        const key = if (state.isSingle(p))
            try groupKeyAlloc(scratch, l, p)
        else
            try tripleKeyAlloc(scratch, l, p, r);
        defer scratch.free(key);
        const index = state.latest.get(key) orelse return null;
        const event = state.events.items[index];
        if (event.operation != .assert_fact) return null;
        if (state.isSingle(p) and !std.mem.eql(u8, event.r, r)) return null;
        return event;
    }

    fn liveGroup(
        state: *DaemonState,
        scratch: Allocator,
        l: []const u8,
        p: []const u8,
    ) !?StoredEvent {
        if (state.isSingle(p)) {
            const key = try groupKeyAlloc(scratch, l, p);
            defer scratch.free(key);
            const index = state.latest.get(key) orelse return null;
            const event = state.events.items[index];
            return if (event.operation == .assert_fact) event else null;
        }
        var latest = state.latest.iterator();
        while (latest.next()) |entry| {
            const event = state.events.items[entry.value_ptr.*];
            if (event.operation == .assert_fact and
                std.mem.eql(u8, event.l, l) and
                std.mem.eql(u8, event.p, p))
            {
                return event;
            }
        }
        return null;
    }

    fn baseVersion(
        state: *DaemonState,
        scratch: Allocator,
        l: []const u8,
        p: []const u8,
    ) !i64 {
        const live = try state.liveGroup(scratch, l, p);
        return if (live) |event| event.tx else 0;
    }

    fn allLivePredicateValuesRef(
        state: *DaemonState,
        predicate: []const u8,
    ) bool {
        var latest = state.latest.iterator();
        var seen = false;
        while (latest.next()) |entry| {
            const event = state.events.items[entry.value_ptr.*];
            if (event.operation != .assert_fact or
                !std.mem.eql(u8, event.p, predicate))
            {
                continue;
            }
            if (!refShape(event.r)) return false;
            seen = true;
        }
        return seen;
    }

    fn currentLease(
        state: *DaemonState,
        scratch: Allocator,
        resource: []const u8,
    ) !?Lease {
        const subject = try std.fmt.allocPrint(scratch, "@lease:{s}", .{resource});
        defer scratch.free(subject);
        const event = try state.liveGroup(scratch, subject, "lease") orelse return null;
        return parseLease(event.r);
    }
};

const fallback_single_predicates = [_][]const u8{
    "title",          "owner",         "lead",        "driver",
    "source",         "part_of",       "do_on",       "valid_until",
    "estimate_hours", "created_at",    "updated_at",  "name",
    "body",           "created_by",    "committed",   "outcome",
    "abandoned",      "superseded_by", "merged_into", "session_of",
    "start_time",     "end_time",      "clockify_id",
};

const meta_single_predicates = [_][]const u8{
    "cardinality", "value_kind", "name", "acyclic", "lease",
};

const Lease = struct {
    holder: []const u8,
    exp: i64,
    epoch: i64,
};

const WriterAuthority = struct {
    file: File,
    path: []u8,

    fn release(authority: *WriterAuthority, io: Io, allocator: Allocator) void {
        authority.file.close(io);
        allocator.free(authority.path);
        authority.* = undefined;
    }
};

pub fn main(init: std.process.Init) void {
    run(init) catch |err| {
        switch (err) {
            error.InvalidArguments => {
                std.debug.print(
                    "usage: fram-daemon-zig serve-flat PORT LOG\n",
                    .{},
                );
                std.process.exit(2);
            },
            error.WriterAuthorityHeld => {
                std.debug.print(
                    "fram: another coordinator generation holds writer authority\n",
                    .{},
                );
                std.process.exit(1);
            },
            error.CorruptLog => std.process.exit(1),
            else => {
                std.debug.print(
                    "fram zig daemon: {s}\n",
                    .{@errorName(err)},
                );
                std.process.exit(1);
            },
        }
    };
}

fn run(init: std.process.Init) !void {
    if (builtin.os.tag != .linux)
        @compileError("the bootstrap daemon currently targets Fram's Linux deployment boundary");

    var args = try std.process.Args.Iterator.initAllocator(
        init.minimal.args,
        init.gpa,
    );
    defer args.deinit();
    _ = args.next();
    const mode = args.next() orelse return error.InvalidArguments;
    const port_text = args.next() orelse return error.InvalidArguments;
    const log_argument = args.next() orelse return error.InvalidArguments;
    if (args.next() != null or !std.mem.eql(u8, mode, "serve-flat"))
        return error.InvalidArguments;
    const port = std.fmt.parseInt(u16, port_text, 10) catch
        return error.InvalidArguments;

    var created = try Dir.cwd().createFile(
        init.io,
        log_argument,
        .{ .truncate = false },
    );
    created.close(init.io);

    const canonical_log = try Dir.cwd().realPathFileAlloc(
        init.io,
        log_argument,
        init.gpa,
    );
    defer init.gpa.free(canonical_log);

    var authority = try acquireWriterAuthority(
        init.gpa,
        init.io,
        canonical_log,
    );
    defer authority.release(init.io, init.gpa);

    var state = try replayState(
        init.gpa,
        init.io,
        init.environ_map,
        canonical_log,
    );
    defer state.deinit();
    const strict_fence = if (init.environ_map.get("FRAM_REQUIRE_LOG_FENCE")) |value|
        std.mem.eql(u8, value, "1")
    else
        false;

    try installSignalHandlers();
    try serve(
        init.gpa,
        init.io,
        port,
        canonical_log,
        authority.path,
        &state,
        strict_fence,
    );
}

fn replayState(
    allocator: Allocator,
    io: Io,
    environ: *const std.process.Environ.Map,
    canonical_log: []const u8,
) !DaemonState {
    var outcome = try flat_log.replayFile(
        allocator,
        io,
        Dir.cwd(),
        canonical_log,
        std.math.maxInt(usize),
    );
    switch (outcome) {
        .corrupt => |corruption| {
            std.debug.print(
                "fram: corrupt log line in {s} at byte {d}; refusing to serve\n",
                .{ canonical_log, corruption.byte_offset },
            );
            return error.CorruptLog;
        },
        .replay => |*replay| {
            defer replay.deinit();
            var state = try DaemonState.init(allocator, environ);
            errdefer state.deinit();
            for (replay.records) |record| {
                if (record.fact.tx) |tx| state.version = @max(state.version, tx);
                if (!record.fact.complete()) continue;
                const operation: EventOperation =
                    if (std.mem.eql(u8, record.fact.op.?, "assert"))
                        .assert_fact
                    else
                        .retract_fact;
                const event = try state.copyEvent(
                    record.fact.tx.?,
                    operation,
                    record.fact.l.?,
                    record.fact.p.?,
                    record.fact.r.?,
                );
                try state.events.append(allocator, event);
            }
            try state.rebuildDerived();
            if (replay.torn_tail) |tail| {
                std.debug.print(
                    "fram: WARN torn-tail: {s}: torn final log line at byte {d} — recovered {d} prior fact(s), incomplete tail dropped\n",
                    .{ canonical_log, tail.byte_offset, tail.recovered_records },
                );
            }
            return state;
        },
    }
}

fn acquireWriterAuthority(
    allocator: Allocator,
    io: Io,
    canonical_log: []const u8,
) !WriterAuthority {
    const path = try std.fmt.allocPrint(
        allocator,
        "{s}.writer-authority.lock",
        .{canonical_log},
    );
    errdefer allocator.free(path);

    var file = try Dir.cwd().createFile(io, path, .{ .truncate = false });
    errdefer file.close(io);

    var lock: std.posix.Flock = .{
        .type = @intCast(std.posix.F.WRLCK),
        .whence = @intCast(std.posix.SEEK.SET),
        .start = 0,
        .len = 0,
        .pid = 0,
        ._unused = {},
    };
    const result = std.posix.system.fcntl(
        file.handle,
        std.posix.F.SETLK,
        @intFromPtr(&lock),
    );
    switch (std.posix.errno(result)) {
        .SUCCESS => return .{ .file = file, .path = path },
        .AGAIN, .ACCES => return error.WriterAuthorityHeld,
        else => |err| {
            std.debug.print(
                "fram: writer authority lock failed: {s}\n",
                .{@tagName(err)},
            );
            return error.WriterAuthorityLockFailed;
        },
    }
}

fn installSignalHandlers() !void {
    shutdown_requested.store(false, .release);
    const action: std.posix.Sigaction = .{
        .handler = .{ .handler = signalHandler },
        .mask = std.posix.sigemptyset(),
        .flags = 0,
    };
    std.posix.sigaction(.TERM, &action, null);
    std.posix.sigaction(.INT, &action, null);
}

fn signalHandler(_: std.posix.SIG) callconv(.c) void {
    shutdown_requested.store(true, .release);
}

fn serve(
    allocator: Allocator,
    io: Io,
    port: u16,
    canonical_log: []const u8,
    authority_path: []const u8,
    state: *DaemonState,
    strict_fence: bool,
) !void {
    var address: std.Io.net.IpAddress = .{
        .ip4 = std.Io.net.Ip4Address.loopback(port),
    };
    var server = try address.listen(io, .{ .reuse_address = true });
    defer server.deinit(io);

    std.debug.print(
        "fram zig coordinator bootstrap: version {d}, canonical={s}, listening=127.0.0.1:{d}\n",
        .{ state.version, canonical_log, port },
    );

    while (!shutdown_requested.load(.acquire)) {
        var descriptors = [_]std.posix.pollfd{.{
            .fd = server.socket.handle,
            .events = std.posix.POLL.IN,
            .revents = 0,
        }};
        _ = try std.posix.poll(&descriptors, 100);
        if (shutdown_requested.load(.acquire)) break;
        if ((descriptors[0].revents & std.posix.POLL.IN) == 0) continue;

        const stream = server.accept(io) catch |err| {
            if (shutdown_requested.load(.acquire)) break;
            std.debug.print("fram: accept failed: {s}\n", .{@errorName(err)});
            continue;
        };
        handleConnection(
            allocator,
            io,
            stream,
            canonical_log,
            authority_path,
            state,
            strict_fence,
        ) catch |err| {
            std.debug.print(
                "fram: request failed: {s}\n",
                .{@errorName(err)},
            );
        };
    }

    std.debug.print("[fram] shutdown complete\n", .{});
}

fn handleConnection(
    allocator: Allocator,
    io: Io,
    stream: std.Io.net.Stream,
    canonical_log: []const u8,
    authority_path: []const u8,
    state: *DaemonState,
    strict_fence: bool,
) !void {
    defer stream.close(io);
    var read_buffer: [max_request_bytes]u8 = undefined;
    var stream_reader = stream.reader(io, &read_buffer);
    const line = (try stream_reader.interface.takeDelimiter('\n')) orelse return;

    const response = try handleRequest(
        allocator,
        io,
        line,
        canonical_log,
        authority_path,
        state,
        strict_fence,
    );
    defer allocator.free(response);

    var write_buffer: [4096]u8 = undefined;
    var stream_writer = stream.writer(io, &write_buffer);
    try stream_writer.interface.writeAll(response);
    try stream_writer.interface.writeByte('\n');
    try stream_writer.interface.flush();
}

fn handleRequest(
    allocator: Allocator,
    io: Io,
    line: []const u8,
    canonical_log: []const u8,
    authority_path: []const u8,
    state: *DaemonState,
    strict_fence: bool,
) ![]u8 {
    var outer = parseMap(allocator, line) catch
        return allocator.dupe(u8, "{:error \"invalid request\"}");
    defer outer.deinit(allocator);

    if (strict_fence and outer.op != .for_log) {
        return renderStrictFenceRequired(allocator, canonical_log);
    }

    if (outer.op != .for_log) {
        return dispatchOperation(
            allocator,
            io,
            outer.op,
            &outer,
            canonical_log,
            authority_path,
            state,
        );
    }

    if (try requestFieldError(
        allocator,
        &outer,
        field_op | field_expected_log | field_request,
        field_expected_log | field_request,
    )) |response| return response;

    const expected = switch (outer.expected_log) {
        .value => |value| value,
        else => return allocator.dupe(
            u8,
            "{:reject [\"log fence requires a non-blank :expected-log path\"] :code :invalid-log-fence}",
        ),
    };
    if (std.mem.trim(u8, expected, " \t\r\n").len == 0) {
        return allocator.dupe(
            u8,
            "{:reject [\"log fence requires a non-blank :expected-log path\"] :code :invalid-log-fence}",
        );
    }

    const canonical_expected = Dir.cwd().realPathFileAlloc(
        io,
        expected,
        allocator,
    ) catch {
        return allocator.dupe(
            u8,
            "{:reject [\"invalid expected log path\"] :code :invalid-log-fence}",
        );
    };
    defer allocator.free(canonical_expected);

    if (!std.mem.eql(u8, canonical_expected, canonical_log)) {
        return renderLogMismatch(
            allocator,
            canonical_expected,
            canonical_log,
        );
    }

    const nested_raw = outer.request orelse return allocator.dupe(
        u8,
        "{:reject [\"log fence requires a nested request map\"] :code :invalid-log-fence}",
    );
    var nested = parseMap(allocator, nested_raw) catch return allocator.dupe(
        u8,
        "{:reject [\"log fence requires a nested request map\"] :code :invalid-log-fence}",
    );
    defer nested.deinit(allocator);
    if (nested.op == .for_log) {
        return allocator.dupe(
            u8,
            "{:reject [\"nested log-fence envelopes are not supported\"] :code :invalid-log-fence}",
        );
    }
    return dispatchOperation(
        allocator,
        io,
        nested.op,
        &nested,
        canonical_log,
        authority_path,
        state,
    );
}

fn dispatchOperation(
    allocator: Allocator,
    io: Io,
    operation: Operation,
    request: *MapFields,
    canonical_log: []const u8,
    authority_path: []const u8,
    state: *DaemonState,
) ![]u8 {
    return switch (operation) {
        .version => if (try requestFieldError(
            allocator,
            request,
            field_op,
            0,
        )) |response| response else std.fmt.allocPrint(
            allocator,
            "{{:version {d}}}",
            .{state.version},
        ),
        .status => if (try requestFieldError(
            allocator,
            request,
            field_op,
            0,
        )) |response| response else renderStatus(
            allocator,
            canonical_log,
            authority_path,
            state.*,
        ),
        .assert_fact => mutateOne(
            allocator,
            io,
            canonical_log,
            state,
            request,
            .assert_fact,
            false,
            false,
            false,
        ),
        .assert_existing => mutateOne(
            allocator,
            io,
            canonical_log,
            state,
            request,
            .assert_fact,
            true,
            false,
            false,
        ),
        .assert_batch => assertBatch(
            allocator,
            io,
            canonical_log,
            state,
            request,
        ),
        .assert_at_version => mutateOne(
            allocator,
            io,
            canonical_log,
            state,
            request,
            .assert_fact,
            false,
            false,
            true,
        ),
        .assert_with_fence => mutateOne(
            allocator,
            io,
            canonical_log,
            state,
            request,
            .assert_fact,
            false,
            true,
            false,
        ),
        .assert_at_version_with_fence => mutateOne(
            allocator,
            io,
            canonical_log,
            state,
            request,
            .assert_fact,
            false,
            true,
            true,
        ),
        .retract_fact => mutateOne(
            allocator,
            io,
            canonical_log,
            state,
            request,
            .retract_fact,
            false,
            false,
            false,
        ),
        .retract_existing => mutateOne(
            allocator,
            io,
            canonical_log,
            state,
            request,
            .retract_fact,
            true,
            false,
            false,
        ),
        .retract_with_fence => mutateOne(
            allocator,
            io,
            canonical_log,
            state,
            request,
            .retract_fact,
            false,
            true,
            false,
        ),
        .acquire_lease => acquireLease(
            allocator,
            io,
            canonical_log,
            state,
            request,
        ),
        .renew_lease => renewLease(
            allocator,
            io,
            canonical_log,
            state,
            request,
        ),
        .release_lease => releaseLease(
            allocator,
            io,
            canonical_log,
            state,
            request,
        ),
        .fence_ok => fenceOk(allocator, io, state, request),
        else => if (try requestFieldError(
            allocator,
            request,
            field_op | field_expected_log | field_request | field_te |
                field_p | field_r | field_base | field_facts,
            0,
        )) |response| response else allocator.dupe(
            u8,
            "{:error \"unknown op\"}",
        ),
    };
}

const BatchFact = struct {
    p: []u8,
    r: []u8,
    base: IntField,

    fn deinit(fact: *BatchFact, allocator: Allocator) void {
        allocator.free(fact.p);
        allocator.free(fact.r);
        fact.* = undefined;
    }
};

const ParsedBatch = struct {
    facts: std.ArrayList(BatchFact),

    fn deinit(batch: *ParsedBatch, allocator: Allocator) void {
        for (batch.facts.items) |*fact| fact.deinit(allocator);
        batch.facts.deinit(allocator);
        batch.* = undefined;
    }
};

fn requestFieldError(
    allocator: Allocator,
    request: *const MapFields,
    allowed: u16,
    required: u16,
) !?[]u8 {
    if (request.duplicate or request.unknown_field or
        (request.present & ~allowed) != 0)
    {
        return try renderInvalidRequest(
            allocator,
            &.{"invalid, duplicate, or unknown request field"},
        );
    }

    const missing = (required | field_op) & ~request.present;
    if (missing == 0) return null;

    var messages: [8][]const u8 = undefined;
    var count: usize = 0;
    const candidates = [_]struct { mask: u16, message: []const u8 }{
        .{ .mask = field_op, .message = "op is required" },
        .{ .mask = field_expected_log, .message = "expected-log is required" },
        .{ .mask = field_request, .message = "request is required" },
        .{ .mask = field_te, .message = "te is required" },
        .{ .mask = field_p, .message = "p is required" },
        .{ .mask = field_r, .message = "r is required" },
        .{ .mask = field_base, .message = "base is required" },
        .{ .mask = field_facts, .message = "facts is required" },
        .{ .mask = field_res, .message = "res is required" },
        .{ .mask = field_holder, .message = "holder is required" },
        .{ .mask = field_epoch, .message = "epoch is required" },
        .{ .mask = field_ttl_ms, .message = "ttl-ms is required" },
    };
    for (candidates) |candidate| {
        if ((missing & candidate.mask) != 0) {
            messages[count] = candidate.message;
            count += 1;
        }
    }
    return try renderInvalidRequest(allocator, messages[0..count]);
}

fn renderInvalidRequest(
    allocator: Allocator,
    messages: []const []const u8,
) ![]u8 {
    var output: Writer.Allocating = .init(allocator);
    defer output.deinit();
    const writer = &output.writer;
    try writeAll(writer, "{:error [");
    for (messages, 0..) |message, index| {
        if (index != 0) try writeAll(writer, " ");
        try writeEdnString(writer, message);
    }
    try writeAll(writer, "], :code :invalid-request}");
    return output.toOwnedSlice();
}

fn nowMs(io: Io) i64 {
    return @intCast(@divFloor(Io.Clock.real.now(io).nanoseconds, std.time.ns_per_ms));
}

fn validLeaseText(value: []const u8) bool {
    return std.mem.trim(u8, value, " \t\r\n").len != 0;
}

fn parseLease(value: []const u8) ?Lease {
    var parts = std.mem.splitScalar(u8, value, '|');
    const holder = parts.next() orelse return null;
    const exp_text = parts.next() orelse return null;
    const epoch_text = parts.next() orelse return null;
    if (parts.next() != null) return null;
    const exp = std.fmt.parseInt(i64, exp_text, 10) catch return null;
    const epoch = std.fmt.parseInt(i64, epoch_text, 10) catch return null;
    return .{ .holder = holder, .exp = exp, .epoch = epoch };
}

fn leaseFields(request: *const MapFields) ?struct { res: []const u8, holder: []const u8 } {
    const res = switch (request.res) { .value => |value| value, else => return null };
    const holder = switch (request.holder) { .value => |value| value, else => return null };
    if (!validLeaseText(res) or !validLeaseText(holder) or std.mem.indexOfScalar(u8, holder, '|') != null)
        return null;
    return .{ .res = res, .holder = holder };
}

fn validTtl(ttl: i64, now: i64) bool {
    return ttl > 0 and now >= 0 and ttl <= std.math.maxInt(i64) - now;
}

fn requestHasCurrentFence(
    allocator: Allocator,
    io: Io,
    state: *DaemonState,
    request: *const MapFields,
) !bool {
    const fields = leaseFields(request) orelse return false;
    const epoch = switch (request.epoch) { .value => |value| value, else => return false };
    if (epoch <= 0) return false;
    const lease = try state.currentLease(allocator, fields.res) orelse return false;
    return lease.exp > nowMs(io) and lease.epoch == epoch and
        std.mem.eql(u8, lease.holder, fields.holder);
}

fn appendLeaseEvent(
    allocator: Allocator,
    io: Io,
    canonical_log: []const u8,
    state: *DaemonState,
    operation: EventOperation,
    resource: []const u8,
    value: []const u8,
) !i64 {
    if (state.version == std.math.maxInt(i64)) return error.VersionExhausted;
    const tx = state.version + 1;
    const subject = try std.fmt.allocPrint(allocator, "@lease:{s}", .{resource});
    defer allocator.free(subject);
    const timestamp = try timestampUtc(allocator, io);
    defer allocator.free(timestamp);
    const payload = try flat_log.encodeLine(allocator, .{
        .tx = tx, .op = operation.wireName(), .l = subject, .p = "lease", .r = value,
    }, .{ .coordinator = .{ .ts = timestamp, .by = "coord" } });
    defer allocator.free(payload);
    const stored = try state.copyEvent(tx, operation, subject, "lease", value);
    try flat_log.appendDurable(io, Dir.cwd(), canonical_log, payload);
    try state.appendCommitted(stored);
    return tx;
}

fn renderLease(allocator: Allocator, epoch: i64, holder: []const u8, exp: i64) ![]u8 {
    var output: Writer.Allocating = .init(allocator);
    defer output.deinit();
    try output.writer.print("{{:ok {d}, :holder ", .{epoch});
    try writeEdnString(&output.writer, holder);
    try output.writer.print(", :exp {d}, :epoch {d}}}", .{ exp, epoch });
    return output.toOwnedSlice();
}

fn renderLeaseReject(allocator: Allocator, reject: []const u8, version: i64) ![]u8 {
    return std.fmt.allocPrint(allocator, "{{:reject :{s}, :version {d}}}", .{ reject, version });
}

fn renderHeld(allocator: Allocator, lease: Lease, version: i64) ![]u8 {
    var output: Writer.Allocating = .init(allocator);
    defer output.deinit();
    try writeAll(&output.writer, "{:reject :held, :holder ");
    try writeEdnString(&output.writer, lease.holder);
    try output.writer.print(" :exp {d}, :version {d}}}", .{ lease.exp, version });
    return output.toOwnedSlice();
}

fn renderFenceLost(allocator: Allocator, version: i64) ![]u8 {
    return renderLeaseReject(allocator, "fence-lost", version);
}

fn acquireLease(allocator: Allocator, io: Io, canonical_log: []const u8, state: *DaemonState, request: *MapFields) ![]u8 {
    if (try requestFieldError(allocator, request, field_op | field_res | field_holder | field_ttl_ms, field_res | field_holder | field_ttl_ms)) |response| return response;
    const fields = leaseFields(request) orelse return renderLeaseReject(allocator, "invalid-lease-request", state.version);
    const ttl = switch (request.ttl_ms) { .value => |value| value, else => return renderLeaseReject(allocator, "invalid-lease-request", state.version) };
    const now = nowMs(io);
    if (!validTtl(ttl, now)) return renderLeaseReject(allocator, "invalid-lease-request", state.version);
    if (try state.currentLease(allocator, fields.res)) |lease| {
        if (lease.exp > now and !std.mem.eql(u8, lease.holder, fields.holder)) return renderHeld(allocator, lease, state.version);
    }
    const value = try std.fmt.allocPrint(allocator, "{s}|{d}|{d}", .{ fields.holder, now + ttl, state.version + 1 });
    defer allocator.free(value);
    const epoch = try appendLeaseEvent(allocator, io, canonical_log, state, .assert_fact, fields.res, value);
    const lease = try state.currentLease(allocator, fields.res) orelse unreachable;
    return renderLease(allocator, epoch, lease.holder, lease.exp);
}

fn renewLease(allocator: Allocator, io: Io, canonical_log: []const u8, state: *DaemonState, request: *MapFields) ![]u8 {
    if (try requestFieldError(allocator, request, field_op | field_res | field_holder | field_epoch | field_ttl_ms, field_res | field_holder | field_epoch | field_ttl_ms)) |response| return response;
    const fields = leaseFields(request) orelse return renderLeaseReject(allocator, "invalid-lease-request", state.version);
    const epoch = switch (request.epoch) { .value => |value| value, else => return renderLeaseReject(allocator, "invalid-lease-request", state.version) };
    const ttl = switch (request.ttl_ms) { .value => |value| value, else => return renderLeaseReject(allocator, "invalid-lease-request", state.version) };
    const now = nowMs(io);
    if (epoch <= 0 or !validTtl(ttl, now)) return renderLeaseReject(allocator, "invalid-lease-request", state.version);
    const current = try state.currentLease(allocator, fields.res) orelse return renderFenceLost(allocator, state.version);
    if (current.exp <= now or current.epoch != epoch or !std.mem.eql(u8, current.holder, fields.holder)) return renderFenceLost(allocator, state.version);
    const next_epoch = state.version + 1;
    const value = try std.fmt.allocPrint(allocator, "{s}|{d}|{d}", .{ fields.holder, now + ttl, next_epoch });
    defer allocator.free(value);
    _ = try appendLeaseEvent(allocator, io, canonical_log, state, .assert_fact, fields.res, value);
    return renderLease(allocator, next_epoch, fields.holder, now + ttl);
}

fn releaseLease(allocator: Allocator, io: Io, canonical_log: []const u8, state: *DaemonState, request: *MapFields) ![]u8 {
    if (try requestFieldError(allocator, request, field_op | field_res | field_holder | field_epoch, field_res | field_holder)) |response| return response;
    const fields = leaseFields(request) orelse return renderOk(allocator, state.version);
    const current = try state.currentLease(allocator, fields.res) orelse return std.fmt.allocPrint(allocator, "{{:ok {d}, :noop true}}", .{state.version});
    const epoch_matches = switch (request.epoch) { .missing => true, .value => |value| value == current.epoch, else => false };
    if (!std.mem.eql(u8, current.holder, fields.holder) or !epoch_matches) return std.fmt.allocPrint(allocator, "{{:ok {d}, :noop true}}", .{state.version});
    const value = try std.fmt.allocPrint(allocator, "{s}|{d}|{d}", .{ current.holder, current.exp, current.epoch });
    defer allocator.free(value);
    const version = try appendLeaseEvent(allocator, io, canonical_log, state, .retract_fact, fields.res, value);
    return renderOk(allocator, version);
}

fn fenceOk(allocator: Allocator, io: Io, state: *DaemonState, request: *MapFields) ![]u8 {
    if (try requestFieldError(allocator, request, field_op | field_res | field_holder | field_epoch, field_res | field_holder | field_epoch)) |response| return response;
    return std.fmt.allocPrint(allocator, "{{:fence-ok {}}}", .{try requestHasCurrentFence(allocator, io, state, request)});
}

fn mutateOne(
    allocator: Allocator,
    io: Io,
    canonical_log: []const u8,
    state: *DaemonState,
    request: *MapFields,
    operation: EventOperation,
    existing_only: bool,
    fenced: bool,
    global_version: bool,
) ![]u8 {
    if (try requestFieldError(
        allocator,
        request,
        field_op | field_te | field_p | field_r | field_base |
            field_res | field_holder | field_epoch,
        field_te | field_p | field_r,
    )) |response| return response;

    if (fenced) {
        if (!try requestHasCurrentFence(allocator, io, state, request))
            return renderFenceLost(allocator, state.version);
    }

    const te = switch (request.te) {
        .value => |value| value,
        else => return renderInvalidRequest(
            allocator,
            &.{"te must be a string"},
        ),
    };
    const predicate = switch (request.p) {
        .value => |value| value,
        else => return renderInvalidRequest(
            allocator,
            &.{"p must be a string"},
        ),
    };
    const requested_value = switch (request.r) {
        .value => |value| value,
        else => return renderInvalidRequest(
            allocator,
            &.{"r must be a string"},
        ),
    };
    if (te.len == 0 or predicate.len == 0) {
        return renderInvalidRequest(
            allocator,
            &.{"te and p must be non-blank strings"},
        );
    }

    const base: ?i64 = switch (request.base) {
        .missing, .nil => null,
        .value => |value| value,
        .invalid => return renderInvalidRequest(
            allocator,
            &.{"base must be an integer or nil"},
        ),
    };

    if (existing_only and !state.subjects.contains(te)) {
        return renderMissingSubject(allocator, te, state.version);
    }

    var normalized_owned: ?[]u8 = null;
    defer if (normalized_owned) |value| allocator.free(value);
    const value = if (existing_only and
        requested_value.len != 0 and
        requested_value[0] != '@' and
        !containsWhitespace(requested_value) and
        state.allLivePredicateValuesRef(predicate))
    normalize: {
        normalized_owned = try std.fmt.allocPrint(
            allocator,
            "@{s}",
            .{requested_value},
        );
        break :normalize normalized_owned.?;
    } else requested_value;

    if (global_version) {
        const expected = base orelse return renderInvalidBase(
            allocator,
            state.version,
        );
        if (expected < 0) return renderInvalidBase(allocator, state.version);
        if (expected != state.version) {
            return renderConflict(allocator, state.version);
        }
    } else if (state.isSingle(predicate) and base != null and
        try state.baseVersion(allocator, te, predicate) > base.?)
    {
        return renderConflict(allocator, state.version);
    }

    const live = if (operation == .assert_fact)
        try state.liveEvent(allocator, te, predicate, value)
    else if (state.isSingle(predicate))
        try state.liveGroup(allocator, te, predicate)
    else
        try state.liveEvent(allocator, te, predicate, value);

    if (operation == .assert_fact and
        !state.isSingle(predicate) and live != null)
    {
        return renderOk(allocator, state.version);
    }
    if (operation == .retract_fact and live == null) {
        return renderOk(allocator, state.version);
    }
    if (state.version == std.math.maxInt(i64)) {
        return allocator.dupe(
            u8,
            "{:reject :version-exhausted, :code :version-exhausted}",
        );
    }

    const tx = state.version + 1;
    const timestamp = try timestampUtc(allocator, io);
    defer allocator.free(timestamp);
    const payload = try flat_log.encodeLine(
        allocator,
        .{
            .tx = tx,
            .op = operation.wireName(),
            .l = te,
            .p = predicate,
            .r = value,
        },
        .{ .coordinator = .{ .ts = timestamp, .by = "coord" } },
    );
    defer allocator.free(payload);
    const stored = try state.copyEvent(tx, operation, te, predicate, value);

    try flat_log.appendDurable(io, Dir.cwd(), canonical_log, payload);
    try state.appendCommitted(stored);
    return renderOk(allocator, tx);
}

fn assertBatch(
    allocator: Allocator,
    io: Io,
    canonical_log: []const u8,
    state: *DaemonState,
    request: *MapFields,
) ![]u8 {
    if (try requestFieldError(
        allocator,
        request,
        field_op | field_te | field_facts | field_base,
        field_te | field_facts,
    )) |response| return response;

    const te = switch (request.te) {
        .value => |value| value,
        else => return renderInvalidBatch(
            allocator,
            "assert-batch requires a subject :te",
            state.version,
        ),
    };
    if (std.mem.trim(u8, te, " \t\r\n").len == 0) {
        return renderInvalidBatch(
            allocator,
            "assert-batch requires a subject :te",
            state.version,
        );
    }
    const top_base: ?i64 = switch (request.base) {
        .missing, .nil => null,
        .value => |value| value,
        .invalid => return renderInvalidRequest(
            allocator,
            &.{"base must be an integer or nil"},
        ),
    };
    const facts_raw = request.facts orelse return renderInvalidBatch(
        allocator,
        "assert-batch requires a non-empty :facts vector",
        state.version,
    );
    var batch = parseBatchFacts(allocator, facts_raw) catch |err| switch (err) {
        error.OutOfMemory => return error.OutOfMemory,
        error.InvalidBatch => return renderInvalidBatch(
            allocator,
            "each :facts entry needs a string :p and a :r",
            state.version,
        ),
    };
    defer batch.deinit(allocator);
    if (batch.facts.items.len == 0) {
        return renderInvalidBatch(
            allocator,
            "assert-batch requires a non-empty :facts vector",
            state.version,
        );
    }

    var ordered: std.ArrayList(usize) = .empty;
    defer ordered.deinit(allocator);
    try ordered.ensureTotalCapacity(allocator, batch.facts.items.len);
    for (batch.facts.items, 0..) |fact, index| {
        if (!deliveryTrigger(fact.p)) ordered.appendAssumeCapacity(index);
    }
    for (batch.facts.items, 0..) |fact, index| {
        if (deliveryTrigger(fact.p)) ordered.appendAssumeCapacity(index);
    }

    var writes: std.ArrayList(usize) = .empty;
    defer writes.deinit(allocator);
    var idempotent: std.ArrayList(usize) = .empty;
    defer idempotent.deinit(allocator);
    try writes.ensureTotalCapacity(allocator, ordered.items.len);
    try idempotent.ensureTotalCapacity(allocator, ordered.items.len);

    for (ordered.items, 0..) |fact_index, ordered_index| {
        const fact = batch.facts.items[fact_index];
        if (fact.p.len == 0) {
            return renderInvalidBatch(
                allocator,
                "each :facts entry needs a string :p and a :r",
                state.version,
            );
        }
        const fact_base: ?i64 = switch (fact.base) {
            .missing => top_base,
            .nil => null,
            .value => |value| value,
            .invalid => return renderInvalidBatch(
                allocator,
                "each fact :base must be an integer or nil",
                state.version,
            ),
        };
        if (state.isSingle(fact.p) and fact_base != null and
            try state.baseVersion(allocator, te, fact.p) > fact_base.?)
        {
            return renderBatchConflict(
                allocator,
                state.version,
                ordered_index,
                fact.p,
            );
        }
        if (!state.isSingle(fact.p) and
            try state.liveEvent(allocator, te, fact.p, fact.r) != null)
        {
            idempotent.appendAssumeCapacity(fact_index);
        } else {
            writes.appendAssumeCapacity(fact_index);
        }
    }

    if (writes.items.len == 0) {
        return renderBatchSuccess(
            allocator,
            state.version,
            batch.facts.items,
            writes.items,
            idempotent.items,
        );
    }
    if (state.version == std.math.maxInt(i64)) {
        return allocator.dupe(
            u8,
            "{:reject :version-exhausted, :code :version-exhausted}",
        );
    }

    const tx = state.version + 1;
    var payload: Writer.Allocating = .init(allocator);
    defer payload.deinit();
    var stored: std.ArrayList(StoredEvent) = .empty;
    defer stored.deinit(allocator);
    try stored.ensureTotalCapacity(allocator, writes.items.len);
    for (writes.items) |fact_index| {
        const fact = batch.facts.items[fact_index];
        const timestamp = try timestampUtc(allocator, io);
        defer allocator.free(timestamp);
        const line = try flat_log.encodeLine(
            allocator,
            .{
                .tx = tx,
                .op = EventOperation.assert_fact.wireName(),
                .l = te,
                .p = fact.p,
                .r = fact.r,
            },
            .{ .coordinator = .{ .ts = timestamp, .by = "coord" } },
        );
        defer allocator.free(line);
        try writeAll(&payload.writer, line);
        stored.appendAssumeCapacity(
            try state.copyEvent(
                tx,
                .assert_fact,
                te,
                fact.p,
                fact.r,
            ),
        );
    }

    try flat_log.appendDurable(
        io,
        Dir.cwd(),
        canonical_log,
        payload.written(),
    );
    try state.appendCommittedBatch(stored.items);
    return renderBatchSuccess(
        allocator,
        tx,
        batch.facts.items,
        writes.items,
        idempotent.items,
    );
}

fn parseBatchFacts(
    allocator: Allocator,
    raw: []const u8,
) (Allocator.Error || error{InvalidBatch})!ParsedBatch {
    var parser: Parser = .{ .input = raw };
    parser.skipSeparators();
    if (parser.index >= raw.len or raw[parser.index] != '[')
        return error.InvalidBatch;
    parser.index += 1;

    var batch: ParsedBatch = .{ .facts = .empty };
    errdefer batch.deinit(allocator);
    while (true) {
        parser.skipSeparators();
        if (parser.index >= raw.len) return error.InvalidBatch;
        if (raw[parser.index] == ']') {
            parser.index += 1;
            parser.skipSeparators();
            if (parser.index != raw.len) return error.InvalidBatch;
            return batch;
        }
        const fact_raw = parser.scanValue() catch return error.InvalidBatch;
        const fact = try parseBatchFact(allocator, fact_raw);
        try batch.facts.append(allocator, fact);
    }
}

fn parseBatchFact(
    allocator: Allocator,
    raw: []const u8,
) (Allocator.Error || error{InvalidBatch})!BatchFact {
    var parser: Parser = .{ .input = raw };
    parser.skipSeparators();
    if (parser.index >= raw.len or raw[parser.index] != '{')
        return error.InvalidBatch;
    parser.index += 1;

    var predicate: ?[]u8 = null;
    errdefer if (predicate) |value| allocator.free(value);
    var value: ?[]u8 = null;
    errdefer if (value) |item| allocator.free(item);
    var base: IntField = .missing;
    var seen: u8 = 0;
    while (true) {
        parser.skipSeparators();
        if (parser.index >= raw.len) return error.InvalidBatch;
        if (raw[parser.index] == '}') {
            parser.index += 1;
            parser.skipSeparators();
            if (parser.index != raw.len or predicate == null or value == null)
                return error.InvalidBatch;
            return .{ .p = predicate.?, .r = value.?, .base = base };
        }
        const key = parser.scanValue() catch return error.InvalidBatch;
        if (key.len < 2 or key[0] != ':') return error.InvalidBatch;
        const item = parser.scanValue() catch return error.InvalidBatch;
        const name = key[1..];
        if (std.mem.eql(u8, name, "p")) {
            if ((seen & 1) != 0) return error.InvalidBatch;
            seen |= 1;
            const parsed = parseStringField(allocator, item) catch |err|
                switch (err) {
                    error.OutOfMemory => return error.OutOfMemory,
                    else => return error.InvalidBatch,
                };
            predicate = switch (parsed) {
                .value => |decoded| decoded,
                else => return error.InvalidBatch,
            };
        } else if (std.mem.eql(u8, name, "r")) {
            if ((seen & 2) != 0) return error.InvalidBatch;
            seen |= 2;
            const parsed = parseStringField(allocator, item) catch |err|
                switch (err) {
                    error.OutOfMemory => return error.OutOfMemory,
                    else => return error.InvalidBatch,
                };
            value = switch (parsed) {
                .value => |decoded| decoded,
                else => return error.InvalidBatch,
            };
        } else if (std.mem.eql(u8, name, "base")) {
            if ((seen & 4) != 0) return error.InvalidBatch;
            seen |= 4;
            base = parseIntField(item);
            switch (base) {
                .invalid => return error.InvalidBatch,
                else => {},
            }
        } else {
            return error.InvalidBatch;
        }
    }
}

fn renderOk(allocator: Allocator, version: i64) ![]u8 {
    return std.fmt.allocPrint(allocator, "{{:ok {d}}}", .{version});
}

fn renderConflict(allocator: Allocator, version: i64) ![]u8 {
    return std.fmt.allocPrint(
        allocator,
        "{{:reject :conflict, :version {d}}}",
        .{version},
    );
}

fn renderInvalidBase(allocator: Allocator, version: i64) ![]u8 {
    return std.fmt.allocPrint(
        allocator,
        "{{:reject :invalid-base, :version {d}}}",
        .{version},
    );
}

fn renderMissingSubject(
    allocator: Allocator,
    subject: []const u8,
    version: i64,
) ![]u8 {
    var output: Writer.Allocating = .init(allocator);
    defer output.deinit();
    const writer = &output.writer;
    try writeAll(
        writer,
        "{:reject :missing-subject, :code :missing-subject, :subject ",
    );
    try writeEdnString(writer, subject);
    try writeAll(writer, ", :version ");
    writer.print("{d}", .{version}) catch return error.OutOfMemory;
    try writeAll(writer, "}");
    return output.toOwnedSlice();
}

fn renderInvalidBatch(
    allocator: Allocator,
    message: []const u8,
    version: i64,
) ![]u8 {
    var output: Writer.Allocating = .init(allocator);
    defer output.deinit();
    const writer = &output.writer;
    try writeAll(writer, "{:reject [");
    try writeEdnString(writer, message);
    try writeAll(writer, "], :code :invalid-batch, :version ");
    writer.print("{d}", .{version}) catch return error.OutOfMemory;
    try writeAll(writer, "}");
    return output.toOwnedSlice();
}

fn renderBatchConflict(
    allocator: Allocator,
    version: i64,
    index: usize,
    predicate: []const u8,
) ![]u8 {
    var output: Writer.Allocating = .init(allocator);
    defer output.deinit();
    const writer = &output.writer;
    try writeAll(writer, "{:reject :conflict, :version ");
    writer.print("{d}", .{version}) catch return error.OutOfMemory;
    try writeAll(writer, ", :at ");
    writer.print("{d}", .{index}) catch return error.OutOfMemory;
    try writeAll(writer, ", :pred ");
    try writeEdnString(writer, predicate);
    try writeAll(writer, "}");
    return output.toOwnedSlice();
}

fn renderBatchSuccess(
    allocator: Allocator,
    version: i64,
    facts: []const BatchFact,
    writes: []const usize,
    idempotent: []const usize,
) ![]u8 {
    var output: Writer.Allocating = .init(allocator);
    defer output.deinit();
    const writer = &output.writer;
    try writeAll(writer, "{:ok ");
    writer.print("{d}", .{version}) catch return error.OutOfMemory;
    try writeAll(writer, ", :written [");
    for (writes, 0..) |index, offset| {
        if (offset != 0) try writeAll(writer, " ");
        try writeEdnString(writer, facts[index].p);
    }
    try writeAll(writer, "], :idempotent [");
    for (idempotent, 0..) |index, offset| {
        if (offset != 0) try writeAll(writer, " ");
        try writeEdnString(writer, facts[index].p);
    }
    try writeAll(writer, "], :batch true}");
    return output.toOwnedSlice();
}

fn timestampUtc(allocator: Allocator, io: Io) ![]u8 {
    const nanoseconds = Io.Clock.real.now(io).nanoseconds;
    if (nanoseconds < 0) return error.InvalidSystemTime;
    const seconds: u64 = @intCast(@divFloor(
        nanoseconds,
        std.time.ns_per_s,
    ));
    const epoch_seconds: std.time.epoch.EpochSeconds = .{ .secs = seconds };
    const year_day = epoch_seconds.getEpochDay().calculateYearDay();
    const month_day = year_day.calculateMonthDay();
    const day_seconds = epoch_seconds.getDaySeconds();
    return std.fmt.allocPrint(
        allocator,
        "{d:0>4}-{d:0>2}-{d:0>2}T{d:0>2}:{d:0>2}:{d:0>2}Z",
        .{
            year_day.year,
            month_day.month.numeric(),
            month_day.day_index + 1,
            day_seconds.getHoursIntoDay(),
            day_seconds.getMinutesIntoHour(),
            day_seconds.getSecondsIntoMinute(),
        },
    );
}

fn groupKeyAlloc(
    allocator: Allocator,
    l: []const u8,
    p: []const u8,
) ![]u8 {
    return std.fmt.allocPrint(
        allocator,
        "{d}:{s}{d}:{s}",
        .{ l.len, l, p.len, p },
    );
}

fn tripleKeyAlloc(
    allocator: Allocator,
    l: []const u8,
    p: []const u8,
    r: []const u8,
) ![]u8 {
    return std.fmt.allocPrint(
        allocator,
        "{d}:{s}{d}:{s}{d}:{s}",
        .{ l.len, l, p.len, p, r.len, r },
    );
}

fn stripAt(value: []const u8) []const u8 {
    return if (value.len != 0 and value[0] == '@') value[1..] else value;
}

fn isMetaSingle(predicate: []const u8) bool {
    for (meta_single_predicates) |candidate| {
        if (std.mem.eql(u8, predicate, candidate)) return true;
    }
    return false;
}

fn refShape(value: []const u8) bool {
    return value.len > 1 and value[0] == '@' and !containsWhitespace(value);
}

fn containsWhitespace(value: []const u8) bool {
    for (value) |byte| {
        if (std.ascii.isWhitespace(byte)) return true;
    }
    return false;
}

fn deliveryTrigger(predicate: []const u8) bool {
    return std.mem.eql(u8, predicate, "to") or
        std.mem.eql(u8, predicate, "target");
}

fn parseMap(allocator: Allocator, input: []const u8) !MapFields {
    var parser: Parser = .{ .input = input };
    parser.skipSeparators();
    if (parser.index >= input.len or input[parser.index] != '{')
        return error.InvalidEdn;
    parser.index += 1;

    var fields: MapFields = .{};
    errdefer fields.deinit(allocator);
    while (true) {
        parser.skipSeparators();
        if (parser.index >= input.len) return error.InvalidEdn;
        if (input[parser.index] == '}') {
            parser.index += 1;
            parser.skipSeparators();
            if (parser.index != input.len) return error.InvalidEdn;
            return fields;
        }

        const key = try parser.scanValue();
        if (key.len < 2 or key[0] != ':') return error.InvalidEdn;
        const value = try parser.scanValue();
        const name = key[1..];

        if (std.mem.eql(u8, name, "op")) {
            fields.noteField(field_op);
            fields.op = parseOperation(value);
        } else if (std.mem.eql(u8, name, "expected-log")) {
            fields.noteField(field_expected_log);
            fields.expected_log.deinit(allocator);
            fields.expected_log = try parseStringField(allocator, value);
        } else if (std.mem.eql(u8, name, "request")) {
            fields.noteField(field_request);
            fields.request = value;
        } else if (std.mem.eql(u8, name, "te")) {
            fields.noteField(field_te);
            fields.te.deinit(allocator);
            fields.te = try parseStringField(allocator, value);
        } else if (std.mem.eql(u8, name, "p")) {
            fields.noteField(field_p);
            fields.p.deinit(allocator);
            fields.p = try parseStringField(allocator, value);
        } else if (std.mem.eql(u8, name, "r")) {
            fields.noteField(field_r);
            fields.r.deinit(allocator);
            fields.r = try parseStringField(allocator, value);
        } else if (std.mem.eql(u8, name, "base")) {
            fields.noteField(field_base);
            fields.base = parseIntField(value);
        } else if (std.mem.eql(u8, name, "facts")) {
            fields.noteField(field_facts);
            fields.facts = value;
        } else if (std.mem.eql(u8, name, "res")) {
            fields.noteField(field_res);
            fields.res.deinit(allocator);
            fields.res = try parseStringField(allocator, value);
        } else if (std.mem.eql(u8, name, "holder")) {
            fields.noteField(field_holder);
            fields.holder.deinit(allocator);
            fields.holder = try parseStringField(allocator, value);
        } else if (std.mem.eql(u8, name, "epoch")) {
            fields.noteField(field_epoch);
            fields.epoch = parseIntField(value);
        } else if (std.mem.eql(u8, name, "ttl-ms")) {
            fields.noteField(field_ttl_ms);
            fields.ttl_ms = parseIntField(value);
        } else {
            fields.unknown_field = true;
        }
    }
}

fn parseOperation(raw: []const u8) Operation {
    if (std.mem.eql(u8, raw, ":for-log")) return .for_log;
    if (std.mem.eql(u8, raw, ":version")) return .version;
    if (std.mem.eql(u8, raw, ":status")) return .status;
    if (std.mem.eql(u8, raw, ":assert")) return .assert_fact;
    if (std.mem.eql(u8, raw, ":assert-existing")) return .assert_existing;
    if (std.mem.eql(u8, raw, ":assert-batch")) return .assert_batch;
    if (std.mem.eql(u8, raw, ":assert-at-version"))
        return .assert_at_version;
    if (std.mem.eql(u8, raw, ":assert-with-fence"))
        return .assert_with_fence;
    if (std.mem.eql(u8, raw, ":assert-at-version-with-fence"))
        return .assert_at_version_with_fence;
    if (std.mem.eql(u8, raw, ":retract")) return .retract_fact;
    if (std.mem.eql(u8, raw, ":retract-existing"))
        return .retract_existing;
    if (std.mem.eql(u8, raw, ":retract-with-fence"))
        return .retract_with_fence;
    if (std.mem.eql(u8, raw, ":acquire-lease")) return .acquire_lease;
    if (std.mem.eql(u8, raw, ":renew-lease")) return .renew_lease;
    if (std.mem.eql(u8, raw, ":release-lease")) return .release_lease;
    if (std.mem.eql(u8, raw, ":fence-ok")) return .fence_ok;
    return .unknown;
}

fn parseStringField(
    allocator: Allocator,
    raw: []const u8,
) !StringField {
    return if (raw.len >= 2 and
        raw[0] == '"' and raw[raw.len - 1] == '"')
        .{ .value = try decodeEdnString(allocator, raw) }
    else
        .invalid;
}

fn parseIntField(raw: []const u8) IntField {
    if (std.mem.eql(u8, raw, "nil")) return .nil;
    const value = std.fmt.parseInt(i64, raw, 10) catch return .invalid;
    return .{ .value = value };
}

fn decodeEdnString(allocator: Allocator, raw: []const u8) ![]u8 {
    if (raw.len < 2 or raw[0] != '"' or raw[raw.len - 1] != '"')
        return error.InvalidEdn;
    const output = try allocator.alloc(u8, raw.len - 2);
    errdefer allocator.free(output);
    var input_index: usize = 1;
    var output_index: usize = 0;
    while (input_index < raw.len - 1) {
        const byte = raw[input_index];
        input_index += 1;
        if (byte != '\\') {
            output[output_index] = byte;
            output_index += 1;
            continue;
        }
        if (input_index >= raw.len - 1) return error.InvalidEdn;
        const escaped = raw[input_index];
        input_index += 1;
        switch (escaped) {
            '"' => output[output_index] = '"',
            '\\' => output[output_index] = '\\',
            'n' => output[output_index] = '\n',
            'r' => output[output_index] = '\r',
            't' => output[output_index] = '\t',
            'b' => output[output_index] = 0x08,
            'f' => output[output_index] = 0x0c,
            'u' => {
                if (input_index + 4 > raw.len - 1) return error.InvalidEdn;
                const codepoint = std.fmt.parseInt(
                    u21,
                    raw[input_index .. input_index + 4],
                    16,
                ) catch return error.InvalidEdn;
                input_index += 4;
                const encoded = std.unicode.utf8Encode(
                    codepoint,
                    output[output_index..],
                ) catch return error.InvalidEdn;
                output_index += encoded;
                continue;
            },
            else => return error.InvalidEdn,
        }
        output_index += 1;
    }
    return allocator.realloc(output, output_index);
}

fn closingDelimiter(byte: u8) ?u8 {
    return switch (byte) {
        '{' => '}',
        '[' => ']',
        '(' => ')',
        else => null,
    };
}

fn isValueDelimiter(byte: u8) bool {
    return switch (byte) {
        ' ', '\t', '\r', '\n', ',', '{', '}', '[', ']', '(', ')' => true,
        else => false,
    };
}

fn renderStrictFenceRequired(
    allocator: Allocator,
    canonical_log: []const u8,
) ![]u8 {
    var output: Writer.Allocating = .init(allocator);
    defer output.deinit();
    const writer = &output.writer;
    try writeAll(writer, "{:reject [\"this coordinator requires a :for-log envelope\"] :code :log-fence-required :served-log ");
    try writeEdnString(writer, canonical_log);
    try writeAll(writer, "}");
    return output.toOwnedSlice();
}

fn renderLogMismatch(
    allocator: Allocator,
    expected: []const u8,
    served: []const u8,
) ![]u8 {
    var output: Writer.Allocating = .init(allocator);
    defer output.deinit();
    const writer = &output.writer;
    try writeAll(writer, "{:reject [");
    var message: Writer.Allocating = .init(allocator);
    defer message.deinit();
    try writeAll(&message.writer, "log mismatch: client expects ");
    try writeAll(&message.writer, expected);
    try writeAll(&message.writer, " but coordinator serves ");
    try writeAll(&message.writer, served);
    try writeEdnString(writer, message.written());
    try writeAll(writer, "] :code :log-mismatch :expected-log ");
    try writeEdnString(writer, expected);
    try writeAll(writer, " :served-log ");
    try writeEdnString(writer, served);
    try writeAll(writer, "}");
    return output.toOwnedSlice();
}

fn renderStatus(
    allocator: Allocator,
    canonical_log: []const u8,
    authority_path: []const u8,
    state: DaemonState,
) ![]u8 {
    var output: Writer.Allocating = .init(allocator);
    defer output.deinit();
    const writer = &output.writer;
    try writeAll(writer, "{:version ");
    writer.print("{d}", .{state.version}) catch return error.OutOfMemory;
    try writeAll(writer, " :log ");
    try writeEdnString(writer, canonical_log);
    try writeAll(writer, " :writer-authority {:format ");
    try writeEdnString(writer, authority_format);
    try writeAll(writer, " :role :active :write-authorized true :log ");
    try writeEdnString(writer, canonical_log);
    try writeAll(writer, " :lock ");
    try writeEdnString(writer, authority_path);
    try writeAll(writer, "}}");
    return output.toOwnedSlice();
}

fn writeAll(writer: *Writer, bytes: []const u8) Allocator.Error!void {
    writer.writeAll(bytes) catch return error.OutOfMemory;
}

fn writeEdnString(writer: *Writer, value: []const u8) Allocator.Error!void {
    writer.writeByte('"') catch return error.OutOfMemory;
    for (value) |byte| switch (byte) {
        '"' => writer.writeAll("\\\"") catch return error.OutOfMemory,
        '\\' => writer.writeAll("\\\\") catch return error.OutOfMemory,
        '\n' => writer.writeAll("\\n") catch return error.OutOfMemory,
        '\r' => writer.writeAll("\\r") catch return error.OutOfMemory,
        '\t' => writer.writeAll("\\t") catch return error.OutOfMemory,
        0x08 => writer.writeAll("\\b") catch return error.OutOfMemory,
        0x0c => writer.writeAll("\\f") catch return error.OutOfMemory,
        else => writer.writeByte(byte) catch return error.OutOfMemory,
    };
    writer.writeByte('"') catch return error.OutOfMemory;
}

test "parse ordinary and fenced bootstrap requests" {
    var ordinary = try parseMap(std.testing.allocator, "{:op :version}");
    defer ordinary.deinit(std.testing.allocator);
    try std.testing.expectEqual(Operation.version, ordinary.op);

    var fenced = try parseMap(
        std.testing.allocator,
        "{:op :for-log, :expected-log \"/tmp/a\\\\b.log\", :request {:op :status}}",
    );
    defer fenced.deinit(std.testing.allocator);
    try std.testing.expectEqual(Operation.for_log, fenced.op);
    try std.testing.expectEqualStrings(
        "/tmp/a\\b.log",
        switch (fenced.expected_log) {
            .value => |value| value,
            else => return error.TestUnexpectedResult,
        },
    );
    var nested = try parseMap(std.testing.allocator, fenced.request.?);
    defer nested.deinit(std.testing.allocator);
    try std.testing.expectEqual(Operation.status, nested.op);
}

test "strict bootstrap response rejects an unwrapped request" {
    var environ = std.process.Environ.Map.init(std.testing.allocator);
    defer environ.deinit();
    var state = try DaemonState.init(std.testing.allocator, &environ);
    defer state.deinit();
    state.version = 7;
    const response = try handleRequest(
        std.testing.allocator,
        std.testing.io,
        "{:op :version}",
        "/tmp/facts.log",
        "/tmp/facts.log.writer-authority.lock",
        &state,
        true,
    );
    defer std.testing.allocator.free(response);
    try std.testing.expect(std.mem.indexOf(
        u8,
        response,
        ":code :log-fence-required",
    ) != null);
}

test "version and status expose replayed version and authority" {
    var environ = std.process.Environ.Map.init(std.testing.allocator);
    defer environ.deinit();
    var state = try DaemonState.init(std.testing.allocator, &environ);
    defer state.deinit();
    state.version = 9;

    const version = try handleRequest(
        std.testing.allocator,
        std.testing.io,
        "{:op :version}",
        "/tmp/facts.log",
        "/tmp/facts.log.writer-authority.lock",
        &state,
        false,
    );
    defer std.testing.allocator.free(version);
    try std.testing.expectEqualStrings("{:version 9}", version);

    const status = try handleRequest(
        std.testing.allocator,
        std.testing.io,
        "{:op :status}",
        "/tmp/facts.log",
        "/tmp/facts.log.writer-authority.lock",
        &state,
        false,
    );
    defer std.testing.allocator.free(status);
    try std.testing.expect(std.mem.indexOf(
        u8,
        status,
        ":write-authorized true",
    ) != null);
}
