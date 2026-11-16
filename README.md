# Way More Items (WMI)

*[Lire en français](README.fr.md)*

A recipe browser and in-game toolbox for Minecraft, in the spirit of NEI and JEI. WMI works on its own or next to any other mod, and it can share the screen with JEI.

What it does:

- an item catalogue with search, and a recipe viewer in a side panel;
- a crafting tree that breaks a recipe down to raw materials;
- notes and goals, for the current inventory or for the whole game;
- inventory sorting and locked slots;
- creative-style shortcuts: time, weather, heal and feed, clearing dropped items, an in-game clock (anything that needs the server is hidden when the server doesn't run WMI);
- colour themes, an interface size of its own, and a layout file (`config/wmi-layout.json`) reloaded live;
- English, French, German, Spanish, Italian and Brazilian Portuguese;
- a small public API (`waymoreitems.api`, separate `wmi-api` jar) so other mods can add their recipes, layouts and info pages.

> WMI is in early development (0.1.0) and NeoForge 26.3 is still in beta, so expect rough edges.

## Versions

Each Minecraft version has its own branch, built against the latest NeoForge release for that version.

| Branch | Minecraft | NeoForge | Status |
|---|---|---|---|
| `26.4` | 26.4 | not out yet | waiting for NeoForge |
| `26.3` | 26.3 | 26.3.0.103 (beta) | in development |
| `26.2` | 26.2 | 26.2.0.93 | port planned |
| `26.1` | 26.1 | 26.1.2.126 | port planned |

Until their port is done, `26.1`, `26.2` and `26.4` hold the 26.3 code.

Mod id: `wmi`. Java 25. NeoForge only for now.

## Building

You need Java 25 and Gradle 9.8 or later. From the repository root:

```
gradle verifierCloisonnement wmiTests   # core tests
gradle runClient                        # development client
gradle build                            # mod jar in build/libs
```

The development client runs in `run/`; set the `wmiGameDir` Gradle property to use another folder.

## For mod developers

WMI reads vanilla recipe displays on its own. A mod can go further through the `wmi-api` jar (`gradle apiJar`, MIT, interfaces and plain types only, version `1.0.0`). Compile against it with `compileOnly`, never bundle it, and declare WMI as an optional dependency: without WMI, nothing of it is loaded.

**Layout without Java**: drop `assets/<modid>/wmi_layouts/<recipe_type>.json` in your resources (the recipe type defaults to `<modid>:<path>`):

```json
{
  "format": "wmi_recipe_layout", "version": 1,
  "title": "container.examplemod.grinder",
  "size": [150, 64],
  "workstations": ["examplemod:grinder"],
  "slots": [
    {"role": "input", "index": 0, "x": 28, "y": 12},
    {"role": "output", "index": 0, "x": 104, "y": 26, "large": true}
  ],
  "arrows": [{"x": 62, "y": 26}],
  "gauges": [{"kind": "energy", "x": 4, "y": 6, "width": 6, "height": 50}]
}
```

**Extension in Java**: one class, found through its annotation.

```java
@WmiPlugin(api = WmiApi.VERSION)
public final class ExampleWmiExtension implements WmiExtension {
    @Override
    public String id() {
        return "examplemod";
    }

    @Override
    public void register(WmiRegistration reg) {
        reg.addRecipes(() -> List.of(WmiRecipe.builder("examplemod:grinding/iron", "examplemod:grinding")
                .input(WmiIngredient.item("minecraft:raw_iron", 1))
                .input(WmiIngredient.fluid("minecraft:water", 250))
                .output("examplemod:iron_dust", 2)
                .duration(200).energy(8)
                .build()));
        reg.addInfo("examplemod:grinder", List.of("examplemod.info.grinder"));
        reg.excludeFromCraftTree("examplemod:recycling");
    }
}
```

Ingredients are keys: an item by its bare id, any other kind as `<kind>@<id>` (`fluid@minecraft:water`, amounts in mB). The API follows strict versioning: an extension built for `X.Y.Z` loads on any `X.y` with `y >= Y`, otherwise it is skipped with a warning in the log.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). To report a problem or share an idea, open an issue.

## License

The code is under the [MIT licence](LICENSE). The bundled Roboto font comes from Google Fonts and is under the SIL Open Font License 1.1 (text in `src/main/resources/assets/wmi/font/OFL.txt`). The toolbar icons were made for WMI and are under the MIT licence like the code.
