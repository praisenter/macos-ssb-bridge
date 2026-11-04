# macOS Security Scoped Bookmarks

Java 22+ FFM access to macOS app-scoped security bookmarks. No JNI bridge,
native compilation, bundled native library or runtime dependency is required.

Maven coordinates: `org.praisenter:macos-ssb-bridge`.

## Build

Install JDK 22+ and Maven 3.9+, then run:

```sh
mvn clean verify
mvn install
```

Ordinary builds use the checked-in generated Java. They do not need an Apple
SDK or `jextract`, and work on Windows, Linux and macOS. Native operation is
limited to 64-bit macOS Intel and Apple Silicon. Build output includes the JAR,
sources, Javadoc and license/provenance resources.

```xml
<dependency>
  <groupId>org.praisenter</groupId>
  <artifactId>macos-ssb-bridge</artifactId>
  <version>0.1.0</version>
</dependency>
```

For a modular consumer, enable native access in the Java launcher:

```text
--enable-native-access=org.praisenter.macos.ssb
```

For a classpath consumer, use `--enable-native-access=ALL-UNNAMED` instead.
A dependency JAR cannot grant native access to its consumer. The library checks
this requirement before initializing the generated bindings.

## API And Migration

```java
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.praisenter.macos.ssb.SecurityScopedBookmarks;

String bookmark = SecurityScopedBookmarks.createBookmark(selectedPath.toString());
// Persist bookmark, then reload it after relaunching the same sandboxed app.
String url = SecurityScopedBookmarks.startResourceAccessing(bookmark);
try {
    Path file = Path.of(URI.create(url));
    byte[] contents = Files.readAllBytes(file);
} finally {
    SecurityScopedBookmarks.stopResourceAccessing(bookmark);
}
```

- **Create:** takes a filesystem path, not a file URI, and returns standard
  Base64 of Apple's opaque bookmark bytes. Persist that string unchanged.
- **Start:** takes that Base64 and returns an absolute percent-encoded file URL,
  not a plain path. Decode using `URI` and `Path`, not string slicing.
- **Stop:** takes the original Base64, not the returned URL. Each successful
  start requires a matching stop. Nested starts share one native activation;
  final stop uses and releases the exact native URL that was activated.

Stopping does not delete or invalidate persisted bookmarks. Valid unknown or
already stopped bookmarks are a no-op; a later start may reactivate the bookmark.
Unbalanced starts retain scoped permissions and native objects until stopped
or process exit. No automatic persistence or cleaner-based lifecycle is provided.

Null arguments produce `NullPointerException`; empty/malformed arguments produce
`IllegalArgumentException`. Native create/resolve errors produce
`IllegalStateException` with available Core Foundation diagnostics. A false
native start produces `SecurityException`: this means scoped activation failed,
not necessarily that ordinary filesystem access is denied. An unsandboxed process
may already have access and cannot prove sandbox correctness.

### Stale Bookmarks

Usable stale bookmarks resolve normally. The three-method API does not expose
staleness or return refreshed bookmark bytes. To refresh proactively, start using
the old bookmark, decode the returned URL to a filesystem path, create and
persist a new bookmark while access is active, and stop using the **old** bookmark.
Resolution failures may require asking the user to select the resource again.

## Generate Bindings On macOS

Maintainers need macOS, Xcode or Command Line Tools with a macOS SDK, a JDK 22+,
and the native macOS build of
[jextract 22-jextract+6-47](https://jdk.java.net/jextract/22).
Use the download matching the Mac's architecture. Normal consumers do not need
the SDK or `jextract`.

```sh
bash scripts/generate-ffm.sh --jextract /path/to/jextract/bin/jextract
```

The script discovers the active SDK using `xcrun --sdk macosx --show-sdk-path`.
To select a different SDK:

```sh
bash scripts/generate-ffm.sh \
  --jextract /path/to/jextract/bin/jextract \
  --sdk /path/to/MacOSX.sdk
```

Alternatively, put `jextract` on `PATH` or set `JEXTRACT`. Set `JAVA_HOME` to a
JDK 22+ or put `javac` on `PATH`. Add `--check` to compare regeneration against
the checked-in sources without replacing them; `--help` lists the options.

Generation uses an include-only C wrapper over the actual SDK's `CFURL.h` and
dependencies, selecting the same 17 functions and four constants. It runs
natively for the host Mac architecture and reads the SDK framework headers
directly. There is no Windows include overlay, cross-target ABI normalization,
or generated-source post-processing.

**Use jextract 22 rather than 25.** Native generation chooses macOS C-long
layouts automatically, but the previously used jextract 25 build also emits
`SymbolLookup.findOrThrow`, which does not compile against Java 22 without
rewriting. Jextract 22 avoids that compatibility rewrite while retaining the
project's Java 22 baseline. If newer SDK headers are not supported by this
tool's Clang parser, select a compatible SDK with `--sdk`; do not rewrite Apple
declarations or generated Java to force a successful run.

The script stages output in a temporary directory, checks that all selected
functions/constants were emitted, and compiles the unmodified Java with
`--release 22` before copying it into `internal.generated`. Do not manually
edit that package. Ordinary Maven builds still use checked-in sources and
continue to work on Windows and Linux. Generated bindings should only be
initialized on macOS.

The currently checked-in artifacts were initially extracted and normalized on
Windows using jextract 25 and a public macOS 11.3 SDK snapshot. Their measured
size remains **2 Java files, 1,170 lines, 42,592 source bytes**. Historical
provenance is retained in `scripts/generation.json`, included in the JAR;
the new native macOS regeneration workflow has **not yet been run**.
The first native regeneration may legitimately differ due to the changed
generator or SDK, so inspect those changes before expecting `--check` to pass.

After native regeneration, update the recorded tool/SDK versions, architecture
and output measurements, then run `mvn clean verify`. Compare the selected
signatures/layouts and test on both Intel and Apple Silicon before declaring
the shared generated package validated for both. SDK acquisition and use remain
subject to applicable Apple terms; SDK files and installers are not bundled or
downloaded by this script or ordinary builds.

## macOS Validation

On each intended Mac architecture:

```sh
mvn -Dtest=CoreFoundationTest test
mvn clean verify
```

Tests check generated scalar carriers and LP64 layouts, UTF-8 conversion,
bookmark create/resolve for files and directories with special characters, and
malformed native bookmark errors. These tests exercise real Core Foundation;
unexpected native errors fail, rather than being silently skipped.

### Sandboxed Acceptance

Use the same signed sandboxed application identity for creation and relaunch.
For a read-write app-scoped workflow, the host generally needs:

```xml
<key>com.apple.security.app-sandbox</key><true/>
<key>com.apple.security.files.user-selected.read-write</key><true/>
<key>com.apple.security.files.bookmarks.app-scope</key><true/>
```

The host must also satisfy its Java runtime's signing and execution requirements.
This library does not configure signing, entitlements or an OS-mediated chooser.

1. Select a file/directory through the application's native chooser, create its
   bookmark and persist the Base64. Do not log or publish bookmark payloads.
2. Exit and relaunch the same signed app without selecting the resource again.
   Start access, read/write through the decoded URL, and exercise nested starts
   and matching stops. For a directory, verify access to selected descendants.
3. Test repeated stop, reactivation, rename/move, revoked access/missing targets,
   and old JNI bookmarks created by that same app.
4. Exercise deliberate refresh while old access remains active. A final stop
   need not cause I/O denial if some other sandbox extension authorizes the path.

An opt-in test method, `accessesPersistedBookmarkInsideSandbox`, accepts the
`ssb.sandbox.bookmark` system property. It must run **inside the sandboxed JVM**
to establish sandbox behavior. Running Maven with that property in an ordinary
terminal does not turn Maven or its test JVM into a sandboxed application.

## CI And Releases

CI verifies Java 22 and 25 on Windows, Linux, macOS Apple Silicon (`macos-15`)
and Intel (`macos-15-intel`). The workflow is configured but has not been run
here. Regular CI does not regenerate bindings or prove app relaunch permissions.

CD runs the complete CI matrix before publishing a `v*.*.*` tag whose version
matches a non-snapshot POM. Configure the `maven-central` environment with
required reviewers to make local sandbox acceptance an explicit release gate.
Required publishing secrets match `ndi-bridge`: `gpg_private_key`,
`gpg_passphrase`, `nexus_username` and `nexus_password`. Maven Central credentials
are Portal user-token credentials; no secrets belong in the repository.

### Automated Binding Updates

Run **Generate FFM Bindings** from the repository's Actions tab using **Run
workflow**, selecting the branch to update. The generation job installs Zulu
JDK 22 and checksum-verified native `jextract 22-jextract+6-47` on `macos-15`
(Apple Silicon), selecting the preinstalled Xcode 16.4 SDK rather than the
runner's default Xcode version.

Bindings are generated once on ARM with `scripts/generate-ffm.sh` without
post-processing. Separate test jobs download that same generated artifact on
`macos-15` (ARM64) and `macos-15-intel` (Intel), replace the checked-in bindings,
and run `mvn clean test -Dtest=CoreFoundationTest` with Zulu JDK 22. These JUnit
tests exercise generated layouts, native string conversion, bookmark round
trips and native error handling. The sandbox-only test remains opt-in and is
not exercised by ordinary hosted runners. JUnit reports are retained as artifacts.

Both architecture test jobs must pass before the workflow updates the generated
Java classes and `scripts/generation.json` in an automation PR. There is no
Intel generation or generated-source comparison step. Repeated runs update
the same PR branch for the selected base branch; unchanged output/provenance
does not create another PR. A base branch change during generation requires a
fresh workflow run.

Enable **Settings > Actions > General > Workflow permissions > Allow GitHub
Actions to create and approve pull requests** (organization policy may control
this setting). Only the PR job requests contents/pull-request write access;
generation jobs remain read-only. The workflow does not merge its PRs.

The default `GITHUB_TOKEN` can create the PR, but GitHub does not trigger other
push/PR workflows for changes made with that token. To trigger normal PR CI,
configure the optional `BINDINGS_PR_TOKEN` repository secret with an appropriately
scoped GitHub App token or PAT having contents and pull-requests write access.
Never put token values in source or workflow inputs. Native sandboxed app
relaunch testing remains a separate acceptance requirement.

The workflow has been statically validated locally, not executed on GitHub.
Its first run must establish actual SDK/parser compatibility and native test
results. If the ARM hosted image drops Xcode 16.4, update the pinned generation
SDK deliberately. The architecture test jobs use the generated Java and system
frameworks; they do not install jextract or regenerate bindings.

No release, commit or publication has been performed during implementation.

## License

BSD-3-Clause; see `LICENSE`. Apple SDK/framework terms apply separately.