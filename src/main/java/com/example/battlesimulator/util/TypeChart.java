package com.example.battlesimulator.util;

import com.example.battlesimulator.model.enums.Type;

public class TypeChart {

    public static double getMultiplier(Type attackType, Type defenseType) {
        if (defenseType == Type.NONE || defenseType == null) {
            return 1.0;
        }

        return switch (attackType) {
            case NORMAL -> switch (defenseType) {
                case ROCK, STEEL -> 0.5;
                case GHOST -> 0.0;
                default -> 1.0;
            };
            case FIRE -> switch (defenseType) {
                case GRASS, BUG, ICE, STEEL -> 2.0;
                case FIRE, WATER, ROCK, DRAGON -> 0.5;
                default -> 1.0;
            };
            case WATER -> switch (defenseType) {
                case FIRE, GROUND, ROCK -> 2.0;
                case WATER, GRASS, DRAGON -> 0.5;
                default -> 1.0;
            };
            case GRASS -> switch (defenseType) {
                case WATER, GROUND, ROCK -> 2.0;
                case FIRE, GRASS, POISON, FLYING, BUG, DRAGON, STEEL -> 0.5;
                default -> 1.0;
            };
            case ELECTRIC -> switch (defenseType) {
                case WATER, FLYING -> 2.0;
                case ELECTRIC, GRASS, DRAGON -> 0.5;
                case GROUND -> 0.0;
                default -> 1.0;
            };
            case ICE -> switch (defenseType) {
                case GRASS, GROUND, FLYING, DRAGON -> 2.0;
                case FIRE, WATER, ICE, STEEL -> 0.5;
                default -> 1.0;
            };
            case FIGHTING -> switch (defenseType) {
                case NORMAL, ICE, ROCK, DARK, STEEL -> 2.0;
                case POISON, FLYING, PSYCHIC, BUG, FAIRY -> 0.5;
                case GHOST -> 0.0;
                default -> 1.0;
            };
            case POISON -> switch (defenseType) {
                case GRASS, FAIRY -> 2.0;
                case POISON, GROUND, ROCK, GHOST -> 0.5;
                case STEEL -> 0.0;
                default -> 1.0;
            };
            case GROUND -> switch (defenseType) {
                case FIRE, ELECTRIC, POISON, ROCK, STEEL -> 2.0;
                case GRASS, BUG -> 0.5;
                case FLYING -> 0.0;
                default -> 1.0;
            };
            case FLYING -> switch (defenseType) {
                case GRASS, FIGHTING, BUG -> 2.0;
                case ELECTRIC, ROCK, STEEL -> 0.5;
                default -> 1.0;
            };
            case PSYCHIC -> switch (defenseType) {
                case FIGHTING, POISON -> 2.0;
                case PSYCHIC, STEEL -> 0.5;
                case DARK -> 0.0;
                default -> 1.0;
            };
            case BUG -> switch (defenseType) {
                case GRASS, PSYCHIC, DARK -> 2.0;
                case FIRE, FIGHTING, FLYING, GHOST, STEEL, FAIRY -> 0.5;
                default -> 1.0;
            };
            case ROCK -> switch (defenseType) {
                case FIRE, ICE, FLYING, BUG -> 2.0;
                case FIGHTING, GROUND, STEEL -> 0.5;
                default -> 1.0;
            };
            case GHOST -> switch (defenseType) {
                case PSYCHIC, GHOST -> 2.0;
                case DARK -> 0.5;
                case NORMAL -> 0.0;
                default -> 1.0;
            };
            case DRAGON -> switch (defenseType) {
                case DRAGON -> 2.0;
                case STEEL -> 0.5;
                case FAIRY -> 0.0;
                default -> 1.0;
            };
            case DARK -> switch (defenseType) {
                case PSYCHIC, GHOST -> 2.0;
                case FIGHTING, DARK, FAIRY -> 0.5;
                default -> 1.0;
            };
            case STEEL -> switch (defenseType) {
                case ICE, ROCK, FAIRY -> 2.0;
                case FIRE, WATER, ELECTRIC, STEEL -> 0.5;
                default -> 1.0;
            };
            case FAIRY -> switch (defenseType) {
                case FIGHTING, DRAGON, DARK -> 2.0;
                case FIRE, POISON, STEEL -> 0.5;
                default -> 1.0;
            };
            default -> 1.0;
        };
    }
}