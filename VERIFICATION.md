# MasterAntiGhost V8 Verification Strategy

## Automated unit tests

Run:
`./gradlew test`

What to expect:
- `SwapBufferTest` verifies the rolling swap/totem evidence window and cleanup logic.
- `DamageCoalescerTest` verifies thread-safe coalescing under concurrent updates.
- `SwapEvidenceLedgerTest` verifies the evidence->decision gate used by the engine pop attempt.

Coverage gap (by design):
- SchedulerHub/Bukkit/Paper region-thread behavior is not unit-tested in this repo because it requires a live server runtime.
- Netty packet injection behavior is not unit-tested; it must be validated via live-server scenarios below.

## Live server validation (required)

Preparation:
1. Start a Paper server (Folia optional).
2. Install the built plugin jar.
3. Confirm plugin is healthy: `/masterantighost compat`
4. Ensure debug logging is off unless you’re diagnosing: `/masterantighost debug`

Core anti-ghost scenarios:

1. Fast-path pop (baseline correctness)
   - Give victim a Totem of Undying in either hand before lethal crystal explosion.
   - Expected: vanilla totem pop occurs; no reconciliation should be needed.

2. No-totem lethal (true death)
   - Give victim no totems in inventory/hands.
   - Expected: plugin cancels lethal and reconciles to a true death after reconciliation delay.

3. Late swap pop (the anti-ghost scenario)
   - Victim has no totem at the moment the crystal would deliver lethal damage.
   - Within the swap evidence window, swap a totem into either hand.
   - Expected: plugin cancels lethal, detects swap evidence, and performs a manual resurrection during reconciliation.

4. Swap window boundary
   - Repeat scenario (3) but swap at the exact edge and one tick past the edge.
   - Expected: swap inside the window reconciles as a pop; swap outside reconciles as a death.

5. Multi-crystal burst coalescing
   - Trigger multiple lethal crystal hits in the same tick or back-to-back ticks.
   - Expected: a single reconciliation decision; coalesced damage leads to one final outcome.

6. Disconnect/quit during pending lethal
   - Start scenario (3) and disconnect victim during the pending window.
   - Expected: engine cleanup runs; no exceptions; no memory/state leak.

Packet evidence validation:
1. Verify the packet ingress path is working
   - Swap totems rapidly using offhand/offhand-swap actions and number-key swaps.
   - Expected: reconciliation outcomes change accordingly (pop for timely swaps, death for untimely swaps).

2. Degraded fallback path
   - If packet injection fails (e.g., due to server internals changing), the plugin must still track swap evidence via Bukkit listeners.
   - Expected: late swap still works, but may have lower accuracy.

Performance validation (scheduler reliability):
1. Stress: continuous swaps + crystal burst for several minutes.
   - Expected: reconciliation stays responsive; server TPS remains stable; no queue growth beyond what is expected for the bounded worker pool.

## Success criteria

- Late swap reliably results in a pop where the totem is introduced within the configured evidence window.
- Totem absence reliably results in a real death (no ghost survivors).
- No soft-locks after pending reconciliation, quit, or rapid repeated hits.
- No functional break across the targeted Paper-family versions supported by the reflection probes.

