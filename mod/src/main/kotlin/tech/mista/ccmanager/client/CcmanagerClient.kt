package tech.mista.ccmanager.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import org.slf4j.LoggerFactory
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.WorldRenderer
import net.minecraft.text.Text
import net.minecraft.util.math.Box

class CcmanagerClient : ClientModInitializer {
    private val log = LoggerFactory.getLogger("ccmanager")

    override fun onInitializeClient() {
        log.info("CCManager Loaded")

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(literal("ccm").executes { ctx ->
                ctx.source.sendFeedback(Text.literal("Hello, World!"))
                1
            })
        }
        HudRenderCallback.EVENT.register { draw, _ ->
            draw.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, "ccmanager", 4, 4, 0xffffff)
        }
        WorldRenderEvents.AFTER_ENTITIES.register { ctx ->
            val consumers = ctx.consumers() ?: return@register
            val cam = ctx.camera().pos
            val matrices = ctx.matrixStack()
            matrices.push()
            matrices.translate(-cam.x, -cam.y, -cam.z) // world coords -> camera-relative
            WorldRenderer.drawBox(
                matrices, consumers.getBuffer(RenderLayer.getLines()),
                Box(0.0, 76.0, 0.0, 1.0, 77.0, 1.0), 1f, 0f, 0f, 1f,
            )
            matrices.pop()
        }
    }
}
