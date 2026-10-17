package tech.mista.ccmanager.client

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import org.lwjgl.glfw.GLFW
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The radial menu: hold the key, point, release. Right-clicking "Select"
 * swaps to the same actions for the turtle you're looking at, without
 * changing the selection.
 *
 * What the menu acts on is captured when it opens, since the camera stands
 * still while a screen is open.
 */
object Menu {
    private lateinit var key: KeyBinding

    /** The selected turtle's id. It stays selected until Deselect or leaving the world. */
    var selectedId: Int? = null

    /** Where Pathfind would go, while the menu is open; drawn as the ghost. */
    var target: BlockPos? = null
        private set

    class Context(val looked: Turtle?, val target: BlockPos?, val here: BlockPos)

    fun register() {
        // the name and category are translation keys, see assets/ccmanager/lang/en_us.json
        key = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.ccmanager.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.ccmanager"))
    }

    fun tick(client: MinecraftClient) {
        val player = client.player ?: return
        if (client.currentScreen != null || !key.isPressed) return

        val hit = client.cameraEntity?.raycast(64.0, 1f, false)
        target = (hit as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }?.let { it.blockPos.offset(it.side) }
        // +0.2: path blocks and farmland are shorter than a block, and you'd otherwise round down into them
        val here = BlockPos.ofFloored(player.x, player.y + 0.2, player.z)
        client.setScreen(RadialScreen(Context(WorldOverlay.lookedAt(client.gameRenderer.camera), target, here)))
    }

    /**
     * Is the menu key still physically held? A screen gets the key events
     * instead of the key binding, so the binding can't tell us.
     */
    fun held(): Boolean {
        val bound = KeyBindingHelper.getBoundKeyOf(key)
        val window = MinecraftClient.getInstance().window.handle
        return if (bound.category == InputUtil.Type.MOUSE) GLFW.glfwGetMouseButton(window, bound.code) == GLFW.GLFW_PRESS
        else InputUtil.isKeyPressed(window, bound.code)
    }

    fun closed() {
        target = null
        key.isPressed = false // the release went to the screen, so the binding still thinks it's down
    }

    fun selected(): Turtle? = selectedId?.let { TurtleStore.turtles[it] }

    // what the menu items do: all through the backend's command parser

    fun pathfind(t: Turtle, pos: BlockPos) =
        command("pathfind ${t.id} ${pos.x} ${pos.y} ${pos.z}", "${t.label} → ${pos.x} ${pos.y} ${pos.z}")

    fun scan(t: Turtle) = command("${t.id} scan ${CcmConfig.get().scanRadius}", "${t.label} scanning")

    /** A message above the hotbar. */
    fun toast(text: String) {
        MinecraftClient.getInstance().player?.sendMessage(Text.literal("[ccm] $text"), true)
    }

    private fun command(line: String, feedback: String) {
        if (Link.state != Link.State.CONNECTED) {
            toast("not connected")
            return
        }
        Link.send("command $line")
        toast(feedback)
    }
}

/** The menu on screen. The world keeps rendering behind it, ghost included. */
class RadialScreen(private val ctx: Menu.Context) : Screen(Text.literal("ccm")) {
    private class Item(val label: String, val enabled: Boolean, val run: () -> Unit, val sub: (() -> List<Item>)? = null)

    private var items = main()
    private var heading = Menu.selected()?.let { "selected: ${it.label}" } ?: "no turtle selected"

    private fun main(): List<Item> {
        val sel = Menu.selected()
        val looked = ctx.looked
        return listOf(
            Item("Select", looked != null, {
                Menu.selectedId = looked!!.id
                Menu.toast("selected ${looked.label}")
            }, sub = looked?.let { t -> { actions(t) } }),
            Item("Pathfind", sel != null && ctx.target != null, { Menu.pathfind(sel!!, ctx.target!!) }),
            Item("Pathfind to here", sel != null, { Menu.pathfind(sel!!, ctx.here) }),
            Item("Scan", sel != null, { Menu.scan(sel!!) }),
            Item("Deselect", sel != null, { Menu.selectedId = null }),
        )
    }

    // the same actions, for a turtle that isn't (necessarily) the selected one
    private fun actions(t: Turtle) = listOf(
        Item("Pathfind", ctx.target != null, { Menu.pathfind(t, ctx.target!!) }),
        Item("Pathfind to here", true, { Menu.pathfind(t, ctx.here) }),
        Item("Scan", true, { Menu.scan(t) }),
    )

    override fun shouldPause() = false

    override fun tick() {
        if (!Menu.held()) pick(hovered) // released: whatever is under the mouse
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val i = indexAt(mouseX, mouseY)
        val sub = items.getOrNull(i)?.takeIf { it.enabled }?.sub
        when {
            button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && sub != null -> {
                items = sub()
                heading = "→ ${ctx.looked?.label}"
            }
            button == GLFW.GLFW_MOUSE_BUTTON_LEFT -> pick(i)
        }
        return true
    }

    override fun removed() = Menu.closed()

    private var hovered = -1

    override fun render(draw: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        hovered = indexAt(mouseX.toDouble(), mouseY.toDouble())
        val cx = width / 2
        val cy = height / 2
        draw.drawCenteredTextWithShadow(textRenderer, heading, cx, cy - 4, 0xAAAAAA)

        items.forEachIndexed { i, item ->
            val angle = angleOf(i)
            val x = cx + (cos(angle) * RADIUS).toInt()
            val y = cy + (sin(angle) * RADIUS).toInt()
            val w = textRenderer.getWidth(item.label) / 2 + 4
            val bg = if (i == hovered && item.enabled) 0xC0505050.toInt() else 0x90000000.toInt()
            draw.fill(x - w, y - 8, x + w, y + 8, bg)
            draw.drawCenteredTextWithShadow(textRenderer, item.label, x, y - 4, if (item.enabled) 0xFFFFFF else 0x666666)
        }
    }

    private fun pick(i: Int) {
        items.getOrNull(i)?.takeIf { it.enabled }?.run?.invoke()
        close()
    }

    // first item straight up, then clockwise
    private fun angleOf(i: Int) = -PI / 2 + i * 2 * PI / items.size

    /** The item whose direction from the centre is closest to the mouse's, or -1 inside the dead zone. */
    private fun indexAt(mouseX: Double, mouseY: Double): Int {
        val dx = mouseX - width / 2
        val dy = mouseY - height / 2
        if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) return -1
        val fromTop = (atan2(dy, dx) + PI / 2 + 2 * PI) % (2 * PI)
        return (fromTop / (2 * PI / items.size) + 0.5).toInt() % items.size
    }

    private companion object {
        const val RADIUS = 70
        const val DEAD_ZONE = 15
    }
}
