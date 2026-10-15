# libs-maven — local stand-ins for artifacts this machine cannot fetch

This directory is **not** a mod dependency and nothing in it is shipped. It exists
so this target can be built on a machine that cannot reach `maven.neoforged.net`,
and it is only ever consulted when the build is given `-Pautojs.localToolchain`.
Without that flag the build resolves everything from the real repositories and
this directory is ignored.

## What is in it

### `dev/latvian/mods/` — the published files, fetched out of band

`rhino`, `rhino-forge` and `kubejs-forge` on the KubeJS 1902 generation, with the
poms and module metadata `maven.latvian.dev` serves. These are unmodified
published artifacts; they are here because Gradle's own HTTP client is reset by
that host from this machine (`Connection reset` on every GET) while `curl` reads
the same URLs without trouble. Nothing was generated or edited.

### `dev/architectury/` and `io/github/llamalad7/` — KubeJS's runtime mods

`architectury-forge` (6.5.82), which KubeJS 1902's own `mods.toml` requires at
runtime, and MixinExtras (`0.2.0-rc.2`), which its runtime classpath drags in.
Both are published artifacts copied from the 1.20.1 target's `libs-maven`, where
they were put for the same reason: the rest of this build runs offline, and
neither is in this machine's Gradle cache under those coordinates. Neither is
Minecraft-version-bound metadata — the jars are what FML loads at runtime, and
the Forge build of MixinExtras is the one 1902-era KubeJS expects.

### Not here: `net.neoforged:minecraft-dependencies`

Unlike the 1.20.1 target, this one needs no generated stand-in for it: the
authoritative `minecraft-dependencies-1.19.2.module` is already in this machine's
Gradle cache (which is also where the rule that produced the 1.20.1 file was
derived from).

## KubeJS's `rhino-forge` requirement

`kubejs-forge` 1902 declares `dev.latvian.mods:rhino-forge:1902.2.2-build.280`,
an older build than the `1902.2.3-build.284` this target resolves, and that older
build is deliberately **not** kept here. The target excludes `rhino-forge` from
the `kubejs-forge` dependency instead (see `build.gradle`): the mods.toml-carrying
half of Rhino is declared by the target directly at the newer version, and the
Rhino classes a compile needs come from the plain `rhino` library that is also
declared there. Pulling in the older jar as well would add nothing but a second
copy of the same classes to the graph.

## Regenerating

The KubeJS artifacts are a plain download of
`https://maven.latvian.dev/releases/dev/latvian/mods/<module>/<version>/<module>-<version>.{jar,pom,module}`.
