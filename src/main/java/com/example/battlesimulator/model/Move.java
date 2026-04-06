package com.example.battlesimulator.model;

import com.example.battlesimulator.model.enums.MoveCategory;
import com.example.battlesimulator.model.enums.Type;

public record Move(
        String id,          // e.g., "earthquake"
        String name,        // e.g., "Earthquake"
        Type type,
        MoveCategory category,
        int basePower,      // 0 for status moves
        int accuracy,       // 100 is standard, 0 means it always hits (like Swift)
        int pp,             // Max Power Points
        int priority        // Standard is 0. Quick attack is 1, Roar is -6.
) {}