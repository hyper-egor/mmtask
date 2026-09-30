package ru.egor;

public final class ReconciliationResult {
    private int cancelCommands;
    private int replacements;
    private int reduceReplacements;

    void recordCancel() {
        cancelCommands++;
    }

    void recordReplacement() {
        replacements++;
    }

    void recordReduceReplacement() {
        reduceReplacements++;
    }

    public int getCancelCommands() {
        return cancelCommands;
    }

    public int getReplacements() {
        return replacements;
    }

    public int getReduceReplacements() {
        return reduceReplacements;
    }
}
