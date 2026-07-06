# NeoDash

<div align="center">

**Minecraft fleet operations hub**

The native control layer for Minecraft server fleets: provisioning, lifecycle control, users, roles, groups, backups, audit, alerts, offline recovery, updates, and secure bridge handoff into Dash, FabricDash, and ForgeDash.

[![Version](https://img.shields.io/badge/Version-2.0-blue?style=for-the-badge)](#release-status)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?style=for-the-badge)](#requirements)
[![Linux](https://img.shields.io/badge/Linux-recommended-success?style=for-the-badge)](#requirements)
[![systemd](https://img.shields.io/badge/systemd-supported-success?style=for-the-badge)](#installation)
[![Docker](https://img.shields.io/badge/Docker-optional-2496ED?style=for-the-badge)](#docker-optional)
[![License](https://img.shields.io/badge/License-BSD%203--Clause-blue?style=for-the-badge)](#license)

</div>

---

## Overview

NeoDash is the native control layer for Minecraft server fleets. It manages server instances, users, roles, groups, backups, audit, alerts, offline recovery, updates, and secure bridge handoff into Dash, FabricDash, and ForgeDash.

NeoDash is built for operators who manage more than one server or need a central place for lifecycle control, provisioning, recovery, permissions, monitoring, update visibility, and bridge-based access into loader-local dashboards.

## Current Release

- **Latest version:** `2.0`
- **Release:** Version 2.0 - Minecraft fleet operations hub
- **Release date:** `2026-07-06`
- **Release:** available from the [GitHub releases page](../../releases)
- **License:** BSD 3-Clause

## What NeoDash Does

| Area | Capability |
| --- | --- |
| Fleet dashboard | Multi-server cards with status, TPS, RAM, runner type, bridge version, groups, and update visibility. |
| Lifecycle | Start, stop, and restart servers through configured runners, startup logs, and safety backups. |
| Provisioning | Create Paper, Purpur, Spigot, Bukkit, Fabric, Quilt, NeoForge, and Vanilla servers. |
| Bridge install | Auto-install Dash, FabricDash, or ForgeDash during setup and write matching bridge settings. |
| Bridge SSO | Signed handoff into loader dashboards with approval-aware bridge users and restart callbacks. |
| Offline recovery | File rescue for offline or unmanaged servers with upload, folder upload, edit, rename, download, and guarded delete. |
| Plugins and mods | Native server-scoped Plugins/Mods page for uploaded JARs, data folders, and bridge-local file work. |
| Guardian | Cross-server Guardian entry point with server selection, bridge health, cases, rollback, incidents, and paged activity. |
| Ops Hub | Smart alerts, crash/log signals, disk and backup risk, staff tools, audit shortcuts, and recovery actions. |
| Backups | Verified local zip backups, restore scopes, safety backups before restore, and optional Google Drive mirror. |
| Security | RBAC, custom roles, server-scoped rights, audit trail, bridge secrets, and path hardening. |
| Updates | GitHub-backed update visibility for NeoDash, Dash, FabricDash, and ForgeDash. |

## New in NeoDash 2.0

NeoDash 2.0 is the major release that ties the Dash family together.

- Shared smooth animation layer across NeoDash, Dash, FabricDash, and ForgeDash.
- Less bumpy navigation: page changes respond directly and content swaps feel fluid.
- Guardian is shorter and clearer with sticky activity headers, paging, and full-width investigation tools.
- Offline file rescue uploads refresh in place and preserve scroll position instead of jumping to the top.
- File rows open from their whole bubble, not only the filename.
- Native Plugins/Mods page for server-scoped JAR upload, browsing, editing, downloading, and deleting.
- GitHub update center and version visibility for NeoDash and every Dash plugin variant.
- Stronger bridge story: signed SSO, approval queue, restart callbacks, and startup-log routing.

## Features

### Fleet Dashboard

- Unified multi-server cards with online state, TPS, RAM, runner type, bridge version, groups, and update visibility.
- Server grouping for clearer fleet organization.
- Bridge health and update visibility across Dash, FabricDash, and ForgeDash servers.
- Responsive dashboard layout for desktop, tablet, and mobile operators.

### Lifecycle Control

- Start, stop, and restart servers through configured runners.
- Startup-log routing so operators can watch a server come back online.
- Safety backups before high-impact lifecycle and restore operations.
- Native `screen` support for Linux server control.
- Process detection, log tailing, TCP reachability checks, and host-aware runtime state.

### Provisioning

- Create Paper, Purpur, Spigot, Bukkit, Fabric, Quilt, NeoForge, and Vanilla servers.
- Generate startup scripts and memory settings.
- Install Dash, FabricDash, or ForgeDash during setup when bridge mode is enabled.
- Write matching bridge configuration with port and shared secret.
- Register completed servers in the NeoDash database.
- Discover and add existing servers through scan workflows.

### Bridge Integration

- Signed SSO handoff into Dash, FabricDash, and ForgeDash.
- Approval-aware bridge users before access is granted.
- Restart callbacks from loader dashboards back into NeoDash.
- Startup-log routing after restart actions.
- Bridge sessions keep the master dashboard URL and restart callback for future lifecycle actions.

### Guardian

- Cross-server Guardian entry point with server selection and bridge health context.
- Cases, rollback, incidents, and paged activity views.
- Sticky activity headers for easier scanning.
- Full-width investigation tools for a cleaner workflow.
- Shorter Guardian surfaces that avoid extremely long pages.

### Offline Recovery

- File rescue for offline or unmanaged servers.
- Upload and folder upload support.
- Edit, rename, download, and guarded delete flows.
- Upload refreshes preserve scroll position and stay in context.
- File rows open from the whole row bubble for faster navigation.

### Plugins and Mods

- Native server-scoped Plugins/Mods page.
- Upload JAR files.
- Browse, edit, download, and delete server-scoped plugin or mod files.
- Keep loader-local work inside Dash, FabricDash, or ForgeDash when deeper plugin/mod diagnostics are needed.

### Ops Hub

- Smart alerts for fleet-level risk and maintenance signals.
- Crash and log signals.
- Disk and backup risk visibility.
- Staff tools and audit shortcuts.
- Recovery actions from one central operations surface.

### Backups

- Verified local zip backups.
- Restore scopes for targeted recovery.
- Safety backups before restore operations.
- Optional Google Drive mirror for verified maintenance backups.

### Security and Access Control

- Cookie-based authenticated sessions with first-run Main-Admin setup.
- Built-in roles plus custom ranks and permission sets.
- Server-scoped rights for start, console, files, properties, and settings.
- Bridge SSO with HMAC-SHA256 signatures, timestamp checks, and replay protection.
- Bridge-created users require approval before access.
- File operations resolve canonical paths and block protected runtime files.
- High-value actions are written to the audit database.

### Updates

- GitHub-backed update visibility for NeoDash, Dash, FabricDash, and ForgeDash.
- Central update center for the full Dash family.
- Version visibility across loader-specific dashboards.
- Clear release visibility for operators managing mixed server fleets.

## Installation

### One-line native install

```bash
curl -sSL https://raw.githubusercontent.com/Framepersecond/NeoDash/main/install.sh | bash
```

The installer can install Java 21 and `screen`, download NeoDash, configure the port and server root, and register a system service when available.

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
  -jar NeoDash-2.0-shaded.jar
```

### Docker Optional

Docker artifacts are available for containerized setups, but native Linux is recommended when NeoDash needs host paths, `screen`, JMX attach, startup scripts, and direct log access.

```bash
docker compose up -d
```

## Requirements

- Java 21 or newer
- Linux host recommended
- `screen` for native server lifecycle control
- Host permissions for Java Attach/JMX metrics when using native JVM metrics
- Docker only for intentional container deployments

## Security Model

NeoDash is designed for explicit operator access.

- Cookie-based authenticated sessions with first-run Main-Admin setup.
- Built-in roles plus custom ranks and permission sets.
- Server-scoped rights for start, console, files, properties, and settings.
- Bridge SSO with HMAC-SHA256 signatures, timestamp checks, and replay protection.
- Bridge-created users require approval before access.
- File operations resolve canonical paths and block protected runtime files.
- High-value actions are written to the audit database.

For production, run NeoDash behind TLS or a trusted reverse proxy and expose it only to staff networks.

## Bridge Split

NeoDash owns fleet-level operations.

Dash, FabricDash, and ForgeDash own loader-local work such as plugin/mod maintenance, detailed diagnostics, player intelligence, and profiler views.

That split keeps NeoDash clean while still giving operators one entry point for the full network.

## Release Status

Current release: **NeoDash 2.0**

See `RELEASE_NOTES.md` for the 2.0 changelog and `MODRINTH_README.md` for publication-ready copy.

## License

BSD 3-Clause. See `LICENSE` for details.

---

<div align="center">

## Partner

<a href="https://emeraldhost.de/frxme">
  <img src="https://cdn.emeraldhost.de/branding/icon/icon.png" width="80" alt="Emerald Host Logo">
</a>

### Powered by EmeraldHost

DDoS protection, NVMe performance, and 99.9% uptime. The host I trust for development servers.

<a href="https://emeraldhost.de/frxme">
  <img src="https://img.shields.io/badge/Code-Frxme10-10b981?style=for-the-badge&logo=gift&logoColor=white&labelColor=0f172a" alt="Use code Frxme10 for 10% off">
</a>

</div>

---

*For issues, feature requests, or contributions, please visit the [GitHub repository](../../issues).*