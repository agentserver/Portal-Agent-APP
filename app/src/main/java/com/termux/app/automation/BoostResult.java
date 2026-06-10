package com.termux.app.automation;

public final class BoostResult {

    public final boolean success;
    public final String recipeId;
    public final String reason;

    private BoostResult(boolean success, String recipeId, String reason) {
        this.success = success;
        this.recipeId = recipeId == null ? "" : recipeId;
        this.reason = reason == null ? "" : reason;
    }

    public static BoostResult success(String recipeId) {
        return new BoostResult(true, recipeId, "");
    }

    public static BoostResult failure(String recipeId, String reason) {
        return new BoostResult(false, recipeId, reason);
    }
}
