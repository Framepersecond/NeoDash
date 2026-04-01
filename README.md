# NeoDash - Professional Minecraft Infrastructure Control

> The agentless, secure, and automated management layer for modern Minecraft networks.

[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange)](#requirements)
[![Docker Ready](https://img.shields.io/badge/Docker-Ready-2496ED)](#installation--easy-setup)
[![Linux Native](https://img.shields.io/badge/Linux-Native-success)](#native-power-jmx--agentless-metrics)
[![License](https://img.shields.io/badge/License-BSD--3--Clause-blue)](#license)
[![Updates](https://img.shields.io/badge/Updates-GitHub%20Auto--Updater-brightgreen)](#automation-suite)

NeoDash is a professional control plane for Minecraft server operations. It combines secure RBAC administration, unified SSO bridge flows, native JVM metrics, file/terminal recovery tooling, and automated provisioning into one lightweight panel.

## Key Features

| Feature | Technical Description | Status |
|---|---|---|
| RBAC Permission System | Granular, role-based access control for multiple sub-users and scoped server capabilities. | ✅ Active |
| Unified SSO Bridge | HMAC-signed SSO flow between panel and Dash plugin to reduce auth friction. | ✅ Active |
| Live Audit Logging | `WebActionLogger` records security-relevant actions with metadata (user, action, source IP). | ✅ Active |
| Auto-Server Installer | Automated provisioning for Vanilla, Fabric, Quilt, Paper, Purpur, Spigot, Bukkit. | ✅ Active |
| GitHub Auto-Updater | `GithubUpdater` handles version discovery, update download, and restart-ready workflows. | ✅ Active |
| Native JMX Metrics | Agentless CPU/RAM/TPS extraction via JVM Attach API + JMX for non-plugin stacks. | ✅ Active |
| Universal Terminal | Native `screen` command injection + log tailing fallback with bridge-compatible paths. | ✅ Active |
| Advanced File Manager | Safe path validation + raw binary uploads for files/folder structures at scale. | ✅ Active |

## Security & Compliance

NeoDash is designed for real team operations and accountability.

- **Audit trail:** `WebActionLogger` captures high-value admin events for incident review and compliance workflows.
- **Permission enforcement:** route-level capability checks for start/control, console, files, and settings.
- **Path hardening:** canonical path resolution prevents directory traversal during file operations.
- **Session controls:** authenticated cookie sessions with explicit logout and access checks.

> ⚠️ Security tip: run NeoDash behind TLS/reverse proxy in production and restrict panel exposure to trusted networks.

## Multi-User Management

NeoDash supports multi-admin environments with practical separation of duties:

- create sub-users with role presets or custom permission sets
- grant server-scoped rights (restart only, console only, file-only, full admin)
- maintain central role definitions and assignment flows

This allows safe delegation without sharing root-level credentials.

## Automation Suite

### Auto-Installer

NeoDash provisions server runtimes end-to-end with modern source integrations:

- Official APIs and distribution flows for major server types
- Modpack install via Modrinth slug/direct URL
- Startup script generation + environment-aware Java handling

### GitHub Auto-Updater

`GithubUpdater` provides operational update workflows:

- release/version checks
- controlled update download state
- restart-aware apply path from the panel

## The SSO Bridge

NeoDash supports signed SSO bridge redirects to the Dash plugin:

- signed URL parameters (user/timestamp/signature)
- shared secret validation on target side
- reduced double-login friction for trusted admin flows

## Native Power: JMX + Agentless Metrics

For Vanilla/Fabric/Quilt environments without Bukkit plugin metrics, NeoDash uses a native pipeline:

- JVM process tagging (`-Dneodash.server.dir=...`) for deterministic PID discovery
- Java Attach API for local JMX connector access
- real-time JMX data: CPU load, heap memory usage, TPS/MSPT
- TCP-based online fallback for resilient status checks

> ℹ️ Requires host-level permissions that allow local JVM attach operations.

## Installation & Easy Setup

### Install

```bash
git clone [https://github.com/Framepersecond/NeoDash.git](https://github.com/Framepersecond/NeoDash.git)
cd NeoDash
chmod +x install.sh
./install.sh
```

NeoDash is designed for zero-config bootstrap: detect environment, prepare runtime paths, and become panel-ready in seconds.

### Manual Installation (Docker Compose)

```yaml
version: "3.9"
services:
  neodash:
    image: ghcr.io/[USER]/neodash:latest
    container_name: neodash
    restart: unless-stopped
    ports:
      - "8080:8080"
    volumes:
      - ./data:/app/data
      - ./servers:/app/servers
```

```bash
docker compose up -d
```

## Requirements

- Java 21+
- Linux host strongly recommended for native attach/screen workflows
- Docker (optional)

## Release Notes

### NeoDash v2.1 - Complete Control & Audit 🛡️

- Integrated Full Audit Logging (`WebActionLogger`).
- Released Granular User Permissions (RBAC).
- Enabled Auto-Provisioning for all major server types.
- Finalized the JMX-Native Metrics pipeline for Vanilla.
- Optimized Docker Binary Streaming for huge file uploads.

## License


---

<div align="center">

## 🤝 Partner

<a href="https://emeraldhost.de/frxme">
  <img src="https://cdn.emeraldhost.de/branding/icon/icon.png" width="80" alt="Emerald Host Logo">
</a>

### Powered by EmeraldHost

*DDoS-Protection, NVMe Performance und 99.9% Uptime.* *Der Host meines Vertrauens für alle Development-Server.*

<a href="https://emeraldhost.de/frxme">
  <img src="https://img.shields.io/badge/Code-Frxme10-10b981?style=for-the-badge&logo=gift&logoColor=white&labelColor=0f172a" alt="Use Code Frxme10 for 10% off">
</a>

</div>

---

BSD 3-Clause (see `LICENSE` if included in your distribution).
