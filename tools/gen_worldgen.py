"""Generates every worldgen JSON of the mod from the vanilla files of the Minecraft jar.

The world type is a copy of the vanilla overworld with bigger continents, giant mountain ranges and
a higher sky, built the same way vanilla builds Large Biomes: each density function that depends on
what changes is duplicated under the vastcontinents namespace. The tuning values are in the first
block below.

    python tools/gen_worldgen.py
"""
import json
import re
import shutil
import zipfile
from pathlib import Path

MOD_ID = "vastcontinents"
ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / "src" / "main" / "resources"
VANILLA_JAR = Path.home() / ".gradle" / "caches" / "fabric-loom" / "26.3" / "minecraft-common.jar"

# Size of the continents: the mask noise repeats every 2^-MASK_OCTAVE / 0.25 blocks.
MASK_OCTAVE = -12
MASK_OCTAVES = [1.0, 1.0, 1.0, 1.0, 0.5]
# Mask value where land and sea are fully established. Lower: fewer islands along the coasts.
MASK_TRANSITION = 0.1
# What the mask adds to the vanilla continentalness, far from the coasts.
LAND_BIAS = 0.5
OCEAN_BIAS = 0.85
# Shifts the coast from the middle of the mask: positive gives more land than sea.
LAND_SHARE_BIAS = -0.04
# Guaranteed land around the world origin, because the spawn search stops at 2048 blocks.
ORIGIN_BONUS = 0.75
ORIGIN_RADIUS = 6000
# Mushroom islands need a continentalness below -1.05, which is now out of reach without help.
DEEPEST_OCEAN = -1.02
MUSHROOM_DEPTH = 0.2
MUSHROOM_THRESHOLD = 0.72
MUSHROOM_SHARPNESS = 8.0

# Top of the world, exclusive. The height has to be a multiple of 16, so 1000 is not possible.
MIN_Y = -64
WORLD_TOP = 1008
# The terrain fades out over the last blocks, like vanilla does between 240 and 256.
TOP_SLIDE_START = WORLD_TOP - 32
TOP_SLIDE_END = WORLD_TOP - 16

# Height added at the top of a range, in units of 128 blocks: 5.2 is about 670 blocks.
MOUNTAIN_HEIGHT = 5.2
# Higher: thinner ranges. The ridge noise repeats every 2^-MOUNTAIN_OCTAVE / 0.25 blocks.
MOUNTAIN_OCTAVE = -10
MOUNTAIN_SHARPNESS = 1.7
# Mask value where the mountains are allowed and at full size: they stay away from the coasts.
MOUNTAIN_INLAND_START = 0.1
MOUNTAIN_INLAND_FULL = 0.22
# Lower: more of each continent has ranges.
MOUNTAIN_REGION_THRESHOLD = -0.1
# Part of the height that comes from the small peaks along the ridge.
MOUNTAIN_PEAK_SHARE = 0.3
# No mountain on the spawn: they start at this distance from the origin and grow over the next one.
MOUNTAIN_SPAWN_CLEARING = 400
MOUNTAIN_SPAWN_RAMP = 800
# Vanilla erosion on the ranges: a low value gives the peak biomes instead of a forest at y=900.
MOUNTAIN_EROSION = -0.85
MOUNTAIN_JAGGEDNESS = 1.2
# Between these heights the ranges go from the real cave biomes to surface biomes only.
MOUNTAIN_SURFACE_BIOMES_START = 64
MOUNTAIN_SURFACE_BIOMES_FULL = 160

# The functions that depend on what changes, same list as overworld_large_biomes. The ones that
# get a new layer on top keep the vanilla version under another name.
COPIED_FUNCTIONS = {
    "chunk_surface_level": "chunk_surface_level",
    "depth": "depth",
    "factor": "factor",
    "final_density": "final_density",
    "jaggedness": "base_jaggedness",
    "offset": "base_offset",
    "preliminary_surface_level": "preliminary_surface_level",
    "sloped_cheese": "sloped_cheese",
}
OVERWORLD_REF = re.compile(
    r'"minecraft:overworld/(' + "|".join([*COPIED_FUNCTIONS, "continents", "erosion"]) + r')"'
)


def df(name):
    return f"{MOD_ID}:continents/{name}"


def binary(kind, left, right):
    return {"type": f"minecraft:{kind}", "left": left, "right": right}


def noise(name):
    return {
        "type": "minecraft:noise",
        "noise": name,
        "shift_x": "minecraft:shift_x",
        "shift_z": "minecraft:shift_z",
        "xz_scale": 0.25,
        "y_scale": 0.0,
    }


def spline(coordinate, points):
    return {
        "type": "minecraft:spline",
        "spline": {
            "coordinate": coordinate,
            "points": [{"location": x, "value": y, "derivative": d} for x, y, d in points],
        },
    }


def unit(value):
    return {"type": "minecraft:clamp", "input": value, "min": 0.0, "max": 1.0}


def origin_distance():
    # The slice drops the height from the distance, so the continents stay a 2D function.
    return {
        "type": "minecraft:slice",
        "axis": "y",
        "coordinate": 0,
        "input": {"type": "minecraft:distance_to_point", "point": [0, 0, 0], "metric": "euclidean"},
    }


def origin_bonus():
    closeness = binary("sub", 1.0, binary("mul", origin_distance(), 1.0 / ORIGIN_RADIUS))
    return binary("mul", ORIGIN_BONUS, unit(closeness))


def ridge(noise_name):
    # 1 where the noise crosses zero, which draws long winding lines, and 0 away from them.
    folded = {"type": "minecraft:abs", "input": noise(f"{MOD_ID}:{noise_name}")}
    return unit(binary("sub", 1.0, binary("mul", folded, MOUNTAIN_SHARPNESS)))


def mountains():
    inland = binary("sub", df("mask"), MOUNTAIN_INLAND_START)
    inland = unit(binary("mul", inland, 1.0 / (MOUNTAIN_INLAND_FULL - MOUNTAIN_INLAND_START)))
    region = binary("add", noise(f"{MOD_ID}:mountain_regions"), -MOUNTAIN_REGION_THRESHOLD)
    region = unit(binary("mul", region, 5.0))
    clearing = binary("sub", origin_distance(), MOUNTAIN_SPAWN_CLEARING)
    clearing = unit(binary("mul", clearing, 1.0 / MOUNTAIN_SPAWN_RAMP))
    where = binary("mul", binary("mul", inland, region), clearing)

    ridges = {"type": "minecraft:square", "input": ridge("mountain_ridges")}
    peaks = binary("add", 1.0 - MOUNTAIN_PEAK_SHARE, binary("mul", MOUNTAIN_PEAK_SHARE, ridge("mountain_peaks")))
    return {"type": "minecraft:cache", "input": binary("mul", where, binary("mul", ridges, peaks))}


def offset():
    return {"type": "minecraft:cache", "input": binary("add", df("base_offset"), binary("mul", MOUNTAIN_HEIGHT, df("mountains")))}


def jaggedness():
    return {"type": "minecraft:cache", "input": binary("add", df("base_jaggedness"), binary("mul", MOUNTAIN_JAGGEDNESS, df("mountains")))}


def biome_depth():
    # Vanilla picks cave biomes from the depth, but on slopes this steep the ground can sit far below
    # depth 0 and the surface would read as a cave. High in the ranges, the depth is simply ignored.
    high = {
        "type": "minecraft:gradient",
        "axis": "y",
        "from_coordinate": MOUNTAIN_SURFACE_BIOMES_START,
        "from_value": 0.0,
        "to_coordinate": MOUNTAIN_SURFACE_BIOMES_FULL,
        "to_value": 1.0,
    }
    in_range = unit(binary("mul", df("mountains"), 4.0))
    return binary("mul", df("depth"), binary("sub", 1.0, binary("mul", in_range, high)))


def erosion():
    return {
        "type": "minecraft:cache",
        "input": {
            "type": "minecraft:lerp",
            "alpha": unit(binary("mul", df("mountains"), 4.0)),
            "first": "minecraft:overworld/erosion",
            "second": MOUNTAIN_EROSION,
        },
    }


def mask():
    return binary("add", binary("add", noise(f"{MOD_ID}:continent_mask"), LAND_SHARE_BIAS), origin_bonus())


def raw_continents():
    bias = spline(df("mask"), [
        (-MASK_TRANSITION, -OCEAN_BIAS, 0.0),
        (MASK_TRANSITION, LAND_BIAS, 0.0),
    ])
    return binary("add", noise("minecraft:continentalness"), bias)


def continents():
    # Vanilla reads anything below -1.05 as mushroom fields, so the open ocean has to stop above it.
    shaped = spline(df("raw"), [
        (-1.5, DEEPEST_OCEAN, 0.0),
        (-0.45, -0.45, 1.0),
        (1.0, 1.0, 0.0),
    ])
    spots = binary("mul", binary("add", noise(f"{MOD_ID}:mushroom_spots"), -MUSHROOM_THRESHOLD), MUSHROOM_SHARPNESS)
    spots = {"type": "minecraft:clamp", "input": spots, "min": 0.0, "max": 1.0}
    return {"type": "minecraft:cache", "input": binary("add", shaped, binary("mul", -MUSHROOM_DEPTH, spots))}


def renamed(text):
    return OVERWORLD_REF.sub(lambda match: f'"{df(match.group(1))}"', text)


def raised(text):
    """Moves every height limit of a vanilla function up to the new top of the world."""
    def raise_limits(node):
        if isinstance(node, list):
            return [raise_limits(child) for child in node]
        if not isinstance(node, dict):
            return node
        node = {key: raise_limits(value) for key, value in node.items()}
        if node.get("type") == "minecraft:gradient" and node.get("axis") == "y":
            if (node["from_coordinate"], node["to_coordinate"]) == (240, 256):
                node["from_coordinate"], node["to_coordinate"] = TOP_SLIDE_START, TOP_SLIDE_END
            elif (node["from_coordinate"], node["to_coordinate"]) == (MIN_Y, 320):
                # Same slope as vanilla, so a block of height still means the same thing.
                slope = (node["to_value"] - node["from_value"]) / (320 - MIN_Y)
                node["to_coordinate"] = WORLD_TOP
                node["to_value"] = node["from_value"] + slope * (WORLD_TOP - MIN_Y)
        if node.get("type") == "minecraft:clamp" and node.get("max") == 320.0:
            node["max"] = float(WORLD_TOP)
        return node

    return json.dumps(raise_limits(json.loads(text)), indent=2)


def write(path, content):
    target = RESOURCES / path
    target.parent.mkdir(parents=True, exist_ok=True)
    if not isinstance(content, str):
        content = json.dumps(content, indent=2, ensure_ascii=False)
    target.write_text(content + "\n", encoding="utf-8")


def main():
    data = RESOURCES / "data"
    shutil.rmtree(data, ignore_errors=True)
    worldgen = f"data/{MOD_ID}/worldgen"

    with zipfile.ZipFile(VANILLA_JAR) as jar:
        def vanilla(path):
            return jar.read(f"data/minecraft/worldgen/{path}.json").decode("utf-8")

        for name, target in COPIED_FUNCTIONS.items():
            function = raised(renamed(vanilla(f"density_function/overworld/{name}")))
            write(f"{worldgen}/density_function/continents/{target}.json", function)

        settings = json.loads(renamed(vanilla("noise_settings/overworld")))
        settings["noise"]["height"] = WORLD_TOP - MIN_Y
        settings["noise_router"]["depth"] = df("biome_depth")
        write(f"{worldgen}/noise_settings/continents.json", settings)

        preset = json.loads(vanilla("world_preset/normal"))
        overworld = preset["dimensions"]["minecraft:overworld"]
        overworld["generator"]["settings"] = f"{MOD_ID}:continents"
        overworld["type"] = f"{MOD_ID}:tall_overworld"
        write(f"{worldgen}/world_preset/continents.json", preset)

        dimension = json.loads(jar.read("data/minecraft/dimension_type/overworld.json"))
        dimension["height"] = dimension["logical_height"] = WORLD_TOP - MIN_Y
        write(f"data/{MOD_ID}/dimension_type/tall_overworld.json", dimension)

    write(f"{worldgen}/density_function/continents/mask.json", mask())
    write(f"{worldgen}/density_function/continents/raw.json", raw_continents())
    write(f"{worldgen}/density_function/continents/continents.json", continents())
    write(f"{worldgen}/density_function/continents/mountains.json", mountains())
    write(f"{worldgen}/density_function/continents/offset.json", offset())
    write(f"{worldgen}/density_function/continents/jaggedness.json", jaggedness())
    write(f"{worldgen}/density_function/continents/erosion.json", erosion())
    write(f"{worldgen}/density_function/continents/biome_depth.json", biome_depth())

    write(f"{worldgen}/noise/continent_mask.json", {
        "amplitude_modifiers": MASK_OCTAVES,
        "base_amplitude": 1.0,
        "base_octave": MASK_OCTAVE,
        "octave_count": len(MASK_OCTAVES),
    })
    write(f"{worldgen}/noise/mushroom_spots.json", {
        "amplitude_modifiers": [1.0, 1.0],
        "base_amplitude": 1.0,
        "base_octave": -8,
        "octave_count": 2,
    })
    write(f"{worldgen}/noise/mountain_ridges.json", {
        "amplitude_modifiers": [1.0, 0.6, 0.2],
        "base_amplitude": 1.0,
        "base_octave": MOUNTAIN_OCTAVE,
        "octave_count": 3,
    })
    write(f"{worldgen}/noise/mountain_regions.json", {
        "amplitude_modifiers": [1.0, 1.0, 0.5],
        "base_amplitude": 1.0,
        "base_octave": MOUNTAIN_OCTAVE - 1,
        "octave_count": 3,
    })
    write(f"{worldgen}/noise/mountain_peaks.json", {
        "amplitude_modifiers": [1.0, 1.0, 0.5, 0.5],
        "base_amplitude": 1.0,
        "base_octave": MOUNTAIN_OCTAVE + 3,
        "octave_count": 4,
    })

    # This tag is the list the "World Type" button goes through.
    write("data/minecraft/tags/worldgen/world_preset/normal.json", {"replace": False, "values": [f"{MOD_ID}:continents"]})

    write(f"assets/{MOD_ID}/lang/fr_fr.json", {f"generator.{MOD_ID}.continents": "Vastes continents"})
    write(f"assets/{MOD_ID}/lang/en_us.json", {f"generator.{MOD_ID}.continents": "Vast Continents"})


if __name__ == "__main__":
    main()
