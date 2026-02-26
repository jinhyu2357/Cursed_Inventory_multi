package org.example.jinhhyu.cursed_Inventory

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.random.Random

class Cursed_Inventory : JavaPlugin(), Listener {

    private lateinit var lockKey: NamespacedKey
    private lateinit var lockBarrierTagKey: NamespacedKey

    private val trackedSlots: List<SlotRef> = buildList {
        repeat(36) { add(SlotRef.storage(it)) }
        add(SlotRef.HELMET)
        add(SlotRef.CHESTPLATE)
        add(SlotRef.LEGGINGS)
        add(SlotRef.BOOTS)
        add(SlotRef.OFF_HAND)
    }

    private val slotByKey: Map<String, SlotRef> by lazy {
        trackedSlots.associateBy { it.key }
    }

    private lateinit var sharedSlotAssignments: Map<SlotRef, Material>
    private lateinit var lockBarriers: Map<SlotRef, ItemStack>
    private val lockedSlots: MutableSet<SlotRef> = mutableSetOf()
    private val hiddenItems: MutableMap<SlotRef, ItemStack?> = mutableMapOf()
    private val sharedItems: MutableMap<SlotRef, ItemStack?> = mutableMapOf()
    private val playerSnapshots: MutableMap<UUID, MutableMap<SlotRef, ItemStack?>> = mutableMapOf()

    private var databaseConnection: Connection? = null
    private var progressDirty: Boolean = false
    private var syncTask: BukkitTask? = null
    private var saveTask: BukkitTask? = null
    private var showUnlockRequirementsOnWall: Boolean = true
    private var unlockBlockSourceMode: UnlockBlockSourceMode = UnlockBlockSourceMode.TAGS
    private var unlockAssignmentMode: UnlockAssignmentMode = UnlockAssignmentMode.SHUFFLED
    private var configuredUnlockMaterials: List<Material> = emptyList()
    private var configuredUnlockTags: List<String> = emptyList()
    private var randomUnlockSeed: Long = -1L
    private var randomUnlockPoolSize: Int = 64
    private var randomIncludeNonSolidBlocks: Boolean = false
    private var randomIncludeInteractableBlocks: Boolean = false
    private var randomExcludedMaterials: Set<Material> = emptySet()

    override fun onEnable() {
        lockKey = NamespacedKey(this, "locked_slot")
        lockBarrierTagKey = NamespacedKey(this, "lock_barrier")
        loadPluginConfig()
        initializeDefaultProgressState()
        initializeDatabase()
        val loaded = loadProgressFromDatabase()
        progressDirty = !loaded
        rebuildLockBarriers()

        server.pluginManager.registerEvents(this, this)
        server.onlinePlayers.forEach { seedSharedLockedItems(it) }
        server.onlinePlayers.forEach { initializePlayerState(it) }

        syncTask = server.scheduler.runTaskTimer(this, Runnable { synchronizeSharedInventory() }, 1L, 1L)
        saveTask = server.scheduler.runTaskTimer(this, Runnable { flushProgressIfDirty() }, 100L, 100L)
    }

    override fun onDisable() {
        synchronizeSharedInventory()
        saveProgressToDatabase()

        syncTask?.cancel()
        syncTask = null
        saveTask?.cancel()
        saveTask = null
        closeDatabase()

        lockedSlots.clear()
        hiddenItems.clear()
        sharedItems.clear()
        playerSnapshots.clear()
    }

    private fun loadPluginConfig() {
        saveDefaultConfig()
        reloadConfig()
        val showUnlockRequirementsPath = "display.show-unlock-requirements-on-wall"
        val defaultShowUnlockRequirements = false

        config.addDefault(showUnlockRequirementsPath, defaultShowUnlockRequirements)
        config.addDefault("unlock-blocks.source", UnlockBlockSourceMode.TAGS.configValue)
        config.addDefault("unlock-blocks.assignment-mode", UnlockAssignmentMode.SHUFFLED.configValue)
        config.addDefault("unlock-blocks.materials", emptyList<String>())
        config.addDefault("unlock-blocks.tags", defaultUnlockTagKeys())
        config.addDefault("unlock-blocks.random.seed", -1L)
        config.addDefault("unlock-blocks.random.pool-size", 64)
        config.addDefault("unlock-blocks.random.include-non-solid", false)
        config.addDefault("unlock-blocks.random.include-interactable", false)
        config.addDefault(
            "unlock-blocks.random.exclude",
            listOf("AIR", "CAVE_AIR", "VOID_AIR", "BARRIER", "BEDROCK")
        )
        config.options().copyDefaults(true)
        saveConfig()

        showUnlockRequirementsOnWall = config.getBoolean(showUnlockRequirementsPath, defaultShowUnlockRequirements)
        unlockBlockSourceMode = parseUnlockBlockSourceMode(config.getString("unlock-blocks.source"))
        unlockAssignmentMode = parseUnlockAssignmentMode(config.getString("unlock-blocks.assignment-mode"))
        configuredUnlockMaterials = parseMaterialList(
            config.getStringList("unlock-blocks.materials"),
            "unlock-blocks.materials"
        )
        configuredUnlockTags = config.getStringList("unlock-blocks.tags")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        randomUnlockSeed = config.getLong("unlock-blocks.random.seed", -1L)
        randomUnlockPoolSize = config.getInt("unlock-blocks.random.pool-size", 64).coerceAtLeast(1)
        randomIncludeNonSolidBlocks = config.getBoolean("unlock-blocks.random.include-non-solid", false)
        randomIncludeInteractableBlocks = config.getBoolean("unlock-blocks.random.include-interactable", false)
        randomExcludedMaterials = parseMaterialList(
            config.getStringList("unlock-blocks.random.exclude"),
            "unlock-blocks.random.exclude"
        ).toSet()
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        initializePlayerState(event.player)
    }

    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        server.scheduler.runTask(this, Runnable {
            applySharedState(event.player)
            updatePlayerSnapshot(event.player)
        })
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val matchingSlot = trackedSlots.firstOrNull { slot ->
            slot in lockedSlots && sharedSlotAssignments[slot] == event.block.type
        } ?: return

        unlockSlot(player, matchingSlot, event.block.type)
    }

    @EventHandler(ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.whoClicked !is Player) {
            return
        }

        if (isLockBarrier(event.currentItem) || isLockBarrier(event.cursor)) {
            event.isCancelled = true
            return
        }

        val clickedSlot = resolveClickedPlayerSlot(event)
        if (clickedSlot != null && isSlotLocked(clickedSlot)) {
            event.isCancelled = true
            return
        }

        if (event.click == ClickType.NUMBER_KEY) {
            val hotbarSlot = event.hotbarButton
            if (hotbarSlot in 0..8 && isSlotLocked(SlotRef.storage(hotbarSlot))) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.whoClicked !is Player) {
            return
        }

        if (isLockBarrier(event.oldCursor)) {
            event.isCancelled = true
            return
        }

        val topSize = event.view.topInventory.size
        for (rawSlot in event.rawSlots) {
            if (rawSlot < topSize) {
                continue
            }

            val localSlot = event.view.convertSlot(rawSlot)
            val slot = slotFromPlayerInventoryIndex(localSlot) ?: continue
            if (isSlotLocked(slot)) {
                event.isCancelled = true
                return
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        if (isLockBarrier(event.itemDrop.itemStack)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerSwapHands(event: PlayerSwapHandItemsEvent) {
        if (isLockBarrier(event.mainHandItem) || isLockBarrier(event.offHandItem)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (!isLockBarrier(event.item)) {
            return
        }

        if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (event.blockPlaced.type == Material.BARRIER || isTaggedLockBarrier(event.itemInHand)) {
            event.isCancelled = true
        }
    }

    private fun initializeDefaultProgressState() {
        sharedSlotAssignments = generateSlotAssignments()
        lockedSlots.clear()
        lockedSlots.addAll(trackedSlots)
        hiddenItems.clear()
        sharedItems.clear()
        playerSnapshots.clear()
    }

    private fun initializeDatabase() {
        try {
            Files.createDirectories(dataFolder.toPath())
            val databasePath = dataFolder.toPath().resolve("progress.db").toAbsolutePath()
            databaseConnection = DriverManager.getConnection("jdbc:sqlite:$databasePath")

            databaseConnection?.createStatement()?.use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS shared_slot_progress (
                        slot_key TEXT PRIMARY KEY,
                        required_block TEXT NOT NULL,
                        locked INTEGER NOT NULL,
                        hidden_item TEXT,
                        shared_item TEXT
                    )
                    """.trimIndent()
                )
            }
        } catch (exception: SQLException) {
            logger.severe("Could not open progress database: ${exception.message}")
            databaseConnection = null
        }
    }

    private fun closeDatabase() {
        try {
            databaseConnection?.close()
        } catch (exception: SQLException) {
            logger.warning("Could not close progress database cleanly: ${exception.message}")
        } finally {
            databaseConnection = null
        }
    }

    private fun loadProgressFromDatabase(): Boolean {
        val connection = databaseConnection ?: return false

        val loadedAssignments = mutableMapOf<SlotRef, Material>()
        val loadedLockedSlots = mutableSetOf<SlotRef>()
        val loadedHidden = mutableMapOf<SlotRef, ItemStack?>()
        val loadedShared = mutableMapOf<SlotRef, ItemStack?>()

        var rowCount = 0

        try {
            connection.prepareStatement(
                """
                SELECT slot_key, required_block, locked, hidden_item, shared_item
                FROM shared_slot_progress
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        rowCount++
                        val slotKey = result.getString("slot_key")
                        val slot = slotByKey[slotKey] ?: continue

                        val requiredBlockName = result.getString("required_block")
                        val requiredBlock = Material.matchMaterial(requiredBlockName)
                        if (requiredBlock == null) {
                            logger.warning("Ignoring invalid saved block '$requiredBlockName' for slot '$slotKey'.")
                            continue
                        }

                        loadedAssignments[slot] = requiredBlock
                        if (result.getInt("locked") != 0) {
                            loadedLockedSlots.add(slot)
                        }

                        val hiddenItem = deserializeItem(result.getString("hidden_item"))
                        if (hiddenItem != null) {
                            loadedHidden[slot] = hiddenItem
                        }

                        val sharedItem = deserializeItem(result.getString("shared_item"))
                        if (sharedItem != null) {
                            loadedShared[slot] = sharedItem
                        }
                    }
                }
            }
        } catch (exception: SQLException) {
            logger.severe("Could not load progress from database: ${exception.message}")
            return false
        }

        if (rowCount == 0) {
            return false
        }

        if (loadedAssignments.size != trackedSlots.size) {
            logger.warning("Saved progress is incomplete. Starting with fresh progress state.")
            return false
        }

        sharedSlotAssignments = loadedAssignments
        lockedSlots.clear()
        lockedSlots.addAll(loadedLockedSlots)
        hiddenItems.clear()
        hiddenItems.putAll(loadedHidden)
        sharedItems.clear()
        sharedItems.putAll(loadedShared)
        playerSnapshots.clear()

        logger.info("Loaded shared slot progress from ${dataFolder.toPath().resolve("progress.db")}.")
        return true
    }

    private fun saveProgressToDatabase() {
        val connection = databaseConnection ?: return

        try {
            connection.autoCommit = false

            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM shared_slot_progress")
            }

            connection.prepareStatement(
                """
                INSERT INTO shared_slot_progress (slot_key, required_block, locked, hidden_item, shared_item)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                for (slot in trackedSlots) {
                    val requiredBlock = sharedSlotAssignments[slot] ?: continue

                    statement.setString(1, slot.key)
                    statement.setString(2, requiredBlock.name)
                    statement.setInt(3, if (slot in lockedSlots) 1 else 0)
                    statement.setString(4, serializeItem(hiddenItems[slot]))
                    statement.setString(5, serializeItem(sharedItems[slot]))
                    statement.addBatch()
                }

                statement.executeBatch()
            }

            connection.commit()
            progressDirty = false
        } catch (exception: SQLException) {
            try {
                connection.rollback()
            } catch (_: SQLException) {
            }
            logger.severe("Could not save progress to database: ${exception.message}")
        } finally {
            try {
                connection.autoCommit = true
            } catch (_: SQLException) {
            }
        }
    }

    private fun flushProgressIfDirty() {
        if (!progressDirty) {
            return
        }
        saveProgressToDatabase()
    }

    private fun rebuildLockBarriers() {
        lockBarriers = trackedSlots.associateWith { slot ->
            createLockBarrier(slot, sharedSlotAssignments.getValue(slot))
        }
    }

    private fun initializePlayerState(player: Player) {
        seedSharedLockedItems(player)
        applySharedState(player)
        updatePlayerSnapshot(player)

        player.sendMessage(
            Component.text(
                "Inventory slots are cursed and shared. Unlocks and item state apply to everyone.",
                NamedTextColor.GOLD
            )
        )
    }

    private fun generateSlotAssignments(): Map<SlotRef, Material> {
        val random = createUnlockRandom()
        val unlockPool = resolveUnlockBlockPool(random)

        logger.info(
            "Generating unlock assignments with ${unlockPool.size} materials " +
                "(source=${unlockBlockSourceMode.configValue}, assignment=${unlockAssignmentMode.configValue})."
        )

        return when (unlockAssignmentMode) {
            UnlockAssignmentMode.SHUFFLED -> {
                val shuffled = unlockPool.shuffled(random)
                trackedSlots.mapIndexed { index, slot ->
                    slot to shuffled[index % shuffled.size]
                }.toMap()
            }
            UnlockAssignmentMode.RANDOM -> {
                trackedSlots.associateWith { unlockPool.random(random) }
            }
        }
    }

    private fun resolveUnlockBlockPool(random: Random): List<Material> {
        val materialsFromTags by lazy { resolveMaterialsFromTags(configuredUnlockTags) }
        val randomMaterials by lazy { buildRandomUnlockMaterialPool(random, randomUnlockPoolSize) }

        val selected = when (unlockBlockSourceMode) {
            UnlockBlockSourceMode.CONFIGURED -> configuredUnlockMaterials
            UnlockBlockSourceMode.TAGS -> materialsFromTags
            UnlockBlockSourceMode.RANDOM -> randomMaterials
            UnlockBlockSourceMode.COMBINED -> linkedSetOf<Material>().apply {
                addAll(configuredUnlockMaterials)
                addAll(materialsFromTags)
                addAll(randomMaterials)
            }.toList()
        }

        if (selected.isNotEmpty()) {
            return selected
        }

        logger.warning(
            "Unlock source '${unlockBlockSourceMode.configValue}' produced no usable materials. " +
                "Falling back to default block tags."
        )

        val fallbackFromDefaultTags = resolveMaterialsFromTags(defaultUnlockTagKeys())
        if (fallbackFromDefaultTags.isNotEmpty()) {
            return fallbackFromDefaultTags
        }

        logger.warning("Default block tags also produced no materials. Falling back to a random block pool.")
        val fallbackRandom = buildRandomUnlockMaterialPool(random, trackedSlots.size * 2)
        if (fallbackRandom.isNotEmpty()) {
            return fallbackRandom
        }

        throw IllegalStateException("Could not resolve unlock materials from config, tags, or random block pool.")
    }

    private fun resolveMaterialsFromTags(tagKeys: List<String>): List<Material> {
        if (tagKeys.isEmpty()) {
            return emptyList()
        }

        val materials = linkedSetOf<Material>()

        for (rawTagKey in tagKeys) {
            val tagKey = rawTagKey.trim()
            if (tagKey.isEmpty()) {
                continue
            }

            val namespacedKey = NamespacedKey.fromString(tagKey)
            if (namespacedKey == null) {
                logger.warning("Ignoring invalid block tag key '$tagKey'.")
                continue
            }

            val tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, namespacedKey, Material::class.java)
            if (tag == null) {
                logger.warning("Could not find block tag '$tagKey'.")
                continue
            }

            tag.values
                .asSequence()
                .filter { it.isBlock && it.isItem && !it.isAir }
                .forEach { materials += it }
        }

        return materials.sortedBy { it.name }
    }

    @Suppress("DEPRECATION")
    private fun buildRandomUnlockMaterialPool(random: Random, sampleSize: Int): List<Material> {
        val candidates = Material.values()
            .asSequence()
            .filter { it.isBlock && it.isItem && !it.isAir }
            .filter { randomIncludeNonSolidBlocks || it.isSolid }
            .filter { randomIncludeInteractableBlocks || !it.isInteractable }
            .filter { it !in randomExcludedMaterials }
            .toList()

        if (candidates.isEmpty()) {
            return emptyList()
        }

        val clampedSize = sampleSize.coerceIn(1, candidates.size)
        return candidates.shuffled(random).take(clampedSize)
    }

    private fun createUnlockRandom(): Random {
        val seed = if (randomUnlockSeed == -1L) System.currentTimeMillis() else randomUnlockSeed
        logger.info("Unlock block random seed: $seed")
        return Random(seed)
    }

    private fun parseMaterialList(values: List<String>, path: String): List<Material> {
        if (values.isEmpty()) {
            return emptyList()
        }

        val materials = linkedSetOf<Material>()
        for (rawValue in values) {
            val materialName = rawValue.trim()
            if (materialName.isEmpty()) {
                continue
            }

            val material = Material.matchMaterial(materialName)
            if (material == null) {
                logger.warning("Ignoring unknown material '$materialName' in '$path'.")
                continue
            }

            if (!material.isBlock || !material.isItem || material.isAir) {
                logger.warning("Ignoring non-usable block '$materialName' in '$path'.")
                continue
            }

            materials += material
        }

        return materials.toList()
    }

    private fun parseUnlockBlockSourceMode(rawValue: String?): UnlockBlockSourceMode {
        val normalized = rawValue?.trim()?.lowercase() ?: return UnlockBlockSourceMode.TAGS

        return when (normalized) {
            "configured", "configured-list", "materials", "list" -> UnlockBlockSourceMode.CONFIGURED
            "tags", "tag" -> UnlockBlockSourceMode.TAGS
            "random", "random-pool" -> UnlockBlockSourceMode.RANDOM
            "combined", "mixed" -> UnlockBlockSourceMode.COMBINED
            else -> {
                logger.warning("Unknown unlock-block source '$rawValue'. Falling back to 'tags'.")
                UnlockBlockSourceMode.TAGS
            }
        }
    }

    private fun parseUnlockAssignmentMode(rawValue: String?): UnlockAssignmentMode {
        val normalized = rawValue?.trim()?.lowercase() ?: return UnlockAssignmentMode.SHUFFLED

        return when (normalized) {
            "shuffled", "shuffle" -> UnlockAssignmentMode.SHUFFLED
            "random" -> UnlockAssignmentMode.RANDOM
            else -> {
                logger.warning("Unknown unlock assignment mode '$rawValue'. Falling back to 'shuffled'.")
                UnlockAssignmentMode.SHUFFLED
            }
        }
    }

    private fun defaultUnlockTagKeys(): List<String> {
        return listOf(
            "minecraft:mineable/pickaxe",
            "minecraft:mineable/axe",
            "minecraft:mineable/shovel",
            "minecraft:mineable/hoe"
        )
    }

    private fun seedSharedLockedItems(player: Player) {
        val inventory = player.inventory

        for (slot in trackedSlots) {
            if (slot !in lockedSlots) {
                continue
            }

            val currentItem = getSlotItem(inventory, slot)
            if (isLockBarrier(currentItem)) {
                continue
            }

            val candidate = normalizedClone(currentItem)
            if (!hiddenItems.containsKey(slot) || (hiddenItems[slot] == null && candidate != null)) {
                if (setSharedItem(hiddenItems, slot, candidate)) {
                    markProgressDirty()
                }
            }
        }
    }

    private fun applySharedState(player: Player) {
        val inventory = player.inventory

        for (slot in trackedSlots) {
            if (slot in lockedSlots) {
                val currentItem = getSlotItem(inventory, slot)
                if (!isLockBarrier(currentItem)) {
                    val candidate = normalizedClone(currentItem)
                    if (!hiddenItems.containsKey(slot) || (hiddenItems[slot] == null && candidate != null)) {
                        if (setSharedItem(hiddenItems, slot, candidate)) {
                            markProgressDirty()
                        }
                    }
                }

                val barrier = lockBarriers.getValue(slot)
                if (!isLockBarrier(currentItem) || !itemsEqual(currentItem, barrier)) {
                    setSlotItem(inventory, slot, barrier.clone())
                }
                continue
            }

            val sharedItem = sharedItems[slot]
            val currentItem = getSlotItem(inventory, slot)
            if (isLockBarrier(currentItem) || !itemsEqual(currentItem, sharedItem)) {
                setSlotItem(inventory, slot, normalizedClone(sharedItem))
            }
        }
    }

    private fun unlockSlot(player: Player, slot: SlotRef, minedBlock: Material) {
        if (!lockedSlots.remove(slot)) {
            return
        }
        markProgressDirty()

        val restoredItem = hiddenItems.remove(slot)?.clone()
        if (restoredItem != null) {
            markProgressDirty()
        }

        if (setSharedItem(sharedItems, slot, restoredItem)) {
            markProgressDirty()
        }
        synchronizeSharedInventory()
        summonUnlockFirecracker(player)

        val message = Component.text(
            "${player.name} unlocked ${slot.displayName()} for everyone by mining ${minedBlock.prettyName()}.",
            NamedTextColor.GREEN
        )
        server.onlinePlayers.forEach { online ->
            online.sendMessage(message)
        }
    }

    private fun summonUnlockFirecracker(player: Player) {
        val firework = player.world.spawn(player.location.clone().add(0.0, 1.0, 0.0), Firework::class.java)
        val fireworkMeta = firework.fireworkMeta
        fireworkMeta.clearEffects()
        fireworkMeta.addEffect(
            FireworkEffect.builder()
                .with(FireworkEffect.Type.BURST)
                .flicker(true)
                .withColor(Color.ORANGE, Color.WHITE)
                .withFade(Color.RED)
                .build()
        )
        fireworkMeta.power = 1
        firework.fireworkMeta = fireworkMeta

        server.scheduler.runTaskLater(this, Runnable {
            if (!firework.isDead && firework.isValid) {
                firework.detonate()
            }
        }, 2L)
    }

    private fun synchronizeSharedInventory() {
        val players = server.onlinePlayers
        if (players.isEmpty()) {
            return
        }

        syncUnlockedItemsFromSnapshots(players)
        players.forEach { applySharedState(it) }
        updateSnapshots(players)
    }

    private fun syncUnlockedItemsFromSnapshots(players: Collection<Player>) {
        for (player in players) {
            val snapshot = playerSnapshots.getOrPut(player.uniqueId) { mutableMapOf() }
            val inventory = player.inventory

            for (slot in trackedSlots) {
                if (slot in lockedSlots) {
                    snapshot.remove(slot)
                    continue
                }

                val currentItem = getSlotItem(inventory, slot)
                if (isLockBarrier(currentItem)) {
                    continue
                }

                val normalizedCurrent = normalizedClone(currentItem)
                val previous = snapshot[slot]
                if (!itemsEqual(normalizedCurrent, previous)) {
                    if (setSharedItem(sharedItems, slot, normalizedCurrent)) {
                        markProgressDirty()
                    }
                }
            }
        }
    }

    private fun updateSnapshots(players: Collection<Player>) {
        val onlineUuids = players.mapTo(mutableSetOf()) { it.uniqueId }
        playerSnapshots.keys.retainAll(onlineUuids)
        players.forEach { updatePlayerSnapshot(it) }
    }

    private fun updatePlayerSnapshot(player: Player) {
        val snapshot = playerSnapshots.getOrPut(player.uniqueId) { mutableMapOf() }
        val inventory = player.inventory

        for (slot in trackedSlots) {
            if (slot in lockedSlots) {
                snapshot.remove(slot)
                continue
            }

            snapshot[slot] = normalizedClone(getSlotItem(inventory, slot))
        }
    }

    private fun setSharedItem(target: MutableMap<SlotRef, ItemStack?>, slot: SlotRef, item: ItemStack?): Boolean {
        val normalized = normalizedClone(item)
        val existing = target[slot]

        if (itemsEqual(existing, normalized)) {
            return false
        }

        if (normalized == null) {
            target.remove(slot)
        } else {
            target[slot] = normalized
        }
        return true
    }

    private fun markProgressDirty() {
        progressDirty = true
    }

    private fun serializeItem(item: ItemStack?): String? {
        val normalized = normalizeItem(item) ?: return null
        val yaml = YamlConfiguration()
        yaml.set("item", normalized)
        return yaml.saveToString()
    }

    private fun deserializeItem(serializedItem: String?): ItemStack? {
        if (serializedItem.isNullOrBlank()) {
            return null
        }

        return try {
            val yaml = YamlConfiguration()
            yaml.loadFromString(serializedItem)
            normalizedClone(yaml.getItemStack("item"))
        } catch (_: InvalidConfigurationException) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun createLockBarrier(slot: SlotRef, requiredBlock: Material): ItemStack {
        val barrier = ItemStack(Material.BARRIER)
        val meta = barrier.itemMeta

        meta.addEnchant(Enchantment.VANISHING_CURSE, 1, true)
        meta.displayName(Component.text("Locked: ${slot.displayName()}", NamedTextColor.RED))
        val unlockLine = if (showUnlockRequirementsOnWall) {
            "Mine ${requiredBlock.prettyName()} to unlock."
        } else {
            "잠김"
        }
        meta.lore(
            listOf(
                Component.text(unlockLine, NamedTextColor.YELLOW),
                //Component.text("", NamedTextColor.GRAY)
            )
        )
        // Prevent placement through item NBT in restricted modes.
        meta.setCanPlaceOn(emptySet())
        meta.persistentDataContainer.set(lockKey, PersistentDataType.STRING, slot.key)
        meta.persistentDataContainer.set(lockBarrierTagKey, PersistentDataType.BYTE, 1)

        barrier.itemMeta = meta
        return barrier
    }

    private fun normalizedClone(item: ItemStack?): ItemStack? {
        return normalizeItem(item)?.clone()
    }

    private fun normalizeItem(item: ItemStack?): ItemStack? {
        if (item == null || item.type == Material.AIR) {
            return null
        }
        return item
    }

    private fun itemsEqual(first: ItemStack?, second: ItemStack?): Boolean {
        val left = normalizeItem(first)
        val right = normalizeItem(second)

        if (left == null || right == null) {
            return left == null && right == null
        }

        return left.amount == right.amount && left.isSimilar(right)
    }

    private fun isSlotLocked(slot: SlotRef): Boolean {
        return slot in lockedSlots
    }

    private fun resolveClickedPlayerSlot(event: InventoryClickEvent): SlotRef? {
        event.clickedInventory as? PlayerInventory ?: return null
        return slotFromPlayerInventoryIndex(event.slot)
    }

    private fun slotFromPlayerInventoryIndex(index: Int): SlotRef? {
        return when (index) {
            in 0..35 -> SlotRef.storage(index)
            36 -> SlotRef.BOOTS
            37 -> SlotRef.LEGGINGS
            38 -> SlotRef.CHESTPLATE
            39 -> SlotRef.HELMET
            40 -> SlotRef.OFF_HAND
            else -> null
        }
    }

    private fun getSlotItem(inventory: PlayerInventory, slot: SlotRef): ItemStack? {
        return when (slot.type) {
            SlotType.STORAGE -> inventory.getItem(slot.index)
            SlotType.HELMET -> inventory.helmet
            SlotType.CHESTPLATE -> inventory.chestplate
            SlotType.LEGGINGS -> inventory.leggings
            SlotType.BOOTS -> inventory.boots
            SlotType.OFF_HAND -> inventory.itemInOffHand
        }
    }

    private fun setSlotItem(inventory: PlayerInventory, slot: SlotRef, item: ItemStack?) {
        when (slot.type) {
            SlotType.STORAGE -> inventory.setItem(slot.index, item)
            SlotType.HELMET -> inventory.helmet = item
            SlotType.CHESTPLATE -> inventory.chestplate = item
            SlotType.LEGGINGS -> inventory.leggings = item
            SlotType.BOOTS -> inventory.boots = item
            SlotType.OFF_HAND -> inventory.setItemInOffHand(item)
        }
    }

    private fun isLockBarrier(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.BARRIER) {
            return false
        }

        val meta = item.itemMeta ?: return false
        val data = meta.persistentDataContainer
        val slotKey = data.get(lockKey, PersistentDataType.STRING) ?: return false
        val marker = data.get(lockBarrierTagKey, PersistentDataType.BYTE)?.toInt() == 1
        return marker && slotByKey.containsKey(slotKey)
    }

    private fun isTaggedLockBarrier(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.BARRIER) {
            return false
        }

        val meta = item.itemMeta ?: return false
        val data = meta.persistentDataContainer
        return data.has(lockBarrierTagKey, PersistentDataType.BYTE) ||
            data.has(lockKey, PersistentDataType.STRING)
    }

    private fun Material.prettyName(): String {
        return name
            .lowercase()
            .split('_')
            .joinToString(" ") { token -> token.replaceFirstChar { ch -> ch.uppercaseChar() } }
    }

    private enum class SlotType {
        STORAGE,
        HELMET,
        CHESTPLATE,
        LEGGINGS,
        BOOTS,
        OFF_HAND
    }

    private enum class UnlockBlockSourceMode(val configValue: String) {
        CONFIGURED("configured"),
        TAGS("tags"),
        RANDOM("random"),
        COMBINED("combined")
    }

    private enum class UnlockAssignmentMode(val configValue: String) {
        SHUFFLED("shuffled"),
        RANDOM("random")
    }

    private data class SlotRef(val type: SlotType, val index: Int = -1) {
        val key: String
            get() = when (type) {
                SlotType.STORAGE -> "storage_$index"
                SlotType.HELMET -> "helmet"
                SlotType.CHESTPLATE -> "chestplate"
                SlotType.LEGGINGS -> "leggings"
                SlotType.BOOTS -> "boots"
                SlotType.OFF_HAND -> "off_hand"
            }

        fun displayName(): String {
            return when (type) {
                SlotType.STORAGE -> if (index in 0..8) {
                    "Hotbar ${index + 1}"
                } else {
                    "Inventory ${index - 8}"
                }
                SlotType.HELMET -> "Helmet"
                SlotType.CHESTPLATE -> "Chestplate"
                SlotType.LEGGINGS -> "Leggings"
                SlotType.BOOTS -> "Boots"
                SlotType.OFF_HAND -> "Off-Hand"
            }
        }

        companion object {
            val HELMET = SlotRef(SlotType.HELMET)
            val CHESTPLATE = SlotRef(SlotType.CHESTPLATE)
            val LEGGINGS = SlotRef(SlotType.LEGGINGS)
            val BOOTS = SlotRef(SlotType.BOOTS)
            val OFF_HAND = SlotRef(SlotType.OFF_HAND)

            fun storage(index: Int): SlotRef = SlotRef(SlotType.STORAGE, index)
        }
    }
}
