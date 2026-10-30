# Vast Continents

Un mod Fabric pour **Minecraft 26.3** qui ajoute un type de monde avec de vrais continents, de vrais océans et des montagnes géantes, dans un monde qui monte jusqu'à **y=1007**.

Le monde vanilla ressemble à une seule grande terre trouée de mers : sur 30 000 blocs, on ne s'éloigne jamais vraiment de l'eau, et on ne la traverse jamais longtemps. **Vastes continents** change la forme du monde :
- **Des continents** de plusieurs dizaines de milliers de blocs, entourés d'un océan d'un seul tenant.
- **Des chaînes de montagnes géantes** au cœur des continents, avec des aiguilles de pierre à près de y=900.
- **Des océans à traverser** : environ 7 500 blocs dans leur partie la plus large, contre 2 200 en vanilla (moyenne de 30 seeds).
- **Des biomes de taille normale** : contrairement à « Grands biomes », températures et végétation gardent leur échelle, donc un continent traverse plusieurs climats.
- **Des côtes à la vanilla** avec leurs baies et leurs archipels, et des îles champignons au large.
- **Un spawn sur la terre ferme** : un continent est garanti autour de l'origine du monde.

Le type de monde normal n'est pas modifié. Le mod est presque entièrement fait de données de génération, avec un seul mixin pour éviter l'avertissement « paramètres expérimentaux ».

Au-dessus de y=320, les montagnes sont de la pierre pure : les minerais vanilla ne montent pas si haut. La génération coûte aussi plus cher, parce que chaque colonne de chunk fait 1 072 blocs de haut au lieu de 384. Pour voir les montagnes de loin, monte la distance de rendu.

## Installer
1. Fabric Loader 0.20.1 ou plus récent, et Fabric API 0.165.0+26.3.
2. Copie `build/libs/vastcontinents-1.0.0.jar` dans `.minecraft/mods`.
3. Crée un **nouveau monde** : Monde > Type de monde > **Vastes continents**.

Sur un serveur, mets `level-type=vastcontinents:continents` dans `server.properties` avant de générer le monde.

Dans un monde existant, seuls les nouveaux chunks changeraient, avec des coupures nettes à la frontière : le mod est fait pour les nouveaux mondes.

## Développer
Le projet utilise un JDK 25 portable, configuré dans `gradle.properties`.

```bash
python tools/gen_worldgen.py     # régénère tous les JSON du type de monde
./gradlew exportMaps             # cartes et statistiques dans build/maps
./gradlew runAutotest -Pseed=2   # test en jeu, captures dans run/screenshots
./gradlew build                  # compile le jar
```

Les données (`src/main/resources/data`) sont **générées** : modifie `tools/gen_worldgen.py`, pas les fichiers générés. Tous les réglages sont en haut du script.

### Comment ça marche
Comme « Grands biomes » en vanilla, le type de monde duplique les fonctions de densité qui dépendent de ce qui change. Pour les continents :

1. Un **masque** à très grande échelle (`continent_mask`, 16 000 blocs) dit où sont les terres et les mers.
2. Il pousse la `continentalness` **vanilla** vers le haut ou vers le bas. Les détails du vanilla restent donc intacts, et les côtes gardent leur échelle et leur forme.
3. Le fond des océans est plafonné à -1.02 : plus bas, le jeu lit des champs de champignons. Une petite fonction `mushroom_spots` remet quelques îles champignons au large.
4. Un bonus autour de l'origine garantit de la terre, parce que le jeu cherche le spawn à moins de 2 048 blocs.

Pour les montagnes :

1. `mountains` trace des crêtes là où un bruit passe par zéro, ce qui dessine de longues chaînes, seulement à l'intérieur des terres et loin du spawn.
2. Cette valeur s'ajoute à `offset`, qui donne l'altitude du terrain, et au relief dentelé de `jaggedness`.
3. `erosion` est abaissée sur les chaînes pour obtenir les biomes de sommets plutôt qu'une forêt à y=800.
4. `biome_depth` force les biomes de surface en altitude : sur des pentes aussi raides, le sol passerait sinon pour une grotte.
5. Les limites de hauteur vanilla sont déplacées vers le nouveau sommet du monde, et le type de dimension `tall_overworld` monte à 1 072 blocs de haut. Le plafond est à y=1008, car cette hauteur doit être un multiple de 16.

`exportMaps` n'a pas besoin de démarrer de serveur : il charge les vrais JSON et compare le vanilla, « Grands biomes » et ce mod sur les mêmes seeds. `src/maps` et `src/autotest` sont des outils de développement et ne sont pas dans le jar.
