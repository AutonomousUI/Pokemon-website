package com.example.battlesimulator.model.enums;

/**
 * Non-volatile weather conditions that affect the whole field.
 * Lasts 5 turns when set by a move (or indefinitely with a weather rock — not implemented).
 */
public enum Weather {
    NONE,
    RAIN,       // Water moves ×1.5, Fire moves ×0.5; Thunder never misses; no Solarbeam charge skip
    SUN,        // Fire moves ×1.5, Water moves ×0.5; Solarbeam skips charge turn
    SAND,       // Rock/Ground/Steel immune; all others lose 1/16 HP per turn; Sp. Def of Rock types ×1.5
    HAIL        // Ice types immune; all others lose 1/16 HP per turn
}