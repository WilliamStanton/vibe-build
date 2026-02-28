package com.vibebuild.redstone.executor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vibebuild.Vibebuild;
import com.vibebuild.session.BuildSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Routes redstone tool calls from the AI pipeline.
 * Delegates shared WorldEdit tools to the existing ToolExecutor.
 * Handles redstone-specific tools via native Minecraft API.
 */
public class RedstoneToolExecutor {

    private static final Set<String> SHARED_TOOLS = Set.of(
            "set", "we_replace", "we_line", "we_stack", "we_move"
    );

    /** Read-only inspection tools must never affect schematic/preview bounds. */
    private static final Set<String> READ_ONLY_TOOLS = Set.of("read_block", "read_region");

    /**
     * Execute one AI tool call and return a standard {success,message} payload.
     */
    public JsonObject execute(ServerPlayer player, BuildSession session, String toolName, JsonObject args) {
        JsonObject result;

        // Delegate shared WorldEdit tools to the existing executor
        if (SHARED_TOOLS.contains(toolName)) {
            result = Vibebuild.getInstance().getToolExecutor().execute(player, session, toolName, args);
        } else {
            try {
                String msg = switch (toolName) {
                    case "place_redstone_component" -> execPlaceComponent(player, args);
                    case "place_redstone_line"      -> execPlaceRedstoneLine(player, args);
                    case "place_repeater_chain"     -> execPlaceRepeaterChain(player, args);
                    case "read_block"               -> execReadBlock(player, args);
                    case "read_region"              -> execReadRegion(player, args);
                    default -> throw new UnsupportedOperationException("Unknown redstone tool: " + toolName);
                };
                result = result(true, msg);
            } catch (Exception e) {
                Vibebuild.LOGGER.error("[VB-RS] Tool '{}' failed: {}", toolName, e.getMessage(), e);
                result = result(false, e.getMessage());
            }
        }

        return result;
    }

    /**
     * Expand session bounds for mutating tools only.
     */
    public void updateBounds(BuildSession session, String toolName, JsonObject args) {
        if (READ_ONLY_TOOLS.contains(toolName)) {
            return;
        }

        // Delegate shared tools to existing executor
        if (SHARED_TOOLS.contains(toolName)) {
            Vibebuild.getInstance().getToolExecutor().updateBounds(session, toolName, args);
            return;
        }

        try {
            BlockPos p1 = pos(args, "pos1");
            BlockPos p2 = pos(args, "pos2");
            if (p1 != null && p2 != null) {
                expandBounds(session, p1, p2);
                return;
            }

            BlockPos position = pos(args, "position");
            if (position != null) expandBounds(session, position, position);

            BlockPos start = pos(args, "start");
            if (start != null) {
                if ("place_repeater_chain".equals(toolName)) {
                    int count = args.has("count") ? args.get("count").getAsInt() : 0;
                    int spacing = args.has("spacing") ? args.get("spacing").getAsInt() : 14;
                    Direction dir = parseHorizontalDirection(str(args, "direction"));
                    // Chain length covers both repeaters and inter-repeater dust positions.
                    int chainLength = count <= 0 ? 0 : count + ((count - 1) * Math.max(0, Math.min(14, spacing))) - 1;
                    BlockPos end = start.offset(dir.getStepX() * chainLength, 0, dir.getStepZ() * chainLength);
                    expandBounds(session, start, end);
                } else {
                    expandBounds(session, start, start);
                }
            }
        } catch (Exception ignored) {}
    }

    // ── Redstone tool implementations ──

    /**
     * Place a single redstone-capable block with optional explicit state.
     */
    private String execPlaceComponent(ServerPlayer player, JsonObject args) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos position = pos(args, "position");
        String component = str(args, "component");
        String blockStateStr = args.has("blockState") ? str(args, "blockState") : "";

        // Resolve the block
        Block block = resolveBlock(component);
        if (block == null) {
            throw new RuntimeException("Unknown redstone component: " + component);
        }

        BlockState state = block.defaultBlockState();

        // Apply block state properties
        if (!blockStateStr.isEmpty()) {
            state = applyBlockStateStrict(state, blockStateStr);
        }

        if (!level.setBlockAndUpdate(position, state)) {
            throw new RuntimeException("Block placement rejected by Minecraft — check that a solid support block exists below, and that the block state is valid for this position");
        }

        BlockState placed = level.getBlockState(position);
        if (!placed.is(block)) {
            throw new RuntimeException("Placed block mismatch. Expected "
                    + BuiltInRegistries.BLOCK.getKey(block) + " but got " + blockId(placed));
        }

        // Notify neighbors for redstone connectivity updates
        level.updateNeighborsAt(position, block);

        return component + " placed at " + position.getX() + ", " + position.getY() + ", " + position.getZ()
                + (blockStateStr.isEmpty() ? "" : " [" + blockStateStr + "]");
    }

    /**
     * Place axis-aligned redstone dust with automatic support beneath each dust block.
     */
    private String execPlaceRedstoneLine(ServerPlayer player, JsonObject args) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos p1 = pos(args, "pos1");
        BlockPos p2 = pos(args, "pos2");
        String supportBlock = args.has("supportBlock") ? str(args, "supportBlock") : "stone";

        if (p1.getY() != p2.getY()) {
            throw new IllegalArgumentException("place_redstone_line requires pos1.y == pos2.y");
        }
        boolean sameX = p1.getX() == p2.getX();
        boolean sameZ = p1.getZ() == p2.getZ();
        if (!sameX && !sameZ) {
            throw new IllegalArgumentException("place_redstone_line only supports axis-aligned lines (X or Z)");
        }

        Block support = resolveSupportBlock(supportBlock);

        // Calculate axis-aligned line
        int dx = Integer.signum(p2.getX() - p1.getX());
        int dz = Integer.signum(p2.getZ() - p1.getZ());
        int steps = Math.max(Math.abs(p2.getX() - p1.getX()), Math.abs(p2.getZ() - p1.getZ()));
        int count = 0;

        BlockPos current = p1;
        for (int i = 0; i <= steps; i++) {
            // Place support block beneath if needed
            BlockPos below = current.below();
            BlockState belowState = level.getBlockState(below);
            if (!belowState.isFaceSturdy(level, below, Direction.UP)) {
                level.setBlockAndUpdate(below, support.defaultBlockState());
            }

            // Place redstone wire
            level.setBlockAndUpdate(current, Blocks.REDSTONE_WIRE.defaultBlockState());
            count++;
            if (i < steps) {
                current = current.offset(dx, 0, dz);
            }
        }

        // Update neighbors along the line for connectivity
        current = p1;
        for (int i = 0; i <= steps; i++) {
            level.updateNeighborsAt(current, Blocks.REDSTONE_WIRE);
            if (i < steps) {
                current = current.offset(dx, 0, dz);
            }
        }

        return count + " redstone dust placed from (" + p1.getX() + "," + p1.getY() + "," + p1.getZ()
                + ") to (" + p2.getX() + "," + p2.getY() + "," + p2.getZ() + ")";
    }

    /**
     * Place repeaters and optional dust spacing in one direction for long signal runs.
     */
    private String execPlaceRepeaterChain(ServerPlayer player, JsonObject args) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos start = pos(args, "start");
        String direction = str(args, "direction");
        int count = args.get("count").getAsInt();
        int delay = args.has("delay") ? args.get("delay").getAsInt() : 1;
        int spacing = args.has("spacing") ? args.get("spacing").getAsInt() : 14;
        String supportBlockName = args.has("supportBlock") ? str(args, "supportBlock") : "stone";

        if (count < 1) {
            throw new IllegalArgumentException("place_repeater_chain requires count >= 1");
        }

        delay = Math.max(1, Math.min(4, delay));
        spacing = Math.max(0, Math.min(14, spacing));

        Block support = resolveSupportBlock(supportBlockName);

        // Direction vector and repeater facing
        Direction dir = parseHorizontalDirection(direction);
        int dx = dir.getStepX();
        int dz = dir.getStepZ();
        String repeaterFacing = dir.getName();

        BlockState repeaterState = Blocks.REPEATER.defaultBlockState();
        repeaterState = applyBlockStateStrict(repeaterState, "facing=" + repeaterFacing + ",delay=" + delay);

        BlockPos current = start;
        int totalPlaced = 0;

        for (int r = 0; r < count; r++) {
            // Place dust before each repeater (except first)
            if (r > 0) {
                for (int d = 0; d < spacing; d++) {
                    BlockPos below = current.below();
                    if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                        level.setBlockAndUpdate(below, support.defaultBlockState());
                    }
                    level.setBlockAndUpdate(current, Blocks.REDSTONE_WIRE.defaultBlockState());
                    totalPlaced++;
                    current = current.offset(dx, 0, dz);
                }
            }

            // Place repeater
            BlockPos below = current.below();
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                level.setBlockAndUpdate(below, support.defaultBlockState());
            }
            level.setBlockAndUpdate(current, repeaterState);
            totalPlaced++;
            current = current.offset(dx, 0, dz);
        }

        return count + " repeaters (delay=" + delay + ") with " + spacing + " dust spacing placed. "
                + totalPlaced + " total components.";
    }

    /**
     * Read one block and include full state for verification workflows.
     */
    private String execReadBlock(ServerPlayer player, JsonObject args) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos position = pos(args, "position");
        BlockState state = level.getBlockState(position);

        JsonObject payload = new JsonObject();
        payload.addProperty("x", position.getX());
        payload.addProperty("y", position.getY());
        payload.addProperty("z", position.getZ());
        payload.addProperty("isAir", state.isAir());
        payload.addProperty("blockId", blockId(state));
        payload.addProperty("state", formatBlockState(state));
        return payload.toString();
    }

    /**
     * Read all non-air blocks in a region, capped to avoid oversized tool output.
     */
    private String execReadRegion(ServerPlayer player, JsonObject args) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos p1 = pos(args, "pos1");
        BlockPos p2 = pos(args, "pos2");
        int limit = args.has("maxBlocks") ? Math.max(1, Math.min(5000, args.get("maxBlocks").getAsInt())) : 1000;

        int minX = Math.min(p1.getX(), p2.getX()), maxX = Math.max(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY()), maxY = Math.max(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ()), maxZ = Math.max(p1.getZ(), p2.getZ());

        JsonArray blocks = new JsonArray();
        Map<String, Integer> countsByBlock = new TreeMap<>();
        Set<Long> seen = new HashSet<>();
        int nonAirCount = 0;
        boolean truncated = false;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;

                    nonAirCount++;
                    String blockId = blockId(state);
                    countsByBlock.merge(blockId, 1, Integer::sum);

                    if (blocks.size() >= limit) {
                        truncated = true;
                        continue;
                    }

                    // Defensive de-duplication in case of future scan-path changes.
                    if (!seen.add(pos.asLong())) {
                        continue;
                    }

                    JsonObject entry = new JsonObject();
                    entry.addProperty("x", x);
                    entry.addProperty("y", y);
                    entry.addProperty("z", z);
                    entry.addProperty("blockId", blockId);
                    entry.addProperty("state", formatBlockState(state));
                    blocks.add(entry);
                }
            }
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("limit", limit);
        payload.addProperty("nonAirCount", nonAirCount);
        payload.addProperty("returnedCount", blocks.size());
        payload.addProperty("truncated", truncated);

        JsonObject counts = new JsonObject();
        for (Map.Entry<String, Integer> entry : countsByBlock.entrySet()) {
            counts.addProperty(entry.getKey(), entry.getValue());
        }
        payload.add("countsByBlock", counts);
        payload.add("blocks", blocks);
        return payload.toString();
    }

    // ── Block resolution ──

    private Block resolveBlock(String component) {
        return switch (component) {
            case "redstone_wire"       -> Blocks.REDSTONE_WIRE;
            case "redstone_torch"      -> Blocks.REDSTONE_TORCH;
            case "redstone_wall_torch" -> Blocks.REDSTONE_WALL_TORCH;
            case "repeater"            -> Blocks.REPEATER;
            case "comparator"          -> Blocks.COMPARATOR;
            case "piston"              -> Blocks.PISTON;
            case "sticky_piston"       -> Blocks.STICKY_PISTON;
            case "observer"            -> Blocks.OBSERVER;
            case "dropper"             -> Blocks.DROPPER;
            case "dispenser"           -> Blocks.DISPENSER;
            case "hopper"              -> Blocks.HOPPER;
            case "lever"               -> Blocks.LEVER;
            case "stone_button"        -> Blocks.STONE_BUTTON;
            case "oak_button"          -> Blocks.OAK_BUTTON;
            case "redstone_lamp"       -> Blocks.REDSTONE_LAMP;
            case "target"              -> Blocks.TARGET;
            case "daylight_detector"   -> Blocks.DAYLIGHT_DETECTOR;
            case "tripwire_hook"       -> Blocks.TRIPWIRE_HOOK;
            case "trapped_chest"       -> Blocks.TRAPPED_CHEST;
            case "note_block"          -> Blocks.NOTE_BLOCK;
            case "tnt"                 -> Blocks.TNT;
            case "redstone_block"      -> Blocks.REDSTONE_BLOCK;
            case "slime_block"         -> Blocks.SLIME_BLOCK;
            case "honey_block"         -> Blocks.HONEY_BLOCK;
            default                    -> null;
        };
    }

    private Block resolveSupportBlock(String name) {
        return switch (name.toLowerCase()) {
            case "stone"            -> Blocks.STONE;
            case "stone_bricks"     -> Blocks.STONE_BRICKS;
            case "cobblestone"      -> Blocks.COBBLESTONE;
            case "oak_planks"       -> Blocks.OAK_PLANKS;
            case "spruce_planks"    -> Blocks.SPRUCE_PLANKS;
            case "birch_planks"     -> Blocks.BIRCH_PLANKS;
            case "concrete", "gray_concrete" -> Blocks.GRAY_CONCRETE;
            case "white_concrete"   -> Blocks.WHITE_CONCRETE;
            case "smooth_stone"     -> Blocks.SMOOTH_STONE;
            default                 -> Blocks.STONE;
        };
    }

    private String formatBlockState(BlockState state) {
        // Use toString() which gives "Block{namespace:id}[prop=val,...]" and clean it up
        String full = state.toString();
        // Format: "Block{minecraft:stone}[]" or "Block{minecraft:repeater}[delay=2,facing=north,...]"
        int start = full.indexOf('{');
        int end = full.indexOf('}');
        if (start >= 0 && end > start) {
            String blockId = full.substring(start + 1, end);
            // Check for properties
            int propStart = full.indexOf('[', end);
            int propEnd = full.lastIndexOf(']');
            if (propStart >= 0 && propEnd > propStart) {
                String props = full.substring(propStart + 1, propEnd);
                if (!props.isEmpty()) {
                    return blockId + "[" + props + "]";
                }
            }
            return blockId;
        }
        return full;
    }

    /** Return stable namespaced block id, e.g. minecraft:repeater. */
    private String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /**
     * Parse and apply key=value state pairs.
     * Fails fast on unknown properties or invalid values to avoid silent misplacement.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private BlockState applyBlockStateStrict(BlockState state, String stateStr) {
        if (stateStr.isBlank()) {
            return state;
        }

        for (String pair : stateStr.split(",")) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length != 2) {
                throw new IllegalArgumentException("Invalid blockState pair: '" + pair.trim() + "'");
            }

            String key = kv[0].trim();
            String value = kv[1].trim();

            Property<?> prop = state.getBlock().getStateDefinition().getProperty(key);
            if (prop == null) {
                throw new IllegalArgumentException("Unknown state property '" + key + "' for "
                        + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            }

            Optional<?> parsed = prop.getValue(value);
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("Invalid value '" + value + "' for property '" + key + "'");
            }

            state = state.setValue((Property) prop, (Comparable) parsed.get());
        }
        return state;
    }

    /** Parse cardinal direction and reject unsupported values. */
    private Direction parseHorizontalDirection(String direction) {
        return switch (direction.toLowerCase()) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> throw new IllegalArgumentException("Direction must be one of: north, south, east, west");
        };
    }

    // ── Helpers ──

    private BlockPos pos(JsonObject a, String key) {
        if (!a.has(key) || a.get(key).isJsonNull()) return null;
        JsonObject v = a.getAsJsonObject(key);
        return new BlockPos(v.get("x").getAsInt(), v.get("y").getAsInt(), v.get("z").getAsInt());
    }

    private String str(JsonObject a, String key) {
        return a.has(key) ? a.get(key).getAsString() : "";
    }

    private void expandBounds(BuildSession session, BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        if (session.buildMin == null) {
            session.buildMin = new BlockPos(minX, minY, minZ);
            session.buildMax = new BlockPos(maxX, maxY, maxZ);
        } else {
            session.buildMin = new BlockPos(
                    Math.min(session.buildMin.getX(), minX),
                    Math.min(session.buildMin.getY(), minY),
                    Math.min(session.buildMin.getZ(), minZ));
            session.buildMax = new BlockPos(
                    Math.max(session.buildMax.getX(), maxX),
                    Math.max(session.buildMax.getY(), maxY),
                    Math.max(session.buildMax.getZ(), maxZ));
        }
    }

    private JsonObject result(boolean success, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("success", success);
        o.addProperty("message", message != null ? message : "");
        return o;
    }
}
