package com.example.battlesimulator.model;

public record StatBlock(
        int hp,
        int attack,
        int defense,
        int specialAttack,
        int specialDefense,
        int speed
) {
    // Helper method for a standard perfect IV spread
    public static StatBlock perfectIvs() {
        return new StatBlock(31, 31, 31, 31, 31, 31);
    }

    // Helper method for a blank EV spread
    public static StatBlock emptyEvs() {
        return new StatBlock(0, 0, 0, 0, 0, 0);
    }
}
