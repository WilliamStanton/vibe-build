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

    public final String planTitle;
    public final Origin origin;
    public final List<Step> steps;

    public Plan(String planTitle, Origin origin, List<Step> steps) {
        this.planTitle = planTitle;
        this.origin = origin;
        this.steps = steps;
    }

    public static class Origin {
        public final int x, y, z;
        public Origin(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    public static class Step {
        public final String id;
        public final String feature;
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
