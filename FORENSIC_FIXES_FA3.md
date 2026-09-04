# OpenMinis — Forensic Fix Rounds F-A1/F-A2/F-A3

Branch: `fix/forensic-round-fa1-fa2-fa3` (base: `6ef483f` — the audited ZIP snapshot)
61 files changed, +2809 / −598

## What's in this branch

### F-A1 — Floating assistant (دستیار شناور)
- **Real agent loop**: `FloatingAssistantViewModel` now streams with the full
  `AgentTools` schema (23 tools), executes tool calls, feeds results back as
  `ToolResult` parts, persists full history (`floating_assistant_history.json`).
- **FA-BUG-01**: ViewModel created once via `ViewModelProvider` + Factory
  (service is the ViewModelStoreOwner) — recomposition can no longer wipe state.
- **Android 14+ crash fix**: `foregroundServiceType="specialUse"` +
  `FOREGROUND_SERVICE_SPECIAL_USE` permission.
- **Boot resume**: `AlarmReceiver` (BOOT_COMPLETED) restarts the service when
  the toggle is enabled — the switch no longer lies after reboot.
- **Execution mode UI**: AUTO / PLANNING / ACCEPT selector in
  `AssistantSettingsScreen`; ACCEPT hard-gates every tool call with an
  approve/deny dialog in the floating panel.

### F-A2 — Security & functional fixes (17 findings)
| Finding | Fix |
|---|---|
| ZIP-Slip (P2-34) | `ZipSafety.safeChild` canonical containment + bomb budget in CloudSyncStore, ArchiveTool, SkillRepository |
| Session path traversal (P1-02) | canonical containment in `resolveSessionHostPath` + `resolveHostPath` |
| Browser SSRF (P1-03) | NetworkPolicy on navigate/fetch/executeJS + HumanFetch |
| WebExtract redirect (P1-04) | manual redirects, policy re-check every hop |
| Fail-open unknown tools (P2-28) | fail-closed + `INTERNALLY_TRUSTED_TOOLS` allowlist |
| Ungated ModelUse/BrowserUse (P2-48) | OffloadGate after help parsing + registry rows |
| Single ASK_ONCE slot (P2-27) | FIFO `PendingAsk` queue |
| FileOps/FileSearch broken on `/var/minis` | session-aware guest-path resolution |
| minis://workspace always 404 (P2-56) | session-aware interception via `sessionIdProvider` |
| MCP OAuth end-to-end broken (P1/P2-21) | bridge writer → `minis-global/mcp-servers/oauth/<server>.json` |
| Legacy alarms die on reboot (SCHED-LEGACY-BOOT-01) | `rescheduleAllOnBoot()` |
| SOUL double-escape (P2-13) | symmetric `unescape()` |
| `du -sh` injection (P2-07) | charset-sanitized dir token |
| Non-atomic DB ops (P1/P2-71/72) | `appendMessageAtomic` / `deleteSessionAtomic` |
| Plaintext WebDAV + SSH secrets (P2-79, SSH-SECURITY-01) | EncryptedPrefs + migration |
| Unbounded NativeOffload (P2-41..45) | bounded pool(8), 60s handler timeout, 8MB reply cap, stop() interrupts |
| Share staging unbounded (P2-61) | 500MB streaming cap, partial deleted |
| Memory write unbounded (P2-15) | 32KB tool-boundary cap |
| Memory RMW race (P2-14) | `memoryWriteLock` |
| Alarm broadcast spoof | `hasAlarm` validation |
| Backup leaks secrets | `backup_rules.xml` + `data_extraction_rules.xml` |
| CI misses broken native deps | `verify_apk_native.sh` DT_NEEDED gate wired into android.yml |
| `build_proot.sh` sed byte bug | literal 0x01 → `\1` |
| in-app FloatingAssistant dead code | removed + orphan prefs removed |

### F-A3 — Architecture (the deferred items)
- **ExecutionLedger** (`agent/ExecutionLedger.kt`): durable per-tool-call
  execution records; startup reconciliation marks RUNNING → INTERRUPTED once
  per process; in-flight duplicate guard; chat_send / calendar_create
  idempotency windows (10 min); resume surfaces ambiguous executions instead
  of blind replay.
- **Rootfs generation token**: NativeOffloadServer bumps generation on
  start/stop; stale queued requests dropped (exit 125); replies discarded when
  the generation changes mid-handler.
- **SSH TOFU**: `StrictHostKeyChecking=accept-new` + per-host
  `UserKnownHostsFile=/var/minis/shared/.ssh_<host>/known_hosts` — a changed
  host key now fails closed (MITM refused). `checking=no` is gone everywhere.
- **Task Engine** (`tasks/TaskEngine.kt` + `tasks/TaskTools.kt`): durable
  tasks with ordered steps, per-step logs and lifecycle
  (PENDING/RUNNING/COMPLETED/FAILED/CANCELLED), exposed to the agent as
  `task_create` / `task_update` / `task_list`, wired into both chat dispatchers
  and the system prompt. Survives process death.

## Not included (product-scale, documented as open)
Workflow Graph/orchestrator, App Factory/Spec Compiler, BuildEngine+queue,
GitHub Autopilot — these are new products, not fixes; the Task Engine is the
durable core they would build on.

## Build
CI (`.github/workflows/android.yml`) builds debug APK, verifies signature, and
now **fails the build** if the native DT_NEEDED closure is broken
(`verify_apk_native.sh`) — the exact regression that caused "proot exit=1".
