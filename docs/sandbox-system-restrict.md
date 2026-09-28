# `sandboxSystemRestrict` 系统级沙箱说明

## 1. 作用

`sandboxSystemRestrict` 控制是否为终端命令启用操作系统级沙箱。它需要与
`sandboxEnabled` 配合使用：

| `sandboxEnabled` | `sandboxSystemRestrict` | 实际效果 |
| --- | --- | --- |
| `false` | 任意值 | 不启用沙箱 |
| `true` | `false` | 仅使用 Java 层路径限制和危险命令保护 |
| `true` | `true` | Java 层限制与 OS 级进程隔离同时启用 |

当前项目的默认配置为：

```java
.sandboxEnabled(true)
.sandboxAllowUserHome(false)
.sandboxSystemRestrict(true)
```

其中，聊天界面的“沙箱”开关控制 `sandboxEnabled`。用户关闭沙箱后，
`sandboxSystemRestrict` 即使保持为 `true` 也不会生效。

## 2. Java 层限制

启用 `sandboxEnabled` 后，Solon AI 会在 Java 层检查：

- `read`、`write`、`edit`、`ls`、`grep`、`glob` 的目标路径；
- `bash_start` 的工作目录；
- `~` 用户主目录访问；
- 逻辑挂载目录的读写权限；
- 杀死宿主 JVM、删除系统根目录等高危命令。

这层保护依赖各个 Tool 在执行前主动调用安全检查代码。

## 3. OS 级限制

启用 `sandboxSystemRestrict` 后，终端命令执行前会尝试使用所在操作系统的
沙箱工具进行包装：

| 操作系统 | 底层实现 |
| --- | --- |
| Linux | Bubblewrap（`bwrap`） |
| macOS | Seatbelt / `sandbox-exec` |
| Windows | `srt-win` |

整体调用链如下：

```text
模型生成命令
    ↓
Java 层安全检查
    ↓
操作系统沙箱包装
    ↓
受限子进程执行
```

Linux 上的行为大致类似：

```bash
bwrap \
  --ro-bind / / \
  --bind /workspace /workspace \
  --chdir /workspace \
  --unshare-pid \
  --new-session \
  /bin/sh -lc "模型生成的命令"
```

通常情况下，系统根目录以只读方式挂载，当前工作区重新绑定为可写目录，
命令进程运行在独立的 PID Namespace 中。

## 4. 主要保护范围

系统级沙箱主要用于阻止终端命令：

- 修改当前工作区之外的系统文件；
- 修改未授权的挂载目录；
- 操作宿主机进程；
- 写入用户主目录；
- 对系统目录产生不可恢复的副作用。

例如模型尝试执行：

```bash
echo hacked > /etc/config
```

Java 层规则可能会直接拒绝该命令；即使命令没有被字符串规则识别，
Bubblewrap 环境中的 `/etc` 通常也是只读的。

## 5. 它不等于“只能看到工作区”

当前 Linux 策略通常会把 `/` 只读挂载，然后把当前工作区重新挂载为可写。
因此它更接近：

```text
工作区外只读，工作区内可写
```

而不是：

```text
除了工作区以外，任何文件都不可见
```

这意味着：

- 工作区外的内容通常不能修改；
- 子进程仍可能读取已经挂载且具有读取权限的文件；
- 网络访问默认不会被禁止。

如果系统要求不同用户之间的目录完全不可见，Kubernetes 层应只向 Pod
挂载当前任务所需的 NFS `subPath`，不要将包含所有用户工作区的 NFS 根目录
挂载到同一个 Pod。

## 6. 关闭 `sandboxSystemRestrict` 的影响

配置：

```java
.sandboxEnabled(true)
.sandboxSystemRestrict(false)
```

会产生以下结果：

- 不再调用 `bwrap`、Seatbelt 或 `srt-win`；
- 文件类 Tool 仍受 Java 路径规则限制；
- `bash` 子进程失去 OS 级目录写保护；
- 只剩危险命令检查和 HITL 人工审批作为保护。

## 7. Fail-open 风险

Solon AI 4.1.0 的系统级沙箱采用 fail-open 行为。如果出现以下情况：

- 找不到 `bwrap`；
- 沙箱依赖检查失败；
- Linux 内核不允许 User Namespace；
- Kubernetes Seccomp 阻止所需系统调用；
- 沙箱命令包装失败；

框架会记录日志，然后继续执行未经过 OS 沙箱包装的命令。典型日志为：

```text
Auto sandbox init failed, running without OS sandbox
Sandbox wrap failed, running without OS sandbox
```

因此，配置：

```java
.sandboxSystemRestrict(true)
```

只表示“尝试启用系统级沙箱”，不能证明沙箱已经成功生效。

## 8. 生产环境建议

生产部署建议同时采用以下措施：

1. 启动时检查 `bwrap`、`rg`、`socat` 等运行依赖；
2. 执行一个最小 Bubblewrap 探测命令，确认内核和 Seccomp 允许相关调用；
3. 当配置要求系统级沙箱但探测失败时阻止服务启动，避免静默降级；
4. 将沙箱运行文件挂载为只读，避免智能体自行替换；
5. 每个 Pod 只挂载它实际需要的工作目录；
6. 不将系统级沙箱作为 Kubernetes 文件挂载隔离的替代方案；
7. 结合 HITL 审批、容器非 root 用户和最小权限 SecurityContext 使用。

Linux Pod 内可使用以下命令进行基础探测：

```bash
/opt/agent-runtime/bwrap --version

/opt/agent-runtime/bwrap \
  --unshare-user \
  --uid 0 \
  --gid 0 \
  --ro-bind / / \
  --proc /proc \
  --dev /dev \
  --unshare-pid \
  --new-session \
  /bin/true
```

如果返回 `Operation not permitted`，通常是内核、User Namespace、容器运行时
或 Seccomp 策略阻止了 Bubblewrap，仅上传可执行文件无法解决该问题。
