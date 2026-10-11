package tech.mista.ccmanager.client.config

import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.ControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder
import dev.isxander.yacl3.api.controller.StringControllerBuilder
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import tech.mista.ccmanager.client.link.Link

/**
 * Builds the settings screen. Opened from Mod Menu or `/ccm config`.
 *
 * Each option is a binding: a default, a getter and a setter into the live
 * [CcmConfig]. YACL edits a pending copy while the screen is open and only
 * calls the setters when you press Save, and then our `save` hook.
 */
object ConfigScreen {

    fun create(parent: Screen?): Screen {
        val cfg = CcmConfig.get()
        val def = CcmConfig()

        val connection = ConfigCategory.createBuilder()
            .name(Text.literal("Connection"))
            .option(text("Server address", "host:port of the ccmanager backend, without ws://",
                def.host, { cfg.host }, { cfg.host = it }))
            .option(bool("Use TLS", "Connect with wss:// instead of ws://",
                def.useTls, { cfg.useTls }, { cfg.useTls = it }))
            .option(text("Password", "Sent as the Authorization header. Stored in plain text in config/ccmanager.json5",
                def.password, { cfg.password }, { cfg.password = it }))
            .option(bool("Connect on world join", "Off: only connect with /ccm connect",
                def.autoConnect, { cfg.autoConnect }, { cfg.autoConnect = it }))
            .option(slider("Poll interval (ms)", "How often to ask the backend for all turtles",
                def.pollMillis, { cfg.pollMillis }, { cfg.pollMillis = it }, 250, 5000, 250))
            .build()

        val display = ConfigCategory.createBuilder()
            .name(Text.literal("Display"))
            .option(bool("Outline boxes", "Wireframe box around every turtle",
                def.showBoxes, { cfg.showBoxes }, { cfg.showBoxes = it }))
            .option(bool("Filled boxes", "Translucent box around every turtle",
                def.fillBoxes, { cfg.fillBoxes }, { cfg.fillBoxes = it }))
            .option(bool("Tracers", "Line from your crosshair to every turtle",
                def.showTracers, { cfg.showTracers }, { cfg.showTracers = it }))
            .option(bool("Nametags", "Name above every turtle. The full panel shows on hover regardless",
                def.showNametags, { cfg.showNametags }, { cfg.showNametags = it }))
            .option(bool("Chunk borders", "Outline of the backend's 16x16x16 chunk you're in, with a grid on floor and ceiling",
                def.showChunkBorders, { cfg.showChunkBorders }, { cfg.showChunkBorders = it }))
            .option(bool("Scan areas", "Translucent cube over each turtle's last scan. /ccm clearscans removes them",
                def.showScans, { cfg.showScans }, { cfg.showScans = it }))
            .option(bool("Turtles through walls", "Boxes and tracers drawn on top of terrain",
                def.turtlesThroughWalls, { cfg.turtlesThroughWalls }, { cfg.turtlesThroughWalls = it }))
            .option(bool("Nametags through walls", "Nametags drawn on top of terrain. The hover panel always is",
                def.nametagsThroughWalls, { cfg.nametagsThroughWalls }, { cfg.nametagsThroughWalls = it }))
            .option(bool("Scan areas through walls", "Scan cubes drawn on top of terrain",
                def.scansThroughWalls, { cfg.scansThroughWalls }, { cfg.scansThroughWalls = it }))
            .option(bool("Chunk borders through walls", "Chunk outline and grid drawn on top of terrain",
                def.chunksThroughWalls, { cfg.chunksThroughWalls }, { cfg.chunksThroughWalls = it }))
            .option(slider("Nametag range", "Nametags only show on turtles closer than this many blocks",
                def.labelRange, { cfg.labelRange }, { cfg.labelRange = it }, 8, 256, 8))
            .option(slider("Hover range", "How far away you can point at a turtle to see its panel",
                def.hoverRange, { cfg.hoverRange }, { cfg.hoverRange = it }, 8, 256, 8))
            .option(bool("Status line", "Connection state in the top left corner",
                def.showHud, { cfg.showHud }, { cfg.showHud = it }))
            .build()

        return YetAnotherConfigLib.createBuilder()
            .title(Text.literal("CCManager"))
            .category(connection)
            .category(display)
            .save {
                CcmConfig.HANDLER.save()
                Link.restartIfRunning() // pick up a changed address or password
            }
            .build()
            .generateScreen(parent)
    }

    private fun <T : Any> option(
        name: String,
        description: String,
        default: T,
        get: () -> T,
        set: (T) -> Unit,
        controller: (Option<T>) -> ControllerBuilder<T>,
    ): Option<T> = Option.createBuilder<T>()
        .name(Text.literal(name))
        .description(OptionDescription.of(Text.literal(description)))
        .binding(default, { get() }, { set(it) })
        .controller { controller(it) }
        .build()

    private fun bool(name: String, description: String, default: Boolean, get: () -> Boolean, set: (Boolean) -> Unit) =
        option(name, description, default, get, set) { TickBoxControllerBuilder.create(it) }

    private fun text(name: String, description: String, default: String, get: () -> String, set: (String) -> Unit) =
        option(name, description, default, get, set) { StringControllerBuilder.create(it) }

    private fun slider(
        name: String, description: String, default: Int, get: () -> Int, set: (Int) -> Unit,
        min: Int, max: Int, step: Int,
    ) = option(name, description, default, get, set) {
        IntegerSliderControllerBuilder.create(it).range(min, max).step(step)
    }
}
