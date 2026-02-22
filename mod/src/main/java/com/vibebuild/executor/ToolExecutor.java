package com.vibebuild.executor;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.World;
import com.vibebuild.Vibebuild;
import com.vibebuild.session.BuildSession;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes tool_call messages from the vibe-build server to the WorldEdit Java API.
 */
public class ToolExecutor {

    public JsonObject execute(ServerPlayer player, BuildSession session, String toolName, JsonObject args) {
        // Handle non-WorldEdit tools that use native Minecraft API
        if (toolName.equals("place_sign")) {
            try {
                String msg = execPlaceSign(player, args);
                return result(true, msg);
            } catch (Exception e) {
                Vibebuild.LOGGER.error("[VB] Tool '{}' failed: {}", toolName, e.getMessage(), e);
                return result(false, e.getMessage());
            }
        }

        try {
            World weWorld = FabricAdapter.adapt(player.level());
            Actor actor = FabricAdapter.adaptPlayer(player);
            try (EditSession editSession = WorldEdit.getInstance()
                    .newEditSessionBuilder()
                    .world(weWorld)
                    .actor(actor)
                    .build()) {

                String msg = dispatch(editSession, weWorld, actor, toolName, args);
                return result(true, msg);
            }
        } catch (Exception e) {
            Vibebuild.LOGGER.error("[VB] Tool '{}' failed: {}", toolName, e.getMessage(), e);
            return result(false, e.getMessage());
        }
    }

    public void updateBounds(BuildSession session, String toolName, JsonObject args) {
        try {
            BlockPos p1 = pos(args, "pos1");
            BlockPos p2 = pos(args, "pos2");
            if (p1 != null && p2 != null) expandBounds(session, p1, p2);

            BlockPos center = pos(args, "center");
            if (center != null) {
                int r = args.has("radiusNS") ? args.get("radiusNS").getAsInt() : 0;
                int h = args.has("height")   ? args.get("height").getAsInt()   : 1;
                expandBounds(session, center.offset(-r, 0, -r), center.offset(r, h, r));
            }

            BlockPos position = pos(args, "position");
            if (position != null) expandBounds(session, position, position);
        } catch (Exception ignored) {}
    }

    // ── Dispatcher ──

    private String dispatch(EditSession es, World world, Actor actor, String name, JsonObject a) throws Exception {
        return switch (name) {
            case "set"             -> execSet(es, world, actor, a);
            case "we_replace"      -> execReplace(es, world, actor, a);
            case "we_walls"        -> execWalls(es, world, actor, a);
            case "we_faces"        -> execFaces(es, world, actor, a);
            case "we_overlay"      -> execOverlay(es, world, actor, a);
            case "we_center"       -> execCenter(es, world, actor, a);
            case "we_naturalize"   -> execNaturalize(es, world, a);
            case "we_line"         -> execLine(es, world, actor, a);
            case "we_curve"        -> execCurve(es, world, actor, a);
            case "we_move"         -> execMove(es, world, actor, a);
            case "we_stack"        -> execStack(es, world, a);
            case "we_smooth"       -> execSmooth(es, world, a);
            case "we_hollow"       -> execHollow(es, world, actor, a);
            case "we_deform"       -> execDeform(es, world, a);
            case "we_cyl"          -> execCyl(es, world, actor, a);
            case "we_sphere"       -> execSphere(es, world, actor, a);
            case "we_pyramid"      -> execPyramid(es, world, actor, a);
            case "we_cone"         -> execCone(es, world, actor, a);
            case "we_generate"     -> execGenerate(es, world, actor, a);
            case "we_copy"         -> "copy requires actor";
            case "we_cut"          -> "cut requires actor";
            case "we_paste"        -> "paste requires actor";
            case "we_rotate"       -> "rotate requires actor";
            case "we_flip"         -> "flip requires actor";
            case "we_undo"         -> "undo requires actor";
            case "we_redo"         -> "redo requires actor";
            case "we_fill"         -> execFill(es, world, actor, a);
            case "we_drain"        -> execDrain(es, world, a);
            case "we_remove_near"  -> execRemoveNear(es, world, actor, a);
            case "we_replace_near" -> execReplaceNear(es, world, actor, a);
            default                -> throw new UnsupportedOperationException("Unknown tool: " + name);
        };
    }

    // ── Region ──

    private String execSet(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        CuboidRegion region = cuboid(a);
        Pattern pattern = parsePattern(world, actor, str(a, "pattern"));
        int count = es.setBlocks(region, pattern);
        return count + " blocks set";
    }

    private String execReplace(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        CuboidRegion region = cuboid(a);
        Pattern to = parsePattern(world, actor, str(a, "to"));
        int count;
        if (a.has("from") && !a.get("from").isJsonNull()) {
            Mask mask = parseMask(world, actor, es, str(a, "from"));
            count = es.replaceBlocks(region, mask, to);
        } else {
            count = es.replaceBlocks(region, (Mask) null, to);
        }
        return count + " blocks replaced";
    }

    private String execWalls(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        CuboidRegion region = cuboid(a);
        Pattern pattern = parsePattern(world, actor, str(a, "pattern"));
        int count = es.makeWalls(region, pattern);
        return count + " wall blocks set";
    }

    private String execFaces(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        CuboidRegion region = cuboid(a);
        Pattern pattern = parsePattern(world, actor, str(a, "pattern"));
        int count = es.makeCuboidFaces(region, pattern);
        return count + " face blocks set";
    }

    private String execOverlay(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        CuboidRegion region = cuboid(a);
        Pattern pattern = parsePattern(world, actor, str(a, "pattern"));
        int count = es.overlayCuboidBlocks(region, pattern);
        return count + " overlay blocks set";
    }

    private String execCenter(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        CuboidRegion region = cuboid(a);
        Pattern pattern = parsePattern(world, actor, str(a, "pattern"));
        int count = es.center(region, pattern);
        return count + " center blocks set";
    }

    private String execNaturalize(EditSession es, World world, JsonObject a) throws Exception {
        CuboidRegion region = cuboid(a);
        int count = es.naturalizeCuboidBlocks(region);
        return count + " blocks naturalized";
    }

    private String execLine(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        BlockVector3 p1 = bv3(a, "pos1");
        BlockVector3 p2 = bv3(a, "pos2");
        Pattern pattern = parsePattern(world, actor, str(a, "pattern"));
        int thickness = a.has("thickness") ? a.get("thickness").getAsInt() : 0;
        boolean hollow = a.has("hollow") && a.get("hollow").getAsBoolean();
        int count = es.drawLine(pattern, p1, p2, thickness, !hollow);
        return count + " line blocks set";
    }

    private String execCurve(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        List<BlockVector3> controlPoints = parsePoints(a, "points");
        Pattern pattern = parsePattern(world, actor, str(a, "pattern"));
        int thickness = a.has("thickness") ? a.get("thickness").getAsInt() : 0;
        boolean hollow = a.has("hollow") && a.get("hollow").getAsBoolean();

        if (controlPoints.size() < 3) {
            throw new IllegalArgumentException("we_curve requires at least 3 points");
        }

        int segmentsPerSpan = 12;
        List<BlockVector3> sampled = new ArrayList<>();
        for (int i = 0; i < controlPoints.size() - 1; i++) {
            BlockVector3 p0 = controlPoints.get(Math.max(0, i - 1));
            BlockVector3 p1 = controlPoints.get(i);
            BlockVector3 p2 = controlPoints.get(i + 1);
            BlockVector3 p3 = controlPoints.get(Math.min(controlPoints.size() - 1, i + 2));

            for (int s = 0; s < segmentsPerSpan; s++) {
                double t = (double) s / segmentsPerSpan;
                sampled.add(catmullRom(p0, p1, p2, p3, t));
            }
        }
        sampled.add(controlPoints.get(controlPoints.size() - 1));

        int count = 0;
        for (int i = 0; i < sampled.size() - 1; i++) {
            BlockVector3 p1 = sampled.get(i);
            BlockVector3 p2 = sampled.get(i + 1);
            count += es.drawLine(pattern, p1, p2, thickness, !hollow);
        }

        return count + " curve blocks set";
    }

    private String execMove(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        CuboidRegion region = cuboid(a);
        int distance = a.get("distance").getAsInt();
        Direction dir = parseDirection(str(a, "direction"));
        BlockVector3 dirVec = dir.toBlockVector().multiply(distance);
        Pattern leave = a.has("leavePattern") && !a.get("leavePattern").isJsonNull()
                ? parsePattern(world, actor, str(a, "leavePattern"))
                : parsePattern(world, actor, "air");
        int count = es.moveRegion(region, dirVec, distance, true, leave);
        return count + " blocks moved";
    }

    private String execStack(EditSession es, World world, JsonObject a) throws Exception {
        CuboidRegion region = cuboid(a);
        int count = a.get("count").getAsInt();
        Direction dir = parseDirection(str(a, "direction"));
        int affected = es.stackCuboidRegion(region, dir.toBlockVector(), count, true);
        return affected + " blocks stacked";
    }

    private String execSmooth(EditSession es, World world, JsonObject a) throws Exception {
        // smoothRegion does not exist in WE 7.4.0; naturalize is the closest alternative
        CuboidRegion region = cuboid(a);
        int count = es.naturalizeCuboidBlocks(region);
        return count + " blocks smoothed (naturalized)";
    }

    private String execHollow(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        CuboidRegion region = cuboid(a);
        int thickness = a.has("thickness") ? a.get("thickness").getAsInt() : 1;
        Pattern fill = a.has("fillPattern") && !a.get("fillPattern").isJsonNull()
                ? parsePattern(world, actor, str(a, "fillPattern"))
                : parsePattern(world, actor, "air");
        int count = es.hollowOutRegion(region, thickness, fill);
        return count + " blocks hollowed";
    }

    private String execDeform(EditSession es, World world, JsonObject a) throws Exception {
        CuboidRegion region = cuboid(a);
        String expression = str(a, "expression");
        int count = es.deformRegion(region,
                Vector3.ZERO,
                Vector3.ONE,
                expression);
        return count + " blocks deformed";
    }

    // ── Generation ──

    private String execCyl(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        BlockVector3 center = bv3(a, "center");
        Pattern pattern = parsePattern(world, actor, str(a, "pattern"));
        double radiusNS = a.get("radiusNS").getAsDouble();
        double radiusEW = a.has("radiusEW") ? a.get("radiusEW").getAsDouble() : radiusNS;
        int height = a.has("height") ? a.get("height").getAsInt() : 1;
        boolean hollow = a.has("hollow") && a.get("hollow").getAsBoolean();
        int count = es.makeCylinder(center, pattern, radiusNS, radiusEW, height, !hollow);
        return count + " cylinder blocks set";
    }

    private String execSphere(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        BlockVector3 center = bv3(a, "center");
        Pattern pattern = parsePattern(world, actor, str(a, "pattern"));
        double radiusNS = a.get("radiusNS").getAsDouble();
        double radiusUD = a.has("radiusUD") ? a.get("radiusUD").getAsDouble() : radiusNS;
        double radiusEW = a.has("radiusEW") ? a.get("radiusEW").getAsDouble() : radiusNS;
        boolean hollow = a.has("hollow") && a.get("hollow").getAsBoolean();
        int count = es.makeSphere(center, pattern, radiusNS, radiusUD, radiusEW, !hollow);
        return count + " sphere blocks set";
    }

    private String execPyramid(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        BlockVector3 center = bv3(a, "center");
        Pattern pattern = parsePattern(world, actor, str(a, "pattern"));
        int size = a.get("size").getAsInt();
        boolean hollow = a.has("hollow") && a.get("hollow").getAsBoolean();
        int count = es.makePyramid(center, pattern, size, !hollow);
        return count + " pyramid blocks set";
    }

    private String execCone(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        BlockVector3 center = bv3(a, "center");
        Pattern pattern = parsePattern(world, actor, str(a, "pattern"));
        double baseRadiusNS = a.get("radiusNS").getAsDouble();
        double baseRadiusEW = a.has("radiusEW") ? a.get("radiusEW").getAsDouble() : baseRadiusNS;
        int height = a.has("height") ? a.get("height").getAsInt() : (int) Math.max(1, Math.ceil(baseRadiusNS));
        boolean hollow = a.has("hollow") && a.get("hollow").getAsBoolean();
        int thickness = a.has("thickness") ? a.get("thickness").getAsInt() : 1;
        if (thickness < 1) thickness = 1;

        int count = 0;
        for (int y = 0; y < height; y++) {
            double taper = 1.0 - ((double) y / Math.max(1, height));
            double radiusNS = baseRadiusNS * taper;
            double radiusEW = baseRadiusEW * taper;
            if (radiusNS <= 0 || radiusEW <= 0) continue;

            BlockVector3 layerCenter = center.add(0, y, 0);
            if (!hollow) {
                count += es.makeCylinder(layerCenter, pattern, radiusNS, radiusEW, 1, true);
                continue;
            }

            int shellLayers = Math.max(1, thickness);
            for (int t = 0; t < shellLayers; t++) {
                double shellRadiusNS = radiusNS - t;
                double shellRadiusEW = radiusEW - t;
                if (shellRadiusNS <= 0 || shellRadiusEW <= 0) break;
                count += es.makeCylinder(layerCenter, pattern, shellRadiusNS, shellRadiusEW, 1, false);
            }
        }

        return count + " cone blocks set";
    }

    private String execGenerate(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        CuboidRegion region = cuboid(a);
        Pattern pattern = parsePattern(world, actor, str(a, "pattern"));
        String expression = str(a, "expression");
        boolean hollow = a.has("hollow") && a.get("hollow").getAsBoolean();
        int count = es.makeShape(region,
                new com.sk89q.worldedit.math.transform.AffineTransform(),
                pattern, expression, hollow, -1);
        return count + " generated blocks set";
    }

    // ── Utility ──

    private String execFill(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        BlockVector3 pos = bv3(a, "position");
        Pattern pattern = parsePattern(world, actor, str(a, "pattern"));
        int radius = a.get("radius").getAsInt();
        int depth  = a.has("depth") ? a.get("depth").getAsInt() : 512;
        int count = es.fillXZ(pos, pattern, radius, depth, false);
        return count + " fill blocks set";
    }

    private String execDrain(EditSession es, World world, JsonObject a) throws Exception {
        BlockVector3 pos = bv3(a, "position");
        int radius = a.get("radius").getAsInt();
        boolean removeWaterlogged = a.has("removeWaterlogged") && a.get("removeWaterlogged").getAsBoolean();
        int count = es.drainArea(pos, radius, removeWaterlogged);
        return count + " liquid blocks removed";
    }

    private String execRemoveNear(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        BlockVector3 pos = bv3(a, "position");
        Mask mask = parseMask(world, actor, es, str(a, "mask"));
        int radius = a.has("radius") ? a.get("radius").getAsInt() : 5;
        int count = es.removeNear(pos, mask, radius);
        return count + " blocks removed";
    }

    private String execReplaceNear(EditSession es, World world, Actor actor, JsonObject a) throws Exception {
        BlockVector3 pos = bv3(a, "position");
        int radius = a.get("radius").getAsInt();
        Pattern to = parsePattern(world, actor, str(a, "to"));
        CuboidRegion region = new CuboidRegion(world,
                pos.subtract(radius, radius, radius),
                pos.add(radius, radius, radius));
        int count;
        if (a.has("from") && !a.get("from").isJsonNull()) {
            Mask mask = parseMask(world, actor, es, str(a, "from"));
            count = es.replaceBlocks(region, mask, to);
        } else {
            count = es.replaceBlocks(region, (Mask) null, to);
        }
        return count + " blocks replaced";
    }

    // ── Sign Placement (native Minecraft API) ──

    private String execPlaceSign(ServerPlayer player, JsonObject a) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos signPos = pos(a, "position");
        String signType = str(a, "signType");
        boolean wallMounted = a.has("wallMounted") && a.get("wallMounted").getAsBoolean();
        String facing = str(a, "facing");

        // Resolve sign block
        Block signBlock = resolveSignBlock(signType, wallMounted);
        BlockState state = signBlock.defaultBlockState();

        if (wallMounted) {
            net.minecraft.core.Direction dir = parseMinecraftDirection(facing);
            state = state.setValue(WallSignBlock.FACING, dir);
        } else {
            int rotation = facingToStandingRotation(facing);
            state = state.setValue(StandingSignBlock.ROTATION, rotation);
        }

        // Place the block
        level.setBlockAndUpdate(signPos, state);

        // Set text on the sign block entity
        BlockEntity be = level.getBlockEntity(signPos);
        if (be instanceof SignBlockEntity sign) {
            // Front text
            if (a.has("frontLines")) {
                SignText front = sign.getFrontText();
                var lines = a.getAsJsonArray("frontLines");
                for (int i = 0; i < Math.min(lines.size(), 4); i++) {
                    front = front.setMessage(i, Component.literal(lines.get(i).getAsString()));
                }
                // Apply color
                if (a.has("color") && !a.get("color").isJsonNull()) {
                    DyeColor color = parseDyeColor(str(a, "color"));
                    front = front.setColor(color);
                }
                // Apply glow
                if (a.has("glowing") && a.get("glowing").getAsBoolean()) {
                    front = front.setHasGlowingText(true);
                }
                sign.setText(front, true);
            }

            // Back text
            if (a.has("backLines") && !a.get("backLines").isJsonNull()) {
                SignText back = sign.getBackText();
                var lines = a.getAsJsonArray("backLines");
                for (int i = 0; i < Math.min(lines.size(), 4); i++) {
                    back = back.setMessage(i, Component.literal(lines.get(i).getAsString()));
                }
                if (a.has("color") && !a.get("color").isJsonNull()) {
                    DyeColor color = parseDyeColor(str(a, "color"));
                    back = back.setColor(color);
                }
                if (a.has("glowing") && a.get("glowing").getAsBoolean()) {
                    back = back.setHasGlowingText(true);
                }
                sign.setText(back, false);
            }

            sign.setChanged();
            // Notify clients of the block entity update
            level.sendBlockUpdated(signPos, state, state, Block.UPDATE_ALL);
        }

        return "Sign placed at " + signPos.getX() + ", " + signPos.getY() + ", " + signPos.getZ();
    }

    private Block resolveSignBlock(String type, boolean wall) {
        return switch (type.toLowerCase()) {
            case "spruce"   -> wall ? Blocks.SPRUCE_WALL_SIGN   : Blocks.SPRUCE_SIGN;
            case "birch"    -> wall ? Blocks.BIRCH_WALL_SIGN    : Blocks.BIRCH_SIGN;
            case "jungle"   -> wall ? Blocks.JUNGLE_WALL_SIGN   : Blocks.JUNGLE_SIGN;
            case "acacia"   -> wall ? Blocks.ACACIA_WALL_SIGN   : Blocks.ACACIA_SIGN;
            case "dark_oak" -> wall ? Blocks.DARK_OAK_WALL_SIGN : Blocks.DARK_OAK_SIGN;
            case "cherry"   -> wall ? Blocks.CHERRY_WALL_SIGN   : Blocks.CHERRY_SIGN;
            case "mangrove" -> wall ? Blocks.MANGROVE_WALL_SIGN : Blocks.MANGROVE_SIGN;
            case "bamboo"   -> wall ? Blocks.BAMBOO_WALL_SIGN   : Blocks.BAMBOO_SIGN;
            case "crimson"  -> wall ? Blocks.CRIMSON_WALL_SIGN  : Blocks.CRIMSON_SIGN;
            case "warped"   -> wall ? Blocks.WARPED_WALL_SIGN   : Blocks.WARPED_SIGN;
            default         -> wall ? Blocks.OAK_WALL_SIGN      : Blocks.OAK_SIGN;
        };
    }

    private int facingToStandingRotation(String facing) {
        return switch (facing.toLowerCase()) {
            case "south" -> 0;
            case "west"  -> 4;
            case "north" -> 8;
            case "east"  -> 12;
            default      -> 0;
        };
    }

    private net.minecraft.core.Direction parseMinecraftDirection(String s) {
        return switch (s.toLowerCase()) {
            case "north" -> net.minecraft.core.Direction.NORTH;
            case "south" -> net.minecraft.core.Direction.SOUTH;
            case "east"  -> net.minecraft.core.Direction.EAST;
            case "west"  -> net.minecraft.core.Direction.WEST;
            default      -> net.minecraft.core.Direction.NORTH;
        };
    }

    private DyeColor parseDyeColor(String s) {
        return switch (s.toLowerCase()) {
            case "white"      -> DyeColor.WHITE;
            case "red"        -> DyeColor.RED;
            case "green"      -> DyeColor.GREEN;
            case "blue"       -> DyeColor.BLUE;
            case "yellow"     -> DyeColor.YELLOW;
            case "cyan"       -> DyeColor.CYAN;
            case "light_blue" -> DyeColor.LIGHT_BLUE;
            case "magenta"    -> DyeColor.MAGENTA;
            case "orange"     -> DyeColor.ORANGE;
            case "pink"       -> DyeColor.PINK;
            case "purple"     -> DyeColor.PURPLE;
            case "brown"      -> DyeColor.BROWN;
            case "light_gray" -> DyeColor.LIGHT_GRAY;
            case "gray"       -> DyeColor.GRAY;
            default           -> DyeColor.BLACK;
        };
    }

    // ── Helpers ──

    private CuboidRegion cuboid(JsonObject a) {
        return new CuboidRegion(bv3(a, "pos1"), bv3(a, "pos2"));
    }

    private BlockVector3 bv3(JsonObject a, String key) {
        JsonObject v = a.getAsJsonObject(key);
        return BlockVector3.at(
                v.get("x").getAsInt(),
                v.get("y").getAsInt(),
                v.get("z").getAsInt());
    }

    private List<BlockVector3> parsePoints(JsonObject a, String key) {
        JsonArray arr = a.getAsJsonArray(key);
        List<BlockVector3> points = new ArrayList<>();
        for (JsonElement e : arr) {
            JsonObject p = e.getAsJsonObject();
            points.add(BlockVector3.at(
                    p.get("x").getAsInt(),
                    p.get("y").getAsInt(),
                    p.get("z").getAsInt()));
        }
        return points;
    }

    private BlockVector3 catmullRom(BlockVector3 p0, BlockVector3 p1, BlockVector3 p2, BlockVector3 p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;

        double x = 0.5 * ((2.0 * p1.x())
                + (-p0.x() + p2.x()) * t
                + (2.0 * p0.x() - 5.0 * p1.x() + 4.0 * p2.x() - p3.x()) * t2
                + (-p0.x() + 3.0 * p1.x() - 3.0 * p2.x() + p3.x()) * t3);
        double y = 0.5 * ((2.0 * p1.y())
                + (-p0.y() + p2.y()) * t
                + (2.0 * p0.y() - 5.0 * p1.y() + 4.0 * p2.y() - p3.y()) * t2
                + (-p0.y() + 3.0 * p1.y() - 3.0 * p2.y() + p3.y()) * t3);
        double z = 0.5 * ((2.0 * p1.z())
                + (-p0.z() + p2.z()) * t
                + (2.0 * p0.z() - 5.0 * p1.z() + 4.0 * p2.z() - p3.z()) * t2
                + (-p0.z() + 3.0 * p1.z() - 3.0 * p2.z() + p3.z()) * t3);

        return BlockVector3.at(Math.round(x), Math.round(y), Math.round(z));
    }

    private BlockPos pos(JsonObject a, String key) {
        if (!a.has(key) || a.get(key).isJsonNull()) return null;
        JsonObject v = a.getAsJsonObject(key);
        return new BlockPos(v.get("x").getAsInt(), v.get("y").getAsInt(), v.get("z").getAsInt());
    }

    private String str(JsonObject a, String key) {
        return a.has(key) ? a.get(key).getAsString() : "";
    }

    private Pattern parsePattern(World world, Actor actor, String raw) throws Exception {
        com.sk89q.worldedit.extension.input.ParserContext ctx =
                new com.sk89q.worldedit.extension.input.ParserContext();
        ctx.setWorld(world);
        ctx.setActor(actor);
        return WorldEdit.getInstance().getPatternFactory().parseFromInput(raw, ctx);
    }

    private Mask parseMask(World world, Actor actor, EditSession es, String raw) throws Exception {
        com.sk89q.worldedit.extension.input.ParserContext ctx =
                new com.sk89q.worldedit.extension.input.ParserContext();
        ctx.setWorld(world);
        ctx.setActor(actor);
        ctx.setExtent(es);
        return WorldEdit.getInstance().getMaskFactory().parseFromInput(raw, ctx);
    }

    private Direction parseDirection(String s) {
        return switch (s.toLowerCase()) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east"  -> Direction.EAST;
            case "west"  -> Direction.WEST;
            case "up"    -> Direction.UP;
            case "down"  -> Direction.DOWN;
            default      -> Direction.NORTH;
        };
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
