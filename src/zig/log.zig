const std = @import("std");

const Allocator = std.mem.Allocator;
const Dir = std.Io.Dir;
const File = std.Io.File;
const Io = std.Io;
const Writer = std.Io.Writer;

pub const FactOp = struct {
    tx: i64,
    op: []const u8,
    l: []const u8,
    p: []const u8,
    r: []const u8,
};

pub const Provenance = union(enum) {
    none,
    cold: struct {
        frame: []const u8,
        ts: []const u8,
    },
    coordinator: struct {
        ts: []const u8,
        by: []const u8,
    },
};

/// Emit the byte contract shared by fram.rt/append-fact-op and the coordinator's
/// flat-line: one Clojure `pr-str` EDN map followed by exactly one LF.
pub fn encodeLine(
    allocator: Allocator,
    fact: FactOp,
    provenance: Provenance,
) Allocator.Error![]u8 {
    var out: Writer.Allocating = .init(allocator);
    defer out.deinit();
    const writer = &out.writer;

    writeBytes(writer, "{:tx ") catch return error.OutOfMemory;
    writer.print("{d}", .{fact.tx}) catch return error.OutOfMemory;
    writeBytes(writer, ", :op ") catch return error.OutOfMemory;
    writeEdnString(writer, fact.op) catch return error.OutOfMemory;
    writeBytes(writer, ", :l ") catch return error.OutOfMemory;
    writeEdnString(writer, fact.l) catch return error.OutOfMemory;
    writeBytes(writer, ", :p ") catch return error.OutOfMemory;
    writeEdnString(writer, fact.p) catch return error.OutOfMemory;
    writeBytes(writer, ", :r ") catch return error.OutOfMemory;
    writeEdnString(writer, fact.r) catch return error.OutOfMemory;

    switch (provenance) {
        .none => {},
        .cold => |cold| {
            writeBytes(writer, ", :frame ") catch return error.OutOfMemory;
            writeEdnString(writer, cold.frame) catch return error.OutOfMemory;
            writeBytes(writer, ", :ts ") catch return error.OutOfMemory;
            writeEdnString(writer, cold.ts) catch return error.OutOfMemory;
        },
        .coordinator => |coordinator| {
            writeBytes(writer, ", :ts ") catch return error.OutOfMemory;
            writeEdnString(writer, coordinator.ts) catch return error.OutOfMemory;
            writeBytes(writer, ", :by ") catch return error.OutOfMemory;
            writeEdnString(writer, coordinator.by) catch return error.OutOfMemory;
        },
    }
    writeBytes(writer, "}\n") catch return error.OutOfMemory;
    return out.toOwnedSlice();
}

fn writeBytes(writer: *Writer, bytes: []const u8) Writer.Error!void {
    try writer.writeAll(bytes);
}

fn writeEdnString(writer: *Writer, value: []const u8) Writer.Error!void {
    try writer.writeByte('"');
    for (value) |byte| switch (byte) {
        '"' => try writer.writeAll("\\\""),
        '\\' => try writer.writeAll("\\\\"),
        '\n' => try writer.writeAll("\\n"),
        '\r' => try writer.writeAll("\\r"),
        '\t' => try writer.writeAll("\\t"),
        0x08 => try writer.writeAll("\\b"),
        0x0c => try writer.writeAll("\\f"),
        else => try writer.writeByte(byte),
    };
    try writer.writeByte('"');
}

pub const PartialFactOp = struct {
    tx: ?i64 = null,
    op: ?[]const u8 = null,
    l: ?[]const u8 = null,
    p: ?[]const u8 = null,
    r: ?[]const u8 = null,
    frame: ?[]const u8 = null,
    by: ?[]const u8 = null,
    ts: ?[]const u8 = null,

    pub fn complete(self: PartialFactOp) bool {
        const operation = self.op orelse return false;
        return self.tx != null and
            self.l != null and
            self.p != null and
            self.r != null and
            (std.mem.eql(u8, operation, "assert") or
                std.mem.eql(u8, operation, "retract"));
    }
};

pub const ParsedLine = struct {
    byte_offset: usize,
    fact: PartialFactOp,
};

pub const TornTail = struct {
    byte_offset: usize,
    recovered_records: usize,
};

pub const Replay = struct {
    arena: std.heap.ArenaAllocator,
    records: []ParsedLine,
    torn_tail: ?TornTail,

    pub fn deinit(self: *Replay) void {
        self.arena.deinit();
        self.* = undefined;
    }
};

pub const Corruption = struct {
    byte_offset: usize,
};

pub const ReadOutcome = union(enum) {
    replay: Replay,
    corrupt: Corruption,
};

/// Split the raw file on LF bytes so diagnostics remain byte offsets even when
/// earlier values contain multibyte UTF-8. An unparseable unterminated final
/// segment is recoverable; an unparseable completed line is corruption.
pub fn replayBytes(allocator: Allocator, bytes: []const u8) Allocator.Error!ReadOutcome {
    var arena = std.heap.ArenaAllocator.init(allocator);
    var arena_moved = false;
    defer if (!arena_moved) arena.deinit();
    const arena_allocator = arena.allocator();
    var records: std.ArrayList(ParsedLine) = .empty;
    defer records.deinit(arena_allocator);

    var offset: usize = 0;
    while (offset < bytes.len) {
        const relative_lf = std.mem.indexOfScalar(u8, bytes[offset..], '\n');
        const terminated = relative_lf != null;
        const end = if (relative_lf) |index| offset + index else bytes.len;
        const segment = bytes[offset..end];
        const next_offset = if (terminated) end + 1 else bytes.len;

        if (!blank(segment)) {
            const fact = parseRecord(arena_allocator, segment) catch |err| switch (err) {
                error.OutOfMemory => return error.OutOfMemory,
                error.InvalidEdn => {
                    if (!terminated) {
                        const owned = try records.toOwnedSlice(arena_allocator);
                        arena_moved = true;
                        return .{ .replay = .{
                            .arena = arena,
                            .records = owned,
                            .torn_tail = .{
                                .byte_offset = offset,
                                .recovered_records = owned.len,
                            },
                        } };
                    }
                    return .{ .corrupt = .{ .byte_offset = offset } };
                },
            };
            try records.append(arena_allocator, .{
                .byte_offset = offset,
                .fact = fact,
            });
        }
        offset = next_offset;
    }

    const owned = try records.toOwnedSlice(arena_allocator);
    arena_moved = true;
    return .{ .replay = .{
        .arena = arena,
        .records = owned,
        .torn_tail = null,
    } };
}

pub fn replayFile(
    allocator: Allocator,
    io: Io,
    dir: Dir,
    sub_path: []const u8,
    max_bytes: usize,
) !ReadOutcome {
    const bytes = dir.readFileAlloc(
        io,
        sub_path,
        allocator,
        .limited(max_bytes),
    ) catch |err| switch (err) {
        error.FileNotFound => return replayBytes(allocator, ""),
        else => |other| return other,
    };
    defer allocator.free(bytes);
    return replayBytes(allocator, bytes);
}

/// Append a complete batch and make it durable before returning. The caller
/// owns Fram's sole-writer and rewrite-admission policy; this primitive owns
/// only the file boundary and fsync contract.
pub fn appendDurable(
    io: Io,
    dir: Dir,
    sub_path: []const u8,
    payload: []const u8,
) !void {
    try requireFramedPayload(payload);
    if (payload.len == 0) return;

    var file = try dir.createFile(io, sub_path, .{
        .read = true,
        .truncate = false,
    });
    defer file.close(io);
    const stat = try file.stat(io);

    if (stat.size != 0) {
        var reader = file.reader(io, &.{});
        reader.pos = stat.size - 1;
        var final_byte: [1]u8 = undefined;
        try reader.interface.readSliceAll(&final_byte);
        if (final_byte[0] != '\n') return error.UnterminatedLog;
    }

    var writer = file.writer(io, &.{});
    writer.pos = stat.size;
    try writer.interface.writeAll(payload);
    try writer.interface.flush();
    try file.sync(io);
}

/// Replace a complete log without exposing a truncated destination: write and
/// sync a same-directory temporary file, atomically rename it, then sync the
/// directory entry. Existing permissions survive the inode replacement.
pub fn rewriteDurableAtomic(
    io: Io,
    dir: Dir,
    sub_path: []const u8,
    payload: []const u8,
) !void {
    try requireFramedPayload(payload);
    const permissions = permissions: {
        const stat = dir.statFile(io, sub_path, .{}) catch |err| switch (err) {
            error.FileNotFound => break :permissions File.Permissions.default_file,
            else => |other| return other,
        };
        break :permissions stat.permissions;
    };

    var atomic_file = try dir.createFileAtomic(io, sub_path, .{
        .permissions = permissions,
        .replace = true,
    });
    defer atomic_file.deinit(io);
    try atomic_file.file.writeStreamingAll(io, payload);
    try atomic_file.file.sync(io);
    try atomic_file.replace(io);

    var sync_dir = try dir.openDir(io, ".", .{ .iterate = true });
    defer sync_dir.close(io);
    const dir_file: File = .{
        .handle = sync_dir.handle,
        .flags = .{ .nonblocking = false },
    };
    try dir_file.sync(io);
}

fn requireFramedPayload(payload: []const u8) !void {
    if (payload.len != 0 and payload[payload.len - 1] != '\n')
        return error.UnterminatedPayload;
}

const ParseError = error{
    InvalidEdn,
    OutOfMemory,
};

const Span = struct {
    start: usize,
    end: usize,
};

const Parser = struct {
    allocator: Allocator,
    input: []const u8,
    pos: usize = 0,

    fn parseTop(self: *Parser) ParseError!PartialFactOp {
        self.skipSeparators();
        if (self.pos >= self.input.len) return error.InvalidEdn;
        if (self.input[self.pos] != '{') {
            _ = try self.skipValue();
            self.skipSeparators();
            if (self.pos != self.input.len) return error.InvalidEdn;
            return .{};
        }

        self.pos += 1;
        var fact: PartialFactOp = .{};
        while (true) {
            self.skipSeparators();
            if (self.pos >= self.input.len) return error.InvalidEdn;
            if (self.input[self.pos] == '}') {
                self.pos += 1;
                break;
            }
            const key = try self.skipValue();
            self.skipSeparators();
            if (self.pos >= self.input.len or self.input[self.pos] == '}')
                return error.InvalidEdn;
            const value = try self.skipValue();
            try self.captureField(&fact, key, value);
        }
        self.skipSeparators();
        if (self.pos != self.input.len) return error.InvalidEdn;
        return fact;
    }

    fn captureField(
        self: *Parser,
        fact: *PartialFactOp,
        key: Span,
        value: Span,
    ) ParseError!void {
        const key_bytes = self.input[key.start..key.end];
        const value_bytes = self.input[value.start..value.end];
        if (std.mem.eql(u8, key_bytes, ":tx")) {
            fact.tx = std.fmt.parseInt(i64, value_bytes, 10) catch null;
        } else if (std.mem.eql(u8, key_bytes, ":op")) {
            fact.op = try self.decodeString(value_bytes);
        } else if (std.mem.eql(u8, key_bytes, ":l")) {
            fact.l = try self.decodeString(value_bytes);
        } else if (std.mem.eql(u8, key_bytes, ":p")) {
            fact.p = try self.decodeString(value_bytes);
        } else if (std.mem.eql(u8, key_bytes, ":r")) {
            fact.r = try self.decodeString(value_bytes);
        } else if (std.mem.eql(u8, key_bytes, ":frame")) {
            fact.frame = try self.decodeString(value_bytes);
        } else if (std.mem.eql(u8, key_bytes, ":by")) {
            fact.by = try self.decodeString(value_bytes);
        } else if (std.mem.eql(u8, key_bytes, ":ts")) {
            fact.ts = try self.decodeString(value_bytes);
        }
    }

    fn skipValue(self: *Parser) ParseError!Span {
        self.skipSeparators();
        if (self.pos >= self.input.len) return error.InvalidEdn;
        const start = self.pos;
        switch (self.input[self.pos]) {
            '"' => try self.skipString(),
            '{' => try self.skipMap(),
            '[' => try self.skipCollection('[', ']'),
            '(' => try self.skipCollection('(', ')'),
            '#' => try self.skipDispatch(),
            '}', ']', ')' => return error.InvalidEdn,
            else => try self.skipToken(),
        }
        return .{ .start = start, .end = self.pos };
    }

    fn skipString(self: *Parser) ParseError!void {
        self.pos += 1;
        while (self.pos < self.input.len) {
            const byte = self.input[self.pos];
            self.pos += 1;
            if (byte == '"') return;
            if (byte == '\\') {
                if (self.pos >= self.input.len) return error.InvalidEdn;
                const escaped = self.input[self.pos];
                self.pos += 1;
                if (escaped == 'u') {
                    if (self.input.len - self.pos < 4) return error.InvalidEdn;
                    for (self.input[self.pos .. self.pos + 4]) |hex| {
                        if (!std.ascii.isHex(hex)) return error.InvalidEdn;
                    }
                    self.pos += 4;
                } else if (std.mem.indexOfScalar(
                    u8,
                    "\\\"nrtbf",
                    escaped,
                ) == null) {
                    return error.InvalidEdn;
                }
            }
        }
        return error.InvalidEdn;
    }

    fn skipMap(self: *Parser) ParseError!void {
        self.pos += 1;
        while (true) {
            self.skipSeparators();
            if (self.pos >= self.input.len) return error.InvalidEdn;
            if (self.input[self.pos] == '}') {
                self.pos += 1;
                return;
            }
            _ = try self.skipValue();
            self.skipSeparators();
            if (self.pos >= self.input.len or self.input[self.pos] == '}')
                return error.InvalidEdn;
            _ = try self.skipValue();
        }
    }

    fn skipCollection(self: *Parser, open: u8, close: u8) ParseError!void {
        std.debug.assert(self.input[self.pos] == open);
        self.pos += 1;
        while (true) {
            self.skipSeparators();
            if (self.pos >= self.input.len) return error.InvalidEdn;
            if (self.input[self.pos] == close) {
                self.pos += 1;
                return;
            }
            _ = try self.skipValue();
        }
    }

    fn skipDispatch(self: *Parser) ParseError!void {
        self.pos += 1;
        if (self.pos >= self.input.len) return error.InvalidEdn;
        if (self.input[self.pos] == '{') {
            try self.skipCollection('{', '}');
            return;
        }
        if (self.input[self.pos] == '"') {
            try self.skipString();
            return;
        }
        if (self.input[self.pos] == '#') {
            try self.skipToken();
            return;
        }
        try self.skipToken();
        self.skipSeparators();
        _ = try self.skipValue();
    }

    fn skipToken(self: *Parser) ParseError!void {
        const start = self.pos;
        while (self.pos < self.input.len and !delimiter(self.input[self.pos])) {
            self.pos += 1;
        }
        if (self.pos == start) return error.InvalidEdn;
    }

    fn skipSeparators(self: *Parser) void {
        while (self.pos < self.input.len) {
            switch (self.input[self.pos]) {
                ' ', '\t', '\r', '\n', ',' => self.pos += 1,
                else => return,
            }
        }
    }

    fn decodeString(self: *Parser, raw: []const u8) ParseError!?[]const u8 {
        if (raw.len < 2 or raw[0] != '"' or raw[raw.len - 1] != '"')
            return null;
        var out: Writer.Allocating = .init(self.allocator);
        defer out.deinit();
        const writer = &out.writer;
        var index: usize = 1;
        while (index < raw.len - 1) {
            const byte = raw[index];
            index += 1;
            if (byte != '\\') {
                writer.writeByte(byte) catch return error.OutOfMemory;
                continue;
            }
            if (index >= raw.len - 1) return error.InvalidEdn;
            const escaped = raw[index];
            index += 1;
            switch (escaped) {
                '\\' => writer.writeByte('\\') catch return error.OutOfMemory,
                '"' => writer.writeByte('"') catch return error.OutOfMemory,
                'n' => writer.writeByte('\n') catch return error.OutOfMemory,
                'r' => writer.writeByte('\r') catch return error.OutOfMemory,
                't' => writer.writeByte('\t') catch return error.OutOfMemory,
                'b' => writer.writeByte(0x08) catch return error.OutOfMemory,
                'f' => writer.writeByte(0x0c) catch return error.OutOfMemory,
                'u' => {
                    if (raw.len - 1 - index < 4) return error.InvalidEdn;
                    const codepoint = std.fmt.parseInt(
                        u21,
                        raw[index .. index + 4],
                        16,
                    ) catch return error.InvalidEdn;
                    index += 4;
                    var encoded: [4]u8 = undefined;
                    const count = std.unicode.utf8Encode(
                        codepoint,
                        &encoded,
                    ) catch return error.InvalidEdn;
                    writer.writeAll(encoded[0..count]) catch
                        return error.OutOfMemory;
                },
                else => return error.InvalidEdn,
            }
        }
        const decoded = out.written();
        if (!std.unicode.utf8ValidateSlice(decoded)) return error.InvalidEdn;
        return try self.allocator.dupe(u8, decoded);
    }
};

fn parseRecord(allocator: Allocator, segment: []const u8) ParseError!PartialFactOp {
    if (!std.unicode.utf8ValidateSlice(segment)) return error.InvalidEdn;
    var parser: Parser = .{ .allocator = allocator, .input = segment };
    return parser.parseTop();
}

fn delimiter(byte: u8) bool {
    return switch (byte) {
        ' ', '\t', '\r', '\n', ',', '{', '}', '[', ']', '(', ')' => true,
        else => false,
    };
}

fn blank(bytes: []const u8) bool {
    for (bytes) |byte| switch (byte) {
        ' ', '\t', '\r', '\n' => {},
        else => return false,
    };
    return true;
}

test "flat line bytes match the Clojure producers" {
    const allocator = std.testing.allocator;
    const unicode_line = try encodeLine(allocator, .{
        .tx = 1,
        .op = "assert",
        .l = "@café",
        .p = "title",
        .r = "Café ☕ time",
    }, .none);
    defer allocator.free(unicode_line);
    try std.testing.expectEqualStrings(
        "{:tx 1, :op \"assert\", :l \"@café\", :p \"title\", :r \"Café ☕ time\"}\n",
        unicode_line,
    );

    const cold_line = try encodeLine(allocator, .{
        .tx = 7,
        .op = "assert",
        .l = "@s",
        .p = "body",
        .r = "line\nquote \" slash \\",
    }, .{ .cold = .{
        .frame = "cli",
        .ts = "2026-07-30T00:00:00Z",
    } });
    defer allocator.free(cold_line);
    try std.testing.expectEqualStrings(
        "{:tx 7, :op \"assert\", :l \"@s\", :p \"body\", :r \"line\\nquote \\\" slash \\\\\", :frame \"cli\", :ts \"2026-07-30T00:00:00Z\"}\n",
        cold_line,
    );

    const coordinator_line = try encodeLine(allocator, .{
        .tx = 8,
        .op = "retract",
        .l = "@s",
        .p = "owner",
        .r = "alice",
    }, .{ .coordinator = .{
        .ts = "2026-07-30T00:00:01Z",
        .by = "coord",
    } });
    defer allocator.free(coordinator_line);
    try std.testing.expectEqualStrings(
        "{:tx 8, :op \"retract\", :l \"@s\", :p \"owner\", :r \"alice\", :ts \"2026-07-30T00:00:01Z\", :by \"coord\"}\n",
        coordinator_line,
    );
}

test "replay distinguishes torn tail, completed corruption, and valid incomplete EDN" {
    const allocator = std.testing.allocator;
    const line1 =
        "{:tx 1, :op \"assert\", :l \"@café\", :p \"title\", :r \"Café ☕ time\"}\n";
    const line2 =
        "{:tx 2, :op \"assert\", :l \"@b\", :p \"note\", :r \"ok\"}\n";
    const torn = "{:tx 3, :op \"assert\", :l \"@c\", :p \"tit";
    const torn_bytes = line1 ++ line2 ++ torn;

    var torn_outcome = try replayBytes(allocator, torn_bytes);
    switch (torn_outcome) {
        .corrupt => return error.TestExpectedEqual,
        .replay => |*replay| {
            defer replay.deinit();
            try std.testing.expectEqual(@as(usize, 2), replay.records.len);
            try std.testing.expectEqual(
                @as(usize, line1.len + line2.len),
                replay.torn_tail.?.byte_offset,
            );
            try std.testing.expectEqualStrings(
                "Café ☕ time",
                replay.records[0].fact.r.?,
            );
        },
    }

    const bad = "{:tx 2, :op broken not-edn (((\n";
    const corruption_bytes = line1 ++ bad ++ line2;
    var corrupt_outcome = try replayBytes(allocator, corruption_bytes);
    switch (corrupt_outcome) {
        .replay => |*replay| {
            replay.deinit();
            return error.TestExpectedEqual;
        },
        .corrupt => |corruption| try std.testing.expectEqual(
            @as(usize, line1.len),
            corruption.byte_offset,
        ),
    }

    const incomplete =
        "{:tx 5, :op \"assert\", :l \"@x\", :p \"title\"}";
    const incomplete_bytes = line1 ++ line2 ++ incomplete;
    var incomplete_outcome = try replayBytes(allocator, incomplete_bytes);
    switch (incomplete_outcome) {
        .corrupt => return error.TestExpectedEqual,
        .replay => |*replay| {
            defer replay.deinit();
            try std.testing.expectEqual(@as(usize, 3), replay.records.len);
            try std.testing.expect(replay.torn_tail == null);
            try std.testing.expectEqual(@as(?i64, 5), replay.records[2].fact.tx);
            try std.testing.expect(replay.records[2].fact.r == null);
            try std.testing.expect(!replay.records[2].fact.complete());
        },
    }
}

test "durable append and atomic rewrite preserve framing" {
    const allocator = std.testing.allocator;
    const io = std.testing.io;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();

    const first = try encodeLine(allocator, .{
        .tx = 1,
        .op = "assert",
        .l = "@a",
        .p = "title",
        .r = "A",
    }, .none);
    defer allocator.free(first);
    const second = try encodeLine(allocator, .{
        .tx = 2,
        .op = "assert",
        .l = "@b",
        .p = "title",
        .r = "B",
    }, .none);
    defer allocator.free(second);

    try appendDurable(io, tmp.dir, "facts.log", first);
    try appendDurable(io, tmp.dir, "facts.log", second);
    const appended = try tmp.dir.readFileAlloc(
        io,
        "facts.log",
        allocator,
        .unlimited,
    );
    defer allocator.free(appended);
    const expected = try std.mem.concat(allocator, u8, &.{ first, second });
    defer allocator.free(expected);
    try std.testing.expectEqualStrings(expected, appended);

    try tmp.dir.writeFile(io, .{
        .sub_path = "broken.log",
        .data = "unterminated",
    });
    try std.testing.expectError(
        error.UnterminatedLog,
        appendDurable(io, tmp.dir, "broken.log", first),
    );

    try rewriteDurableAtomic(io, tmp.dir, "facts.log", second);
    const rewritten = try tmp.dir.readFileAlloc(
        io,
        "facts.log",
        allocator,
        .unlimited,
    );
    defer allocator.free(rewritten);
    try std.testing.expectEqualStrings(second, rewritten);

    var replay_outcome = try replayFile(
        allocator,
        io,
        tmp.dir,
        "facts.log",
        1024,
    );
    switch (replay_outcome) {
        .corrupt => return error.TestExpectedEqual,
        .replay => |*replay| {
            defer replay.deinit();
            try std.testing.expectEqual(@as(usize, 1), replay.records.len);
            try std.testing.expect(replay.records[0].fact.complete());
            try std.testing.expectEqual(@as(?i64, 2), replay.records[0].fact.tx);
        },
    }
}
