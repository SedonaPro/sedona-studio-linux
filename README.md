# Sedona Studio for Linux

A standalone Java/JavaFX visual engineering environment for Sedona applications:
function-block diagrams, live diagnostics, local simulation and kit development.
An optional SCADA module adds operator screens, alarms and stored value history.

## Capabilities

- Hierarchical diagrams, typed ports, properties, search, editing and undo/redo.
- Live values, watched signals, quality and trends.
- Local projects and SAX/SAB import/export with exact kit matching.
- Explicit device-change review, backups, operation journal and readback.
- Local simulator and kit workshop using a compatible SedonaPro SDK.
- English reference texts, optional language files, themes and canvas settings.
- Optional screens, equipment libraries, live bindings, operator commands,
  alarm acknowledgement/journal and stored history.

Standalone station operation and users/roles remain future work. This source
preview is not a complete production SCADA product. Linux and Windows editions
have independent features and release tracks.

## Build and check

Requirements: Linux, JDK 17, Python 3.12+ for provisioning, internet access for
pinned dependencies. Set JAVA_HOME and PATH to JDK 17.

```sh
git clone https://github.com/SedonaPro/sedona-studio-linux.git
cd sedona-studio-linux
python3 tools/fetch_linux_deps.py
python3 build.py
python3 test.py --ui-smoke
```

The fetcher verifies hashes for JavaFX, Monocle and portable Python. Headless GUI
checks use Monocle. Run the application with studio.sh using JDK 17. Optional
local SOX/simulator tests need a built matching SDK. Never point tests at hardware.
Use build.py --engineering for the edition without the SCADA module.

See [source notes](README_RU.md), [publication scope](PUBLICATION.md),
[Contributing](CONTRIBUTING.md) and
[Discussions](https://github.com/SedonaPro/.github/discussions).

## Licence

Our editor code uses Apache-2.0. Framework classes, Sedona JARs and dependencies
retain their licences: [LICENSE](LICENSE), [NOTICE](NOTICE.md).
