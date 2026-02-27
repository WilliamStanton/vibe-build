package com.vibebuild.ai;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;

import java.util.List;

/**
 * Defines all langchain4j ToolSpecification objects for the AI pipeline.
 * Matches the tool contracts that ToolExecutor can handle.
 */
public class ToolSpecs {

    // ── Shared schema fragments ──

    private static JsonObjectSchema vec3(String desc) {
        return JsonObjectSchema.builder()
                .description(desc)
                .addIntegerProperty("x", "X coordinate (east/west)")
                .addIntegerProperty("y", "Y coordinate (up/down)")
                .addIntegerProperty("z", "Z coordinate (north/south)")
                .required("x", "y", "z")
                .build();
    }

    private static final JsonEnumSchema DIRECTION = JsonEnumSchema.builder()
            .enumValues("north", "south", "east", "west", "up", "down")
            .description("Cardinal or vertical direction")
            .build();

    private static ToolSpecification tool(String name, String desc, JsonObjectSchema params) {
        return ToolSpecification.builder().name(name).description(desc).parameters(params).build();
    }

    // ── Planner tool ──

    public static ToolSpecification submitPlan() {
        JsonObjectSchema stepSchema = JsonObjectSchema.builder()
                .addStringProperty("id", "Short kebab-case id like 'foundation' or 'roof'")
                .addStringProperty("feature", "What this feature is")
                .addStringProperty("details", "Creative details for the executor — materials, dimensions, coordinates, block states, etc.")
                .required("id", "feature", "details")
                .build();

        return tool("submit_plan",
                "Submit the build plan. Call this exactly once with the complete feature list.",
                JsonObjectSchema.builder()
                        .addStringProperty("planTitle", "Short title for the build")
                        .addProperty("origin", vec3("Build origin coordinates"))
                        .addProperty("steps", JsonArraySchema.builder()
                                .items(stepSchema)
                                .description("Ordered list of features to build")
                                .build())
                        .required("planTitle", "origin", "steps")
                        .build());
    }

    // ── WorldEdit tools ──

    public static List<ToolSpecification> worldEditTools() {
        return List.of(
                // Region
                tool("set",
                        "Set all blocks within a cuboid region to a pattern. Replaces EVERY block including air.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .addStringProperty("pattern", "Block pattern to fill with")
                                .required("pos1", "pos2", "pattern").build()),

                tool("we_replace",
                        "Replace blocks matching a mask with a new pattern within a cuboid region.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .addStringProperty("from", "Mask of blocks to replace (omit to replace all non-air)")
                                .addStringProperty("to", "Pattern to replace with")
                                .required("pos1", "pos2", "to").build()),

                tool("we_walls",
                        "Build the four vertical sides (walls) of a cuboid selection. No ceiling or floor.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .addStringProperty("pattern", "Block pattern for walls")
                                .required("pos1", "pos2", "pattern").build()),

                tool("we_faces",
                        "Build all 6 faces of a cuboid selection (hollow box). Interior not modified.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .addStringProperty("pattern", "Block pattern for faces")
                                .required("pos1", "pos2", "pattern").build()),

                tool("we_overlay",
                        "Place a layer of blocks on top of the highest non-air block in each column.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .addStringProperty("pattern", "Block pattern to overlay")
                                .required("pos1", "pos2", "pattern").build()),

                tool("we_center",
                        "Set the center block(s) of a cuboid selection to a pattern.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .addStringProperty("pattern", "Block pattern for center")
                                .required("pos1", "pos2", "pattern").build()),

                tool("we_naturalize",
                        "Naturalize terrain: grass on top, dirt underneath, then stone.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .required("pos1", "pos2").build()),

                tool("we_line",
                        "Draw a straight line of blocks between two points.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("Start point"))
                                .addProperty("pos2", vec3("End point"))
                                .addStringProperty("pattern", "Block pattern")
                                .addIntegerProperty("thickness", "Line thickness (default 0 = 1 block)")
                                .addBooleanProperty("hollow", "If true, hollow tube instead of solid")
                                .required("pos1", "pos2", "pattern").build()),

                tool("we_curve",
                        "Draw a smooth spline curve through multiple control points (min 3).",
                        JsonObjectSchema.builder()
                                .addProperty("points", JsonArraySchema.builder()
                                        .items(vec3("Control point"))
                                        .description("Ordered control points (minimum 3)")
                                        .build())
                                .addStringProperty("pattern", "Block pattern")
                                .addIntegerProperty("thickness", "Curve thickness (default 0)")
                                .addBooleanProperty("hollow", "If true, hollow tube")
                                .required("points", "pattern").build()),

                tool("we_move",
                        "Move contents of a cuboid selection in a direction by a given distance.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .addIntegerProperty("distance", "Blocks to move")
                                .addProperty("direction", DIRECTION)
                                .addStringProperty("leavePattern", "Pattern for vacated area (default air)")
                                .addBooleanProperty("ignoreAir", "Skip air blocks when moving")
                                .required("pos1", "pos2", "distance", "direction").build()),

                tool("we_stack",
                        "Repeat/stack cuboid contents multiple times in a direction.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .addIntegerProperty("count", "Number of repetitions")
                                .addProperty("direction", DIRECTION)
                                .addBooleanProperty("ignoreAir", "Skip air blocks")
                                .required("pos1", "pos2", "count", "direction").build()),

                tool("we_smooth",
                        "Smooth elevation/terrain within a selection using heightmap algorithm.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .addIntegerProperty("iterations", "Smoothing iterations (default 1)")
                                .addStringProperty("mask", "Only smooth blocks matching this mask")
                                .required("pos1", "pos2").build()),

                tool("we_hollow",
                        "Hollow out solid objects, leaving only a shell of specified thickness.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .addIntegerProperty("thickness", "Shell thickness (default 1)")
                                .addStringProperty("fillPattern", "Interior fill pattern (default air)")
                                .required("pos1", "pos2").build()),

                tool("we_deform",
                        "Deform blocks using a math expression. E.g. \"y-=1\" shifts blocks up.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .addStringProperty("expression", "Deformation expression modifying x/y/z")
                                .required("pos1", "pos2", "expression").build()),

                // Generation
                tool("we_cyl",
                        "Generate a filled cylinder. For a flat circle, set height to 1.",
                        JsonObjectSchema.builder()
                                .addProperty("center", vec3("Center base point"))
                                .addStringProperty("pattern", "Block pattern")
                                .addNumberProperty("radiusNS", "Radius north/south")
                                .addNumberProperty("radiusEW", "Radius east/west (default same as radiusNS)")
                                .addIntegerProperty("height", "Height in blocks (default 1)")
                                .addBooleanProperty("hollow", "If true, hollow cylinder")
                                .required("center", "pattern", "radiusNS").build()),

                tool("we_sphere",
                        "Generate a filled sphere. Can create ellipsoids with different radii.",
                        JsonObjectSchema.builder()
                                .addProperty("center", vec3("Center point"))
                                .addStringProperty("pattern", "Block pattern")
                                .addNumberProperty("radiusNS", "Radius north/south")
                                .addNumberProperty("radiusUD", "Radius up/down (default same)")
                                .addNumberProperty("radiusEW", "Radius east/west (default same)")
                                .addBooleanProperty("hollow", "If true, hollow sphere")
                                .required("center", "pattern", "radiusNS").build()),

                tool("we_pyramid",
                        "Generate a filled pyramid. Base width = 2 x size.",
                        JsonObjectSchema.builder()
                                .addProperty("center", vec3("Center base point"))
                                .addStringProperty("pattern", "Block pattern")
                                .addIntegerProperty("size", "Height in layers")
                                .addBooleanProperty("hollow", "If true, hollow pyramid")
                                .required("center", "pattern", "size").build()),

                tool("we_cone",
                        "Generate a cone with circular cross-sections.",
                        JsonObjectSchema.builder()
                                .addProperty("center", vec3("Center base point"))
                                .addStringProperty("pattern", "Block pattern")
                                .addNumberProperty("radiusNS", "Base radius north/south")
                                .addNumberProperty("radiusEW", "Base radius east/west (default same)")
                                .addIntegerProperty("height", "Height in blocks")
                                .addBooleanProperty("hollow", "If true, hollow cone")
                                .addIntegerProperty("thickness", "Wall thickness for hollow cone")
                                .required("center", "pattern", "radiusNS").build()),

                tool("we_generate",
                        "Generate arbitrary shape using math expression. Return >0 for solid blocks.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner of bounding region"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .addStringProperty("pattern", "Block pattern for solid parts")
                                .addStringProperty("expression", "Math expression returning >0 for solid blocks")
                                .addBooleanProperty("hollow", "If true, only outer shell")
                                .required("pos1", "pos2", "pattern", "expression").build()),

                // Utility
                tool("we_fill",
                        "Fill a hole/pit by filling air blocks downward from a position within a radius.",
                        JsonObjectSchema.builder()
                                .addProperty("position", vec3("Starting position"))
                                .addStringProperty("pattern", "Block pattern to fill with")
                                .addIntegerProperty("radius", "Horizontal radius")
                                .addIntegerProperty("depth", "Maximum depth to fill")
                                .required("position", "pattern", "radius").build()),

                tool("we_remove_near",
                        "Remove all blocks matching a mask near a position.",
                        JsonObjectSchema.builder()
                                .addProperty("position", vec3("Center position"))
                                .addStringProperty("mask", "Mask of blocks to remove")
                                .addIntegerProperty("radius", "Apothem of square area")
                                .required("position", "mask").build()),

                tool("we_replace_near",
                        "Replace blocks matching a mask with a pattern near a position.",
                        JsonObjectSchema.builder()
                                .addProperty("position", vec3("Center position"))
                                .addIntegerProperty("radius", "Apothem of square area")
                                .addStringProperty("from", "Mask of blocks to replace")
                                .addStringProperty("to", "Pattern to replace with")
                                .required("position", "radius", "to").build()),

                // Sign placement
                tool("place_sign",
                        "Place a sign with custom text. Use this instead of set for signs.",
                        JsonObjectSchema.builder()
                                .addProperty("position", vec3("Position to place the sign"))
                                .addProperty("signType", JsonEnumSchema.builder()
                                        .enumValues("oak", "spruce", "birch", "jungle", "acacia",
                                                "dark_oak", "cherry", "mangrove", "bamboo", "crimson", "warped")
                                        .description("Wood type for the sign").build())
                                .addBooleanProperty("wallMounted", "true = wall sign, false = standing sign")
                                .addProperty("facing", JsonEnumSchema.builder()
                                        .enumValues("north", "south", "east", "west")
                                        .description("Direction the sign text faces").build())
                                .addProperty("frontLines", JsonArraySchema.builder()
                                        .items(JsonStringSchema.builder().build())
                                        .description("Up to 4 lines of front text").build())
                                .addProperty("backLines", JsonArraySchema.builder()
                                        .items(JsonStringSchema.builder().build())
                                        .description("Up to 4 lines of back text (optional)").build())
                                .addBooleanProperty("glowing", "If true, sign text glows")
                                .addProperty("color", JsonEnumSchema.builder()
                                        .enumValues("black", "white", "red", "green", "blue", "yellow",
                                                "cyan", "light_blue", "magenta", "orange", "pink", "purple",
                                                "brown", "light_gray", "gray")
                                        .description("Dye color for text (default black)").build())
                                .required("position", "signType", "wallMounted", "facing", "frontLines").build())
        );
    }
}
