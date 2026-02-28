package com.vibebuild.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a build plan produced by the planner stage.
 */
public class Plan {

    private static final Gson GSON = new Gson();

    /** Human-readable title for the build, provided by the planner. */
    public final String planTitle;
    /** World coordinates where the build is anchored (always {@code (0, 64, 0)} in the build dimension). */
    public final Origin origin;
    /** Ordered list of build steps to execute sequentially. */
    public final List<Step> steps;

    public Plan(String planTitle, Origin origin, List<Step> steps) {
        this.planTitle = planTitle;
        this.origin = origin;
        this.steps = steps;
    }

    /** World coordinates anchoring the build, deserialized from the planner tool call. */
    public static class Origin {
        /** Block X coordinate. */
        public final int x;
        /** Block Y coordinate. */
        public final int y;
        /** Block Z coordinate. */
        public final int z;
        public Origin(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    /** A single build step produced by the planner, deserialized from the planner tool call. */
    public static class Step {
        /** Short identifier for this step (e.g. {@code "step_1"}). */
        public final String id;
        /** Brief label describing the feature being built (e.g. {@code "foundation"}). */
        public final String feature;
        /** Detailed instructions for the executor agent. */
        public final String details;
        public Step(String id, String feature, String details) {
            this.id = id; this.feature = feature; this.details = details;
        }
    }

    /**
     * Parse a Plan from the JSON arguments of the submit_plan tool call.
     */
    public static Plan fromJson(String json) {
        JsonObject obj = GSON.fromJson(json, JsonObject.class);

        String title = obj.get("planTitle").getAsString();

        JsonObject o = obj.getAsJsonObject("origin");
        Origin origin = new Origin(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());

        JsonArray arr = obj.getAsJsonArray("steps");
        List<Step> steps = new ArrayList<>();
        for (var el : arr) {
            JsonObject s = el.getAsJsonObject();
            steps.add(new Step(
                    s.get("id").getAsString(),
                    s.get("feature").getAsString(),
                    s.get("details").getAsString()
            ));
        }

        return new Plan(title, origin, steps);
    }
}
