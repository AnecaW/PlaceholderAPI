package me.clip.placeholderapi.adventure;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Minimal shim for `BukkitAudiences` used by the port so code can call `getAdventure().player(player).sendMessage(component)`.
 */
public final class BukkitAudiencesShim {

    public Audience player(Player player) {
        return new Audience(player);
    }

    public static final class Audience {
        private final Player player;

        public Audience(Player player) {
            this.player = player;
        }

        public void sendMessage(Component component) {
            try {
                // fallback: send plain text representation
                player.sendMessage(component == null ? "" : component.toString());
            } catch (NoSuchMethodError e) {
                player.sendMessage(component == null ? "" : component.toString());
            }
        }
    }
}
