# ChunkFreezer
Minecraft plugin that protects against crash machines.

> [!WARNING]
> **Only for Alpha version**
>
> You're watching a documentation and plugin API only for Alpha version (plugin APIs is not available for stable/beta release)

## Compile
```bash
git clone https://github.com/mishakorzik/ChunkFreezer
cd ChunkFreezer
mvn clean package
```

## API
ChunkFreezer exposes a small, read-only API other plugins can use to check whether a chunk is
frozen, whether a player is standing in one, and so on. It never lets another plugin freeze or
unfreeze a chunk itself - only observe it.

### Add as a dependency
Only the `ChunkFreezerAPI` interface is needed at compile time. Use `provided`/`compileOnly` so
it isn't bundled into your own jar - ChunkFreezer will already be on the server at runtime.

**Maven:**
```xml
<dependency>
    <groupId>com.heonezen</groupId>
    <artifactId>chunkfreezer</artifactId>
    <version>1.0.2</version>
    <scope>provided</scope>
</dependency>
```

### Getting an instance
```java
import com.heonezen.chunkfreezer.api.ChunkFreezerAPI;

ChunkFreezerAPI api = ChunkFreezerAPI.get(); // Bukkit.getServicesManager().load(...) shorthand
if (api == null) {
    // not installed, not enabled yet, or mid-reload - always null-check
    return;
}
```
The same instance stays valid across `/chunk reload`, so it's safe to grab once in your own
`onEnable()` and keep the reference.

### Methods

| Method                                                                 | Returns                                     | Notes                                                                                                                                                                                              |
| ---------------------------------------------------------------------- | ------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `isChunkFrozen(World, int chunkX, int chunkZ)`                         | `boolean`                                   | Chunk coordinates, not block coordinates                                                                                                                                                           |
| `isChunkFrozen(Chunk)`                                                 | `boolean`                                   | Overload                                                                                                                                                                                           |
| `isChunkFrozen(Location)`                                              | `boolean`                                   | Overload; `false` if the world is `null`                                                                                                                                                           |
| `isNearFrozenChunk(World, int chunkX, int chunkZ, int radiusInChunks)` | `boolean`                                   | Square radius, center chunk included, so radius `0` = `isChunkFrozen`                                                                                                                              |
| `isNearFrozenChunk(Location, int radiusInChunks)`                      | `boolean`                                   | Overload                                                                                                                                                                                           |
| `isPlayerInFrozenChunk(Player)`                                        | `boolean`                                   | Player (or their vehicle) standing in a frozen chunk right now. Only tracked while `barrier.watch-players.enabled` is `true` in config.yml (`false` by default) - otherwise always returns `false` |
| `getFreezeCause(World, int chunkX, int chunkZ)`                        | `FrozenChunkManager.FreezeCause`            | `ENTITY`, `REDSTONE`, or `null` if not frozen                                                                                                                                                      |
| `getFrozenChunks()`                                                    | `Collection<FrozenChunkManager.FrozenInfo>` | Snapshot of every frozen chunk, across all worlds, right now                                                                                                                                       |
| `isBarrierEnabled()`                                                   | `boolean`                                   | Whether the movement/item/projectile barrier is active (`barrier.enabled` in config.yml)                                                                                                           |

`FrozenInfo` is a record: `(UUID worldId, int cx, int cz, FreezeCause cause, int entityCount, long frozenSinceMs)`.
Resolve `worldId` with `Bukkit.getWorld(UUID)`.

### Examples

Skip a farm payout while its chunk is frozen:
```java
ChunkFreezerAPI api = ChunkFreezerAPI.get();
if (api != null && api.isChunkFrozen(collector.getLocation())) {
    return;
}
```

Warn a player who's about to walk into a frozen chunk:
```java
@EventHandler
public void onMove(PlayerMoveEvent e) {
    ChunkFreezerAPI api = ChunkFreezerAPI.get();
    if (api != null && api.isNearFrozenChunk(e.getTo(), 1)) {
        e.getPlayer().sendActionBar(Component.text("A frozen chunk is nearby!"));
    }
}
```

List every frozen chunk:
```java
ChunkFreezerAPI api = ChunkFreezerAPI.get();
if (api == null) return;
for (FrozenChunkManager.FrozenInfo info : api.getFrozenChunks()) {
    World world = Bukkit.getWorld(info.worldId());
    if (world == null) continue;
    getLogger().info(world.getName() + " " + info.cx() + "," + info.cz() + " cause=" + info.cause());
}
```

All methods are safe to call from any thread on Folia - no need to hop to a specific region first.
