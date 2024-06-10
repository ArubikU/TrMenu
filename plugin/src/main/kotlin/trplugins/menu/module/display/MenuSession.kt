package trplugins.menu.module.display

import org.bukkit.entity.Player
import taboolib.common.platform.ProxyPlayer
import taboolib.common.platform.service.PlatformExecutor
import taboolib.common.util.replaceWithOrder
import taboolib.library.configuration.ConfigurationSection
import taboolib.library.reflex.Reflex.Companion.getProperty
import taboolib.module.chat.colored
import taboolib.module.lang.Language
import taboolib.platform.compat.replacePlaceholder
import trplugins.menu.api.event.MenuCloseEvent
import trplugins.menu.api.receptacle.vanilla.window.WindowReceptacle
import trplugins.menu.module.display.icon.Icon
import trplugins.menu.module.display.icon.IconProperty
import trplugins.menu.module.display.layout.Layout
import trplugins.menu.module.internal.script.FunctionParser
import trplugins.menu.util.parseGradients
import trplugins.menu.util.parseRainbow
import java.util.UUID

/**
 * @author Arasple
 * @date 2021/1/24 10:01
 */
class MenuSession(
    val viewer: Player,
    var menu: Menu?,
    var page: Int,
    arguments: Array<String>,
    var agent: Player = viewer,
    var receptacle: WindowReceptacle? = null
) {

    /**
     * 会话的唯一标识 ID
     */
    val id = UID++

    /**
     * 处理变量的玩家对象
     * 不一定是菜单查看者
     */
    val placeholderPlayer: Player
        get() {
            return if (agent.isOnline) agent else viewer
        }

    private var mark = System.currentTimeMillis()

    internal var implicitArguments: Array<String> = arrayOf()

    var arguments: Array<String> = arguments
        set(value) {
            val def = menu?.settings?.defaultArguments ?: kotlin.run {
                field = value
                return
            }

            if (def.isNotEmpty()) {
                field = when {
                    value.isEmpty() -> {
                        def
                    }
                    value.size < def.size -> {
                        val args = value.toMutableList()
                        for (i in args.size until def.size) args.add(def[i])
                        args.toTypedArray()
                    }
                    else -> value
                }
                return
            }
            field = value
        }

    // 当前活动的图标记录
    val activeIcons = mutableSetOf<Icon>()

    // 玩家物品槽位记录
    private val playerItemSlots = mutableSetOf<Int>()

    // 该会话正在运行的所有任务
    private val tasking = mutableSetOf<PlatformExecutor.PlatformTask>()

    // 临时任务（切换页码时允许删除）
    private val temporaries = mutableSetOf<PlatformExecutor.PlatformTask>()

    var locale: String = kotlin.run {
        val code = try {
            viewer.locale
        } catch (ignored: NoSuchMethodError) {
            viewer.getProperty<String>("entity/locale")!!
        }.lowercase()

        Language.languageCodeTransfer[code]?.lowercase() ?: code
    }
        set(value) {
            if (field == value.lowercase()) return
            // Clear display cache
            Menu.menus.forEach { menu -> menu.icons.forEach { icon ->
                icon.defIcon.display.cache.remove(id)
                icon.subs.elements.forEach { sub -> sub.display.cache.remove(id) }
            } }
            field = value.lowercase()
        }

    /**
     * 取得当前会话的布局
     */
    fun layout(page: Int? = null): Layout? {
        return menu?.layout?.get(page ?: this.page)
    }

    /**
     * 取得主要对象
     */
    fun objects(): Triple<Player, Menu?, WindowReceptacle?> {
        return Triple(viewer, menu, receptacle)
    }

    /**
     * 处理一个字符串，替换函数变量
     */
    fun parse(string: String): String {
        val preColor = MenuSettings.PRE_COLOR
        val funced = FunctionParser.parse(placeholderPlayer, string) { type, value ->
            when (type) {
                "node", "nodes", "n" -> parseNode(menu?.conf, value)
                "lang" -> parseNode(menu?.getLocaleSection(locale), value)
                else -> null
            }
        }
        val content =
            (if (preColor) funced else funced.colored().parseRainbow().parseGradients()).replaceWithOrder(*arguments)
        val papi = content.replacePlaceholder(placeholderPlayer)
        return if (preColor) papi else papi.colored().parseRainbow().parseGradients()
    }

    fun parse(string: List<String>): List<String> {
        return string.map { parse(it) }
    }

    private fun parseNode(conf: ConfigurationSection?, node: String): String? {
        if (conf == null) {
            return null
        }
        var key = node
        val arguments: Array<String>? = if (key.contains('_')) {
            val index = key.indexOf('_')
            key = node.substring(0, index)
            if (index + 1 < node.length) {
                node.substring(index + 1).split('_').toTypedArray()
            } else {
                null
            }
        } else {
            null
        }
        return conf[parse(conf.getKeys(true).find { it.equals(key, ignoreCase = true) } ?: key)].let {
            val value = if (it is List<*>) {
                it.joinToString("\n")
            } else {
                it.toString()
            }
            if (arguments != null) {
                value.replaceWithOrder(*arguments)
            } else {
                value
            }
        }
    }


    /**
     * 为该会话新建一个任务
     */
    fun arrange(task: PlatformExecutor.PlatformTask, temporary: Boolean = false) {
        tasking.add(task)
        if (temporary) temporaries.add(task)
    }

    /**
     * 关闭会话
     */
    fun shut() {
        tasking.removeIf {
            it.cancel()
            true
        }

        menu?.removeViewer(viewer)
        menu = null
        page = -1
        agent = viewer
        receptacle = null
    }

    fun shutTemps() {
        activeIcons.clear()
        tasking.removeIf {
            if (temporaries.remove(it)) {
                it.cancel()
                true
            } else false
        }
    }

    /**
     * 关闭会话和容器
     */
    fun close(closePacket: Boolean, updateInventory: Boolean) {
        MenuCloseEvent(this).call()
        receptacle?.close(closePacket)
        if (updateInventory) viewer.updateInventory()
    }

    /**
     * 取得槽位的图标
     */
    private fun getIcon(slot: Int): Icon? {
        return activeIcons.find { it.position.currentPosition(this).contains(slot) }
    }

    fun getIcon(key: String): Icon? {
        return activeIcons.find { it.id == key }
    }

    /**
     * 取得槽位的图标属性
     */
    fun getIconProperty(slot: Int): IconProperty? {
        return getIcon(slot)?.getProperty(this)
    }

    /**
     * 缓存记录玩家物品槽位
     */
    fun playerItemSlots() {
        if (menu == null || receptacle == null) return
        val type = receptacle!!.type
        if (!menu!!.settings.hidePlayerInventory) {
            // 0-8 Hotbar
            // 9-35 Inventory
            playerItemSlots.clear()
            viewer.inventory.contents.forEachIndexed { index, itemStack ->
                if (itemStack != null) {
                    val slot = when (index) {
                        in 0..8 -> type.hotBarSlots[index]
                        in 9..35 -> type.mainInvSlots[index - 9]
                        else -> -1
                    }
                    if (slot > 0) {
                        receptacle!!.setElement(itemStack, slot)
                        playerItemSlots.add(slot)
                    }
                }
            }
        }
    }

    /**
     * 更新活动槽位
     */
    fun updateActiveSlots() {
        if (receptacle != null) {
            val rece = receptacle!!
            val type = rece.type
            val active = activeIcons.flatMap { it.position.currentPosition(this) }

            type.totalSlots.forEach {
                if (!active.contains(it) && !playerItemSlots.contains(it) && !menu!!.settings.freeSlots.contains(it)) {
                    rece.setElement(null, it)
                }
            }
        }
    }

    fun isViewing(): Boolean {
        return menu != null
    }

    companion object {

        private var UID = 0

        @JvmField
        val SESSIONS = mutableMapOf<UUID, MenuSession>()

        fun getSession(player: Player): MenuSession {
            return SESSIONS.computeIfAbsent(player.uniqueId) { MenuSession(player, null, 0, arrayOf()) }
        }

        fun removeSession(player: Player) {
            SESSIONS.remove(player.uniqueId)
        }

    }

}
fun Player.session(): MenuSession {
    return MenuSession.getSession(this)
}
fun ProxyPlayer.session(): MenuSession {
    return cast<Player>().session()
}