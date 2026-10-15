# libs-maven — local stand-ins for artifacts this machine cannot fetch

This directory is **not** a mod dependency and nothing in it is shipped. It exists
so this target can be built on a machine whose Gradle cannot read
`maven.latvian.dev`, and it is only ever consulted when the build is given
`-Pautojs.localToolchain`. Without that flag the build resolves everything from
the real repositories and this directory is ignored. (The `*.jar` files here are
also outside git's tracked set, by the repository-wide `*.jar` rule; only the
descriptors are committed.)

## Why a copy rather than a redirect

`maven.latvian.dev` answers `curl` from this machine — that is how every file here
was fetched — but Gradle's own HTTP client is reset by the host, so a normal
resolution against it fails instead of falling through. Its content therefore has
to be on disk, under the layout a Maven repository uses, and the `LocalToolchain`
repository in `build.gradle` points Gradle at this directory for the two groups
that live there (`dev.latvian.mods` and `dev.latvian.apps`) whenever the flag is
set. Every file is the unmodified published artifact, together with the `.pom`
and `.module` descriptors the same release published.

## What is in it, and why

### `dev/latvian/mods/kubejs-neoforge` 26.1.2-8.0.6

KubeJS 8 for Minecraft 26.1 — the API AutoJS compiles against and the mod a dev run
loads. It is pinned by `kubejs_version` in this target's `gradle.properties`, and
its own `META-INF/neoforge.mods.toml` requires `neoforge [26.1.2.84,)`, which is
exactly `neoforge_261_version`.

### `dev/latvian/mods/rhino` 2101.2.8-build.91

The script engine AutoJS embeds so that KubeJS can be optional, on the **2101**
Rhino line even though the Minecraft version is 26.1 — the line number did not
follow the game. Two things about it are worth stating, because they differ from
what the 1902 and 2001 targets declare:

- **This artifact is the mod as well as the library.** Its jar carries
  `META-INF/neoforge.mods.toml` with `modId = "rhino"` (and the older
  `META-INF/mods.toml` beside it, with `modLoader = "lowcodefml"`), so one
  declaration serves both `neoforge.mods.toml`'s mandatory `rhino` dependency and
  the classpath the standalone kernel compiles against. That is the 1802 situation
  rather than the 1902/2001 one, which split the two across `rhino` and
  `rhino-forge`.
- It is the version KubeJS 26.1 requires: `kubejs-neoforge`'s `neoforge.mods.toml`
  declares `rhino [2101.2.8-build.91,)`, and its Gradle metadata asks for exactly
  this version. Its own `pom` and `.module` declare no dependencies at all.

### `dev/latvian/mods/better-advanced-tooltips` 2601.1.0-build.9

KubeJS's optional client-side tooltip mod, which no AutoJS source names. It is here
because KubeJS's Gradle metadata declares it as a **runtime** dependency of
`kubejs-neoforge`, and Gradle resolves that whether or not the mod is present in
game. `2601.1.0-build.9` and not a newer build, because that is the version KubeJS's
own metadata names as preferred: a range like `[2601.1.0-build.9,)` resolves to the
preferred version, so anything else in this directory would be dead weight.

### `dev/latvian/apps/tiny-java-server` 1.0.0-build.45

KubeJS's HTTP server library, again declared as a runtime dependency by KubeJS's
Gradle metadata. This release also happens to be bundled inside
`kubejs-neoforge`'s `META-INF/jarjar/`, which is what the game actually loads; the
copy here is the same published file, present so that Gradle's resolution of the
declared dependency succeeds offline.

### Not here: `com.github.rtyley:animated-gif-lib-for-java`

KubeJS's third runtime dependency. It is published on JitPack, which **is**
reachable from this machine (`https://jitpack.io/...` answers `curl`), so the
target declares that repository with the real URL and nothing is kept here.

### Not here: MixinExtras

As on 1802, KubeJS 26.1 does not need it: `kubejs-neoforge-26.1.2-8.0.6.jar`
references no `com.llamalad7` class and its `neoforge.mods.toml` lists only
`neoforge`, `rhino` and the two optional recipe-viewer mods.

## Regenerating

```sh
B=https://maven.latvian.dev/releases
dl() { # group-path artifact version
  mkdir -p "$1/$2/$3" && cd "$1/$2/$3"
  for e in jar pom module; do curl -O "$B/$1/$2/$3/$2-$3.$e"; done
  cd - >/dev/null
}
dl dev/latvian/mods          kubejs-neoforge         26.1.2-8.0.6
dl dev/latvian/mods          rhino                   2101.2.8-build.91
dl dev/latvian/mods          better-advanced-tooltips 2601.1.0-build.9
dl dev/latvian/apps          tiny-java-server        1.0.0-build.45
```
