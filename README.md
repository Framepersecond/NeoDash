# NeoDash - Minecraft Network Ops Hub

> The native control layer for Minecraft server fleets: provisioning, lifecycle control, live metrics, offline recovery, permissions, audit, and secure bridge handoff to Dash, FabricDash, and ForgeDash.

[![Version](https://img.shields.io/badge/Version-1.5.1-blue)](#release-status)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange)](#requirements)
[![Linux Native](https://img.shields.io/badge/Linux-Native-success)](#installation)
[![systemd](https://img.shields.io/badge/systemd-Native-success)](#installation)
[![screen](https://img.shields.io/badge/screen-Server%20Control-22c55e)](#requirements)
[![Docker Optional](https://img.shields.io/badge/Docker-Optional-2496ED)](#docker-optional)
[![License](https://img.shields.io/badge/License-BSD--3--Clause-blue)](#license)

NeoDash is a standalone web panel for running and supervising Minecraft infrastructure from one place. It manages local server instances, starts and restarts them through the configured host runner, watches live health data, keeps recovery tools available while servers are offline, and routes trusted admins into the right loader-specific Dash interface when deeper plugin or mod work is needed.

NeoDash is intentionally split from the in-server dashboards:

- **NeoDash** owns fleet-level operations: servers, users, roles, groups, backups, audit, alerts, native metrics, offline files, and update visibility.
- **Dash, FabricDash, and ForgeDash** own loader-local tools: plugin/mod browser, Crash Doctor, detailed player intelligence, profiler views, and plugin/mod maintenance.

That split keeps NeoDash lightweight while still giving operators one secure entry point for the whole network.

## Key Features

| Feature | Current Capability |
|---|---|
| Multi-server dashboard | Unified server cards with online state, TPS, RAM, path, runner type, bridge package version, update badges, and group overview. |
| Server lifecycle control | Start, stop, and restart servers through the configured runner, with startup-log routing and automatic safety backups before restarts. |
| Native runners | First-class Linux `screen` support, configured start commands, process detection, log tailing, and optional Docker-aware paths. |
| Server installer | Provisions Paper, Purpur, Spigot, Bukkit, Fabric, Quilt, NeoForge, and Vanilla servers. |
| Bridge auto-install | Can inject Dash for Bukkit-family servers, FabricDash for Fabric/Quilt, and ForgeDash for NeoForge during installation. |
| Modpack bootstrap | Supports Modrinth slug/direct URL workflows for Fabric/Quilt installs, including `.mrpack` processing and server-side mod filtering. |
| Server discovery | Scans configured roots, home/server paths, and common Linux locations for existing Minecraft servers and bridge configs. |
| Bridge dashboard handoff | Opens the local Dash/FabricDash/ForgeDash panel with signed SSO and a NeoDash restart callback URL. |
| Offline file rescue | Browser-based file rescue for offline/unmanaged servers: upload, folder upload, edit, save, download, rename, and guarded delete. |
| Console access | Uses bridge console endpoints when available and native screen/log fallbacks when the server is managed directly by NeoDash. |
| Live monitoring | Reads bridge health/stats and native JVM data, including TPS, MSPT, CPU, RAM, uptime, process state, and TCP reachability. |
| Native JMX metrics | Uses the Java Attach API and local JMX to collect JVM metrics without requiring a plugin on Vanilla-like stacks. |
| Ops Hub | Global maintenance page with smart alerts, server risk, disk/log/backup/crash checks, audit shortcuts, recovery tools, and plugin dashboard links. |
| Smart alerts | Detects missing server roots, TPS drops, memory pressure, disk pressure, fresh crash reports, warning/error log patterns, player spikes, and missing backups. |
| Backup and recovery | Creates verified local zip backups, supports full/config/world scopes, blocks rollback while running, and creates safety backups before restore. |
| Google Drive backup mirror | Optional Google Drive OAuth connection for uploading verified maintenance backups. |
| Audit and compliance | Persistent audit database, recent/searchable timeline, IP/user/action metadata, and CSV/JSON exports. |
| RBAC and server permissions | Global roles, custom role permissions, main-admin ownership transfer, server assignments, and server-scoped rights for start, console, files, properties, and settings. |
| Bridge user approval | SSO-created bridge users wait for admin approval before they can enter NeoDash. |
| Server groups | Group servers for clearer operations and dashboard summaries. |
| Graph snapshots | Homey-style comparison graphs saved as NeoDash JSON snapshots. |
| Notifications | Persistent web notification center plus Discord webhook dispatch for audit and smart-alert events. |
| Updates | GitHub-based update checks/downloads for NeoDash and version visibility for Dash, FabricDash, and ForgeDash. |
| Responsive interface | Polished dark UI with smoother navigation, dashboard motion, custom selects, and repaired mobile sidebar behavior. |

## Security Model

NeoDash is built around explicit access control and operational accountability.

- **Authenticated sessions:** cookie-based login flow with first-run Main-Admin setup.
- **Role hierarchy:** built-in ADMIN, MODERATOR, and USER roles plus custom ranks and permission sets.
- **Server-scoped rights:** grant only the capabilities a user needs for a specific server.
- **Bridge SSO safety:** HMAC-SHA256 signatures, timestamp checks, replay protection, per-server bridge secrets, and optional global SSO secret.
- **Approval gate:** bridge-created users are not trusted automatically; a Main-Admin must approve them.
- **Path hardening:** file operations resolve canonical paths and block protected lock/pid files.
- **Audit trail:** high-value actions are written to the audit DB and can be reviewed or exported.

Production recommendation: run NeoDash behind TLS or a trusted reverse proxy, expose it only to trusted networks, and keep bridge secrets private.

## Operations Hub

The Maintenance page is the global NeoDash Ops Hub. It is designed for network-level decisions rather than loader-specific plugin work.

It includes:

- persistent smart alerts with unread/read/dismiss flows
- verified backup creation and rollback
- disk, memory, TPS, MSPT, crash-report, and log-pattern signals
- audit and compliance shortcuts
- staff notes, queue items, tasks, and server-scoped staff chat
- links into the matching Dash/FabricDash/ForgeDash dashboard for plugin/mod work

Plugin/mod installation, Crash Doctor, detailed profiler views, and player intelligence now live inside the local plugin dashboard where the server context is most accurate.

## Provisioning

NeoDash can create a fully runnable server from the web UI.

Supported server types:

- Paper
- Purpur
- Spigot
- Bukkit
- Fabric
- Quilt
- NeoForge
- Vanilla

During install NeoDash can:

- download official server files or run required installers/build tools
- generate startup scripts and memory settings
- install Dash, FabricDash, or ForgeDash when bridge mode is enabled
- write matching bridge configuration with port and shared secret
- process compatible Modrinth modpacks for Fabric/Quilt
- register the finished server in the NeoDash database

Existing servers can also be added manually or discovered through the scan workflow.

## Bridge Integration

NeoDash talks to Dash, FabricDash, and ForgeDash through a shared-secret bridge.

The bridge is used for:

- health and stats snapshots
- console logs and command dispatch
- opening the local plugin dashboard
- SSO into the plugin dashboard
- restart requests routed back through NeoDash's configured start command

If no bridge is available, NeoDash still keeps native controls and offline recovery available for host-managed servers.

## Installation

### One-line native install

```bash
curl -sSL https://raw.githubusercontent.com/Framepersecond/NeoDash/main/install.sh | bash
```

The installer will:

1. Detect the Linux package manager.
2. Install Java 21 and `screen` when missing.
3. Download the latest NeoDash release JAR.
4. Ask for panel port, server root, and data directory.
5. Register NeoDash as a `systemd` service when available.
6. Fall back to a persistent `screen` session when `systemd` is unavailable.

### Service management

```bash
sudo systemctl status neodash
sudo systemctl restart neodash
journalctl -u neodash -f
```

### Manual run

```bash
java \
  -Dneodash.port=8080 \
  -Dneodash.serverDir=/home/user/servers \
  -Dneodash.dataDir=/home/user/NeoDash/data \
  -jar NeoDash-1.5.1-shaded.jar
```

### Docker optional

Docker artifacts are still included for containerized setups, but the recommended path is native Linux. Native mode lets NeoDash access host paths, `screen`, JMX attach, startup scripts, and log files without volume-mapping surprises.

```bash
docker compose up -d
```

## Requirements

- Java 21 or newer
- Linux host recommended
- `screen` for native server lifecycle control
- Host permissions that allow Java Attach/JMX when using native JVM metrics
- Docker only when intentionally deploying NeoDash in a container

## Release Status

Current project version: **1.5.1**

Highlights in the current generation:

- improved startup and restart flow with startup-log visibility
- offline file rescue with folder upload, download, rename, edit, save, and guarded delete
- mobile navigation fixes and smoother dashboard motion
- new global Ops Hub with smart alerts, backups, notifications, audit exports, groups, and graphs
- staff workflow tools for notes, tickets, tasks, and staff chat
- clearer split between NeoDash global operations and loader-local Dash/FabricDash/ForgeDash maintenance

See `RELEASE_NOTES.md` for the full changelog.

## License

BSD 3-Clause. See `LICENSE` if included in your distribution.

---

<div align="center">

## Partner

<a href="https://emeraldhost.de/frxme">
  <img src="https://cdn.emeraldhost.de/branding/icon/icon.png" width="80" alt="Emerald Host Logo">
</a>

### Powered by EmeraldHost

DDoS protection, NVMe performance, and 99.9% uptime. The host I trust for development servers.

<a href="https://emeraldhost.de/frxme">
  <img src="https://img.shields.io/badge/Code-Frxme10-10b981?style=for-the-badge&logo=gift&logoColor=white&labelColor=0f172a" alt="Use Code Frxme10 for 10% off">
</a>

</div>
