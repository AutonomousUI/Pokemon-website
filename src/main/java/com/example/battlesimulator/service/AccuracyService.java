package com.example.battlesimulator.service;

import com.example.battlesimulator.model.BattlePokemon;
import com.example.battlesimulator.model.Move;
import com.example.battlesimulator.model.enums.Ability;
import com.example.battlesimulator.model.enums.MoveCategory;
import org.springframework.stereotype.Service;

import java.util.Random;

@Service
public class AccuracyService {

    private final Random random = new Random();

    public boolean doesMoveHit(BattlePokemon attacker, BattlePokemon defender, Move move) {
        if (attacker.getAbility() == Ability.NO_GUARD || defender.getAbility() == Ability.NO_GUARD) {
            return true;
        }

        // 1. Certain moves (like Swift or Aerial Ace) never miss.
        // In most APIs, this is represented by an accuracy of 0 or a flag.
        if (move.accuracy() <= 0) {
            return true;
        }

        // 1b. Hustle lowers physical move accuracy by 0.8x
        double hustleFactor = 1.0;
        if (attacker.getAbility() == com.example.battlesimulator.model.enums.Ability.HUSTLE
                && move.category() == com.example.battlesimulator.model.enums.MoveCategory.PHYSICAL) {
            hustleFactor = 0.8;
        }

        double compoundEyesFactor = attacker.getAbility() == Ability.COMPOUND_EYES ? 1.3 : 1.0;

        // 2. Calculate the net stage difference
        // E.g., if attacker is at -1 Accuracy and defender is at +1 Evasion, the net is -2.
        int attackerAccuracy = attacker.getAccuracyStage();
        int defenderEvasion = defender.getEvasionStage();
        if (attacker.getAbility() == Ability.KEEN_EYE && defenderEvasion > 0) {
            defenderEvasion = 0;
        }
        int netStage = attackerAccuracy - defenderEvasion;

        // Stages cannot go below -6 or above +6
        netStage = Math.max(-6, Math.min(6, netStage));

        // 3. Calculate the stage multiplier based on the official Gen 5+ formula
        // Positive stages add to the numerator, negative stages add to the denominator
        double multiplier;
        if (netStage >= 0) {
            multiplier = (3.0 + netStage) / 3.0; // E.g., +1 stage = 4/3 = 1.33x
        } else {
            multiplier = 3.0 / (3.0 - netStage); // E.g., -1 stage = 3/4 = 0.75x
        }

        // 4. Calculate the final modified accuracy
        double finalAccuracy = move.accuracy() * multiplier * hustleFactor * compoundEyesFactor;
        if (defender.getAbility() == Ability.WONDER_SKIN && move.category() == MoveCategory.STATUS) {
            finalAccuracy = Math.min(finalAccuracy, 50.0);
        }

        // 5. Roll a random number between 1 and 100
        int roll = random.nextInt(100) + 1;

        // The move hits if the random roll is less than or equal to the final accuracy
        return roll <= finalAccuracy;
    }
}
