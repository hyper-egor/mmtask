package ru.egor;

public final class ReconciliationResult {
    private int cancelCommands;
    private int replacements;

    void recordCancel() {
        cancelCommands++;
    }

    void recordReplacement() {
        replacements++;
    }

    public int getCancelCommands() {
        return cancelCommands;
    }

    public int getReplacements() {
        return replacements;
    }
}
