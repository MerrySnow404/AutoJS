# AutoJS

Once a mod's content is built, you end up doing most of the acceptance work yourself, then testing each feature one by one.

Have you thought about letting an AI do that testing, only to find that computer use is too brittle, that data packs only cover the server side, that JUnit never covers enough, and that script-based testing is too slow to iterate on?

This mod provides an interactive programming environment in the spirit of JShell: it lets you **send scripts straight into a running game over a local port and have them executed immediately**, and those scripts can **call nearly any Java class in the game process**.

An AI can use it to read any data from the game and simulate any behaviour in it, almost without limit — including, but not limited to, **GUI interaction tests, mob behaviour, terrain generation, multiplayer simulation, and full playthroughs**.

## What it is made of

KubeJS needs little introduction: it ships a large set of events and utility classes, and uses Rhino to evaluate scripts at three points — client reload, server reload, and startup.

In fact, back in mid-2025 some modpack developers were already debugging by listening for chat messages and executing the JavaScript typed into them. That trick is the ancestor of this tool.

This tool depends on Rhino; KubeJS is optional. Every type filter, guard and path restriction is disabled on this debug channel: dangerous tools such as `HttpURLConnection` or `Files` can be called, and because the channel is a local port, an AI can call them from outside the game.

In short: the AI generates a piece of JavaScript, sends it to the tool's port, and Rhino executes it immediately, turning it into Java calls.

KubeJS pre-registers a great many bindings — `Item`, `Text`, `NBT`, `Registry`, `Ingredient`, `Utils`, `Platform`, `Fluid`, `Client`, `GLFWInput` — and a large set of common events such as `ServerEvents.tick`, `PlayerEvents`, `BlockEvents`, `ItemEvents`, `EntityEvents`, `LevelEvents`, `NetworkEvents`, `ClientEvents`, `StartupEvents` and `KeyBindEvents`. When what you need is hot execution and hot reloading, those are very convenient; if a KubeJS build exists for the game version you are on, install it alongside.

On top of that, the tool pre-registers several dozen debugging helpers — read the current game state, inspect the terrain around you, and so on. `AutoJS.help()` lists everything it registers.

Official Maven:
```
repositories {
    maven {
        url "https://maven.sighs.cc/repository/maven-public/"
    }
}
dependencies {
    implementation 'com.sighs:AutoJS-forge-1.20.1:1.0.0'
}
```

## Notes

- **Modpacks must not include this mod.**
- For now it covers the game versions KubeJS supports; Rhino reaches many more, and those will follow. Ask if you need one urgently.
- Some developers maintain their own hardened Rhino forks, or keep a close eye on Rhino releases, so this tool does not bundle Rhino.
- One downside of this much freedom is that results vary when an AI improvises. Once a debugging flow works, have the AI turn it into a stable test script: that makes repeat runs easy, and it can also feed GitHub workflow tests.
- The jar already contains the SKILL, so an AI can read it straight out of the jar. It is still worth adapting the SKILL to your situation. Making an AI play the game like an ordinary player is possible too — try `SKILL-GAMEPLAY` from the repository.
- Contributions of SKILLs for other scenarios are welcome.

![cover](https://resource-api.xyeidc.com//client/members/pics/82ef1547)
