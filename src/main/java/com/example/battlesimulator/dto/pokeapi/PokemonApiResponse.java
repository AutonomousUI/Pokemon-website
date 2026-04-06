package com.example.battlesimulator.dto.pokeapi;

import java.util.List;

// This matches the root level of the PokeAPI response
public record PokemonApiResponse(
        String name,
        List<AbilitySlot> abilities,
        List<MoveSlot> moves,
        List<TypeSlot> types,
        List<StatSlot> stats
) {
    public record AbilitySlot(AbilityDetail ability, boolean is_hidden, int slot) {}
    public record AbilityDetail(String name) {}

    public record MoveSlot(MoveDetail move) {}
    public record MoveDetail(String name) {}

    public record TypeSlot(int slot, TypeDetail type) {}
    public record TypeDetail(String name) {}

    public record StatSlot(int base_stat, StatDetail stat) {}
    public record StatDetail(String name) {}
}
