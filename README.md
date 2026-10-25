# NeoDash

**Version 3.0 - Material 3 Expressive interface and full language support**

NeoDash manages multiple Minecraft servers from one self-hosted interface. It combines lifecycle control, provisioning, users and roles, backups, offline recovery, Guardian, plugin/mod handoff and a fleet-aware Intelligence Center.

[![Version](https://img.shields.io/badge/version-3.0.0-22d3ee)](#release-status)
[![Java](https://img.shields.io/badge/Java-21%2B-f97316)](#requirements)
[![Linux](https://img.shields.io/badge/Linux-native-22c55e)](#installation)
[![systemd](https://img.shields.io/badge/systemd-supported-22c55e)](#installation)
[![Docker](https://img.shields.io/badge/Docker-optional-2496ED)](#docker)

## Languages

The whole interface is available in **English, German (Deutsch), Spanish (Español), French (Français), Italian, Portuguese, Dutch, Polish, Turkish, Russian, Japanese, Korean, Simplified Chinese (简体中文) and Traditional Chinese (繁體中文)**.

- Pick a language from the translate button in the top bar (or the drawer on phones), in *Settings → Language*, or choose **Automatic** to follow the browser. The sign-in page follows the browser or the last choice too.
- Every user keeps their own choice; it is saved with the account and a `dash_lang` cookie.
- Translations ship as one dictionary per language (`web/i18n/<code>.json`), served as an immutable, content-hashed asset, so a page only loads the language it needs.
- German, Spanish, French and both Chinese variants are translated by hand end to end; the other languages are hand-translated for all labels and buttons, with long explanatory sentences machine-translated with MADLAD-400 (Apache-2.0) and checked for placeholders. Anything still untranslated falls back to English.

## Fleet Intelligence

Each managed server owns an isolated Intelligence state. Operators can switch server context without leaking incidents, support cases, grants, snapshots or status data between instances.

| Workspace | Fleet capability |
|---|---|
| Investigate | Shadow Boot Lab, Root Cause Explorer, Performance Regression Radar, Resource Attribution, Guided Safe Mode and Dependency and Impact Map. |
| Change | State Time Machine, Backup Content Explorer, Adaptive Maintenance Window and Action Guardrails. |
| Players | Player Journey Replay, Support and Appeals Inbox, Cross-Server Player Identity and Player Experience Score. |
| Policy | Just-in-Time Staff Access, Dual-Control Actions and Retention Center. |
| Supply | Supply Chain Center, Config Schema Assistant and Fleet Universal Search. |
| Reliability | World Health Center, Storage Intelligence and Service Level Dashboard. |
| Response | Operator War Room and per-service Public Status Page. |

### What fleet context adds

- Universal Search finds servers, players, support cases, incidents and operational records from one query.
- Cross-Server Player Identity correlates literal player identifiers without SQL wildcard ambiguity.
- Just-in-Time access can grant a narrow right for a bounded period on the selected server.
- Server selectors keep the seven workspaces compact while preserving exact ownership boundaries.
- Public status and war rooms share incident truth without exposing the authenticated admin interface.

## Complete Control Plane

| Area | Capability |
|---|---|
| Fleet dashboard | Server status, TPS, RAM, runner, bridge version, groups and update visibility. |
| Lifecycle | Start, stop and restart through configured runners with startup logs and safety backups. |
| Provisioning | Paper, Purpur, Spigot, Bukkit, Fabric, Quilt, NeoForge and Vanilla server creation. |
| Bridge install | Installs the matching Dash variant and writes coordinated bridge settings. |
| Signed handoff | HMAC-signed SSO, replay protection, approval-aware users and restart callbacks. |
| Offline recovery | Whole-row files, upload/folder upload, edit, rename, download and guarded delete for stopped servers. |
| Plugins and mods | Native server-scoped artifact and data-folder management plus loader-dashboard handoff. |
| Guardian | Server selection, cases, rollback, incidents, scores and compact paged activity. |
| Backups | Verified local archives, scoped restore, safety backup and optional Google Drive mirror. |
| Operations | Maintenance plans, incident mode, handover, restore drills, drift, alerts and automation. |
| Security | RBAC, custom roles, server scopes, audit trail, bridge secrets and action guardrails. |
| Updates | GitHub-backed release state for NeoDash, Dash, FabricDash and ForgeDash. |

## Evolution Since 1.5.1

- **2.0:** unified the Dash-family motion system, compact Guardian, in-place offline uploads, native plugin/mod pages, GitHub visibility and stronger bridge lifecycle flows.
- **2.1:** completed notifications, backup destinations and beta gating; protected OAuth credentials; verified installers and made every return path honor the configured HTTPS panel address.
- **2.2:** added persistent Fleet Operations for maintenance, incidents, handover, restore drills, compatibility, capacity, permission simulation and executable recipes.
- **2.3:** adds 25 fleet-aware Intelligence features, universal search, identity correlation, JIT rights, dual control, transactional recovery, public status and deeper action validation.
- **2.4:** adds Minecraft 26.2 and complete 1.21.x provisioning, Paper Fill v3 downloads, Java 25 runtime selection, modern NeoForge resolution and a focused Intelligence-only advanced workspace.

## Installation

### Native Linux

```bash
curl -sSL https://raw.githubusercontent.com/Framepersecond/NeoDash/main/install.sh | bash
```

The installer uses a short-lived one-time setup flow, writes sensitive files with restrictive permissions and can create a hardened systemd service without asking for a root password in the web interface.

```bash
sudo systemctl status neodash
sudo systemctl restart neodash
journalctl -u neodash -f
```

### Manual

```bash
java \
  -Dneodash.port=8080 \
  -Dneodash.serverDir=/home/user/servers \
  -Dneodash.dataDir=/home/user/NeoDash/data \
  -jar NeoDash-3.0.0.jar
```

### Docker

```bash
mvn clean package
docker compose up -d
```

The container runs as UID/GID `10001:10001` with a read-only root filesystem and dropped Linux capabilities. Host-mounted data and server directories must already be writable by that identity. Native Linux remains the preferred deployment when direct `screen`, JMX, startup script and host path access is required.


## Hosting behind a reverse proxy

Set Settings -> General -> Base Path when the panel is served below a sub-path, for example `/admin`
for `https://example.org/admin/`. Every link, form target and asset URL is then
emitted with that prefix. Configure the proxy to strip the prefix before
forwarding — `proxy_pass http://127.0.0.1:8080/` with the trailing slash in
nginx, or Traefik's `StripPrefix` middleware. When it is left blank, an
`X-Forwarded-Prefix` header sent by the proxy is honoured instead. Forward
`Host` (or `X-Forwarded-Host`) and `X-Forwarded-Proto` so the same-origin checks
see the browser-facing address.

## Security Model

- First-run Main-Admin setup uses short-lived, one-time credentials and authenticated cookies.
- Built-in and custom roles are enforced server-side, including server-scoped lifecycle, console, file and settings rights.
- CSRF checks, bounded request bodies, rate limits and audit records protect mutations.
- Bridge SSO uses HMAC-SHA256, timestamp windows, nonce replay protection and approval before bridge users become trusted.
- File and archive operations enforce canonical roots, symlink boundaries, protected lock/pid files and extraction limits.
- Update and installer downloads validate hosts, redirects, size and release digests when available.
- Secrets are fingerprinted in UI, excluded from logs and retained when credential edit fields are left blank.
- Guardrails can require a reason, quiet hours, verified backups, JIT rights or dual approval.

Use NeoDash behind TLS or a trusted reverse proxy and expose it only to staff networks.

## Requirements

- Java 25 or newer for Minecraft 26.2 management
- Linux recommended
- `screen` for native lifecycle control
- Java Attach/JMX host permission for native JVM metrics
- Docker only for intentionally containerized deployments

## Release Status

Current release: **NeoDash 3.0.0**

See [RELEASE_NOTES.md](./RELEASE_NOTES.md), [GITHUB_RELEASE_2.4.md](./GITHUB_RELEASE_2.4.md) and [MODRINTH_README.md](./MODRINTH_README.md).

## License

BSD 3-Clause. See `LICENSE` for details.

## Partner

Dash and NeoDash are partnered with EmeraldHost. Use the code Frxme10 for 10% off your server.
