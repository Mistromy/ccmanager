package tech.mista.ccmanager.client.config

import dev.isxander.yacl3.config.v2.api.ConfigClassHandler
import dev.isxander.yacl3.config.v2.api.SerialEntry
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.util.Identifier

/**
 * Every setting, saved to `config/ccmanager.json5`. YACL creates and fills an
 * instance by reflection, which is why every field is `@JvmField`: it turns
 * the Kotlin property into a plain public Java field YACL can write to.
 * The defaults here are also the "reset" values in the config screen.
 */
class CcmConfig {
    // connection
    @JvmField @SerialEntry var host: String = "localhost:6767"
    @JvmField @SerialEntry var useTls: Boolean = false
    @JvmField @SerialEntry var password: String = ""
    @JvmField @SerialEntry var autoConnect: Boolean = true
    @JvmField @SerialEntry var pollMillis: Int = 1000

    // display
    @JvmField @SerialEntry var showBoxes: Boolean = true
    @JvmField @SerialEntry var fillBoxes: Boolean = false
    @JvmField @SerialEntry var showTracers: Boolean = false
    @JvmField @SerialEntry var showNametags: Boolean = true
    @JvmField @SerialEntry var throughWalls: Boolean = true
    @JvmField @SerialEntry var labelRange: Int = 32
    @JvmField @SerialEntry var hoverRange: Int = 32
    @JvmField @SerialEntry var showHud: Boolean = true

    companion object {
        val HANDLER: ConfigClassHandler<CcmConfig> = ConfigClassHandler.createBuilder(CcmConfig::class.java)
            .id(Identifier("ccmanager", "config"))
            .serializer { handler ->
                GsonConfigSerializerBuilder.create(handler)
                    .setPath(FabricLoader.getInstance().configDir.resolve("ccmanager.json5"))
                    .setJson5(true)
                    .build()
            }
            .build()

        /** Always go through this rather than holding on to an instance. */
        fun get(): CcmConfig = HANDLER.instance()
    }
}
