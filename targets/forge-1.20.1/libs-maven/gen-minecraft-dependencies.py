#!/usr/bin/env python3
"""Reproduce NeoForged's net.neoforged:minecraft-dependencies:<mc> Gradle module metadata.

Why: the ModDevGradle LegacyForge plugin injects that module into the
net.minecraftforge:forge component as the source of Minecraft's libraries, and it
is published only on maven.neoforged.net. Where that host is unreachable, the
module can be reproduced from the two things Mojang ships, which are reachable:

  * the client's version manifest  (version.json of the client, "libraries")
  * the server bundle's bundled jars (META-INF/libraries/**)

The rule below was derived from, and verified byte-for-byte against, the
authoritative 1.19.2 module Gradle had already cached.
"""
import argparse
import json
import os
import re
import sys
import zipfile

VARIANT_ORDER = [
    "clientCompileDependencies",
    "clientRuntimeDependencies",
    "serverCompileDependencies",
    "serverRuntimeDependencies",
    "clientWindowsNatives",
    "clientOsxNatives",
    "clientLinuxNatives",
]


def parts(name):
    p = name.split(":")
    return p[0], p[1], p[2], (p[3] if len(p) > 3 else "")


def dependency(group, module, version, classifier, artifact):
    dep = {
        "excludes": [{"group": "*", "module": "*"}],
        "group": group,
        "module": module,
        "version": {"strictly": version},
    }
    if classifier:
        dep["thirdPartyCompatibility"] = {
            "artifactSelector": {
                "classifier": classifier,
                "extension": "jar",
                "type": "jar",
                "name": artifact,
            }
        }
    return dep


def server_libraries(server_jar):
    libs = []
    with zipfile.ZipFile(server_jar) as z:
        for entry in z.namelist():
            m = re.match(r"META-INF/libraries/(.+)\.jar$", entry)
            if not m:
                continue
            seg = m.group(1).split("/")
            version, filename, artifact = seg[-2], seg[-1], seg[-3]
            group = ".".join(seg[:-3])
            classifier = ""
            prefix = f"{artifact}-{version}"
            if filename.startswith(prefix + "-"):
                classifier = filename[len(prefix) + 1 :]
            libs.append((group, artifact, version, classifier))
    return libs


def generate(mc, client_manifest, server_jar):
    client = json.load(open(client_manifest, encoding="utf-8"))
    jvm = client.get("javaVersion", {}).get("majorVersion", 17)

    # Client side, compile classpath: every library the client manifest names without a
    # classifier. That includes the non-natives libraries gated on one operating system
    # (a macOS bridge library is one), because the compile classpath is OS-independent.
    client_compile_libs = [parts(l["name"]) for l in client["libraries"] if len(l["name"].split(":")) == 3]

    # Client side, runtime classpath: only the libraries with no operating-system gate at
    # all, because the OS-specific ones come from the selected natives variant instead —
    # including the non-natives macOS library, which lives in the macOS natives variant.
    client_runtime_libs = [parts(l["name"]) for l in client["libraries"] if not l.get("rules")]

    # Client natives: every library the client manifest gates on one operating
    # system, kept with its classifier, grouped by that operating system.
    natives = {}
    for lib in client["libraries"]:
        rules = lib.get("rules")
        if not rules:
            continue
        os_name = rules[0].get("os", {}).get("name")
        if os_name is None:
            continue
        natives.setdefault(os_name, []).append(parts(lib["name"]))

    # Server side: exactly what the server bundle ships in META-INF/libraries.
    server_libs = server_libraries(server_jar)

    def deps(items):
        return [dependency(g, a, v, c, a) for (g, a, v, c) in items]

    def variant(name, usage, distribution, os_name, items, natives_capability=False, capability_request=False):
        attributes = {
            "org.gradle.usage": usage,
            "org.gradle.jvm.version": jvm,
            "net.neoforged.distribution": distribution,
        }
        if os_name:
            attributes["net.neoforged.operatingsystem"] = os_name
        dependencies = deps(items) if items is not None else []
        if capability_request:
            # This is what makes Gradle select one of the natives variants when the client
            # runtime classpath is resolved: a request for the natives capability, which only
            # those variants declare.
            dependencies.append(
                {
                    "endorseStrictVersions": True,
                    "group": "net.neoforged",
                    "module": "minecraft-dependencies",
                    "requestedCapabilities": [
                        {"group": "net.neoforged", "name": "minecraft-dependencies-natives"}
                    ],
                    "version": {"strictly": mc},
                }
            )
        return {
            "name": name,
            "attributes": attributes,
            "dependencies": dependencies,
            "capabilities": [
                {
                    "group": "net.neoforged",
                    "name": "minecraft-dependencies-natives" if natives_capability else "minecraft-dependencies",
                    "version": mc,
                }
            ],
        }

    variants = [
        variant("clientCompileDependencies", "java-api", "client", None, client_compile_libs),
        variant(
            "clientRuntimeDependencies",
            "java-runtime",
            "client",
            None,
            client_runtime_libs,
            capability_request=True,
        ),
        variant("serverCompileDependencies", "java-api", "server", None, server_libs),
        variant("serverRuntimeDependencies", "java-runtime", "server", None, server_libs),
        variant(
            "clientWindowsNatives", "java-runtime", "client", "windows", natives.get("windows", []), True
        ),
        variant("clientOsxNatives", "java-runtime", "client", "osx", natives.get("osx", []), True),
        variant("clientLinuxNatives", "java-runtime", "client", "linux", natives.get("linux", []), True),
    ]
    assert [v["name"] for v in variants] == VARIANT_ORDER
    return {
        "formatVersion": "1.1",
        "component": {"group": "net.neoforged", "module": "minecraft-dependencies", "version": mc},
        "variants": variants,
    }


def normalized(module):
    """The module with dependency order ignored, so a set comparison can be exact."""
    out = {"component": module["component"], "formatVersion": module["formatVersion"], "variants": {}}
    for v in module["variants"]:
        out["variants"][v["name"]] = {
            "attributes": v["attributes"],
            "capabilities": v.get("capabilities"),
            "dependencies": sorted(json.dumps(d, sort_keys=True) for d in v["dependencies"]),
        }
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mc", required=True)
    ap.add_argument("--client-manifest", required=True)
    ap.add_argument("--server-jar", required=True)
    ap.add_argument("--out", help="where to write the generated .module")
    ap.add_argument("--verify-against", help="an authoritative .module to compare with")
    args = ap.parse_args()

    generated = generate(args.mc, args.client_manifest, args.server_jar)

    if args.verify_against:
        expected = json.load(open(args.verify_against, encoding="utf-8"))
        if normalized(generated) == normalized(expected):
            print(f"verify {args.mc}: identical to {args.verify_against} (order-insensitive)")
        else:
            g, e = normalized(generated), normalized(expected)
            print(f"verify {args.mc}: MISMATCH")
            for name in VARIANT_ORDER:
                gv, ev = g["variants"].get(name), e["variants"].get(name)
                if gv is None or ev is None:
                    print(f"  {name}: missing on one side")
                    continue
                for field in ("attributes", "capabilities"):
                    if gv[field] != ev[field]:
                        print(f"  {name}.{field}: {gv[field]} != {ev[field]}")
                only_gen = [d for d in gv["dependencies"] if d not in ev["dependencies"]]
                only_exp = [d for d in ev["dependencies"] if d not in gv["dependencies"]]
                if only_gen or only_exp:
                    print(f"  {name}: generated-only={len(only_gen)} expected-only={len(only_exp)}")
                    for d in only_gen[:4]:
                        print("    + ", d)
                    for d in only_exp[:4]:
                        print("    - ", d)
            return 1

    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8", newline="\n") as f:
            json.dump(generated, f, indent=4)
            f.write("\n")
        print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
