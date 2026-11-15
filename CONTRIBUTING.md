# Helping with WMI

Thanks for wanting to help! Here is what makes a change easy for me to review and merge.

## Before you start

For anything bigger than a small fix, open an issue first so we can agree on the idea. It avoids work that ends up unused.

## Which branch

Each Minecraft version has its own branch (`26.3`, `26.2`...). Work on the branch of the version you play. If a fix is needed on several versions, start from the oldest one; I'll bring it to the newer ones.

## Sending a change

- Keep each change to one topic: a fix, a feature or a cleanup, not all three at once.
- Say what was wrong, what you changed and how you checked it.
- Leave build output, logs, game folders, IDE settings and jars out of the commit.

## How the code is split

- `waymoreitems.core` and `waymoreitems.api` are plain Java, with no `net.minecraft`, `net.neoforged` or `com.mojang`. The build stops if one slips in.
- Everything that talks to NeoForge is in `waymoreitems.client`; the optional JEI support is in `waymoreitems.compat.jei`.
- WMI doesn't depend on any other mod. JEI support must stay optional.
- NeoForge 26.3 is still in beta, so double-check the APIs you use against the current NeoForge sources.

## Texts and languages

- Text shown to players never goes straight into the code: use `WmiText` with a `wmi.<zone>.<name>` key.
- A new key goes into all six language files (`en_us`, `fr_fr`, `de_de`, `es_es`, `it_it`, `pt_br`). If you can't translate it, copy the English text; `en_us` is used when a key is missing. `LangTests` checks that the files match.

## Checking your work

- `gradle verifierCloisonnement wmiTests` must pass. New core logic deserves a check in `WmiTests`.
- For interface changes, have a look in the development client (`gradle runClient`).

## Images, fonts and sounds

Only add assets you made yourself or that come with a compatible licence, and tell me where they come from. Nothing taken from Minecraft or from other mods.

## Licence

Your contribution is published under the MIT licence of the project.
