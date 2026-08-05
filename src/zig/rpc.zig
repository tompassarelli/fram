//! Frozen FRAMRPC v1 codec shared by the live server and native test peer.

const std = @import("std");
const log = @import("log.zig");

const Allocator = std.mem.Allocator;
const Writer = std.Io.Writer;

pub const format_magic: []const u8 = "FRAMRPC\x00";
pub const format_major: u16 = 1;
pub const format_minor: u16 = 0;
pub const format_flags: u8 = 0;
pub const fixed_header_bytes: usize = format_magic.len +
    @sizeOf(u16) + @sizeOf(u16) + @sizeOf(u8) + @sizeOf(u8) +
    @sizeOf(u32) + @sizeOf(u64);
pub const max_body_bytes: usize = 1024 * 1024;
pub const term_limits: log.TermLimits = .{
    .max_depth = log.max_term_depth,
    .max_nodes = 65_536,
    .max_string_bytes = max_body_bytes,
    .max_total_string_bytes = max_body_bytes,
};

comptime {
    std.debug.assert(fixed_header_bytes == 26);
}

pub const Kind = enum(u8) {
    request = 1,
    response = 2,
    cancel = 3,
    event = 4,
};

pub const PageRequest = struct {
    limit: u32,
    cursor: ?log.Term,
};

pub const PageResponse = struct {
    ordinal: u32,
    next: ?log.Term,
    done: bool,
};

pub const Error = struct {
    code: log.Term,
    retryable: bool,
    message: log.Term,
    detail: ?log.Term,
};

pub const Request = struct {
    space: log.Term,
    op: log.Term,
    expected_version: ?i64,
    page: ?PageRequest,
    timeout_ms: ?u32,
    payload: log.Term,
};

pub const Response = struct {
    space: log.Term,
    op: log.Term,
    served_version: i64,
    page: ?PageResponse,
    @"error": ?Error,
    payload: ?log.Term,
};

pub const Event = Response;

pub const Message = union(Kind) {
    request: Request,
    response: Response,
    cancel: void,
    event: Event,
};

pub const Frame = struct {
    request_id: u64,
    message: Message,
};

pub const DecodedFrame = struct {
    arena: std.heap.ArenaAllocator,
    request_id: u64,
    message: Message,

    pub fn deinit(frame: *DecodedFrame) void {
        frame.arena.deinit();
        frame.* = undefined;
    }
};

pub const EncodeError = Allocator.Error || log.EncodeError || log.TermMeasureError || error{
    InvalidSpaceId,
    InvalidOperation,
    InvalidErrorCode,
    InvalidErrorMessage,
    BodyTooLarge,
};

pub const DecodeError = Allocator.Error || log.TermDecodeError || error{
    InvalidMagic,
    UnsupportedVersion,
    InvalidKind,
    InvalidFlags,
    BodyTooLarge,
    TruncatedFrame,
    TrailingFrameBytes,
    TrailingBodyBytes,
    InvalidCancelBody,
    InvalidPresence,
    InvalidBoolean,
    InvalidSpaceId,
    InvalidOperation,
    InvalidErrorCode,
    InvalidErrorMessage,
};

pub fn encodeFrame(allocator: Allocator, frame: Frame) EncodeError![]u8 {
    const body_len = try measureBody(frame.message);

    var out: Writer.Allocating = .init(allocator);
    defer out.deinit();
    try writeBytes(&out.writer, format_magic);
    try writeInt(&out.writer, u16, format_major);
    try writeInt(&out.writer, u16, format_minor);
    try writeByte(&out.writer, @intFromEnum(frame.message));
    try writeByte(&out.writer, format_flags);
    try writeInt(&out.writer, u32, @intCast(body_len));
    try writeInt(&out.writer, u64, frame.request_id);
    try writeBody(&out.writer, frame.message);
    std.debug.assert(out.written().len == fixed_header_bytes + body_len);
    return out.toOwnedSlice();
}

pub fn decodeFrame(allocator: Allocator, bytes: []const u8) DecodeError!DecodedFrame {
    const header = try parseHeader(bytes);
    const body = bytes[fixed_header_bytes..];

    var arena = std.heap.ArenaAllocator.init(allocator);
    var arena_moved = false;
    defer if (!arena_moved) arena.deinit();

    var cursor: BodyCursor = .{ .input = body };
    const message = try parseBody(arena.allocator(), header.kind, &cursor);
    if (cursor.pos != body.len) {
        if (header.kind == .cancel) return error.InvalidCancelBody;
        return error.TrailingBodyBytes;
    }

    arena_moved = true;
    return .{
        .arena = arena,
        .request_id = header.request_id,
        .message = message,
    };
}

const Header = struct {
    kind: Kind,
    request_id: u64,
};

fn parseHeader(bytes: []const u8) DecodeError!Header {
    if (bytes.len < format_magic.len) return error.TruncatedFrame;
    if (!std.mem.eql(u8, bytes[0..format_magic.len], format_magic))
        return error.InvalidMagic;
    if (bytes.len < fixed_header_bytes) return error.TruncatedFrame;

    const major = readIntAt(u16, bytes, format_magic.len);
    const minor = readIntAt(u16, bytes, format_magic.len + @sizeOf(u16));
    if (major != format_major or minor != format_minor)
        return error.UnsupportedVersion;

    const kind_offset = format_magic.len + @sizeOf(u16) + @sizeOf(u16);
    const kind = std.enums.fromInt(Kind, bytes[kind_offset]) orelse
        return error.InvalidKind;
    if (bytes[kind_offset + 1] != format_flags) return error.InvalidFlags;

    const length_offset = kind_offset + 2;
    const body_len: usize = readIntAt(u32, bytes, length_offset);
    if (body_len > max_body_bytes) return error.BodyTooLarge;
    const actual_body_len = bytes.len - fixed_header_bytes;
    if (actual_body_len < body_len) return error.TruncatedFrame;
    if (actual_body_len > body_len) return error.TrailingFrameBytes;

    return .{
        .kind = kind,
        .request_id = readIntAt(u64, bytes, length_offset + @sizeOf(u32)),
    };
}

fn measureBody(message: Message) EncodeError!usize {
    var length: usize = 0;
    switch (message) {
        .request => |request| {
            try validateSpace(request.space);
            try validateOperation(request.op);
            try addTermLength(&length, request.space);
            try addTermLength(&length, request.op);
            try addOptionalIntLength(&length, i64, request.expected_version);
            try addBytes(&length, 1);
            if (request.page) |page| {
                try addBytes(&length, @sizeOf(u32) + 1);
                if (page.cursor) |cursor| try addTermLength(&length, cursor);
            }
            try addOptionalIntLength(&length, u32, request.timeout_ms);
            try addTermLength(&length, request.payload);
        },
        .response => |response| try measureResponse(&length, response),
        .cancel => {},
        .event => |event| try measureResponse(&length, event),
    }
    return length;
}

fn measureResponse(length: *usize, response: Response) EncodeError!void {
    try validateSpace(response.space);
    try validateOperation(response.op);
    try addTermLength(length, response.space);
    try addTermLength(length, response.op);
    try addBytes(length, @sizeOf(i64) + 1);
    if (response.page) |page| {
        try addBytes(length, @sizeOf(u32) + 1);
        if (page.next) |next| try addTermLength(length, next);
        try addBytes(length, 1);
    }
    try addBytes(length, 1);
    if (response.@"error") |value| {
        try validateErrorCode(value.code);
        try validateErrorMessage(value.message);
        try addTermLength(length, value.code);
        try addBytes(length, 1);
        try addTermLength(length, value.message);
        try addBytes(length, 1);
        if (value.detail) |detail| try addTermLength(length, detail);
    }
    try addBytes(length, 1);
    if (response.payload) |payload| try addTermLength(length, payload);
}

fn addOptionalIntLength(
    length: *usize,
    comptime T: type,
    value: ?T,
) EncodeError!void {
    try addBytes(length, 1);
    if (value != null) try addBytes(length, @sizeOf(T));
}

fn addTermLength(length: *usize, term: log.Term) EncodeError!void {
    const measured = try log.TermCodecV1.measure(term, term_limits);
    try addBytes(length, measured.encoded_bytes);
}

fn addBytes(length: *usize, count: usize) EncodeError!void {
    length.* = std.math.add(usize, length.*, count) catch
        return error.BodyTooLarge;
    if (length.* > max_body_bytes or length.* > std.math.maxInt(u32))
        return error.BodyTooLarge;
}

fn writeBody(writer: *Writer, message: Message) EncodeError!void {
    switch (message) {
        .request => |request| {
            try log.TermCodecV1.append(writer, request.space);
            try log.TermCodecV1.append(writer, request.op);
            try writeOptionalInt(writer, i64, request.expected_version);
            try writePresence(writer, request.page != null);
            if (request.page) |page| {
                try writeInt(writer, u32, page.limit);
                try writePresence(writer, page.cursor != null);
                if (page.cursor) |cursor| try log.TermCodecV1.append(writer, cursor);
            }
            try writeOptionalInt(writer, u32, request.timeout_ms);
            try log.TermCodecV1.append(writer, request.payload);
        },
        .response => |response| try writeResponse(writer, response),
        .cancel => {},
        .event => |event| try writeResponse(writer, event),
    }
}

fn writeResponse(writer: *Writer, response: Response) EncodeError!void {
    try log.TermCodecV1.append(writer, response.space);
    try log.TermCodecV1.append(writer, response.op);
    try writeInt(writer, i64, response.served_version);
    try writePresence(writer, response.page != null);
    if (response.page) |page| {
        try writeInt(writer, u32, page.ordinal);
        try writePresence(writer, page.next != null);
        if (page.next) |next| try log.TermCodecV1.append(writer, next);
        try writeBoolean(writer, page.done);
    }
    try writePresence(writer, response.@"error" != null);
    if (response.@"error") |value| {
        try log.TermCodecV1.append(writer, value.code);
        try writeBoolean(writer, value.retryable);
        try log.TermCodecV1.append(writer, value.message);
        try writePresence(writer, value.detail != null);
        if (value.detail) |detail| try log.TermCodecV1.append(writer, detail);
    }
    try writePresence(writer, response.payload != null);
    if (response.payload) |payload| try log.TermCodecV1.append(writer, payload);
}

fn writeOptionalInt(writer: *Writer, comptime T: type, value: ?T) EncodeError!void {
    try writePresence(writer, value != null);
    if (value) |present| try writeInt(writer, T, present);
}

fn writePresence(writer: *Writer, present: bool) Allocator.Error!void {
    try writeByte(writer, @intFromBool(present));
}

fn writeBoolean(writer: *Writer, value: bool) Allocator.Error!void {
    try writeByte(writer, @intFromBool(value));
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

const BodyCursor = struct {
    input: []const u8,
    pos: usize = 0,

    fn readByte(cursor: *BodyCursor) DecodeError!u8 {
        if (cursor.pos >= cursor.input.len) return error.TruncatedFrame;
        const result = cursor.input[cursor.pos];
        cursor.pos += 1;
        return result;
    }

    fn readInt(cursor: *BodyCursor, comptime T: type) DecodeError!T {
        if (cursor.input.len - cursor.pos < @sizeOf(T))
            return error.TruncatedFrame;
        const result = readIntAt(T, cursor.input, cursor.pos);
        cursor.pos += @sizeOf(T);
        return result;
    }

    fn readPresence(cursor: *BodyCursor) DecodeError!bool {
        return switch (try cursor.readByte()) {
            0 => false,
            1 => true,
            else => error.InvalidPresence,
        };
    }

    fn readBoolean(cursor: *BodyCursor) DecodeError!bool {
        return switch (try cursor.readByte()) {
            0 => false,
            1 => true,
            else => error.InvalidBoolean,
        };
    }

    fn readTerm(cursor: *BodyCursor, allocator: Allocator) DecodeError!log.Term {
        const decoded = try log.TermCodecV1.decodePrefix(
            allocator,
            cursor.input[cursor.pos..],
            term_limits,
        );
        cursor.pos += decoded.consumed;
        return decoded.term;
    }
};

fn parseBody(
    allocator: Allocator,
    kind: Kind,
    cursor: *BodyCursor,
) DecodeError!Message {
    return switch (kind) {
        .request => .{ .request = try parseRequest(allocator, cursor) },
        .response => .{ .response = try parseResponse(allocator, cursor) },
        .cancel => .{ .cancel = {} },
        .event => .{ .event = try parseResponse(allocator, cursor) },
    };
}

fn parseRequest(allocator: Allocator, cursor: *BodyCursor) DecodeError!Request {
    const space = try cursor.readTerm(allocator);
    try validateDecodedSpace(space);
    const op = try cursor.readTerm(allocator);
    try validateDecodedOperation(op);

    const expected_version = if (try cursor.readPresence())
        try cursor.readInt(i64)
    else
        null;
    const page = if (try cursor.readPresence()) PageRequest{
        .limit = try cursor.readInt(u32),
        .cursor = if (try cursor.readPresence())
            try cursor.readTerm(allocator)
        else
            null,
    } else null;
    const timeout_ms = if (try cursor.readPresence())
        try cursor.readInt(u32)
    else
        null;
    return .{
        .space = space,
        .op = op,
        .expected_version = expected_version,
        .page = page,
        .timeout_ms = timeout_ms,
        .payload = try cursor.readTerm(allocator),
    };
}

fn parseResponse(allocator: Allocator, cursor: *BodyCursor) DecodeError!Response {
    const space = try cursor.readTerm(allocator);
    try validateDecodedSpace(space);
    const op = try cursor.readTerm(allocator);
    try validateDecodedOperation(op);
    const served_version = try cursor.readInt(i64);
    const page = if (try cursor.readPresence()) PageResponse{
        .ordinal = try cursor.readInt(u32),
        .next = if (try cursor.readPresence())
            try cursor.readTerm(allocator)
        else
            null,
        .done = try cursor.readBoolean(),
    } else null;
    const error_value = if (try cursor.readPresence()) try parseError(allocator, cursor) else null;
    const payload = if (try cursor.readPresence()) try cursor.readTerm(allocator) else null;
    return .{
        .space = space,
        .op = op,
        .served_version = served_version,
        .page = page,
        .@"error" = error_value,
        .payload = payload,
    };
}

fn parseError(allocator: Allocator, cursor: *BodyCursor) DecodeError!Error {
    const code = try cursor.readTerm(allocator);
    try validateDecodedErrorCode(code);
    const retryable = try cursor.readBoolean();
    const message = try cursor.readTerm(allocator);
    try validateDecodedErrorMessage(message);
    return .{
        .code = code,
        .retryable = retryable,
        .message = message,
        .detail = if (try cursor.readPresence())
            try cursor.readTerm(allocator)
        else
            null,
    };
}

fn validateSpace(term: log.Term) EncodeError!void {
    const value = switch (term) {
        .atom => |atom| switch (atom) {
            .string => |text| text,
            else => return error.InvalidSpaceId,
        },
        .triple => return error.InvalidSpaceId,
    };
    if (value.len == 0 or value.len > log.max_space_id_bytes)
        return error.InvalidSpaceId;
}

fn validateOperation(term: log.Term) EncodeError!void {
    switch (term) {
        .atom => |atom| switch (atom) {
            .keyword => {},
            else => return error.InvalidOperation,
        },
        .triple => return error.InvalidOperation,
    }
}

fn validateErrorCode(term: log.Term) EncodeError!void {
    switch (term) {
        .atom => |atom| switch (atom) {
            .keyword => {},
            else => return error.InvalidErrorCode,
        },
        .triple => return error.InvalidErrorCode,
    }
}

fn validateErrorMessage(term: log.Term) EncodeError!void {
    switch (term) {
        .atom => |atom| switch (atom) {
            .string => {},
            else => return error.InvalidErrorMessage,
        },
        .triple => return error.InvalidErrorMessage,
    }
}

fn validateDecodedSpace(term: log.Term) DecodeError!void {
    const value = switch (term) {
        .atom => |atom| switch (atom) {
            .string => |text| text,
            else => return error.InvalidSpaceId,
        },
        .triple => return error.InvalidSpaceId,
    };
    if (value.len == 0 or value.len > log.max_space_id_bytes)
        return error.InvalidSpaceId;
}

fn validateDecodedOperation(term: log.Term) DecodeError!void {
    switch (term) {
        .atom => |atom| switch (atom) {
            .keyword => {},
            else => return error.InvalidOperation,
        },
        .triple => return error.InvalidOperation,
    }
}

fn validateDecodedErrorCode(term: log.Term) DecodeError!void {
    switch (term) {
        .atom => |atom| switch (atom) {
            .keyword => {},
            else => return error.InvalidErrorCode,
        },
        .triple => return error.InvalidErrorCode,
    }
}

fn validateDecodedErrorMessage(term: log.Term) DecodeError!void {
    switch (term) {
        .atom => |atom| switch (atom) {
            .string => {},
            else => return error.InvalidErrorMessage,
        },
        .triple => return error.InvalidErrorMessage,
    }
}

fn readIntAt(comptime T: type, bytes: []const u8, offset: usize) T {
    return std.mem.readInt(T, bytes[offset..][0..@sizeOf(T)], .little);
}

fn stringTerm(value: []const u8) log.Term {
    return .{ .atom = .{ .string = value } };
}

fn keywordTerm(value: []const u8) log.Term {
    return .{ .atom = .{ .keyword = value } };
}

test "v1 golden covers every message kind, atom, recursive option, and typed error" {
    const allocator = std.testing.allocator;
    const cursor_triple: log.Triple = .{
        .t1 = stringTerm("cursor"),
        .t2 = keywordTerm("after"),
        .t3 = .{ .atom = .{ .integer = 7 } },
    };
    const numeric_triple: log.Triple = .{
        .t1 = .{ .atom = .{ .integer = -42 } },
        .t2 = .{ .atom = .{ .float = 1.5 } },
        .t3 = .{ .atom = .{ .instant = .{
            .epoch_seconds = 1_775_000_000,
            .nanosecond = 123_456_789,
        } } },
    };
    const payload_triple: log.Triple = .{
        .t1 = stringTerm("Alice"),
        .t2 = .{ .triple = &numeric_triple },
        .t3 = .{ .atom = .{ .boolean = true } },
    };
    const request_id: u64 = 0x0102030405060708;
    const frames = [_]Frame{
        .{ .request_id = request_id, .message = .{ .request = .{
            .space = stringTerm("msa-space"),
            .op = keywordTerm("query"),
            .expected_version = 41,
            .page = .{ .limit = 25, .cursor = .{ .triple = &cursor_triple } },
            .timeout_ms = 1500,
            .payload = .{ .triple = &payload_triple },
        } } },
        .{ .request_id = request_id, .message = .{ .response = .{
            .space = stringTerm("msa-space"),
            .op = keywordTerm("query"),
            .served_version = 42,
            .page = .{ .ordinal = 2, .next = .{ .triple = &cursor_triple }, .done = false },
            .@"error" = .{
                .code = keywordTerm("conflict"),
                .retryable = true,
                .message = stringTerm("version moved"),
                .detail = .{ .atom = .{ .boolean = false } },
            },
            .payload = .{ .triple = &payload_triple },
        } } },
        .{ .request_id = 9, .message = .{ .cancel = {} } },
        .{ .request_id = 10, .message = .{ .event = .{
            .space = stringTerm("msa-space"),
            .op = keywordTerm("changed"),
            .served_version = 43,
            .page = null,
            .@"error" = null,
            .payload = null,
        } } },
    };

    var combined: Writer.Allocating = .init(allocator);
    defer combined.deinit();
    for (frames) |frame| {
        const encoded = try encodeFrame(allocator, frame);
        defer allocator.free(encoded);
        try combined.writer.writeAll(encoded);

        var decoded = try decodeFrame(allocator, encoded);
        defer decoded.deinit();
        try std.testing.expectEqual(frame.request_id, decoded.request_id);
        try std.testing.expectEqual(@as(Kind, frame.message), @as(Kind, decoded.message));
        const reencoded = try encodeFrame(allocator, .{
            .request_id = decoded.request_id,
            .message = decoded.message,
        });
        defer allocator.free(reencoded);
        try std.testing.expectEqualSlices(u8, encoded, reencoded);
    }

    const expected_hex =
        "4652414d5250430001000000010077000000080706050403020101090000006d73612d737061636506050000007175657279012900000000000000011900000001070106000000637572736f720605000000616674657202070000000000000001dc050000070105000000416c6963650702d6ffffffffffffff03000000000000f83f08c059cc690000000015cd5b0705" ++
        "4652414d5250430001000000020096000000080706050403020101090000006d73612d7370616365060500000071756572792a00000000000000010200000001070106000000637572736f720605000000616674657202070000000000000000010608000000636f6e666c69637401010d00000076657273696f6e206d6f766564010401070105000000416c6963650702d6ffffffffffffff03000000000000f83f08c059cc690000000015cd5b0705" ++
        "4652414d52504300010000000300000000000900000000000000" ++
        "4652414d52504300010000000400250000000a0000000000000001090000006d73612d737061636506070000006368616e6765642b00000000000000000000";
    var expected: [expected_hex.len / 2]u8 = undefined;
    _ = try std.fmt.hexToBytes(&expected, expected_hex);
    try std.testing.expectEqualSlices(u8, &expected, combined.written());
}

test "v1 rejects headers, lengths, tags, truncation, trailing bytes, and loose bits" {
    const allocator = std.testing.allocator;
    const cancel = try encodeFrame(allocator, .{
        .request_id = 9,
        .message = .{ .cancel = {} },
    });
    defer allocator.free(cancel);

    try std.testing.expectError(error.TruncatedFrame, decodeFrame(allocator, "FRAM"));
    const bad_magic = try allocator.dupe(u8, cancel);
    defer allocator.free(bad_magic);
    bad_magic[0] = 'X';
    try std.testing.expectError(error.InvalidMagic, decodeFrame(allocator, bad_magic));
    const bad_version = try allocator.dupe(u8, cancel);
    defer allocator.free(bad_version);
    bad_version[format_magic.len] = 2;
    try std.testing.expectError(error.UnsupportedVersion, decodeFrame(allocator, bad_version));
    const bad_kind = try allocator.dupe(u8, cancel);
    defer allocator.free(bad_kind);
    bad_kind[12] = 0;
    try std.testing.expectError(error.InvalidKind, decodeFrame(allocator, bad_kind));
    const bad_flags = try allocator.dupe(u8, cancel);
    defer allocator.free(bad_flags);
    bad_flags[13] = 1;
    try std.testing.expectError(error.InvalidFlags, decodeFrame(allocator, bad_flags));
    const bad_length = try allocator.dupe(u8, cancel);
    defer allocator.free(bad_length);
    std.mem.writeInt(u32, bad_length[14..18], max_body_bytes + 1, .little);
    try std.testing.expectError(error.BodyTooLarge, decodeFrame(allocator, bad_length));

    const extra = try std.mem.concat(allocator, u8, &.{ cancel, &.{0} });
    defer allocator.free(extra);
    try std.testing.expectError(error.TrailingFrameBytes, decodeFrame(allocator, extra));
    std.mem.writeInt(u32, extra[14..18], 1, .little);
    try std.testing.expectError(error.InvalidCancelBody, decodeFrame(allocator, extra));

    const request = try encodeFrame(allocator, .{
        .request_id = 11,
        .message = .{ .request = .{
            .space = stringTerm("s"),
            .op = keywordTerm("op"),
            .expected_version = null,
            .page = null,
            .timeout_ms = null,
            .payload = stringTerm("payload"),
        } },
    });
    defer allocator.free(request);
    try std.testing.expectError(
        error.TruncatedFrame,
        decodeFrame(allocator, request[0 .. request.len - 1]),
    );

    const bad_tag = try allocator.dupe(u8, request);
    defer allocator.free(bad_tag);
    bad_tag[fixed_header_bytes] = 0xff;
    try std.testing.expectError(error.InvalidTermTag, decodeFrame(allocator, bad_tag));

    const truncated_term = try allocator.dupe(u8, request[0 .. request.len - 1]);
    defer allocator.free(truncated_term);
    std.mem.writeInt(u32, truncated_term[14..18], @intCast(request.len - fixed_header_bytes - 1), .little);
    try std.testing.expectError(error.TruncatedTerm, decodeFrame(allocator, truncated_term));

    const trailing_body = try std.mem.concat(allocator, u8, &.{ request, &.{0} });
    defer allocator.free(trailing_body);
    std.mem.writeInt(u32, trailing_body[14..18], @intCast(request.len - fixed_header_bytes + 1), .little);
    try std.testing.expectError(error.TrailingBodyBytes, decodeFrame(allocator, trailing_body));

    const bad_presence = try allocator.dupe(u8, request);
    defer allocator.free(bad_presence);
    const expected_presence = fixed_header_bytes + 1 + 4 + 1 + 1 + 4 + 2;
    bad_presence[expected_presence] = 2;
    try std.testing.expectError(error.InvalidPresence, decodeFrame(allocator, bad_presence));

    const page_response = try encodeFrame(allocator, .{
        .request_id = 12,
        .message = .{ .response = .{
            .space = stringTerm("s"),
            .op = keywordTerm("op"),
            .served_version = 1,
            .page = .{ .ordinal = 0, .next = null, .done = true },
            .@"error" = null,
            .payload = null,
        } },
    });
    defer allocator.free(page_response);
    const bad_boolean = try allocator.dupe(u8, page_response);
    defer allocator.free(bad_boolean);
    const done_offset = fixed_header_bytes + (1 + 4 + 1) + (1 + 4 + 2) + 8 + 1 + 4 + 1;
    bad_boolean[done_offset] = 2;
    try std.testing.expectError(error.InvalidBoolean, decodeFrame(allocator, bad_boolean));
}

test "v1 validates record term types and term bounds before allocation" {
    const allocator = std.testing.allocator;
    try std.testing.expectError(error.InvalidSpaceId, encodeFrame(allocator, .{
        .request_id = 1,
        .message = .{ .request = .{
            .space = keywordTerm("space"),
            .op = keywordTerm("op"),
            .expected_version = null,
            .page = null,
            .timeout_ms = null,
            .payload = stringTerm("payload"),
        } },
    }));
    try std.testing.expectError(error.InvalidOperation, encodeFrame(allocator, .{
        .request_id = 1,
        .message = .{ .request = .{
            .space = stringTerm("space"),
            .op = stringTerm("op"),
            .expected_version = null,
            .page = null,
            .timeout_ms = null,
            .payload = stringTerm("payload"),
        } },
    }));

    var bounded: [fixed_header_bytes + 5]u8 = undefined;
    @memset(&bounded, 0);
    @memcpy(bounded[0..format_magic.len], format_magic);
    std.mem.writeInt(u16, bounded[8..10], format_major, .little);
    std.mem.writeInt(u16, bounded[10..12], format_minor, .little);
    bounded[12] = @intFromEnum(Kind.request);
    bounded[13] = format_flags;
    std.mem.writeInt(u32, bounded[14..18], 5, .little);
    std.mem.writeInt(u64, bounded[18..26], 1, .little);
    const empty_string = try log.TermCodecV1.encode(allocator, stringTerm(""));
    defer allocator.free(empty_string);
    bounded[26] = empty_string[0];
    std.mem.writeInt(u32, bounded[27..31], max_body_bytes + 1, .little);
    try std.testing.expectError(error.TermStringTooLarge, decodeFrame(allocator, &bounded));
}
