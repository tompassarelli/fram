const std = @import("std");
const log = @import("log.zig");
const rpc = @import("rpc.zig");

const Allocator = std.mem.Allocator;
const Io = std.Io;

fn stringTerm(value: []const u8) log.Term {
    return .{ .atom = .{ .string = value } };
}

fn integerTerm(value: i64) log.Term {
    return .{ .atom = .{ .integer = value } };
}

fn booleanTerm(value: bool) log.Term {
    return .{ .atom = .{ .boolean = value } };
}

fn keywordTerm(value: []const u8) log.Term {
    return .{ .atom = .{ .keyword = value } };
}

fn stringValue(term: log.Term) ?[]const u8 {
    return switch (term) {
        .atom => |atom| switch (atom) {
            .string => |value| value,
            else => null,
        },
        .triple => null,
    };
}

fn integerValue(term: log.Term) ?i64 {
    return switch (term) {
        .atom => |atom| switch (atom) {
            .integer => |value| value,
            else => null,
        },
        .triple => null,
    };
}

fn booleanValue(term: log.Term) ?bool {
    return switch (term) {
        .atom => |atom| switch (atom) {
            .boolean => |value| value,
            else => null,
        },
        .triple => null,
    };
}

fn keywordValue(term: log.Term) ?[]const u8 {
    return switch (term) {
        .atom => |atom| switch (atom) {
            .keyword => |value| value,
            else => null,
        },
        .triple => null,
    };
}

fn isKeyword(term: log.Term, expected: []const u8) bool {
    const value = keywordValue(term) orelse return false;
    return std.mem.eql(u8, value, expected);
}

fn tripleValue(term: log.Term) ?log.Triple {
    return switch (term) {
        .triple => |value| value.*,
        .atom => null,
    };
}

fn tripleTerm(
    arena: Allocator,
    slot0: log.Term,
    slot1: log.Term,
    slot2: log.Term,
) !log.Term {
    const value = try arena.create(log.Triple);
    value.* = .{ .slot0 = slot0, .slot1 = slot1, .slot2 = slot2 };
    return .{ .triple = value };
}

fn stringTriple(
    arena: Allocator,
    slot0: []const u8,
    slot1: []const u8,
    slot2: []const u8,
) !log.Term {
    return tripleTerm(
        arena,
        stringTerm(slot0),
        stringTerm(slot1),
        stringTerm(slot2),
    );
}

fn list(arena: Allocator, items: []const log.Term) !log.Term {
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

fn collectList(arena: Allocator, root: log.Term) ![]const log.Term {
    var items: std.ArrayList(log.Term) = .empty;
    var cursor = root;
    while (!isKeyword(cursor, "rpc/list-end")) {
        const cell = tripleValue(cursor) orelse return error.InvalidResponse;
        if (!isKeyword(cell.slot0, "rpc/list")) return error.InvalidResponse;
        try items.append(arena, cell.slot1);
        cursor = cell.slot2;
    }
    return items.toOwnedSlice(arena);
}

fn record(
    arena: Allocator,
    tag: []const u8,
    fields: []const log.Term,
) !log.Term {
    return tripleTerm(
        arena,
        keywordTerm(tag),
        try list(arena, fields),
        keywordTerm("rpc/record"),
    );
}

fn recordFields(
    arena: Allocator,
    value: log.Term,
    tag: []const u8,
    expected: usize,
) ![]const log.Term {
    const triple = tripleValue(value) orelse return error.InvalidResponse;
    if (!isKeyword(triple.slot0, tag) or
        !isKeyword(triple.slot2, "rpc/record")) return error.InvalidResponse;
    const fields = try collectList(arena, triple.slot1);
    if (fields.len != expected) return error.InvalidResponse;
    return fields;
}

fn option(arena: Allocator, value: ?log.Term) !log.Term {
    if (value) |present| return tripleTerm(
        arena,
        keywordTerm("rpc/some"),
        present,
        keywordTerm("rpc/option"),
    );
    return keywordTerm("rpc/none");
}

fn readFrame(
    allocator: Allocator,
    reader: *std.Io.Reader,
) ![]u8 {
    var header: [rpc.fixed_header_bytes]u8 = undefined;
    try reader.readSliceAll(&header);
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
    try reader.readSliceAll(bytes[rpc.fixed_header_bytes..]);
    return bytes;
}

const Peer = struct {
    allocator: Allocator,
    io: Io,
    port: u16,
    space: []const u8,
    next_request_id: u64 = 1,

    fn exchange(
        peer: *Peer,
        op: []const u8,
        expected_version: ?i64,
        page: ?rpc.PageRequest,
        payload: log.Term,
    ) !rpc.DecodedFrame {
        const request_id = peer.next_request_id;
        peer.next_request_id += 1;
        const encoded = try rpc.encodeFrame(peer.allocator, .{
            .request_id = request_id,
            .message = .{ .request = .{
                .space = stringTerm(peer.space),
                .op = keywordTerm(op),
                .expected_version = expected_version,
                .page = page,
                .timeout_ms = 5000,
                .payload = payload,
            } },
        });
        defer peer.allocator.free(encoded);

        var address: std.Io.net.IpAddress = .{
            .ip4 = std.Io.net.Ip4Address.loopback(peer.port),
        };
        const stream = try address.connect(peer.io, .{ .mode = .stream });
        defer stream.close(peer.io);
        var write_buffer: [8192]u8 = undefined;
        var writer = stream.writer(peer.io, &write_buffer);
        try writer.interface.writeAll(encoded);
        try writer.interface.flush();

        var read_buffer: [8192]u8 = undefined;
        var reader = stream.reader(peer.io, &read_buffer);
        const response_bytes = try readFrame(
            peer.allocator,
            &reader.interface,
        );
        defer peer.allocator.free(response_bytes);
        var decoded = try rpc.decodeFrame(peer.allocator, response_bytes);
        errdefer decoded.deinit();
        if (decoded.request_id != request_id or
            decoded.message != .response) return error.InvalidResponse;
        return decoded;
    }

    fn sendOnly(
        peer: *Peer,
        frame: rpc.Frame,
        prefix_len: ?usize,
    ) !void {
        const encoded = try rpc.encodeFrame(peer.allocator, frame);
        defer peer.allocator.free(encoded);
        const bytes = encoded[0..@min(prefix_len orelse encoded.len, encoded.len)];
        var address: std.Io.net.IpAddress = .{
            .ip4 = std.Io.net.Ip4Address.loopback(peer.port),
        };
        const stream = try address.connect(peer.io, .{ .mode = .stream });
        defer stream.close(peer.io);
        var write_buffer: [8192]u8 = undefined;
        var writer = stream.writer(peer.io, &write_buffer);
        try writer.interface.writeAll(bytes);
        try writer.interface.flush();
    }
};

fn response(frame: *rpc.DecodedFrame) !rpc.Response {
    return switch (frame.message) {
        .response => |value| value,
        else => error.InvalidResponse,
    };
}

fn expectSuccess(frame: *rpc.DecodedFrame, version: i64) !rpc.Response {
    const value = try response(frame);
    if (value.@"error" != null or value.served_version != version)
        return error.ProtocolAssertion;
    return value;
}

fn expectError(
    frame: *rpc.DecodedFrame,
    code: []const u8,
    version: i64,
) !void {
    const value = try response(frame);
    if (value.served_version != version) return error.ProtocolAssertion;
    const failure = value.@"error" orelse return error.ProtocolAssertion;
    if (!isKeyword(failure.code, code)) return error.ProtocolAssertion;
}

fn mutationChanged(
    arena: Allocator,
    payload: log.Term,
    expected: []const bool,
) !void {
    const result = try recordFields(
        arena,
        payload,
        "rpc/mutation-result",
        1,
    );
    const actions = try collectList(arena, result[0]);
    if (actions.len != expected.len) return error.ProtocolAssertion;
    for (actions, expected, 0..) |action, changed, index| {
        const fields = try recordFields(
            arena,
            action,
            "rpc/action-result",
            3,
        );
        if (integerValue(fields[0]) != @as(i64, @intCast(index)) or
            booleanValue(fields[1]) != changed) return error.ProtocolAssertion;
        const occurrences = try collectList(arena, fields[2]);
        if (changed) {
            if (occurrences.len != 3) return error.ProtocolAssertion;
            const binding = tripleValue(occurrences[0]) orelse
                return error.ProtocolAssertion;
            if (!(isKeyword(binding.slot1, "kernel/asserts") or
                isKeyword(binding.slot1, "kernel/retracts")))
                return error.ProtocolAssertion;
        } else if (occurrences.len != 0) return error.ProtocolAssertion;
    }
}

fn writePayload(
    arena: Allocator,
    triple: log.Term,
    policy: []const u8,
    fence: ?log.Term,
) !log.Term {
    return record(
        arena,
        "rpc/write",
        &.{
            triple,
            keywordTerm(policy),
            try option(arena, fence),
        },
    );
}

fn batchAction(
    arena: Allocator,
    operation: []const u8,
    triple: log.Term,
    policy: []const u8,
) !log.Term {
    return record(
        arena,
        "rpc/action",
        &.{ keywordTerm(operation), triple, keywordTerm(policy) },
    );
}

fn pattern(
    arena: Allocator,
    slot0: ?log.Term,
    slot1: ?log.Term,
    slot2: ?log.Term,
) !log.Term {
    return record(
        arena,
        "rpc/triple-pattern",
        &.{
            try option(arena, slot0),
            try option(arena, slot1),
            try option(arena, slot2),
        },
    );
}

fn queryPayload(arena: Allocator) !log.Term {
    const var_subject = try record(
        arena,
        "query/var",
        &.{stringTerm("subject")},
    );
    const var_email = try record(
        arena,
        "query/var",
        &.{stringTerm("email")},
    );
    const const_email = try record(
        arena,
        "query/const",
        &.{stringTerm("email")},
    );
    const head = try record(
        arena,
        "query/head",
        &.{
            stringTerm("emails"),
            try list(arena, &.{ var_subject, var_email }),
        },
    );
    const clause = try record(
        arena,
        "query/relation",
        &.{
            stringTerm("triple"),
            try list(arena, &.{ var_subject, const_email, var_email }),
            booleanTerm(false),
        },
    );
    const rule = try record(
        arena,
        "query/rule",
        &.{ head, try list(arena, &.{clause}) },
    );
    const stratum = try record(
        arena,
        "query/stratum",
        &.{try list(arena, &.{rule})},
    );
    const find = try record(
        arena,
        "query/find-relation",
        &.{stringTerm("emails")},
    );
    const plan = try record(
        arena,
        "query/plan",
        &.{ find, try list(arena, &.{stratum}) },
    );
    return record(
        arena,
        "query/request",
        &.{ plan, keywordTerm("query/current") },
    );
}

fn aggregateQueryPayload(arena: Allocator) !log.Term {
    const var_subject = try record(
        arena,
        "query/var",
        &.{stringTerm("subject")},
    );
    const var_value = try record(
        arena,
        "query/var",
        &.{stringTerm("value")},
    );
    const const_tag = try record(
        arena,
        "query/const",
        &.{stringTerm("tag")},
    );
    const head = try record(
        arena,
        "query/head",
        &.{
            stringTerm("tagged"),
            try list(arena, &.{var_subject}),
        },
    );
    const clause = try record(
        arena,
        "query/relation",
        &.{
            stringTerm("triple"),
            try list(arena, &.{ var_subject, const_tag, var_value }),
            booleanTerm(false),
        },
    );
    const rule = try record(
        arena,
        "query/rule",
        &.{ head, try list(arena, &.{clause}) },
    );
    const stratum = try record(
        arena,
        "query/stratum",
        &.{try list(arena, &.{rule})},
    );
    const aggregate = try record(
        arena,
        "query/aggregate",
        &.{ keywordTerm("count"), try option(arena, null) },
    );
    const having = try record(
        arena,
        "query/having",
        &.{ keywordTerm("ge"), integerTerm(0), integerTerm(1) },
    );
    const find = try record(
        arena,
        "query/find-aggregate",
        &.{
            stringTerm("tagged"),
            try list(arena, &.{integerTerm(0)}),
            try list(arena, &.{aggregate}),
            try list(arena, &.{having}),
        },
    );
    const plan = try record(
        arena,
        "query/plan",
        &.{ find, try list(arena, &.{stratum}) },
    );
    return record(
        arena,
        "query/request",
        &.{ plan, keywordTerm("query/current") },
    );
}

fn extractFence(arena: Allocator, payload: log.Term) !log.Term {
    const grant = try recordFields(arena, payload, "lease/grant", 2);
    _ = try recordFields(arena, grant[0], "rpc/fence", 3);
    return grant[0];
}

fn bootstrap(
    allocator: Allocator,
    io: Io,
    port: u16,
    space: []const u8,
) !void {
    var peer: Peer = .{
        .allocator = allocator,
        .io = io,
        .port = port,
        .space = space,
    };

    var arena_state = std.heap.ArenaAllocator.init(allocator);
    defer arena_state.deinit();
    const arena = arena_state.allocator();

    var version = try peer.exchange(
        "rpc/version",
        null,
        null,
        keywordTerm("rpc/unit"),
    );
    defer version.deinit();
    _ = try expectSuccess(&version, 0);

    var status = try peer.exchange(
        "rpc/status",
        null,
        null,
        keywordTerm("rpc/unit"),
    );
    defer status.deinit();
    const status_response = try expectSuccess(&status, 0);
    const status_fields = try recordFields(
        arena,
        status_response.payload orelse return error.ProtocolAssertion,
        "rpc/status",
        3,
    );
    if (!isKeyword(status_fields[0], "rpc/ready") or
        !isKeyword(status_fields[2], "rpc/zig"))
        return error.ProtocolAssertion;

    const alice_email = try stringTriple(
        arena,
        "Alice",
        "email",
        "alice@example.com",
    );
    var asserted = try peer.exchange(
        "rpc/assert",
        0,
        null,
        try writePayload(arena, alice_email, "rpc/subject-any", null),
    );
    defer asserted.deinit();
    const asserted_response = try expectSuccess(&asserted, 1);
    try mutationChanged(
        arena,
        asserted_response.payload orelse return error.ProtocolAssertion,
        &.{true},
    );

    var duplicate = try peer.exchange(
        "rpc/assert",
        1,
        null,
        try writePayload(arena, alice_email, "rpc/subject-any", null),
    );
    defer duplicate.deinit();
    const duplicate_response = try expectSuccess(&duplicate, 1);
    try mutationChanged(
        arena,
        duplicate_response.payload orelse return error.ProtocolAssertion,
        &.{false},
    );

    var conflict = try peer.exchange(
        "rpc/assert",
        0,
        null,
        try writePayload(
            arena,
            try stringTriple(arena, "Never", "lands", "stale"),
            "rpc/subject-any",
            null,
        ),
    );
    defer conflict.deinit();
    try expectError(&conflict, "rpc/conflict", 1);

    const actions = [_]log.Term{
        try batchAction(
            arena,
            "rpc/assert",
            try stringTriple(arena, "Alice", "note", "hello"),
            "rpc/subject-existing",
        ),
        try batchAction(
            arena,
            "rpc/assert",
            try stringTriple(arena, "Alice", "tag", "person"),
            "rpc/subject-existing",
        ),
    };
    const batch_payload = try record(
        arena,
        "rpc/batch",
        &.{ try list(arena, &actions), try option(arena, null) },
    );
    var batch = try peer.exchange("rpc/batch", 1, null, batch_payload);
    defer batch.deinit();
    const batch_response = try expectSuccess(&batch, 2);
    try mutationChanged(
        arena,
        batch_response.payload orelse return error.ProtocolAssertion,
        &.{ true, true },
    );

    const atomic_actions = [_]log.Term{
        try batchAction(
            arena,
            "rpc/assert",
            try stringTriple(arena, "Alice", "must-not", "land"),
            "rpc/subject-any",
        ),
        try batchAction(
            arena,
            "rpc/assert",
            try stringTriple(arena, "Missing", "note", "reject"),
            "rpc/subject-existing",
        ),
    };
    var atomic = try peer.exchange(
        "rpc/batch",
        2,
        null,
        try record(
            arena,
            "rpc/batch",
            &.{ try list(arena, &atomic_actions), try option(arena, null) },
        ),
    );
    defer atomic.deinit();
    try expectError(&atomic, "rpc/missing-subject", 2);

    const alice_pattern = try pattern(
        arena,
        stringTerm("Alice"),
        null,
        null,
    );
    var scan = try peer.exchange(
        "rpc/scan",
        null,
        .{ .limit = 1, .cursor = null },
        alice_pattern,
    );
    defer scan.deinit();
    const scan_response = try expectSuccess(&scan, 2);
    const triples_fields = try recordFields(
        arena,
        scan_response.payload orelse return error.ProtocolAssertion,
        "rpc/triples",
        1,
    );
    const triples = try collectList(arena, triples_fields[0]);
    if (triples.len != 1 or scan_response.page == null or
        scan_response.page.?.done or scan_response.page.?.next == null)
        return error.ProtocolAssertion;

    var scan_tail = try peer.exchange(
        "rpc/scan",
        null,
        .{
            .limit = 64,
            .cursor = scan_response.page.?.next,
        },
        alice_pattern,
    );
    defer scan_tail.deinit();
    const scan_tail_response = try expectSuccess(&scan_tail, 2);
    const scan_tail_fields = try recordFields(
        arena,
        scan_tail_response.payload orelse return error.ProtocolAssertion,
        "rpc/triples",
        1,
    );
    const tail_triples = try collectList(arena, scan_tail_fields[0]);
    if (tail_triples.len != 2 or scan_tail_response.page == null or
        !scan_tail_response.page.?.done or
        scan_tail_response.page.?.ordinal != 1)
        return error.ProtocolAssertion;

    var occurrences = try peer.exchange(
        "rpc/occurrences",
        null,
        .{ .limit = 64, .cursor = null },
        keywordTerm("rpc/unit"),
    );
    defer occurrences.deinit();
    const occurrence_response = try expectSuccess(&occurrences, 2);
    const occurrence_fields = try recordFields(
        arena,
        occurrence_response.payload orelse return error.ProtocolAssertion,
        "rpc/occurrences",
        1,
    );
    const occurrence_rows = try collectList(arena, occurrence_fields[0]);
    if (occurrence_rows.len != 9) return error.ProtocolAssertion;
    const direct = tripleValue(occurrence_rows[0]) orelse
        return error.ProtocolAssertion;
    if (!isKeyword(direct.slot1, "kernel/asserts"))
        return error.ProtocolAssertion;

    var query = try peer.exchange(
        "rpc/query",
        null,
        .{ .limit = 16, .cursor = null },
        try queryPayload(arena),
    );
    defer query.deinit();
    const query_response = try expectSuccess(&query, 2);
    const query_fields = try recordFields(
        arena,
        query_response.payload orelse return error.ProtocolAssertion,
        "query/rows",
        1,
    );
    const query_rows = try collectList(arena, query_fields[0]);
    if (query_rows.len != 1) return error.ProtocolAssertion;

    var aggregate = try peer.exchange(
        "rpc/query",
        null,
        .{ .limit = 16, .cursor = null },
        try aggregateQueryPayload(arena),
    );
    defer aggregate.deinit();
    const aggregate_response = try expectSuccess(&aggregate, 2);
    const aggregate_fields = try recordFields(
        arena,
        aggregate_response.payload orelse return error.ProtocolAssertion,
        "query/rows",
        1,
    );
    const aggregate_rows = try collectList(arena, aggregate_fields[0]);
    if (aggregate_rows.len != 1) return error.ProtocolAssertion;
    const aggregate_row = try recordFields(
        arena,
        aggregate_rows[0],
        "query/row",
        1,
    );
    const aggregate_values = try collectList(arena, aggregate_row[0]);
    if (aggregate_values.len != 2 or
        !std.mem.eql(
            u8,
            stringValue(aggregate_values[0]) orelse "",
            "Alice",
        ) or integerValue(aggregate_values[1]) != 1)
        return error.ProtocolAssertion;

    const ordered_schema_actions = [_]log.Term{
        try batchAction(
            arena,
            "rpc/assert",
            try stringTriple(
                arena,
                "@p_batch_collapse",
                "cardinality",
                "single",
            ),
            "rpc/subject-any",
        ),
        try batchAction(
            arena,
            "rpc/assert",
            try stringTriple(arena, "BatchSubject", "p_batch_collapse", "one"),
            "rpc/subject-any",
        ),
        try batchAction(
            arena,
            "rpc/assert",
            try stringTriple(arena, "BatchSubject", "p_batch_collapse", "two"),
            "rpc/subject-any",
        ),
    };
    var ordered_schema = try peer.exchange(
        "rpc/batch",
        2,
        null,
        try record(
            arena,
            "rpc/batch",
            &.{
                try list(arena, &ordered_schema_actions),
                try option(arena, null),
            },
        ),
    );
    defer ordered_schema.deinit();
    try expectError(
        &ordered_schema,
        "rpc/cardinality-collapse",
        2,
    );

    var acquired = try peer.exchange(
        "rpc/lease-acquire",
        null,
        null,
        try record(
            arena,
            "lease/acquire",
            &.{ stringTerm("bootstrap"), stringTerm("client"), integerTerm(60_000) },
        ),
    );
    defer acquired.deinit();
    const acquired_response = try expectSuccess(&acquired, 3);
    const first_fence = try extractFence(
        arena,
        acquired_response.payload orelse return error.ProtocolAssertion,
    );

    var checked = try peer.exchange(
        "rpc/lease-check",
        null,
        null,
        first_fence,
    );
    defer checked.deinit();
    const checked_response = try expectSuccess(&checked, 3);
    const check_fields = try recordFields(
        arena,
        checked_response.payload orelse return error.ProtocolAssertion,
        "lease/check",
        2,
    );
    if (booleanValue(check_fields[0]) != true)
        return error.ProtocolAssertion;

    var renewed = try peer.exchange(
        "rpc/lease-renew",
        null,
        null,
        try record(
            arena,
            "lease/renew",
            &.{ first_fence, integerTerm(60_000) },
        ),
    );
    defer renewed.deinit();
    const renewed_response = try expectSuccess(&renewed, 4);
    const renewed_fence = try extractFence(
        arena,
        renewed_response.payload orelse return error.ProtocolAssertion,
    );

    var stale_check = try peer.exchange(
        "rpc/lease-check",
        null,
        null,
        first_fence,
    );
    defer stale_check.deinit();
    const stale_response = try expectSuccess(&stale_check, 4);
    const stale_fields = try recordFields(
        arena,
        stale_response.payload orelse return error.ProtocolAssertion,
        "lease/check",
        2,
    );
    if (booleanValue(stale_fields[0]) != false)
        return error.ProtocolAssertion;

    var fenced_write = try peer.exchange(
        "rpc/assert",
        4,
        null,
        try writePayload(
            arena,
            try stringTriple(arena, "Alice", "fenced", "accepted"),
            "rpc/subject-existing",
            renewed_fence,
        ),
    );
    defer fenced_write.deinit();
    const fenced_response = try expectSuccess(&fenced_write, 5);
    try mutationChanged(
        arena,
        fenced_response.payload orelse return error.ProtocolAssertion,
        &.{true},
    );

    var stale_write = try peer.exchange(
        "rpc/assert",
        5,
        null,
        try writePayload(
            arena,
            try stringTriple(arena, "Alice", "fenced", "rejected"),
            "rpc/subject-existing",
            first_fence,
        ),
    );
    defer stale_write.deinit();
    try expectError(&stale_write, "rpc/fence-lost", 5);

    var released = try peer.exchange(
        "rpc/lease-release",
        null,
        null,
        renewed_fence,
    );
    defer released.deinit();
    const released_response = try expectSuccess(&released, 6);
    const released_fields = try recordFields(
        arena,
        released_response.payload orelse return error.ProtocolAssertion,
        "lease/released",
        1,
    );
    if (booleanValue(released_fields[0]) != true)
        return error.ProtocolAssertion;

    const multi_one = try stringTriple(arena, "Subject", "p_multi", "one");
    const multi_two = try stringTriple(arena, "Subject", "p_multi", "two");
    for ([_]log.Term{ multi_one, multi_two }, 0..) |triple, index| {
        var added = try peer.exchange(
            "rpc/assert",
            6 + @as(i64, @intCast(index)),
            null,
            try writePayload(arena, triple, "rpc/subject-any", null),
        );
        defer added.deinit();
        _ = try expectSuccess(&added, 7 + @as(i64, @intCast(index)));
    }

    var collapse = try peer.exchange(
        "rpc/assert",
        8,
        null,
        try writePayload(
            arena,
            try stringTriple(arena, "@p_multi", "cardinality", "single"),
            "rpc/subject-any",
            null,
        ),
    );
    defer collapse.deinit();
    try expectError(&collapse, "rpc/cardinality-collapse", 8);

    var validate = try peer.exchange(
        "rpc/validate",
        null,
        null,
        keywordTerm("rpc/unit"),
    );
    defer validate.deinit();
    const validate_response = try expectSuccess(&validate, 8);
    const validation_fields = try recordFields(
        arena,
        validate_response.payload orelse return error.ProtocolAssertion,
        "rpc/validation",
        2,
    );
    if (booleanValue(validation_fields[0]) != true)
        return error.ProtocolAssertion;

    var unsupported = try peer.exchange(
        "rpc/admin",
        null,
        null,
        keywordTerm("rpc/unit"),
    );
    defer unsupported.deinit();
    try expectError(&unsupported, "rpc/unsupported-operation", 8);

    const cancel_id = peer.next_request_id;
    peer.next_request_id += 1;
    try peer.sendOnly(.{
        .request_id = cancel_id,
        .message = .{ .cancel = {} },
    }, null);

    const truncated_id = peer.next_request_id;
    peer.next_request_id += 1;
    const truncated_payload = try writePayload(
        arena,
        try stringTriple(arena, "Never", "partial", "request"),
        "rpc/subject-any",
        null,
    );
    try peer.sendOnly(.{
        .request_id = truncated_id,
        .message = .{ .request = .{
            .space = stringTerm(space),
            .op = keywordTerm("rpc/assert"),
            .expected_version = 8,
            .page = null,
            .timeout_ms = null,
            .payload = truncated_payload,
        } },
    }, rpc.fixed_header_bytes + 2);

    var after_partial = try peer.exchange(
        "rpc/version",
        null,
        null,
        keywordTerm("rpc/unit"),
    );
    defer after_partial.deinit();
    _ = try expectSuccess(&after_partial, 8);

    const disconnected_id = peer.next_request_id;
    peer.next_request_id += 1;
    try peer.sendOnly(.{
        .request_id = disconnected_id,
        .message = .{ .request = .{
            .space = stringTerm(space),
            .op = keywordTerm("rpc/assert"),
            .expected_version = 8,
            .page = null,
            .timeout_ms = null,
            .payload = try writePayload(
                arena,
                try stringTriple(arena, "Alice", "disconnect", "durable"),
                "rpc/subject-existing",
                null,
            ),
        } },
    }, null);

    var after_disconnect = try peer.exchange(
        "rpc/version",
        null,
        null,
        keywordTerm("rpc/unit"),
    );
    defer after_disconnect.deinit();
    _ = try expectSuccess(&after_disconnect, 9);
}

const ModelOperation = enum {
    assert,
    retract,
};

const ModelAction = struct {
    operation: ModelOperation,
    slot0: []const u8,
    slot1: []const u8,
    slot2: []const u8,
};

const ModelTriple = struct {
    slot0: []const u8,
    slot1: []const u8,
    slot2: []const u8,
};

const Model = struct {
    allocator: Allocator,
    triples: std.ArrayList(ModelTriple) = .empty,
    singles: std.StringHashMap(void),
    predicates: std.StringHashMap(void),
    version: i64 = 0,

    fn init(allocator: Allocator) Model {
        return .{
            .allocator = allocator,
            .singles = std.StringHashMap(void).init(allocator),
            .predicates = std.StringHashMap(void).init(allocator),
        };
    }

    fn deinit(model: *Model) void {
        model.predicates.deinit();
        model.singles.deinit();
        model.triples.deinit(model.allocator);
        model.* = undefined;
    }

    fn clone(model: *const Model) !Model {
        var copy = Model.init(model.allocator);
        errdefer copy.deinit();
        try copy.triples.appendSlice(model.allocator, model.triples.items);
        var predicate_iterator = model.predicates.iterator();
        while (predicate_iterator.next()) |entry|
            try copy.predicates.put(entry.key_ptr.*, {});
        copy.version = model.version;
        try copy.rebuildSingles();
        return copy;
    }

    fn rebuildSingles(model: *Model) !void {
        model.singles.clearRetainingCapacity();
        for (model.triples.items) |triple| {
            if (!std.mem.eql(u8, triple.slot1, "cardinality") or
                !std.mem.eql(u8, triple.slot2, "single")) continue;
            const predicate = if (triple.slot0.len != 0 and
                triple.slot0[0] == '@')
                triple.slot0[1..]
            else
                triple.slot0;
            try model.singles.put(predicate, {});
        }
    }

    fn exactIndex(model: *const Model, action: ModelAction) ?usize {
        for (model.triples.items, 0..) |triple, index| {
            if (std.mem.eql(u8, triple.slot0, action.slot0) and
                std.mem.eql(u8, triple.slot1, action.slot1) and
                std.mem.eql(u8, triple.slot2, action.slot2)) return index;
        }
        return null;
    }

    fn groupIndex(model: *const Model, action: ModelAction) ?usize {
        for (model.triples.items, 0..) |triple, index| {
            if (std.mem.eql(u8, triple.slot0, action.slot0) and
                std.mem.eql(u8, triple.slot1, action.slot1)) return index;
        }
        return null;
    }

    fn declarationCollapse(
        model: *const Model,
        action: ModelAction,
    ) bool {
        if (action.operation != .assert or
            !std.mem.eql(u8, action.slot1, "cardinality") or
            !std.mem.eql(u8, action.slot2, "single")) return false;
        const predicate = if (action.slot0.len != 0 and action.slot0[0] == '@')
            action.slot0[1..]
        else
            action.slot0;
        var counts = std.StringHashMap(usize).init(model.allocator);
        defer counts.deinit();
        for (model.triples.items) |triple| {
            if (!std.mem.eql(u8, triple.slot1, predicate)) continue;
            const result = counts.getOrPut(triple.slot0) catch
                return true;
            if (!result.found_existing) result.value_ptr.* = 0;
            result.value_ptr.* += 1;
            if (result.value_ptr.* > 1) return true;
        }
        return false;
    }

    fn apply(model: *Model, action: ModelAction) !bool {
        try model.predicates.put(action.slot1, {});
        if (model.declarationCollapse(action))
            return error.CardinalityCollapse;
        if (action.operation == .assert) {
            if (model.exactIndex(action) != null) return false;
            if (model.singles.contains(action.slot1)) {
                while (model.groupIndex(action)) |index|
                    _ = model.triples.orderedRemove(index);
            }
            try model.triples.append(model.allocator, .{
                .slot0 = action.slot0,
                .slot1 = action.slot1,
                .slot2 = action.slot2,
            });
            try model.rebuildSingles();
            return true;
        }
        const index = if (model.singles.contains(action.slot1))
            model.groupIndex(action)
        else
            model.exactIndex(action);
        if (index == null) return false;
        _ = model.triples.orderedRemove(index.?);
        try model.rebuildSingles();
        return true;
    }
};

const ParsedFact = struct {
    predicate: []const u8,
    value: []const u8,
    local_base: ?i64,
};

fn splitFields(
    arena: Allocator,
    line: []const u8,
) ![]const []const u8 {
    var fields: std.ArrayList([]const u8) = .empty;
    var iterator = std.mem.splitScalar(u8, line, '\t');
    while (iterator.next()) |field| try fields.append(arena, field);
    return fields.toOwnedSlice(arena);
}

fn parseFact(token: []const u8) !ParsedFact {
    const equals = std.mem.indexOfScalar(u8, token, '=') orelse
        return error.InvalidCorpus;
    if (equals == 0 or equals + 1 >= token.len)
        return error.InvalidCorpus;
    const encoded = token[equals + 1 ..];
    const at = std.mem.lastIndexOfScalar(u8, encoded, '@');
    if (at) |index| {
        if (index + 1 >= encoded.len) return error.InvalidCorpus;
        const base = std.fmt.parseInt(i64, encoded[index + 1 ..], 10) catch
            return .{
                .predicate = token[0..equals],
                .value = encoded,
                .local_base = null,
            };
        return .{
            .predicate = token[0..equals],
            .value = encoded[0..index],
            .local_base = base,
        };
    }
    return .{
        .predicate = token[0..equals],
        .value = encoded,
        .local_base = null,
    };
}

fn parseFacts(
    arena: Allocator,
    encoded: []const u8,
) ![]const ParsedFact {
    var facts: std.ArrayList(ParsedFact) = .empty;
    if (encoded.len == 0) return facts.toOwnedSlice(arena);
    var iterator = std.mem.splitScalar(u8, encoded, '|');
    while (iterator.next()) |token|
        try facts.append(arena, try parseFact(token));
    return facts.toOwnedSlice(arena);
}

fn modelActionTerm(
    arena: Allocator,
    action: ModelAction,
    malformed_local_base: ?i64,
) !log.Term {
    const operation = keywordTerm(if (action.operation == .assert)
        "rpc/assert"
    else
        "rpc/retract");
    const triple = try stringTriple(
        arena,
        action.slot0,
        action.slot1,
        action.slot2,
    );
    if (malformed_local_base) |base| return record(
        arena,
        "rpc/action",
        &.{
            operation,
            triple,
            keywordTerm("rpc/subject-any"),
            integerTerm(base),
        },
    );
    return batchAction(
        arena,
        if (action.operation == .assert) "rpc/assert" else "rpc/retract",
        triple,
        "rpc/subject-any",
    );
}

fn runModelMutation(
    arena: Allocator,
    peer: *Peer,
    model: *Model,
    operation: []const u8,
    actions: []const ModelAction,
    expected_version: ?i64,
    local_bases: []const ?i64,
    force_batch: bool,
) !void {
    if (local_bases.len != actions.len) return error.InvalidCorpus;
    var action_terms = try arena.alloc(log.Term, actions.len);
    var malformed = false;
    for (actions, local_bases, 0..) |action, local_base, index| {
        malformed = malformed or local_base != null;
        action_terms[index] = try modelActionTerm(arena, action, local_base);
    }

    const payload = if (force_batch)
        try record(
            arena,
            "rpc/batch",
            &.{ try list(arena, action_terms), try option(arena, null) },
        )
    else
        try writePayload(
            arena,
            try stringTriple(
                arena,
                actions[0].slot0,
                actions[0].slot1,
                actions[0].slot2,
            ),
            "rpc/subject-any",
            null,
        );

    var frame = try peer.exchange(
        operation,
        expected_version,
        null,
        payload,
    );
    defer frame.deinit();

    if (expected_version) |expected| {
        if (expected < 0) {
            try expectError(&frame, "rpc/invalid-request", model.version);
            return;
        }
        if (expected != model.version) {
            try expectError(&frame, "rpc/conflict", model.version);
            return;
        }
    }
    if (actions.len == 0 or malformed) {
        try expectError(&frame, "rpc/invalid-request", model.version);
        return;
    }

    var trial = try model.clone();
    var trial_live = true;
    defer if (trial_live) trial.deinit();
    const changed = try arena.alloc(bool, actions.len);
    for (actions, 0..) |action, index| {
        changed[index] = trial.apply(action) catch |err| switch (err) {
            error.CardinalityCollapse => {
                try expectError(
                    &frame,
                    "rpc/cardinality-collapse",
                    model.version,
                );
                return;
            },
            else => return err,
        };
    }
    var any_changed = false;
    for (changed) |value| any_changed = any_changed or value;
    if (any_changed) trial.version += 1;

    const value = try expectSuccess(&frame, trial.version);
    try mutationChanged(
        arena,
        value.payload orelse return error.ProtocolAssertion,
        changed,
    );
    model.deinit();
    model.* = trial;
    trial_live = false;
}

fn oracleLine(
    arena: Allocator,
    peer: *Peer,
    model: *Model,
    line: []const u8,
) !void {
    const fields = try splitFields(arena, line);
    if (fields.len == 0) return error.InvalidCorpus;
    const operation = fields[0];
    if (std.mem.eql(u8, operation, "version")) {
        if (fields.len != 1) return error.InvalidCorpus;
        var frame = try peer.exchange(
            "rpc/version",
            null,
            null,
            keywordTerm("rpc/unit"),
        );
        defer frame.deinit();
        _ = try expectSuccess(&frame, model.version);
        return;
    }

    if (std.mem.eql(u8, operation, "assert") or
        std.mem.eql(u8, operation, "retract") or
        std.mem.eql(u8, operation, "assert-at-version"))
    {
        if (fields.len != 4 and fields.len != 5)
            return error.InvalidCorpus;
        if (std.mem.eql(u8, operation, "assert-at-version") and
            fields.len != 5) return error.InvalidCorpus;
        const action = ModelAction{
            .operation = if (std.mem.eql(u8, operation, "retract"))
                .retract
            else
                .assert,
            .slot0 = fields[1],
            .slot1 = fields[2],
            .slot2 = fields[3],
        };
        const expected = if (fields.len == 5)
            std.fmt.parseInt(i64, fields[4], 10) catch
                return error.InvalidCorpus
        else
            null;
        return runModelMutation(
            arena,
            peer,
            model,
            if (action.operation == .assert) "rpc/assert" else "rpc/retract",
            &.{action},
            expected,
            &.{null},
            false,
        );
    }

    const at_version = std.mem.eql(
        u8,
        operation,
        "assert-batch-at-version",
    );
    if (!at_version and !std.mem.eql(u8, operation, "assert-batch"))
        return error.InvalidCorpus;
    if (at_version and fields.len != 4) return error.InvalidCorpus;
    if (!at_version and fields.len != 2 and fields.len != 3)
        return error.InvalidCorpus;

    const subject = fields[1];
    const expected = if (at_version)
        std.fmt.parseInt(i64, fields[2], 10) catch
            return error.InvalidCorpus
    else
        null;
    const encoded = if (at_version)
        fields[3]
    else if (fields.len == 3)
        fields[2]
    else
        "";
    const facts = try parseFacts(arena, encoded);
    const actions = try arena.alloc(ModelAction, facts.len);
    const local_bases = try arena.alloc(?i64, facts.len);
    for (facts, 0..) |fact, index| {
        actions[index] = .{
            .operation = .assert,
            .slot0 = subject,
            .slot1 = fact.predicate,
            .slot2 = fact.value,
        };
        local_bases[index] = fact.local_base;
    }
    return runModelMutation(
        arena,
        peer,
        model,
        "rpc/batch",
        actions,
        expected,
        local_bases,
        true,
    );
}

fn verifyModel(
    arena: Allocator,
    peer: *Peer,
    model: *Model,
) !void {
    var predicate_iterator = model.predicates.iterator();
    while (predicate_iterator.next()) |entry| {
        const predicate = entry.key_ptr.*;
        var frame = try peer.exchange(
            "rpc/scan",
            null,
            .{ .limit = 4096, .cursor = null },
            try pattern(arena, null, stringTerm(predicate), null),
        );
        defer frame.deinit();
        const value = try expectSuccess(&frame, model.version);
        const payload_fields = try recordFields(
            arena,
            value.payload orelse return error.ProtocolAssertion,
            "rpc/triples",
            1,
        );
        const actual = try collectList(arena, payload_fields[0]);
        var expected_count: usize = 0;
        for (model.triples.items) |triple| {
            if (!std.mem.eql(u8, triple.slot1, predicate)) continue;
            expected_count += 1;
            var found = false;
            for (actual) |term| {
                const candidate = tripleValue(term) orelse continue;
                found = found or
                    std.mem.eql(
                        u8,
                        stringValue(candidate.slot0) orelse "",
                        triple.slot0,
                    ) and
                        std.mem.eql(
                            u8,
                            stringValue(candidate.slot1) orelse "",
                            triple.slot1,
                        ) and
                        std.mem.eql(
                            u8,
                            stringValue(candidate.slot2) orelse "",
                            triple.slot2,
                        );
            }
            if (!found) return error.ProtocolAssertion;
        }
        if (actual.len != expected_count) return error.ProtocolAssertion;
    }

    var occurrences = try peer.exchange(
        "rpc/occurrences",
        null,
        .{ .limit = 4096, .cursor = null },
        keywordTerm("rpc/unit"),
    );
    defer occurrences.deinit();
    const occurrence_response = try expectSuccess(
        &occurrences,
        model.version,
    );
    const occurrence_fields = try recordFields(
        arena,
        occurrence_response.payload orelse return error.ProtocolAssertion,
        "rpc/occurrences",
        1,
    );
    const rows = try collectList(arena, occurrence_fields[0]);
    for (rows) |term| {
        const binding = tripleValue(term) orelse
            return error.ProtocolAssertion;
        if (!(isKeyword(binding.slot1, "kernel/asserts") or
            isKeyword(binding.slot1, "kernel/retracts")))
            return error.ProtocolAssertion;
    }

    var validation = try peer.exchange(
        "rpc/validate",
        null,
        null,
        keywordTerm("rpc/unit"),
    );
    defer validation.deinit();
    const validation_response = try expectSuccess(
        &validation,
        model.version,
    );
    const validation_fields = try recordFields(
        arena,
        validation_response.payload orelse return error.ProtocolAssertion,
        "rpc/validation",
        2,
    );
    if (booleanValue(validation_fields[0]) != true)
        return error.ProtocolAssertion;
}

fn oracle(
    allocator: Allocator,
    io: Io,
    port: u16,
    space: []const u8,
    corpus_path: []const u8,
) !void {
    const corpus = try std.Io.Dir.cwd().readFileAlloc(
        io,
        corpus_path,
        allocator,
        .limited(2 * 1024 * 1024),
    );
    defer allocator.free(corpus);
    var peer: Peer = .{
        .allocator = allocator,
        .io = io,
        .port = port,
        .space = space,
    };
    var model = Model.init(allocator);
    defer model.deinit();
    var arena_state = std.heap.ArenaAllocator.init(allocator);
    defer arena_state.deinit();
    const arena = arena_state.allocator();

    var line_count: usize = 0;
    var lines = std.mem.splitScalar(u8, corpus, '\n');
    while (lines.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (line.len == 0) continue;
        try oracleLine(arena, &peer, &model, line);
        line_count += 1;
    }
    try verifyModel(arena, &peer, &model);
    std.debug.print(
        "oracle {s}: {d} operations, version {d}, {d} live triples\n",
        .{ corpus_path, line_count, model.version, model.triples.items.len },
    );
}

fn probe(
    allocator: Allocator,
    io: Io,
    port: u16,
    space: []const u8,
    expected: ?i64,
) !void {
    var peer: Peer = .{
        .allocator = allocator,
        .io = io,
        .port = port,
        .space = space,
    };
    var frame = try peer.exchange(
        "rpc/version",
        null,
        null,
        keywordTerm("rpc/unit"),
    );
    defer frame.deinit();
    const value = try response(&frame);
    if (value.@"error" != null) return error.ProtocolAssertion;
    if (expected) |version| {
        if (value.served_version != version) return error.ProtocolAssertion;
    }
    std.debug.print("{d}\n", .{value.served_version});
}

pub fn main(init: std.process.Init) void {
    run(init) catch |err| {
        std.debug.print("fram-rpc-client: {s}\n", .{@errorName(err)});
        std.process.exit(1);
    };
}

fn run(init: std.process.Init) !void {
    var args = try std.process.Args.Iterator.initAllocator(
        init.minimal.args,
        init.gpa,
    );
    defer args.deinit();
    _ = args.next();
    const mode = args.next() orelse return error.InvalidArguments;
    const port_text = args.next() orelse return error.InvalidArguments;
    const space = args.next() orelse return error.InvalidArguments;
    const port = std.fmt.parseInt(u16, port_text, 10) catch
        return error.InvalidArguments;

    if (std.mem.eql(u8, mode, "probe")) {
        const expected = if (args.next()) |text|
            std.fmt.parseInt(i64, text, 10) catch return error.InvalidArguments
        else
            null;
        if (args.next() != null) return error.InvalidArguments;
        return probe(init.gpa, init.io, port, space, expected);
    }
    if (std.mem.eql(u8, mode, "bootstrap")) {
        if (args.next() != null) return error.InvalidArguments;
        try bootstrap(init.gpa, init.io, port, space);
        std.debug.print(
            "FRAMRPC bootstrap: 13 operations, typed triples, OCC, batch, query, leases, cancellation, and disconnect passed\n",
            .{},
        );
        return;
    }
    if (std.mem.eql(u8, mode, "oracle")) {
        const corpus_path = args.next() orelse return error.InvalidArguments;
        if (args.next() != null) return error.InvalidArguments;
        return oracle(
            init.gpa,
            init.io,
            port,
            space,
            corpus_path,
        );
    }
    return error.InvalidArguments;
}
