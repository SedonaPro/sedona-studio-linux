# Source preview validation

15 November 2026.

Linux/JDK 17 build passed; core tests and headless UI smoke passed for both full and engineering editions. All 951 used interface keys are present in English.

The Windows source-only build uses --without-c-toolchain; full compiler provisioning is separate. Test changes fix JavaFX module discovery when using an installed JDK rather than a bundled runtime.

No physical controller was written to. Historical release reports are retained as historical evidence, not new acceptance. This publication does not supply a signed installer.
