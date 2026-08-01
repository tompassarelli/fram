const std = @import("std");

const Allocator = std.mem.Allocator;
const Dir = std.Io.Dir;
const File = std.Io.File;
const Io = std.Io;
const Writer = std.Io.Writer;

pub const format_magic: []const u8 = "FRAMLOG\x00";
pub const format_version: u16 = 1;
pub const format_flags: u16 = 0;
pub const max_space_id_bytes: usize = 4096;
pub const max_frame_payload_bytes: usize = 64 * 1024 * 1024;
pub const max_term_depth: usize = 256;

const fixed_header_bytes = format_magic.len + @sizeOf(u16) + @sizeOf(u16) + @sizeOf(u32);

pub const Instant = struct {
    epoch_seconds: i64,
    nanosecond: u32,
};

pub const Atom = union(enum) {
    string: []const u8,
    integer: i64,
    float: f64,
    boolean: bool,
    /// Canonical keyword spelling without the leading `:` sigil.
    keyword: []const u8,
    instant: Instant,
};

pub const Term = union(enum) {
    atom: Atom,
    triple: *const Triple,
};

/// The one semantic aggregate stored by Fram. Slot meaning belongs to the
/// ontology; the physical codec treats all three positions uniformly.
pub const Triple = struct {
    slot0: Term,
    slot1: Term,
    slot2: Term,
};

pub const Action = enum(u8) {
    assert = 1,
    retract = 2,
};

pub const Op = struct {
    ordinal: u32,
    action: Action,
    triple: Triple,
};

pub const Transaction = struct {
    tx_seq: i64,
    ops: []const Op,
};

const TermTag = enum(u8) {
    string = 1,
    integer = 2,
    float = 3,
    bool_false = 4,
    bool_true = 5,
    keyword = 6,
    triple = 7,
    instant = 8,
};

pub const CorruptionReason = enum {
    invalid_header,
    frame_too_large,
    checksum_mismatch,
    invalid_transaction,
    non_monotonic_transaction,
};

pub const Corruption = struct {
    byte_offset: usize,
    reason: CorruptionReason,
};

pub const TornTail = struct {
    byte_offset: usize,
    recovered_transactions: usize,
};

pub const Replay = struct {
    arena: std.heap.ArenaAllocator,
    space_id: []const u8,
    transactions: []Transaction,
    valid_bytes: usize,
    torn_tail: ?TornTail,

    pub fn deinit(self: *Replay) void {
        self.arena.deinit();
        self.* = undefined;
    }
};

pub const ReadOutcome = union(enum) {
    replay: Replay,
    corrupt: Corruption,
};

pub const EncodeError = Allocator.Error || error{
    InvalidSpaceId,
    InvalidAtom,
    InvalidAtomUtf8,
    InvalidInstant,
    EmptyTransaction,
    TooManyOperations,
    NonCanonicalOrdinal,
    NonMonotonicTransaction,
    TermTooDeep,
    FrameTooLarge,
};

pub const ReplayError = Allocator.Error || error{
    MigrationRequired,
    InvalidSpaceId,
    SpaceMismatch,
};

/// Encode the immutable identity fence at the front of every v1 log.
pub fn encodeHeader(allocator: Allocator, space_id: []const u8) EncodeError![]u8 {
    try validateSpaceId(space_id);
    var out: Writer.Allocating = .init(allocator);
    defer out.deinit();
    try writeBytes(&out.writer, format_magic);
    try writeInt(&out.writer, u16, format_version);
    try writeInt(&out.writer, u16, format_flags);
    try writeLength(&out.writer, space_id.len);
    try writeBytes(&out.writer, space_id);
    return out.toOwnedSlice();
}

/// Encode one atomic transaction frame. CRC-32/ISO-HDLC is the standard
/// reflected IEEE CRC-32; the checksum covers the payload and nothing else.
pub fn encodeTransactionFrame(
    allocator: Allocator,
    transaction: Transaction,
) EncodeError![]u8 {
    try validateTransaction(transaction);

    var payload_out: Writer.Allocating = .init(allocator);
    defer payload_out.deinit();
    const payload = &payload_out.writer;
    try writeInt(payload, i64, transaction.tx_seq);
    try writeInt(payload, u32, @intCast(transaction.ops.len));
    for (transaction.ops) |op| {
        try writeInt(payload, u32, op.ordinal);
        try writeByte(payload, @intFromEnum(op.action));
        try writeTerm(payload, .{ .triple = &op.triple }, 0);
    }

    const payload_bytes = payload_out.written();
    if (payload_bytes.len > max_frame_payload_bytes or
        payload_bytes.len > std.math.maxInt(u32))
    {
        return error.FrameTooLarge;
    }

    var frame_out: Writer.Allocating = .init(allocator);
    defer frame_out.deinit();
    const frame = &frame_out.writer;
    try writeInt(frame, u32, @intCast(payload_bytes.len));
    try writeBytes(frame, payload_bytes);
    try writeInt(frame, u32, std.hash.Crc32.hash(payload_bytes));
    return frame_out.toOwnedSlice();
}

/// Encode a complete canonical image. Transactions must be ordered strictly by
/// their logical sequence; equal content in different operations stays distinct.
pub fn encodeLog(
    allocator: Allocator,
    space_id: []const u8,
    transactions: []const Transaction,
) EncodeError![]u8 {
    const header = try encodeHeader(allocator, space_id);
    defer allocator.free(header);

    var out: Writer.Allocating = .init(allocator);
    defer out.deinit();
    try writeBytes(&out.writer, header);

    var previous_tx: ?i64 = null;
    for (transactions) |transaction| {
        if (previous_tx) |previous| {
            if (transaction.tx_seq <= previous)
                return error.NonMonotonicTransaction;
        }
        const frame = try encodeTransactionFrame(allocator, transaction);
        defer allocator.free(frame);
        try writeBytes(&out.writer, frame);
        previous_tx = transaction.tx_seq;
    }
    return out.toOwnedSlice();
}

pub fn replayBytes(allocator: Allocator, bytes: []const u8) ReplayError!ReadOutcome {
    return replayBytesExpected(allocator, bytes, null);
}

pub fn replayBytesForSpace(
    allocator: Allocator,
    bytes: []const u8,
    expected_space_id: []const u8,
) ReplayError!ReadOutcome {
    try validateExpectedSpaceId(expected_space_id);
    return replayBytesExpected(allocator, bytes, expected_space_id);
}

fn replayBytesExpected(
    allocator: Allocator,
    bytes: []const u8,
    expected_space_id: ?[]const u8,
) ReplayError!ReadOutcome {
    const header = parseHeader(bytes) catch |err| switch (err) {
        error.MigrationRequired => return error.MigrationRequired,
        error.InvalidHeader => return .{ .corrupt = .{
            .byte_offset = 0,
            .reason = .invalid_header,
        } },
    };
    if (expected_space_id) |expected| {
        if (!std.mem.eql(u8, expected, header.space_id))
            return error.SpaceMismatch;
    }

    var arena = std.heap.ArenaAllocator.init(allocator);
    var arena_moved = false;
    defer if (!arena_moved) arena.deinit();
    const arena_allocator = arena.allocator();
    const owned_space_id = try arena_allocator.dupe(u8, header.space_id);
    var transactions: std.ArrayList(Transaction) = .empty;
    defer transactions.deinit(arena_allocator);

    var offset = header.end_offset;
    var previous_tx: ?i64 = null;
    while (offset < bytes.len) {
        const frame_offset = offset;
        if (bytes.len - offset < @sizeOf(u32)) {
            return finishReplay(
                &arena,
                &arena_moved,
                arena_allocator,
                &transactions,
                owned_space_id,
                frame_offset,
                .{
                    .byte_offset = frame_offset,
                    .recovered_transactions = transactions.items.len,
                },
            );
        }

        const payload_len: usize = readIntAt(u32, bytes, offset);
        offset += @sizeOf(u32);
        if (bytes.len - offset < payload_len + @sizeOf(u32)) {
            return finishReplay(
                &arena,
                &arena_moved,
                arena_allocator,
                &transactions,
                owned_space_id,
                frame_offset,
                .{
                    .byte_offset = frame_offset,
                    .recovered_transactions = transactions.items.len,
                },
            );
        }
        if (payload_len > max_frame_payload_bytes) {
            return .{ .corrupt = .{
                .byte_offset = frame_offset,
                .reason = .frame_too_large,
            } };
        }

        const payload = bytes[offset .. offset + payload_len];
        offset += payload_len;
        const stored_crc = readIntAt(u32, bytes, offset);
        offset += @sizeOf(u32);
        if (stored_crc != std.hash.Crc32.hash(payload)) {
            return .{ .corrupt = .{
                .byte_offset = frame_offset,
                .reason = .checksum_mismatch,
            } };
        }

        const transaction = parseTransaction(arena_allocator, payload) catch |err| switch (err) {
            error.OutOfMemory => return error.OutOfMemory,
            error.InvalidFrame, error.TermTooDeep => return .{ .corrupt = .{
                .byte_offset = frame_offset,
                .reason = .invalid_transaction,
            } },
        };
        if (previous_tx) |previous| {
            if (transaction.tx_seq <= previous) {
                return .{ .corrupt = .{
                    .byte_offset = frame_offset,
                    .reason = .non_monotonic_transaction,
                } };
            }
        }
        try transactions.append(arena_allocator, transaction);
        previous_tx = transaction.tx_seq;
    }

    return finishReplay(
        &arena,
        &arena_moved,
        arena_allocator,
        &transactions,
        owned_space_id,
        bytes.len,
        null,
    );
}

fn finishReplay(
    arena: *std.heap.ArenaAllocator,
    arena_moved: *bool,
    arena_allocator: Allocator,
    transactions: *std.ArrayList(Transaction),
    space_id: []const u8,
    valid_bytes: usize,
    torn_tail: ?TornTail,
) Allocator.Error!ReadOutcome {
    const owned_transactions = try transactions.toOwnedSlice(arena_allocator);
    arena_moved.* = true;
    return .{ .replay = .{
        .arena = arena.*,
        .space_id = space_id,
        .transactions = owned_transactions,
        .valid_bytes = valid_bytes,
        .torn_tail = torn_tail,
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
        error.FileNotFound => return error.MigrationRequired,
        else => |other| return other,
    };
    defer allocator.free(bytes);
    return replayBytes(allocator, bytes);
}

/// Append against the byte boundary returned by replay. The immutable SpaceId
/// and exact file size are both fenced before bytes are written and synced.
pub fn appendTransactionDurable(
    allocator: Allocator,
    io: Io,
    dir: Dir,
    sub_path: []const u8,
    expected_space_id: []const u8,
    expected_size: u64,
    transaction: Transaction,
) !u64 {
    try validateExpectedSpaceId(expected_space_id);
    const frame = try encodeTransactionFrame(allocator, transaction);
    defer allocator.free(frame);

    var file = dir.openFile(io, sub_path, .{ .mode = .read_write }) catch |err| switch (err) {
        error.FileNotFound => return error.MigrationRequired,
        else => |other| return other,
    };
    defer file.close(io);
    const stat = try file.stat(io);
    if (stat.size != expected_size) return error.LogAdvanced;

    const probe_len_u64 = @min(
        stat.size,
        @as(u64, fixed_header_bytes + max_space_id_bytes),
    );
    const probe_len: usize = @intCast(probe_len_u64);
    const probe = try allocator.alloc(u8, probe_len);
    defer allocator.free(probe);
    var reader = file.reader(io, &.{});
    reader.pos = 0;
    try reader.interface.readSliceAll(probe);
    const header = parseHeader(probe) catch |err| switch (err) {
        error.MigrationRequired => return error.MigrationRequired,
        error.InvalidHeader => return error.CorruptLog,
    };
    if (!std.mem.eql(u8, expected_space_id, header.space_id))
        return error.SpaceMismatch;
    if (expected_size < header.end_offset) return error.CorruptLog;

    var writer = file.writer(io, &.{});
    writer.pos = stat.size;
    try writer.interface.writeAll(frame);
    try writer.interface.flush();
    try file.sync(io);
    return std.math.add(u64, stat.size, frame.len) catch error.FileTooBig;
}

/// Atomically install a complete canonical image, preserving existing file
/// permissions and syncing the containing directory after rename.
pub fn rewriteDurableAtomic(
    allocator: Allocator,
    io: Io,
    dir: Dir,
    sub_path: []const u8,
    expected_space_id: []const u8,
    payload: []const u8,
) !void {
    var outcome = try replayBytesForSpace(allocator, payload, expected_space_id);
    switch (outcome) {
        .corrupt => return error.CorruptLog,
        .replay => |*replay| {
            defer replay.deinit();
            if (replay.torn_tail != null) return error.TornTail;
        },
    }

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

pub fn tripleEql(left: Triple, right: Triple) bool {
    return termEql(left.slot0, right.slot0) and
        termEql(left.slot1, right.slot1) and
        termEql(left.slot2, right.slot2);
}

pub fn termEql(left: Term, right: Term) bool {
    if (std.meta.activeTag(left) != std.meta.activeTag(right)) return false;
    return switch (left) {
        .atom => |left_atom| atomEql(left_atom, right.atom),
        .triple => |left_triple| tripleEql(left_triple.*, right.triple.*),
    };
}

fn atomEql(left: Atom, right: Atom) bool {
    if (std.meta.activeTag(left) != std.meta.activeTag(right)) return false;
    return switch (left) {
        .string => |value| std.mem.eql(u8, value, right.string),
        .integer => |value| value == right.integer,
        .float => |value| @as(u64, @bitCast(value)) == @as(u64, @bitCast(right.float)),
        .boolean => |value| value == right.boolean,
        .keyword => |value| std.mem.eql(u8, value, right.keyword),
        .instant => |value| value.epoch_seconds == right.instant.epoch_seconds and
            value.nanosecond == right.instant.nanosecond,
    };
}

fn validateSpaceId(space_id: []const u8) EncodeError!void {
    if (space_id.len == 0 or space_id.len > max_space_id_bytes or
        !std.unicode.utf8ValidateSlice(space_id))
    {
        return error.InvalidSpaceId;
    }
}

fn validateExpectedSpaceId(space_id: []const u8) error{InvalidSpaceId}!void {
    if (space_id.len == 0 or space_id.len > max_space_id_bytes or
        !std.unicode.utf8ValidateSlice(space_id))
    {
        return error.InvalidSpaceId;
    }
}

fn validateTransaction(transaction: Transaction) EncodeError!void {
    if (transaction.ops.len == 0) return error.EmptyTransaction;
    if (transaction.ops.len > std.math.maxInt(u32)) return error.TooManyOperations;
    for (transaction.ops, 0..) |op, index| {
        if (op.ordinal != index) return error.NonCanonicalOrdinal;
        try validateTerm(.{ .triple = &op.triple }, 0);
    }
}

fn validateTerm(term: Term, depth: usize) EncodeError!void {
    if (depth > max_term_depth) return error.TermTooDeep;
    switch (term) {
        .atom => |atom| switch (atom) {
            .string => |value| if (!std.unicode.utf8ValidateSlice(value))
                return error.InvalidAtomUtf8,
            .keyword => |value| {
                if (value.len == 0 or value[0] == ':' or
                    !std.unicode.utf8ValidateSlice(value))
                {
                    return error.InvalidAtom;
                }
            },
            .instant => |value| if (value.nanosecond > 999_999_999)
                return error.InvalidInstant,
            else => {},
        },
        .triple => |triple| {
            try validateTerm(triple.slot0, depth + 1);
            try validateTerm(triple.slot1, depth + 1);
            try validateTerm(triple.slot2, depth + 1);
        },
    }
}

fn writeTerm(writer: *Writer, term: Term, depth: usize) EncodeError!void {
    if (depth > max_term_depth) return error.TermTooDeep;
    switch (term) {
        .atom => |atom| switch (atom) {
            .string => |value| {
                if (!std.unicode.utf8ValidateSlice(value)) return error.InvalidAtomUtf8;
                try writeByte(writer, @intFromEnum(TermTag.string));
                try writeLength(writer, value.len);
                try writeBytes(writer, value);
            },
            .integer => |value| {
                try writeByte(writer, @intFromEnum(TermTag.integer));
                try writeInt(writer, i64, value);
            },
            .float => |value| {
                try writeByte(writer, @intFromEnum(TermTag.float));
                try writeInt(writer, u64, @bitCast(value));
            },
            .boolean => |value| try writeByte(writer, @intFromEnum(
                if (value) TermTag.bool_true else TermTag.bool_false,
            )),
            .keyword => |value| {
                if (value.len == 0 or value[0] == ':' or
                    !std.unicode.utf8ValidateSlice(value))
                {
                    return error.InvalidAtom;
                }
                try writeByte(writer, @intFromEnum(TermTag.keyword));
                try writeLength(writer, value.len);
                try writeBytes(writer, value);
            },
            .instant => |value| {
                if (value.nanosecond > 999_999_999) return error.InvalidInstant;
                try writeByte(writer, @intFromEnum(TermTag.instant));
                try writeInt(writer, i64, value.epoch_seconds);
                try writeInt(writer, u32, value.nanosecond);
            },
        },
        .triple => |triple| {
            try writeByte(writer, @intFromEnum(TermTag.triple));
            try writeTerm(writer, triple.slot0, depth + 1);
            try writeTerm(writer, triple.slot1, depth + 1);
            try writeTerm(writer, triple.slot2, depth + 1);
        },
    }
}

fn writeLength(writer: *Writer, length: usize) EncodeError!void {
    if (length > std.math.maxInt(u32)) return error.FrameTooLarge;
    try writeInt(writer, u32, @intCast(length));
}

fn writeBytes(writer: *Writer, bytes: []const u8) Allocator.Error!void {
    writer.writeAll(bytes) catch return error.OutOfMemory;
}

fn writeByte(writer: *Writer, byte: u8) Allocator.Error!void {
    writer.writeByte(byte) catch return error.OutOfMemory;
}

fn writeInt(writer: *Writer, comptime T: type, value: T) Allocator.Error!void {
    var bytes: [@sizeOf(T)]u8 = undefined;
    std.mem.writeInt(T, &bytes, value, .little);
    try writeBytes(writer, &bytes);
}

const HeaderView = struct {
    space_id: []const u8,
    end_offset: usize,
};

const HeaderError = error{
    MigrationRequired,
    InvalidHeader,
};

fn parseHeader(bytes: []const u8) HeaderError!HeaderView {
    if (bytes.len < format_magic.len or
        !std.mem.eql(u8, bytes[0..format_magic.len], format_magic))
    {
        return error.MigrationRequired;
    }
    if (bytes.len < fixed_header_bytes) return error.InvalidHeader;
    const version = readIntAt(u16, bytes, format_magic.len);
    const flags = readIntAt(u16, bytes, format_magic.len + @sizeOf(u16));
    if (version != format_version or flags != format_flags)
        return error.MigrationRequired;

    const length_offset = format_magic.len + @sizeOf(u16) + @sizeOf(u16);
    const space_len: usize = readIntAt(u32, bytes, length_offset);
    if (space_len == 0 or space_len > max_space_id_bytes or
        bytes.len - fixed_header_bytes < space_len)
    {
        return error.InvalidHeader;
    }
    const space_id = bytes[fixed_header_bytes .. fixed_header_bytes + space_len];
    if (!std.unicode.utf8ValidateSlice(space_id)) return error.InvalidHeader;
    return .{
        .space_id = space_id,
        .end_offset = fixed_header_bytes + space_len,
    };
}

const DecodeError = error{
    InvalidFrame,
    TermTooDeep,
    OutOfMemory,
};

const Cursor = struct {
    input: []const u8,
    pos: usize = 0,

    fn readByte(self: *Cursor) DecodeError!u8 {
        if (self.pos >= self.input.len) return error.InvalidFrame;
        const result = self.input[self.pos];
        self.pos += 1;
        return result;
    }

    fn readInt(self: *Cursor, comptime T: type) DecodeError!T {
        if (self.input.len - self.pos < @sizeOf(T)) return error.InvalidFrame;
        const result = readIntAt(T, self.input, self.pos);
        self.pos += @sizeOf(T);
        return result;
    }

    fn readSlice(self: *Cursor, length: usize) DecodeError![]const u8 {
        if (self.input.len - self.pos < length) return error.InvalidFrame;
        const result = self.input[self.pos .. self.pos + length];
        self.pos += length;
        return result;
    }
};

fn readIntAt(comptime T: type, bytes: []const u8, offset: usize) T {
    return std.mem.readInt(T, bytes[offset..][0..@sizeOf(T)], .little);
}

fn parseTransaction(allocator: Allocator, payload: []const u8) DecodeError!Transaction {
    var cursor: Cursor = .{ .input = payload };
    const tx_seq = try cursor.readInt(i64);
    const op_count = try cursor.readInt(u32);
    if (op_count == 0) return error.InvalidFrame;

    var ops: std.ArrayList(Op) = .empty;
    defer ops.deinit(allocator);
    try ops.ensureTotalCapacity(allocator, op_count);
    for (0..op_count) |index| {
        const ordinal = try cursor.readInt(u32);
        if (ordinal != index) return error.InvalidFrame;
        const action = std.enums.fromInt(Action, try cursor.readByte()) orelse
            return error.InvalidFrame;
        const root_tag = std.enums.fromInt(TermTag, try cursor.readByte()) orelse
            return error.InvalidFrame;
        if (root_tag != .triple) return error.InvalidFrame;
        const triple = try parseTripleAfterTag(allocator, &cursor, 0);
        try ops.append(allocator, .{
            .ordinal = ordinal,
            .action = action,
            .triple = triple,
        });
    }
    if (cursor.pos != payload.len) return error.InvalidFrame;
    return .{
        .tx_seq = tx_seq,
        .ops = try ops.toOwnedSlice(allocator),
    };
}

fn parseTerm(allocator: Allocator, cursor: *Cursor, depth: usize) DecodeError!Term {
    if (depth > max_term_depth) return error.TermTooDeep;
    const tag = std.enums.fromInt(TermTag, try cursor.readByte()) orelse
        return error.InvalidFrame;
    return switch (tag) {
        .string => .{ .atom = .{ .string = try parseText(allocator, cursor, false) } },
        .integer => .{ .atom = .{ .integer = try cursor.readInt(i64) } },
        .float => .{ .atom = .{ .float = @bitCast(try cursor.readInt(u64)) } },
        .bool_false => .{ .atom = .{ .boolean = false } },
        .bool_true => .{ .atom = .{ .boolean = true } },
        .keyword => .{ .atom = .{ .keyword = try parseText(allocator, cursor, true) } },
        .instant => instant: {
            const value: Instant = .{
                .epoch_seconds = try cursor.readInt(i64),
                .nanosecond = try cursor.readInt(u32),
            };
            if (value.nanosecond > 999_999_999) return error.InvalidFrame;
            break :instant .{ .atom = .{ .instant = value } };
        },
        .triple => triple: {
            const value = try allocator.create(Triple);
            value.* = try parseTripleAfterTag(allocator, cursor, depth);
            break :triple .{ .triple = value };
        },
    };
}

fn parseTripleAfterTag(
    allocator: Allocator,
    cursor: *Cursor,
    depth: usize,
) DecodeError!Triple {
    if (depth > max_term_depth) return error.TermTooDeep;
    return .{
        .slot0 = try parseTerm(allocator, cursor, depth + 1),
        .slot1 = try parseTerm(allocator, cursor, depth + 1),
        .slot2 = try parseTerm(allocator, cursor, depth + 1),
    };
}

fn parseText(
    allocator: Allocator,
    cursor: *Cursor,
    keyword: bool,
) DecodeError![]const u8 {
    const length = try cursor.readInt(u32);
    const value = try cursor.readSlice(length);
    if (!std.unicode.utf8ValidateSlice(value) or
        (keyword and (value.len == 0 or value[0] == ':')))
    {
        return error.InvalidFrame;
    }
    return allocator.dupe(u8, value);
}

fn stringTerm(value: []const u8) Term {
    return .{ .atom = .{ .string = value } };
}

fn keywordTerm(value: []const u8) Term {
    return .{ .atom = .{ .keyword = value } };
}

test "recursive triples and typed atoms roundtrip deterministically" {
    const allocator = std.testing.allocator;

    const in_slot0: Triple = .{
        .slot0 = stringTerm("Alice"),
        .slot1 = keywordTerm("contact/email"),
        .slot2 = .{ .atom = .{ .boolean = true } },
    };
    const in_slot1: Triple = .{
        .slot0 = .{ .atom = .{ .integer = -42 } },
        .slot1 = .{ .atom = .{ .float = -0.0 } },
        .slot2 = .{ .atom = .{ .instant = .{
            .epoch_seconds = 1_775_000_000,
            .nanosecond = 123_456_789,
        } } },
    };
    const in_slot2: Triple = .{
        .slot0 = keywordTerm("kernel/tx-sequence"),
        .slot1 = stringTerm("same bytes, different atom type"),
        .slot2 = .{ .atom = .{ .integer = 7 } },
    };
    const root: Triple = .{
        .slot0 = .{ .triple = &in_slot0 },
        .slot1 = .{ .triple = &in_slot1 },
        .slot2 = .{ .triple = &in_slot2 },
    };
    const ops = [_]Op{
        .{ .ordinal = 0, .action = .assert, .triple = root },
        .{ .ordinal = 1, .action = .retract, .triple = in_slot0 },
    };
    const transactions = [_]Transaction{.{
        .tx_seq = 1842,
        .ops = &ops,
    }};

    const first = try encodeLog(allocator, "msa-space", &transactions);
    defer allocator.free(first);
    const second = try encodeLog(allocator, "msa-space", &transactions);
    defer allocator.free(second);
    try std.testing.expectEqualSlices(u8, first, second);
    try std.testing.expectEqualSlices(
        u8,
        "FRAMLOG\x00\x01\x00\x00\x00\x09\x00\x00\x00msa-space",
        first[0 .. fixed_header_bytes + "msa-space".len],
    );

    var outcome = try replayBytesForSpace(allocator, first, "msa-space");
    switch (outcome) {
        .corrupt => return error.TestUnexpectedResult,
        .replay => |*replay| {
            defer replay.deinit();
            try std.testing.expectEqualStrings("msa-space", replay.space_id);
            try std.testing.expectEqual(@as(usize, first.len), replay.valid_bytes);
            try std.testing.expect(replay.torn_tail == null);
            try std.testing.expectEqual(@as(usize, 1), replay.transactions.len);
            try std.testing.expectEqual(@as(i64, 1842), replay.transactions[0].tx_seq);
            try std.testing.expectEqual(@as(usize, 2), replay.transactions[0].ops.len);
            try std.testing.expectEqual(@as(u32, 1), replay.transactions[0].ops[1].ordinal);
            try std.testing.expectEqual(Action.retract, replay.transactions[0].ops[1].action);
            try std.testing.expect(tripleEql(root, replay.transactions[0].ops[0].triple));
            try std.testing.expect(!termEql(
                keywordTerm("contact/email"),
                stringTerm("contact/email"),
            ));

            const reencoded = try encodeLog(allocator, replay.space_id, replay.transactions);
            defer allocator.free(reencoded);
            try std.testing.expectEqualSlices(u8, first, reencoded);
        },
    }
}

test "missing legacy and incompatible headers require migration" {
    const allocator = std.testing.allocator;
    try std.testing.expectError(error.MigrationRequired, replayBytes(allocator, ""));
    try std.testing.expectError(
        error.MigrationRequired,
        replayBytes(allocator, "{:tx 1, :op \"assert\", :l \"@a\", :p \"title\", :r \"A\"}\n"),
    );

    const image = try encodeLog(allocator, "coordination", &.{});
    defer allocator.free(image);
    const wrong_version = try allocator.dupe(u8, image);
    defer allocator.free(wrong_version);
    wrong_version[format_magic.len] = 2;
    try std.testing.expectError(
        error.MigrationRequired,
        replayBytes(allocator, wrong_version),
    );
    try std.testing.expectError(
        error.SpaceMismatch,
        replayBytesForSpace(allocator, image, "telemetry"),
    );
}

test "v1 byte fixture locks the cross-runtime ABI" {
    const allocator = std.testing.allocator;
    const triple: Triple = .{
        .slot0 = stringTerm("Alice"),
        .slot1 = keywordTerm("email"),
        .slot2 = stringTerm("alice@example.com"),
    };
    const ops = [_]Op{.{ .ordinal = 0, .action = .assert, .triple = triple }};
    const transactions = [_]Transaction{.{ .tx_seq = 1842, .ops = &ops }};
    const image = try encodeLog(allocator, "msa-space", &transactions);
    defer allocator.free(image);

    const expected_hex =
        "4652414d4c4f470001000000090000006d73612d7370616365" ++
        "3c0000003207000000000000010000000000000001070105000000416c696365" ++
        "0605000000656d61696c0111000000616c696365406578616d706c652e636f6d" ++
        "d42d3294";
    var expected: [expected_hex.len / 2]u8 = undefined;
    _ = try std.fmt.hexToBytes(&expected, expected_hex);
    try std.testing.expectEqualSlices(u8, &expected, image);
    try std.testing.expectEqual(@as(u32, 0x94322dd4), std.hash.Crc32.hash(
        image[fixed_header_bytes + "msa-space".len + @sizeOf(u32) .. image.len - @sizeOf(u32)],
    ));
}

test "a torn final frame drops its whole transaction and completed damage corrupts" {
    const allocator = std.testing.allocator;
    const triple: Triple = .{
        .slot0 = stringTerm("Alice"),
        .slot1 = keywordTerm("email"),
        .slot2 = stringTerm("alice@example.com"),
    };
    const first_ops = [_]Op{.{ .ordinal = 0, .action = .assert, .triple = triple }};
    const second_ops = [_]Op{
        .{ .ordinal = 0, .action = .retract, .triple = triple },
        .{ .ordinal = 1, .action = .assert, .triple = triple },
    };
    const first_tx: Transaction = .{ .tx_seq = 10, .ops = &first_ops };
    const second_tx: Transaction = .{ .tx_seq = 11, .ops = &second_ops };

    const header = try encodeHeader(allocator, "coordination");
    defer allocator.free(header);
    const frame1 = try encodeTransactionFrame(allocator, first_tx);
    defer allocator.free(frame1);
    const frame2 = try encodeTransactionFrame(allocator, second_tx);
    defer allocator.free(frame2);
    const complete = try std.mem.concat(allocator, u8, &.{ header, frame1, frame2 });
    defer allocator.free(complete);
    const frame2_offset = header.len + frame1.len;

    var torn_outcome = try replayBytes(allocator, complete[0 .. complete.len - 3]);
    switch (torn_outcome) {
        .corrupt => return error.TestUnexpectedResult,
        .replay => |*replay| {
            defer replay.deinit();
            try std.testing.expectEqual(@as(usize, 1), replay.transactions.len);
            try std.testing.expectEqual(frame2_offset, replay.valid_bytes);
            try std.testing.expectEqual(frame2_offset, replay.torn_tail.?.byte_offset);
            try std.testing.expectEqual(@as(usize, 1), replay.torn_tail.?.recovered_transactions);
        },
    }

    const damaged = try allocator.dupe(u8, complete);
    defer allocator.free(damaged);
    damaged[frame2_offset + @sizeOf(u32) + 1] ^= 0x01;
    var damaged_outcome = try replayBytes(allocator, damaged);
    switch (damaged_outcome) {
        .replay => |*replay| {
            replay.deinit();
            return error.TestUnexpectedResult;
        },
        .corrupt => |corruption| {
            try std.testing.expectEqual(frame2_offset, corruption.byte_offset);
            try std.testing.expectEqual(CorruptionReason.checksum_mismatch, corruption.reason);
        },
    }
}

test "transaction canonicality and instant bounds are enforced" {
    const allocator = std.testing.allocator;
    const triple: Triple = .{
        .slot0 = stringTerm("s"),
        .slot1 = keywordTerm("p"),
        .slot2 = stringTerm("o"),
    };
    try std.testing.expectError(
        error.EmptyTransaction,
        encodeTransactionFrame(allocator, .{ .tx_seq = 1, .ops = &.{} }),
    );
    const bad_ordinal = [_]Op{.{ .ordinal = 1, .action = .assert, .triple = triple }};
    try std.testing.expectError(
        error.NonCanonicalOrdinal,
        encodeTransactionFrame(allocator, .{ .tx_seq = 1, .ops = &bad_ordinal }),
    );
    const bad_instant: Triple = .{
        .slot0 = .{ .atom = .{ .instant = .{
            .epoch_seconds = 0,
            .nanosecond = 1_000_000_000,
        } } },
        .slot1 = keywordTerm("recorded-at"),
        .slot2 = stringTerm("invalid"),
    };
    const bad_instant_ops = [_]Op{.{
        .ordinal = 0,
        .action = .assert,
        .triple = bad_instant,
    }};
    try std.testing.expectError(
        error.InvalidInstant,
        encodeTransactionFrame(allocator, .{ .tx_seq = 1, .ops = &bad_instant_ops }),
    );

    var invalid_instant_wire: [1 + @sizeOf(i64) + @sizeOf(u32)]u8 = undefined;
    invalid_instant_wire[0] = @intFromEnum(TermTag.instant);
    std.mem.writeInt(i64, invalid_instant_wire[1..9], 0, .little);
    std.mem.writeInt(u32, invalid_instant_wire[9..13], 1_000_000_000, .little);
    var cursor: Cursor = .{ .input = &invalid_instant_wire };
    var arena = std.heap.ArenaAllocator.init(allocator);
    defer arena.deinit();
    try std.testing.expectError(
        error.InvalidFrame,
        parseTerm(arena.allocator(), &cursor, 0),
    );
}

test "durable append fences space and size and atomic rewrite stays replayable" {
    const allocator = std.testing.allocator;
    const io = std.testing.io;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();

    const empty = try encodeLog(allocator, "coordination", &.{});
    defer allocator.free(empty);
    try rewriteDurableAtomic(
        allocator,
        io,
        tmp.dir,
        "facts.log",
        "coordination",
        empty,
    );

    const triple: Triple = .{
        .slot0 = stringTerm("Alice"),
        .slot1 = keywordTerm("email"),
        .slot2 = stringTerm("alice@example.com"),
    };
    const ops = [_]Op{.{ .ordinal = 0, .action = .assert, .triple = triple }};
    const transaction: Transaction = .{ .tx_seq = 1, .ops = &ops };
    const new_size = try appendTransactionDurable(
        allocator,
        io,
        tmp.dir,
        "facts.log",
        "coordination",
        empty.len,
        transaction,
    );
    try std.testing.expect(new_size > empty.len);
    try std.testing.expectError(
        error.LogAdvanced,
        appendTransactionDurable(
            allocator,
            io,
            tmp.dir,
            "facts.log",
            "coordination",
            empty.len,
            transaction,
        ),
    );
    try std.testing.expectError(
        error.SpaceMismatch,
        appendTransactionDurable(
            allocator,
            io,
            tmp.dir,
            "facts.log",
            "telemetry",
            new_size,
            transaction,
        ),
    );

    var replay_outcome = try replayFile(
        allocator,
        io,
        tmp.dir,
        "facts.log",
        4096,
    );
    switch (replay_outcome) {
        .corrupt => return error.TestUnexpectedResult,
        .replay => |*replay| {
            defer replay.deinit();
            try std.testing.expectEqualStrings("coordination", replay.space_id);
            try std.testing.expectEqual(@as(usize, 1), replay.transactions.len);
            try std.testing.expectEqual(@as(usize, new_size), replay.valid_bytes);
        },
    }
}
