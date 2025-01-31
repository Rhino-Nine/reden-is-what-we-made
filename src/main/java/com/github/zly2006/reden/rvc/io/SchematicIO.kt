package com.github.zly2006.reden.rvc.io

import com.github.zly2006.reden.exceptions.RedenException
import com.github.zly2006.reden.rvc.*
import com.github.zly2006.reden.rvc.tracking.PlacementInfo
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.registry.Registries
import net.minecraft.structure.StructureTemplate
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.Vec3i
import java.nio.file.Path

private object Names {
    const val MATERIALS = "Materials"
    const val FORMAT_STRUCTURE = "Structure"
}

val FORMATS = mutableMapOf<String, SchematicFormat>(Names.FORMAT_STRUCTURE to SchematicStructure())

abstract class SchematicFormat {
    abstract fun readFromNBT(tagCompound: NbtCompound): IStructure
    abstract fun writeToNBT(tagCompound: NbtCompound, schematic: IStructure): Boolean
}

class SchematicImpl(
    name: String,
    override var xSize: Int,
    override var ySize: Int,
    override var zSize: Int
) : ReadWriteStructure(name), SizeMutableStructure {
    init {
        io = SchematicIO
    }
    override fun isInArea(pos: RelativeCoordinate): Boolean {
        return pos.x in 0 until xSize
                && pos.y in 0 until ySize
                && pos.z in 0 until zSize
    }

    override fun createPlacement(placementInfo: PlacementInfo): IPlacement {
        return DefaultPlacement(this, placementInfo.worldInfo.getWorld()!!, placementInfo.origin)
    }
}

object SchematicIO: StructureIO {
    override fun save(path: Path, structure: IStructure) {
        val format = FORMATS[Names.FORMAT_STRUCTURE]!!
        val nbt = NbtCompound()
        if (!format.writeToNBT(nbt, structure)) {
            throw RedenException(Text.translatable("rvc.error.schematic.write_failed"))
        }
        NbtIo.writeCompressed(nbt, path)
    }

    override fun load(path: Path, structure: IWritableStructure) {
        val nbt = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes())
        val formatName = if (nbt.contains(Names.MATERIALS)) nbt.getString(Names.MATERIALS)
        else Names.FORMAT_STRUCTURE
        val format = FORMATS[formatName]
            ?: throw UnsupportedOperationException("Schematic format $formatName is not supported!")
        structure %= format.readFromNBT(nbt)
    }
}

class SchematicStructure: SchematicFormat() {
    override fun readFromNBT(tagCompound: NbtCompound): IStructure {
        val template = StructureTemplate()
        template.readNbt(Registries.BLOCK.readOnlyWrapper, tagCompound)
        val ret = SchematicImpl("", template.size.x, template.size.y, template.size.z)
        template.blockInfoLists.flatMap { it.all }.forEach {
            ret.setBlockState(it.pos.relative(), it.state)
            if (it.nbt != null) {
                ret.getOrCreateBlockEntityData(it.pos.relative()).copyFrom(it.nbt)
            }
        }
        return ret
    }

    override fun writeToNBT(tagCompound: NbtCompound, schematic: IStructure): Boolean {
        val template = StructureTemplate()
        template.author = "TEST ~ Reden ~"
        template.size = Vec3i(schematic.xSize, schematic.ySize, schematic.zSize)
        val list = mutableListOf<StructureTemplate.StructureBlockInfo>()
        for (x in 0 until schematic.xSize)
            for (y in 0 until schematic.ySize)
                for (z in 0 until schematic.zSize) {
                    val pos = BlockPos(x, y, z)
                    list.add(
                        StructureTemplate.StructureBlockInfo(
                            pos,
                            schematic.getBlockState(pos.relative()),
                            schematic.getBlockEntityData(pos.relative())
                        )
                    )
                }
        template.blockInfoLists.clear()
        template.blockInfoLists.add(StructureTemplate.PalettedBlockInfoList(list))
        template.entities.clear()
        template.entities.addAll(schematic.entities.map {
            val posNbt = it.value.getList("Pos", NbtElement.DOUBLE_TYPE.toInt())
            val pos = Vec3d(posNbt.getDouble(0), posNbt.getDouble(1), posNbt.getDouble(2))
            StructureTemplate.StructureEntityInfo(
                pos,
                BlockPos.ofFloored(pos),
                it.value
            )
        })
        template.writeNbt(tagCompound)
        return true
    }
}

/**
 * We only use this function in this file because only for structure blocks the [BlockPos] is relative.
 */
private fun BlockPos.relative(): RelativeCoordinate {
    return RelativeCoordinate(x, y, z)
}
