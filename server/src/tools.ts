import { toolDefinition } from "@tanstack/ai";
import { z } from "zod";

/**
 * Canonical WorldEdit tool schemas exposed to the executor model.
 *
 * These definitions are the contract between:
 * - backend LLM tool-calling
 * - mod-side command translation in ToolExecutor
 *
 * Keep names and argument semantics aligned with ToolExecutor switch cases.
 */

// ── Shared Schemas ──

const vec3 = z.object({
	x: z.number().describe("X coordinate (east/west)"),
	y: z.number().describe("Y coordinate (up/down)"),
	z: z.number().describe("Z coordinate (north/south)"),
});

const pattern = z
	.string()
	.describe(
		'WorldEdit block pattern. Examples: "stone" (single block), "50%stone,50%cobblestone" (random mix), "oak_stairs[facing=north]" (with block states), "#clipboard" (from clipboard)',
	);

const mask = z
	.string()
	.describe(
		'WorldEdit mask to filter blocks. Examples: "stone,dirt" (match specific blocks), "#existing" (all non-air), "!air" (everything except air), "##wool" (block tag), ">stone" (above stone)',
	);

const direction = z
	.enum(["north", "south", "east", "west", "up", "down"])
	.describe("Cardinal or vertical direction");

const coordinateMode = z
	.enum(["normalized", "raw", "offset", "center"])
	.optional()
	.describe(
		'"normalized" (default: -1..1), "raw" (-r: world coords), "offset" (-o: relative to placement), "center" (-c: selection center)',
	);

const weResult = z.object({
	success: z.boolean().describe("Whether the command executed successfully"),
	message: z.string().describe("Output message from WorldEdit"),
});

// ── Region Commands ──

export const set = toolDefinition({
	name: "set",
	description:
		"Set all blocks within a cuboid region to a pattern. This replaces EVERY block in the selection including air. Use we_replace if you only want to change specific block types.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the cuboid region"),
		pos2: vec3.describe("Opposite corner of the cuboid region"),
		pattern: pattern.describe("The block pattern to fill the region with"),
	}),
	outputSchema: weResult,
});

export const replace = toolDefinition({
	name: "we_replace",
	description:
		"Replace blocks matching a mask with a new pattern within a cuboid region. Only affects blocks that match the from mask — all other blocks are left untouched. If no mask is specified, replaces all non-air blocks.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the cuboid region"),
		pos2: vec3.describe("Opposite corner of the cuboid region"),
		from: mask
			.optional()
			.describe(
				'Mask of blocks to replace (e.g. "grass_block,dirt"). Omit to replace all non-air blocks',
			),
		to: pattern.describe("Pattern to replace matching blocks with"),
	}),
	outputSchema: weResult,
});

export const walls = toolDefinition({
	name: "we_walls",
	description:
		"Build the four vertical sides (walls) of a cuboid selection. Does NOT include ceiling or floor — only the north, south, east, and west faces. Use we_faces to include ceiling and floor.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the cuboid region"),
		pos2: vec3.describe("Opposite corner of the cuboid region"),
		pattern: pattern.describe("The block pattern for the walls"),
	}),
	outputSchema: weResult,
});

export const faces = toolDefinition({
	name: "we_faces",
	description:
		"Build all 6 faces (walls + ceiling + floor) of a cuboid selection, creating a hollow box outline. The interior is not modified. Also known as //outline.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the cuboid region"),
		pos2: vec3.describe("Opposite corner of the cuboid region"),
		pattern: pattern.describe("The block pattern for all faces"),
	}),
	outputSchema: weResult,
});

export const overlay = toolDefinition({
	name: "we_overlay",
	description:
		"Place a layer of blocks on top of the highest non-air block in each column within the selection. Useful for adding torches, fences, snow layers, or road surfaces on terrain.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the cuboid region"),
		pos2: vec3.describe("Opposite corner of the cuboid region"),
		pattern: pattern.describe(
			"The block pattern to overlay on top of existing terrain",
		),
	}),
	outputSchema: weResult,
});

export const center = toolDefinition({
	name: "we_center",
	description:
		"Set the center block(s) of a cuboid selection to a pattern. If any axis has even length, sets 2 blocks along that axis.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the cuboid region"),
		pos2: vec3.describe("Opposite corner of the cuboid region"),
		pattern: pattern.describe("Block pattern for the center"),
	}),
	outputSchema: weResult,
});

export const naturalize = toolDefinition({
	name: "we_naturalize",
	description:
		"Naturalize terrain in the selection: creates 1 layer of grass on top, 3 layers of dirt underneath, then stone below. Makes artificial terrain look natural.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the region to naturalize"),
		pos2: vec3.describe("Opposite corner of the region to naturalize"),
	}),
	outputSchema: weResult,
});

export const line = toolDefinition({
	name: "we_line",
	description:
		"Draw a straight line of blocks between two points. Use hollow flag to create a hollow tube instead of a solid line.",
	inputSchema: z.object({
		pos1: vec3.describe("Start point of the line"),
		pos2: vec3.describe("End point of the line"),
		pattern: pattern.describe("Block pattern for the line"),
		thickness: z
			.number()
			.int()
			.min(0)
			.optional()
			.describe("Thickness of the line (default: 0 = 1 block wide)"),
		hollow: z
			.boolean()
			.optional()
			.describe("If true, create a hollow tube instead of solid line"),
	}),
	outputSchema: weResult,
});

export const curve = toolDefinition({
	name: "we_curve",
	description:
		"Draw a smooth spline curve through multiple control points. Points are connected in order with a smooth Catmull-Rom spline. Requires minimum 3 points.",
	inputSchema: z.object({
		points: z
			.array(vec3)
			.min(3)
			.describe(
				"Ordered control points for the curve (minimum 3). The curve passes through each point in sequence.",
			),
		pattern: pattern.describe("Block pattern for the curve"),
		thickness: z
			.number()
			.int()
			.min(0)
			.optional()
			.describe("Thickness of the curve (default: 0 = 1 block wide)"),
		hollow: z
			.boolean()
			.optional()
			.describe("If true, create a hollow tube instead of solid curve"),
	}),
	outputSchema: weResult,
});

export const move = toolDefinition({
	name: "we_move",
	description:
		"Move the contents of a cuboid selection in a direction by a given distance. The area left behind is filled with air (or a specified pattern). Useful for repositioning structures.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the region to move"),
		pos2: vec3.describe("Opposite corner of the region to move"),
		distance: z.number().int().min(1).describe("Number of blocks to move"),
		direction: direction.describe("Direction to move the contents"),
		leavePattern: pattern
			.optional()
			.describe("Pattern to fill the vacated area with (default: air)"),
		ignoreAir: z
			.boolean()
			.optional()
			.describe("If true, skip air blocks when moving"),
		shiftSelection: z
			.boolean()
			.optional()
			.describe("If true, move the selection to the new location"),
		mask: mask
			.optional()
			.describe("Only move blocks matching this mask, non-matching become air"),
	}),
	outputSchema: weResult,
});

export const stack = toolDefinition({
	name: "we_stack",
	description:
		"Repeat/stack the contents of a cuboid selection multiple times in a given direction. Each copy is placed adjacent to the previous one. Great for extending bridges, tunnels, fences, or repeating architectural segments.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the region to stack"),
		pos2: vec3.describe("Opposite corner of the region to stack"),
		count: z
			.number()
			.int()
			.min(1)
			.describe("Number of times to repeat the selection"),
		direction: direction.describe("Direction to stack copies toward"),
		ignoreAir: z
			.boolean()
			.optional()
			.describe("If true, air blocks in the source are not copied"),
		shiftSelection: z
			.boolean()
			.optional()
			.describe("If true, move the selection to the last stacked copy"),
		mask: mask
			.optional()
			.describe(
				"Only stack blocks matching this mask, non-matching become air",
			),
	}),
	outputSchema: weResult,
});

export const smooth = toolDefinition({
	name: "we_smooth",
	description:
		"Smooth the elevation/terrain within a selection using a heightmap algorithm. Best for outdoor terrain — NOT suitable for smoothing caves, walls, or vertical surfaces. More iterations = smoother result.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the region to smooth"),
		pos2: vec3.describe("Opposite corner of the region to smooth"),
		iterations: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe(
				"Number of smoothing iterations (default: 1). Higher = smoother",
			),
		mask: mask
			.optional()
			.describe(
				'Only use blocks matching this mask as the heightmap (e.g. "grass_block,dirt,stone" for natural terrain)',
			),
	}),
	outputSchema: weResult,
});

export const hollow = toolDefinition({
	name: "we_hollow",
	description:
		"Hollow out solid objects within the selection, leaving only a shell of the specified thickness. Interior is filled with air (or a specified pattern). Thickness is measured in manhattan distance.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the region"),
		pos2: vec3.describe("Opposite corner of the region"),
		thickness: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Shell thickness in blocks (default: 1)"),
		fillPattern: pattern
			.optional()
			.describe("Pattern to fill the hollowed interior with (default: air)"),
	}),
	outputSchema: weResult,
});

export const forest = toolDefinition({
	name: "we_forest",
	description:
		"Plant a forest of trees within the selection region. Trees are placed on valid surfaces within the area. The default density (5) is already fairly dense.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the region to plant trees in"),
		pos2: vec3.describe("Opposite corner of the region to plant trees in"),
		treeType: z
			.string()
			.optional()
			.describe(
				"Tree type: oak, birch, spruce, jungle, acacia, dark_oak, etc.",
			),
		density: z
			.number()
			.min(0)
			.max(100)
			.optional()
			.describe("Density of tree placement (0-100, default: 5)"),
	}),
	outputSchema: weResult,
});

export const flora = toolDefinition({
	name: "we_flora",
	description:
		"Scatter flora (tall grass, flowers) on grass blocks, and cacti/dead grass on sand within the selection. Works like overlay — places on top of existing terrain.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the region"),
		pos2: vec3.describe("Opposite corner of the region"),
		density: z
			.number()
			.min(0)
			.max(100)
			.optional()
			.describe("Density of flora placement (0-100)"),
	}),
	outputSchema: weResult,
});

export const deform = toolDefinition({
	name: "we_deform",
	description:
		'Deform blocks in a region using a mathematical expression. The expression modifies x, y, z variables to point to a new block to fetch. Example: "y-=1" shifts all blocks up by one. "swap(x,z)" rotates 90 degrees.',
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the region to deform"),
		pos2: vec3.describe("Opposite corner of the region to deform"),
		expression: z
			.string()
			.describe(
				'Deformation expression. Modifies x/y/z to indicate source block. E.g. "y-=1" (shift up), "x+=0.5*sin(y*pi/10)" (wave)',
			),
		coordinateMode,
	}),
	outputSchema: weResult,
});

export const revolve = toolDefinition({
	name: "we_revolve",
	description:
		"Revolve (radially repeat) the selection around a vertical axis. Creates rotational symmetry — e.g. build one quarter of a circular tower, then revolve with pasteCount=4 to complete the circle.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the region to revolve"),
		pos2: vec3.describe("Opposite corner of the region to revolve"),
		pasteCount: z
			.number()
			.int()
			.min(2)
			.describe(
				"Number of copies around the circle (e.g. 4 for quarter-symmetry, 8 for octagonal)",
			),
		reverse: z
			.boolean()
			.optional()
			.describe("If true, revolve counter-clockwise"),
		mask: mask.optional().describe("Only revolve blocks matching this mask"),
	}),
	outputSchema: weResult,
});

// ── Generation Commands ──

export const cyl = toolDefinition({
	name: "we_cyl",
	description:
		"Generate a filled cylinder at a position. The cylinder extends upward from the center point. Can create elliptical cylinders by specifying different N/S and E/W radii. For a flat circle, set height to 1.",
	inputSchema: z.object({
		center: vec3.describe(
			"Center base point of the cylinder (it extends upward from here)",
		),
		pattern: pattern.describe("Block pattern for the cylinder"),
		radiusNS: z.number().min(0).describe("Radius in the north/south direction"),
		radiusEW: z
			.number()
			.min(0)
			.optional()
			.describe(
				"Radius in the east/west direction (default: same as radiusNS for circular)",
			),
		height: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Height of the cylinder in blocks (default: 1 = flat circle)"),
		hollow: z
			.boolean()
			.optional()
			.describe("If true, create a hollow cylinder"),
	}),
	outputSchema: weResult,
});

export const sphere = toolDefinition({
	name: "we_sphere",
	description:
		"Generate a filled sphere at a position. Can create ellipsoids with different radii per axis (N/S, U/D, E/W).",
	inputSchema: z.object({
		center: vec3.describe("Center point of the sphere"),
		pattern: pattern.describe("Block pattern for the sphere"),
		radiusNS: z.number().min(0).describe("Radius in the north/south direction"),
		radiusUD: z
			.number()
			.min(0)
			.optional()
			.describe("Radius in the up/down direction (default: same as radiusNS)"),
		radiusEW: z
			.number()
			.min(0)
			.optional()
			.describe(
				"Radius in the east/west direction (default: same as radiusNS)",
			),
		hollow: z.boolean().optional().describe("If true, create a hollow sphere"),
		raise: z
			.boolean()
			.optional()
			.describe(
				"If true, raise the sphere so its bottom is at the center position",
			),
	}),
	outputSchema: weResult,
});

export const pyramid = toolDefinition({
	name: "we_pyramid",
	description:
		"Generate a filled pyramid at a position. The size parameter sets the height — each layer is 1 block shorter than the one below in each direction. Base width = 2 x size.",
	inputSchema: z.object({
		center: vec3.describe("Center base point of the pyramid"),
		pattern: pattern.describe("Block pattern for the pyramid"),
		size: z
			.number()
			.int()
			.min(1)
			.describe("Height of the pyramid in layers (base width = 2 x size)"),
		hollow: z.boolean().optional().describe("If true, create a hollow pyramid"),
	}),
	outputSchema: weResult,
});

export const cone = toolDefinition({
	name: "we_cone",
	description:
		"Generate a cone at a position. Similar to a pyramid but with circular cross-sections. Supports elliptical base with different N/S and E/W radii.",
	inputSchema: z.object({
		center: vec3.describe("Center base point of the cone"),
		pattern: pattern.describe("Block pattern for the cone"),
		radiusNS: z
			.number()
			.min(0)
			.describe("Base radius in the north/south direction"),
		radiusEW: z
			.number()
			.min(0)
			.optional()
			.describe(
				"Base radius in the east/west direction (default: same as radiusNS)",
			),
		height: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Height of the cone in blocks"),
		hollow: z.boolean().optional().describe("If true, create a hollow cone"),
		thickness: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Wall thickness for hollow cone"),
	}),
	outputSchema: weResult,
});

export const generate = toolDefinition({
	name: "we_generate",
	description:
		"Generate an arbitrary shape within a selection using a mathematical expression. The expression is evaluated for each block — return >0 for blocks that should be part of the shape, <=0 for empty. Powerful for creating tori, organic shapes, and anything describable with math.",
	inputSchema: z.object({
		pos1: vec3.describe("First corner of the bounding region"),
		pos2: vec3.describe("Opposite corner of the bounding region"),
		pattern: pattern.describe("Block pattern for solid parts of the shape"),
		expression: z
			.string()
			.describe(
				'Math expression returning >0 for solid blocks. Variables: x, y, z. E.g. torus: "(x^2+z^2-4)^2+y^2<1.5"',
			),
		hollow: z
			.boolean()
			.optional()
			.describe("If true, only generate the outer shell"),
		coordinateMode,
	}),
	outputSchema: weResult,
});

export const forestGen = toolDefinition({
	name: "we_forestgen",
	description:
		"Generate a forest in a square area centered on a position. Trees are planted on valid ground within the area. Different from we_forest which uses a selection — this radiates outward from a point.",
	inputSchema: z.object({
		center: vec3.describe("Center point of the forest area"),
		size: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Radius of the square area in blocks"),
		treeType: z
			.string()
			.optional()
			.describe(
				"Tree type: oak, birch, spruce, jungle, acacia, dark_oak, etc.",
			),
		density: z
			.number()
			.min(0)
			.max(100)
			.optional()
			.describe(
				"Density of tree placement (0-100, default: 5). Even 5 is fairly dense.",
			),
	}),
	outputSchema: weResult,
});

// ── History Commands ──

export const undo = toolDefinition({
	name: "we_undo",
	description:
		"Undo the last WorldEdit action(s). Can undo multiple actions at once by specifying a count.",
	inputSchema: z.object({
		times: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Number of actions to undo (default: 1)"),
	}),
	outputSchema: weResult,
});

export const redo = toolDefinition({
	name: "we_redo",
	description:
		"Redo the last undone WorldEdit action(s). Can redo multiple actions at once.",
	inputSchema: z.object({
		times: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Number of actions to redo (default: 1)"),
	}),
	outputSchema: weResult,
});

// ── Utility Commands ──

export const fill = toolDefinition({
	name: "we_fill",
	description:
		"Fill a hole/pit by filling air blocks downward from a position within a radius. Works straight down from the starting layer — fills ponds and pits but NOT caves that expand sideways. Will not fill upward.",
	inputSchema: z.object({
		position: vec3.describe(
			"Starting position (fills downward and outward from here)",
		),
		pattern: pattern.describe("Block pattern to fill with"),
		radius: z
			.number()
			.int()
			.min(1)
			.describe("Horizontal radius to fill within"),
		depth: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Maximum depth to fill downward"),
	}),
	outputSchema: weResult,
});

export const fillRecursive = toolDefinition({
	name: "we_fill_recursive",
	description:
		"Recursively fill a hole by following connected air spaces downward. Unlike we_fill, this WILL follow caves and openings that expand sideways as they go deeper. Still will not fill upward past the starting Y level.",
	inputSchema: z.object({
		position: vec3.describe("Starting position"),
		pattern: pattern.describe("Block pattern to fill with"),
		radius: z.number().int().min(1).describe("Maximum radius to fill within"),
		depth: z.number().int().min(1).optional().describe("Maximum depth to fill"),
	}),
	outputSchema: weResult,
});

export const snow = toolDefinition({
	name: "we_snow",
	description:
		"Simulate snowfall over an area. Snow is placed on exposed surfaces that can logically hold snow. Snowfall is purely vertical — overhangs block snow below them.",
	inputSchema: z.object({
		position: vec3.describe("Center position for snowfall"),
		radius: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Horizontal radius of the snow area"),
		height: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Vertical extent of the snow cylinder"),
		stackLayers: z
			.boolean()
			.optional()
			.describe("If true, stack snow layers for varying depth"),
	}),
	outputSchema: weResult,
});

export const green = toolDefinition({
	name: "we_green",
	description:
		"Convert exposed dirt blocks to grass blocks in an area. Useful for greening up terrain after edits.",
	inputSchema: z.object({
		position: vec3.describe("Center position for greening"),
		radius: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Horizontal radius of the area"),
		height: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Vertical extent of the area"),
		includeCoarseDirt: z
			.boolean()
			.optional()
			.describe("If true, also convert coarse dirt to grass"),
	}),
	outputSchema: weResult,
});

export const removeAbove = toolDefinition({
	name: "we_remove_above",
	description:
		"Remove all blocks above a position in a square column. Useful for clearing towers, removing overhangs, or creating open sky.",
	inputSchema: z.object({
		position: vec3.describe("Base position (removes blocks above this point)"),
		size: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Apothem (half-width) of the square column"),
		height: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Maximum height to remove (default: to world top)"),
	}),
	outputSchema: weResult,
});

export const removeBelow = toolDefinition({
	name: "we_remove_below",
	description:
		"Remove all blocks below a position in a square column. Useful for clearing ground, creating pits, or removing foundations.",
	inputSchema: z.object({
		position: vec3.describe("Base position (removes blocks below this point)"),
		size: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Apothem (half-width) of the square column"),
		depth: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Maximum depth to remove (default: to world bottom)"),
	}),
	outputSchema: weResult,
});

export const removeNear = toolDefinition({
	name: "we_remove_near",
	description:
		"Remove all blocks matching a mask near a position. Useful for quickly cleaning up specific block types (e.g. removing all torches, flowers, or scaffolding in an area).",
	inputSchema: z.object({
		position: vec3.describe("Center position"),
		mask: mask.describe(
			'Mask of blocks to remove (e.g. "torch", "scaffolding", "water")',
		),
		radius: z
			.number()
			.int()
			.min(1)
			.optional()
			.describe("Apothem of the square area"),
	}),
	outputSchema: weResult,
});

export const replaceNear = toolDefinition({
	name: "we_replace_near",
	description:
		"Replace blocks matching a mask with a pattern near a position. A quick alternative to making a selection and using we_replace.",
	inputSchema: z.object({
		position: vec3.describe("Center position"),
		radius: z.number().int().min(1).describe("Apothem of the square area"),
		from: mask
			.optional()
			.describe("Mask of blocks to replace (omit to replace all non-air)"),
		to: pattern.describe("Pattern to replace matching blocks with"),
	}),
	outputSchema: weResult,
});

// ── Sign Placement ──

export const placeSign = toolDefinition({
	name: "place_sign",
	description:
		"Place a sign at a specific position with custom text. Use this instead of we_set for signs, since signs require special text handling that WorldEdit cannot do. Supports both wall-mounted and standing signs in any wood type.",
	inputSchema: z.object({
		position: vec3.describe("Position to place the sign"),
		signType: z
			.enum([
				"oak",
				"spruce",
				"birch",
				"jungle",
				"acacia",
				"dark_oak",
				"cherry",
				"mangrove",
				"bamboo",
				"crimson",
				"warped",
			])
			.describe("Wood type for the sign"),
		wallMounted: z
			.boolean()
			.describe(
				"true = wall sign (attached to a block face), false = standing sign (on top of a block)",
			),
		facing: z
			.enum(["north", "south", "east", "west"])
			.describe(
				"For wall signs: the direction the sign face points (away from the wall). For standing signs: the direction the sign text faces.",
			),
		frontLines: z
			.array(z.string())
			.max(4)
			.describe(
				"Up to 4 lines of text for the front of the sign. Empty strings for blank lines.",
			),
		backLines: z
			.array(z.string())
			.max(4)
			.optional()
			.describe("Up to 4 lines of text for the back of the sign (optional)"),
		glowing: z
			.boolean()
			.optional()
			.describe("If true, the sign text glows (like after using a glow ink sac)"),
		color: z
			.enum([
				"black",
				"white",
				"red",
				"green",
				"blue",
				"yellow",
				"cyan",
				"light_blue",
				"magenta",
				"orange",
				"pink",
				"purple",
				"brown",
				"light_gray",
				"gray",
			])
			.optional()
			.describe("Dye color for the sign text (default: black)"),
	}),
	outputSchema: weResult,
});

// ── Export ──

// Keep this list explicit and grouped so prompt/tool auditing is straightforward.
export const allWorldEditTools = [
	// Region
	set,
	replace,
	walls,
	faces,
	overlay,
	center,
	naturalize,
	line,
	curve,
	move,
	stack,
	smooth,
	hollow,
	deform,
	// Generation
	cyl,
	sphere,
	pyramid,
	cone,
	generate,
	// Utility
	fill,
	removeNear,
	replaceNear,
	// Sign placement
	placeSign,
];
