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
- a small public API (`waymoreitems.api`) so other mods can describe their recipe layouts.

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

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). To report a problem or share an idea, open an issue.

## License

The code is under the [MIT licence](LICENSE). The bundled Roboto font comes from Google Fonts and is under the SIL Open Font License 1.1 (text in `src/main/resources/assets/wmi/font/OFL.txt`). The toolbar icons were made for WMI and are under the MIT licence like the code.
