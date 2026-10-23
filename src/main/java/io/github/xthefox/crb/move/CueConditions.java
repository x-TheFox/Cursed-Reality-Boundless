package io.github.xthefox.crb.move;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates conditions extracted from JJA selectors against a move's tags and scores.
 *
 * <ul>
 *   <li>{@code tag=x} / {@code tag=!x}: membership in the move's tag set (caster's JJA tags plus
 *       state the server logic sets, e.g. {@code blackflashed}).</li>
 *   <li>{@code score:k=range}: the move's score map; an unknown score fails, except that negative
 *       stun checks are treated as "not stunned".</li>
 *   <li>{@code type=!player}: the caster is not a player.</li>
 *   <li>{@code if:*} world tests (hit confirmations, block checks) fail and {@code unless:*} pass by
 *       default: a missed effect is better than a doubled one. Server logic flips either by setting
 *       the same string as a move tag (e.g. tag {@code if:entity} after a hit lands).</li>
 *   <li>Anything else ({@code family=}, {@code hasitem=}, {@code name=}) passes.</li>
 * </ul>
 */
public final class CueConditions {
    private static final Pattern SCORE = Pattern.compile("score:([A-Za-z0-9_]+)=(-?\\d+)?(\\.\\.)?(-?\\d+)?");

    private CueConditions() {}

    public static boolean test(List<String> conds, Set<String> tags, Map<String, Integer> scores) {
        for (String c : conds) {
            if (!testOne(c, tags, scores)) return false;
        }
        return true;
    }

    static boolean testOne(String c, Set<String> tags, Map<String, Integer> scores) {
        if (c.startsWith("tag=!")) return !tags.contains(c.substring(5));
        if (c.startsWith("tag=")) return tags.contains(c.substring(4));
        if (c.equals("type=!player")) return !tags.contains("player");
        if (c.startsWith("if:")) return tags.contains(c);
        if (c.startsWith("unless:")) return !tags.contains(c);
        if (c.startsWith("score:")) {
            Matcher m = SCORE.matcher(c);
            if (!m.matches()) return false;
            Integer v = scores.get(m.group(1));
            if (v == null) {
                if (!m.group(1).equals("Stun")) return false;
                v = 0; // a playing move's caster is not stunned (stun ends the move)
            }
            boolean range = m.group(3) != null;
            Integer lo = m.group(2) == null ? null : Integer.valueOf(m.group(2));
            Integer hi = range ? (m.group(4) == null ? null : Integer.valueOf(m.group(4))) : lo;
            return (lo == null || v >= lo) && (hi == null || v <= hi);
        }
        return true;
    }
}
