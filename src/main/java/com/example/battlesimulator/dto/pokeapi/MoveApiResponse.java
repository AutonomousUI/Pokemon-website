package com.example.battlesimulator.dto.pokeapi;

public record MoveApiResponse(
        String name,
        Integer accuracy,
        Integer power,
        int pp,
        int priority,
        TypeDetail type,
        DamageClass damage_class
) {
    public record TypeDetail(String name) {}
    public record DamageClass(String name) {}
}