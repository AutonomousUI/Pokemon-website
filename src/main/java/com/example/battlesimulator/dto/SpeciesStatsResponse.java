package com.example.battlesimulator.dto;

public record SpeciesStatsResponse(
        int hp,
        int attack,
        int defense,
        int specialAttack,
        int specialDefense,
        int speed
) {}
