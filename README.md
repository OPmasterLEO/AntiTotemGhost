# MasterAntiGhost

**Extreme anti-totem-ghost plugin for high-intensity crystal PvP.**

Eliminates near-instant totem ghosting by intercepting lethal damage at the NMS level, performing fast-path totem checks, and reconciling uncertain states within a configurable tick window. Fully Folia-safe with region-thread-aware scheduling.

**Author:** OPmasterLEO  
**Version:** 1.0.0  
**Target:** Minecraft 1.21.0 - 1.21.6 (Paper/Folia/Spigot and compatible forks)  
**Java:** 17+

---

## High-Level Design

```
┌──────────────────────────────────────────────────────────────────────┐
│                        DAMAGE EVENT ARRIVES                          │
│                    (EntityDamageEvent, HIGHEST)                       │
└──────────────┬───────────────────────────────────────────────────────┘
               │
               ▼
        ┌──────────────┐     YES     ┌─────────────────────┐
        │ Is Lethal?   │────────────►│  A: FAST PATH       │
        └──────┬───────┘             │  Read NMS offhand   │
               │ NO                  │  Totem found?       │
               ▼                     └────┬──────────┬─────┘
          (pass through)              YES │          │ NO
                                         ▼          ▼
                                  ┌──────────┐  ┌───────────────────┐
                                  │ RESURRECT│  │ B: INTERCEPT GATE │
                                  │ (instant)│  │ Cancel event      │
                                  └──────────┘  │ Store context     │
                                                │ PENDING_LETHAL    │
                                                └────────┬──────────┘
                                                         │
                                                         ▼
                                              ┌──────────────────────┐
                                              │ C: RECONCILIATION    │
                                              │ (next tick, Entity   │
                                              │  Scheduler)          │
                                              │                      │
                                              │ 1. Re-read NMS       │
                                              │ 2. Check swap buffer │
                                              │ 3. Pop or die        │
                                              └──────────────────────┘
                                                         │
                                              ┌──────────┴──────────┐
                                              ▼                     ▼
                                        ┌──────────┐        ┌──────────┐
                                        │ RESURRECT│        │  DEATH   │
                                        │ (delayed)│        │ Re-apply │
                                        └──────────┘        │ damage   │
                                                             └──────────┘

During PENDING_LETHAL:
┌──────────────────────────────────────────────────────────────────────┐
│ D: DAMAGE COALESCING                                                 │
│ Additional damage events → merged into DamageCoalescer (CAS loop)    │
│ No duplicate reconciliation cycles                                   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Package Architecture

| Package | Purpose |
|---------|---------|
| `net.opmasterleo.masterantighost` | Main plugin class, lifecycle management |
| `net.opmasterleo.masterantighost.config` | Typed configuration loader with defaults |
| `net.opmasterleo.masterantighost.combat` | Core combat system: state machine, coalescing, resurrection |
| `net.opmasterleo.masterantighost.nms` | NMS abstraction layer for version-independent server internals |
| `net.opmasterleo.masterantighost.buffer` | Rolling tick-indexed swap buffer |
| `net.opmasterleo.masterantighost.listener` | Bukkit event listeners and command handler |
| `net.opmasterleo.masterantighost.scheduler` | Folia-safe scheduler abstraction |
| `net.opmasterleo.masterantighost.debug` | Debug logging utility with runtime toggle |

---

## Folia Multithreaded Architecture

```
╔══════════════════════════════════════════════════════════════════╗
║  REGION THREAD (per-region, owns player entity)                  ║
║  ├─ Bukkit event handlers (DamageListener, SwapListener)         ║
║  ├─ NMS access (read/write inventory, health, effects)           ║
║  ├─ Reconciliation logic (Entity Scheduler)                      ║
║  └─ Manual resurrection (NMS totem pop)                          ║
║                                                                  ║
║  GLOBAL THREAD                                                   ║
║  ├─ SwapBuffer cleanup (periodic)                                ║
║  ├─ Statistics aggregation                                       ║
║  └─ Config reload                                                ║
║                                                                  ║
║  CONCURRENT DATA STRUCTURES                                      ║
║  ├─ ConcurrentHashMap  — player states, damage contexts          ║
║  ├─ ConcurrentLinkedDeque — swap buffer entries                  ║
║  ├─ AtomicReference    — damage coalescing (CAS)                 ║
║  ├─ LongAdder          — high-throughput statistics              ║
║  └─ volatile           — config flags, debug toggle              ║
║                                                                  ║
║  SAFETY INVARIANTS                                               ║
║  1. NMS operations ONLY on entity's region thread                ║
║  2. No synchronized blocks (deadlock risk on Folia)              ║
║  3. Entity Scheduler for player-scoped delayed tasks             ║
║  4. Global Scheduler for non-entity periodic tasks               ║
╚══════════════════════════════════════════════════════════════════╝
```

---

## NMS Usage

This plugin uses **paperweight-userdev** for direct NMS access with Mojang mappings at compile time. The build system reobfuscates to Spigot mappings for the production JAR.

### Why NMS over Bukkit?

- **Offhand state:** `Player.getInventory().getItemInOffHand()` may return stale data due to Bukkit caching. Direct NMS read via `ServerPlayer.getItemBySlot(EquipmentSlot.OFFHAND)` is authoritative.
- **Totem animation:** Entity event byte 35 must be sent via NMS `broadcastEntityEvent()`. No Bukkit equivalent.
- **Damage source preservation:** NMS `DamageSource` contains cause, attacker, and message parameters needed for accurate death messages.

### Version Adaptability

The `NmsAccessor` interface abstracts all NMS operations. Adding support for a new Minecraft version requires:
1. Create a new implementation class (e.g., `NmsAccessorImpl_v1_21_R1`)
2. Add a version check in `MasterAntiGhost.createNmsAccessor()`

---

## Configuration

```yaml
reconciliation-ticks: 1    # Ticks before reconciliation decision (1-10)
swap-buffer-ticks: 2       # Swap buffer lookback window (1-20)
enable-fast-path: true     # Enable same-tick NMS totem check
debug-mode: false          # Verbose debug logging
sandbox-mode: false        # Stress testing mode
```

---

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/mag reload` | `masterantighost.admin` | Reload configuration |
| `/mag debug` | `masterantighost.admin` | Toggle debug logging |
| `/mag stats` | `masterantighost.admin` | Display statistics |

---

## Building

### Prerequisites
- Java 17+ JDK
- Gradle 8.x (or use the wrapper)

### Setup Gradle Wrapper
```bash
gradle wrapper --gradle-version 8.5
```

### Build Production JAR
```bash
./gradlew build
```
Output: `build/libs/MasterAntiGhost-1.0.0.jar` (reobfuscated for production)

### Build Dev JAR
```bash
./gradlew devJar
```
Output: `build/libs/MasterAntiGhost-1.0.0-dev.jar` (Mojang-mapped for dev servers)

### Run Tests
```bash
./gradlew test
```

---

## Testing & Debugging

### Debug Mode
Enable via config (`debug-mode: true`) or command (`/mag debug`). Logs include:
- Every damage intercept with amounts and causes
- Swap buffer state on each query
- Reconciliation decisions (pop vs. death) with timing
- NMS read/write operations

### Crystal PvP Test Scenarios

1. **Fast pop:** Place crystal, detonate. Verify instant pop with totem in offhand.
2. **Late swap:** Hold totem in mainhand, swap (F) simultaneously with crystal. Verify swap buffer catches it.
3. **No totem:** No totem in inventory. Verify clean death with correct message.
4. **Multi-crystal:** Detonate 2+ crystals in same tick. Verify single pop, damage coalesced.
5. **Rapid swap spam:** Rapid F-key presses. Verify buffer records all, no double-pops.

### Sandbox Mode
Enable `sandbox-mode: true` for stress testing:
- Players respawn instantly at death location
- Receive fresh totems on respawn
- All combat events logged for analysis

---

## Thread Safety Summary

| Component | Data Structure | Justification |
|-----------|---------------|---------------|
| Player states | `ConcurrentHashMap<UUID, AtomicReference<CombatState>>` | CAS transitions prevent double-intercept |
| Damage coalescing | `AtomicReference<DamageContext>` + CAS loop | Lock-free damage merging, no lost updates |
| Swap buffer | `ConcurrentHashMap<UUID, ConcurrentLinkedDeque<SwapEntry>>` | Lock-free append + iteration |
| Statistics | `LongAdder` | Striped counters, near-zero contention |
| Bypass set | `ConcurrentHashMap.newKeySet()` | Lock-free add/check/remove |
| Debug flag | `volatile boolean` | Single-writer, multi-reader visibility |
| Config | Immutable object + reference swap | No synchronization needed |

---

## License

Closed source. All rights reserved. Commercial distribution ready.
