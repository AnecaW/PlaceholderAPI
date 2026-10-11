package me.clip.placeholderapi.replacer;

import org.bukkit.OfflinePlayer;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

public interface Replacer {

    enum Closure {
        BRACKET('{', '}'),
        PERCENT('%', '%');

        public final char head, tail;

        Closure(final char head, final char tail) {
            this.head = head;
            this.tail = tail;
        }
    }

    @NotNull
    String apply(@NotNull final String text, @Nullable final OfflinePlayer player,
                 @NotNull final Function<String, @Nullable PlaceholderExpansion> lookup);

}
