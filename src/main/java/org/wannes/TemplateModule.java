package org.wannes;

import org.wannes.plugModCore.api.Module;
import org.wannes.plugModCore.api.ModuleContext;

import java.io.File;
import java.util.logging.Logger;

/**
 * Template Module - Kopieer deze klasse als basis voor je eigen modules
 *
 * Stappen om je module werkend te krijgen:
 * 1. Hernoem deze klasse naar jouw module naam (bijv. MyAwesomeModule)
 * 2. Update module.info.yml met je module details
 * 3. Voeg je eigen logica toe in onEnable() en onDisable()
 * 4. Compileer: mvn clean package
 * 5. Zet de JAR in plugins/PlugModCore/modules/
 * 6. Laad de module met: /module load template
 *
 * @author Your Name Here
 * @version 1.0.0
 */
public class TemplateModule implements Module {

    private ModuleContext context;
    private Logger logger;
    private File dataFolder;

    /**
     * Constructor - IMPORTANT: moet een no-args constructor zijn!
     * PlugModCore zal dit aanroepen via reflection
     */
    public TemplateModule() {
        // Initialisatie gebeurt in onEnable() nadat context bekend is
    }

    /**
     * Called wanneer de module ingeschakeld wordt
     * Dit gebeurt na het starten van PlugModCore of wanneer je /module enable <id> gebruikt
     *
     * @param context ModuleContext - bevat module informatie, logger, etc
     */
    @Override
    public void onEnable(ModuleContext context) {
        try {
            // Sla context op voor later gebruik
            this.context = context;
            this.logger = context.getLogger();
            this.dataFolder = context.getDataFolder();

            // Log dat module geladen is
            logger.info("╔════════════════════════════════════╗");
            logger.info("║   " + context.getModuleName() + " v" + context.getVersion() + " geladen!");
            logger.info("╚════════════════════════════════════╝");

            // TODO: Voeg je eigen startup logica toe:
            // - Config files laden
            // - Event listeners registreren (Bukkit events)
            // - Commands registreren
            // - Database verbindingen initialiseren
            // - Web API endpoints registreren
            // etc...

            logger.info("Module is gereed!");

        } catch (Exception e) {
            logger.severe("Fout bij het laden van " + context.getModuleName() + "!");
            e.printStackTrace();
        }
    }

    /**
     * Called wanneer de module uitgeschakeld wordt
     * Dit gebeurt wanneer je /module disable <id> gebruikt of server shutdowns
     *
     * Zorg dat je alle resources proper opschoondt:
     * - Event listeners deregistreren
     * - Database verbindingen sluiten
     * - Threads stoppen
     * - Data opslaan
     */
    @Override
    public void onDisable() {
        try {
            if (logger != null && context != null) {
                logger.info("Module " + context.getModuleName() + " wordt uitgeschakeld...");
            }

            // TODO: Voeg je eigen shutdown logica toe:
            // - Threads stoppen
            // - Database verbindingen sluiten
            // - Data wegschrijven
            // - Event listeners deregistreren
            // etc...

            if (logger != null) {
                logger.info("Module succesvol uitgeschakeld");
            }

        } catch (Exception e) {
            if (logger != null) {
                logger.severe("Fout bij het uitschakelen van module!");
                e.printStackTrace();
            }
        }
    }

    // ========== HELPER METHODS ==========

    /**
     * Get module logger
     */
    public Logger getLogger() {
        return this.logger;
    }

    /**
     * Get module data folder (voor config files etc)
     */
    public File getDataFolder() {
        return this.dataFolder;
    }

    /**
     * Get module context
     */
    public ModuleContext getContext() {
        return this.context;
    }
}
