package tech.mista.ccmanager.client.config

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/**
 * Gives Mod Menu our config screen. Mod Menu finds this class through the
 * "modmenu" entrypoint in fabric.mod.json. Without Mod Menu installed nothing
 * ever loads this class, so its imports can't crash the game.
 */
class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> ConfigScreen.create(parent) }
}
