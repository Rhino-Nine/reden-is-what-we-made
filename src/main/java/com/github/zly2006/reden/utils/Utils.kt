package com.github.zly2006.reden.utils

import com.github.zly2006.reden.ModNames
import com.github.zly2006.reden.Reden
import com.github.zly2006.reden.exceptions.RedenException
import com.github.zly2006.reden.malilib.DEVELOPER_MODE
import com.github.zly2006.reden.malilib.LOCAL_API_BASEURL
import com.github.zly2006.reden.malilib.SELECTION_TOOL
import com.google.gson.Gson
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import io.wispforest.owo.ui.core.Surface
import net.fabricmc.api.EnvType
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.impl.discovery.ModResolutionException
import net.minecraft.block.Block
import net.minecraft.block.BlockEntityProvider
import net.minecraft.block.BlockState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.nbt.NbtHelper
import net.minecraft.network.PacketByteBuf
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.ServerTask
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Heightmap
import net.minecraft.world.World
import net.minecraft.world.chunk.WorldChunk
import net.minecraft.world.chunk.light.ChunkLightProvider
import org.eclipse.jgit.api.AddCommand
import org.eclipse.jgit.api.CommitCommand
import org.eclipse.jgit.api.GitCommand
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.*
import kotlin.math.max
import kotlin.math.min

lateinit var server: MinecraftServer

fun Vec3d.toBlockPos(): BlockPos {
    return BlockPos.ofFloored(this)
}

fun PlayerEntity.sendMessage(s: String) {
    sendMessage(Text.literal(s))
}

val ClientPlayerEntity?.holdingToolItem: Boolean
    get() {
        val stack = this?.getStackInHand(Hand.MAIN_HAND) ?: return false
        return Registries.ITEM.getId(stack.item) == Identifier.tryParse(SELECTION_TOOL.stringValue)
    }

fun World.setBlockNoPP(pos: BlockPos, state: BlockState, flags: Int = Block.NOTIFY_LISTENERS) {
//    setBlockState(pos, state, flags and Block.NOTIFY_NEIGHBORS.inv() or Block.FORCE_STATE or Block.SKIP_DROPS)
    profiler.push("reden_setBlockState_noPP")
    val stateBefore = getBlockState(pos)
    if (stateBefore.hasBlockEntity()) {
        removeBlockEntity(pos)
    }
    getChunk(pos).run { getSection(getSectionIndex(pos.y)) }
        .setBlockState(pos.x and 15, pos.y and 15, pos.z and 15, state, false)
    getWorldChunk(pos).run {
        this.heightmaps[Heightmap.Type.MOTION_BLOCKING]!!.trackUpdate(pos.x and 15, pos.y, pos.z and 15, state)
        this.heightmaps[Heightmap.Type.MOTION_BLOCKING_NO_LEAVES]!!.trackUpdate(
            pos.x and 15,
            pos.y,
            pos.z and 15,
            state
        )
        this.heightmaps[Heightmap.Type.OCEAN_FLOOR]!!.trackUpdate(pos.x and 15, pos.y, pos.z and 15, state)
        this.heightmaps[Heightmap.Type.WORLD_SURFACE]!!.trackUpdate(pos.x and 15, pos.y, pos.z and 15, state)
        setNeedsSaving(true)

        if (ChunkLightProvider.needsLightUpdate(this, pos, stateBefore, state)) {
            profiler.push("updateSkyLightSources")
            chunkSkyLight.isSkyLightAccessible(this, pos.x and 15, pos.y and 15, pos.z and 15)
            profiler.swap("queueCheckLight")
            chunkManager.lightingProvider.checkBlock(pos)
            profiler.pop()
        }

        if (!state.isOf(stateBefore.block) && stateBefore.hasBlockEntity()) {
            this.removeBlockEntity(pos)
        }

        if (state.hasBlockEntity()) {
            var blockEntity = this.getBlockEntity(pos, WorldChunk.CreationType.CHECK)
            if (blockEntity == null) {
                blockEntity = (state.block as BlockEntityProvider).createBlockEntity(pos, state)
                if (blockEntity != null) {
                    this.addBlockEntity(blockEntity)
                }
            } else {
                @Suppress("DEPRECATION")
                blockEntity.cachedState = state
                this.updateTicker(blockEntity)
            }
        }
    }
    if (this is ServerWorld) {
        chunkManager.markForUpdate(pos)
    }
    if (flags and Block.NOTIFY_LISTENERS != 0) {
        updateListeners(pos, stateBefore, state, flags)
    }
    // poi
    this.onBlockChanged(pos, stateBefore, state)
    profiler.pop()
}

val isClient: Boolean get() = FabricLoader.getInstance().environmentType == EnvType.CLIENT

object ResourceLoader {
    fun loadBytes(path: String): ByteArray? {
        return Reden::class.java.classLoader.getResourceAsStream(path)?.readAllBytes()
    }

    fun loadString(path: String): String {
        return loadBytes(path)!!.decodeToString()
    }

    fun loadStringOrNull(path: String): String? {
        return loadBytes(path)?.decodeToString()
    }

    fun loadTexture(path: String): Identifier {
        return Reden.identifier(path)
    }

    @JvmStatic
    fun loadLang(lang: String) =
        loadStringOrNull("assets/reden/lang/$lang.json")?.let {
            // work around for owo rich translate
            @Suppress("UNCHECKED_CAST")
            Gson().fromJson(it, Map::class.java).filterValues { value -> value is String } as Map<String, String>
        }
}

fun buttonWidget(x: Int, y: Int, width: Int, height: Int, message: Text, onPress: ButtonWidget.PressAction) =
    ButtonWidget(x, y, width, height, message, onPress) { it.get() }

val isSinglePlayerAndCheating: Boolean
    get() {
        infix fun Boolean?.and(other: Boolean?) = this ?: false && other ?: false
        return MinecraftClient.getInstance()?.let {
            (it.server?.isSingleplayer and it.player?.hasPermissionLevel(2))
        } == true
    }

fun memorySizeToString(size: Int) {
    val unit = arrayOf("B", "KB", "MB", "GB", "TB")
    var i = 0
    var s = size.toDouble()
    while (s > 1024) {
        s /= 1024
        i++
    }
    println("%.2f".format(s) + unit[i])
}

fun MutableText.red() = formatted(Formatting.RED)!!

val litematicaInstalled get() = FabricLoader.getInstance().isModLoaded(ModNames.litematica)

fun PacketByteBuf.writeBlockState(state: BlockState) {
    writeNbt(NbtHelper.fromBlockState(state))
}

fun PacketByteBuf.readBlockState(): BlockState {
    return NbtHelper.toBlockState(Registries.BLOCK.readOnlyWrapper, readNbt())
}

fun PacketByteBuf.writeBlock(block: Block) {
    writeIdentifier(Registries.BLOCK.getId(block))
}

fun PacketByteBuf.readBlock(): Block {
    return Registries.BLOCK.get(readIdentifier())
}

fun PacketByteBuf.readDirection(): Direction {
    return Direction.byId(readVarInt())
}

fun PacketByteBuf.writeDirection(direction: Direction) {
    writeVarInt(direction.id)
}

fun URL.openStreamRetrying(retries: Int = 3): InputStream {
    var retry = retries
    while (retry > 0) {
        try {
            return this.openStream()
        } catch (e: IOException) {
            Reden.LOGGER.warn("Opening $this", e)
        }
        retry--
    }
    Reden.LOGGER.error("Opening $this: max retries exceeded.")
    throw IOException("Opening $this: max retries exceeded.")
}

fun checkMalilib() {
    try {
        if (isClient)
            Class.forName("fi.dy.masa.malilib.util.FileUtils")
    } catch (_: ClassNotFoundException) {
        throw ModResolutionException(
            """
            Dependency not found!
            Reden requires Malilib to run on the clients.
            Please install Malilib from https://www.curseforge.com/minecraft/mc-mods/malilib
        """.trimIndent()
        )
    }
}

/**
 * @author Zai_yu_you
 */
@Deprecated("", level = DeprecationLevel.HIDDEN)
fun generateRandomColor(alpha: Int, baseGray: Int, offsetWeight: Float): Int {
    require(offsetWeight > 0 && offsetWeight <= 1) { "The input offsetWeight must be between 0(inclusive) and 1 " }
    require(baseGray in 1..256) { "The input baseGray must be between 0(inclusive) and 256 " }
    val random = Random()

    var r = (baseGray * (1 - offsetWeight) + random.nextInt((baseGray * offsetWeight).toInt())).toInt()
    var g = (baseGray * (1 - offsetWeight) + random.nextInt((baseGray * offsetWeight).toInt())).toInt()
    var b = (baseGray * (1 - offsetWeight) + random.nextInt((baseGray * offsetWeight).toInt())).toInt()

    //归一化
    var scaleFactor = 256f / (r + g + b)
    r = (r * scaleFactor).toInt()
    g = (g * scaleFactor).toInt()
    b = (b * scaleFactor).toInt()

    // 调整RGB值，使其灰度接近于目标灰度
    val currentGray = (0.2126f * r + 0.587f * g + 0.114f * b).toInt()
    scaleFactor = baseGray.toFloat() / currentGray
    r = (r * scaleFactor).toInt()
    g = (g * scaleFactor).toInt()
    b = (b * scaleFactor).toInt()

    // 确保RGB值在0-255范围内
    r = max(14, min(r, 207))
    g = max(13, min(g, 210))
    b = max(23, min(b, 234))

    // 保留Alpha通道的值
    val a = alpha and 0xFF

    // 确保RGB值在0-255范围内
    r = r and 0xFF
    g = g and 0xFF
    b = b and 0xFF

    // 合并RGB值到ARGB值中
    val rgb = (r shl 16) or (g shl 8) or b

    // 返回合并后的ARGB值
    return (a shl 24) or rgb
}

operator fun Surface.plus(other: Surface): Surface = and(other)

val redenApiBaseUrl: String
    get() = if (isClient && DEVELOPER_MODE.booleanValue) LOCAL_API_BASEURL.stringValue else "https://redenmc.com/api"

infix fun Int.has(flag: Int) = (this and flag) == flag

@Suppress("NOTHING_TO_INLINE")
inline fun redenError(message: String, throwable: Throwable? = null, log: Boolean = false): Nothing {
    if (log) {
        Reden.LOGGER.error(message, throwable)
    }
    throw if (throwable != null) RedenException(message, throwable) else RedenException(message)
}

@Suppress("NOTHING_TO_INLINE")
inline fun redenError(message: Text, throwable: Throwable? = null, log: Boolean = false): Nothing {
    if (log) {
        Reden.LOGGER.error(message.string, throwable)
    }
    throw if (throwable != null) RedenException(message, throwable) else RedenException(message)
}

fun Class<*>.shortenName(): String {
    val simple = this.name.substringAfterLast('.')
    return this.name.split('.').dropLast(1).joinToString(".") { it[0].toString() } + "." + simple
}

fun MinecraftServer.send(task: () -> Unit) = send(ServerTask(ticks, task))

@Suppress("NOTHING_TO_INLINE")
inline fun error(reason: String): Nothing =
    throw SimpleCommandExceptionType(Text.literal(reason)).create()

fun GitCommand<*>.gitCommandLine(): ProcessBuilder {
    val builder = ProcessBuilder("git")
    return when (this) {
        is AddCommand -> {
            builder.apply {
                command().add("add")
                command().addAll(filepatterns)
                if (update) {
                    command().add("-u")
                }
                if (renormalize) {
                    command().add("--renormalize")
                }
            }
        }

        is CommitCommand -> {
            builder.apply {
                command().add("commit")
                if (all) {
                    command().add("-a")
                }
                if (amend) {
                    command().add("--amend")
                }
                if (allowEmpty) {
                    command().add("--allow-empty")
                }
                if (signCommit) {
                    command().add("-S")
                }
                if (noVerify) {
                    command().add("--no-verify")
                }
//                if (only) {
//                    command().add("--only")
//                }
                if (message != null) {
                    command().add("-m")
                    command().add(message!!)
                }
                if (committer != null) {
                    command().add("--committer")
                    command().add(committer!!.toExternalString())
                }
                if (author != null) {
                    command().add("--author")
                    command().add(author!!.toExternalString())
                }
                if (gpgSigner != null) {
                    command().add("--signoff")
                    command().add("--gpg-sign")
                    TODO()
//                    command().add(gpgSigner!!.toExternalString())
                }
                if (gpgSigner == null && signCommit) {
                    command().add("--signoff")
                }
                if (cleanupMode != null) {
                    command().add("--cleanup")
                    command().add(cleanupMode!!.name)
                }
                if (cleanDefaultIsStrip) {
                    command().add("--cleanup-default")
                }
                if (commentChar != null) {
                    command().add("--comment-char")
                    command().add(commentChar!!.toString())
                }
            }
        }

        else          -> error("Unsupported GitCommand")
    }
}

fun MinecraftClient.setScreenLater(screen: Screen) =
    send { setScreen(screen) }
