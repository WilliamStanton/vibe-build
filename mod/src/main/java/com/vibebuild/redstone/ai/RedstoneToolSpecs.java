package com.vibebuild.redstone.ai;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;

import java.util.List;

/**
 * Defines tool specifications for the redstone AI pipeline.
 * Includes shared WorldEdit tools plus redstone-specific tools.
 */
public class RedstoneToolSpecs {

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
                .addStringProperty("id", "Short kebab-case id like 'input-lever' or 'signal-line'")
                .addStringProperty("feature", "What this circuit subsystem is")
                .addStringProperty("details", "Precise details — component types, positions, facing, delays, signal path, support blocks")
                .required("id", "feature", "details")
                .build();

        return tool("submit_plan",
                "Submit the redstone circuit plan. Call this exactly once with the complete plan.",
                JsonObjectSchema.builder()
                        .addStringProperty("planTitle", "Short title for the circuit")
                        .addProperty("origin", vec3("Circuit origin coordinates"))
                        .addProperty("steps", JsonArraySchema.builder()
                                .items(stepSchema)
                                .description("Ordered list of circuit subsystems to build (signal flow order: inputs → logic → transmission → output)")
                                .build())
                        .required("planTitle", "origin", "steps")
                        .build());
    }

    // ── Redstone tools ──

    public static List<ToolSpecification> redstoneTools() {
        return List.of(
                // Shared WorldEdit tools (useful for bulk operations and support structures)
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

                tool("we_line",
                        "Draw a straight line of blocks between two points.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("Start point"))
                                .addProperty("pos2", vec3("End point"))
                                .addStringProperty("pattern", "Block pattern")
                                .addIntegerProperty("thickness", "Line thickness (default 0 = 1 block)")
                                .addBooleanProperty("hollow", "If true, hollow tube instead of solid")
                                .required("pos1", "pos2", "pattern").build()),

                tool("we_stack",
                        "Repeat/stack cuboid contents multiple times in a direction.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .addIntegerProperty("count", "Number of repetitions")
                                .addProperty("direction", DIRECTION)
                                .addBooleanProperty("ignoreAir", "Skip air blocks")
                                .required("pos1", "pos2", "count", "direction").build()),

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

                // ── Redstone-specific tools ──

                tool("place_redstone_component",
                        "Place a single redstone component at a position with full block state control. "
                                + "Use this for precise placement of repeaters, comparators, pistons, observers, etc. "
                                + "The blockState string sets properties like facing, delay, mode.",
                        JsonObjectSchema.builder()
                                .addProperty("position", vec3("Position to place the component"))
                                .addProperty("component", JsonEnumSchema.builder()
                                        .enumValues("redstone_wire", "redstone_torch", "redstone_wall_torch",
                                                "repeater", "comparator",
                                                "piston", "sticky_piston", "observer",
                                                "dropper", "dispenser", "hopper",
                                                "lever", "stone_button", "oak_button",
                                                "redstone_lamp", "target", "daylight_detector",
                                                "tripwire_hook", "trapped_chest", "note_block", "tnt",
                                                "redstone_block", "slime_block", "honey_block")
                                        .description("Redstone component type").build())
                                .addStringProperty("blockState",
                                        "Block state properties as key=value pairs separated by commas. "
                                                + "Examples: 'facing=north,delay=2' for repeater, "
                                                + "'facing=south,mode=subtract' for comparator, "
                                                + "'facing=up,extended=false' for piston, "
                                                + "'face=wall,facing=north' for lever/button. "
                                                + "Omit for defaults.")
                                .required("position", "component").build()),

                tool("place_redstone_line",
                        "Place a straight line of redstone dust on solid support blocks from pos1 to pos2. "
                                + "Automatically places support blocks beneath each dust position if missing. "
                                + "Only works for axis-aligned lines (along X or Z at same Y).",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("Start position (where dust sits, not the support block)"))
                                .addProperty("pos2", vec3("End position"))
                                .addStringProperty("supportBlock", "Block to place beneath dust if needed (default: stone)")
                                .required("pos1", "pos2").build()),

                tool("place_repeater_chain",
                        "Place a chain of repeaters with redstone dust between them along a straight line. "
                                + "Automatically places support blocks beneath. Repeaters face the signal flow direction.",
                        JsonObjectSchema.builder()
                                .addProperty("start", vec3("Starting position"))
                                .addProperty("direction", JsonEnumSchema.builder()
                                        .enumValues("north", "south", "east", "west")
                                        .description("Direction of signal flow").build())
                                .addIntegerProperty("count", "Number of repeaters to place")
                                .addIntegerProperty("delay", "Repeater delay 1-4 ticks (default 1)")
                                .addIntegerProperty("spacing", "Blocks of redstone dust between repeaters (default 14, max 14)")
                                .addStringProperty("supportBlock", "Block beneath components (default: stone)")
                                .required("start", "direction", "count").build()),

                tool("read_block",
                        "Read one block at a position for verification. "
                                + "Returns structured JSON with x/y/z, isAir, blockId, and full state string.",
                        JsonObjectSchema.builder()
                                .addProperty("position", vec3("Position to read"))
                                .required("position").build()),

                tool("read_region",
                        "Read non-air blocks in a cuboid region for auditing. "
                                + "Returns structured JSON with countsByBlock, returned blocks, and truncation metadata.",
                        JsonObjectSchema.builder()
                                .addProperty("pos1", vec3("First corner"))
                                .addProperty("pos2", vec3("Opposite corner"))
                                .addIntegerProperty("maxBlocks", "Maximum block entries to return (default 1000, max 5000)")
                                .required("pos1", "pos2").build())
        );
    }
}
