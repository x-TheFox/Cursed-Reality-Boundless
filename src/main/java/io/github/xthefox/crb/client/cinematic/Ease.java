package io.github.xthefox.crb.client.cinematic;

import net.minecraft.util.Mth;

import java.util.Locale;

/** Bedrock camera ease types ({@code camera set ... ease <seconds> <type>}), easings.net formulas. */
public enum Ease {
    LINEAR, SPRING,
    IN_QUAD, OUT_QUAD, IN_OUT_QUAD,
    IN_CUBIC, OUT_CUBIC, IN_OUT_CUBIC,
    IN_QUART, OUT_QUART, IN_OUT_QUART,
    IN_QUINT, OUT_QUINT, IN_OUT_QUINT,
    IN_SINE, OUT_SINE, IN_OUT_SINE,
    IN_EXPO, OUT_EXPO, IN_OUT_EXPO,
    IN_CIRC, OUT_CIRC, IN_OUT_CIRC,
    IN_BOUNCE, OUT_BOUNCE, IN_OUT_BOUNCE,
    IN_BACK, OUT_BACK, IN_OUT_BACK,
    IN_ELASTIC, OUT_ELASTIC, IN_OUT_ELASTIC;

    private static final double C1 = 1.70158, C2 = C1 * 1.525, C3 = C1 + 1, C4 = (2 * Math.PI) / 3, C5 = (2 * Math.PI) / 4.5;

    public static Ease parse(String name) {
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return LINEAR;
        }
    }

    public double apply(double x) {
        x = Mth.clamp(x, 0, 1);
        return switch (this) {
            case LINEAR -> x;
            case SPRING -> 1 - Math.cos(x * Math.PI * 4.5) * Math.exp(-6 * x);
            case IN_QUAD -> x * x;
            case OUT_QUAD -> 1 - (1 - x) * (1 - x);
            case IN_OUT_QUAD -> x < 0.5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2;
            case IN_CUBIC -> x * x * x;
            case OUT_CUBIC -> 1 - Math.pow(1 - x, 3);
            case IN_OUT_CUBIC -> x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
            case IN_QUART -> Math.pow(x, 4);
            case OUT_QUART -> 1 - Math.pow(1 - x, 4);
            case IN_OUT_QUART -> x < 0.5 ? 8 * Math.pow(x, 4) : 1 - Math.pow(-2 * x + 2, 4) / 2;
            case IN_QUINT -> Math.pow(x, 5);
            case OUT_QUINT -> 1 - Math.pow(1 - x, 5);
            case IN_OUT_QUINT -> x < 0.5 ? 16 * Math.pow(x, 5) : 1 - Math.pow(-2 * x + 2, 5) / 2;
            case IN_SINE -> 1 - Math.cos(x * Math.PI / 2);
            case OUT_SINE -> Math.sin(x * Math.PI / 2);
            case IN_OUT_SINE -> -(Math.cos(Math.PI * x) - 1) / 2;
            case IN_EXPO -> x == 0 ? 0 : Math.pow(2, 10 * x - 10);
            case OUT_EXPO -> x == 1 ? 1 : 1 - Math.pow(2, -10 * x);
            case IN_OUT_EXPO -> x == 0 ? 0 : x == 1 ? 1 : x < 0.5 ? Math.pow(2, 20 * x - 10) / 2 : (2 - Math.pow(2, -20 * x + 10)) / 2;
            case IN_CIRC -> 1 - Math.sqrt(1 - x * x);
            case OUT_CIRC -> Math.sqrt(1 - Math.pow(x - 1, 2));
            case IN_OUT_CIRC -> x < 0.5 ? (1 - Math.sqrt(1 - Math.pow(2 * x, 2))) / 2 : (Math.sqrt(1 - Math.pow(-2 * x + 2, 2)) + 1) / 2;
            case IN_BOUNCE -> 1 - bounceOut(1 - x);
            case OUT_BOUNCE -> bounceOut(x);
            case IN_OUT_BOUNCE -> x < 0.5 ? (1 - bounceOut(1 - 2 * x)) / 2 : (1 + bounceOut(2 * x - 1)) / 2;
            case IN_BACK -> C3 * x * x * x - C1 * x * x;
            case OUT_BACK -> 1 + C3 * Math.pow(x - 1, 3) + C1 * Math.pow(x - 1, 2);
            case IN_OUT_BACK -> x < 0.5 ? (Math.pow(2 * x, 2) * ((C2 + 1) * 2 * x - C2)) / 2
                    : (Math.pow(2 * x - 2, 2) * ((C2 + 1) * (x * 2 - 2) + C2) + 2) / 2;
            case IN_ELASTIC -> x == 0 ? 0 : x == 1 ? 1 : -Math.pow(2, 10 * x - 10) * Math.sin((x * 10 - 10.75) * C4);
            case OUT_ELASTIC -> x == 0 ? 0 : x == 1 ? 1 : Math.pow(2, -10 * x) * Math.sin((x * 10 - 0.75) * C4) + 1;
            case IN_OUT_ELASTIC -> x == 0 ? 0 : x == 1 ? 1 : x < 0.5
                    ? -(Math.pow(2, 20 * x - 10) * Math.sin((20 * x - 11.125) * C5)) / 2
                    : (Math.pow(2, -20 * x + 10) * Math.sin((20 * x - 11.125) * C5)) / 2 + 1;
        };
    }

    private static double bounceOut(double x) {
        double n1 = 7.5625, d1 = 2.75;
        if (x < 1 / d1) return n1 * x * x;
        if (x < 2 / d1) return n1 * (x -= 1.5 / d1) * x + 0.75;
        if (x < 2.5 / d1) return n1 * (x -= 2.25 / d1) * x + 0.9375;
        return n1 * (x -= 2.625 / d1) * x + 0.984375;
    }
}
