const std = @import("std");
const rt = @import("beagle_rt.zig");
const k = @import("fram_kernel_classify.zig");

const corpus = @embedFile("corpus.tsv");
const hex_digits = "0123456789abcdef";

fn emitHex(value: []const u8) void {
    for (value) |byte| {
        std.debug.print("{c}{c}", .{
            hex_digits[byte >> 4],
            hex_digits[byte & 0x0f],
        });
    }
}

fn emitString(op: []const u8, index: usize, value: []const u8) void {
    std.debug.print("{s}\t{d}\t", .{ op, index });
    emitHex(value);
    std.debug.print("\n", .{});
}

fn emitBool(op: []const u8, index: usize, value: bool) void {
    std.debug.print("{s}\t{d}\t{d}\n", .{
        op,
        index,
        @intFromBool(value),
    });
}

fn emitLease(op: []const u8, index: usize, lease: k.LeaseParts) void {
    std.debug.print("{s}\t{d}\t", .{ op, index });
    emitHex(lease.holder);
    std.debug.print("\t{d}\t{d}\t{d}\n", .{
        lease.exp,
        lease.epoch,
        @intFromBool(lease.valid),
    });
}

fn nibble(byte: u8) u8 {
    return switch (byte) {
        '0'...'9' => byte - '0',
        'a'...'f' => byte - 'a' + 10,
        else => @panic("corpus contains non-lowercase-hex byte"),
    };
}

fn decodeHex(allocator: std.mem.Allocator, encoded: []const u8) []const u8 {
    if (encoded.len % 2 != 0) @panic("corpus contains odd-length hex");
    if (encoded.len == 0) return "";
    const decoded = allocator.alloc(u8, encoded.len / 2) catch @panic("oom");
    for (decoded, 0..) |*byte, index| {
        byte.* = (nibble(encoded[index * 2]) << 4) |
            nibble(encoded[index * 2 + 1]);
    }
    return decoded;
}

fn nextField(fields: *std.mem.SplitIterator(u8, .scalar)) []const u8 {
    return fields.next() orelse @panic("corpus row has too few fields");
}

fn requireEnd(fields: *std.mem.SplitIterator(u8, .scalar)) void {
    if (fields.next() != null) @panic("corpus row has too many fields");
}

fn parseBool(value: []const u8) bool {
    if (std.mem.eql(u8, value, "0")) return false;
    if (std.mem.eql(u8, value, "1")) return true;
    @panic("corpus contains invalid boolean");
}

fn parseInt(value: []const u8) i64 {
    return std.fmt.parseInt(i64, value, 10) catch
        @panic("corpus contains invalid integer");
}

pub fn main() void {
    var storage: [131072]u8 = undefined;
    var arena = std.heap.FixedBufferAllocator.init(&storage);
    var rng = rt.Splitmix64.init(1);
    var ctx = rt.Ctx{ .tick = arena.allocator(), .rng = &rng };
    var lines = std.mem.splitScalar(u8, corpus, '\n');
    var index: usize = 0;

    while (lines.next()) |line| {
        if (line.len == 0 or line[0] == '#') continue;
        var fields = std.mem.splitScalar(u8, line, '|');
        const op = nextField(&fields);

        if (std.mem.eql(u8, op, "string")) {
            const value = decodeHex(ctx.tick, nextField(&fields));
            requireEnd(&fields);
            emitString("stripAt", index, k.stripAt(value));
            emitBool("hasWhitespace", index, k.hasWhitespace(value));
            emitBool("refShape", index, k.refShape(value));
        } else if (std.mem.eql(u8, op, "predicate")) {
            const predicate = decodeHex(ctx.tick, nextField(&fields));
            var configured = [_][]const u8{
                decodeHex(ctx.tick, nextField(&fields)),
                decodeHex(ctx.tick, nextField(&fields)),
                decodeHex(ctx.tick, nextField(&fields)),
            };
            requireEnd(&fields);
            emitBool("vecMember", index, k.vecMember(&configured, predicate));
            emitBool(
                "configuredSingle",
                index,
                k.configuredSingle(&configured, predicate),
            );
            emitBool("emojiSingle", index, k.emojiSingle(predicate));
        } else if (std.mem.eql(u8, op, "meta")) {
            const predicate = decodeHex(ctx.tick, nextField(&fields));
            requireEnd(&fields);
            emitBool("metaSingleSeed", index, k.metaSingleSeed(predicate));
        } else if (std.mem.eql(u8, op, "single")) {
            const declared_present = parseBool(nextField(&fields));
            const declared_single = parseBool(nextField(&fields));
            const configured = parseBool(nextField(&fields));
            const predicate = decodeHex(ctx.tick, nextField(&fields));
            requireEnd(&fields);
            emitBool("singleEff", index, k.singleEff(
                declared_present,
                declared_single,
                configured,
                predicate,
            ));
        } else if (std.mem.eql(u8, op, "group")) {
            const left = decodeHex(ctx.tick, nextField(&fields));
            const predicate = decodeHex(ctx.tick, nextField(&fields));
            requireEnd(&fields);
            emitString("keyOfGroup", index, k.keyOfGroup(
                &ctx,
                left,
                predicate,
            ));
        } else if (std.mem.eql(u8, op, "triple")) {
            const left = decodeHex(ctx.tick, nextField(&fields));
            const predicate = decodeHex(ctx.tick, nextField(&fields));
            const right = decodeHex(ctx.tick, nextField(&fields));
            requireEnd(&fields);
            emitString("keyOfTriple", index, k.keyOfTriple(
                &ctx,
                left,
                predicate,
                right,
            ));
        } else if (std.mem.eql(u8, op, "normalize")) {
            const value_kind = decodeHex(ctx.tick, nextField(&fields));
            const value = decodeHex(ctx.tick, nextField(&fields));
            requireEnd(&fields);
            emitString("normalizeRefValue", index, k.normalizeRefValue(
                &ctx,
                value_kind,
                value,
            ));
        } else if (std.mem.eql(u8, op, "lease-subject")) {
            const resource = decodeHex(ctx.tick, nextField(&fields));
            requireEnd(&fields);
            emitString("leaseSubject", index, k.leaseSubject(&ctx, resource));
        } else if (std.mem.eql(u8, op, "lease-encode")) {
            const holder = decodeHex(ctx.tick, nextField(&fields));
            const exp = parseInt(nextField(&fields));
            const epoch = parseInt(nextField(&fields));
            requireEnd(&fields);
            emitString("leaseEncode", index, k.leaseEncode(
                &ctx,
                holder,
                exp,
                epoch,
            ));
        } else if (std.mem.eql(u8, op, "lease-decode")) {
            const value = decodeHex(ctx.tick, nextField(&fields));
            requireEnd(&fields);
            emitLease("leaseDecode", index, k.leaseDecode(value));
        } else if (std.mem.eql(u8, op, "lease-invalid")) {
            requireEnd(&fields);
            emitLease("leaseInvalid", index, k.leaseInvalid());
        } else if (std.mem.eql(u8, op, "delivery")) {
            const predicate = decodeHex(ctx.tick, nextField(&fields));
            requireEnd(&fields);
            emitBool("deliveryTrigger", index, k.deliveryTrigger(predicate));
        } else {
            @panic("corpus contains unknown operation");
        }
        index += 1;
    }

    for (k.fallback_single, 0..) |value, constant_index| {
        emitString("fallbackSingle", constant_index, value);
    }
    emitString("keySep", 0, k.key_sep);
    for (k.lease_schema_lines, 0..) |value, constant_index| {
        emitString("leaseSchemaLine", constant_index, value);
    }
}
