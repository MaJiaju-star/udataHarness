package org.noear.solon.ai.talents.cli;

import com.udata.harness.common.context.ContextArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.talents.mount.MountManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class StreamingProcessExecutorTest {
    @TempDir Path workspace;

    @Test
    void largeOutputCompletesAndEntireMiddleIsRecoverableOnlyInOwningSession() throws Exception {
        Fixture fixture = fixture(8L * 1024 * 1024);
        ToolResult result = fixture.tool.bashResult("large", 15000, 1200, null, "session-a");
        assertTrue(result.getContent().length() < 2000);
        assertTrue(result.getContent().contains("FINAL-中文-😀"));
        assertFalse(result.getContent().contains("MIDDLE-MARKER"));
        Map<?, ?> details = (Map<?, ?>) result.metas().get("bashOutput");
        assertEquals(Boolean.TRUE, details.get("captureComplete"));
        assertEquals(0, details.get("exitCode"));
        String path = (String) details.get("path");
        String id = path.substring("@tool-results/".length(), path.length() - 4);
        String full = new String(Files.readAllBytes(fixture.store.bodyPath("session-a", id)), StandardCharsets.UTF_8);
        assertTrue(full.length() > 3 * 1024 * 1024);
        assertTrue(full.contains("MIDDLE-MARKER"));
        assertTrue(full.endsWith("FINAL-中文-😀\n"));
        assertThrows(java.io.IOException.class, () -> fixture.store.bodyPath("session-b", id));
        assertEquals(full.length(), fixture.store.load("session-a", id).chars);
    }

    @Test
    void failureShowsMiddleDiagnosticAndSuccessfulBuildShowsCompactLog() {
        Fixture fixture = fixture(8L * 1024 * 1024);
        ToolResult failed = fixture.tool.bashResult("fail-middle", 15000, 2000, null, "failure");
        assertTrue(failed.getContent().contains("[exit_code=1]"));
        assertTrue(failed.getContent().contains("[ERROR] src/Main.java:42: missing symbol"));
        assertTrue(failed.getContent().contains("完整日志"));

        ToolResult succeeded = fixture.tool.bashResult("mvn test", 15000, 2000, null, "build");
        assertTrue(succeeded.getContent().contains("构建/测试成功；常规日志已省略"));
        assertTrue(succeeded.getContent().contains("[exit_code=0]"));
    }

    @Test
    void shortOutputOmitsArchiveEnvelopeAndStripsAnsiOnlyFromPreview() throws Exception {
        Fixture fixture = fixture(1024 * 1024);
        ToolResult result = fixture.tool.bashResult("short-ansi", 15000, 2000, null, "short");
        assertTrue(result.getContent().startsWith("[exit_code=0]\n"));
        assertTrue(result.getContent().endsWith("Name: pwsh\nVersion: 7.6.7\n"));
        assertFalse(result.getContent().contains("\u001b["));
        assertFalse(result.getContent().contains("@tool-results/"));
        Map<?, ?> details = (Map<?, ?>) result.metas().get("bashOutput");
        assertEquals(Boolean.TRUE, details.get("captureComplete"));
        String path = (String) details.get("path");
        String id = path.substring("@tool-results/".length(), path.length() - 4);
        String archived = new String(Files.readAllBytes(fixture.store.bodyPath("short", id)), StandardCharsets.UTF_8);
        assertTrue(archived.contains("\u001b[32;1m"));
    }

    @Test
    void verifyDetectsProjectAndReturnsCompactStatusWithRecoverableLog() throws Exception {
        Files.write(workspace.resolve("pom.xml"), "<project/>".getBytes(StandardCharsets.UTF_8));
        Fixture succeeded = fixture(8L * 1024 * 1024);
        ToolResult passing = succeeded.tool.verify("test", "auto", 15000, null, "verify-ok");
        assertTrue(passing.getContent().contains("[验证通过] maven test"));
        assertEquals(EnvironmentResolver.isWindows() ? "mvn.cmd test" : "mvn test", succeeded.command.get());
        assertTrue(passing.getContent().contains("日志：@tool-results/"));
        assertFalse(passing.getContent().contains("MIDDLE-MARKER"));

        Fixture failed = fixture(new ContextArtifactStore(workspace.resolve("failed-artifacts")),
                8L * 1024 * 1024, true);
        ToolResult failing = failed.tool.verify("test", "auto", 15000, null, "verify-fail");
        assertTrue(failing.getContent().contains("[验证失败] maven test"));
        assertTrue(failing.getContent().contains("src/Main.java:42: missing symbol"));
        assertTrue(failing.getContent().contains("日志：@tool-results/"));
    }

    @Test
    void independentStorageQuotaStopsCaptureAndLabelsRecoverablePrefix() throws Exception {
        Fixture fixture = fixture(128 * 1024);
        ToolResult result = fixture.tool.bashResult("large", 15000, 1000, null, "quota");
        assertTrue(result.getContent().contains("独立日志存储上限"));
        Map<?, ?> details = (Map<?, ?>) result.metas().get("bashOutput");
        assertEquals(Boolean.FALSE, details.get("captureComplete"));
        String path = (String) details.get("path");
        String id = path.substring("@tool-results/".length(), path.length() - 4);
        assertFalse(fixture.store.load("quota", id).captureComplete);
        assertTrue(fixture.store.restore("quota", id, 0, 1000).contains("capture_complete=false"));
        assertTrue(((Number) details.get("bytes")).longValue() <= 128 * 1024);
        assertFalse(result.getContent().contains("FINAL-中文"));
    }

    @Test
    void timeoutAndCancellationKeepPartialOutputWithoutLeakingProcesses() throws Exception {
        Fixture fixture = fixture(1024 * 1024);
        ToolResult timeout = fixture.tool.bashResult("sleep", 1200, 1000, null, "timeout");
        assertTrue(timeout.getContent().contains("执行超时"));
        assertTrue(timeout.getContent().contains("BEFORE-SLEEP"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) timeout.metas().get("bashOutput")).get("captureComplete"));

        AtomicReference<ToolResult> result = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            result.set(fixture.tool.bashResult("sleep", 30000, 1000, null, "cancel"));
            interruptPreserved.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        Thread.sleep(1200);
        worker.interrupt();
        worker.join(8000);
        assertFalse(worker.isAlive());
        assertTrue(interruptPreserved.get());
        assertTrue(result.get().getContent().contains("执行已取消"));
    }

    @Test
    void closedStdoutDoesNotMarkTimedOutCommandAsComplete() {
        Fixture fixture = fixture(1024 * 1024);
        ToolResult result = fixture.tool.bashResult("closed-stdout", 1200, 1000, null, "closed");
        assertTrue(result.getContent().contains("执行超时"));
        Map<?, ?> details = (Map<?, ?>) result.metas().get("bashOutput");
        String path = (String) details.get("path");
        String id = path.substring("@tool-results/".length(), path.length() - 4);
        assertDoesNotThrow(() -> assertFalse(fixture.store.load("closed", id).captureComplete));
    }

    @Test
    void deniedCommandsStillUseOriginalTerminalValidation() {
        TerminalTalent terminal = new TerminalTalent(new MountManager(workspace.toString()));
        BashCaptureTalent tool = new BashCaptureTalent(terminal, new ContextArtifactStore(workspace.resolve("logs")), 2000, 1024 * 1024);
        ToolResult result = tool.bashResult("", 1000, 1000, null, "guard");
        assertTrue(result.getContent().contains("不能为空"));
        assertFalse(result.getContent().contains("<bash-output"));
        assertFalse(result.metas().containsKey("bashOutput"));
    }

    @Test
    void missingSessionCannotStartAnUnarchivedCommand() {
        Fixture fixture = fixture(1024 * 1024);
        ToolResult result = fixture.tool.bashResult("large", 15000, 1000, null, null);
        assertTrue(result.getContent().contains("Missing context session"));
        assertFalse(result.getContent().contains("FINAL-中文"));
        assertFalse(Files.exists(workspace.resolve("artifacts")));
    }

    @Test
    void archiveCreationFailureDoesNotRunCommandWithoutLogs() throws Exception {
        Path blocked = workspace.resolve("not-a-directory");
        Files.write(blocked, new byte[]{1});
        Fixture fixture = fixture(new ContextArtifactStore(blocked), 1024 * 1024);
        ToolResult result = fixture.tool.bashResult("large", 15000, 1000, null, "s");
        assertTrue(result.getContent().contains("无法可靠保存"));
        assertFalse(result.getContent().contains("FINAL-中文"));
        assertFalse(result.metas().containsKey("bashOutput"));
    }

    /** 创建走原 bash 入口、但用确定性 Java 子进程产生输出的测试宿主。 */
    private Fixture fixture(long quota) {
        return fixture(new ContextArtifactStore(workspace.resolve("artifacts")), quota);
    }

    /** 创建指定归档后端的测试宿主，便于覆盖持久化失败。 */
    private Fixture fixture(ContextArtifactStore store, long quota) {
        return fixture(store, quota, false);
    }

    private Fixture fixture(ContextArtifactStore store, long quota, boolean fail) {
        AtomicReference<String> commandSeen = new AtomicReference<>();
        TerminalTalent terminal = new TerminalTalent(new MountManager(workspace.toString())) {
            @Override
            public String bash(String command, Integer timeout, Integer max, String cwd) {
                commandSeen.set(command);
                String java = Paths.get(System.getProperty("java.home"), "bin", EnvironmentResolver.isWindows() ? "java.exe" : "java").toString();
                return getExecutor().executeCmd(workspace, Arrays.asList(java, "-cp", System.getProperty("java.class.path"),
                        Output.class.getName(), fail ? "fail-middle" : command), Collections.emptyMap(), timeout, max, null);
            }
        };
        return new Fixture(store, new BashCaptureTalent(terminal, store, 2000, quota, workspace.toString()), commandSeen);
    }

    private static final class Fixture {
        final ContextArtifactStore store;
        final BashCaptureTalent tool;
        final AtomicReference<String> command;
        Fixture(ContextArtifactStore store, BashCaptureTalent tool, AtomicReference<String> command) {
            this.store = store;
            this.tool = tool;
            this.command = command;
        }
    }

    /** 独立 JVM 输出源，避免依赖部署机是否安装 Node/Python 或特定 shell。 */
    public static final class Output {
        public static void main(String[] args) throws Exception {
            if ("short-ansi".equals(args[0])) {
                System.out.write("\u001b[32;1mName\u001b[0m: pwsh\nVersion: 7.6.7\n"
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("closed-stdout".equals(args[0])) {
                System.out.close();
                System.err.close();
                Thread.sleep(30000);
                return;
            }
            if ("sleep".equals(args[0])) {
                System.out.println("BEFORE-SLEEP");
                System.out.flush();
                Thread.sleep(30000);
                return;
            }
            if ("fail-middle".equals(args[0])) {
                byte[] noise = new byte[8192];
                Arrays.fill(noise, (byte) 'x');
                noise[noise.length - 1] = '\n';
                System.out.write(noise);
                System.out.write("[ERROR] src/Main.java:42: missing symbol\n".getBytes(StandardCharsets.UTF_8));
                System.out.write(noise);
                System.exit(1);
            }
            byte[] chunk = new byte[8192];
            Arrays.fill(chunk, (byte) 'x');
            chunk[chunk.length - 1] = '\n';
            for (int i = 0; i < 400; i++) {
                System.out.write(chunk);
                if (i == 200) System.out.write("MIDDLE-MARKER\n".getBytes(StandardCharsets.UTF_8));
            }
            System.out.write("FINAL-中文-😀\n".getBytes(StandardCharsets.UTF_8));
        }
    }
}
