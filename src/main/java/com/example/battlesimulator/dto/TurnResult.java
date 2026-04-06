package com.example.battlesimulator.dto;

import com.example.battlesimulator.model.BattlePokemon;
import java.util.List;

public record TurnResult(
        String battleId,
        List<String> combatLog,
        List<PokemonSnapshot> player1Team,
        List<PokemonSnapshot> player2Team,
        int player1ActiveIndex,
        int player2ActiveIndex,
        String phase,
        String winnerId,
        String weather,
        // Entry hazards — indexed by player whose side they are ON
        int spikesP1,
        int spikesP2,
        int toxicSpikesP1,
        int toxicSpikesP2,
        boolean stealthRockP1,
        boolean stealthRockP2,
        boolean stickyWebP1,
        boolean stickyWebP2
) {
    public record PokemonSnapshot(
            String speciesId,
            String nickname,
            int currentHp,
            int maxHp,
            boolean fainted,
            String status,
            String heldItem,
            String ability,
            boolean confused,
            boolean taunted
    ) {
        public static PokemonSnapshot of(BattlePokemon p) {
            return new PokemonSnapshot(
                    p.getSpeciesId(), p.getNickname(),
                    p.getCurrentHp(), p.getMaxHp(), p.isFainted(),
                    p.getStatusCondition().name(),
                    p.getHeldItem().name(),
                    p.getAbility() != null ? p.getAbility().name() : "NONE",
                    p.isConfused(),
                    p.isTaunted());
        }
    }
}