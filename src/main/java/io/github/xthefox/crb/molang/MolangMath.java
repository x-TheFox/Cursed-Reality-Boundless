package io.github.xthefox.crb.molang;

import io.github.xthefox.crb.molang.Molang.Expr;

/** {@code math.*} functions. Angles are degrees, as in Bedrock. Unknown functions return 0. */
final class MolangMath {
    private MolangMath() {}

    static Expr call(String name, Expr[] a) {
        String n = name.startsWith("math.") ? name.substring(5) : name;
        Expr x = arg(a, 0), y = arg(a, 1), z = arg(a, 2);
        Expr f = switch (n) {
            case "abs" -> c -> Math.abs(x.eval(c));
            case "sin" -> c -> Math.sin(Math.toRadians(x.eval(c)));
            case "cos" -> c -> Math.cos(Math.toRadians(x.eval(c)));
            case "asin" -> c -> Math.toDegrees(Math.asin(x.eval(c)));
            case "acos" -> c -> Math.toDegrees(Math.acos(x.eval(c)));
            case "atan" -> c -> Math.toDegrees(Math.atan(x.eval(c)));
            case "atan2" -> c -> Math.toDegrees(Math.atan2(x.eval(c), y.eval(c)));
            case "ceil" -> c -> Math.ceil(x.eval(c));
            case "floor" -> c -> Math.floor(x.eval(c));
            case "round" -> c -> Math.round(x.eval(c));
            case "trunc" -> c -> (long) x.eval(c);
            case "sqrt" -> c -> Math.sqrt(Math.max(0, x.eval(c)));
            case "exp" -> c -> Math.exp(x.eval(c));
            case "ln" -> c -> Math.log(x.eval(c));
            case "pow" -> c -> Math.pow(x.eval(c), y.eval(c));
            case "mod" -> c -> {
                double d = y.eval(c);
                return d == 0 ? 0 : x.eval(c) % d;
            };
            case "min" -> c -> Math.min(x.eval(c), y.eval(c));
            case "max" -> c -> Math.max(x.eval(c), y.eval(c));
            case "clamp" -> c -> Math.max(y.eval(c), Math.min(z.eval(c), x.eval(c)));
            case "lerp" -> c -> {
                double t = z.eval(c);
                return x.eval(c) + (y.eval(c) - x.eval(c)) * t;
            };
            case "lerprotate" -> c -> {
                double s = x.eval(c), e = y.eval(c), t = z.eval(c);
                double d = ((e - s) % 360 + 540) % 360 - 180;
                return s + d * t;
            };
            case "inverse_lerp" -> c -> {
                double s = x.eval(c), e = y.eval(c);
                return e == s ? 0 : (z.eval(c) - s) / (e - s);
            };
            case "hermite_blend" -> c -> {
                double t = x.eval(c);
                return 3 * t * t - 2 * t * t * t;
            };
            case "sign" -> c -> Math.signum(x.eval(c));
            case "random" -> c -> {
                double lo = x.eval(c), hi = y.eval(c);
                return lo + (hi - lo) * c.random().nextDouble();
            };
            case "random_integer" -> c -> {
                long lo = Math.round(x.eval(c)), hi = Math.round(y.eval(c));
                if (lo > hi) { long t = lo; lo = hi; hi = t; }
                return lo + c.random().nextLong(hi - lo + 1);
            };
            case "die_roll" -> c -> {
                int num = (int) x.eval(c);
                double lo = y.eval(c), hi = z.eval(c), sum = 0;
                for (int i = 0; i < num; i++) sum += lo + (hi - lo) * c.random().nextDouble();
                return sum;
            };
            case "die_roll_integer" -> c -> {
                int num = (int) x.eval(c);
                long lo = Math.round(y.eval(c)), hi = Math.round(z.eval(c));
                if (lo > hi) { long t = lo; lo = hi; hi = t; }
                long sum = 0;
                for (int i = 0; i < num; i++) sum += lo + c.random().nextLong(hi - lo + 1);
                return sum;
            };
            default -> null;
        };
        return f == null ? Molang.ZERO : f;
    }

    private static Expr arg(Expr[] a, int i) {
        return i < a.length ? a[i] : Molang.ZERO;
    }
}
