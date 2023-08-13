package net.horizonsend.ion.server.features.multiblock.misc

import net.horizonsend.ion.common.extensions.information
import net.horizonsend.ion.server.features.gas.Gasses
import net.horizonsend.ion.server.features.multiblock.FurnaceMultiblock
import net.horizonsend.ion.server.features.multiblock.InteractableMultiblock
import net.horizonsend.ion.server.features.multiblock.Multiblock
import net.horizonsend.ion.server.features.multiblock.MultiblockShape
import org.bukkit.Material
import org.bukkit.block.Furnace
import org.bukkit.block.Sign
import org.bukkit.entity.Player
import org.bukkit.event.inventory.FurnaceBurnEvent
import org.bukkit.event.player.PlayerInteractEvent

object GasCollectorMultiblock : Multiblock(), FurnaceMultiblock, InteractableMultiblock {
	override val name = "gascollector"

	override val signText = createSignText(
		line1 = "&3Gas &7Collector",
		line2 = null,
		line3 = null,
		line4 = null
	)

	override fun MultiblockShape.buildStructure() {
		at(0, 0, 0).machineFurnace()
		at(0, 0, 1).hopper()
	}

	override fun onFurnaceTick(
		event: FurnaceBurnEvent,
		furnace: Furnace,
		sign: Sign
	) {
		event.isBurning = false
		event.burnTime = 0
		event.isCancelled = true
		val fuel = furnace.inventory.fuel

		if (fuel == null || fuel.type != Material.PRISMARINE_CRYSTALS) {
			return
		}

		if (!Gasses.isEmptyCanister(furnace.inventory.smelting)) {
			return
		}

		event.isBurning = false
		event.burnTime = (750 + Math.random() * 500).toInt()
		furnace.cookTime = (-1000).toShort()
		event.isCancelled = false

		Gasses.tickCollectorAsync(sign)
	}

	override fun onSignInteract(sign: Sign, player: Player, event: PlayerInteractEvent) {
		val available = Gasses.findGas(sign.location).joinToString { it.name }

		player.information("Available gasses: $available")
	}
}
