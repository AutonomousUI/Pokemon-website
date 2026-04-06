package com.example.battlesimulator.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class BattleSession {
    public enum Phase {
        WAITING_FOR_PLAYERS,
        CHOOSING_ACTION,
        WAITING_FOR_SWITCH_P1,
        WAITING_FOR_SWITCH_P2,
        WAITING_FOR_SWITCH_BOTH,
        BATTLE_OVER
    }

    private String battleId;
    private List<BattlePokemon> player1Team = new ArrayList<>();
    private List<BattlePokemon> player2Team = new ArrayList<>();
    private int player1ActiveIndex = 0;
    private int player2ActiveIndex = 0;
    private int turnCount = 1;
    private Phase phase = Phase.WAITING_FOR_PLAYERS;
    private String winnerId = null;
    private boolean aiBattle = false;
    private String aiPlayerId = null;
    private List<String> battleHistory = new ArrayList<>();

    // Weather
    private com.example.battlesimulator.model.enums.Weather weather
            = com.example.battlesimulator.model.enums.Weather.NONE;
    private int weatherTurnsRemaining = 0; // 0 = infinite (not currently used), >0 counts down
    private com.example.battlesimulator.model.enums.Terrain terrain
            = com.example.battlesimulator.model.enums.Terrain.NONE;
    private int terrainTurnsRemaining = 0;

    // Entry Hazards — "Player1" side = hazards on Player1's field (hurt Player1's incoming mons)
    private int spikesPlayer1     = 0; // 0–3 layers
    private int spikesPlayer2     = 0;
    private int toxicSpikesPlayer1 = 0; // 0–2 layers
    private int toxicSpikesPlayer2 = 0;
    private boolean stealthRockPlayer1 = false;
    private boolean stealthRockPlayer2 = false;
    private boolean stickyWebPlayer1   = false;
    private boolean stickyWebPlayer2   = false;

    public BattleSession(String battleId) {
        this.battleId = battleId;
    }

    public BattlePokemon getPlayer1Active() {
        if (player1Team.isEmpty()) return null;
        return player1Team.get(player1ActiveIndex);
    }

    public BattlePokemon getPlayer2Active() {
        if (player2Team.isEmpty()) return null;
        return player2Team.get(player2ActiveIndex);
    }

    public boolean isAiPlayer(String playerId) {
        return aiBattle && aiPlayerId != null && aiPlayerId.equals(playerId);
    }

    public boolean isPlayer1TeamReady() { return player1Team.size() == 6 && player1Team.stream().noneMatch(java.util.Objects::isNull); }
    public boolean isPlayer2TeamReady() { return player2Team.size() == 6 && player2Team.stream().noneMatch(java.util.Objects::isNull); }
    public boolean areBothTeamsReady() { return isPlayer1TeamReady() && isPlayer2TeamReady(); }

    public boolean isPlayer1AllFainted() {
        return player1Team.stream().allMatch(BattlePokemon::isFainted);
    }

    public boolean isPlayer2AllFainted() {
        return player2Team.stream().allMatch(BattlePokemon::isFainted);
    }

    /** Returns next non-fainted index for a player, or -1 if none left */
    public int getNextAliveIndex(int playerNum) {
        List<BattlePokemon> team = (playerNum == 1) ? player1Team : player2Team;
        int currentIdx = (playerNum == 1) ? player1ActiveIndex : player2ActiveIndex;
        for (int i = 0; i < team.size(); i++) {
            if (i != currentIdx && !team.get(i).isFainted()) return i;
        }
        return -1;
    }
}
