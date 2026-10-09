# AutoArmorEnchant (Fabric 1.21.11, client)

Open an anvil with both input slots empty. The mod moves each armor piece in your
inventory into the anvil, adds every enchanted book that fits (no conflicts, actually
an upgrade), takes the result, and repeats until nothing more applies or XP runs out.
Needs no buttons or settings. If you place items yourself first, it stays out of the way.

Build: push to GitHub, run the "build" workflow, download the jar from Actions artifacts.
Or locally: JDK 21 + Gradle 8.14+, `gradle build`, jar in build/libs/ (not the -sources one).
Requires Fabric API for 1.21.11. Not compiled or tested yet.
