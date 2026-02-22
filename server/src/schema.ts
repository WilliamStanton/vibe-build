import { toolDefinition } from "@tanstack/ai";
import { z } from "zod";

// ── Planner tool schema ──

const planStepSchema = z.object({
	id: z.string().describe("Short kebab-case id like 'foundation' or 'roof'"),
	feature: z
		.string()
		.describe(
			"What this feature is — e.g. 'Stone brick foundation 12x10 at ground level'",
		),
	details: z
		.string()
		.describe(
			"Creative details for the executor — materials, dimensions, coordinates, block states, etc.",
		),
});

export const planSchema = z.object({
	planTitle: z.string().describe("Short title for the build"),
	origin: z.object({
		x: z.number().int(),
		y: z.number().int(),
		z: z.number().int(),
	}),
	steps: z
		.array(planStepSchema)
		.min(1)
		.describe("Ordered list of features to build"),
});

export type Plan = z.infer<typeof planSchema>;

export const submitPlanTool = toolDefinition({
	name: "submit_plan",
	description:
		"Submit the build plan. Call this exactly once with the complete feature list.",
	inputSchema: planSchema,
	outputSchema: z.object({ accepted: z.boolean() }),
});
