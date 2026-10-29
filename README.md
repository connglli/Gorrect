# Gorrect

Gorrect validates garbage collectors through **Object Graph Verification**. It generates Java programs that manipulate object graphs and checks their observed objects, references, and data against an independent oracle.

Build with JDK 21, CMake 3.24 or newer, and a C++17 compiler. Compatibility checks require a discoverable JDK 17; set `JAVA_HOME_17_X64` if it is outside the standard installation locations.

```bash
./gradlew build
```

The build checks native loading on JDK 17 and 21.

[Design](./docs/design.md) | [Implementation](./docs/implementation.md) | [AGENTS.md](./AGENTS.md) | [docs/AGENTS.md](./docs/AGENTS.md)
