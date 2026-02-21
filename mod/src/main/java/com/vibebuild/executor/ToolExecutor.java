package com.vibebuild.executor;

import com.google.gson.JsonObject;
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
import net.minecraft.server.level.ServerPlayer;

/**
 * Routes tool_call messages from the vibe-build server to the WorldEdit Java API.
 */
public class ToolExecutor {

    public JsonObject execute(ServerPlayer player, BuildSession session, String toolName, JsonObject args) {
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
            case "we_move"         -> execMove(es, world, actor, a);
            case "we_stack"        -> execStack(es, world, a);
            case "we_smooth"       -> execSmooth(es, world, a);
            case "we_hollow"       -> execHollow(es, world, actor, a);
            case "we_deform"       -> execDeform(es, world, a);
            case "we_cyl"          -> execCyl(es, world, actor, a);
            case "we_sphere"       -> execSphere(es, world, actor, a);
            case "we_pyramid"      -> execPyramid(es, world, actor, a);
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
