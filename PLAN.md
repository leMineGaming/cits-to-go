# /goal — Build C-ITS Drive Assist: Complete Android & Android Auto Application

You are tasked with turning the existing repository into a complete, polished, production-quality Android application for C-ITS traffic information, GLOSA (Green Light Optimal Speed Advisory), and driving safety alerts.

**Work autonomously until the entire task is implemented, built, tested, and documented. Do not stop after planning or implementing only a subset of the features.**

Continue working through errors, missing implementations, integration problems, and failing tests. Make reasonable engineering decisions independently. Only ask me questions when an essential decision cannot be resolved by inspecting the repository or available documentation.

Repository:
https://github.com/Sascha8a/cits-to-go/tree/main

This is my fork of CITS-to-go. It already contains basic parsing functionality for C-ITS messages. It is the foundation of this project, not merely a reference.

## 1. General objectives

Build a complete Android application called **C-ITS Drive Assist**.

The application should:

- Receive C-ITS messages from external hardware over Wi-Fi TCP.
- Decode and process SPATEM, MAPEM and DENM messages.
- Provide GLOSA speed recommendations.
- Display relevant driving safety information.
- Provide a comprehensive mobile dashboard.
- Support multiple configurable driver profiles.
- Integrate with Android Auto as extensively as officially supported.
- Be easy to install, configure and use.
- Be fully buildable through GitHub Actions.
- Provide downloadable APK releases through GitHub Releases.

Target audience: Personal use initially, with an architecture that allows future public distribution.

## 2. First task: inspect and understand the existing repository

Before making architectural decisions:

1. Inspect the entire repository.
2. Read its README, Gradle files, Android manifests and existing documentation.
3. Identify the current programming language, Android architecture, minimum SDK and target SDK.
4. Understand how the existing CITS-to-go message parsers work.
5. Identify existing implementations of SPATEM, MAPEM and DENM.
6. Identify existing communication, UI and Android Auto functionality.
7. Identify incomplete, experimental or broken functionality.
8. Determine which components can be reused directly.

**Important: Do not unnecessarily rewrite existing working C-ITS parsers.**

Extend and improve the existing implementation wherever possible.

Preserve the original upstream attribution and applicable licenses.

Do not assume the existing repository uses a particular architecture. Adapt to its actual structure.

Create an implementation plan and immediately begin executing it.

## 3. C-ITS communication

Implement a reliable TCP client for communication with external C-ITS hardware.

Requirements:

- Configurable IP address and TCP port.
- Connection timeout configuration.
- Automatic reconnection.
- Configurable reconnect intervals.
- Connection status monitoring.
- Proper socket lifecycle management.
- Graceful shutdown.
- Network interruption handling.
- Wi-Fi connectivity monitoring.
- Connection diagnostics.
- Incoming byte-stream buffering.
- Correct message framing and reassembly.
- No assumption that one TCP read equals one complete C-ITS message.
- Robust handling of malformed and incomplete messages.

Reuse the existing CITS-to-go communication implementation if suitable.

Support the message formats and encoding profiles actually used by the existing project and hardware. Verify ASN.1 encoding and framing instead of assuming them.

Communication must run independently of the Android Auto interface.

The application should continue receiving and processing messages when its dashboard is not currently visible, subject to Android background execution restrictions.

Use an appropriate foreground service and notification when continuous background operation requires one.

## 4. C-ITS message support

Implement and integrate the following message types.

### SPATEM

Process:

- Intersection identifiers.
- Signal group identifiers.
- Current signal states.
- Upcoming signal states.
- Minimum end times.
- Maximum end times.
- Likely end times, where available.
- Message timestamps.
- Message freshness.
- Signal phase transitions.

Provide normalized internal models independent of ASN.1.

### MAPEM

Process:

- Intersection geometry.
- Reference coordinates.
- Road and lane geometry.
- Inbound and outbound lanes.
- Lane connections.
- Signal group associations.
- Lane attributes.
- Available road topology.

Implement coordinate transformations and spatial matching.

The application must be able to identify which traffic signal applies to the driver's current direction of travel.

### DENM

Implement comprehensive event processing.

Support:

- Management container.
- Action ID.
- Detection time.
- Reference time.
- Validity duration.
- Termination.
- Cancellation.
- Negation.
- Situation container.
- Cause codes.
- Subcause codes.
- Information quality.
- Location container.
- Event coordinates.
- Event speed and heading.
- Relevance distance.
- Relevance traffic direction.
- Event traces.
- Applicable alacarte information.

Handle the complete event lifecycle:

- New event.
- Repeated event.
- Updated event.
- Cancelled event.
- Negated event.
- Expired event.

Use the originating station ID and sequence number to identify events.

Avoid duplicate notifications.

Retain relevant event history.

Never treat a repeated DENM as a completely new event without checking its ActionID.

Support unknown cause codes gracefully. Preserve raw information wherever practical so future event types can be supported.

## 5. Vehicle positioning and intersection matching

Implement vehicle positioning using Android location services.

Use:

- GPS coordinates.
- GPS heading.
- Vehicle speed, where available.
- Location accuracy.
- Position timestamps.

Allow configuration of location update frequency and accuracy to balance responsiveness and battery consumption.

Implement:

1. Intersection proximity detection.
2. MAPEM coordinate transformation.
3. Inbound lane detection.
4. Heading-based lane matching.
5. Lane connection traversal.
6. Signal group identification.
7. SPATEM signal group matching.

Implement confidence scoring.

If the application cannot confidently determine which signal applies to the vehicle, it must not produce a misleading GLOSA recommendation.

Support an optional future hardware-provided position source.

## 6. GLOSA engine

Implement a complete GLOSA calculation engine.

It should calculate speed recommendations based on:

- Distance to the stop line.
- Current vehicle speed.
- Current traffic light phase.
- Expected green phase start and end.
- Signal timing uncertainty.
- Speed limits.
- Comfortable acceleration.
- Comfortable deceleration.
- Location accuracy.
- Message freshness.

Implement:

- Feasible arrival speed intervals.
- Recommended target speed.
- Green arrival windows.
- Red-light approach recommendations.
- Upcoming green phase calculations.
- Signal transition handling.
- Recommendation confidence.
- Stale-data handling.
- Impossible-arrival detection.

Support configurable driver comfort settings.

Never recommend exceeding the applicable speed limit.

Never recommend accelerating aggressively to catch a green light.

Do not interpret estimated SPATEM timings as guaranteed signal transitions.

If a safe recommendation cannot be calculated, clearly indicate that no recommendation is available.

Keep the calculation engine independent from Android UI code and make it extensively unit-testable.

## 7. DENM driving safety system

Implement a dedicated Safety Alert Manager.

Classify and present relevant events, including:

- Accidents.
- Dangerous end of queue.
- Stationary vehicles.
- Broken-down vehicles.
- Roadworks.
- Lane closures.
- Slippery roads.
- Reduced visibility.
- Obstacles on the road.
- Animals and pedestrians on the road.
- Dangerous road conditions.
- Other supported ETSI DENM event types.

Use the actual ETSI cause and subcause code definitions. Do not invent classifications.

For each event calculate:

- Distance.
- Direction relative to the vehicle.
- Relevance to the current road.
- Event age.
- Validity.
- Information quality.
- Confidence.
- Alert priority.

Implement configurable relevance distances and event filtering.

Make the filtering account for:

- Whether the event is ahead or behind.
- Whether the event affects the current driving direction.
- Whether the event belongs to the same road or connected road.
- Whether the event is still valid.
- Whether the vehicle is approaching or moving away from it.

Avoid alert spam through event deduplication and intelligent update handling.

### Alert priority

Implement at least three priority levels:

- Critical: Immediate and highly relevant hazards.
- Advisory: Relevant road conditions and restrictions.
- Informational: Events that may be useful but do not require immediate attention.

Allow users to configure which event types trigger notifications.

Do not automatically suggest evasive manoeuvres based solely on DENM information.

## 8. Unified driving information coordinator

Implement a central coordinator that manages GLOSA and DENM information.

Examples:

- Normal driving: Show GLOSA.
- Roadworks ahead: Show GLOSA and a compact roadworks advisory.
- Accident ahead: Prioritize the accident warning.
- Critical hazard: Suppress unnecessary GLOSA information.
- Stale C-ITS data: Suppress invalid recommendations.
- Lost hardware connection: Display connection status and disable unsupported functionality.

Prioritize driver safety and glanceability.

Make the priority system configurable where appropriate.

## 9. Complete mobile dashboard

Build a polished, complete Android application dashboard.

The mobile application is the primary configuration and diagnostics interface.

Use modern Android UI technologies compatible with the existing repository. Prefer Jetpack Compose if the project architecture supports it; otherwise make an informed decision based on the existing implementation.

Design direction:

- Modern dark interface.
- Rounded cards.
- Clear visual hierarchy.
- High-quality icons.
- Smooth transitions.
- Consistent typography.
- Responsive layouts.
- Proper light and dark themes.
- Accessible contrast.
- No unnecessary visual clutter.

Implement the following screens.

### A. Home / Driving dashboard

Show:

- Hardware connection status.
- Current vehicle position.
- Current speed.
- Current intersection.
- Current traffic light.
- GLOSA recommendation.
- Green arrival window.
- Relevant DENM alerts.
- Active event count.
- C-ITS message freshness.
- Location confidence.

Make the most important information immediately visible.

### B. C-ITS connection settings

Allow configuration of:

- Hardware IP.
- TCP port.
- Automatic connection.
- Reconnect behaviour.
- Connection timeout.
- Background operation.

Include connection testing and detailed diagnostics.

### C. Traffic light screen

Display:

- Current signal state.
- Signal group.
- Intersection ID.
- Next signal phase.
- Timing estimates.
- MAPEM lane information.
- GLOSA recommendation.

Provide a useful visualization of the traffic signal.

### D. Safety alerts screen

Display active DENM events and event history.

Include:

- Event type.
- Distance.
- Direction.
- Detection time.
- Remaining validity.
- Severity.
- Information quality.
- Event status.

Provide filtering by event type and severity.

### E. MAPEM debugging screen

Provide a technical visualization of decoded intersections and lanes.

Display lane connections and signal group associations.

Where practical, provide a map-based visualization.

### F. Raw C-ITS diagnostics

Provide a developer-oriented view of:

- Incoming messages.
- Message types.
- Decoding results.
- Timestamps.
- Message rates.
- Invalid packets.
- Connection errors.

Allow exporting diagnostic logs without exposing sensitive information unnecessarily.

### G. Settings

Implement comprehensive settings with persistent storage.

## 10. Driver profiles

Implement a complete driver profile system.

Users must be able to create, edit, duplicate, delete and select profiles.

Each profile should independently store:

- Profile name.
- GLOSA preferences.
- Comfortable acceleration.
- Comfortable deceleration.
- Preferred target speed behaviour.
- Maximum configured speed cap.
- Alert preferences.
- Enabled DENM event categories.
- Alert relevance distances.
- Audio warning preferences.
- Dashboard preferences.
- Display preferences.

Use sensible defaults.

Support a default profile and automatic loading of the selected profile at application startup.

Changing profiles should immediately update the relevant application behaviour.

Persist profiles locally.

Use a robust storage format and implement migration support for future schema changes.

Do not silently overwrite user settings during updates.

## 11. Android Auto integration

Implement as much official Android Auto functionality as technically and legally possible.

This is a major feature, not an optional afterthought.

First inspect the existing Android Auto implementation, if any.

Use the official Android for Cars App Library and supported templates.

Investigate the most suitable supported app category for this application.

The application is initially intended for personal use and sideloading, but must still respect Android Auto host restrictions.

Implement the best possible driving interface using officially supported APIs.

Potential information to display:

- Current traffic light state.
- GLOSA target speed.
- Green arrival window.
- Distance to intersection.
- Relevant safety alerts.
- Hardware connection state.
- Current C-ITS data freshness.

Investigate appropriate templates and capabilities, including:

- NavigationTemplate.
- PaneTemplate.
- Alert.
- Navigation notifications.
- Other supported information presentation mechanisms.

Do not assume that an application can freely render custom Android Auto layouts.

Do not falsely declare a navigation category without implementing the functionality required by that category.

If a desired feature is restricted by Android Auto, implement the closest officially supported alternative and document the limitation.

Do not use undocumented projection APIs, exploits, screen mirroring tricks or unsupported template bypasses.

Implement the Android Auto application service, manifest declarations, automotive app metadata, session handling and lifecycle management.

The Android Auto interface should be minimal and glanceable.

Avoid requiring user interaction while driving.

The phone dashboard must remain fully functional independently of Android Auto.

## 12. Audio and notifications

Implement configurable audio warnings for relevant DENM events.

Requirements:

- Configurable alert sounds.
- Different sounds for priority levels.
- Optional text-to-speech.
- Duplicate suppression.
- Respect Android audio focus.
- Configurable volume.
- Quiet mode.
- No unnecessary repeated warnings.

Ensure that warnings are not excessively distracting.

Provide an option to disable individual audio alert categories.

## 13. Data persistence and architecture

Use an appropriate local persistence solution.

Prefer Room for structured data if suitable for the existing architecture.

Persist:

- Driver profiles.
- Application settings.
- Event history.
- Selected profile.
- Relevant diagnostics.

Keep the following components independent:

- TCP communication.
- C-ITS decoding.
- Message normalization.
- Intersection matching.
- GLOSA calculations.
- DENM processing.
- Safety alert management.
- UI.
- Android Auto integration.

Use Kotlin coroutines and StateFlow where appropriate.

Avoid global mutable state.

Implement proper dependency management and lifecycle handling.

## 14. Easy installation and configuration

The application must be easy to set up.

On first launch:

1. Present a short onboarding process.
2. Explain the hardware connection requirements.
3. Allow entering the hardware IP and TCP port.
4. Test the connection.
5. Request location permissions when needed.
6. Explain background operation requirements.
7. Create or select a driver profile.
8. Show a connection and decoding test.

Provide useful error messages and setup guidance.

Do not require users to edit configuration files manually.

Provide sensible defaults.

Allow exporting and importing application configuration and driver profiles.

## 15. GitHub Actions: Build and publish

Create a complete GitHub Actions workflow system.

At minimum, implement the following.

### CI workflow

File: `.github/workflows/android-ci.yml`

Triggers:

- Push to main.
- Pull requests.
- Manual workflow dispatch.

Actions:

1. Check out the repository.
2. Set up the appropriate JDK.
3. Set up Android SDK.
4. Configure Gradle caching.
5. Run formatting and static checks where available.
6. Run unit tests.
7. Build the debug APK.
8. Build the release APK without requiring signing secrets.
9. Upload generated APKs as workflow artifacts.
10. Report failures clearly.

### Release workflow

File: `.github/workflows/android-release.yml`

Triggers:

- Git tags matching `v*`.
- Manual workflow dispatch with a version input.

Actions:

1. Check out the requested revision.
2. Determine the release version.
3. Set up the Android build environment.
4. Run all required tests.
5. Build the release APK.
6. Optionally build an Android App Bundle.
7. Sign release artifacts when signing secrets are configured.
8. Generate release notes from commits or changelog.
9. Create a GitHub Release.
10. Attach the APK and any additional distributable artifacts.

Support unsigned APK generation for personal testing when signing secrets are unavailable.

For signed releases, use GitHub Actions secrets for keystore data and passwords.

Never commit signing keys, credentials or private configuration.

Document the required repository secrets and how to configure them.

Make sure version codes increase correctly.

### Additional quality requirements

- Pin or appropriately constrain GitHub Actions dependencies.
- Use least-privilege workflow permissions.
- Do not expose secrets in logs.
- Fail releases if required tests fail.
- Ensure artifacts have predictable names.

Provide a clear README section explaining how to build locally and download releases.

## 16. Testing

Testing is a mandatory part of this task.

Implement unit tests for:

- TCP message framing.
- TCP reconnect logic.
- SPATEM decoding.
- MAPEM decoding.
- DENM decoding.
- DENM event identification.
- DENM cancellation and negation.
- DENM expiry.
- DENM deduplication.
- Cause/subcause classification.
- Coordinate transformations.
- Lane matching.
- Signal group matching.
- GLOSA speed calculations.
- GLOSA timing uncertainty.
- GLOSA stale-data handling.
- Safety alert relevance.
- Alert prioritization.
- Driver profile persistence.
- Profile switching.

Use recorded or synthetic C-ITS messages where real hardware is unavailable.

Create a simulation/test data source that allows the application to be tested without connecting to actual traffic infrastructure.

Test edge cases, malformed data and stale information.

Do not claim a test passed unless it was actually executed.

If Android Auto cannot be tested in the available environment, document exactly what requires manual testing in the Android Auto Desktop Head Unit or a real vehicle.

## 17. Documentation

Create or update the README with:

- Project description.
- Feature overview.
- Screenshots where available.
- Architecture overview.
- Supported C-ITS message types.
- Hardware compatibility.
- TCP configuration.
- Installation instructions.
- Android Auto setup.
- Driver profile configuration.
- GLOSA explanation.
- DENM explanation.
- Local build instructions.
- GitHub Actions information.
- Release installation instructions.
- Known limitations.
- Troubleshooting.

Document the exact ASN.1 schemas and encoding profiles used.

Document any features that require specific hardware capabilities.

Include a disclaimer that C-ITS and GLOSA information is supplementary and must never replace observing actual road conditions and traffic signals.

## 18. Engineering quality requirements

These requirements apply to the entire project:

- Buildable Android application, not just sample code.
- No placeholder implementations for core functionality.
- No TODO-only implementations of requested features.
- No fake C-ITS data presented as live data.
- No hardcoded hardware IP addresses.
- No hardcoded credentials.
- No swallowed exceptions.
- No unbounded background loops.
- No memory leaks.
- No unnecessary battery consumption.
- No duplicate event notifications.
- No unsafe speed recommendations.
- No unsupported Android Auto APIs.
- No unnecessary rewriting of existing working code.

Use structured logging.

Handle errors gracefully.

Keep code modular, maintainable and well documented.

Preserve upstream licenses and attribution.

## 19. Execution strategy — DO NOT STOP EARLY

Work through the entire task autonomously.

Use the following process:

1. Inspect the repository.
2. Establish the existing architecture.
3. Identify reusable implementations.
4. Create the implementation plan.
5. Implement the core communication and parsing.
6. Implement message normalization.
7. Implement vehicle positioning and matching.
8. Implement GLOSA.
9. Implement DENM and safety alerts.
10. Implement the unified driving information coordinator.
11. Implement the complete mobile dashboard.
12. Implement driver profiles and settings.
13. Implement Android Auto integration.
14. Implement onboarding and setup.
15. Implement tests and simulation.
16. Implement GitHub Actions.
17. Write documentation.
18. Run the full test suite.
19. Build the application.
20. Fix all discovered problems.
21. Repeat validation until the project is in a working state.

After each major phase, verify the implementation before moving forward.

If a build fails, investigate and fix the underlying issue rather than merely documenting it.

If an API or feature is unavailable, investigate alternatives and implement the best supported option.

Do not abandon a feature because its first implementation attempt fails.

Do not stop after creating a roadmap, architecture document or skeleton project.

Do not wait for confirmation after every implementation step.

You have permission to make reasonable decisions about dependencies, architecture, UI and implementation details.

Keep a persistent `IMPLEMENTATION_STATUS.md` file containing:

- Completed features.
- Remaining features.
- Build status.
- Test status.
- Known limitations.
- Decisions made.
- Current next steps.

Update this file throughout development.

**Completion criteria:**

The task is only complete when:

- The existing repository has been transformed into the intended application.
- All implementable core features are implemented.
- The mobile dashboard is functional.
- Driver profiles work and persist.
- SPATEM, MAPEM and DENM processing is integrated.
- GLOSA calculations work and have tests.
- DENM event management works and has tests.
- Android Auto integration is implemented to the maximum officially supported extent.
- Onboarding and configuration are implemented.
- GitHub Actions CI and release workflows exist.
- Documentation is complete.
- Tests have been executed.
- A successful Android build has been produced, or any environment-specific blocker is precisely identified.

At completion, provide a concise summary of the implementation, important architectural decisions, tests executed, build artifacts, GitHub Actions workflows and any remaining limitations.

**Start now. Do not stop until the completion criteria are satisfied.**
