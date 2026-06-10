package com.termux.app.automation;

public final class BoostExecutor {

    private final AndroidActionRunner runner;
    private final AutomationStore store;

    public BoostExecutor(AndroidActionRunner runner, AutomationStore store) {
        this.runner = runner;
        this.store = store;
    }

    public interface Callback {
        Callback NOOP = new Callback() {
            @Override
            public void onStep(String recipeName, int index, int total, String toolName) {
            }

            @Override
            public void onCompleted(String recipeName) {
            }

            @Override
            public void onFailed(String recipeName, String reason) {
            }
        };

        void onStep(String recipeName, int index, int total, String toolName);

        void onCompleted(String recipeName);

        void onFailed(String recipeName, String reason);
    }

    public BoostResult execute(ActionRecipe recipe, Callback callback) {
        Callback safeCallback = callback == null ? Callback.NOOP : callback;
        if (recipe == null) {
            return BoostResult.failure("", "Missing recipe");
        }
        if (!AutomationPolicy.canAutoBoost(recipe)) {
            return BoostResult.failure(recipe.id, "Recipe is not eligible for Automation Boost");
        }

        ActionStep activeStep = null;
        try {
            int total = recipe.steps.size();
            for (int i = 0; i < total; i++) {
                activeStep = recipe.steps.get(i);
                safeOnStep(safeCallback, recipe.name, i + 1, total, activeStep.toolName);
                runner.runStep(activeStep);
                verifyPostconditions(activeStep);
            }
            activeStep = null;
            safeOnCompleted(safeCallback, recipe.name);
            return BoostResult.success(recipe.id);
        } catch (Exception e) {
            String reason = reasonFor(e);
            String stepId = activeStep == null ? "" : activeStep.id;
            safeOnFailed(safeCallback, recipe.name, reason);
            recordFailure(recipe.id, stepId, reason);
            return BoostResult.failure(recipe.id, reason);
        }
    }

    private void verifyPostconditions(ActionStep step) throws Exception {
        ScreenFingerprint postconditions = step.postconditions;
        if (postconditions != null && postconditions.anchors != null && !postconditions.anchors.isEmpty()
            && !runner.matches(postconditions)) {
            throw new IllegalStateException("postcondition mismatch for step " + step.id);
        }
    }

    private void recordFailure(String recipeId, String stepId, String reason) {
        if (store == null) {
            return;
        }
        store.appendFailure(recipeId, stepId, reason, safeCurrentFingerprint());
    }

    private ScreenFingerprint safeCurrentFingerprint() {
        if (runner == null) {
            return ScreenFingerprint.empty();
        }
        try {
            ScreenFingerprint fingerprint = runner.currentFingerprint();
            return fingerprint == null ? ScreenFingerprint.empty() : fingerprint;
        } catch (Exception ignored) {
            return ScreenFingerprint.empty();
        }
    }

    private void safeOnStep(Callback callback, String recipeName, int index, int total, String toolName) {
        try {
            callback.onStep(recipeName, index, total, toolName);
        } catch (Exception ignored) {
        }
    }

    private void safeOnCompleted(Callback callback, String recipeName) {
        try {
            callback.onCompleted(recipeName);
        } catch (Exception ignored) {
        }
    }

    private void safeOnFailed(Callback callback, String recipeName, String reason) {
        try {
            callback.onFailed(recipeName, reason);
        } catch (Exception ignored) {
        }
    }

    private String reasonFor(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.toString() : message;
    }
}
