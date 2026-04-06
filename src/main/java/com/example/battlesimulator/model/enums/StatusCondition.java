package com.example.battlesimulator.model.enums;

/**
 * Non-volatile status conditions (persist until cured or the Pokémon faints).
 * A Pokémon can only have one at a time.
 */
public enum StatusCondition {
    NONE,
    BURN,        // Loses 1/8 max HP per turn; physical damage halved
    POISON,      // Loses 1/8 max HP per turn
    TOXIC,       // Badly poisoned: loses 1/16, 2/16, 3/16... escalating each turn
    PARALYSIS,   // 25% chance to be fully paralysed each turn; Speed halved
    SLEEP,       // Cannot move; lasts 1–3 turns
    FREEZE       // Cannot move; 20% chance to thaw each turn
}