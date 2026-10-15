# libs-maven — local stand-ins for artifacts this machine cannot fetch

This directory is **not** a mod dependency and nothing in it is shipped. It exists
so this target can be built on a machine that cannot reach `maven.latvian.dev`
from Gradle, and it is only ever consulted when the build is given
`-Pautojs.localToolchain`. Without that flag the build resolves everything from
the real repositories and this directory is ignored.

It is the same arrangement `targets/forge-1.20.1/libs-maven` uses, for the same
reason: Gradle's own HTTP client is reset by that host from this machine
(`Connection reset` on every GET) while `curl` reads the same URLs without
trouble, so the published files are fetched out of band and put in a plain local
Maven layout. Nothing here is generated or edited — every jar, pom and `.module`
is byte-for-byte the published file.

## What is in it

`dev/latvian/mods/` — the KubeJS 2001 generation, **Fabric flavour**:

| module | version | why |
| --- | --- | --- |
| `kubejs-fabric` | `2001.6.5-build.26` | the KubeJS APIs AutoJS compiles against and drives |
| `rhino-fabric` | `2001.2.2-build.17` | the Rhino classes *and* the `rhino` mod identity (`fabric.mod.json`, `modId=rhino`) |
| `rhino` | `2001.2.2-build.17` | not used by the build: kept as the cross-loader reference the class sets were compared against |

`dev/architectury/architectury-fabric/9.1.12/` — KubeJS's own runtime dependency,
which AutoJS never names, added so `runClient`/`runServer` load KubeJS at all.

`io/github/llamalad7/mixinextras-fabric/0.2.0-rc.2/` — KubeJS's MixinExtras
companion. The build does **not** declare this one, and the dev run showed it is
not needed: the MixinExtras that loaded was `0.4.1`, the one Fabric Loader
0.16.14 itself provides, and KubeJS's nested `META-INF/jars` copy produced no
duplicate-mod error. It is kept as a local copy of the file in case a future run
needs it declared explicitly, and so the same file set can be dropped into an
`--offline` environment.

## `rhino` versus `rhino-fabric` — what was checked

On this generation the plain `dev.latvian.mods:rhino` artifact is KubeJS's own
architectury-common build output: it has **no `fabric.mod.json` and no
`mods.toml` at all**, so installing it satisfies no loaders' mandatory `rhino`
dependency. `rhino-fabric` carries the identical class set plus the three Fabric
platform classes (`RhinoModFabric`, `RhinoPropertiesImpl`, and the architectury
platform-methods shim) and the `fabric.mod.json` that declares `modId=rhino`
version `2001.2.2-build.17`.

Verified with `unzip -l` on both jars: 443 of `rhino-fabric`'s 446 classes are
present in plain `rhino`, and plain `rhino` has no class that `rhino-fabric`
lacks. Rhino itself never references Minecraft (`grep -a net/minecraft` over the
extracted tree finds nothing), so this target declares only the loader-suffixed
artifact — both for compiling the Rhino kernel and for the dev runtime.

## Regenerating

A plain download of
`https://maven.latvian.dev/releases/dev/latvian/mods/<module>/<version>/<module>-<version>.{jar,pom,module}`,
`https://maven.architectury.dev/dev/architectury/architectury-fabric/9.1.12/architectury-fabric-9.1.12.{jar,pom,module}`
and
`https://repo1.maven.org/maven2/io/github/llamalad7/mixinextras-fabric/0.2.0-rc.2/mixinextras-fabric-0.2.0-rc.2.{jar,pom}`.

The versions are the ones `gradle.properties` names, and they are the same pair
`targets/forge-1.20.1` uses, so the two targets exercise the same KubeJS and
Rhino generation.
