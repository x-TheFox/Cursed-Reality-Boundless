package io.github.xthefox.crb.progression;

import java.util.Locale;

/** Sorcerer grade, JJA's promotion ladder (Grade 4 → Special Grade). */
public enum Grade {
    GRADE_4, GRADE_3, GRADE_2, GRADE_1, SPECIAL;

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Grade parse(String s) {
        String n = s.toLowerCase(Locale.ROOT).replace("grade", "").replace("_", "").trim();
        return switch (n) {
            case "4" -> GRADE_4;
            case "3" -> GRADE_3;
            case "2" -> GRADE_2;
            case "1" -> GRADE_1;
            case "special", "s" -> SPECIAL;
            default -> throw new IllegalArgumentException("Unknown grade: " + s);
        };
    }
}
