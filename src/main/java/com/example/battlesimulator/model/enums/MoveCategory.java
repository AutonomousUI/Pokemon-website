package com.example.battlesimulator.model.enums;

public enum MoveCategory {
    PHYSICAL, // Uses Attack / Defense
    SPECIAL,  // Uses Sp. Atk / Sp. Def
    STATUS,   // Does not deal direct damage
    OTHER     // e.g. Seismic Toss, Night Shade — PokéAPI "other" damage class; treated as PHYSICAL for damage
}