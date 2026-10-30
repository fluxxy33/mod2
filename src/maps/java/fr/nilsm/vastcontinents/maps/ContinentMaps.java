package fr.nilsm.vastcontinents.maps;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSpawnFinder;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Development tool: draws the same seeds with the vanilla world types and with ours, from the real
 * generator code and the real JSON files, and measures what a player would feel. It only loads the
 * worldgen data, so no server or client has to start.
 */
public final class ContinentMaps {
	private static final int SIZE = 640;
	private static final int BLOCKS_PER_PIXEL = 100;
	private static final int PIXEL_AREA = BLOCKS_PER_PIXEL * BLOCKS_PER_PIXEL;
	// Below this area a landmass counts as an island: a square of 2000 blocks.
	private static final int ISLAND_AREA = 2000 * 2000;
	private static final int TITLE_HEIGHT = 34;

	private static final List<String> WORLDS = List.of("minecraft:overworld", "minecraft:large_biomes", "vastcontinents:continents");
	private static final List<String> TITLES = List.of("Vanilla", "Grands biomes", "Vastes continents");

	private ContinentMaps() {
	}

	public static void main(String[] args) throws Exception {
		Path resourceDir = Path.of(args[0]);
		Path outputDir = Path.of(args[1]);
		long[] seeds = Arrays.stream(args[2].split(",")).mapToLong(Long::parseLong).toArray();
		Files.createDirectories(outputDir);

		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();

		StringBuilder report = new StringBuilder();
		try (CloseableResourceManager resources = openResources(resourceDir)) {
			RegistryAccess registries = loadRegistries(resources);
			var biomePreset = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
			MultiNoiseBiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(biomePreset);
			for (long seed : seeds) {
				BufferedImage image = new BufferedImage(SIZE * WORLDS.size(), SIZE + TITLE_HEIGHT, BufferedImage.TYPE_INT_RGB);
				Graphics2D graphics = image.createGraphics();
				for (int i = 0; i < WORLDS.size(); i++) {
					WorldMap map = render(registries, biomes, WORLDS.get(i), seed);
					map.draw(graphics, i * SIZE, TITLES.get(i));
					report.append(map.describe(TITLES.get(i), seed)).append('\n');
				}
				graphics.dispose();
				ImageIO.write(image, "png", outputDir.resolve("seed-" + seed + ".png").toFile());
				report.append('\n');
			}
		}
		Files.writeString(outputDir.resolve("stats.txt"), report);
		System.out.print(report);
		System.exit(0);
	}

	private static CloseableResourceManager openResources(Path resourceDir) {
		PackLocationInfo info = new PackLocationInfo("vastcontinents", Component.literal("Vast Continents"), PackSource.BUILT_IN, Optional.empty());
		List<PackResources> packs = List.of(ServerPacksSource.createVanillaPackSource().fullResources(), new PathPackResources(info, resourceDir));
		return new MultiPackResourceManager(PackType.SERVER_DATA, packs);
	}

	// The first steps of WorldLoader.load, the only ones the noise needs.
	private static RegistryAccess loadRegistries(CloseableResourceManager resources) {
		LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
		List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
		RegistryAccess.Frozen context = layers.getAccessForLoading(RegistryLayer.WORLD);
		List<HolderLookup.RegistryLookup<?>> lookups = TagLoader.buildUpdatedLookups(context, staticTags);
		return RegistryDataLoader.load(resources, lookups, RegistryDataLoader.WORLD_REGISTRIES, ForkJoinPool.commonPool()).join();
	}

	private static WorldMap render(RegistryAccess registries, MultiNoiseBiomeSource biomes, String world, long seed) {
		ResourceKey<NoiseGeneratorSettings> key = ResourceKey.create(Registries.NOISE_SETTINGS, Identifier.parse(world));
		NoiseGeneratorSettings settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value();
		RandomState random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings);
		NoiseRouter router = settings.noiseRouter();
		WorldMap map = new WorldMap();

		IntStream.range(0, SIZE).parallel().forEach(row -> {
			BiomeResolver resolver = biomes.createUncachedResolver(random);
			for (int column = 0; column < SIZE; column++) {
				int x = (column - SIZE / 2) * BLOCKS_PER_PIXEL;
				int z = (row - SIZE / 2) * BLOCKS_PER_PIXEL;
				// Depth falls by 1 every 128 blocks and reaches 0 at the terrain surface.
				int surface = (int) (128.0F * random.sampleBlockValueUncached(router.depth(), x, 0, z));
				int biomeY = QuartPos.fromBlock(Math.max(surface, 63));
				Holder<Biome> biome = resolver.getNoiseBiome(QuartPos.fromBlock(x), biomeY, QuartPos.fromBlock(z));
				String name = biome.unwrapKey().orElseThrow().identifier().getPath();
				map.set(column, row, name, surface, random.sampleBlockValueUncached(router.continents(), x, 0, z));
			}
		});
		map.spawn = NoiseSpawnFinder.findSpawnPosition(settings.spawnTarget(), random.samplersWithContext(SamplerContext.EMPTY_UNCACHED));
		return map;
	}

	private static final class WorldMap {
		private final int[] colors = new int[SIZE * SIZE];
		private final boolean[] land = new boolean[SIZE * SIZE];
		private final boolean[] mushroom = new boolean[SIZE * SIZE];
		private final float[] continents = new float[SIZE * SIZE];
		private final int[] heights = new int[SIZE * SIZE];
		private BlockPos spawn = BlockPos.ZERO;

		private void set(int column, int row, String biome, int surface, float continent) {
			int index = row * SIZE + column;
			heights[index] = surface;
			boolean sea = biome.contains("ocean");
			land[index] = !sea;
			mushroom[index] = biome.contains("mushroom");
			continents[index] = continent;
			colors[index] = sea ? seaColor(biome, surface) : shaded(landColor(biome), surface);
		}

		private static int seaColor(String biome, int surface) {
			if (biome.contains("frozen")) {
				return 0xA9C8E8;
			}
			return surface < 40 ? 0x17386B : 0x2F62A8;
		}

		private static Color landColor(String biome) {
			if (biome.contains("river")) {
				return new Color(0x4C8FD6);
			}
			if (biome.contains("beach") || biome.contains("shore")) {
				return new Color(0xE8DCA0);
			}
			if (biome.contains("mushroom")) {
				return new Color(0xC154C1);
			}
			if (biome.contains("snowy") || biome.contains("frozen") || biome.contains("ice") || biome.contains("grove")) {
				return new Color(0xEDF2F4);
			}
			if (biome.contains("peak") || biome.contains("slopes") || biome.contains("windswept") || biome.contains("stony")) {
				return new Color(0x8D8D8D);
			}
			if (biome.contains("desert")) {
				return new Color(0xD9C777);
			}
			if (biome.contains("badlands")) {
				return new Color(0xB5602F);
			}
			if (biome.contains("savanna")) {
				return new Color(0xA9A84A);
			}
			if (biome.contains("jungle") || biome.contains("bamboo")) {
				return new Color(0x2C8A2C);
			}
			if (biome.contains("swamp")) {
				return new Color(0x4A6B45);
			}
			if (biome.contains("taiga")) {
				return new Color(0x3F7A62);
			}
			if (biome.contains("forest") || biome.contains("garden")) {
				return new Color(0x3D8F3D);
			}
			return new Color(0x7DB84D);
		}

		private static int shaded(Color color, int surface) {
			float light = Math.clamp(0.8F + (surface - 63) / 200.0F, 0.7F, 1.15F);
			// Above the vanilla ceiling the land fades to a dark rock, then to white at the top.
			float rock = Math.clamp((surface - 260) / 240.0F, 0.0F, 1.0F);
			float snow = Math.clamp((surface - 500) / 320.0F, 0.0F, 1.0F);
			int red = (int) mix(mix(color.getRed() * light, 0x55, rock), 0xFF, snow);
			int green = (int) mix(mix(color.getGreen() * light, 0x4C, rock), 0xFF, snow);
			int blue = (int) mix(mix(color.getBlue() * light, 0x48, rock), 0xFF, snow);
			return red << 16 | green << 8 | blue;
		}

		private static float mix(float from, float to, float amount) {
			return Math.min(255.0F, from + (to - from) * amount);
		}

		private void draw(Graphics2D graphics, int left, String title) {
			BufferedImage picture = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
			picture.setRGB(0, 0, SIZE, SIZE, colors, 0, SIZE);
			graphics.setColor(Color.BLACK);
			graphics.fillRect(left, 0, SIZE, TITLE_HEIGHT);
			graphics.drawImage(picture, left, TITLE_HEIGHT, null);

			graphics.setColor(Color.WHITE);
			graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
			graphics.drawString(title + " : " + SIZE * BLOCKS_PER_PIXEL / 1000 + " 000 blocs de côté", left + 10, 24);

			// Every 10 000 blocks on a grid that goes through the origin.
			graphics.setColor(new Color(255, 255, 255, 45));
			int step = 10000 / BLOCKS_PER_PIXEL;
			for (int offset = -SIZE / 2 / step * step; offset <= SIZE / 2; offset += step) {
				graphics.drawLine(left + SIZE / 2 + offset, TITLE_HEIGHT, left + SIZE / 2 + offset, TITLE_HEIGHT + SIZE);
				graphics.drawLine(left, TITLE_HEIGHT + SIZE / 2 + offset, left + SIZE, TITLE_HEIGHT + SIZE / 2 + offset);
			}

			int spawnX = left + SIZE / 2 + spawn.getX() / BLOCKS_PER_PIXEL;
			int spawnY = TITLE_HEIGHT + SIZE / 2 + spawn.getZ() / BLOCKS_PER_PIXEL;
			graphics.setColor(Color.RED);
			graphics.setStroke(new BasicStroke(3));
			graphics.drawLine(spawnX - 9, spawnY - 9, spawnX + 9, spawnY + 9);
			graphics.drawLine(spawnX - 9, spawnY + 9, spawnX + 9, spawnY - 9);
		}

		private String describe(String title, long seed) {
			List<Integer> landmasses = sizes(true);
			int total = landmasses.stream().mapToInt(Integer::intValue).sum();
			int biggest = landmasses.stream().mapToInt(Integer::intValue).max().orElse(0);
			long islands = landmasses.stream().filter(size -> size * PIXEL_AREA < ISLAND_AREA).count();
			long seas = sizes(false).stream().filter(size -> size * PIXEL_AREA >= ISLAND_AREA).count();
			int mushrooms = 0;
			for (boolean cell : mushroom) {
				mushrooms += cell ? 1 : 0;
			}

			int spawnIndex = (SIZE / 2 + spawn.getZ() / BLOCKS_PER_PIXEL) * SIZE + SIZE / 2 + spawn.getX() / BLOCKS_PER_PIXEL;
			return String.format(
					"seed %d %-18s sommet y=%4d | terre %4.1f%% | masses %4d dont iles %4d | plus grande %5.1f%% de la terre | etendues d'eau %3d | "
							+ "traversee max %6d | coeur des terres %6d | champignons %4d px | spawn (%d, %d) %s | %s",
					seed, title, Arrays.stream(heights).max().orElse(0), 100.0 * total / (SIZE * SIZE), landmasses.size(), islands, total == 0 ? 0.0 : 100.0 * biggest / total, seas,
					farthestFrom(true) * 2 * BLOCKS_PER_PIXEL, farthestFrom(false) * 2 * BLOCKS_PER_PIXEL, mushrooms,
					spawn.getX(), spawn.getZ(), land[spawnIndex] ? "terre" : "MER", bands());
		}

		// Share of the map in each continentalness band of OverworldBiomeBuilder.
		private String bands() {
			float[] limits = {-1.05F, -0.455F, -0.19F, -0.11F, 0.03F, 0.3F, Float.MAX_VALUE};
			String[] names = {"champ", "large", "ocean", "cote", "proche", "milieu", "loin"};
			int[] counts = new int[limits.length];
			for (float value : continents) {
				int band = 0;
				while (value >= limits[band]) {
					band++;
				}
				counts[band]++;
			}
			StringBuilder text = new StringBuilder();
			for (int i = 0; i < names.length; i++) {
				text.append(String.format("%s %.0f%% ", names[i], 100.0 * counts[i] / continents.length));
			}
			return text.toString().trim();
		}

		private List<Integer> sizes(boolean ofLand) {
			boolean[] seen = new boolean[land.length];
			List<Integer> sizes = new ArrayList<>();
			ArrayDeque<Integer> queue = new ArrayDeque<>();
			for (int start = 0; start < land.length; start++) {
				if (land[start] != ofLand || seen[start]) {
					continue;
				}
				int size = 0;
				seen[start] = true;
				queue.add(start);
				while (!queue.isEmpty()) {
					int cell = queue.poll();
					size++;
					int column = cell % SIZE;
					if (column > 0) {
						visit(cell - 1, ofLand, seen, queue);
					}
					if (column < SIZE - 1) {
						visit(cell + 1, ofLand, seen, queue);
					}
					if (cell >= SIZE) {
						visit(cell - SIZE, ofLand, seen, queue);
					}
					if (cell < land.length - SIZE) {
						visit(cell + SIZE, ofLand, seen, queue);
					}
				}
				sizes.add(size);
			}
			return sizes;
		}

		private void visit(int cell, boolean ofLand, boolean[] seen, ArrayDeque<Integer> queue) {
			if (land[cell] == ofLand && !seen[cell]) {
				seen[cell] = true;
				queue.add(cell);
			}
		}

		// The widest circle in the sea or on land, in pixels: how far a player can be from the other one.
		private int farthestFrom(boolean sourceIsLand) {
			int[] distance = new int[land.length];
			Arrays.fill(distance, -1);
			ArrayDeque<Integer> queue = new ArrayDeque<>();
			for (int cell = 0; cell < land.length; cell++) {
				if (land[cell] == sourceIsLand) {
					distance[cell] = 0;
					queue.add(cell);
				}
			}
			int farthest = 0;
			while (!queue.isEmpty()) {
				int cell = queue.poll();
				farthest = Math.max(farthest, distance[cell]);
				int column = cell % SIZE;
				for (int next : new int[] {column > 0 ? cell - 1 : -1, column < SIZE - 1 ? cell + 1 : -1, cell - SIZE, cell + SIZE}) {
					if (next >= 0 && next < land.length && distance[next] < 0) {
						distance[next] = distance[cell] + 1;
						queue.add(next);
					}
				}
			}
			return farthest;
		}
	}
}
