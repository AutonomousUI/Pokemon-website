package com.example.battlesimulator.service;

import com.example.battlesimulator.dto.PlayerAction;
import com.example.battlesimulator.model.BattlePokemon;
import com.example.battlesimulator.model.BattleSession;
import com.example.battlesimulator.model.Move;
import com.example.battlesimulator.model.enums.MoveCategory;
import com.example.battlesimulator.model.enums.Type;
import com.example.battlesimulator.util.TypeChart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GroqBattleAiService {

    private static final String GROQ_URL = "https://api.groq.com/openai/v1";

    private final ObjectMapper objectMapper;
    private final RestClient groqClient;
    private final String groqApiKey;
    private final String groqModel;

    public GroqBattleAiService(@Value("${groq.api.key:}") String groqApiKey,
                               @Value("${groq.model:llama-3.3-70b-versatile}") String groqModel) {
        this.objectMapper = new ObjectMapper();
        this.groqApiKey = groqApiKey;
        this.groqModel = groqModel;
        this.groqClient = RestClient.builder().baseUrl(GROQ_URL).build();
    }

    public PlayerAction chooseAction(String battleId, String aiPlayerId, BattleSession session) {
        if (aiMustSwitch(session, aiPlayerId)) {
            return chooseFallbackSwitch(battleId, aiPlayerId, session);
        }

        PlayerAction fallback = chooseFallbackMove(battleId, aiPlayerId, session);
        if (groqApiKey == null || groqApiKey.isBlank()) {
            return fallback;
        }

        try {
            String prompt = buildPrompt(session, aiPlayerId);
            Map<String, Object> payload = Map.of(
                    "model", groqModel,
                    "temperature", 0.2,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content",
                                    "You are a competitive Pokemon battle AI. " +
                                    "Reply with a single JSON object only. " +
                                    "Use actionType MOVE or SWITCH. " +
                                    "If MOVE, include targetId. If SWITCH, include switchIndex. " +
                                    "Only choose legal actions from the provided state."),
                            Map.of("role", "user", "content", prompt)
                    )
            );

            JsonNode response = groqClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + groqApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            String content = response.path("choices").path(0).path("message").path("content").asText("");
            PlayerAction parsed = parseAiAction(content, battleId, aiPlayerId);
            return isValidAction(parsed, session) ? parsed : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    public PlayerAction chooseForcedSwitchAction(String battleId, String aiPlayerId, BattleSession session) {
        return chooseFallbackSwitch(battleId, aiPlayerId, session);
    }

    private String buildPrompt(BattleSession session, String aiPlayerId) throws Exception {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("aiPlayerId", aiPlayerId);
        state.put("phase", session.getPhase().name());
        state.put("turn", session.getTurnCount());
        state.put("weather", session.getWeather().name());
        state.put("terrain", session.getTerrain().name());
        state.put("history", session.getBattleHistory());
        state.put("player1", buildTeamState(session.getPlayer1Team(), session.getPlayer1ActiveIndex()));
        state.put("player2", buildTeamState(session.getPlayer2Team(), session.getPlayer2ActiveIndex()));

        return """
                Current battle state:
                %s

                Decide the best legal action for %s right now.
                Output JSON in this exact shape:
                {
                  "actionType": "MOVE" or "SWITCH",
                  "targetId": "move-id-or-null",
                  "switchIndex": 0-5 or null,
                  "reasoning": "short explanation"
                }
                """.formatted(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state), aiPlayerId);
    }

    private List<Map<String, Object>> buildTeamState(List<BattlePokemon> team, int activeIndex) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < team.size(); i++) {
            BattlePokemon pokemon = team.get(i);
            if (pokemon == null) {
                result.add(null);
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("teamIndex", i);
            entry.put("active", i == activeIndex);
            entry.put("speciesId", pokemon.getSpeciesId());
            entry.put("nickname", pokemon.getNickname());
            entry.put("hp", Map.of("current", pokemon.getCurrentHp(), "max", pokemon.getMaxHp()));
            entry.put("fainted", pokemon.isFainted());
            entry.put("status", pokemon.getStatusCondition().name());
            entry.put("ability", pokemon.getAbility().name());
            entry.put("heldItem", pokemon.getHeldItem().name());
            entry.put("types", List.of(pokemon.getType1().name(), pokemon.getType2().name()));
            entry.put("stats", Map.of(
                    "attack", pokemon.getAttack(),
                    "defense", pokemon.getDefense(),
                    "specialAttack", pokemon.getSpecialAttack(),
                    "specialDefense", pokemon.getSpecialDefense(),
                    "speed", pokemon.getSpeed()
            ));
            entry.put("stages", Map.of(
                    "attack", pokemon.getAttackStage(),
                    "defense", pokemon.getDefenseStage(),
                    "specialAttack", pokemon.getSpecialAttackStage(),
                    "specialDefense", pokemon.getSpecialDefenseStage(),
                    "speed", pokemon.getSpeedStage(),
                    "accuracy", pokemon.getAccuracyStage(),
                    "evasion", pokemon.getEvasionStage()
            ));
            Map<String, Object> volatileState = new LinkedHashMap<>();
            volatileState.put("confused", pokemon.isConfused());
            volatileState.put("taunted", pokemon.isTaunted());
            volatileState.put("trapped", pokemon.isTrapped());
            volatileState.put("choiceLock", pokemon.getChoiceLock());
            entry.put("volatile", volatileState);
            entry.put("moves", pokemon.getMoves().stream().map(move -> Map.of(
                    "id", move.id(),
                    "name", move.name(),
                    "type", move.type().name(),
                    "category", move.category().name(),
                    "power", move.basePower(),
                    "priority", move.priority()
            )).toList());
            result.add(entry);
        }
        return result;
    }

    private PlayerAction parseAiAction(String rawContent, String battleId, String aiPlayerId) throws Exception {
        String cleaned = rawContent == null ? "" : rawContent.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```json\\s*", "").replaceFirst("^```\\s*", "").replaceFirst("\\s*```$", "");
        }
        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            cleaned = cleaned.substring(firstBrace, lastBrace + 1);
        }

        JsonNode node = objectMapper.readTree(cleaned);
        String actionType = node.path("actionType").asText("");
        String targetId = node.path("targetId").isNull() ? null : node.path("targetId").asText(null);
        Integer switchIndex = node.path("switchIndex").isNull() || node.path("switchIndex").isMissingNode()
                ? null : node.path("switchIndex").asInt();
        return new PlayerAction(battleId, aiPlayerId, actionType, targetId, switchIndex);
    }

    private boolean isValidAction(PlayerAction action, BattleSession session) {
        if (action == null) {
            return false;
        }
        String playerId = action.playerId();
        BattlePokemon active = "player1".equals(playerId) ? session.getPlayer1Active() : session.getPlayer2Active();
        List<BattlePokemon> team = "player1".equals(playerId) ? session.getPlayer1Team() : session.getPlayer2Team();

        if ("MOVE".equalsIgnoreCase(action.actionType())) {
            if (active == null || active.isFainted() || action.targetId() == null) {
                return false;
            }
            return active.getMoves().stream().anyMatch(move -> move.id().equalsIgnoreCase(action.targetId()));
        }
        if ("SWITCH".equalsIgnoreCase(action.actionType())) {
            if (action.switchIndex() == null || action.switchIndex() < 0 || action.switchIndex() >= team.size()) {
                return false;
            }
            BattlePokemon target = team.get(action.switchIndex());
            return target != null && !target.isFainted() && target != active;
        }
        return false;
    }

    private boolean aiMustSwitch(BattleSession session, String aiPlayerId) {
        return switch (session.getPhase()) {
            case WAITING_FOR_SWITCH_P1 -> "player1".equals(aiPlayerId);
            case WAITING_FOR_SWITCH_P2 -> "player2".equals(aiPlayerId);
            case WAITING_FOR_SWITCH_BOTH -> true;
            default -> false;
        };
    }

    private PlayerAction chooseFallbackSwitch(String battleId, String aiPlayerId, BattleSession session) {
        List<BattlePokemon> team = "player1".equals(aiPlayerId) ? session.getPlayer1Team() : session.getPlayer2Team();
        BattlePokemon active = "player1".equals(aiPlayerId) ? session.getPlayer1Active() : session.getPlayer2Active();
        BattlePokemon opponent = "player1".equals(aiPlayerId) ? session.getPlayer2Active() : session.getPlayer1Active();

        Integer bestIndex = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < team.size(); i++) {
            BattlePokemon candidate = team.get(i);
            if (candidate == null || candidate.isFainted() || candidate == active) {
                continue;
            }

            double score = heuristicSwitchScore(candidate, opponent);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }

        if (bestIndex != null) {
            return new PlayerAction(battleId, aiPlayerId, "SWITCH", null, bestIndex);
        }
        return new PlayerAction(battleId, aiPlayerId, "MOVE", active != null && !active.getMoves().isEmpty() ? active.getMoves().get(0).id() : null, null);
    }

    private PlayerAction chooseFallbackMove(String battleId, String aiPlayerId, BattleSession session) {
        BattlePokemon active = "player1".equals(aiPlayerId) ? session.getPlayer1Active() : session.getPlayer2Active();
        BattlePokemon defender = "player1".equals(aiPlayerId) ? session.getPlayer2Active() : session.getPlayer1Active();
        if (active == null || active.isFainted()) {
            return chooseFallbackSwitch(battleId, aiPlayerId, session);
        }

        Move forcedMove = getChoiceLockedMove(active);
        if (forcedMove != null) {
            double forcedScore = heuristicMoveScore(forcedMove, defender);
            if (shouldSwitchInsteadOfLockedMove(active, defender, forcedScore, battleId, aiPlayerId, session)) {
                return chooseFallbackSwitch(battleId, aiPlayerId, session);
            }
            return new PlayerAction(battleId, aiPlayerId, "MOVE", forcedMove.id(), null);
        }

        Move bestMove = active.getMoves().stream()
                .filter(move -> move != null)
                .max(Comparator.comparingDouble(move -> heuristicMoveScore(move, defender)))
                .orElse(null);

        if (bestMove == null) {
            return chooseFallbackSwitch(battleId, aiPlayerId, session);
        }
        return new PlayerAction(battleId, aiPlayerId, "MOVE", bestMove.id(), null);
    }

    private Move getChoiceLockedMove(BattlePokemon active) {
        if (active.getChoiceLock() == null || active.getHeldItem() == null) {
            return null;
        }
        return switch (active.getHeldItem()) {
            case CHOICE_BAND, CHOICE_SPECS, CHOICE_SCARF -> active.getMoves().stream()
                    .filter(move -> move != null && move.id().equalsIgnoreCase(active.getChoiceLock()))
                    .findFirst()
                    .orElse(null);
            default -> null;
        };
    }

    private boolean shouldSwitchInsteadOfLockedMove(BattlePokemon active, BattlePokemon defender,
                                                    double forcedScore, String battleId,
                                                    String aiPlayerId, BattleSession session) {
        if (defender == null) {
            return false;
        }

        PlayerAction bestSwitch = chooseFallbackSwitch(battleId, aiPlayerId, session);
        if (!"SWITCH".equalsIgnoreCase(bestSwitch.actionType()) || bestSwitch.switchIndex() == null) {
            return false;
        }

        List<BattlePokemon> team = "player1".equals(aiPlayerId) ? session.getPlayer1Team() : session.getPlayer2Team();
        BattlePokemon switchTarget = team.get(bestSwitch.switchIndex());
        if (switchTarget == null || switchTarget == active || switchTarget.isFainted()) {
            return false;
        }

        double switchScore = heuristicSwitchScore(switchTarget, defender);
        return forcedScore <= 0.0 || switchScore > forcedScore + 40.0;
    }

    private double heuristicMoveScore(Move move, BattlePokemon defender) {
        if (move.category() == MoveCategory.STATUS) {
            return move.priority() > 0 ? 25 : 5;
        }
        double power = Math.max(move.basePower(), 1);
        double accuracy = move.accuracy() <= 0 ? 1.0 : move.accuracy() / 100.0;
        double multiplier = 1.0;
        if (defender != null) {
            Type t2 = defender.getType2() == null ? Type.NONE : defender.getType2();
            multiplier = TypeChart.getMultiplier(move.type(), defender.getType1())
                    * TypeChart.getMultiplier(move.type(), t2);
        }
        return power * accuracy * multiplier + (move.priority() * 20.0);
    }

    private double heuristicSwitchScore(BattlePokemon candidate, BattlePokemon opponent) {
        double hpRatio = candidate.getMaxHp() > 0 ? (double) candidate.getCurrentHp() / candidate.getMaxHp() : 0.0;
        double bulk = candidate.getDefense() + candidate.getSpecialDefense();
        double speed = candidate.getSpeed();
        double offensivePressure = 0.0;
        double defensiveSafety = 1.0;

        if (opponent != null) {
            offensivePressure = candidate.getMoves().stream()
                    .filter(move -> move != null)
                    .mapToDouble(move -> heuristicMoveScore(move, opponent))
                    .max()
                    .orElse(0.0);

            defensiveSafety = 0.0;
            for (Move opponentMove : opponent.getMoves()) {
                if (opponentMove == null) {
                    continue;
                }
                Type candidateType2 = candidate.getType2() == null ? Type.NONE : candidate.getType2();
                double multiplier = TypeChart.getMultiplier(opponentMove.type(), candidate.getType1())
                        * TypeChart.getMultiplier(opponentMove.type(), candidateType2);
                defensiveSafety = Math.max(defensiveSafety, multiplier);
            }
        }

        return offensivePressure
                + (hpRatio * 140.0)
                + (bulk * 0.12)
                + (speed * 0.08)
                - (defensiveSafety * 90.0);
    }
}
