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
    unknown,
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

const MapFields = struct {
    op: Operation = .unknown,
    expected_log: StringField = .missing,
    request: ?[]const u8 = null,

    fn deinit(fields: *MapFields, allocator: Allocator) void {
        fields.expected_log.deinit(allocator);
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

const DaemonState = struct {
    version: i64,
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

    const state = try replayState(init.gpa, init.io, canonical_log);
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
        state,
        strict_fence,
    );
}

fn replayState(allocator: Allocator, io: Io, canonical_log: []const u8) !DaemonState {
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
            var version: i64 = 0;
            for (replay.records) |record| {
                if (record.fact.tx) |tx| version = @max(version, tx);
            }
            if (replay.torn_tail) |tail| {
                std.debug.print(
                    "fram: WARN torn-tail: {s}: torn final log line at byte {d} — recovered {d} prior fact(s), incomplete tail dropped\n",
                    .{ canonical_log, tail.byte_offset, tail.recovered_records },
                );
            }
            return .{ .version = version };
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
    state: DaemonState,
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
    state: DaemonState,
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
    state: DaemonState,
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
            outer.op,
            canonical_log,
            authority_path,
            state,
        );
    }

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
        nested.op,
        canonical_log,
        authority_path,
        state,
    );
}

fn dispatchOperation(
    allocator: Allocator,
    operation: Operation,
    canonical_log: []const u8,
    authority_path: []const u8,
    state: DaemonState,
) ![]u8 {
    return switch (operation) {
        .version => std.fmt.allocPrint(
            allocator,
            "{{:version {d}}}",
            .{state.version},
        ),
        .status => renderStatus(
            allocator,
            canonical_log,
            authority_path,
            state,
        ),
        else => allocator.dupe(u8, "{:error \"unknown op\"}"),
    };
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
            fields.op = parseOperation(value);
        } else if (std.mem.eql(u8, name, "expected-log")) {
            fields.expected_log.deinit(allocator);
            fields.expected_log = if (value.len >= 2 and
                value[0] == '"' and value[value.len - 1] == '"')
                .{ .value = try decodeEdnString(allocator, value) }
            else
                .invalid;
        } else if (std.mem.eql(u8, name, "request")) {
            fields.request = value;
        }
    }
}

fn parseOperation(raw: []const u8) Operation {
    if (std.mem.eql(u8, raw, ":for-log")) return .for_log;
    if (std.mem.eql(u8, raw, ":version")) return .version;
    if (std.mem.eql(u8, raw, ":status")) return .status;
    return .unknown;
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
    const response = try handleRequest(
        std.testing.allocator,
        std.testing.io,
        "{:op :version}",
        "/tmp/facts.log",
        "/tmp/facts.log.writer-authority.lock",
        .{ .version = 7 },
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
    const version = try dispatchOperation(
        std.testing.allocator,
        .version,
        "/tmp/facts.log",
        "/tmp/facts.log.writer-authority.lock",
        .{ .version = 9 },
    );
    defer std.testing.allocator.free(version);
    try std.testing.expectEqualStrings("{:version 9}", version);

    const status = try dispatchOperation(
        std.testing.allocator,
        .status,
        "/tmp/facts.log",
        "/tmp/facts.log.writer-authority.lock",
        .{ .version = 9 },
    );
    defer std.testing.allocator.free(status);
    try std.testing.expect(std.mem.indexOf(
        u8,
        status,
        ":write-authorized true",
    ) != null);
}
