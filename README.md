# Rounds

Valorant-style minihra pro Paper: 2 tymy po max 5, jeden zivot na kolo, hra do 5 bodu.

## Build
Po pushi na GitHub se jar zbuilduje sam (zalozka **Actions** -> posledni beh -> **Artifacts** -> `Rounds`).
Pro stazeni z **Releases** pushni tag, napr. `git tag v1.0.0 && git push origin v1.0.0`.

Lokalne: `gradle build` (JDK 25) -> `build/libs/Rounds.jar`.

## Verze
Uprav `gradle.properties` (`paperVersion`, `apiVersion`, `javaVersion`).

## Prikazy (`/rounds`, alias `/mm`)
`join red|blue`, `leave`, `start`, `stop`, `setlobby`, `setspawn red|blue` (admin: `rounds.admin`)
