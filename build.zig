const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const standalone = b.addWriteFiles();
    const server = standalone.addCopyFile(
        b.path("src/zig/server.zig"),
        "server.zig",
    );
    _ = standalone.addCopyFile(b.path("src/zig/log.zig"), "log.zig");
    _ = standalone.addCopyFile(b.path("src/zig/rpc.zig"), "rpc.zig");
    _ = standalone.add("fram_kernel_classify.zig",
        \\pub fn stripAt(s: []const u8) []const u8 {
        \\    return if (s.len != 0 and s[0] == '@') s[1..] else s;
        \\}
        \\
    );

    const executable = b.addExecutable(.{
        .name = "fram-server-zig",
        .root_module = b.createModule(.{
            .root_source_file = server,
            .target = target,
            .optimize = optimize,
        }),
    });
    b.installArtifact(executable);

    const client = b.addExecutable(.{
        .name = "fram-rpc-client",
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/zig/client.zig"),
            .target = target,
            .optimize = optimize,
        }),
    });
    b.installArtifact(client);

    const unit_tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = server,
            .target = target,
            .optimize = optimize,
        }),
    });
    const run_unit_tests = b.addRunArtifact(unit_tests);
    const test_step = b.step("test", "Run Zig unit tests");
    test_step.dependOn(&run_unit_tests.step);

    const rpc_tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/zig/rpc.zig"),
            .target = target,
            .optimize = optimize,
        }),
    });
    const run_rpc_tests = b.addRunArtifact(rpc_tests);
    test_step.dependOn(&run_rpc_tests.step);
}
