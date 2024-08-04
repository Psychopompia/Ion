package net.horizonsend.ion.server.command.nations.admin

import co.aikar.commands.annotation.CommandAlias
import co.aikar.commands.annotation.CommandCompletion
import co.aikar.commands.annotation.CommandPermission
import co.aikar.commands.annotation.Optional
import co.aikar.commands.annotation.Subcommand
import net.horizonsend.ion.common.database.Oid
import net.horizonsend.ion.common.database.schema.misc.SLPlayer
import net.horizonsend.ion.common.database.schema.misc.SLPlayerId
import net.horizonsend.ion.common.database.schema.nations.CapturableStation
import net.horizonsend.ion.common.database.schema.nations.CapturableStationSiege
import net.horizonsend.ion.common.database.schema.nations.Nation
import net.horizonsend.ion.common.database.schema.nations.Settlement
import net.horizonsend.ion.common.database.schema.nations.Territory
import net.horizonsend.ion.common.database.schema.nations.spacestation.NationSpaceStation
import net.horizonsend.ion.common.database.schema.nations.spacestation.PlayerSpaceStation
import net.horizonsend.ion.common.database.schema.nations.spacestation.SettlementSpaceStation
import net.horizonsend.ion.common.database.schema.nations.spacestation.SpaceStationCompanion
import net.horizonsend.ion.common.database.slPlayerId
import net.horizonsend.ion.common.extensions.success
import net.horizonsend.ion.common.extensions.userError
import net.horizonsend.ion.common.utils.miscellaneous.toCreditsString
import net.horizonsend.ion.server.features.nations.NATIONS_BALANCE
import net.horizonsend.ion.server.features.nations.NationsBalancing
import net.horizonsend.ion.server.features.nations.NationsMap
import net.horizonsend.ion.server.features.nations.NationsMasterTasks
import net.horizonsend.ion.server.features.nations.TerritoryImporter
import net.horizonsend.ion.server.features.nations.region.Regions
import net.horizonsend.ion.server.features.nations.region.types.RegionSpaceStation
import net.horizonsend.ion.server.features.nations.utils.isActive
import net.horizonsend.ion.server.features.nations.utils.isInactive
import net.horizonsend.ion.server.features.space.spacestations.CachedSpaceStation
import net.horizonsend.ion.server.miscellaneous.utils.msg
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.litote.kmongo.and
import org.litote.kmongo.deleteOneById
import org.litote.kmongo.eq
import org.litote.kmongo.gt
import org.litote.kmongo.ne
import org.litote.kmongo.setValue
import org.litote.kmongo.updateOne
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@CommandAlias("nadmin|nationsadmin")
@CommandPermission("nations.admin")
internal object NationAdminCommand : net.horizonsend.ion.server.command.SLCommand() {
	@Subcommand("rebalance")
	@Suppress("unused")
	fun onRebalance(sender: CommandSender) {
		NationsBalancing.reload()
		sender msg "&aRebalanced"
	}

	@Subcommand("refresh map")
	@Suppress("unused")
	fun onRefreshMap(sender: CommandSender) {
		NationsMap.reloadDynmap()
		sender msg "Refreshed map"
	}

	@Subcommand("runtask money")
	@Suppress("unused")
	fun onRunTaskIncome(sender: CommandSender) {
		NationsMasterTasks.executeMoneyTasks()
		sender msg "Executed income task"
	}

	@Subcommand("runtask purge")
	@Suppress("unused")
	fun onRunTaskPurge(sender: CommandSender) = asyncCommand(sender) {
		NationsMasterTasks.checkPurges()
		sender msg "Executed purge task"
	}

	@Subcommand("player set settlement")
	@Suppress("unused")
	fun onPlayerSetSettlement(sender: CommandSender, player: String, settlement: String) = asyncCommand(sender) {
		val playerId = resolveOfflinePlayer(player).slPlayerId
		val settlementId = resolveSettlement(settlement)

		failIf(SLPlayer.isSettlementLeader(playerId)) { "$player is the leader of a settlement, leader can't leave" }

		if (SLPlayer.matches(playerId, SLPlayer::settlement ne null)) {
			SLPlayer.leaveSettlement(playerId)
		}

		SLPlayer.joinSettlement(playerId, settlementId)

		sender msg "&aPut $player in $settlement"
	}

	private fun percentAndTotal(dividend: Double, divisor: Double) =
		"${(dividend / divisor * 100).roundToInt()}% ($dividend)"

	@Subcommand("player stats")
	@Suppress("unused")
	fun onPlayerStats(sender: CommandSender) = asyncCommand(sender) {
		sender msg "Pulling from db..."
		val allPlayers = SLPlayer.all()
		val total = allPlayers.size.toDouble()
		sender msg "Analyzing $total players..."
		var playersInSettlements = 0.0
		var playersInNations = 0.0
		var activePlayers = 0.0
		var semiActivePlayers = 0.0
		var inactivePlayers = 0.0

		allPlayers.forEach {
			if (it.settlement != null) playersInSettlements++
			if (it.nation != null) playersInNations++

			when {
				isActive(it.lastSeen) -> activePlayers++
				isInactive(it.lastSeen) -> inactivePlayers++
				else -> semiActivePlayers++
			}
		}

		sender msg "&6Players in settlements: &b" + percentAndTotal(playersInSettlements, total)
		sender msg "&6Players in nations: &5" + percentAndTotal(playersInNations, total)
		sender msg "&6Active Players: &2" + percentAndTotal(activePlayers, total)
		sender msg "&6Semi-Active Players: &7" + percentAndTotal(semiActivePlayers, total)
		sender msg "&6Inactive Players: &c" + percentAndTotal(inactivePlayers, total)
	}

	@Subcommand("settlement set leader")
	@Suppress("unused")
	fun onSettlementSetLeader(sender: CommandSender, settlement: String, player: String) = asyncCommand(sender) {
		val settlementId = resolveSettlement(settlement)
		val playerId = resolveOfflinePlayer(player).slPlayerId
		requireIsMemberOf(playerId, settlementId)
		Settlement.setLeader(settlementId, playerId)
		sender msg "Changed leader of ${getSettlementName(settlementId)} to ${getPlayerName(playerId)}"
	}

	@Subcommand("settlement purge")
	@Suppress("unused")
	fun onSettlementPurge(sender: CommandSender, settlement: String, sendMessage: Boolean) = asyncCommand(sender) {
		val settlementId = resolveSettlement(settlement)
		NationsMasterTasks.purgeSettlement(settlementId, sendMessage)
		sender msg "Purged ${getSettlementName(settlementId)}"
	}

	@Subcommand("settlement set balance")
	@Suppress("unused")
	fun onSettlementSetBalance(sender: CommandSender, settlement: String, balance: Int) = asyncCommand(sender) {
		val settlementId = resolveSettlement(settlement)
		Settlement.updateById(settlementId, setValue(Settlement::balance, balance))
		sender msg "Set balance of $settlement to ${balance.toCreditsString()}"
	}

	@Subcommand("nation set balance")
	@Suppress("unused")
	fun onNationSetBalance(sender: CommandSender, nation: String, balance: Int) = asyncCommand(sender) {
		val nationId = resolveNation(nation)
		Nation.updateById(nationId, setValue(Nation::balance, balance))
		sender msg "Set balance of $nation to ${balance.toCreditsString()}"
	}

	@CommandPermission("nations.admin.movestation")
	@Subcommand("spacestation set location")
	@Suppress("unused")
	fun onStationSetLocaiton(sender: CommandSender, station: CachedSpaceStation<*, *, *>, world: World, x: Int, z: Int) = asyncCommand(sender) {
		station.setLocation(x, z, world.name)

		sender.success("Set position of ${station.name} to $x, $z")
	}

	@CommandPermission("nations.admin.movestation")
	@Subcommand("spacestation set owner")
	@Suppress("unused")
	fun onStationSetOwner(sender: CommandSender, station: CachedSpaceStation<*, *, *>, newOwner: String) = asyncCommand(sender) {
		when (station.companion) {
			is PlayerSpaceStation.Companion -> {
				val player = resolveOfflinePlayer(newOwner).slPlayerId
				transferPersonal(station, player)
			}

			is SettlementSpaceStation.Companion -> {
				val settlement = resolveSettlement(newOwner)
				transferSettlement(station, settlement)
			}

			is NationSpaceStation.Companion -> {
				val nation = resolveNation(newOwner)
				transferNation(station, nation)
			}

			else -> throw NotImplementedError()
		}

		sender.success("Transferred ${station.ownershipType} station ${station.name} to ${station.ownershipType} $newOwner")
	}

	private fun transferPersonal(station: CachedSpaceStation<*, *, *>, newOwner: SLPlayerId) {
		station.companion.col.deleteOneById(station.databaseId)

		val id = PlayerSpaceStation.create(
			newOwner,
			station.name,
			station.world,
			station.x,
			station.z,
			station.radius,
			SpaceStationCompanion.TrustLevel.MANUAL
		)

		PlayerSpaceStation.updateById(id, setValue(PlayerSpaceStation::trustedPlayers, station.trustedPlayers))
		PlayerSpaceStation.updateById(id, setValue(PlayerSpaceStation::trustedSettlements, station.trustedSettlements))
		PlayerSpaceStation.updateById(id, setValue(PlayerSpaceStation::trustedNations, station.trustedNations))
		PlayerSpaceStation.updateById(id, setValue(PlayerSpaceStation::trustLevel, station.trustLevel))
	}

	private fun transferSettlement(station: CachedSpaceStation<*, *, *>, newOwner: Oid<Settlement>) {
		station.companion.col.deleteOneById(station.databaseId)

		val id = SettlementSpaceStation.create(
			newOwner,
			station.name,
			station.world,
			station.x,
			station.z,
			station.radius,
			SpaceStationCompanion.TrustLevel.MANUAL
		)

		SettlementSpaceStation.updateById(id, setValue(SettlementSpaceStation::trustedPlayers, station.trustedPlayers))
		SettlementSpaceStation.updateById(id, setValue(SettlementSpaceStation::trustedSettlements, station.trustedSettlements))
		SettlementSpaceStation.updateById(id, setValue(SettlementSpaceStation::trustedNations, station.trustedNations))
		SettlementSpaceStation.updateById(id, setValue(SettlementSpaceStation::trustLevel, station.trustLevel))
	}

	private fun transferNation(station: CachedSpaceStation<*, *, *>, newOwner: Oid<Nation>) {
		station.companion.col.deleteOneById(station.databaseId)

		val id = NationSpaceStation.create(
			newOwner,
			station.name,
			station.world,
			station.x,
			station.z,
			station.radius,
			SpaceStationCompanion.TrustLevel.MANUAL
		)

		NationSpaceStation.updateById(id, setValue(NationSpaceStation::trustedPlayers, station.trustedPlayers))
		NationSpaceStation.updateById(id, setValue(NationSpaceStation::trustedSettlements, station.trustedSettlements))
		NationSpaceStation.updateById(id, setValue(NationSpaceStation::trustedNations, station.trustedNations))
		NationSpaceStation.updateById(id, setValue(NationSpaceStation::trustLevel, station.trustLevel))
	}

	@CommandPermission("nations.admin.movestation")
	@Subcommand("spacestation set radius")
	@Suppress("unused")
	fun onStationSetRadius(sender: CommandSender, station: CachedSpaceStation<*, *, *>, radius: Int) =
		asyncCommand(sender) {

			station.changeRadius(radius)

			sender.success("Set radius of ${station.name} to $radius")
		}


	@Subcommand("spacestation reload")
	@Suppress("unused")
	fun onStationReload(sender: CommandSender) {
		Regions.getAllOf<RegionSpaceStation<*, *>>().forEach(NationsMap::updateSpaceStation)
		sender.success("Reloaded space stations")
	}

	@Subcommand("station set quarter")
	@Suppress("unused")
	fun onStationSetQuarter(sender: CommandSender, station: String, quarter: Int) = asyncCommand(sender) {
		failIf(quarter !in 1..4) { "Quarter must be within [1, 4]" }
		val station = CapturableStation.findOne(CapturableStation::name eq station)
			?: fail { "Station $station not found" }
		station.siegeTimeFrame = quarter
		CapturableStation.col.updateOne(station)
		sender msg "Set quarter of $station to $quarter"
	}

	@Subcommand("station clearsieges")
	@Suppress("unused")
	fun onStationClearSieges(sender: CommandSender, nation: String) = asyncCommand(sender) {
		val nationId = resolveNation(nation)
		val daysPerSiege = NATIONS_BALANCE.capturableStation.daysPerSiege
		val duration = TimeUnit.DAYS.toMillis(daysPerSiege.toLong())
		val date = Date(System.currentTimeMillis() - duration)
		val deleted = CapturableStationSiege.col
			.deleteMany(and(CapturableStationSiege::time gt date, CapturableStationSiege::nation eq nationId))
			.deletedCount
		sender msg "Deleted $deleted siege(s)"
	}

	@Subcommand("territory import")
	@Suppress("unused")
	fun onTerritoryImport(sender: CommandSender) {
		TerritoryImporter.importOldTerritories(sender)
	}

	@Subcommand("territory setOwner")
	@CommandCompletion("@nations")
	fun onTerritoryOwn(sender: Player, newOwner: String, @Optional confirm: String?) {
		val currentTerritory = requireTerritoryIn(sender)
		val nation = resolveNation(newOwner)

		failIf(currentTerritory.settlement != null) {
			"This territory is claimed by a settlement!"
		}

		if (confirm != "confirm") {
			val ownerName = currentTerritory.nation?.let { getNationName(it) }
			sender.userError("You are about to change the owner of this territory from $ownerName to ${getNationName(nation)}. You must confirm.")
		}

		Territory.setNation(currentTerritory.id, nation)
	}
}
