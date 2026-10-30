package fr.nilsm.vastcontinents.autotest;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Set;

import com.mojang.datafixers.util.Pair;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Development tool: drives a real client through the world creation screen, then checks the world
 * the same way a player would see it. The logs are prefixed with [autotest].
 */
public final class WorldAutotest implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("autotest");
	private static final Identifier PRESET = Identifier.parse(System.getProperty("vastcontinents.autotest.world", "vastcontinents:continents"));
	private static final String SEED = System.getProperty("vastcontinents.autotest.seed", "2");
	private static final int MIN_VIEW_TICKS = 240;
	// The renderer looks finished right after a teleport, before any chunk has arrived.
	private static final int STABLE_CHUNK_TICKS = 100;
	private static final int MAX_VIEW_TICKS = 1800;
	private static final int DIRECTIONS = 16;
	// Ocean biomes also show up in small inland ponds.
	private static final int MIN_OCEAN_WIDTH = 1500;

	private enum Step {
		MENU, CREATE_SCREEN, WAIT_WORLD, SURVEY, WAIT_SURVEY, VIEWS, DONE
	}

	private record View(String name, double x, double y, double z, float yaw, float pitch) {
	}

	private final ArrayDeque<View> views = new ArrayDeque<>();
	private Step step = Step.MENU;
	private View current;
	private int viewTicks;
	private int loadedChunks;
	private int stableTicks;
	private String resultFile;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(Minecraft minecraft) {
		try {
			switch (step) {
				case MENU -> openCreateScreen(minecraft);
				case CREATE_SCREEN -> createWorld(minecraft);
				case WAIT_WORLD -> waitForWorld(minecraft);
				case SURVEY -> {
					step = Step.WAIT_SURVEY;
					minecraft.getSingleplayerServer().execute(() -> survey(minecraft));
				}
				case VIEWS -> nextView(minecraft);
				default -> {
				}
			}
		} catch (Exception e) {
			LOGGER.error("[autotest] ECHEC", e);
			step = Step.DONE;
			minecraft.stop();
		}
	}

	private void openCreateScreen(Minecraft minecraft) {
		if (minecraft.gui.overlay() == null && minecraft.gui.screen() != null) {
			CreateWorldScreen.openFresh(minecraft, () -> {
			});
			step = Step.CREATE_SCREEN;
		}
	}

	private void createWorld(Minecraft minecraft) throws ReflectiveOperationException {
		if (!(minecraft.gui.screen() instanceof CreateWorldScreen screen)) {
			return;
		}
		WorldCreationUiState state = screen.getUiState();
		WorldCreationUiState.WorldTypeEntry entry = state.getNormalPresetList().stream()
				.filter(type -> type.preset() != null && type.preset().is(PRESET))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("le type de monde n'est pas dans la liste"));
		LOGGER.info("[autotest] type de monde dans la liste : {}", entry.describePreset().getString());

		state.setName("autotest");
		state.setSeed(SEED);
		state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
		state.setWorldType(entry);
		minecraft.options.renderDistance().set(32);
		minecraft.options.pauseOnLostFocus = false;

		Method create = CreateWorldScreen.class.getDeclaredMethod("onCreate");
		create.setAccessible(true);
		create.invoke(screen);
		step = Step.WAIT_WORLD;
	}

	private void waitForWorld(Minecraft minecraft) throws ReflectiveOperationException {
		if (minecraft.gui.screen() instanceof ConfirmScreen confirm) {
			LOGGER.warn("[autotest] AVERTISSEMENT A LA CREATION : {}", confirm.getTitle().getString());
			Field callback = ConfirmScreen.class.getDeclaredField("callback");
			callback.setAccessible(true);
			((BooleanConsumer) callback.get(confirm)).accept(true);
		} else if (minecraft.player != null && minecraft.level != null && minecraft.gui.screen() == null) {
			step = Step.SURVEY;
		}
	}

	// Runs on the server thread.
	private void survey(Minecraft minecraft) {
		ServerLevel level = minecraft.getSingleplayerServer().overworld();
		LOGGER.info("[autotest] graine {} stabilite {}", level.getSeed(), minecraft.getSingleplayerServer().getWorldData().worldGenSettingsLifecycle());

		BlockPos spawn = level.getRespawnData().pos();
		level.getChunk(spawn);
		BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, spawn);
		LOGGER.info("[autotest] spawn {} surface y={} bloc {} biome {}", spawn.toShortString(), surface.getY(),
				level.getBlockState(surface.below()).getBlock(), name(level.getBiome(surface)));

		locate(level, spawn, StructureTags.VILLAGE, "village");
		locate(level, spawn, StructureTags.EYE_OF_ENDER_LOCATED, "stronghold");
		locate(level, spawn, StructureTags.ON_OCEAN_MONUMENT_MAPS, "monument");
		locate(level, spawn, StructureTags.ON_WOODLAND_MANSION_MAPS, "manoir");

		timeGeneration(level);
		views.add(new View("spawn", spawn.getX(), surface.getY() + 60, spawn.getZ(), 0.0F, 22.0F));
		addMountainViews(level, spawn);
		addOceanViews(level, spawn);
		addMushroomView(level, spawn);
		step = Step.VIEWS;
	}

	private void timeGeneration(ServerLevel level) {
		long start = System.nanoTime();
		for (int x = 0; x < 9; x++) {
			for (int z = 0; z < 9; z++) {
				level.getChunk(2500 + x, 2500 + z);
			}
		}
		LOGGER.info("[autotest] 81 chunks generes en {} ms", (System.nanoTime() - start) / 1000000);
	}

	private void addMountainViews(ServerLevel level, BlockPos spawn) {
		var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
		var depth = generator.generatorSettings().value().noiseRouter().depth();
		RandomState random = level.getChunkSource().randomState();
		float highest = Float.NEGATIVE_INFINITY;
		BlockPos peak = spawn;
		for (int x = spawn.getX() - 16000; x <= spawn.getX() + 16000; x += 64) {
			for (int z = spawn.getZ() - 16000; z <= spawn.getZ() + 16000; z += 64) {
				float height = random.sampleBlockValueUncached(depth, x, 0, z);
				if (height > highest) {
					highest = height;
					peak = new BlockPos(x, 0, z);
				}
			}
		}
		level.getChunk(peak);
		BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, peak);
		LOGGER.info("[autotest] sommet {} a {} blocs du spawn, y={} bloc {} biome {}", top.toShortString(), (int) Math.sqrt(top.distSqr(spawn)), top.getY(),
				level.getBlockState(top.below()).getBlock(), name(level.getBiome(top)));

		BlockPos foot = new BlockPos(top.getX() - 420, 0, top.getZ());
		level.getChunk(foot);
		int footY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, foot.getX(), foot.getZ());
		LOGGER.info("[autotest] pied de la montagne y={} : {} blocs de denivele", footY, top.getY() - footY);

		views.add(new View("sommet-pied", foot.getX(), footY + 12, foot.getZ(), -90.0F, -38.0F));
		views.add(new View("sommet-loin", top.getX() - 380, Math.max(top.getY() - 120, 120), top.getZ(), -90.0F, -4.0F));
		views.add(new View("sommet-haut", top.getX(), top.getY() + 40, top.getZ() - 60, 0.0F, 24.0F));
	}

	private void locate(ServerLevel level, BlockPos origin, TagKey<Structure> tag, String label) {
		BlockPos found = level.findNearestMapStructure(tag, origin, 200, false);
		LOGGER.info("[autotest] {} le plus proche : {}", label, found == null ? "AUCUN" : found.toShortString() + " a " + (int) Math.sqrt(found.distSqr(origin)) + " blocs");
	}

	private void addOceanViews(ServerLevel level, BlockPos spawn) {
		BiomeResolver biomes = level.getChunkSource().getGenerator().getBiomeSource().createUncachedResolver(level.getChunkSource().randomState());
		double bestDistance = Double.MAX_VALUE;
		double bestX = 0.0;
		double bestZ = 0.0;
		int width = 0;
		for (int i = 0; i < DIRECTIONS; i++) {
			double angle = i * Math.PI * 2 / DIRECTIONS;
			double x = Math.sin(angle);
			double z = Math.cos(angle);
			for (int distance = 0; distance < 30000 && distance < bestDistance; distance += 32) {
				int run = oceanWidth(biomes, spawn, x, z, distance);
				if (run >= MIN_OCEAN_WIDTH) {
					bestDistance = distance;
					bestX = x;
					bestZ = z;
					width = run;
					break;
				}
				distance += run;
			}
		}
		if (bestDistance == Double.MAX_VALUE) {
			LOGGER.warn("[autotest] AUCUN OCEAN a moins de 30000 blocs");
			return;
		}
		LOGGER.info("[autotest] cote a {} blocs du spawn, ocean large de {} blocs dans cette direction", (int) bestDistance, width);

		float yaw = (float) Math.toDegrees(Math.atan2(-bestX, bestZ));
		double coast = bestDistance - 100.0;
		views.add(new View("cote", spawn.getX() + bestX * coast, 150.0, spawn.getZ() + bestZ * coast, yaw, 18.0F));
		double middle = bestDistance + width / 2.0;
		int middleX = (int) (spawn.getX() + bestX * middle);
		int middleZ = (int) (spawn.getZ() + bestZ * middle);
		level.getChunk(new BlockPos(middleX, 0, middleZ));
		LOGGER.info("[autotest] fond de l'ocean au milieu ({}, {}) : y={} biome {}", middleX, middleZ, level.getHeight(Heightmap.Types.OCEAN_FLOOR, middleX, middleZ),
				name(level.getBiome(new BlockPos(middleX, 63, middleZ))));
		views.add(new View("ocean", middleX, 110.0, middleZ, yaw, 12.0F));
	}

	private void addMushroomView(ServerLevel level, BlockPos spawn) {
		Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(biome -> biome.is(Biomes.MUSHROOM_FIELDS), spawn, 25000, 64, 128);
		if (found == null) {
			LOGGER.warn("[autotest] AUCUNE ILE CHAMPIGNON a moins de 25000 blocs");
			return;
		}
		BlockPos island = found.getFirst();
		level.getChunk(island);
		BlockPos top = level.getHeightmapPos(Heightmap.Types.OCEAN_FLOOR, island);
		LOGGER.info("[autotest] ile champignon {} sol y={}", island.toShortString(), top.getY());
		views.add(new View("champignon", island.getX(), 150.0, island.getZ() - 120.0, 0.0F, 30.0F));
	}

	private void nextView(Minecraft minecraft) throws IOException {
		if (current == null) {
			current = views.poll();
			if (current == null) {
				LOGGER.info("[autotest] TERMINE");
				step = Step.DONE;
				minecraft.stop();
				return;
			}
			viewTicks = 0;
			stableTicks = 0;
			minecraft.gui.setScreen(null);
			View view = current;
			var server = minecraft.getSingleplayerServer();
			server.execute(() -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setGameMode(GameType.SPECTATOR);
				player.teleportTo(server.overworld(), view.x, view.y, view.z, Set.of(), view.yaw, view.pitch, true);
			});
			return;
		}

		viewTicks++;
		int loaded = minecraft.level.getChunkSource().getLoadedChunksCount();
		stableTicks = loaded == loadedChunks ? stableTicks + 1 : 0;
		loadedChunks = loaded;
		boolean ready = viewTicks >= MIN_VIEW_TICKS && stableTicks >= STABLE_CHUNK_TICKS && minecraft.levelRenderer.hasRenderedAllSections();
		if (ready || viewTicks >= MAX_VIEW_TICKS) {
			resultFile = "autotest-" + SEED + "-" + current.name + ".png";
			Screenshot.grab(minecraft.gameDirectory, resultFile, minecraft.gameRenderer.mainRenderTarget(), 1, message -> {
			});
			LOGGER.info("[autotest] capture {} apres {} ticks ({})", resultFile, viewTicks, ready ? "chargee" : "delai depasse");
			current = null;
		}
	}

	private static int oceanWidth(BiomeResolver biomes, BlockPos spawn, double x, double z, int start) {
		int width = 0;
		while (width < 40000 && isOcean(biomes, spawn.getX() + x * (start + width), spawn.getZ() + z * (start + width))) {
			width += 32;
		}
		return width;
	}

	private static boolean isOcean(BiomeResolver biomes, double x, double z) {
		Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock((int) x), QuartPos.fromBlock(63), QuartPos.fromBlock((int) z));
		return name(biome).contains("ocean");
	}

	private static String name(Holder<Biome> biome) {
		return biome.unwrapKey().orElseThrow().identifier().getPath();
	}
}
