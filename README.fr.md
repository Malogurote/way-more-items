# Way More Items (WMI)

*[Read in English](README.md)*

Un navigateur de recettes et des outils de jeu pour Minecraft, dans l'esprit de NEI et de JEI. WMI fonctionne seul ou avec n'importe quels autres mods, et peut partager l'écran avec JEI.

Ce qu'il propose :

- un catalogue d'objets avec recherche et une visionneuse de recettes dans un panneau latéral ;
- un arbre de fabrication qui décompose une recette jusqu'aux matières premières ;
- des notes et des objectifs, pour l'inventaire en cours ou pour toute la partie ;
- le tri de l'inventaire et des cases verrouillées ;
- des raccourcis de type créatif : heure, météo, soin et nourriture, nettoyage des objets au sol, horloge en jeu (ce qui passe par le serveur est masqué si le serveur n'a pas WMI) ;
- des thèmes de couleurs, une taille d'interface propre et un fichier de mise en page (`config/wmi-layout.json`) rechargé à chaud ;
- l'anglais, le français, l'allemand, l'espagnol, l'italien et le portugais du Brésil ;
- une petite API publique (`waymoreitems.api`) pour que d'autres mods décrivent leurs mises en page de recettes.

> WMI en est à ses débuts (0.1.0) et NeoForge 26.3 est encore en bêta : il reste des aspérités.

## Versions

Chaque version de Minecraft a sa propre branche, compilée avec la dernière version de NeoForge publiée pour cette version.

| Branche | Minecraft | NeoForge | État |
|---|---|---|---|
| `26.4` | 26.4 | pas encore sorti | en attente de NeoForge |
| `26.3` | 26.3 | 26.3.0.103 (bêta) | en développement |
| `26.2` | 26.2 | 26.2.0.93 | portage prévu |
| `26.1` | 26.1 | 26.1.2.126 | portage prévu |

Tant que leur portage n'est pas fait, `26.1`, `26.2` et `26.4` contiennent le code de la 26.3.

Identifiant du mod : `wmi`. Java 25. NeoForge uniquement pour l'instant.

## Compilation

Il faut Java 25 et Gradle 9.8 ou plus récent. Depuis la racine du dépôt :

```
gradle verifierCloisonnement wmiTests   # tests du noyau
gradle runClient                        # client de développement
gradle build                            # jar du mod dans build/libs
```

Le client de développement tourne dans `run/` ; la propriété Gradle `wmiGameDir` permet d'utiliser un autre dossier.

## Contribuer

Voir [CONTRIBUTING.md](CONTRIBUTING.md) (en anglais). Pour signaler un problème ou proposer une idée, ouvrez un ticket.

## Licence

Le code est sous [licence MIT](LICENSE). La police Roboto embarquée vient de Google Fonts et est sous SIL Open Font License 1.1 (texte dans `src/main/resources/assets/wmi/font/OFL.txt`). Les icônes de la barre d'outils ont été faites pour WMI et sont sous licence MIT, comme le code.
