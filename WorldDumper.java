package ; // package pass here

import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WorldDumper {

    private static final int ANVIL_VERSION = 19133;

    private WorldDumper() {
    }

    public static int dump(String worldName) throws IOException {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        if (level == null || player == null) {
            return 0;
        }

        Path saveDir = client.gameDirectory.toPath().resolve("saves").resolve(sanitize(worldName));
        Path regionDir = saveDir.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("region");
        Files.createDirectories(regionDir);

        writeLevelDat(saveDir, worldName, player);
        writeWorldGenSettings(saveDir);

        Map<Long, List<LevelChunk>> byRegion = new LinkedHashMap<>();
        int radius = client.options.getEffectiveRenderDistance();
        ChunkPos center = player.chunkPosition();
        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                long region = ((long) (x >> 5) << 32) | ((z >> 5) & 0xffffffffL);
                byRegion.computeIfAbsent(region, key -> new ArrayList<>()).add(chunk);
            }
        }

        PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
        RegionStorageInfo info = new RegionStorageInfo(worldName, Level.OVERWORLD, "chunk");
        int written = 0;

        for (Map.Entry<Long, List<LevelChunk>> entry : byRegion.entrySet()) {
            int regionX = (int) (entry.getKey() >> 32);
            int regionZ = entry.getKey().intValue();
            Path file = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");

            try (RegionFile region = new RegionFile(info, file, regionDir, false)) {
                for (LevelChunk chunk : entry.getValue()) {
                    CompoundTag tag = serialize(level, factory, chunk);
                    try (DataOutputStream out = region.getChunkDataOutputStream(chunk.getPos())) {
                        NbtIo.write(tag, out);
                    }
                    written++;
                }
            }
        }

        return written;
    }

    private static CompoundTag serialize(ClientLevel level, PalettedContainerFactory factory, LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        List<SerializableChunkData.SectionData> sectionData = new ArrayList<>(sections.length);
        for (int i = 0; i < sections.length; i++) {
            sectionData.add(new SerializableChunkData.SectionData(level.getSectionYFromSectionIndex(i), sections[i], null, null));
        }

        Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            heightmaps.put(entry.getKey(), entry.getValue().getRawData());
        }

        List<CompoundTag> blockEntities = new ArrayList<>();
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            CompoundTag tag = chunk.getBlockEntityNbtForSaving(pos, level.registryAccess());
            if (tag != null) {
                blockEntities.add(tag);
            }
        }

        CompoundTag structures = new CompoundTag();
        structures.put("References", new CompoundTag());
        structures.put("starts", new CompoundTag());

        SerializableChunkData data = new SerializableChunkData(
                factory,
                chunk.getPos(),
                level.getMinSectionY(),
                0L,                 // LastUpdate
                0L,                 // InhabitedTime
                ChunkStatus.FULL,
                null,               // blending_data
                null,               // below_zero_retrogen
                UpgradeData.EMPTY,
                null,               // carving_mask
                heightmaps,
                new ChunkAccess.PackedTicks(List.of(), List.of()),
                chunk.getPostProcessing(),
                false,              // isLightOn
                sectionData,
                List.of(),
                blockEntities,
                structures);

        return data.write();
    }

    private static void writeLevelDat(Path saveDir, String worldName, LocalPlayer player) throws IOException {
        Path file = saveDir.resolve("level.dat");
        if (Files.exists(file)) {
            return;
        }
        NbtIo.writeCompressed(levelDataTag(worldName, UUIDUtil.uuidToIntArray(player.getUUID()),
                player.getBlockX(), player.getBlockY(), player.getBlockZ(), player.getYRot()), file);
    }

    static CompoundTag levelDataTag(String worldName, int[] playerUuid, int spawnX, int spawnY, int spawnZ, float spawnYaw) {
        WorldVersion version = SharedConstants.getCurrentVersion();
        int dataVersion = version.dataVersion().version();

        CompoundTag difficulty = new CompoundTag();
        difficulty.putString("difficulty", "peaceful");
        difficulty.putBoolean("hardcore", false);
        difficulty.putBoolean("locked", false);

        CompoundTag spawn = new CompoundTag();
        spawn.putIntArray("pos", new int[]{spawnX, spawnY, spawnZ});
        spawn.putFloat("pitch", 0.0F);
        spawn.putFloat("yaw", spawnYaw);
        spawn.putString("dimension", "minecraft:overworld");

        CompoundTag versionTag = new CompoundTag();
        versionTag.putBoolean("Snapshot", !version.stable());
        versionTag.putString("Series", version.dataVersion().series());
        versionTag.putInt("Id", dataVersion);
        versionTag.putString("Name", version.name());

        ListTag enabled = new ListTag();
        enabled.add(StringTag.valueOf("vanilla"));
        CompoundTag dataPacks = new CompoundTag();
        dataPacks.put("Enabled", enabled);
        dataPacks.put("Disabled", new ListTag());

        ListTag brands = new ListTag();
        brands.add(StringTag.valueOf("fabric"));

        CompoundTag data = new CompoundTag();
        data.put("difficulty_settings", difficulty);
        data.putIntArray("singleplayer_uuid", playerUuid);
        data.putLong("Time", 0L);
        data.putInt("GameType", 1);
        data.put("ServerBrands", brands);
        data.putInt("version", ANVIL_VERSION);
        data.putLong("LastPlayed", System.currentTimeMillis());
        data.put("spawn", spawn);
        data.put("Version", versionTag);
        data.putString("LevelName", worldName);
        data.putBoolean("initialized", true);
        data.putBoolean("WasModded", true);
        data.putInt("DataVersion", dataVersion);
        data.putBoolean("allowCommands", true);
        data.put("DataPacks", dataPacks);

        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        return root;
    }

    private static void writeWorldGenSettings(Path saveDir) throws IOException {
        Path file = saveDir.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat");
        if (Files.exists(file)) {
            return;
        }
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(worldGenSettingsTag(), file);
    }

    static CompoundTag worldGenSettingsTag() {
        CompoundTag flatSettings = new CompoundTag();
        flatSettings.putString("biome", "minecraft:the_void");
        flatSettings.putBoolean("lakes", false);
        flatSettings.putBoolean("features", false);
        CompoundTag airLayer = new CompoundTag();
        airLayer.putString("block", "minecraft:air");
        airLayer.putInt("height", 1);
        ListTag layers = new ListTag();
        layers.add(airLayer);
        flatSettings.put("layers", layers);

        CompoundTag flatGenerator = new CompoundTag();
        flatGenerator.putString("type", "minecraft:flat");
        flatGenerator.put("settings", flatSettings);

        CompoundTag dimensions = new CompoundTag();
        dimensions.put("minecraft:overworld", dimension("minecraft:overworld", flatGenerator));
        dimensions.put("minecraft:the_nether", dimension("minecraft:the_nether",
                noiseGenerator("minecraft:nether", multiNoise("minecraft:nether"))));
        dimensions.put("minecraft:the_end", dimension("minecraft:the_end",
                noiseGenerator("minecraft:end", biomeSource("minecraft:the_end"))));

        CompoundTag data = new CompoundTag();
        data.putBoolean("bonus_chest", false);
        data.putLong("seed", 0L);
        data.putBoolean("generate_structures", false);
        data.put("dimensions", dimensions);

        CompoundTag root = new CompoundTag();
        root.put("data", data);
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        return root;
    }

    private static CompoundTag dimension(String type, CompoundTag generator) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", type);
        tag.put("generator", generator);
        return tag;
    }

    private static CompoundTag noiseGenerator(String settings, CompoundTag biomeSource) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "minecraft:noise");
        tag.putString("settings", settings);
        tag.put("biome_source", biomeSource);
        return tag;
    }

    private static CompoundTag biomeSource(String type) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", type);
        return tag;
    }

    private static CompoundTag multiNoise(String preset) {
        CompoundTag tag = biomeSource("minecraft:multi_noise");
        tag.putString("preset", preset);
        return tag;
    }

    private static String sanitize(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "dump" : cleaned.toLowerCase(Locale.ROOT).equals("con") ? "dump" : cleaned;
    }
}
