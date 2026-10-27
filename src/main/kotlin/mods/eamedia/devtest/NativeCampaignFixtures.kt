package mods.eamedia.devtest

import mods.eln.Eln
import mods.eln.misc.Coordinate
import mods.eln.node.NodeManager
import mods.eln.node.six.SixNode
import mods.eln.node.transparent.TransparentNode
import mods.eln.node.transparent.TransparentNodeElement
import mods.eln.sixnode.electricalcable.UtilityCableDescriptor
import mods.eln.sixnode.electricalcable.UtilityCableMaterial
import mods.eln.sixnode.electricalsource.ElectricalSourceElement
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack

/** Small addon-owned fixtures; no dependency on ELN's internal development campaigns. */
internal class NativeCampaignFixtures(val world: ServerLevel,val player: ServerPlayer) {
    private fun node(at: BlockPos)=NodeManager.instance!!.getNodeFromCoordonate(Coordinate(at.x,at.y,at.z,world))
    private fun six(at: BlockPos)=(node(at) as SixNode).getElement(mods.eln.misc.Direction.YN)!!
    fun placeMachine(stack: ItemStack,at: BlockPos): TransparentNodeElement {
        player.yRot=0f;player.xRot=0f
        check(Eln.transparentNodeItem.placeBlockAt(stack,player,world,at,Direction.UP))
        return (node(at) as TransparentNode).element!!
    }
    private fun placeSix(stack: ItemStack,at: BlockPos) {
        player.setItemInHand(InteractionHand.MAIN_HAND,stack)
        Eln.sixNodeItem.onItemUse(stack,player,world,at,InteractionHand.MAIN_HAND,Direction.UP,.5f,1f,.5f)
    }
    fun wire(at: BlockPos) {
        val d=UtilityCableDescriptor.allDescriptors().single { it.material==UtilityCableMaterial.COPPER && it.sizeLabel=="2 AWG" && it.insulated && !it.melted && it.insulationVoltageRating==40000.0 }
        val stack=d.newItemStack();d.setRemainingLengthMeters(stack,1.0);placeSix(stack,at)
    }
    fun source(at: BlockPos,volts: Double) { placeSix(Eln.findItemStack("Electrical Source",1),at);voltage(at,volts) }
    fun voltage(at: BlockPos,volts: Double)=(six(at) as ElectricalSourceElement).readConfigTool(CompoundTag().apply { putDouble("voltage",volts) },player)
}
