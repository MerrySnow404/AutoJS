# libs-maven — local stand-ins for artifacts this machine cannot fetch

This directory is **not** a mod dependency and nothing in it is shipped. It exists
so this target can be built on a machine that cannot reach `maven.neoforged.net`,
and it is only ever consulted when the build is given `-Pautojs.localToolchain`.
Without that flag the build resolves everything from the real repositories and
this directory is ignored. (The `*.jar` files here are also outside git's tracked
set, by the repository-wide `*.jar` rule; only the descriptors are committed.)

## What is in it, and why it is a copy rather than a redirect

### `maven/modrinth/` — the KubeJS 1802 artifacts, which Modrinth cannot serve

`kubejs` (file id `MngQb49Q`, mod version 1802.5.5-build.569) and `rhino`
(file id `N59eFKIq`, mod version 1802.2.1-build.255). Both are unmodified
published files copied out of this machine's Gradle cache
(`~/.gradle/caches/modules-2/files-2.1/maven.modrinth/...`), together with the
poms the cache carries.

They are here because Modrinth maven does not serve these two file ids: the
project is addressed by file id and `api.modrinth.com/maven/.../kubejs-MngQb49Q.jar`
is what a normal build would ask for. Since the files are already in the cache and
the cache cannot be told to answer for a repository it was not fetched from, the
build either reads them from here or not at all.

`rhino-N59eFKIq.jar` is both the library AutoJS compiles against and the mod that
satisfies mods.toml's mandatory `rhino` dependency — see the target's
`gradle.properties`.

### `dev/architectury/` — KubeJS's mandatory runtime mod

`architectury-forge` 4.11.89, downloaded from `https://maven.architectury.dev` (the
4.x line is the 1.18.2 one). KubeJS 1802's own `mods.toml` requires architectury
`[4.7.78,)`; no AutoJS source names it, but a game with KubeJS installed needs it
present. It is compiled/published as `dev` (development) mappings — architectury
links against Forge's maven at compile time and its artifact carries no Minecraft
calls that a production loader rejects.

Its `pom` declares no dependencies at all (the real dependency data is in the
`module` file, which is here too, because a normal build prefers it).

### Not here: MixinExtras

Unlike the 1902 and 2001 targets, KubeJS 1802 does not need it: no class in
`kubejs-MngQb49Q.jar` and neither of its mixin configurations references
`com.llamalad7`, and its `mods.toml` lists only forge, rhino and architectury as
mandatory. Nothing is kept here for it.

### Not here: `net.neoforged:minecraft-dependencies`

Unlike the 1.20.1 target, this one needs no generated stand-in for it: the
authoritative `minecraft-dependencies-1.18.2.module` is already in this machine's
Gradle cache (which is also where the rule that produced the 1.20.1 file was
derived from).

## Regenerating

```sh
C=~/.gradle/caches/modules-2/files-2.1/maven.modrinth
cp $C/kubejs/MngQb49Q/*/kubejs-MngQb49Q.{jar,pom}   maven/modrinth/kubejs/MngQb49Q/
cp $C/rhino/N59eFKIq/*/rhino-N59eFKIq.{jar,pom}     maven/modrinth/rhino/N59eFKIq/
curl -O https://maven.architectury.dev/dev/architectury/architectury-forge/4.11.89/architectury-forge-4.11.89.{jar,pom,module}
```
