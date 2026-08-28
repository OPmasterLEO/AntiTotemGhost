# AntiTotemGhost

**A lightweight anti-totem-ghost plugin for Crystal PvP.**

> 🤖 **AI Notice:** This project is fully made by AI and maintained by AI. Code, fixes, improvements, and documentation are AI-generated/maintained.

**Author:** OPmasterLEO
**Version:** 1.0.0
**Minecraft:** 1.21.0 – 1.21.6
**Platforms:** Paper / Folia / Spigot
**Java:** 17+

## Features

* ⚡ Fast totem detection
* 🛡️ Prevents totem ghosting
* 🔄 Handles fast item swaps
* 💥 Supports multiple damage events
* 🧵 Folia-safe
* ⚙️ Configurable
* 🐛 Debug mode
* 🧪 Sandbox mode

## Commands

| Command       | Description       |
| ------------- | ----------------- |
| `/mag reload` | Reload config     |
| `/mag debug`  | Toggle debug mode |
| `/mag stats`  | Show statistics   |

Permission: `AntiTotemGhost.admin`

## Configuration

```yaml
reconciliation-ticks: 1
swap-buffer-ticks: 2
enable-fast-path: true
debug-mode: false
sandbox-mode: false
```

## Building

Requires **Java 17+** and **Gradle 8.x**.

```bash
./gradlew build
```

The finished JAR will be in:

```text
build/libs/
```

## License

This project is open source and licensed under the **MIT License**.

You are free to use, modify, and redistribute the project under the terms of the license.

See [`LICENSE`](LICENSE) for the full license text.
