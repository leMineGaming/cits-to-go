# Drive Assist — decoder scope and design notes

This document records the exact C-ITS encoding/profile assumptions and the intentional limitations of
the Drive Assist layer. It complements the README.

## Wire format and framing

The Android receive path expects complete link-layer frames containing a GeoNetworking envelope, not
bare BTP payloads. The existing `GeoNetworkingFrameParser` locates the LLC/SNAP header

```
aa aa 03 00 00 00 89 47
```

and then parses the GeoNetworking Basic Header. Supported Basic Header next-header values:

- `1` — Common Header, followed by the Extended Header and BTP.
- `2` — Secured Packet (IEEE 1609.2). The parser looks for the signed `unsecuredData` prefix and
  locates the inner Common Header; security trailers are not passed to the ASN.1 decoders.

BTP-B destination ports used by the pipeline:

| Port | PDU |
| --- | --- |
| 2003 | MAPEM |
| 2004 | SPATEM |
| 2001 | DENM/CAM (message ID disambiguates) |

ITS PDU header: `protocolVersion` must be `2` for MAPEM/SPATEM/DENM. The header carries
`messageId` and `stationId`; the six-byte header is skipped before ASN.1 decoding.

TCP framing is independent of the above. Supported modes are implemented in
`drive/net/TcpFrameDecoder`:

- `LengthPrefixed4BigEndian` (default): 4-byte unsigned big-endian length, then frame bytes.
- `LengthPrefixed2BigEndian`: 2-byte unsigned big-endian length.
- `Cobs`: COBS records terminated by `0x00`, matching the USB firmware.
- `NewlineHex`: one hexadecimal frame per LF/CRLF line, spaces/colons/hyphens allowed.

A malformed length, malformed COBS record or bad hex line is reported and skipped. The decoder never
assumes a TCP read boundary equals a frame boundary and bounds both buffered and emitted data.

## MAPEM (message ID 5)

Decoder: `intersection/MapSpatDecoder.kt`, reusing the upstream UPER reader.

Retained fields:

- `MapData`: time stamp (skipped), layer type/id (skipped), intersection list.
- `IntersectionGeometry`: name, reference ID, revision, reference position (lat/lon E7), lane width,
  regulatory speed limits (converted from 0.01 m/s to km/h), generic lanes.
- `GenericLane`: lane ID, ingress/egress approach, lane type attributes, ingress/egress flags,
  node list (cumulative `NodeXY` offsets), lane connections.
- `LaneConnection`: destination lane, signal group, connection ID, remote intersection.
- Node attributes: stop-line flag, width delta.
- Lane data attributes: node-attached regulatory speed limits are parsed into `MapLane.speedLimitsKph`
  and used by GLOSA before the intersection-level limit.

Not supported (the decoder throws a structured `IntersectionDecodeException` rather than guessing):

- MAPEM/IntersectionGeometry/MovementState/GenericLane ASN.1 extensions.
- Computed lanes (`ComputedLane`).
- Regional extensions are skipped by length.
- Intersection `preemptPriorityData` and advisory speed branches of movement events.

Geometry convention: MAPEM node `x` is east and `y` is north in centimetres relative to the
intersection reference point. `GeoMath` converts these to local metres and to E7 coordinates.

## SPATEM (message ID 4)

Retained fields:

- `SPAT`: regions skipped, intersection states.
- `IntersectionState`: reference ID, revision, MOY, timestamp (milliseconds within the minute),
  enabled lanes (skipped), movement states, maneuver assist (connection IDs).
- `MovementState`: signal group, movement events, connection IDs.
- `MovementEvent`: phase state (`MovementPhaseState` 0–9), `TimeChangeDetails` min/likely/max end
  and confidence. Unknown phase codes map to `Unknown` and never produce GLOSA.

Timing model: `minEndTime` is the conservative end of the current phase. The remaining phase is
derived from the SPATEM MOY + timestamp and the local reception time, including an hour-boundary
rollover check. `SignalSnapshot` exposes min/likely/max seconds and confidence.

## DENM (message ID 1)

Decoder: `denm/DenmDecoder.kt`.

Retained fields:

- Management: originating station ID, sequence number, detection time, reference time, termination
  (cancellation/negation), reference position, relevance distance/traffic direction, validity
  duration, transmission interval, station type.
- Situation: information quality, cause code, subcause code, event zone.
- Location: traces (`detectionZonesToEventPosition` paths), event speed (0.01 m/s) and event heading
  (0.1 degree).
- `AlacarteContainer` root fields are normalised in `DenmAlacarte`: lane position; impact reduction
  (dimensions, pillars, centre of mass, wheelbase, turning radius, front axle, occupants, mass,
  request/response); external temperature; roadworks (light bar/siren, closed lanes, restricted
  station types, speed limit, incident cause, recommended path, starting point, traffic-flow rule,
  referenced ActionIDs); positioning solution; and stationary-vehicle details (duration, cause,
  dangerous goods, occupants, vehicle identification, energy storage). Unknown future extension
  additions are skipped by the extensible-sequence rules and recorded as a partial decode rather
  than guessed.

Event identity is `(originatingStationId, sequenceNumber)`. The manager applies the receiving rules
of EN 302 637-3: ignore outdated detection/reference times, refresh equal repetitions, update on
newer content, hide cancellation/negation, expire with validity. History retains terminations and
expirations.

Cause/subcause labels and icon groups reuse `livemap/DenmCauses` (ETSI CDD v1.3.1/v2). Unknown
cause codes are retained and classified as `Other`.

## GLOSA model

Inputs: distance to stop line, current speed, road speed limit, phase state, min/likely/max end,
timing confidence, SPAT age, position accuracy, lane confidence, driver profile.

Core equations (`drive/glosa/GlosaEngine.kt`):

- Comfortable reachable speed: `v_reachable = sqrt(v² + 2·a_comfort·d)`.
- Required speed to arrive before green end: `v_required = d / (green_remaining − safety_margin)`.
- Green is feasible only if `v_required ≤ min(v_limit, v_reachable)`.
- Red arrival speed: `v_target = d / (red_remaining + arrival_buffer)`.
- Required deceleration: `a = (v_target² − v²) / (2·d)`; if it exceeds comfortable deceleration, the
  engine stops advising a rolling arrival and recommends stopping.

Never: recommend above the road/profile limit, exceed comfortable acceleration, treat likely times as
guaranteed, or produce a number when data is stale/low-confidence/impossible.

## Lane matching

1. Convert the vehicle position to local east/north metres around each MAPEM reference point.
2. For every inbound vehicle lane, project the position onto every polyline segment.
3. Reject lanes whose lateral offset is implausible or whose heading differs by more than 75°
   (a stopped fix without bearing is allowed at lower confidence).
4. Reject positions past the stop line.
5. Compute confidence from heading, lateral offset, position accuracy, intersection distance and
   signal-group evidence (explicit lane connection > movement fallback > none).
6. Pick the highest-confidence lane; below 0.45 confidence GLOSA is prohibited.

## Persistence decisions

- Profiles and settings: `SharedPreferences` holding one versioned JSON document each. Unknown or
  missing keys keep defaults; corrupt documents fall back to defaults without overwriting the stored
  document. This avoids silently wiping user settings during updates.
- Event history: one SQLite table with a `(station_id, sequence_number, version)` primary key and a
  `received_at` index. A future schema version can `ALTER TABLE` without dropping history.
- No Room/KSP dependency was introduced; the history is one append-only table.

## Background and lifecycle

`DriveAssistService` is a foreground service (`location|dataSync`) owning:

- the TCP client and its reconnect loop,
- the location subscription,
- network callbacks,
- the 500 ms engine ticker and alert notification/audio dispatch.

The `DriveEngine` is application-scoped and shared with the phone UI and Android Auto. Starting a
service does not require the dashboard to be visible. The service stops its socket, location
requests, audio engine and coroutine scope in `onDestroy`; the notification offers an explicit Stop
action. `stopWithTask=false` keeps it alive when the app task is removed, unless the user disabled
background operation.

## Android Auto

`DriveCarAppService` returns official templates only and declares the required Car App permissions
`androidx.car.app.NAVIGATION_TEMPLATES`, `androidx.car.app.MAP_TEMPLATES` and
`androidx.car.app.ACCESS_SURFACE`. `androidx.car.app.minCarApiLevel` is a `<application>` metadata
entry; `CarAppService.getAppInfo()` reads it from application info, and placing it on the service
causes the host to reject the app with `Min API level not declared in manifest`:

- The main driving screen is always a stable `NavigationTemplate` with `MessageInfo` and a status
  action. Keeping the template type stable prevents template-quota/type-switch errors.
- `DriveCarStatusScreen`, reached only by the driver's status action, uses `PaneTemplate`.
- If template construction throws, the screen logs the cause under `DriveCarApp` and returns a valid
  `MessageTemplate` fallback instead of letting the host show its generic "unknown error".

The screen refreshes only when a coarse state fingerprint changes (signal, advisory, primary alert,
connection, speed/distance buckets) and at a 2 s minimum interval, rather than invalidating every
second. It registers the official `SurfaceCallback` required by map-backed navigation templates and
draws a neutral placeholder surface; the app has no head-unit map provider and does not pretend to
offer turn-by-turn navigation. Car notifications use `CarAppExtender`.

`HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` is used because the app is sideloaded/personal-use; it is
the documented testing validator, not a bypass of template restrictions.
