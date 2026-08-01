const builtin = @import("builtin");
const std = @import("std");
const flat_log = @import("log.zig");
const rpc = @import("rpc.zig");
const kernel_classify = @import("fram_kernel_classify.zig");

const Allocator = std.mem.Allocator;
const Dir = std.Io.Dir;
const File = std.Io.File;
const Io = std.Io;
const Writer = std.Io.Writer;

var shutdown_requested = std.atomic.Value(bool).init(false);

const Operation = enum {
    version,
    status,
    assert,
    retract,
    batch,
    scan,
    occurrences,
    query,
    lease_acquire,
    lease_renew,
    lease_release,
    lease_check,
    validate,
    unknown,

    fn parse(term: flat_log.Term) Operation {
        const name = keywordValue(term) orelse return .unknown;
        if (std.mem.eql(u8, name, "rpc/version")) return .version;
        if (std.mem.eql(u8, name, "rpc/status")) return .status;
        if (std.mem.eql(u8, name, "rpc/assert")) return .assert;
        if (std.mem.eql(u8, name, "rpc/retract")) return .retract;
        if (std.mem.eql(u8, name, "rpc/batch")) return .batch;
        if (std.mem.eql(u8, name, "rpc/scan")) return .scan;
        if (std.mem.eql(u8, name, "rpc/occurrences")) return .occurrences;
        if (std.mem.eql(u8, name, "rpc/query")) return .query;
        if (std.mem.eql(u8, name, "rpc/lease-acquire")) return .lease_acquire;
        if (std.mem.eql(u8, name, "rpc/lease-renew")) return .lease_renew;
        if (std.mem.eql(u8, name, "rpc/lease-release")) return .lease_release;
        if (std.mem.eql(u8, name, "rpc/lease-check")) return .lease_check;
        if (std.mem.eql(u8, name, "rpc/validate")) return .validate;
        return .unknown;
    }
};

const EventOperation = enum {
    assert,
    retract,
};

const TripleRow = struct {
    tx_seq: i64,
    ordinal: u32,
    operation: EventOperation,
    triple: flat_log.Triple,
};

const LatestDeclaration = struct {
    tx_seq: i64,
    ordinal: u32,
    operation: EventOperation,
    single: bool,
};

const StringTripleView = struct {
    slot0: []const u8,
    slot1: []const u8,
    slot2: []const u8,
};

fn stringAtom(term: flat_log.Term) ?[]const u8 {
    return switch (term) {
        .atom => |atom| switch (atom) {
            .string => |value| value,
            else => null,
        },
        .triple => null,
    };
}

fn stringTripleView(triple: flat_log.Triple) ?StringTripleView {
    return .{
        .slot0 = stringAtom(triple.slot0) orelse return null,
        .slot1 = stringAtom(triple.slot1) orelse return null,
        .slot2 = stringAtom(triple.slot2) orelse return null,
    };
}

fn stringTriple(slot0: []const u8, slot1: []const u8, slot2: []const u8) flat_log.Triple {
    return .{
        .slot0 = .{ .atom = .{ .string = slot0 } },
        .slot1 = .{ .atom = .{ .string = slot1 } },
        .slot2 = .{ .atom = .{ .string = slot2 } },
    };
}

fn stringTerm(value: []const u8) flat_log.Term {
    return .{ .atom = .{ .string = value } };
}

fn integerTerm(value: i64) flat_log.Term {
    return .{ .atom = .{ .integer = value } };
}

fn booleanTerm(value: bool) flat_log.Term {
    return .{ .atom = .{ .boolean = value } };
}

fn keywordTerm(value: []const u8) flat_log.Term {
    return .{ .atom = .{ .keyword = value } };
}

fn stringValue(term: flat_log.Term) ?[]const u8 {
    return switch (term) {
        .atom => |atom| switch (atom) {
            .string => |value| value,
            else => null,
        },
        .triple => null,
    };
}

fn integerValue(term: flat_log.Term) ?i64 {
    return switch (term) {
        .atom => |atom| switch (atom) {
            .integer => |value| value,
            else => null,
        },
        .triple => null,
    };
}

fn booleanValue(term: flat_log.Term) ?bool {
    return switch (term) {
        .atom => |atom| switch (atom) {
            .boolean => |value| value,
            else => null,
        },
        .triple => null,
    };
}

fn keywordValue(term: flat_log.Term) ?[]const u8 {
    return switch (term) {
        .atom => |atom| switch (atom) {
            .keyword => |value| value,
            else => null,
        },
        .triple => null,
    };
}

fn isKeyword(term: flat_log.Term, expected: []const u8) bool {
    const actual = keywordValue(term) orelse return false;
    return std.mem.eql(u8, actual, expected);
}

fn tripleValue(term: flat_log.Term) ?flat_log.Triple {
    return switch (term) {
        .triple => |value| value.*,
        .atom => null,
    };
}

fn tripleTerm(
    arena: Allocator,
    slot0: flat_log.Term,
    slot1: flat_log.Term,
    slot2: flat_log.Term,
) !flat_log.Term {
    const value = try arena.create(flat_log.Triple);
    value.* = .{ .slot0 = slot0, .slot1 = slot1, .slot2 = slot2 };
    return .{ .triple = value };
}

fn list(arena: Allocator, items: []const flat_log.Term) !flat_log.Term {
    var tail = keywordTerm("rpc/list-end");
    var index = items.len;
    while (index != 0) {
        index -= 1;
        tail = try tripleTerm(
            arena,
            keywordTerm("rpc/list"),
            items[index],
            tail,
        );
    }
    return tail;
}

fn collectList(arena: Allocator, root: flat_log.Term) ![]const flat_log.Term {
    var items: std.ArrayList(flat_log.Term) = .empty;
    var cursor = root;
    while (!isKeyword(cursor, "rpc/list-end")) {
        const cell = tripleValue(cursor) orelse return error.InvalidPayload;
        if (!isKeyword(cell.slot0, "rpc/list")) return error.InvalidPayload;
        if (items.items.len >= rpc.term_limits.max_nodes)
            return error.InvalidPayload;
        try items.append(arena, cell.slot1);
        cursor = cell.slot2;
    }
    return items.toOwnedSlice(arena);
}

fn record(
    arena: Allocator,
    tag: []const u8,
    fields: []const flat_log.Term,
) !flat_log.Term {
    return tripleTerm(
        arena,
        keywordTerm(tag),
        try list(arena, fields),
        keywordTerm("rpc/record"),
    );
}

fn recordFields(
    arena: Allocator,
    value: flat_log.Term,
    tag: []const u8,
    expected: usize,
) ![]const flat_log.Term {
    const triple = tripleValue(value) orelse return error.InvalidPayload;
    if (!isKeyword(triple.slot0, tag) or
        !isKeyword(triple.slot2, "rpc/record")) return error.InvalidPayload;
    const fields = try collectList(arena, triple.slot1);
    if (fields.len != expected) return error.InvalidPayload;
    return fields;
}

const OptionTerm = union(enum) {
    none,
    some: flat_log.Term,
};

fn optionValue(value: flat_log.Term) !OptionTerm {
    if (isKeyword(value, "rpc/none")) return .none;
    const triple = tripleValue(value) orelse return error.InvalidPayload;
    if (!isKeyword(triple.slot0, "rpc/some") or
        !isKeyword(triple.slot2, "rpc/option")) return error.InvalidPayload;
    return .{ .some = triple.slot1 };
}

fn option(arena: Allocator, value: ?flat_log.Term) !flat_log.Term {
    if (value) |present| return tripleTerm(
        arena,
        keywordTerm("rpc/some"),
        present,
        keywordTerm("rpc/option"),
    );
    return keywordTerm("rpc/none");
}

fn failure(
    arena: Allocator,
    code: []const u8,
    retryable: bool,
    message: []const u8,
    detail: ?flat_log.Term,
) !DispatchResult {
    _ = arena;
    return .{ .@"error" = .{
        .code = keywordTerm(code),
        .retryable = retryable,
        .message = stringTerm(message),
        .detail = detail,
    } };
}

fn laterPosition(left: TripleRow, right: TripleRow) bool {
    return left.tx_seq > right.tx_seq or
        (left.tx_seq == right.tx_seq and left.ordinal > right.ordinal);
}

const DaemonState = struct {
    allocator: Allocator,
    arena: std.heap.ArenaAllocator,
    events: std.ArrayList(TripleRow),
    cardinality: std.StringHashMap(bool),
    configured_single: std.StringHashMap(void),
    latest: std.StringHashMap(usize),
    subjects: std.StringHashMap(void),
    space_id: []const u8,
    log_valid_bytes: u64,
    version: i64,

    fn init(
        allocator: Allocator,
        environ: *const std.process.Environ.Map,
        space_id: []const u8,
    ) !DaemonState {
        var state: DaemonState = .{
            .allocator = allocator,
            .arena = std.heap.ArenaAllocator.init(allocator),
            .events = .empty,
            .cardinality = std.StringHashMap(bool).init(allocator),
            .configured_single = std.StringHashMap(void).init(allocator),
            .latest = std.StringHashMap(usize).init(allocator),
            .subjects = std.StringHashMap(void).init(allocator),
            .space_id = undefined,
            .log_valid_bytes = 0,
            .version = 0,
        };
        errdefer state.deinit();
        state.space_id = try state.arena.allocator().dupe(u8, space_id);
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
        tx_seq: i64,
        ordinal: u32,
        operation: EventOperation,
        triple: flat_log.Triple,
    ) !TripleRow {
        const arena = state.arena.allocator();
        return .{
            .tx_seq = tx_seq,
            .ordinal = ordinal,
            .operation = operation,
            .triple = try flat_log.cloneTriple(arena, triple),
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
            const triple = stringTripleView(event.triple) orelse continue;
            if (!std.mem.eql(u8, triple.slot1, "cardinality")) continue;
            const predicate = kernel_classify.stripAt(triple.slot0);
            const declaration: LatestDeclaration = .{
                .tx_seq = event.tx_seq,
                .ordinal = event.ordinal,
                .operation = event.operation,
                .single = std.mem.eql(u8, triple.slot2, "single"),
            };
            if (declarations.get(predicate)) |previous| {
                if (previous.tx_seq > event.tx_seq or
                    (previous.tx_seq == event.tx_seq and
                        previous.ordinal > event.ordinal)) continue;
            }
            try declarations.put(predicate, declaration);
        }
        var declaration_iterator = declarations.iterator();
        while (declaration_iterator.next()) |entry| {
            const declaration = entry.value_ptr.*;
            if (declaration.operation == .assert) {
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
        event: TripleRow,
    ) !void {
        if (stringTripleView(event.triple)) |triple| {
            try state.subjects.put(triple.slot0, {});
            if (!std.mem.eql(u8, triple.slot1, "v") and refShape(triple.slot2)) {
                try state.subjects.put(triple.slot2, {});
            }
        }
        const key = try state.eventKey(event);
        if (state.latest.get(key)) |previous_index| {
            if (laterPosition(state.events.items[previous_index], event)) return;
        }
        try state.latest.put(key, index);
    }

    fn appendCommitted(
        state: *DaemonState,
        event: TripleRow,
    ) !void {
        try state.events.append(state.allocator, event);
        state.version = @max(state.version, event.tx_seq);
        const changes_cardinality = if (stringTripleView(event.triple)) |triple|
            std.mem.eql(u8, triple.slot1, "cardinality")
        else
            false;
        if (changes_cardinality) {
            try state.rebuildDerived();
        } else {
            try state.applyEvent(state.events.items.len - 1, event);
        }
    }

    fn appendCommittedBatch(
        state: *DaemonState,
        events: []const TripleRow,
    ) !void {
        try state.events.ensureUnusedCapacity(state.allocator, events.len);
        var changes_cardinality = false;
        const start = state.events.items.len;
        for (events) |event| {
            state.events.appendAssumeCapacity(event);
            state.version = @max(state.version, event.tx_seq);
            if (stringTripleView(event.triple)) |triple| {
                changes_cardinality = changes_cardinality or
                    std.mem.eql(u8, triple.slot1, "cardinality");
            }
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

    /// Declared cardinality only — the fallback/emoji conventions of `isSingle`
    /// are not a schema fact and must never stand in for one.
    fn schemaSingle(state: *const DaemonState, predicate: []const u8) bool {
        return state.cardinality.get(predicate) orelse false;
    }

    fn eventKey(state: *DaemonState, event: TripleRow) ![]const u8 {
        return state.eventKeyAlloc(state.arena.allocator(), event.triple);
    }

    fn eventKeyAlloc(
        state: *const DaemonState,
        allocator: Allocator,
        triple: flat_log.Triple,
    ) ![]const u8 {
        if (stringTripleView(triple)) |view| {
            if (state.isSingle(view.slot1)) {
                return groupKeyAlloc(allocator, view.slot0, view.slot1);
            }
        }
        return flat_log.encodeTripleKey(allocator, triple);
    }

    fn liveGroup(
        state: *DaemonState,
        scratch: Allocator,
        l: []const u8,
        p: []const u8,
    ) !?TripleRow {
        if (state.isSingle(p)) {
            const key = try groupKeyAlloc(scratch, l, p);
            defer scratch.free(key);
            const index = state.latest.get(key) orelse return null;
            const event = state.events.items[index];
            return if (event.operation == .assert) event else null;
        }
        var latest = state.latest.iterator();
        while (latest.next()) |entry| {
            const event = state.events.items[entry.value_ptr.*];
            const triple = stringTripleView(event.triple) orelse continue;
            if (event.operation == .assert and
                std.mem.eql(u8, triple.slot0, l) and
                std.mem.eql(u8, triple.slot1, p))
            {
                return event;
            }
        }
        return null;
    }

    fn allLivePredicateValuesRef(
        state: *DaemonState,
        predicate: []const u8,
    ) bool {
        var latest = state.latest.iterator();
        var seen = false;
        while (latest.next()) |entry| {
            const event = state.events.items[entry.value_ptr.*];
            const triple = stringTripleView(event.triple) orelse continue;
            if (event.operation != .assert or
                !std.mem.eql(u8, triple.slot1, predicate))
            {
                continue;
            }
            if (!refShape(triple.slot2)) return false;
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
        const triple = stringTripleView(event.triple) orelse return null;
        return parseLease(triple.slot2);
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
    "cardinality", "value_kind", "name", "acyclic", "predicate_name",
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
                    "usage: fram-daemon-zig serve-log PORT LOG\n",
                    .{},
                );
                std.process.exit(2);
            },
            error.MissingSpaceId => {
                std.debug.print("fram: FRAM_SPACE_ID is required\n", .{});
                std.process.exit(2);
            },
            error.MigrationRequired => {
                std.debug.print(
                    "fram: legacy or empty log requires one-shot FRAMLOG v1 migration\n",
                    .{},
                );
                std.process.exit(1);
            },
            error.SpaceMismatch => {
                std.debug.print("fram: immutable SpaceId does not match log header\n", .{});
                std.process.exit(1);
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
    if (args.next() != null or !std.mem.eql(u8, mode, "serve-log"))
        return error.InvalidArguments;
    const port = std.fmt.parseInt(u16, port_text, 10) catch
        return error.InvalidArguments;

    const space_id = init.environ_map.get("FRAM_SPACE_ID") orelse
        return error.MissingSpaceId;
    try ensureLogImage(
        init.gpa,
        init.io,
        init.environ_map,
        log_argument,
        space_id,
    );

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
        space_id,
    );
    defer state.deinit();
    try installSignalHandlers();
    try serve(
        init.gpa,
        init.io,
        port,
        canonical_log,
        authority.path,
        &state,
    );
}

fn ensureLogImage(
    allocator: Allocator,
    io: Io,
    environ: *const std.process.Environ.Map,
    log_path: []const u8,
    space_id: []const u8,
) !void {
    const stat = Dir.cwd().statFile(io, log_path, .{}) catch |err| switch (err) {
        error.FileNotFound => null,
        else => |other| return other,
    };
    if (stat != null and stat.?.size != 0) return;
    const may_create = if (environ.get("FRAM_CREATE_LOG")) |value|
        std.mem.eql(u8, value, "1")
    else
        false;
    if (!may_create) return error.MigrationRequired;

    const image = try flat_log.encodeLog(allocator, space_id, &.{});
    defer allocator.free(image);
    try flat_log.rewriteDurableAtomic(
        allocator,
        io,
        Dir.cwd(),
        log_path,
        space_id,
        image,
    );
}

fn replayState(
    allocator: Allocator,
    io: Io,
    environ: *const std.process.Environ.Map,
    canonical_log: []const u8,
    expected_space_id: []const u8,
) !DaemonState {
    var outcome = try flat_log.replayFileForSpace(
        allocator,
        io,
        Dir.cwd(),
        canonical_log,
        std.math.maxInt(usize),
        expected_space_id,
    );
    switch (outcome) {
        .corrupt => |corruption| {
            std.debug.print(
                "fram: corrupt transaction frame in {s} at byte {d}; refusing to serve\n",
                .{ canonical_log, corruption.byte_offset },
            );
            return error.CorruptLog;
        },
        .replay => |*replay| {
            defer replay.deinit();
            var state = try DaemonState.init(
                allocator,
                environ,
                replay.space_id,
            );
            errdefer state.deinit();
            state.log_valid_bytes = @intCast(replay.valid_bytes);
            for (replay.transactions) |transaction| {
                state.version = @max(state.version, transaction.tx_seq);
                for (transaction.ops) |op| {
                    const operation: EventOperation = switch (op.action) {
                        .assert => .assert,
                        .retract => .retract,
                    };
                    const event = try state.copyEvent(
                        transaction.tx_seq,
                        op.ordinal,
                        operation,
                        op.triple,
                    );
                    try state.events.append(allocator, event);
                }
            }
            try state.rebuildDerived();
            if (replay.torn_tail) |tail| {
                var file = try Dir.cwd().openFile(
                    io,
                    canonical_log,
                    .{ .mode = .read_write },
                );
                defer file.close(io);
                try file.setLength(io, state.log_valid_bytes);
                try file.sync(io);
                std.debug.print(
                    "fram: WARN torn-tail: {s}: incomplete final transaction at byte {d}; recovered {d} complete transaction(s)\n",
                    .{ canonical_log, tail.byte_offset, tail.recovered_transactions },
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

const DispatchResult = struct {
    payload: ?flat_log.Term = null,
    page: ?rpc.PageResponse = null,
    @"error": ?rpc.Error = null,
};

fn serve(
    allocator: Allocator,
    io: Io,
    port: u16,
    canonical_log: []const u8,
    authority_path: []const u8,
    state: *DaemonState,
) !void {
    var address: std.Io.net.IpAddress = .{
        .ip4 = std.Io.net.Ip4Address.loopback(port),
    };
    var server = try address.listen(io, .{ .reuse_address = true });
    defer server.deinit(io);

    std.debug.print(
        "fram zig coordinator: FRAMRPC/1.0 version={d}, space={s}, listening=127.0.0.1:{d}\n",
        .{ state.version, state.space_id, port },
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
        ) catch |err| {
            std.debug.print("fram: FRAMRPC connection failed: {s}\n", .{@errorName(err)});
        };
    }

    std.debug.print("[fram] shutdown complete\n", .{});
}

fn readFrame(
    allocator: Allocator,
    reader: *std.Io.Reader,
) !?[]u8 {
    var header: [rpc.fixed_header_bytes]u8 = undefined;
    reader.readSliceAll(&header) catch |err| switch (err) {
        error.EndOfStream => return null,
        else => return err,
    };
    const length_offset = rpc.format_magic.len +
        @sizeOf(u16) + @sizeOf(u16) + @sizeOf(u8) + @sizeOf(u8);
    const body_len: usize = std.mem.readInt(
        u32,
        header[length_offset..][0..@sizeOf(u32)],
        .little,
    );
    if (body_len > rpc.max_body_bytes) return error.BodyTooLarge;
    const bytes = try allocator.alloc(u8, rpc.fixed_header_bytes + body_len);
    errdefer allocator.free(bytes);
    @memcpy(bytes[0..rpc.fixed_header_bytes], &header);
    reader.readSliceAll(bytes[rpc.fixed_header_bytes..]) catch |err| switch (err) {
        error.EndOfStream => return error.TruncatedFrame,
        else => return err,
    };
    return bytes;
}

fn handleConnection(
    allocator: Allocator,
    io: Io,
    stream: std.Io.net.Stream,
    canonical_log: []const u8,
    authority_path: []const u8,
    state: *DaemonState,
) !void {
    defer stream.close(io);
    var read_buffer: [8192]u8 = undefined;
    var stream_reader = stream.reader(io, &read_buffer);
    const frame_bytes = (try readFrame(
        allocator,
        &stream_reader.interface,
    )) orelse return;
    defer allocator.free(frame_bytes);

    var decoded = rpc.decodeFrame(allocator, frame_bytes) catch return;
    defer decoded.deinit();
    const request = switch (decoded.message) {
        .request => |value| value,
        .cancel => return,
        else => return,
    };

    var response_arena = std.heap.ArenaAllocator.init(allocator);
    defer response_arena.deinit();
    const arena = response_arena.allocator();

    const result = if (!std.mem.eql(
        u8,
        stringValue(request.space) orelse "",
        state.space_id,
    ))
        try failure(
            arena,
            "rpc/space-mismatch",
            false,
            "request space does not match this daemon",
            try record(
                arena,
                "rpc/space-mismatch",
                &.{ request.space, stringTerm(state.space_id) },
            ),
        )
    else
        try dispatchRequest(
            arena,
            allocator,
            io,
            canonical_log,
            authority_path,
            state,
            request,
        );

    const response_bytes = try rpc.encodeFrame(allocator, .{
        .request_id = decoded.request_id,
        .message = .{ .response = .{
            .space = stringTerm(state.space_id),
            .op = request.op,
            .served_version = state.version,
            .page = result.page,
            .@"error" = result.@"error",
            .payload = result.payload,
        } },
    });
    defer allocator.free(response_bytes);

    var write_buffer: [8192]u8 = undefined;
    var stream_writer = stream.writer(io, &write_buffer);
    stream_writer.interface.writeAll(response_bytes) catch return;
    stream_writer.interface.flush() catch return;
}

fn dispatchRequest(
    arena: Allocator,
    allocator: Allocator,
    io: Io,
    canonical_log: []const u8,
    authority_path: []const u8,
    state: *DaemonState,
    request: rpc.Request,
) !DispatchResult {
    const operation = Operation.parse(request.op);
    if (request.expected_version) |expected| {
        if (expected < 0) return failure(
            arena,
            "rpc/invalid-request",
            false,
            "expected_version must be non-negative",
            null,
        );
        if (expected != state.version) return failure(
            arena,
            "rpc/conflict",
            true,
            "version moved",
            try record(
                arena,
                "rpc/conflict",
                &.{
                    integerTerm(expected),
                    integerTerm(state.version),
                },
            ),
        );
    }
    if (request.page != null and
        operation != .scan and operation != .query and operation != .occurrences)
    {
        return failure(
            arena,
            "rpc/invalid-request",
            false,
            "paging is not valid for this operation",
            null,
        );
    }

    return switch (operation) {
        .version => unitRequest(arena, request.payload),
        .status => statusRequest(arena, request.payload, authority_path, state),
        .assert => writeRequest(
            arena,
            allocator,
            io,
            canonical_log,
            state,
            request.payload,
            .assert,
        ),
        .retract => writeRequest(
            arena,
            allocator,
            io,
            canonical_log,
            state,
            request.payload,
            .retract,
        ),
        .batch => batchRequest(
            arena,
            allocator,
            io,
            canonical_log,
            state,
            request.payload,
        ),
        .scan => scanRequest(arena, allocator, state, request),
        .occurrences => occurrencesRequest(arena, allocator, state, request),
        .query => queryRequest(arena, allocator, state, request),
        .lease_acquire => leaseAcquireRequest(
            arena,
            allocator,
            io,
            canonical_log,
            state,
            request.payload,
        ),
        .lease_renew => leaseRenewRequest(
            arena,
            allocator,
            io,
            canonical_log,
            state,
            request.payload,
        ),
        .lease_release => leaseReleaseRequest(
            arena,
            allocator,
            io,
            canonical_log,
            state,
            request.payload,
        ),
        .lease_check => leaseCheckRequest(
            arena,
            allocator,
            io,
            state,
            request.payload,
        ),
        .validate => validateRequest(arena, allocator, state, request.payload),
        .unknown => failure(
            arena,
            "rpc/unsupported-operation",
            false,
            "operation is outside the FRAMRPC core",
            try record(arena, "rpc/unsupported-operation", &.{request.op}),
        ),
    };
}

fn unitRequest(arena: Allocator, payload: flat_log.Term) !DispatchResult {
    if (!isKeyword(payload, "rpc/unit")) return failure(
        arena,
        "rpc/invalid-request",
        false,
        "payload must be rpc/unit",
        null,
    );
    return .{ .payload = keywordTerm("rpc/unit") };
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

const Fence = struct {
    resource: []const u8,
    holder: []const u8,
    epoch: i64,
};

fn parseFence(arena: Allocator, value: flat_log.Term) !Fence {
    const fields = try recordFields(arena, value, "rpc/fence", 3);
    const resource = stringValue(fields[0]) orelse return error.InvalidPayload;
    const holder = stringValue(fields[1]) orelse return error.InvalidPayload;
    const epoch = integerValue(fields[2]) orelse return error.InvalidPayload;
    if (!validLeaseText(resource) or !validLeaseText(holder) or
        std.mem.indexOfScalar(u8, holder, '|') != null or epoch <= 0)
        return error.InvalidPayload;
    return .{ .resource = resource, .holder = holder, .epoch = epoch };
}

fn validTtl(ttl: i64, now: i64) bool {
    return ttl > 0 and now >= 0 and ttl <= std.math.maxInt(i64) - now;
}

fn fenceCurrent(
    allocator: Allocator,
    io: Io,
    state: *DaemonState,
    fence: Fence,
) !bool {
    const lease = try state.currentLease(allocator, fence.resource) orelse
        return false;
    return lease.exp > nowMs(io) and lease.epoch == fence.epoch and
        std.mem.eql(u8, lease.holder, fence.holder);
}

const PendingOperation = struct {
    operation: EventOperation,
    triple: flat_log.Triple,
};

fn occurrenceCoordinate(
    allocator: Allocator,
    space_id: []const u8,
    tx_seq: i64,
    ordinal: u32,
) !flat_log.Term {
    const tx_coord = try allocator.create(flat_log.Triple);
    tx_coord.* = .{
        .slot0 = stringTerm(space_id),
        .slot1 = keywordTerm("kernel/tx-sequence"),
        .slot2 = integerTerm(tx_seq),
    };
    return tripleTerm(
        allocator,
        .{ .triple = tx_coord },
        keywordTerm("kernel/op-ordinal"),
        integerTerm(@intCast(ordinal)),
    );
}

fn occurrenceTerm(
    allocator: Allocator,
    state: *const DaemonState,
    event: TripleRow,
) !flat_log.Term {
    return tripleTerm(
        allocator,
        try occurrenceCoordinate(
            allocator,
            state.space_id,
            event.tx_seq,
            event.ordinal,
        ),
        keywordTerm(if (event.operation == .assert)
            "kernel/asserts"
        else
            "kernel/retracts"),
        try tripleTerm(
            allocator,
            event.triple.slot0,
            event.triple.slot1,
            event.triple.slot2,
        ),
    );
}

fn realInstant(io: Io) !flat_log.Instant {
    const nanoseconds = Io.Clock.real.now(io).nanoseconds;
    const epoch_seconds = @divFloor(nanoseconds, std.time.ns_per_s);
    const remainder = nanoseconds - epoch_seconds * std.time.ns_per_s;
    return .{
        .epoch_seconds = @intCast(epoch_seconds),
        .nanosecond = @intCast(remainder),
    };
}

fn commitTransaction(
    allocator: Allocator,
    io: Io,
    canonical_log: []const u8,
    state: *DaemonState,
    pending: []const PendingOperation,
) !i64 {
    if (pending.len == 0) return error.EmptyTransaction;
    if (state.version == std.math.maxInt(i64)) return error.VersionExhausted;
    if (pending.len > std.math.maxInt(u32) / 3)
        return error.TooManyOperations;

    const tx_seq = state.version + 1;
    const recorded_at = try realInstant(io);
    var arena = std.heap.ArenaAllocator.init(allocator);
    defer arena.deinit();
    const tx_allocator = arena.allocator();
    const operations = try tx_allocator.alloc(flat_log.Op, pending.len * 3);

    for (pending, 0..) |item, index| {
        operations[index] = .{
            .ordinal = @intCast(index),
            .action = switch (item.operation) {
                .assert => .assert,
                .retract => .retract,
            },
            .triple = item.triple,
        };
    }
    for (pending, 0..) |_, index| {
        const occurrence = try occurrenceCoordinate(
            tx_allocator,
            state.space_id,
            tx_seq,
            @intCast(index),
        );
        const offset = pending.len + index * 2;
        operations[offset] = .{
            .ordinal = @intCast(offset),
            .action = .assert,
            .triple = .{
                .slot0 = occurrence,
                .slot1 = keywordTerm("kernel/recorded-at"),
                .slot2 = .{ .atom = .{ .instant = recorded_at } },
            },
        };
        operations[offset + 1] = .{
            .ordinal = @intCast(offset + 1),
            .action = .assert,
            .triple = .{
                .slot0 = occurrence,
                .slot1 = keywordTerm("kernel/asserted-by"),
                .slot2 = stringTerm("coord"),
            },
        };
    }

    var stored: std.ArrayList(TripleRow) = .empty;
    defer stored.deinit(allocator);
    try stored.ensureTotalCapacity(allocator, operations.len);
    for (operations) |op| {
        stored.appendAssumeCapacity(try state.copyEvent(
            tx_seq,
            op.ordinal,
            switch (op.action) {
                .assert => .assert,
                .retract => .retract,
            },
            op.triple,
        ));
    }

    state.log_valid_bytes = try flat_log.appendTransactionDurable(
        allocator,
        io,
        Dir.cwd(),
        canonical_log,
        state.space_id,
        state.log_valid_bytes,
        .{ .tx_seq = tx_seq, .ops = operations },
    );
    try state.appendCommittedBatch(stored.items);
    return tx_seq;
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
    const subject = try std.fmt.allocPrint(allocator, "@lease:{s}", .{resource});
    defer allocator.free(subject);
    var pending: std.ArrayList(PendingOperation) = .empty;
    defer pending.deinit(allocator);
    try pending.ensureTotalCapacity(allocator, 3);

    if (operation == .assert and !state.schemaSingle("lease")) {
        for ([_][2][]const u8{
            .{ "cardinality", "single" },
            .{ "value_kind", "literal" },
        }) |declaration| {
            pending.appendAssumeCapacity(.{
                .operation = .assert,
                .triple = stringTriple("@lease", declaration[0], declaration[1]),
            });
        }
    }
    pending.appendAssumeCapacity(.{
        .operation = operation,
        .triple = stringTriple(subject, "lease", value),
    });
    return commitTransaction(
        allocator,
        io,
        canonical_log,
        state,
        pending.items,
    );
}

fn currentEvent(
    state: *DaemonState,
    scratch: Allocator,
    event: TripleRow,
) !bool {
    const key = try state.eventKeyAlloc(scratch, event.triple);
    defer scratch.free(key);
    const index = state.latest.get(key) orelse return false;
    const live = state.events.items[index];
    return live.tx_seq == event.tx_seq and live.ordinal == event.ordinal and
        live.operation == .assert;
}

fn liveCount(allocator: Allocator, state: *DaemonState) !i64 {
    var count: i64 = 0;
    for (state.events.items) |event| {
        if (event.operation == .assert and
            try currentEvent(state, allocator, event)) count += 1;
    }
    return count;
}

fn statusRequest(
    arena: Allocator,
    payload: flat_log.Term,
    authority_path: []const u8,
    state: *DaemonState,
) !DispatchResult {
    _ = authority_path;
    if (!isKeyword(payload, "rpc/unit")) return failure(
        arena,
        "rpc/invalid-request",
        false,
        "payload must be rpc/unit",
        null,
    );
    return .{ .payload = try record(
        arena,
        "rpc/status",
        &.{
            keywordTerm("rpc/ready"),
            integerTerm(try liveCount(arena, state)),
            keywordTerm("rpc/zig"),
        },
    ) };
}

fn validateRequest(
    arena: Allocator,
    scratch: Allocator,
    state: *DaemonState,
    payload: flat_log.Term,
) !DispatchResult {
    if (!isKeyword(payload, "rpc/unit")) return failure(
        arena,
        "rpc/invalid-request",
        false,
        "payload must be rpc/unit",
        null,
    );
    var valid = state.version >= 0;
    var previous_tx: i64 = -1;
    var previous_ordinal: u32 = 0;
    for (state.events.items, 0..) |event, index| {
        if (event.tx_seq < previous_tx or
            (event.tx_seq == previous_tx and
                (index != 0 and event.ordinal <= previous_ordinal)))
            valid = false;
        previous_tx = event.tx_seq;
        previous_ordinal = event.ordinal;
    }
    var latest = state.latest.iterator();
    while (latest.next()) |entry| {
        if (entry.value_ptr.* >= state.events.items.len) valid = false;
    }
    _ = scratch;
    return .{ .payload = try record(
        arena,
        "rpc/validation",
        &.{ booleanTerm(valid), try list(arena, &.{}) },
    ) };
}

const PageCursor = struct {
    version: i64,
    index: usize,
    ordinal: u32,
};

fn parsePageCursor(
    arena: Allocator,
    value: ?flat_log.Term,
    tag: []const u8,
    version: i64,
) !PageCursor {
    const term = value orelse return .{ .version = version, .index = 0, .ordinal = 0 };
    const fields = try recordFields(arena, term, tag, 3);
    const cursor_version = integerValue(fields[0]) orelse return error.InvalidPayload;
    const index_value = integerValue(fields[1]) orelse return error.InvalidPayload;
    const ordinal_value = integerValue(fields[2]) orelse return error.InvalidPayload;
    if (cursor_version != version or index_value < 0 or ordinal_value < 0 or
        ordinal_value > std.math.maxInt(u32))
        return error.InvalidPayload;
    return .{
        .version = cursor_version,
        .index = @intCast(index_value),
        .ordinal = @intCast(ordinal_value),
    };
}

fn makePageCursor(
    arena: Allocator,
    tag: []const u8,
    version: i64,
    index: usize,
    ordinal: u32,
) !flat_log.Term {
    return record(
        arena,
        tag,
        &.{
            integerTerm(version),
            integerTerm(@intCast(index)),
            integerTerm(@intCast(ordinal)),
        },
    );
}

fn pageLimit(request: rpc.Request) !u32 {
    const limit = if (request.page) |page| page.limit else 4096;
    if (limit == 0 or limit > 4096) return error.InvalidPayload;
    return limit;
}

fn parsePattern(
    arena: Allocator,
    payload: flat_log.Term,
) ![3]OptionTerm {
    const fields = try recordFields(arena, payload, "rpc/triple-pattern", 3);
    return .{
        try optionValue(fields[0]),
        try optionValue(fields[1]),
        try optionValue(fields[2]),
    };
}

fn patternMatches(pattern: [3]OptionTerm, triple: flat_log.Triple) bool {
    const values = [_]flat_log.Term{ triple.slot0, triple.slot1, triple.slot2 };
    for (pattern, values) |slot, value| switch (slot) {
        .none => {},
        .some => |wanted| if (!flat_log.termEql(wanted, value)) return false,
    };
    return true;
}

fn scanRequest(
    arena: Allocator,
    scratch: Allocator,
    state: *DaemonState,
    request: rpc.Request,
) !DispatchResult {
    const pattern = parsePattern(arena, request.payload) catch return failure(
        arena,
        "rpc/invalid-request",
        false,
        "scan payload must be rpc/triple-pattern",
        null,
    );
    const limit = pageLimit(request) catch return failure(
        arena,
        "rpc/invalid-request",
        false,
        "page limit must be 1 through 4096",
        null,
    );
    const cursor = parsePageCursor(
        arena,
        if (request.page) |page| page.cursor else null,
        "rpc/scan-cursor",
        state.version,
    ) catch return failure(
        arena,
        "rpc/invalid-cursor",
        false,
        "scan cursor does not match this snapshot",
        null,
    );

    var rows: std.ArrayList(flat_log.Term) = .empty;
    var index = cursor.index;
    while (index < state.events.items.len and rows.items.len < limit) : (index += 1) {
        const event = state.events.items[index];
        if (event.operation != .assert or
            !(try currentEvent(state, scratch, event)) or
            !patternMatches(pattern, event.triple)) continue;
        try rows.append(
            arena,
            try tripleTerm(
                arena,
                event.triple.slot0,
                event.triple.slot1,
                event.triple.slot2,
            ),
        );
    }
    const done = index >= state.events.items.len;
    const ordinal = cursor.ordinal;
    return .{
        .payload = try record(
            arena,
            "rpc/triples",
            &.{try list(arena, rows.items)},
        ),
        .page = .{
            .ordinal = ordinal,
            .next = if (done) null else try makePageCursor(
                arena,
                "rpc/scan-cursor",
                state.version,
                index,
                ordinal + 1,
            ),
            .done = done,
        },
    };
}

fn occurrencesRequest(
    arena: Allocator,
    scratch: Allocator,
    state: *DaemonState,
    request: rpc.Request,
) !DispatchResult {
    if (!isKeyword(request.payload, "rpc/unit")) return failure(
        arena,
        "rpc/invalid-request",
        false,
        "payload must be rpc/unit",
        null,
    );
    const limit = pageLimit(request) catch return failure(
        arena,
        "rpc/invalid-request",
        false,
        "page limit must be 1 through 4096",
        null,
    );
    const cursor = parsePageCursor(
        arena,
        if (request.page) |page| page.cursor else null,
        "rpc/occurrence-cursor",
        state.version,
    ) catch return failure(
        arena,
        "rpc/invalid-cursor",
        false,
        "occurrence cursor does not match this snapshot",
        null,
    );
    _ = scratch;

    const end = @min(state.events.items.len, cursor.index + limit);
    const rows = try arena.alloc(flat_log.Term, end - cursor.index);
    for (state.events.items[cursor.index..end], 0..) |event, index| {
        rows[index] = try occurrenceTerm(arena, state, event);
    }
    const done = end == state.events.items.len;
    return .{
        .payload = try record(
            arena,
            "rpc/occurrences",
            &.{try list(arena, rows)},
        ),
        .page = .{
            .ordinal = cursor.ordinal,
            .next = if (done) null else try makePageCursor(
                arena,
                "rpc/occurrence-cursor",
                state.version,
                end,
                cursor.ordinal + 1,
            ),
            .done = done,
        },
    };
}

fn leaseGrantTerm(
    arena: Allocator,
    resource: []const u8,
    holder: []const u8,
    epoch: i64,
    exp_ms: i64,
) !flat_log.Term {
    const fence = try record(
        arena,
        "rpc/fence",
        &.{ stringTerm(resource), stringTerm(holder), integerTerm(epoch) },
    );
    const seconds = @divFloor(exp_ms, 1000);
    const millis = exp_ms - seconds * 1000;
    return record(
        arena,
        "lease/grant",
        &.{
            fence,
            .{ .atom = .{ .instant = .{
                .epoch_seconds = seconds,
                .nanosecond = @intCast(millis * std.time.ns_per_ms),
            } } },
        },
    );
}

fn leaseStateFailure(
    arena: Allocator,
    resource: []const u8,
    lease: ?Lease,
    message: []const u8,
) !DispatchResult {
    return failure(
        arena,
        "rpc/lease-state",
        true,
        message,
        try record(
            arena,
            "rpc/lease-state",
            &.{
                stringTerm(resource),
                try option(arena, if (lease) |v| stringTerm(v.holder) else null),
                try option(arena, if (lease) |v| integerTerm(v.epoch) else null),
                try option(arena, if (lease) |v| integerTerm(v.exp) else null),
            },
        ),
    );
}

fn leaseAcquireRequest(
    arena: Allocator,
    scratch: Allocator,
    io: Io,
    canonical_log: []const u8,
    state: *DaemonState,
    payload: flat_log.Term,
) !DispatchResult {
    const fields = recordFields(arena, payload, "lease/acquire", 3) catch
        return failure(arena, "rpc/invalid-request", false, "invalid lease/acquire payload", null);
    const resource = stringValue(fields[0]) orelse
        return failure(arena, "rpc/invalid-request", false, "lease resource must be a string", null);
    const holder = stringValue(fields[1]) orelse
        return failure(arena, "rpc/invalid-request", false, "lease holder must be a string", null);
    const ttl = integerValue(fields[2]) orelse
        return failure(arena, "rpc/invalid-request", false, "lease ttl must be an integer", null);
    const now = nowMs(io);
    if (!validLeaseText(resource) or !validLeaseText(holder) or
        std.mem.indexOfScalar(u8, holder, '|') != null or !validTtl(ttl, now))
        return failure(arena, "rpc/invalid-request", false, "invalid lease acquisition", null);
    if (try state.currentLease(scratch, resource)) |current| {
        if (current.exp > now and !std.mem.eql(u8, current.holder, holder))
            return leaseStateFailure(arena, resource, current, "lease is held");
    }
    const epoch = state.version + 1;
    const value = try std.fmt.allocPrint(
        scratch,
        "{s}|{d}|{d}",
        .{ holder, now + ttl, epoch },
    );
    defer scratch.free(value);
    _ = try appendLeaseEvent(scratch, io, canonical_log, state, .assert, resource, value);
    const current = (try state.currentLease(scratch, resource)).?;
    return .{ .payload = try leaseGrantTerm(
        arena,
        resource,
        current.holder,
        current.epoch,
        current.exp,
    ) };
}

fn leaseRenewRequest(
    arena: Allocator,
    scratch: Allocator,
    io: Io,
    canonical_log: []const u8,
    state: *DaemonState,
    payload: flat_log.Term,
) !DispatchResult {
    const fields = recordFields(arena, payload, "lease/renew", 2) catch
        return failure(arena, "rpc/invalid-request", false, "invalid lease/renew payload", null);
    const fence = parseFence(arena, fields[0]) catch
        return failure(arena, "rpc/invalid-request", false, "invalid lease fence", null);
    const ttl = integerValue(fields[1]) orelse
        return failure(arena, "rpc/invalid-request", false, "lease ttl must be an integer", null);
    const now = nowMs(io);
    const current = try state.currentLease(scratch, fence.resource);
    if (!validTtl(ttl, now) or current == null or current.?.exp <= now or
        current.?.epoch != fence.epoch or
        !std.mem.eql(u8, current.?.holder, fence.holder))
        return leaseStateFailure(arena, fence.resource, current, "lease fence is no longer current");
    const epoch = state.version + 1;
    const value = try std.fmt.allocPrint(
        scratch,
        "{s}|{d}|{d}",
        .{ fence.holder, now + ttl, epoch },
    );
    defer scratch.free(value);
    _ = try appendLeaseEvent(scratch, io, canonical_log, state, .assert, fence.resource, value);
    return .{ .payload = try leaseGrantTerm(
        arena,
        fence.resource,
        fence.holder,
        epoch,
        now + ttl,
    ) };
}

fn leaseReleaseRequest(
    arena: Allocator,
    scratch: Allocator,
    io: Io,
    canonical_log: []const u8,
    state: *DaemonState,
    payload: flat_log.Term,
) !DispatchResult {
    const fence = parseFence(arena, payload) catch
        return failure(arena, "rpc/invalid-request", false, "invalid lease fence", null);
    const current = try state.currentLease(scratch, fence.resource);
    if (current == null or current.?.epoch != fence.epoch or
        !std.mem.eql(u8, current.?.holder, fence.holder))
        return .{ .payload = try record(arena, "lease/released", &.{booleanTerm(false)}) };
    const value = try std.fmt.allocPrint(
        scratch,
        "{s}|{d}|{d}",
        .{ current.?.holder, current.?.exp, current.?.epoch },
    );
    defer scratch.free(value);
    _ = try appendLeaseEvent(
        scratch,
        io,
        canonical_log,
        state,
        .retract,
        fence.resource,
        value,
    );
    return .{ .payload = try record(arena, "lease/released", &.{booleanTerm(true)}) };
}

fn leaseCheckRequest(
    arena: Allocator,
    scratch: Allocator,
    io: Io,
    state: *DaemonState,
    payload: flat_log.Term,
) !DispatchResult {
    const fence = parseFence(arena, payload) catch
        return failure(arena, "rpc/invalid-request", false, "invalid lease fence", null);
    const current = try state.currentLease(scratch, fence.resource);
    const ok = try fenceCurrent(scratch, io, state, fence);
    const expiry = if (current) |lease| blk: {
        const seconds = @divFloor(lease.exp, 1000);
        const millis = lease.exp - seconds * 1000;
        break :blk flat_log.Term{ .atom = .{ .instant = .{
            .epoch_seconds = seconds,
            .nanosecond = @intCast(millis * std.time.ns_per_ms),
        } } };
    } else null;
    return .{ .payload = try record(
        arena,
        "lease/check",
        &.{ booleanTerm(ok), try option(arena, expiry) },
    ) };
}

const max_native_query_rows: usize = 65_536;

const QueryTerm = union(enum) {
    variable: []const u8,
    constant: flat_log.Term,
};

const PredicateOperator = enum {
    eq,
    ne,
    lt,
    le,
    gt,
    ge,

    fn parse(term: flat_log.Term) ?PredicateOperator {
        const name = keywordValue(term) orelse return null;
        if (std.mem.eql(u8, name, "eq")) return .eq;
        if (std.mem.eql(u8, name, "ne")) return .ne;
        if (std.mem.eql(u8, name, "lt")) return .lt;
        if (std.mem.eql(u8, name, "le")) return .le;
        if (std.mem.eql(u8, name, "gt")) return .gt;
        if (std.mem.eql(u8, name, "ge")) return .ge;
        return null;
    }
};

const FunctionOperator = enum {
    add,
    subtract,
    multiply,
    divide,
    modulo,

    fn parse(term: flat_log.Term) ?FunctionOperator {
        const name = keywordValue(term) orelse return null;
        if (std.mem.eql(u8, name, "+")) return .add;
        if (std.mem.eql(u8, name, "-")) return .subtract;
        if (std.mem.eql(u8, name, "*")) return .multiply;
        if (std.mem.eql(u8, name, "/")) return .divide;
        if (std.mem.eql(u8, name, "mod")) return .modulo;
        return null;
    }
};

const QueryRelationClause = struct {
    relation: []const u8,
    args: []const QueryTerm,
    negated: bool,
};

const QueryPredicateClause = struct {
    operator: PredicateOperator,
    left: QueryTerm,
    right: QueryTerm,
};

const QueryFunctionClause = struct {
    operator: FunctionOperator,
    args: []const QueryTerm,
    bind: []const u8,
};

const QueryClause = union(enum) {
    relation: QueryRelationClause,
    predicate: QueryPredicateClause,
    function: QueryFunctionClause,
};

const QueryHead = struct {
    relation: []const u8,
    args: []const QueryTerm,
};

const QueryRule = struct {
    head: QueryHead,
    body: []const QueryClause,
};

const QueryStratum = struct {
    rules: []const QueryRule,
};

const AggregateSpec = struct {
    operator: []const u8,
    argument: ?usize,
};

const HavingSpec = struct {
    operator: PredicateOperator,
    aggregate_index: usize,
    value: flat_log.Term,
};

const AggregateFind = struct {
    relation: []const u8,
    grouping: []const usize,
    aggregates: []const AggregateSpec,
    having: []const HavingSpec,
};

const QueryFind = union(enum) {
    relation: []const u8,
    aggregate: AggregateFind,
};

const ParsedQuery = struct {
    find: QueryFind,
    strata: []const QueryStratum,
    snapshot: i64,
};

fn parseQueryTerm(arena: Allocator, value: flat_log.Term) !QueryTerm {
    const triple = tripleValue(value) orelse return error.InvalidPayload;
    const tag = keywordValue(triple.slot0) orelse return error.InvalidPayload;
    if (!isKeyword(triple.slot2, "rpc/record")) return error.InvalidPayload;
    if (std.mem.eql(u8, tag, "query/var")) {
        const fields = try recordFields(arena, value, "query/var", 1);
        const name = stringValue(fields[0]) orelse return error.InvalidPayload;
        if (name.len == 0) return error.InvalidPayload;
        return .{ .variable = name };
    }
    if (std.mem.eql(u8, tag, "query/const")) {
        const fields = try recordFields(arena, value, "query/const", 1);
        return .{ .constant = fields[0] };
    }
    return error.InvalidPayload;
}

fn parseQueryTermList(
    arena: Allocator,
    value: flat_log.Term,
) ![]const QueryTerm {
    const raw = try collectList(arena, value);
    const terms = try arena.alloc(QueryTerm, raw.len);
    for (raw, 0..) |term, index| terms[index] = try parseQueryTerm(arena, term);
    return terms;
}

fn parseQueryHead(arena: Allocator, value: flat_log.Term) !QueryHead {
    const fields = try recordFields(arena, value, "query/head", 2);
    const relation = stringValue(fields[0]) orelse return error.InvalidPayload;
    if (relation.len == 0) return error.InvalidPayload;
    return .{
        .relation = relation,
        .args = try parseQueryTermList(arena, fields[1]),
    };
}

fn parseQueryClause(arena: Allocator, value: flat_log.Term) !QueryClause {
    const triple = tripleValue(value) orelse return error.InvalidPayload;
    const tag = keywordValue(triple.slot0) orelse return error.InvalidPayload;
    if (std.mem.eql(u8, tag, "query/relation")) {
        const fields = try recordFields(arena, value, "query/relation", 3);
        const relation = stringValue(fields[0]) orelse return error.InvalidPayload;
        const negated = booleanValue(fields[2]) orelse return error.InvalidPayload;
        if (relation.len == 0) return error.InvalidPayload;
        return .{ .relation = .{
            .relation = relation,
            .args = try parseQueryTermList(arena, fields[1]),
            .negated = negated,
        } };
    }
    if (std.mem.eql(u8, tag, "query/predicate")) {
        const fields = try recordFields(arena, value, "query/predicate", 3);
        return .{ .predicate = .{
            .operator = PredicateOperator.parse(fields[0]) orelse
                return error.InvalidPayload,
            .left = try parseQueryTerm(arena, fields[1]),
            .right = try parseQueryTerm(arena, fields[2]),
        } };
    }
    if (std.mem.eql(u8, tag, "query/function")) {
        const fields = try recordFields(arena, value, "query/function", 3);
        const bind = stringValue(fields[2]) orelse return error.InvalidPayload;
        if (bind.len == 0) return error.InvalidPayload;
        return .{ .function = .{
            .operator = FunctionOperator.parse(fields[0]) orelse
                return error.InvalidPayload,
            .args = try parseQueryTermList(arena, fields[1]),
            .bind = bind,
        } };
    }
    return error.InvalidPayload;
}

fn parseQueryRule(arena: Allocator, value: flat_log.Term) !QueryRule {
    const fields = try recordFields(arena, value, "query/rule", 2);
    const body_raw = try collectList(arena, fields[1]);
    const body = try arena.alloc(QueryClause, body_raw.len);
    for (body_raw, 0..) |clause, index|
        body[index] = try parseQueryClause(arena, clause);
    return .{
        .head = try parseQueryHead(arena, fields[0]),
        .body = body,
    };
}

fn parseQueryStratum(arena: Allocator, value: flat_log.Term) !QueryStratum {
    const fields = try recordFields(arena, value, "query/stratum", 1);
    const raw = try collectList(arena, fields[0]);
    if (raw.len == 0) return error.InvalidPayload;
    const rules = try arena.alloc(QueryRule, raw.len);
    for (raw, 0..) |rule, index| rules[index] = try parseQueryRule(arena, rule);
    return .{ .rules = rules };
}

fn parseIndexList(arena: Allocator, value: flat_log.Term) ![]const usize {
    const raw = try collectList(arena, value);
    const indexes = try arena.alloc(usize, raw.len);
    for (raw, 0..) |term, index| {
        const item = integerValue(term) orelse return error.InvalidPayload;
        if (item < 0) return error.InvalidPayload;
        indexes[index] = @intCast(item);
    }
    return indexes;
}

fn parseAggregateSpec(arena: Allocator, value: flat_log.Term) !AggregateSpec {
    const fields = try recordFields(arena, value, "query/aggregate", 2);
    const operator = keywordValue(fields[0]) orelse return error.InvalidPayload;
    if (!(std.mem.eql(u8, operator, "count") or
        std.mem.eql(u8, operator, "count-distinct") or
        std.mem.eql(u8, operator, "sum") or
        std.mem.eql(u8, operator, "avg") or
        std.mem.eql(u8, operator, "min") or
        std.mem.eql(u8, operator, "max"))) return error.InvalidPayload;
    const argument = switch (try optionValue(fields[1])) {
        .none => null,
        .some => |term| blk: {
            const item = integerValue(term) orelse return error.InvalidPayload;
            if (item < 0) return error.InvalidPayload;
            break :blk @as(usize, @intCast(item));
        },
    };
    if (!std.mem.eql(u8, operator, "count") and argument == null)
        return error.InvalidPayload;
    return .{ .operator = operator, .argument = argument };
}

fn parseHaving(arena: Allocator, value: flat_log.Term) !HavingSpec {
    const fields = try recordFields(arena, value, "query/having", 3);
    const aggregate_index = integerValue(fields[1]) orelse
        return error.InvalidPayload;
    if (aggregate_index < 0) return error.InvalidPayload;
    return .{
        .operator = PredicateOperator.parse(fields[0]) orelse
            return error.InvalidPayload,
        .aggregate_index = @intCast(aggregate_index),
        .value = fields[2],
    };
}

fn parseQueryFind(arena: Allocator, value: flat_log.Term) !QueryFind {
    const triple = tripleValue(value) orelse return error.InvalidPayload;
    const tag = keywordValue(triple.slot0) orelse return error.InvalidPayload;
    if (std.mem.eql(u8, tag, "query/find-relation")) {
        const fields = try recordFields(arena, value, "query/find-relation", 1);
        const relation = stringValue(fields[0]) orelse return error.InvalidPayload;
        if (relation.len == 0) return error.InvalidPayload;
        return .{ .relation = relation };
    }
    if (!std.mem.eql(u8, tag, "query/find-aggregate"))
        return error.InvalidPayload;
    const fields = try recordFields(arena, value, "query/find-aggregate", 4);
    const relation = stringValue(fields[0]) orelse return error.InvalidPayload;
    const aggregate_raw = try collectList(arena, fields[2]);
    if (relation.len == 0 or aggregate_raw.len == 0)
        return error.InvalidPayload;
    const aggregates = try arena.alloc(AggregateSpec, aggregate_raw.len);
    for (aggregate_raw, 0..) |spec, index|
        aggregates[index] = try parseAggregateSpec(arena, spec);
    const having_raw = try collectList(arena, fields[3]);
    const having = try arena.alloc(HavingSpec, having_raw.len);
    for (having_raw, 0..) |spec, index| {
        having[index] = try parseHaving(arena, spec);
        if (having[index].aggregate_index >= aggregates.len)
            return error.InvalidPayload;
    }
    return .{ .aggregate = .{
        .relation = relation,
        .grouping = try parseIndexList(arena, fields[1]),
        .aggregates = aggregates,
        .having = having,
    } };
}

fn parseQueryRequest(
    arena: Allocator,
    payload: flat_log.Term,
    current_version: i64,
) !ParsedQuery {
    const request_fields = try recordFields(
        arena,
        payload,
        "query/request",
        2,
    );
    const snapshot = if (isKeyword(request_fields[1], "query/current"))
        current_version
    else snapshot: {
        const fields = try recordFields(
            arena,
            request_fields[1],
            "query/as-of",
            1,
        );
        break :snapshot integerValue(fields[0]) orelse
            return error.InvalidPayload;
    };
    if (snapshot < 0 or snapshot > current_version)
        return error.HistoryUnavailable;

    const plan_fields = try recordFields(
        arena,
        request_fields[0],
        "query/plan",
        2,
    );
    const strata_raw = try collectList(arena, plan_fields[1]);
    if (strata_raw.len == 0) return error.InvalidPayload;
    const strata = try arena.alloc(QueryStratum, strata_raw.len);
    for (strata_raw, 0..) |stratum, index|
        strata[index] = try parseQueryStratum(arena, stratum);
    return .{
        .find = try parseQueryFind(arena, plan_fields[0]),
        .strata = strata,
        .snapshot = snapshot,
    };
}

const QueryBinding = struct {
    name: []const u8,
    value: flat_log.Term,
};

const QuerySubstitution = struct {
    bindings: []const QueryBinding,
};

const QueryRow = struct {
    relation: []const u8,
    values: []const flat_log.Term,
};

const KeyedRow = struct {
    values: []const flat_log.Term,
    key: []const u8,
};

fn lookupBinding(
    substitution: QuerySubstitution,
    name: []const u8,
) ?flat_log.Term {
    for (substitution.bindings) |binding| {
        if (std.mem.eql(u8, binding.name, name)) return binding.value;
    }
    return null;
}

fn bindTerm(
    arena: Allocator,
    substitution: QuerySubstitution,
    term: QueryTerm,
    value: flat_log.Term,
) !?QuerySubstitution {
    return switch (term) {
        .constant => |constant| if (flat_log.termEql(constant, value))
            substitution
        else
            null,
        .variable => |name| if (lookupBinding(substitution, name)) |bound|
            if (flat_log.termEql(bound, value)) substitution else null
        else blk: {
            const bindings = try arena.alloc(
                QueryBinding,
                substitution.bindings.len + 1,
            );
            @memcpy(bindings[0..substitution.bindings.len], substitution.bindings);
            bindings[bindings.len - 1] = .{ .name = name, .value = value };
            break :blk .{ .bindings = bindings };
        },
    };
}

fn resolveQueryTerm(
    substitution: QuerySubstitution,
    term: QueryTerm,
) ?flat_log.Term {
    return switch (term) {
        .constant => |value| value,
        .variable => |name| lookupBinding(substitution, name),
    };
}

fn encodeRowKey(
    arena: Allocator,
    relation: ?[]const u8,
    values: []const flat_log.Term,
) ![]const u8 {
    var writer: Writer.Allocating = .init(arena);
    if (relation) |name| try flat_log.TermCodecV1.append(
        &writer.writer,
        stringTerm(name),
    );
    for (values) |value| try flat_log.TermCodecV1.append(&writer.writer, value);
    return writer.toOwnedSlice();
}

fn buildBaseRows(
    arena: Allocator,
    state: *DaemonState,
    snapshot: i64,
) ![]const QueryRow {
    var latest = std.StringHashMap(usize).init(arena);
    for (state.events.items, 0..) |event, index| {
        if (event.tx_seq > snapshot) continue;
        const key = try state.eventKeyAlloc(arena, event.triple);
        try latest.put(key, index);
    }

    var rows: std.ArrayList(QueryRow) = .empty;
    var predicates = std.StringHashMap(void).init(arena);
    for (state.events.items, 0..) |event, index| {
        if (event.tx_seq > snapshot) continue;

        const occurrence_values = try arena.alloc(flat_log.Term, 3);
        const occurrence = try occurrenceTerm(arena, state, event);
        const occurrence_triple = tripleValue(occurrence).?;
        occurrence_values[0] = occurrence_triple.slot0;
        occurrence_values[1] = occurrence_triple.slot1;
        occurrence_values[2] = occurrence_triple.slot2;
        try rows.append(arena, .{
            .relation = "occurrence",
            .values = occurrence_values,
        });

        const key = try state.eventKeyAlloc(arena, event.triple);
        const live_index = latest.get(key) orelse continue;
        if (live_index != index or event.operation != .assert) continue;
        const values = try arena.alloc(flat_log.Term, 3);
        values[0] = event.triple.slot0;
        values[1] = event.triple.slot1;
        values[2] = event.triple.slot2;
        try rows.append(arena, .{ .relation = "triple", .values = values });
        if (stringTripleView(event.triple)) |view|
            try predicates.put(view.slot1, {});
    }

    var predicate_iterator = predicates.iterator();
    while (predicate_iterator.next()) |entry| {
        const name = entry.key_ptr.*;
        const identity = try std.fmt.allocPrint(arena, "@{s}", .{name});
        const values = try arena.alloc(flat_log.Term, 5);
        values[0] = stringTerm(identity);
        values[1] = stringTerm(name);
        values[2] = stringTerm(name);
        values[3] = stringTerm(if (state.isSingle(name)) "single" else "multi");
        values[4] = stringTerm(if (state.allLivePredicateValuesRef(name)) "ref" else "literal");
        try rows.append(arena, .{ .relation = "predicate", .values = values });
    }
    return rows.toOwnedSlice(arena);
}

fn relationArity(relation: []const u8, rows: []const QueryRow) ?usize {
    if (std.mem.eql(u8, relation, "triple") or
        std.mem.eql(u8, relation, "occurrence")) return 3;
    if (std.mem.eql(u8, relation, "predicate")) return 5;
    for (rows) |row| if (std.mem.eql(u8, row.relation, relation))
        return row.values.len;
    return null;
}

fn relationMatches(
    substitution: QuerySubstitution,
    clause: QueryRelationClause,
    row: QueryRow,
) bool {
    if (!std.mem.eql(u8, clause.relation, row.relation) or
        clause.args.len != row.values.len) return false;
    for (clause.args, row.values) |term, value| switch (term) {
        .constant => |constant| if (!flat_log.termEql(constant, value))
            return false,
        .variable => |name| if (lookupBinding(substitution, name)) |bound| {
            if (!flat_log.termEql(bound, value)) return false;
        },
    };
    return true;
}

fn unifyRelation(
    arena: Allocator,
    substitution: QuerySubstitution,
    clause: QueryRelationClause,
    row: QueryRow,
) !?QuerySubstitution {
    if (!std.mem.eql(u8, clause.relation, row.relation) or
        clause.args.len != row.values.len) return null;
    var result = substitution;
    for (clause.args, row.values) |term, value|
        result = (try bindTerm(arena, result, term, value)) orelse return null;
    return result;
}

fn termOrder(left: flat_log.Term, right: flat_log.Term) ?std.math.Order {
    const left_number = numericValue(left);
    const right_number = numericValue(right);
    if (left_number != null and right_number != null)
        return std.math.order(left_number.?, right_number.?);
    if (stringValue(left)) |a| if (stringValue(right)) |b|
        return std.mem.order(u8, a, b);
    if (keywordValue(left)) |a| if (keywordValue(right)) |b|
        return std.mem.order(u8, a, b);
    return null;
}

fn predicateMatches(
    operator: PredicateOperator,
    left: flat_log.Term,
    right: flat_log.Term,
) bool {
    return switch (operator) {
        .eq => flat_log.termEql(left, right),
        .ne => !flat_log.termEql(left, right),
        .lt => if (termOrder(left, right)) |order| order == .lt else false,
        .le => if (termOrder(left, right)) |order| order != .gt else false,
        .gt => if (termOrder(left, right)) |order| order == .gt else false,
        .ge => if (termOrder(left, right)) |order| order != .lt else false,
    };
}

fn numericValue(term: flat_log.Term) ?f64 {
    return switch (term) {
        .atom => |atom| switch (atom) {
            .integer => |value| @floatFromInt(value),
            .float => |value| value,
            else => null,
        },
        .triple => null,
    };
}

fn applyFunction(
    operator: FunctionOperator,
    args: []const flat_log.Term,
) ?flat_log.Term {
    if (args.len == 0) return null;
    var result = numericValue(args[0]) orelse return null;
    var all_integer = integerValue(args[0]) != null;
    for (args[1..]) |arg| {
        const value = numericValue(arg) orelse return null;
        all_integer = all_integer and integerValue(arg) != null;
        result = switch (operator) {
            .add => result + value,
            .subtract => result - value,
            .multiply => result * value,
            .divide => if (value == 0) return null else result / value,
            .modulo => if (value == 0) return null else @mod(result, value),
        };
    }
    if (operator == .add and args.len == 1) return args[0];
    if (all_integer and std.math.isFinite(result) and
        result >= @as(f64, @floatFromInt(std.math.minInt(i64))) and
        result <= @as(f64, @floatFromInt(std.math.maxInt(i64))) and
        @trunc(result) == result)
        return integerTerm(@intFromFloat(result));
    return .{ .atom = .{ .float = result } };
}

fn evaluateBody(
    arena: Allocator,
    base: []const QueryRow,
    derived: []const QueryRow,
    body: []const QueryClause,
) ![]const QuerySubstitution {
    var current: std.ArrayList(QuerySubstitution) = .empty;
    try current.append(arena, .{ .bindings = &.{} });

    for (body) |clause| {
        var next: std.ArrayList(QuerySubstitution) = .empty;
        for (current.items) |substitution| switch (clause) {
            .relation => |relation| {
                if (relation.negated) {
                    var matched = false;
                    for (base) |row| if (relationMatches(
                        substitution,
                        relation,
                        row,
                    )) {
                        matched = true;
                        break;
                    };
                    if (!matched) for (derived) |row| if (relationMatches(
                        substitution,
                        relation,
                        row,
                    )) {
                        matched = true;
                        break;
                    };
                    if (!matched) try next.append(arena, substitution);
                } else {
                    for (base) |row| if (try unifyRelation(
                        arena,
                        substitution,
                        relation,
                        row,
                    )) |unified| {
                        if (next.items.len >= max_native_query_rows)
                            return error.QueryWorkLimit;
                        try next.append(arena, unified);
                    };
                    for (derived) |row| if (try unifyRelation(
                        arena,
                        substitution,
                        relation,
                        row,
                    )) |unified| {
                        if (next.items.len >= max_native_query_rows)
                            return error.QueryWorkLimit;
                        try next.append(arena, unified);
                    };
                }
            },
            .predicate => |predicate| {
                const left = resolveQueryTerm(substitution, predicate.left) orelse
                    continue;
                const right = resolveQueryTerm(substitution, predicate.right) orelse
                    continue;
                if (predicateMatches(predicate.operator, left, right))
                    try next.append(arena, substitution);
            },
            .function => |function| {
                const args = try arena.alloc(flat_log.Term, function.args.len);
                var complete = true;
                for (function.args, 0..) |term, index| {
                    args[index] = resolveQueryTerm(substitution, term) orelse {
                        complete = false;
                        break;
                    };
                }
                if (!complete) continue;
                const value = applyFunction(function.operator, args) orelse
                    continue;
                const unified = try bindTerm(
                    arena,
                    substitution,
                    .{ .variable = function.bind },
                    value,
                );
                if (unified) |result| try next.append(arena, result);
            },
        };
        current = next;
    }
    return current.toOwnedSlice(arena);
}

fn groundHead(
    arena: Allocator,
    head: QueryHead,
    substitution: QuerySubstitution,
) ![]const flat_log.Term {
    const values = try arena.alloc(flat_log.Term, head.args.len);
    for (head.args, 0..) |term, index|
        values[index] = resolveQueryTerm(substitution, term) orelse
            return error.InvalidQueryState;
    return values;
}

fn evaluateQuery(
    arena: Allocator,
    state: *DaemonState,
    query: ParsedQuery,
) ![]const QueryRow {
    const base = try buildBaseRows(arena, state, query.snapshot);
    var derived: std.ArrayList(QueryRow) = .empty;
    var seen = std.StringHashMap(void).init(arena);

    for (query.strata) |stratum| {
        while (true) {
            var changed = false;
            for (stratum.rules) |rule| {
                if (relationArity(rule.head.relation, derived.items)) |arity| {
                    if (arity != rule.head.args.len) return error.InvalidQuery;
                }
                const substitutions = try evaluateBody(
                    arena,
                    base,
                    derived.items,
                    rule.body,
                );
                for (substitutions) |substitution| {
                    const values = try groundHead(arena, rule.head, substitution);
                    const key = try encodeRowKey(
                        arena,
                        rule.head.relation,
                        values,
                    );
                    if (seen.contains(key)) continue;
                    if (derived.items.len >= max_native_query_rows)
                        return error.QueryWorkLimit;
                    try seen.put(key, {});
                    try derived.append(arena, .{
                        .relation = rule.head.relation,
                        .values = values,
                    });
                    changed = true;
                }
            }
            if (!changed) break;
        }
    }
    return derived.toOwnedSlice(arena);
}

fn aggregateValue(
    arena: Allocator,
    spec: AggregateSpec,
    rows: []const QueryRow,
) !flat_log.Term {
    if (std.mem.eql(u8, spec.operator, "count"))
        return integerTerm(@intCast(rows.len));
    const argument = spec.argument orelse return error.InvalidAggregate;
    if (rows.len == 0) return error.InvalidAggregate;
    for (rows) |row| if (argument >= row.values.len)
        return error.InvalidAggregate;

    if (std.mem.eql(u8, spec.operator, "count-distinct")) {
        var seen = std.StringHashMap(void).init(arena);
        for (rows) |row| {
            const key = try encodeRowKey(arena, null, &.{row.values[argument]});
            try seen.put(key, {});
        }
        return integerTerm(@intCast(seen.count()));
    }
    if (std.mem.eql(u8, spec.operator, "min") or
        std.mem.eql(u8, spec.operator, "max"))
    {
        var best = rows[0].values[argument];
        for (rows[1..]) |row| {
            const order = termOrder(row.values[argument], best) orelse
                return error.InvalidAggregate;
            if ((std.mem.eql(u8, spec.operator, "min") and order == .lt) or
                (std.mem.eql(u8, spec.operator, "max") and order == .gt))
                best = row.values[argument];
        }
        return best;
    }

    var total: f64 = 0;
    var all_integer = true;
    for (rows) |row| {
        total += numericValue(row.values[argument]) orelse
            return error.InvalidAggregate;
        all_integer = all_integer and integerValue(row.values[argument]) != null;
    }
    if (std.mem.eql(u8, spec.operator, "avg"))
        return .{ .atom = .{ .float = total / @as(f64, @floatFromInt(rows.len)) } };
    if (!std.mem.eql(u8, spec.operator, "sum"))
        return error.InvalidAggregate;
    if (all_integer and total >= @as(f64, @floatFromInt(std.math.minInt(i64))) and
        total <= @as(f64, @floatFromInt(std.math.maxInt(i64))) and
        @trunc(total) == total)
        return integerTerm(@intFromFloat(total));
    return .{ .atom = .{ .float = total } };
}

const AggregateGroup = struct {
    key: []const u8,
    values: []const flat_log.Term,
    rows: std.ArrayList(QueryRow),
};

fn aggregateRows(
    arena: Allocator,
    find: AggregateFind,
    derived: []const QueryRow,
) ![]const QueryRow {
    var groups: std.ArrayList(AggregateGroup) = .empty;
    for (derived) |row| {
        if (!std.mem.eql(u8, row.relation, find.relation)) continue;
        const grouping = try arena.alloc(flat_log.Term, find.grouping.len);
        for (find.grouping, 0..) |source_index, index| {
            if (source_index >= row.values.len) return error.InvalidAggregate;
            grouping[index] = row.values[source_index];
        }
        const key = try encodeRowKey(arena, null, grouping);
        var group_index: ?usize = null;
        for (groups.items, 0..) |group, index| {
            if (std.mem.eql(u8, group.key, key)) {
                group_index = index;
                break;
            }
        }
        if (group_index == null) {
            const rows: std.ArrayList(QueryRow) = .empty;
            try groups.append(arena, .{
                .key = key,
                .values = grouping,
                .rows = rows,
            });
            group_index = groups.items.len - 1;
        }
        try groups.items[group_index.?].rows.append(arena, row);
    }

    var result: std.ArrayList(QueryRow) = .empty;
    for (groups.items) |group| {
        const values = try arena.alloc(
            flat_log.Term,
            group.values.len + find.aggregates.len,
        );
        @memcpy(values[0..group.values.len], group.values);
        for (find.aggregates, 0..) |spec, index|
            values[group.values.len + index] = try aggregateValue(
                arena,
                spec,
                group.rows.items,
            );

        var admitted = true;
        for (find.having) |having| {
            const aggregate = values[group.values.len + having.aggregate_index];
            if (!predicateMatches(having.operator, aggregate, having.value)) {
                admitted = false;
                break;
            }
        }
        if (admitted) try result.append(arena, .{
            .relation = find.relation,
            .values = values,
        });
    }
    return result.toOwnedSlice(arena);
}

fn queryDigest(
    arena: Allocator,
    scratch: Allocator,
    payload: flat_log.Term,
) ![]const u8 {
    const encoded = try flat_log.TermCodecV1.encode(scratch, payload);
    defer scratch.free(encoded);
    var digest: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
    std.crypto.hash.sha2.Sha256.hash(encoded, &digest, .{});
    const hex = try arena.alloc(u8, digest.len * 2);
    _ = std.fmt.bufPrint(hex, "{x}", .{digest}) catch
        return error.OutOfMemory;
    return hex;
}

fn parseQueryCursor(
    arena: Allocator,
    cursor: ?flat_log.Term,
    snapshot: i64,
    digest: []const u8,
) !struct { ordinal: u32, after: ?[]const flat_log.Term } {
    const value = cursor orelse return .{ .ordinal = 0, .after = null };
    const fields = try recordFields(arena, value, "query/cursor", 4);
    const cursor_snapshot = integerValue(fields[0]) orelse
        return error.InvalidPayload;
    const cursor_digest = stringValue(fields[1]) orelse
        return error.InvalidPayload;
    const ordinal = integerValue(fields[2]) orelse return error.InvalidPayload;
    const row_fields = try recordFields(arena, fields[3], "query/row", 1);
    if (cursor_snapshot != snapshot or !std.mem.eql(u8, cursor_digest, digest) or
        ordinal < 0 or ordinal > std.math.maxInt(u32))
        return error.InvalidPayload;
    return .{
        .ordinal = @intCast(ordinal),
        .after = try collectList(arena, row_fields[0]),
    };
}

fn queryRequest(
    arena: Allocator,
    scratch: Allocator,
    state: *DaemonState,
    request: rpc.Request,
) !DispatchResult {
    const query = parseQueryRequest(
        arena,
        request.payload,
        state.version,
    ) catch |err| return switch (err) {
        error.HistoryUnavailable => failure(
            arena,
            "rpc/history",
            false,
            "requested snapshot is unavailable",
            try record(
                arena,
                "rpc/history",
                &.{ integerTerm(-1), integerTerm(0) },
            ),
        ),
        else => failure(
            arena,
            "rpc/invalid-query",
            false,
            "query payload does not match the closed typed IR",
            null,
        ),
    };
    const digest = try queryDigest(arena, scratch, request.payload);
    const cursor = parseQueryCursor(
        arena,
        if (request.page) |page| page.cursor else null,
        query.snapshot,
        digest,
    ) catch return failure(
        arena,
        "rpc/invalid-cursor",
        false,
        "query cursor does not match query or snapshot",
        null,
    );
    const limit = pageLimit(request) catch return failure(
        arena,
        "rpc/invalid-request",
        false,
        "page limit must be 1 through 4096",
        null,
    );

    const derived = evaluateQuery(arena, state, query) catch |err| return switch (err) {
        error.QueryWorkLimit => failure(
            arena,
            "rpc/limit",
            true,
            "query exceeded the intermediate row limit",
            try record(
                arena,
                "rpc/limit",
                &.{
                    keywordTerm("query/rows"),
                    integerTerm(max_native_query_rows),
                    integerTerm(max_native_query_rows),
                },
            ),
        ),
        else => failure(
            arena,
            "rpc/invalid-query",
            false,
            "query could not be evaluated",
            null,
        ),
    };
    const found = switch (query.find) {
        .relation => |relation| blk: {
            var rows: std.ArrayList(QueryRow) = .empty;
            for (derived) |row| if (std.mem.eql(u8, row.relation, relation))
                try rows.append(arena, row);
            break :blk try rows.toOwnedSlice(arena);
        },
        .aggregate => |aggregate| aggregateRows(
            arena,
            aggregate,
            derived,
        ) catch return failure(
            arena,
            "rpc/invalid-query",
            false,
            "aggregate input or operator is invalid",
            null,
        ),
    };

    var keyed: std.ArrayList(KeyedRow) = .empty;
    for (found) |row| try keyed.append(arena, .{
        .values = row.values,
        .key = try encodeRowKey(arena, null, row.values),
    });
    std.mem.sort(KeyedRow, keyed.items, {}, struct {
        fn lessThan(_: void, left: KeyedRow, right: KeyedRow) bool {
            return std.mem.order(u8, left.key, right.key) == .lt;
        }
    }.lessThan);

    const after_key = if (cursor.after) |after|
        try encodeRowKey(arena, null, after)
    else
        null;
    var eligible: std.ArrayList(KeyedRow) = .empty;
    for (keyed.items) |row| {
        if (after_key) |after| {
            if (std.mem.order(u8, row.key, after) != .gt) continue;
        }
        if (eligible.items.len >= limit) break;
        try eligible.append(arena, row);
    }

    const row_terms = try arena.alloc(flat_log.Term, eligible.items.len);
    for (eligible.items, 0..) |row, index| row_terms[index] = try record(
        arena,
        "query/row",
        &.{try list(arena, row.values)},
    );
    const consumed = if (eligible.items.len == 0)
        keyed.items.len
    else blk: {
        const last = eligible.items[eligible.items.len - 1].key;
        var position: usize = 0;
        for (keyed.items, 0..) |row, index| if (std.mem.eql(u8, row.key, last)) {
            position = index + 1;
            break;
        };
        break :blk position;
    };
    const done = consumed >= keyed.items.len;
    const next = if (done or eligible.items.len == 0)
        null
    else
        try record(
            arena,
            "query/cursor",
            &.{
                integerTerm(query.snapshot),
                stringTerm(digest),
                integerTerm(@intCast(cursor.ordinal + 1)),
                row_terms[row_terms.len - 1],
            },
        );
    return .{
        .payload = try record(
            arena,
            "query/rows",
            &.{try list(arena, row_terms)},
        ),
        .page = .{
            .ordinal = cursor.ordinal,
            .next = next,
            .done = done,
        },
    };
}

const SubjectPolicy = enum {
    any,
    existing,
};

const WriteAction = struct {
    operation: EventOperation,
    triple: flat_log.Triple,
    subject_policy: SubjectPolicy,
};

const PreparedAction = struct {
    changed: bool,
    pending_index: ?usize,
};

const EffectiveEvent = struct {
    operation: EventOperation,
    triple: flat_log.Triple,
};

fn parseSubjectPolicy(value: flat_log.Term) !SubjectPolicy {
    if (isKeyword(value, "rpc/subject-any")) return .any;
    if (isKeyword(value, "rpc/subject-existing")) return .existing;
    return error.InvalidPayload;
}

fn parseOptionalFence(arena: Allocator, value: flat_log.Term) !?Fence {
    return switch (try optionValue(value)) {
        .none => null,
        .some => |present| try parseFence(arena, present),
    };
}

fn parseWriteAction(
    arena: Allocator,
    value: flat_log.Term,
    operation: EventOperation,
) !WriteAction {
    const fields = try recordFields(arena, value, "rpc/write", 3);
    return .{
        .operation = operation,
        .triple = tripleValue(fields[0]) orelse return error.InvalidPayload,
        .subject_policy = try parseSubjectPolicy(fields[1]),
    };
}

fn parseBatchAction(arena: Allocator, value: flat_log.Term) !WriteAction {
    const fields = try recordFields(arena, value, "rpc/action", 3);
    const operation = if (isKeyword(fields[0], "rpc/assert"))
        EventOperation.assert
    else if (isKeyword(fields[0], "rpc/retract"))
        EventOperation.retract
    else
        return error.InvalidPayload;
    return .{
        .operation = operation,
        .triple = tripleValue(fields[1]) orelse return error.InvalidPayload,
        .subject_policy = try parseSubjectPolicy(fields[2]),
    };
}

fn sameEffectiveKey(
    allocator: Allocator,
    state: *DaemonState,
    left: flat_log.Triple,
    right: flat_log.Triple,
) !bool {
    const left_key = try state.eventKeyAlloc(allocator, left);
    defer allocator.free(left_key);
    const right_key = try state.eventKeyAlloc(allocator, right);
    defer allocator.free(right_key);
    return std.mem.eql(u8, left_key, right_key);
}

fn effectiveEvent(
    allocator: Allocator,
    state: *DaemonState,
    pending: []const PendingOperation,
    triple: flat_log.Triple,
) !?EffectiveEvent {
    var index = pending.len;
    while (index != 0) {
        index -= 1;
        const candidate = pending[index];
        if (try sameEffectiveKey(
            allocator,
            state,
            candidate.triple,
            triple,
        )) return .{
            .operation = candidate.operation,
            .triple = candidate.triple,
        };
    }
    const key = try state.eventKeyAlloc(allocator, triple);
    defer allocator.free(key);
    const state_index = state.latest.get(key) orelse return null;
    const event = state.events.items[state_index];
    return .{ .operation = event.operation, .triple = event.triple };
}

fn subjectExists(
    allocator: Allocator,
    state: *DaemonState,
    pending: []const PendingOperation,
    subject: flat_log.Term,
) !bool {
    for (pending) |item| {
        if (item.operation == .assert and
            flat_log.termEql(item.triple.slot0, subject)) return true;
    }
    for (state.events.items) |event| {
        if (event.operation != .assert or
            !flat_log.termEql(event.triple.slot0, subject)) continue;
        if (try currentEvent(state, allocator, event)) return true;
    }
    return false;
}

fn cardinalityDeclaration(triple: flat_log.Triple) ?[]const u8 {
    const view = stringTripleView(triple) orelse return null;
    if (!std.mem.eql(u8, view.slot1, "cardinality") or
        !std.mem.eql(u8, view.slot2, "single")) return null;
    return kernel_classify.stripAt(view.slot0);
}

fn projectedCardinalityCollapse(
    arena: Allocator,
    scratch: Allocator,
    state: *DaemonState,
    pending: []const PendingOperation,
    predicate: []const u8,
) !bool {
    var live: std.ArrayList(flat_log.Triple) = .empty;
    for (state.events.items) |event| {
        if (event.operation != .assert or
            !(try currentEvent(state, scratch, event))) continue;
        try live.append(arena, event.triple);
    }
    for (pending) |item| {
        var found: ?usize = null;
        for (live.items, 0..) |candidate, index| {
            if (flat_log.tripleEql(candidate, item.triple)) {
                found = index;
                break;
            }
        }
        if (item.operation == .retract) {
            if (found) |index| _ = live.orderedRemove(index);
        } else if (found == null) {
            try live.append(arena, item.triple);
        }
    }

    var counts = std.StringHashMap(usize).init(scratch);
    defer counts.deinit();
    for (live.items) |triple| {
        const view = stringTripleView(triple) orelse continue;
        if (!std.mem.eql(u8, view.slot1, predicate)) continue;
        const result = try counts.getOrPut(view.slot0);
        if (!result.found_existing) result.value_ptr.* = 0;
        result.value_ptr.* += 1;
        if (result.value_ptr.* > 1) return true;
    }
    return false;
}

fn prepareAction(
    arena: Allocator,
    scratch: Allocator,
    state: *DaemonState,
    pending: *std.ArrayList(PendingOperation),
    action: WriteAction,
) !PreparedAction {
    if (action.subject_policy == .existing and
        !(try subjectExists(
            scratch,
            state,
            pending.items,
            action.triple.slot0,
        ))) return error.MissingSubject;

    const effective = try effectiveEvent(
        scratch,
        state,
        pending.items,
        action.triple,
    );
    if (action.operation == .assert) {
        if (effective) |present| {
            if (present.operation == .assert and
                flat_log.tripleEql(present.triple, action.triple))
                return .{ .changed = false, .pending_index = null };
        }
        const pending_index = pending.items.len;
        try pending.append(arena, .{
            .operation = .assert,
            .triple = action.triple,
        });
        if (cardinalityDeclaration(action.triple)) |predicate| {
            if (try projectedCardinalityCollapse(
                arena,
                scratch,
                state,
                pending.items,
                predicate,
            )) return error.CardinalityCollapse;
        }
        return .{ .changed = true, .pending_index = pending_index };
    }

    const present = effective orelse
        return .{ .changed = false, .pending_index = null };
    if (present.operation != .assert)
        return .{ .changed = false, .pending_index = null };
    const pending_index = pending.items.len;
    try pending.append(arena, .{
        .operation = .retract,
        .triple = present.triple,
    });
    return .{ .changed = true, .pending_index = pending_index };
}

fn actionOccurrences(
    arena: Allocator,
    state: *DaemonState,
    tx_seq: i64,
    pending_index: usize,
    pending_count: usize,
) !flat_log.Term {
    const ordinals = [_]usize{
        pending_index,
        pending_count + pending_index * 2,
        pending_count + pending_index * 2 + 1,
    };
    var occurrences: [3]flat_log.Term = undefined;
    for (ordinals, 0..) |ordinal, index| {
        var found: ?TripleRow = null;
        for (state.events.items) |event| {
            if (event.tx_seq == tx_seq and event.ordinal == ordinal) {
                found = event;
                break;
            }
        }
        occurrences[index] = try occurrenceTerm(
            arena,
            state,
            found orelse return error.CommittedEventMissing,
        );
    }
    return list(arena, &occurrences);
}

fn mutationResult(
    arena: Allocator,
    state: *DaemonState,
    prepared: []const PreparedAction,
    pending_count: usize,
    tx_seq: ?i64,
) !DispatchResult {
    const results = try arena.alloc(flat_log.Term, prepared.len);
    for (prepared, 0..) |item, index| {
        const occurrences = if (item.pending_index) |pending_index|
            try actionOccurrences(
                arena,
                state,
                tx_seq orelse return error.CommittedEventMissing,
                pending_index,
                pending_count,
            )
        else
            try list(arena, &.{});
        results[index] = try record(
            arena,
            "rpc/action-result",
            &.{
                integerTerm(@intCast(index)),
                booleanTerm(item.changed),
                occurrences,
            },
        );
    }
    return .{ .payload = try record(
        arena,
        "rpc/mutation-result",
        &.{try list(arena, results)},
    ) };
}

fn executeActions(
    arena: Allocator,
    scratch: Allocator,
    io: Io,
    canonical_log: []const u8,
    state: *DaemonState,
    actions: []const WriteAction,
    fence: ?Fence,
) !DispatchResult {
    if (actions.len == 0) return failure(
        arena,
        "rpc/invalid-request",
        false,
        "mutation requires at least one action",
        null,
    );
    if (fence) |value| {
        if (!(try fenceCurrent(scratch, io, state, value))) return failure(
            arena,
            "rpc/fence-lost",
            true,
            "lease fence is not current",
            try record(
                arena,
                "rpc/fence-lost",
                &.{
                    stringTerm(value.resource),
                    stringTerm(value.holder),
                    integerTerm(value.epoch),
                },
            ),
        );
    }

    var pending: std.ArrayList(PendingOperation) = .empty;
    var prepared: std.ArrayList(PreparedAction) = .empty;
    try prepared.ensureTotalCapacity(arena, actions.len);
    for (actions) |action| {
        const result = prepareAction(
            arena,
            scratch,
            state,
            &pending,
            action,
        ) catch |err| return switch (err) {
            error.MissingSubject => failure(
                arena,
                "rpc/missing-subject",
                false,
                "subject-existing requires a live subject",
                action.triple.slot0,
            ),
            error.CardinalityCollapse => failure(
                arena,
                "rpc/cardinality-collapse",
                false,
                "single cardinality would collapse live values",
                try tripleTerm(
                    arena,
                    action.triple.slot0,
                    action.triple.slot1,
                    action.triple.slot2,
                ),
            ),
            else => return err,
        };
        prepared.appendAssumeCapacity(result);
    }

    // A declaration can precede its values inside one atomic batch. Recheck
    // the projected final set so that ordering cannot smuggle a collapse past
    // the declaration-local check.
    for (pending.items) |item| {
        const predicate = cardinalityDeclaration(item.triple) orelse continue;
        const declaration = try effectiveEvent(
            scratch,
            state,
            pending.items,
            item.triple,
        );
        if (declaration == null or declaration.?.operation != .assert or
            !flat_log.tripleEql(declaration.?.triple, item.triple)) continue;
        if (try projectedCardinalityCollapse(
            arena,
            scratch,
            state,
            pending.items,
            predicate,
        )) return failure(
            arena,
            "rpc/cardinality-collapse",
            false,
            "single cardinality would collapse live values",
            try tripleTerm(
                arena,
                item.triple.slot0,
                item.triple.slot1,
                item.triple.slot2,
            ),
        );
    }

    if (pending.items.len == 0)
        return mutationResult(arena, state, prepared.items, 0, null);
    if (state.version == std.math.maxInt(i64)) return failure(
        arena,
        "rpc/version-exhausted",
        false,
        "transaction sequence is exhausted",
        null,
    );
    const tx_seq = commitTransaction(
        scratch,
        io,
        canonical_log,
        state,
        pending.items,
    ) catch |err| switch (err) {
        error.VersionExhausted => return failure(
            arena,
            "rpc/version-exhausted",
            false,
            "transaction sequence is exhausted",
            null,
        ),
        else => return err,
    };
    return mutationResult(
        arena,
        state,
        prepared.items,
        pending.items.len,
        tx_seq,
    );
}

fn writeRequest(
    arena: Allocator,
    scratch: Allocator,
    io: Io,
    canonical_log: []const u8,
    state: *DaemonState,
    payload: flat_log.Term,
    operation: EventOperation,
) !DispatchResult {
    const fields = recordFields(arena, payload, "rpc/write", 3) catch
        return failure(
            arena,
            "rpc/invalid-request",
            false,
            "write payload must be rpc/write",
            null,
        );
    const action = parseWriteAction(arena, payload, operation) catch
        return failure(
            arena,
            "rpc/invalid-request",
            false,
            "write fields must be Triple, subject policy, and optional fence",
            null,
        );
    const fence = parseOptionalFence(arena, fields[2]) catch
        return failure(
            arena,
            "rpc/invalid-request",
            false,
            "write fence must be rpc/none or rpc/some(rpc/fence)",
            null,
        );
    return executeActions(
        arena,
        scratch,
        io,
        canonical_log,
        state,
        &.{action},
        fence,
    );
}

fn batchRequest(
    arena: Allocator,
    scratch: Allocator,
    io: Io,
    canonical_log: []const u8,
    state: *DaemonState,
    payload: flat_log.Term,
) !DispatchResult {
    const fields = recordFields(arena, payload, "rpc/batch", 2) catch
        return failure(
            arena,
            "rpc/invalid-request",
            false,
            "batch payload must be rpc/batch",
            null,
        );
    const action_terms = collectList(arena, fields[0]) catch
        return failure(
            arena,
            "rpc/invalid-request",
            false,
            "batch actions must be a closed rpc/list",
            null,
        );
    const actions = try arena.alloc(WriteAction, action_terms.len);
    for (action_terms, 0..) |value, index| {
        actions[index] = parseBatchAction(arena, value) catch
            return failure(
                arena,
                "rpc/invalid-request",
                false,
                "batch action must be rpc/action",
                integerTerm(@intCast(index)),
            );
    }
    const fence = parseOptionalFence(arena, fields[1]) catch
        return failure(
            arena,
            "rpc/invalid-request",
            false,
            "batch fence must be rpc/none or rpc/some(rpc/fence)",
            null,
        );
    return executeActions(
        arena,
        scratch,
        io,
        canonical_log,
        state,
        actions,
        fence,
    );
}

/// Authority: key-sep in fram/kernel_classify.bclj; a clean value never
/// contains U+0001.
const key_sep = "\x01";

fn groupKeyAlloc(
    allocator: Allocator,
    slot0: []const u8,
    slot1: []const u8,
) ![]u8 {
    return std.fmt.allocPrint(
        allocator,
        "\x00{s}" ++ key_sep ++ "{s}",
        .{ slot0, slot1 },
    );
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

test "closed list option and record shapes round trip" {
    var arena_state = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena_state.deinit();
    const arena = arena_state.allocator();

    const fields = [_]flat_log.Term{
        stringTerm("left"),
        try option(arena, integerTerm(7)),
        keywordTerm("rpc/right"),
    };
    const value = try record(arena, "rpc/example", &fields);
    const decoded = try recordFields(arena, value, "rpc/example", 3);
    try std.testing.expectEqualStrings("left", stringValue(decoded[0]).?);
    const present = try optionValue(decoded[1]);
    try std.testing.expectEqual(
        @as(i64, 7),
        integerValue(switch (present) {
            .some => |term| term,
            .none => return error.TestUnexpectedResult,
        }).?,
    );
    try std.testing.expect(isKeyword(decoded[2], "rpc/right"));
}

test "occurrence is a direct coordinate operation proposition triple" {
    var environ = std.process.Environ.Map.init(std.testing.allocator);
    defer environ.deinit();
    var state = try DaemonState.init(
        std.testing.allocator,
        &environ,
        "test-space",
    );
    defer state.deinit();
    var arena_state = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena_state.deinit();
    const arena = arena_state.allocator();

    const event: TripleRow = .{
        .tx_seq = 9,
        .ordinal = 4,
        .operation = .assert,
        .triple = stringTriple("Alice", "email", "alice@example.com"),
    };
    const occurrence = tripleValue(try occurrenceTerm(
        arena,
        &state,
        event,
    )).?;
    try std.testing.expect(isKeyword(occurrence.slot1, "kernel/asserts"));
    const coordinate = tripleValue(occurrence.slot0).?;
    try std.testing.expect(isKeyword(
        coordinate.slot1,
        "kernel/op-ordinal",
    ));
    const transaction = tripleValue(coordinate.slot0).?;
    try std.testing.expectEqualStrings(
        "test-space",
        stringValue(transaction.slot0).?,
    );
    try std.testing.expect(isKeyword(
        transaction.slot1,
        "kernel/tx-sequence",
    ));
    try std.testing.expectEqual(@as(i64, 9), integerValue(transaction.slot2).?);
    try std.testing.expectEqual(@as(i64, 4), integerValue(coordinate.slot2).?);
    try std.testing.expect(flat_log.tripleEql(
        event.triple,
        tripleValue(occurrence.slot2).?,
    ));
}
