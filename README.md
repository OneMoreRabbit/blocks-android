# Blocks for Android

**Pick up a project without piecing yesterday back together.** Blocks gives each project a small, dependable place to return to: what matters now, useful references, and—when you choose to leave one—the exact place you stopped. It is built for people moving between several projects, interrupted days, and work that rarely fits a perfect plan.

The dashboard gets you to a project quickly. Opening one is just browsing: it does not start a session, lock another project, or demand a check-out ritual. You can create, rename, archive and reopen projects; an archived project remains available to read. The Project page puts the latest context first, with lightweight Notes, Links and history close by.

## Leave a useful trail, only when it helps

A **Park** is an optional three-part note to your future self:

1. Where am I?
2. What is the exact next physical action?
3. What must I not forget?

Back to the dashboard does not require a Park. If you do add one, it stays in the project's history rather than becoming a task, deadline or measure of progress. Notes capture small pieces of context you explicitly save; Links launch the tools and documents that already own the work. Blocks references those resources instead of copying them into a second task manager. Project links can also be shared as QR codes or written to NFC tags, so a physical cue can take you back to the right project.

## Designed around interruptions

Ordinary project actions are saved on the Android device first. Creating a project, adding a Note or Park, and other edits do not wait for a network round trip; background synchronization can catch up later. That choice keeps the foreground experience useful when connectivity is poor. The app keeps separate local data for each established account and tenant. Signing out hides that data rather than silently deleting it; Service confirmation still governs identity and access.

The interface stays deliberately narrow. Projects are freely browsable, parking is optional, and there is no score for how consistently you use either. Links remain links. Project covers are local to Android today, not a promised cross-device media library.

## How the pieces fit

- **[Blocks Android](https://github.com/OneMoreRabbit/blocks-android)** is the local-first client. It owns device presentation, local work, QR/NFC interaction and a durable background sync queue.
- **[Blocks Service](https://github.com/OneMoreRabbit/blocks-service)** is the authenticated shared API and domain authority. It checks tenant membership, stores shared project data, and accepts versioned, retry-safe synchronization. Its API 0.8 is deployed; Android consumes that published contract.
- **Blocks Web** (not yet public) is a planned browser client of the same Service API, not a direct database client. Its component and identity design are registered, but there is no live Web product UI yet. A Windows client is parked, not part of this release.

Android **v0.10.1** is a custody-signed pilot, not a Play Store release. The Service deployment is live, but physical signed-upgrade data retention and two-device production synchronization remain open observations; a local test is not proof of those journeys. See [release notes](release-notes.md) for the latest Android change.

## Build and contribute

This is a Kotlin Android app with a Gradle wrapper. Use JDK 17 and Android SDK 37. The checked-in Service OpenAPI artifacts generate the client models; do not hand-edit them or infer a new wire shape from examples. From the repository root:

```sh
./gradlew :app:verifyServiceApiContract :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug sign-in and synchronization need access to the project's test Service; a successful APK build alone does not establish those journeys. Android instrumentation tests additionally need a compatible emulator or device. Release signing is handled by the project's custody process, not by this repository's debug build.

Contributors with access to the Blocks Atlas vault should run `sh scripts/atlas-context.sh` before changing behavior. It delivers the current architecture brief and exact upstream contract artifacts; [AGENTS.md](AGENTS.md) describes the component workflow. This README is an introduction, not a substitute for the accepted API contract or device evidence.
