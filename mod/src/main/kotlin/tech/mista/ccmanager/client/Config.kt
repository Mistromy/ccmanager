package tech.mista.ccmanager.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.ControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder
import dev.isxander.yacl3.api.controller.StringControllerBuilder
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder
import dev.isxander.yacl3.config.v2.api.ConfigClassHandler
import dev.isxander.yacl3.config.v2.api.SerialEntry
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import kotlin.reflect.KMutableProperty1

/**
 * Every setting, saved to config/ccmanager.json5. YACL fills the fields by
 * reflection, hence @JvmField (a plain public field instead of a property).
 */
class CcmConfig {
    @JvmField @SerialEntry var host = "localhost:6767"
    @JvmField @SerialEntry var useTls = false
    @JvmField @SerialEntry var password = ""
    @JvmField @SerialEntry var autoConnect = true
    @JvmField @SerialEntry var pollMillis = 1000
    @JvmField @SerialEntry var scanRadius = 8

    @JvmField @SerialEntry var showBoxes = true
    @JvmField @SerialEntry var fillBoxes = false
    @JvmField @SerialEntry var showTracers = false
    @JvmField @SerialEntry var showNametags = true
    @JvmField @SerialEntry var showChunkBorders = false
    @JvmField @SerialEntry var showScans = true
    @JvmField @SerialEntry var showPathfind = true
    @JvmField @SerialEntry var pathfindThroughWalls = true
    @JvmField @SerialEntry var turtlesThroughWalls = true
    @JvmField @SerialEntry var nametagsThroughWalls = true
    @JvmField @SerialEntry var scansThroughWalls = true
    @JvmField @SerialEntry var chunksThroughWalls = false
    @JvmField @SerialEntry var labelRange = 32
    @JvmField @SerialEntry var hoverRange = 32
    @JvmField @SerialEntry var showHud = true

    companion object {
        val HANDLER: ConfigClassHandler<CcmConfig> = ConfigClassHandler.createBuilder(CcmConfig::class.java)
            .id(Identifier("ccmanager", "config"))
            .serializer {
                GsonConfigSerializerBuilder.create(it)
                    .setPath(FabricLoader.getInstance().configDir.resolve("ccmanager.json5"))
                    .setJson5(true)
                    .build()
            }
            .build()

        fun get(): CcmConfig = HANDLER.instance()
    }
}

/** The YACL settings screen, opened from Mod Menu or /ccm config. */
object ConfigScreen {
    private val defaults = CcmConfig()

    fun create(parent: Screen?): Screen = YetAnotherConfigLib.createBuilder()
        .title(Text.literal("CCManager"))
        .category(ConfigCategory.createBuilder().name(Text.literal("Connection"))
            .option(text("Server address", "host:port of the backend, without ws://", CcmConfig::host))
            .option(bool("Use TLS", "wss:// instead of ws://", CcmConfig::useTls))
            .option(text("Password", "Sent as the Authorization header. Stored in plain text", CcmConfig::password))
            .option(bool("Connect on world join", "Off: only connect with /ccm connect", CcmConfig::autoConnect))
            .option(slider("Poll interval (ms)", "How often to ask for all turtles", CcmConfig::pollMillis, 250, 5000, 250))
            .option(slider("Scan radius", "Radius the menu's Scan asks for; 8 scans 17³ blocks", CcmConfig::scanRadius, 1, 16, 1))
            .build())
        .category(ConfigCategory.createBuilder().name(Text.literal("Display"))
            .option(bool("Outline boxes", "Wireframe box around every turtle", CcmConfig::showBoxes))
            .option(bool("Filled boxes", "Translucent box around every turtle", CcmConfig::fillBoxes))
            .option(bool("Tracers", "Line from your crosshair to every turtle", CcmConfig::showTracers))
            .option(bool("Nametags", "Name above every turtle. The hover panel shows regardless", CcmConfig::showNametags))
            .option(bool("Chunk borders", "The 16³ chunk you're in, and the 3x3x3 around it", CcmConfig::showChunkBorders))
            .option(bool("Scan areas", "Cube over each turtle's last scan. /ccm clearscans removes them", CcmConfig::showScans))
            .option(bool("Pathfinder search", "Blocks the last pathfind explored. /ccm clearpath removes them", CcmConfig::showPathfind))
            .option(bool("Pathfinder search through walls", "", CcmConfig::pathfindThroughWalls))
            .option(bool("Turtles through walls", "Boxes and tracers on top of terrain", CcmConfig::turtlesThroughWalls))
            .option(bool("Nametags through walls", "The hover panel always is", CcmConfig::nametagsThroughWalls))
            .option(bool("Scan areas through walls", "", CcmConfig::scansThroughWalls))
            .option(bool("Chunk borders through walls", "", CcmConfig::chunksThroughWalls))
            .option(slider("Nametag range", "Blocks", CcmConfig::labelRange, 8, 256, 8))
            .option(slider("Hover range", "Blocks", CcmConfig::hoverRange, 8, 256, 8))
            .option(bool("Status line", "Connection state, top left", CcmConfig::showHud))
            .build())
        .save {
            CcmConfig.HANDLER.save()
            Link.restartIfRunning() // pick up a changed address or password
        }
        .build()
        .generateScreen(parent)

    // One option bound to one config field: default from a fresh CcmConfig, get/set on the live one.
    private fun <T : Any> option(
        name: String, desc: String, field: KMutableProperty1<CcmConfig, T>,
        controller: (Option<T>) -> ControllerBuilder<T>,
    ): Option<T> = Option.createBuilder<T>()
        .name(Text.literal(name))
        .description(OptionDescription.of(Text.literal(desc)))
        .binding(field.get(defaults), { field.get(CcmConfig.get()) }, { field.set(CcmConfig.get(), it) })
        .controller { controller(it) }
        .build()

    private fun bool(name: String, desc: String, field: KMutableProperty1<CcmConfig, Boolean>) =
        option(name, desc, field) { TickBoxControllerBuilder.create(it) }

    private fun text(name: String, desc: String, field: KMutableProperty1<CcmConfig, String>) =
        option(name, desc, field) { StringControllerBuilder.create(it) }

    private fun slider(name: String, desc: String, field: KMutableProperty1<CcmConfig, Int>, min: Int, max: Int, step: Int) =
        option(name, desc, field) { IntegerSliderControllerBuilder.create(it).range(min, max).step(step) }
}

/** Found through the "modmenu" entrypoint; never loaded when Mod Menu isn't installed. */
class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> = ConfigScreenFactory<Screen> { ConfigScreen.create(it) }
}
