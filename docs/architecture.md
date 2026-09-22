# Architecture

UData Harness 是单体 Solon Web 应用，前端不需要 Node 构建链。

```text
Vanilla browser UI
  ├─ REST: sessions / files / integrations / capabilities
  └─ SSE: chat + HITL continuation
            ↓
Controllers → HarnessChatService → HarnessEngine
                  │                    ├─ ReActAgent + Harness tools
                  │                    └─ MCP servers
                  ├─ ActiveRunRegistry
                  └─ FileAgentSession
```

## User and session isolation

Every request carries `X-User-Id`. Valid IDs contain letters, digits, `.`, `_`,
or `-`, up to 64 characters. The server resolves:

```text
<registered local directory>                      active agent working directory
<agent.workspace>/<userId>/                       fallback working directory
<agent.data-dir>/users/<userId>/sessions/...      persistent conversations
<agent.data-dir>/workspaces/<userId>/...           workspace registry
```

The browser never accesses the local filesystem directly. It asks the backend for all available
filesystem roots and lazily lists one directory level at a time. A directory must be registered
before it can become an agent working directory. Sessions persist the corresponding
`workspaceId`, and a user engine is rebuilt after workspace activation so its tools receive the
new root as `cwd`.

Paths are normalized, must stay beneath the user root, and symbolic links are
rejected by the file API. Files larger than 2 MB are not opened or saved.

## Agent runs and HITL

Only one run may be active per session. `ActiveRunRegistry` owns cancellation,
while SSE forwards thinking, text, tool lifecycle, completion, and error events.
When Harness emits a pending HITL task, the stream sends a `hitl` event and
stops. `/api/hitl/decide` submits approve/skip/reject and resumes the same
`FileAgentSession`, preserving ReAct state.

## MCP

MCP integration definitions are loaded into each user `HarnessEngine` at startup
and can be changed at runtime. They are service-level, not per-user. Raw
headers/environment values are persisted under
`<agent.data-dir>/integrations`; list responses redact these values.

## Skill and Subagent

The main agent exposes Harness `skillread`/`skillrefresh` and
`task`/`multitask` tools. Each user has a separate, lazily-created
`HarnessEngine`. The backend Skill library and activated copies are:

```text
<agent.data-dir>/skill-library/<name>/             managed package
<agent.workspace>/<userId>/.soloncode/skills/<name>/  activated copy
```

The package may contain `SKILL.md`, scripts, references, and assets. ZIP import
checks entry count, expanded size, and path containment. Activating a Skill
copies the complete package and refreshes only that user's mount and lazy main
agent. Deactivation removes only the user's copy; deleting a library package
does not silently alter already-activated copies.

Harness built-in agents remain available. Custom Subagent Markdown files live
under `<agent.data-dir>/capabilities/agents` and are currently shared across
user engines.

## Security boundary

Harness sandbox mode and system restrictions are enabled; user-home access is
disabled. `X-User-Id` is an isolation selector, not authentication. A production
gateway must authenticate users, overwrite this header, authorize global
integration changes, add audit logging/rate limits, and protect the data
directory and provider secrets.
