# Multiplayer World Dumper

A dumper for copying multiplayer worlds.

Java file only.

## Usage

1. Copy `WorldDumper.java` into your Fabric mod project.

2. Change the package declaration at the top of the file to match your project:

```java
package your.package.name;
```

3. Call the dumper while connected to the multiplayer world:

```java
WorldDumper.dump("WorldName");
```

4. The copied world will be saved in:

```text
.minecraft/saves/WorldName/
```

5. Open Minecraft's Singleplayer menu and the dumped world should appear there.

The dumper saves the currently loaded chunks around the player based on the client's render distance.

Move around the multiplayer world and run the dumper again to save additional areas. Existing chunks in the same world folder are preserved.
