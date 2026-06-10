package com.termux.app.automation;

public interface AndroidActionRunner {

    void runStep(ActionStep step) throws Exception;

    ScreenFingerprint currentFingerprint();

    boolean matches(ScreenFingerprint expected);
}
