package com.botfury;
import java.util.Set;
import java.util.Locale;
import java.util.stream.Collectors;

final class BotCollisionPolicy {
    private static volatile Set<String> names = Set.of();
    static void replace(java.util.Collection<String> bots) {
        names = bots.stream().filter(n -> n != null && n.matches("[A-Za-z0-9_]{1,16}"))
                .map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
    static boolean known(String name) { return names.contains(name.toLowerCase(Locale.ROOT)); }
    static boolean cancel(boolean firstLocal, boolean secondLocal, String first, String second) {
        return firstLocal && known(second) || secondLocal && known(first);
    }
}
