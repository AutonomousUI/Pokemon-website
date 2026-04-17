package com.example.battlesimulator.service;

import com.example.battlesimulator.model.BattlePokemon;
import com.example.battlesimulator.model.Move;
import com.example.battlesimulator.model.enums.Ability;
import com.example.battlesimulator.model.enums.HeldItem;
import com.example.battlesimulator.model.enums.MoveCategory;
import com.example.battlesimulator.model.enums.StatusCondition;
import com.example.battlesimulator.model.enums.Type;
import com.example.battlesimulator.model.enums.Weather;
import com.example.battlesimulator.util.TypeChart;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.Set;

@Service
public class DamageCalculatorService {

    private final Random random = new Random();

    public int calculateDamage(BattlePokemon attacker, BattlePokemon defender, Move move, Weather weather) {
        if (move.category() == MoveCategory.STATUS) return 0;

        Ability attackerAbility = attacker.getAbility();
        Ability defenderAbility = defender.getAbility();
        Type effectiveMoveType = resolveMoveType(attacker, move);

        int attackerAtkStage = defenderAbility == Ability.UNAWARE ? 0 : attacker.getAttackStage();
        int attackerSpAtkStage = defenderAbility == Ability.UNAWARE ? 0 : attacker.getSpecialAttackStage();
        int defenderDefStage = attackerAbility == Ability.UNAWARE ? 0 : defender.getDefenseStage();
        int defenderSpDefStage = attackerAbility == Ability.UNAWARE ? 0 : defender.getSpecialDefenseStage();

        int attackStat;
        int defenseStat;
        if (move.category() == MoveCategory.PHYSICAL || move.category() == MoveCategory.OTHER) {
            attackStat = (int) Math.floor(attacker.getAttack() * stageMultiplier(attackerAtkStage));
            defenseStat = (int) Math.floor(defender.getDefense() * stageMultiplier(defenderDefStage));
        } else {
            attackStat = (int) Math.floor(attacker.getSpecialAttack() * stageMultiplier(attackerSpAtkStage));
            defenseStat = (int) Math.floor(defender.getSpecialDefense() * stageMultiplier(defenderSpDefStage));
        }

        if (attackerAbility != null) {
            switch (attackerAbility) {
                case HUGE_POWER, PURE_POWER -> {
                    if (move.category() == MoveCategory.PHYSICAL) attackStat *= 2;
                }
                case GUTS -> {
                    if (move.category() == MoveCategory.PHYSICAL && attacker.getStatusCondition() != StatusCondition.NONE) {
                        attackStat = (int) Math.floor(attackStat * 1.5);
                    }
                }
                case HUSTLE -> {
                    if (move.category() == MoveCategory.PHYSICAL) attackStat = (int) Math.floor(attackStat * 1.5);
                }
                case SLOW_START -> {
                    if (move.category() == MoveCategory.PHYSICAL && attacker.getSlowStartTurns() > 0) {
                        attackStat = (int) Math.floor(attackStat * 0.5);
                    }
                }
                case FLASH_FIRE -> {
                    if (effectiveMoveType == Type.FIRE && attacker.isFlashFireActive()) {
                        attackStat = (int) Math.floor(attackStat * 1.5);
                    }
                }
                case SOLAR_POWER -> {
                    if (move.category() == MoveCategory.SPECIAL && weather == Weather.SUN) {
                        attackStat = (int) Math.floor(attackStat * 1.5);
                    }
                }
                case WATER_BUBBLE -> {
                    if (effectiveMoveType == Type.WATER) {
                        attackStat = (int) Math.floor(attackStat * 2.0);
                    }
                }
                case HADRON_ENGINE -> {
                    if (move.category() == MoveCategory.SPECIAL) {
                        attackStat = (int) Math.floor(attackStat * 1.3);
                    }
                }
                case ORICHALCUM_PULSE -> {
                    if (move.category() == MoveCategory.PHYSICAL) {
                        attackStat = (int) Math.floor(attackStat * 1.3);
                    }
                }
                default -> {
                }
            }
        }

        if (defenderAbility != null) {
            switch (defenderAbility) {
                case MARVEL_SCALE -> {
                    if (move.category() == MoveCategory.PHYSICAL && defender.getStatusCondition() != StatusCondition.NONE) {
                        defenseStat = (int) Math.floor(defenseStat * 1.5);
                    }
                }
                case THICK_FAT -> {
                    if (effectiveMoveType == Type.FIRE || effectiveMoveType == Type.ICE) {
                        attackStat = (int) Math.floor(attackStat * 0.5);
                    }
                }
                case HEATPROOF -> {
                    if (effectiveMoveType == Type.FIRE) {
                        attackStat = (int) Math.floor(attackStat * 0.5);
                    }
                }
                case FUR_COAT -> {
                    if (move.category() == MoveCategory.PHYSICAL) {
                        defenseStat = (int) Math.floor(defenseStat * 2.0);
                    }
                }
                case ICE_SCALES -> {
                    if (move.category() == MoveCategory.SPECIAL) {
                        defenseStat = (int) Math.floor(defenseStat * 2.0);
                    }
                }
                case WATER_BUBBLE -> {
                    if (effectiveMoveType == Type.FIRE) {
                        attackStat = (int) Math.floor(attackStat * 0.5);
                    }
                }
                default -> {
                }
            }
        }

        if (defender.getHeldItem() == HeldItem.ASSAULT_VEST && move.category() == MoveCategory.SPECIAL) {
            defenseStat = (int) Math.floor(defenseStat * 1.5);
        }
        if (defender.getHeldItem() == HeldItem.EVIOLITE) {
            defenseStat = (int) Math.floor(defenseStat * 1.5);
        }

        int level = attacker.getLevel();
        int power = move.basePower();
        double baseDamage = Math.floor((Math.floor((2.0 * level) / 5.0 + 2.0) * power * attackStat) / Math.max(1, defenseStat)) / 50.0 + 2.0;

        boolean hasStab = attacker.getType1() == effectiveMoveType || attacker.getType2() == effectiveMoveType;
        double stab = hasStab ? (attackerAbility == Ability.ADAPTABILITY ? 2.0 : 1.5) : 1.0;

        double typeMultiplier = TypeChart.getMultiplier(effectiveMoveType, defender.getType1())
                * TypeChart.getMultiplier(effectiveMoveType, defender.getType2());
        if (attackerAbility == Ability.SCRAPPY
                && (effectiveMoveType == Type.NORMAL || effectiveMoveType == Type.FIGHTING)
                && (defender.getType1() == Type.GHOST || defender.getType2() == Type.GHOST)
                && typeMultiplier == 0.0) {
            typeMultiplier = 1.0;
        }
        if (defenderAbility == Ability.WONDER_GUARD && typeMultiplier <= 1.0) return 0;
        if (typeMultiplier == 0.0) return 0;

        double burnFactor = (attacker.getStatusCondition() == StatusCondition.BURN
                && (move.category() == MoveCategory.PHYSICAL || move.category() == MoveCategory.OTHER)
                && attackerAbility != Ability.GUTS)
                ? 0.5 : 1.0;

        int critStage = attacker.getCritStageBonus();
        if (isHighCritMove(move)) critStage++;
        if (attacker.getHeldItem() == HeldItem.LEEK && attacker.getSpeciesId() != null) {
            String species = attacker.getSpeciesId().toLowerCase();
            if (species.contains("farfetch") || species.contains("sirfetch")) {
                critStage++;
            }
        }
        double critThreshold = switch (Math.min(critStage, 3)) {
            case 0 -> 1.0 / 24.0;
            case 1 -> 1.0 / 8.0;
            case 2 -> 1.0 / 2.0;
            default -> 1.0;
        };
        boolean isCrit = random.nextDouble() < critThreshold;
        double critFactor = isCrit ? (attackerAbility == Ability.SNIPER ? 2.25 : 1.5) : 1.0;

        double weatherFactor = 1.0;
        if (weather == Weather.RAIN) {
            if (effectiveMoveType == Type.WATER) weatherFactor = 1.5;
            else if (effectiveMoveType == Type.FIRE) weatherFactor = 0.5;
        } else if (weather == Weather.SUN) {
            if (effectiveMoveType == Type.FIRE) weatherFactor = 1.5;
            else if (effectiveMoveType == Type.WATER) weatherFactor = 0.5;
        }

        double sandSpDefBoost = 1.0;
        if (weather == Weather.SAND
                && move.category() == MoveCategory.SPECIAL
                && (defender.getType1() == Type.ROCK || defender.getType2() == Type.ROCK)) {
            sandSpDefBoost = 1.5;
        }

        double itemFactor = 1.0;
        switch (attacker.getHeldItem()) {
            case CHOICE_BAND -> {
                if (move.category() == MoveCategory.PHYSICAL) itemFactor = 1.5;
            }
            case CHOICE_SPECS -> {
                if (move.category() == MoveCategory.SPECIAL) itemFactor = 1.5;
            }
            case LIFE_ORB -> itemFactor = 1.3;
            case EXPERT_BELT -> {
                if (typeMultiplier > 1.0) itemFactor = 1.2;
            }
            case MUSCLE_BAND -> {
                if (move.category() == MoveCategory.PHYSICAL) itemFactor = 1.1;
            }
            case WISE_GLASSES -> {
                if (move.category() == MoveCategory.SPECIAL) itemFactor = 1.1;
            }
            case LIGHT_BALL -> {
                if (attacker.getSpeciesId() != null && attacker.getSpeciesId().toLowerCase().contains("pikachu")) {
                    itemFactor = 2.0;
                }
            }
            case METRONOME -> {
                int consecutiveUses = Math.max(0, attacker.getMetronomeCount() - 1);
                itemFactor = Math.min(2.0, 1.0 + consecutiveUses * 0.2);
            }
            default -> {
            }
        }

        double abilityMoveFactor = 1.0;
        if (attackerAbility != null) {
            switch (attackerAbility) {
                case TECHNICIAN -> {
                    if (move.basePower() > 0 && move.basePower() <= 60) abilityMoveFactor = 1.5;
                }
                case SHEER_FORCE -> {
                    if (hasSheerForceEffect(move)) abilityMoveFactor = 1.3;
                }
                case TOUGH_CLAWS -> {
                    if (isContactMove(move)) abilityMoveFactor = 1.3;
                }
                case IRON_FIST -> {
                    if (isPunchMove(move)) abilityMoveFactor = 1.2;
                }
                case MEGA_LAUNCHER -> {
                    if (isPulseMove(move)) abilityMoveFactor = 1.5;
                }
                case STRONG_JAW -> {
                    if (isBiteMove(move)) abilityMoveFactor = 1.5;
                }
                case RECKLESS -> {
                    if (isRecoilMove(move)) abilityMoveFactor = 1.2;
                }
                case BLAZE -> {
                    if (effectiveMoveType == Type.FIRE && attacker.getCurrentHp() <= attacker.getMaxHp() / 3) abilityMoveFactor = 1.5;
                }
                case TORRENT -> {
                    if (effectiveMoveType == Type.WATER && attacker.getCurrentHp() <= attacker.getMaxHp() / 3) abilityMoveFactor = 1.5;
                }
                case OVERGROW -> {
                    if (effectiveMoveType == Type.GRASS && attacker.getCurrentHp() <= attacker.getMaxHp() / 3) abilityMoveFactor = 1.5;
                }
                case SWARM -> {
                    if (effectiveMoveType == Type.BUG && attacker.getCurrentHp() <= attacker.getMaxHp() / 3) abilityMoveFactor = 1.5;
                }
                case SAND_FORCE -> {
                    if (weather == Weather.SAND && (effectiveMoveType == Type.ROCK || effectiveMoveType == Type.GROUND || effectiveMoveType == Type.STEEL)) {
                        abilityMoveFactor = 1.3;
                    }
                }
                case AERILATE, REFRIGERATE, PIXILATE, GALVANIZE -> {
                    if (move.type() == Type.NORMAL && move.category() != MoveCategory.STATUS) abilityMoveFactor = 1.2;
                }
                case LIQUID_VOICE -> {
                    if (isSoundMove(move)) abilityMoveFactor = 1.2;
                }
                case STEELY_SPIRIT -> {
                    if (effectiveMoveType == Type.STEEL) abilityMoveFactor = 1.5;
                }
                case PUNK_ROCK -> {
                    if (isSoundMove(move)) abilityMoveFactor = 1.3;
                }
                case SUPREME_OVERLORD -> abilityMoveFactor = 1.0 + Math.min(0.5, attacker.getSupremeOverlordStacks() * 0.1);
                case PROTOSYNTHESIS, QUARK_DRIVE -> abilityMoveFactor = 1.3;
                case TINTED_LENS -> {
                    if (typeMultiplier > 0.0 && typeMultiplier < 1.0) abilityMoveFactor = 2.0;
                }
                case ANALYTIC -> {
                    if (attacker.isAnalyticBoosted()) abilityMoveFactor = 1.3;
                }
                default -> {
                }
            }
        }

        double filterFactor = 1.0;
        if (typeMultiplier > 1.0 && (defenderAbility == Ability.FILTER || defenderAbility == Ability.SOLID_ROCK || defenderAbility == Ability.PRISM_ARMOR)) {
            filterFactor = 0.75;
        }

        double defenderFactor = 1.0;
        if ((defenderAbility == Ability.MULTISCALE || defenderAbility == Ability.SHADOW_SHIELD) && defender.getCurrentHp() == defender.getMaxHp()) {
            defenderFactor *= 0.5;
        }
        if (defenderAbility == Ability.FLUFFY && isContactMove(move)) {
            defenderFactor *= 0.5;
        }
        if (defenderAbility == Ability.FLUFFY && effectiveMoveType == Type.FIRE) {
            defenderFactor *= 2.0;
        }
        if (defenderAbility == Ability.PUNK_ROCK && isSoundMove(move)) {
            defenderFactor *= 0.5;
        }
        if (defenderAbility == Ability.PURIFYING_SALT && effectiveMoveType == Type.GHOST) {
            defenderFactor *= 0.5;
        }

        double ruinFactor = 1.0;
        if (attackerAbility == Ability.SWORD_OF_RUIN && move.category() == MoveCategory.PHYSICAL) ruinFactor *= 1.25;
        if (attackerAbility == Ability.BEADS_OF_RUIN && move.category() == MoveCategory.SPECIAL) ruinFactor *= 1.25;
        if (defenderAbility == Ability.TABLETS_OF_RUIN && move.category() == MoveCategory.PHYSICAL) ruinFactor *= 0.75;
        if (defenderAbility == Ability.VESSEL_OF_RUIN && move.category() == MoveCategory.SPECIAL) ruinFactor *= 0.75;

        double randomFactor = 0.85 + (random.nextDouble() * 0.15);
        double finalDamage = baseDamage * stab * typeMultiplier * burnFactor * weatherFactor * itemFactor
                * abilityMoveFactor * filterFactor * defenderFactor * critFactor * sandSpDefBoost * ruinFactor * randomFactor;
        return (int) Math.max(1, Math.floor(finalDamage));
    }

    private Type resolveMoveType(BattlePokemon attacker, Move move) {
        Ability ability = attacker.getAbility();
        if (ability == null || move.category() == MoveCategory.STATUS) return move.type();
        if (ability == Ability.NORMALIZE) return Type.NORMAL;
        if (ability == Ability.LIQUID_VOICE && isSoundMove(move)) return Type.WATER;
        if (move.type() != Type.NORMAL) return move.type();
        return switch (ability) {
            case AERILATE -> Type.FLYING;
            case REFRIGERATE -> Type.ICE;
            case PIXILATE -> Type.FAIRY;
            case GALVANIZE -> Type.ELECTRIC;
            default -> move.type();
        };
    }

    private double stageMultiplier(int stage) {
        return stage >= 0 ? (2.0 + stage) / 2.0 : 2.0 / (2.0 - stage);
    }

    private boolean isContactMove(Move move) {
        if (move.category() != MoveCategory.PHYSICAL) return false;
        Set<String> nonContact = Set.of(
                "earthquake", "magnitude", "rock-slide", "rock-blast",
                "bullet-seed", "pin-missile", "icicle-spear", "surf",
                "self-destruct", "explosion", "poison-sting"
        );
        return !nonContact.contains(move.id().toLowerCase());
    }

    private boolean isPunchMove(Move move) {
        Set<String> punchMoves = Set.of(
                "ice-punch", "fire-punch", "thunder-punch", "mach-punch", "focus-punch",
                "shadow-punch", "drain-punch", "hammer-arm", "comet-punch", "bullet-punch",
                "mega-punch", "sky-uppercut", "sucker-punch", "dizzy-punch", "dynamic-punch"
        );
        return punchMoves.contains(move.id().toLowerCase());
    }

    private boolean isPulseMove(Move move) {
        Set<String> pulseMoves = Set.of(
                "aura-sphere", "dark-pulse", "dragon-pulse", "heal-pulse", "origin-pulse",
                "oblivion-wing", "water-pulse", "terrain-pulse"
        );
        return pulseMoves.contains(move.id().toLowerCase());
    }

    private boolean isBiteMove(Move move) {
        Set<String> biteMoves = Set.of(
                "bite", "crunch", "fire-fang", "ice-fang", "thunder-fang", "poison-fang",
                "hyper-fang", "super-fang", "psychic-fangs", "fishious-rend", "jaw-lock"
        );
        return biteMoves.contains(move.id().toLowerCase());
    }

    private boolean isRecoilMove(Move move) {
        Set<String> recoilMoves = Set.of(
                "flare-blitz", "brave-bird", "wild-charge", "head-smash", "take-down",
                "double-edge", "volt-tackle", "submission", "struggle"
        );
        return recoilMoves.contains(move.id().toLowerCase());
    }

    private boolean isHighCritMove(Move move) {
        Set<String> highCrit = Set.of(
                "slash", "razor-leaf", "crabhammer", "karate-chop", "razor-wind",
                "aeroblast", "cross-chop", "night-slash", "psycho-cut", "leaf-blade",
                "stone-edge", "shadow-claw", "spacial-rend", "air-cutter",
                "blaze-kick", "poison-tail", "attack-order", "x-scissor"
        );
        return highCrit.contains(move.id().toLowerCase());
    }

    private boolean hasSheerForceEffect(Move move) {
        Set<String> sfMoves = Set.of(
                "air-slash", "bite", "dark-pulse", "dragon-rush", "extrasensory", "fake-out",
                "fire-fang", "headbutt", "hyper-fang", "ice-fang", "iron-head", "needle-arm",
                "rock-slide", "rolling-kick", "snore", "stomp", "thunder-fang", "twister", "zen-headbutt",
                "blaze-kick", "ember", "fire-blast", "fire-punch", "flame-wheel", "flamethrower",
                "flame-charge", "heat-wave", "lava-plume", "mystical-fire", "sacred-fire", "scald",
                "blizzard", "ice-beam", "ice-punch", "powder-snow", "tri-attack",
                "body-slam", "bounce", "discharge", "force-palm", "glare", "lick",
                "nuzzle", "spark", "thunder", "thunderbolt", "thunder-punch", "tri-attack",
                "cross-poison", "gunk-shot", "poison-jab", "poison-sting", "sludge", "sludge-bomb",
                "sludge-wave", "smog", "twineedle",
                "aurora-beam", "bubble", "bubble-beam", "constrict", "crunch", "crush-claw",
                "earth-power", "energy-ball", "flash-cannon", "icy-wind", "iron-tail",
                "lunge", "mirror-shot", "mud-bomb", "mud-shot", "muddy-water", "octazooka",
                "psychic", "rock-smash", "rock-tomb", "shadow-ball", "snarl", "struggle-bug",
                "thunder-shock", "water-pulse"
        );
        return sfMoves.contains(move.id().toLowerCase());
    }

    private boolean isSoundMove(Move move) {
        Set<String> soundMoves = Set.of(
                "hyper-voice", "boomburst", "bug-buzz", "chatter", "echoed-voice",
                "grass-whistle", "growl", "heal-bell", "metal-sound", "perish-song",
                "relic-song", "screech", "sing", "snore", "sparkling-aria",
                "supersonic", "uproar", "round"
        );
        return soundMoves.contains(move.id().toLowerCase());
    }
}
