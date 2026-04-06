package com.example.battlesimulator.model.enums;

public enum Nature {
    HARDY(Stat.ATTACK, Stat.ATTACK),     // Neutral
    ADAMANT(Stat.ATTACK, Stat.SPECIAL_ATTACK), // +Atk, -SpA
    BOLD(Stat.DEFENSE, Stat.ATTACK),           // +Def, -Atk
    BRAVE(Stat.ATTACK, Stat.SPEED),            // +Atk, -Spe
    CAREFUL(Stat.SPECIAL_DEFENSE, Stat.SPECIAL_ATTACK), // +SpDef, -SpA
    IMPISH(Stat.DEFENSE, Stat.SPECIAL_ATTACK), // +Def, -SpA
    MODEST(Stat.SPECIAL_ATTACK, Stat.ATTACK),  // +SpA, -Atk
    CALM(Stat.SPECIAL_DEFENSE, Stat.ATTACK),   // +SpDef, -Atk
    JOLLY(Stat.SPEED, Stat.SPECIAL_ATTACK),    // +Spe, -SpA
    TIMID(Stat.SPEED, Stat.ATTACK);            // +Spe, -Atk

    private final Stat increasedStat;
    private final Stat decreasedStat;

    Nature(Stat increasedStat, Stat decreasedStat) {
        this.increasedStat = increasedStat;
        this.decreasedStat = decreasedStat;
    }

    public double getMultiplier(Stat stat) {
        if (this.increasedStat == this.decreasedStat) return 1.0; // Neutral Nature
        if (stat == this.increasedStat) return 1.1;
        if (stat == this.decreasedStat) return 0.9;
        return 1.0;
    }
}
