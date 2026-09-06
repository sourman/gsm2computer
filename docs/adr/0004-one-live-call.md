# ADR 0004: One live GSM/hub call; waiting legs are rejected

- **Status:** accepted
- **Date:** 2026-09-05

## Context

The gateway phone is a single-seat OpenClaw Talk splice: one Chromium Talk session, one PipeWire bridge, one hub WebSocket. Call-waiting on the SIM still delivers a second `android.telecom.Call` to `GsmCallService` while the first is live. A second simulator (or a second phone) can also open a WebSocket to the hub.

The product is not a PBX. Conferencing, hold, or swapping would mean two Talk sessions, two uplink mixes, and a second Chromium mic — none of which exist.

A waiting leg used to be able to **kill the live call**: `GsmCallManager` kept a single `activeCall` pointer and `onCallAdded` overwrote it with the ringing call. When that waiting call was rejected and removed, `restoreAudio()` and `onGsmCallEnded` ran against the live session and tore down the hub bridge.

## Decision

**One live call.** A second inbound is rejected. The live caller stays on OpenClaw.

| Layer | Busy behavior |
|-------|----------------|
| Hub WebSocket | HTTP **409 Conflict**, connection closed. Slot is claimed (`call_busy`) before the 101 upgrade so a second handshake cannot start. |
| Android orchestrator | If `bridgeState != IDLE` and the `Call` is not the live one, `rejectCall` that waiting leg. Duplicate RINGING on the live `Call` is ignored. |
| `GsmCallManager` | `activeCall` is the bridged call only. Waiting/rejected `Call` objects must not replace it, must not run `configureAudioBridge` / `restoreAudio`, and must not look like hangup. |
| Dialer | Outgoing dial while not `IDLE` fails with “Busy — cannot dial” (stale-state force-reset after 60s is unchanged). |

No call-waiting UI, hold, swap, or queue. Loopback (`/loopback`) uses the same hub slot as OpenClaw Talk.

## Consequences

- The second caller hears reject/busy from the network, not a second agent.
- Mixer restore stays tied to the live call ending, not to a waiting leg disappearing.
- Hub `/health` still describes one session; overlapping Talk Chromium sessions are out of scope.
