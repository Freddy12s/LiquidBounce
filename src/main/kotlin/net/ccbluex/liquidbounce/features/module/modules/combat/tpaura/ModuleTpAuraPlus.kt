/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.tpaura

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.KeybindIsPressedEvent
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.integration.screen.ScreenManager
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.screens.Screen
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBox
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.clicking.Clicker
import net.ccbluex.liquidbounce.utils.clicking.isMissCooldownActive
import net.ccbluex.liquidbounce.utils.combat.TargetPriority
import net.ccbluex.liquidbounce.utils.combat.TargetSelector
import net.ccbluex.liquidbounce.utils.combat.attackEntity
import net.ccbluex.liquidbounce.utils.entity.getEyePositionAt
import net.ccbluex.liquidbounce.utils.entity.ping
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.math.fma
import net.ccbluex.liquidbounce.utils.navigation.route.RouteResult
import net.ccbluex.liquidbounce.utils.navigation.teleport.Route
import net.ccbluex.liquidbounce.utils.navigation.teleport.StepsValueGroup
import net.ccbluex.liquidbounce.utils.navigation.teleport.Teleport
import net.ccbluex.liquidbounce.utils.navigation.teleport.TeleportManager
import net.ccbluex.liquidbounce.utils.navigation.teleport.Trip
import net.ccbluex.liquidbounce.utils.navigation.terrain.Terrain
import net.ccbluex.liquidbounce.utils.navigation.terrain.hasRoom
import net.ccbluex.liquidbounce.utils.navigation.terrain.reachFeetPositions
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * TpAuraPlus — teleport-assisted melee.
 *
 * An engagement is a small state machine: pick a target, find a route to a spot near it, wait for the
 * attack cooldown, then swing and either return home or stay. The destination is chosen from a short
 * extrapolation of the target's measured motion; on fast targets the optional [PredictionValueGroup.aggressive]
 * spray covers several future positions at once, because the client only sees a lagging picture of a
 * flying player. Every swing is a two-step move: send the position on one tick and the attack on the
 * next, so the server judges the attack from the spot it was just told about.
 *
 * Debug logging writes the stage-by-stage record to `logs/tpauraplus.log`; the HUD reports the live
 * flight estimate, hit confirmation and the current reach. The server's reach is protocol-dependent:
 * 1.8–1.20.4 allow six blocks with line of sight and three without it, while newer versions use the
 * `entity_interaction_range` attribute. Planning always errs toward the smaller budget.
 */
@Suppress("TooManyFunctions")
object ModuleTpAuraPlus : ClientModule("TpAuraPlus", ModuleCategories.COMBAT, disableOnQuit = true) {

    private val rangeValue = float("Range", 30f, 6f..100f, "blocks")
    private val range by rangeValue
    private val afterAttack by enumChoice("AfterAttack", AfterAttack.RETURN)
    /**
     * How many swings one attack sequence takes before it leaves. Only [AfterAttack.RETURN] counts them:
     * with `Stay` the engagement never leaves on its own, so the setting does nothing in that mode.
     * The counter is created with the engagement and survives re-routes, so it really means "swings
     * per engagement"; [DebugValueGroup.holdRepins] bounds how often the sequence may follow the target.
     */
    private val hold by int("Hold", 1, 1..10, "swings")

    private val prediction = tree(PredictionValueGroup())
    /**
     * Side of the target the landing spot prefers, by its body yaw. Only the plain single-spot search
     * sorts by it; the spray picks its probe chain from the target's path instead, so it has no effect
     * while [PredictionValueGroup.aggressive] is on. It stays visible — see [hold] for why.
     */
    private val landingSide by enumChoice("LandingSide", LandingSide.ANY)
    private val flight by enumChoice("PacketStyle", Teleport.FlightMode.BURST, aliases = listOf("Flight"))

    /** The protocol takes the attack packet straight — no look, no click simulation, just the cooldown. */
    private val attackWithoutLooking by boolean("AttackWithoutLooking", false)

    /** The arm swings on the client only; the server sees a bare attack. */
    private val attackWithoutSwing by boolean("AttackWithoutSwing", false)

    /**
     * Through-wall attacking, its reach nested under the switch. The range is capped at three blocks
     * because that is the server's own blind budget ([BLIND_REACH]) — the engage gate refuses anything
     * further and a real server also rejects walls thicker than two blocks, so a larger slider only
     * widened the spot search without ever connecting.
     */
    private val throughWall = tree(object : ToggleableValueGroup(this@ModuleTpAuraPlus, "ThroughWall", false) {
        val range by float("Range", 2f, 0f..3f, "blocks")
    })
    private val allowThroughWall: Boolean get() = throughWall.enabled
    private val throughWallRange: Float get() = throughWall.range

    private val hud = tree(HudValueGroup())
    private val debug = tree(DebugValueGroup())
    private val sfx = tree(SfxValueGroup())
    private val manual = tree(ManualValueGroup())

    private val targetSelector = tree(TargetSelector(TargetPriority.DISTANCE, rangeValue))
    /** Allows hovering landing spots; the spray always tries them, so this only shapes the plain search. */
    private val airTargetsValue = targetSelector.boolean("AirTargets", false)
    private val airTargets by airTargetsValue

    /** The spray needs the prediction: without it the probes would aim at where the target stands. */
    private val sprays: Boolean
        get() = prediction.enabled && prediction.aggressive

    // The click plan and the rotations only shape the looked attack; without it they configure nothing.
    // `AttackWithoutLooking` sends the packet straight, so the rotation group is inert in that mode;
    // the clicker keeps the item cooldown that `attackReady()` still reads and charges after a swing.
    // Both stay visible: hiding a group makes the theme duplicate its neighbours (chapter 13).
    private val clicker = tree(Clicker(this, mc.options.keyAttack))
    private val rotations = tree(RotationsValueGroup(this, combatSpecific = true))
    private val steps = tree(StepsValueGroup())

    private val trip = Trip(this, steps)
    private val view = trip.view

    init {
        tree(trip.renderer)
    }

    /** Diagnostics and the search/probe budgets. */
    private class DebugValueGroup : ValueGroup("Debug") {

        /** Writes the stage-by-stage record to `logs/tpauraplus.log` and mirrors key changes to chat. */
        val log by boolean("Log", false)

        /** How much more of the terrain the A* may expand for a route; 1 keeps the engine's own budget. */
        val pathBudget by float("PathBudget", 1f, 1f..4f, "x")

        /** Upper bound on probe candidates: also used by the plain path when it picks a single spot. */
        val maxProbes by int("MaxProbes", 8, 2..12, "probes")

        /**
         * First probe lead, in ticks, on top of the round trip the prediction adds itself. Zero starts
         * the chain at the target's current position; raising it skips the early probes where the
         * server no longer has the target and shifts the whole chain forward.
         */
        val probeLeadStart by int("ProbeLeadStart", 0, 0..3, "ticks")

        /**
         * How often a [hold] sequence may teleport again after the target left reach, before it gives
         * up and returns. Zero means "stand where the first arrival put us, hit or leave".
         */
        val holdRepins by int("HoldRepins", 2, 0..5, "re-chases")

    }

    private class PredictionValueGroup : ValueGroup("Prediction") {
        val enabled by boolean("Enabled", true)

        /**
         * Ceiling for the *measured* flight; the actual aim lead is the rolling average of measured
         * flight ticks. The network round trip (`ping / 50`) is added on top of this by the prediction,
         * so the effective lead can exceed this value by one ping.
         */
        val ticks by int("MaxLead", 5, 1..10, "ticks", aliases = listOf("Ticks"))

        /** Moves the destination while the route is still being searched, at most every few ticks. */
        val liveRetarget by boolean("LiveRetarget", true)

        /**
         * Covers several future positions at once. Useful against fast flyers, where a single
         * predicted spot is easily wrong by more than the server's reach.
         */
        val aggressive by boolean("Spray", false, aliases = listOf("Aggressive"))
    }

    private enum class AfterAttack(override val tag: String) : Tagged {
        RETURN("Return"),
        STAY("Stay"),
    }

    private enum class Trigger(override val tag: String) : Tagged {
        LEFT_CLICK("LeftClick"),
        RIGHT_CLICK("RightClick"),
        JUMP("Jump"),
        SNEAK("Sneak"),
    }

    /** Armed but holding: routing is shown, nothing sets off until the player's action. */
    private class ManualValueGroup : ValueGroup("AttackManually") {
        val enabled by boolean("Enabled", false)
        val trigger by enumChoice("Trigger", Trigger.LEFT_CLICK, aliases = listOf("AttackWhen"))
        val preventExtraAction by boolean("PreventExtraAction", true)

        /** Manual-only: the automatic mode always draws the route it is flying. */
        val showRoute by boolean("ShowRoute", true, aliases = listOf("ShowPath"))
        val refreshInterval by int("RefreshInterval", 8, 1..20, "ticks", aliases = listOf("RerouteInterval"))
    }

    /** Sound hints for the stages of an engagement: a switch per event with its sound identifier. */
    private class SfxValueGroup : ValueGroup("SoundHints", aliases = listOf("SFXTips")) {
        val enabled by boolean("Enabled", true)
        val volume by float("Volume", 60f, 0f..100f, "%")

        val pathFinding = tree(hint("PathFinding", "OnPathFinding", false, "block.enchantment_table.use"))
        val teleport = tree(hint("Teleport", "OnTeleport", false, "entity.enderman.teleport"))
        val hitSuccess = tree(hint("HitSuccess", "OnHitSuccess", false, "entity.arrow.hit_player"))
        val hitFail = tree(hint("HitFail", "OnHitFail", true, "entity.villager.trade"))
        val serverSetback = tree(hint("ServerSetback", "OnServerSetback", true, "entity.villager.no"))
        val routeFail = tree(hint("RouteFail", "OnRouteFail", true, "block.note_block.bass"))

        /** One hint, named after the stage, keeping the old `On…` name for saved configs. */
        private fun hint(name: String, legacy: String, enabled: Boolean, sound: String) =
            SoundValueGroup(name, enabled, sound, aliases = listOf(legacy)) { volume }
    }

    /** One hint: the switch of the event with the sound it plays, previewed while its name is typed. */
    private class SoundValueGroup(
        name: String,
        enabled: Boolean,
        defaultSound: String,
        aliases: List<String> = emptyList(),
        private val volume: () -> Float,
    ) : ToggleableValueGroup(null, name, enabled, aliases) {

        val soundValue = text("Sound", defaultSound)

        init {
            soundValue.onChanged { play(it, volume() / 100f) }
        }

        companion object {
            /** Plays [identifier] over the game's master volume; silent when it does not resolve. */
            fun play(identifier: String, volume: Float) {
                val id = Identifier.tryParse(identifier) ?: return
                val sound = BuiltInRegistries.SOUND_EVENT.get(id).orElse(null) ?: return
                val level = mc.level ?: return
                val master = mc.options.getFinalSoundSourceVolume(SoundSource.MASTER)
                level.playSound(player, player.x, player.y, player.z, sound, SoundSource.MASTER, volume * master, 1f)
            }
        }
    }

    /** Which side of the target the landing spot prefers. */
    private enum class LandingSide(override val tag: String) : Tagged {
        ANY("Any"),
        BEHIND("Behind"),
        FLANKS("Flanks"),
    }

    private enum class Phase { IDLE, ROUTING, READY, APPROACH, ATTACK, RETURN }

    /** Where the feet of an engagement may end, standing or hovering. */
    private class AttackGoals(val positions: List<BlockPos>, val feet: Map<BlockPos, Vec3>)

    private class Engagement(val target: LivingEntity) {
        /** When the engagement began; retargets must not reset it, or the measured flight shrinks. */
        var engageTick = -1

        /** Set once the two tick attack has moved the player and is waiting to swing. */
        var waitingSwing = false
        var movedTick = 0
        var route: Route? = null
        var waited = 0
        var holdLeft = 0
        var retries = 0
        var dispatchFails = 0

        /** Re-teleports spent following the target during this hold sequence. */
        var repins = 0

        /** Re-routes because the route went stale under the player's own speed, not real failures. */
        var staleRetries = 0
        var attacked = false

        /** Ticks in ATTACK without a click opportunity, to give up when the click plan stalls. */
        var idleTicks = 0

        /** Click ticks the gate turned away, to give up when the aim never lands. */
        var blockedTicks = 0

        /** The goals chosen for this engagement, reused on a replan until the target has run away. */
        var plan: AttackGoals? = null
        var planAnchor: Vec3? = null

        /** Where the goals were aimed at last; a drift moves the destination. */
        var goalAnchor: Vec3? = null

        /** Last tick a retarget was attempted, so the search is not restarted every tick. */
        var retargetTick = 0

        /** Ticks spent searching for this engagement. */
        var searchTicks = 0

        /** When the route was last requested, to know how long the swing is still away. */
        var requestTick = 0

        /** Where the engagement began, to measure how far the player walked during it. */
        var origin: Vec3? = null
    }

    private var phase = Phase.IDLE
    private var engagement: Engagement? = null
    private var ticks = 0
    private var restUntil = 0

    /** The tick the last sound went out on, and how important it was — one sound wins a tick. */
    private var sfxTick = -1
    private var sfxPriority = 0

    /** Armed for a manual trigger: the route is shown and nothing sets off until the player acts. */
    private var manualTriggered = false
    private var manualRefreshAt = 0

    /** The target whose damage we are waiting for, and the tick that wait ends on. */
    private var awaitingHit: LivingEntity? = null
    private var hitWaitUntil = 0

    /**
     * Health of the target when the last swing went out, the lowest value seen since, and when to
     * report. The minimum matters: a regenerating target heals the damage back within a few ticks.
     */
    private var hpCheckTarget: LivingEntity? = null
    private var hpCheckValue = 0.0
    private var hpCheckLowest = 0.0
    private var hpCheckTick = 0

    /** Spray-only: the forward aim recorded at send time, compared at the delayed HP check. */
    private var aimAhead: Vec3? = null

    /** One-shot flag: the HURT-WAIT line is written once per wait, not on every tick of it. */
    private var hurtWaiting = false

    /** When the return run began, and since when the two positions disagree — both watched for stalls. */
    private var returnSince = 0
    private var desyncSince = 0

    /** The binding whose queued press the next tick swallows, and the binding muted for that tick. */
    private var pendingSuppress: KeyMapping? = null
    private var mutedBinding: KeyMapping? = null
    private var mutedTick = -1

    /** Targets without a route, by entity id, until the tick they may be tried again. */
    private val unreachable = Int2IntOpenHashMap()

    /** Positions of the watched target, to measure its velocity and turn. */
    private val targetSamples = ArrayDeque<Pair<Int, Vec3>>()
    private var samplesId = 0

    /** The last target, sampled even while the target filter excludes it. */
    private var watchedTarget: LivingEntity? = null

    /** Rolling average of the ticks from requesting a route to the swing, used as the aim lead. */
    private var flightAvg = 3.0

    /** Target and health seen last tick, to turn a drop into the damage of the last swing. */
    private var hudTargetId = -1
    private var hudTargetHealth = -1.0
    private var hudLastDamage = 0.0

    private val reach: Double
        get() = player.entityInteractionRange() - REACH_MARGIN

    /**
     * The reach the server allows against [target] from [feet]. Both 1.8 and 1.12.2 compare the distance
     * against six blocks when a player has line of sight to the target's eyes and three when they do not;
     * the look direction plays no part in it.
     */
    private fun reachTo(feet: Vec3, target: LivingEntity): Double =
        min(reach, if (sighted(feet, target)) SIGHTED_REACH else BLIND_REACH)

    /** The server's own test: a ray from our eyes to the target's eyes, blocked by any collider. */
    private fun sighted(feet: Vec3, target: LivingEntity): Boolean {
        val eyes = target.position().add(0.0, target.eyeHeight.toDouble(), 0.0)
        return world.clip(
            ClipContext(
                player.getEyePositionAt(feet),
                eyes,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player,
            ),
        ).type == HitResult.Type.MISS
    }

    /**
     * The engagement while its target is still one. The hurt-time filter is deliberately skipped: a
     * target that was just hit stays the target, otherwise the fight would end after the first swing
     * and `Hold` could never count past one. When a swing may go out is decided in [attack].
     */
    private val liveEngagement: Engagement?
        get() = engagement?.takeIf { targetSelector.validate(it.target, checkHurtTime = false) }

    override fun onEnabled() {
        TpAuraPlusLog.open()
        dlog(
            "ENABLE",
            TpAuraPlusLog.details(
                "range" to range,
                "afterAttack" to afterAttack,
                "hold" to hold,
                "prediction" to prediction.enabled,
                "maxLead" to prediction.ticks,
                "probeLeadStart" to debug.probeLeadStart,
                "liveRetarget" to prediction.liveRetarget,
                "airTargets" to airTargets,
                "landingSide" to landingSide,
                "packetStyle" to flight,
                "attackWithoutLooking" to attackWithoutLooking,
                "attackWithoutSwing" to attackWithoutSwing,
                "allowThroughWall" to allowThroughWall,
                "throughWallRange" to fmt(throughWallRange.toDouble()),
                "spray" to sprays,
                "manual" to manual.enabled,
                "soundHints" to sfx.enabled,
                "reach" to fmt(reach),
                "packetsPerTick" to steps.packetsPerTick,
            ),
        )
    }

    override fun onDisabled() {
        dlog("DISABLE", TpAuraPlusLog.details("phase" to phase))
        TpAuraPlusHud.reset()
        TeleportManager.undo(this)
        reset()
        restUntil = 0
        unreachable.clear()
        TpAuraPlusLog.close()
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        dlog("WORLD-CHANGE")
        reset()
        unreachable.clear()
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        ticks++
        keepSampling()
        syncHud()

        // The press that triggered the engagement: drop its queued click and mute the key for this
        // tick, before the game drains the click queue and reads the key states. Attacks and item
        // use come from that queue, the held keys from the state — both must be covered here.
        pendingSuppress?.let { binding ->
            binding.clickCount = 0
            mutedBinding = binding
            mutedTick = ticks
            pendingSuppress = null
        }

        // A swing no hurt event answers within the window did no damage: invulnerability, reach or a
        // dead target. Only the server's own word plays the hit sound.
        hpCheckTarget?.let { checked ->
            val health = checked.health.toDouble()
            if (health < hpCheckLowest) {
                hpCheckLowest = health
            }
            if (ticks >= hpCheckTick) {
                val details = TpAuraPlusLog.details(
                    "target" to desc(checked),
                    "before" to fmt(hpCheckValue),
                    "lowest" to fmt(hpCheckLowest),
                    "drop" to fmt(hpCheckValue - hpCheckLowest),
                )
                // The forward aim is only recorded by the spray's PREDICT line; a plain swing would
                // otherwise report a stale error from an earlier engagement.
                val aimed = if (sprays) aimAhead else null
                dlog(
                    "HP-DELTA",
                    if (aimed != null) {
                        details + TpAuraPlusLog.details(
                            "aimErr" to fmt(aimed.distanceTo(checked.position())),
                            "targetThen" to fmt(checked.position()),
                        )
                    } else {
                        details
                    },
                )
                hpCheckTarget = null
            }
        }

        awaitingHit?.let { waiting ->
            if (ticks > hitWaitUntil) {
                awaitingHit = null
                dlog("HIT-UNCONFIRMED", TpAuraPlusLog.details("target" to desc(waiting)))
                TpAuraPlusHud.onResult(false)
            }
        }
        // A return, or a tracked position, that never finishes leaves the player standing where the
        // server put them — out of reach of everything and unable to move. Both get a deadline.
        if (phase == Phase.RETURN && ticks - returnSince > RETURN_PATIENCE) {
            dlog("RETURN-TIMEOUT", TpAuraPlusLog.details("since" to returnSince))
            trip.reset()
            releaseSprayPin()
            phase = Phase.IDLE
            engagement = null
        }

        val pinned = TeleportManager.serverPosition
        if (pinned != null && pinned.distanceTo(player.position()) > DESYNC_GAP) {
            if (desyncSince == 0) {
                desyncSince = ticks
            } else if (ticks - desyncSince > DESYNC_PATIENCE) {
                dlog(
                    "DESYNC-RESYNC",
                    TpAuraPlusLog.details(
                        "client" to fmt(player.position()),
                        "server" to fmt(pinned),
                        "gap" to fmt(pinned.distanceTo(player.position())),
                    ),
                )
                trip.reset()
                releaseSprayPin()
                desyncSince = 0
            }
        } else {
            desyncSince = 0
        }

        if (player.isDeadOrDying || player.isSpectator || player.isPassenger) {
            leave("player-gone")
            return@handler
        }

        when (phase) {
            Phase.IDLE -> engage()
            Phase.ROUTING -> route()
            Phase.READY -> launch()
            Phase.APPROACH -> approach()
            Phase.ATTACK -> attack()
            Phase.RETURN -> {
                if (!trip.isAway) {
                    dlog(
                        "RETURN-DONE",
                        TpAuraPlusLog.details(
                            "client" to fmt(player.position()),
                            "server" to fmt(trip.feet),
                            "clientServerGap" to fmt(player.position().distanceTo(trip.feet)),
                        ),
                    )
                    view.done()
                    phase = Phase.IDLE
                } else {
                    dlog(
                        "RETURNING",
                        TpAuraPlusLog.details("server" to fmt(trip.feet), "client" to fmt(player.position())),
                    )
                }
            }
        }
    }

    @Suppress("unused")
    private val rotationUpdateHandler = handler<RotationUpdateEvent> {
        if (attackWithoutLooking) {
            return@handler
        }
        val engagement = engagement ?: return@handler
        val feet = when (phase) {
            Phase.READY, Phase.APPROACH -> engagement.route?.destination
            Phase.ATTACK -> trip.feet
            else -> null
        } ?: return@handler

        val eyes = player.getEyePositionAt(feet)
        val box = predictedBox(engagement.target)
        val rotation = raytraceBox(eyes, box, range = reach, wallsRange = 0.0)?.rotation
            ?: Rotation.lookingAt(box.center, eyes)

        RotationManager.setRotationTarget(
            rotations.toRotationTarget(rotation, engagement.target),
            priority = Priority.IMPORTANT_FOR_USAGE_2,
            provider = this@ModuleTpAuraPlus
        )
    }

    @Suppress("unused")
    private val manualMouseHandler = handler<MouseButtonEvent> { event ->
        if (event.isPressed && inputFree(event.screen) && event.key == actionBinding(manual.trigger).key) {
            onManualTrigger()
        }
    }

    @Suppress("unused")
    private val manualKeyHandler = handler<KeyboardKeyEvent> { event ->
        if (event.isPressed && !event.isRepeat && inputFree(event.screen) &&
            event.key == actionBinding(manual.trigger).key
        ) {
            onManualTrigger()
        }
    }

    /** Swallows the triggering action itself, for the tick after the press — once, not again. */
    @Suppress("unused")
    private val manualActionHandler = handler<KeybindIsPressedEvent> { event ->
        if (event.keyBinding === mutedBinding && ticks == mutedTick) {
            event.isPressed = false
        }
    }

    private fun engage() {
        // Nothing else clears a pin left behind by a spray whose swing never went out.
        releaseSprayPin()

        if (ticks < restUntil || !trip.isAvailable) {
            return
        }

        // A manual trigger decides when to go; the cooldown is waited out at dispatch instead.
        val ready = if (manual.enabled) {
            0
        } else {
            readyClickTick() ?: run {
                dlog(
                    "ENGAGE-WAIT-COOLDOWN",
                    TpAuraPlusLog.details(
                        "progress" to fmt(cooldownProgress()),
                        "missTime" to mc.missTime,
                    ),
                )
                return
            }
        }

        val origin = trip.feet
        val target = targetSelector.targets().firstOrNull { unreachable.getOrDefault(it.id, 0) <= ticks }
        // The swing budget belongs to the engagement, not to an arrival: a re-route must not top it up.
        engagement = target?.let { Engagement(it).apply { holdLeft = hold } }
        if (target == null) {
            view.clear()
            return
        }

        watchedTarget = target
        engagement?.origin = origin
        if (engagement?.engageTick == -1) {
            engagement?.engageTick = ticks
        }
        engagement?.requestTick = ticks
        view.target = target
        TpAuraPlusHud.reason = null
        val dist = player.position().distanceTo(target.position())
        if (isHittable(origin, target)) {
            if (manual.enabled && !manualTriggered) {
                // Armed inside reach: the swing waits for the trigger.
                dlog(
                    "MANUAL-ARMED",
                    TpAuraPlusLog.details("target" to desc(target), "dist" to fmt(dist)),
                )
                return
            }

            dlog(
                "ENGAGE-IN-REACH",
                TpAuraPlusLog.details("target" to desc(target), "dist" to fmt(dist), "ready" to ready),
            )
            dchat("engage ${desc(target)} in reach, dist=${fmt(dist)}")
            startAttack()
            attack()
            return
        }

        val goals = attackPositions(target, origin)
        if (goals.positions.isEmpty()) {
            dlog(
                "ENGAGE-NO-GOALS",
                TpAuraPlusLog.details(
                    "target" to desc(target),
                    "dist" to fmt(dist),
                    "targetY" to fmt(target.position().y),
                    "targetOnGround" to target.onGround(),
                ),
            )
            dchat("no attack positions for ${desc(target)} (y=${fmt(target.position().y)} onGround=${target.onGround()})")
            fail(target, "no-attack-positions")
        } else {
            dlog(
                "ENGAGE",
                TpAuraPlusLog.details(
                    "target" to desc(target),
                    "dist" to fmt(dist),
                    "goals" to goals.positions.size,
                    "goal0" to goals.positions.first(),
                    "origin" to fmt(origin),
                    "ready" to ready,
                    "serverPin" to (TeleportManager.serverPosition?.let { fmt(it) } ?: "none"),
                    "pinGap" to (TeleportManager.serverPosition?.let { fmt(player.position().distanceTo(it)) } ?: "-"),
                ),
            )
            dchat("engage ${desc(target)} dist=${fmt(dist)} goals=${goals.positions.size}")
            sfx(sfx.pathFinding, SFX_PATH)
            requestRoute(origin, goals)
        }
    }

    private fun requestRoute(origin: Vec3, goals: AttackGoals, reuseScores: Boolean = false) {
        if (!reuseScores) {
            engagement?.plan = goals
            engagement?.planAnchor = engagement?.target?.let { predictedBox(it).center }
        }
        val anchor = engagement?.target?.let { predictedBox(it).center }
        engagement?.goalAnchor = anchor
        engagement?.searchTicks = 0
        engagement?.requestTick = ticks
        dlog(
            "REQUEST-ROUTE",
            TpAuraPlusLog.details(
                "origin" to fmt(origin),
                "goals" to goals.positions.size,
                "goal0" to goals.positions.first(),
                "airGoals" to goals.feet.count { (pos, feet) -> feet.y == pos.y.toDouble() },
                "reuseScores" to reuseScores,
                "anchor" to anchor?.let(::fmt),
                "lead" to engagement?.target?.let { fmt(leadTicks(it)) },
                "flightEMA" to fmt(flightAvg),
            ),
        )
        trip.request(
            origin,
            goals.positions,
            maxLength = range * ROUTE_DETOUR,
            reuseScores = reuseScores,
            goalFeet = goals.feet,
            explorationScale = debug.pathBudget.toDouble(),
        )
        phase = Phase.ROUTING
    }

    private fun route() {
        val engagement = liveEngagement ?: return leave("target-lost-while-routing")
        engagement.searchTicks++

        // A search that runs this long is not slow, it is stuck: the origin drifted away from where the
        // server has the player and no route will ever come out of it.
        if (engagement.searchTicks > SEARCH_PATIENCE) {
            dlog(
                "SEARCH-TIMEOUT",
                TpAuraPlusLog.details(
                    "searchTick" to engagement.searchTicks,
                    "playerDrift" to fmt(playerDrift(engagement)),
                ),
            )
            return leave("search-timeout")
        }

        // The route is planned from the position the pin claims the server has. When the server never
        // took that spot the origin is fiction and no route comes out of it: the client sits dozens of
        // blocks away while the search looks for a way out of a place nobody is standing in. Detected on
        // the *current* belief, after a few ticks of searching, so it cannot fire in a loop.
        val pinned = TeleportManager.serverPosition
        if (pinned != null && engagement.searchTicks > RESYNC_AFTER) {
            val drift = pinned.distanceTo(player.position())
            if (drift > DESYNC_GAP) {
                dlog(
                    "ORIGIN-RESYNC",
                    TpAuraPlusLog.details(
                        "client" to fmt(player.position()),
                        "believed" to fmt(pinned),
                        "drift" to fmt(drift),
                        "searchTick" to engagement.searchTicks,
                    ),
                )
                trip.reset()
                releaseSprayPin()
                engagement.searchTicks = 0
                return replan("origin-resync")
            }
        }

        updateDestination(engagement)
        when (val result = trip.poll()) {
            RouteResult.Searching -> {
                dlog(
                    "ROUTING",
                    TpAuraPlusLog.details(
                        "searchTick" to engagement.searchTicks,
                        "playerDrift" to fmt(playerDrift(engagement)),
                        "targetDist" to fmt(targetDist(engagement)),
                    ),
                )
            }
            RouteResult.AlreadyThere -> {
                dlog("ROUTE-ALREADY-THERE")
                startAttack()
            }
            is RouteResult.Found -> {
                val route = result.value
                engagement.route = route
                engagement.waited = 0
                dlog(
                    "ROUTE-FOUND",
                    TpAuraPlusLog.details(
                        "searchTick" to engagement.searchTicks,
                        "waypoints" to route.points.size,
                        "steps" to route.steps.size,
                        "length" to fmt(route.length),
                        "destination" to fmt(route.destination),
                        "playerDrift" to fmt(playerDrift(engagement)),
                    ),
                )
                dchat("route found (${route.points.size} wp, len=${fmt(route.length)})")
                if (!manual.enabled || manual.showRoute) {
                    view.active(route.path)
                    view.attackPosition = route.destination
                }
                phase = Phase.READY
                launch()
            }
            is RouteResult.Failed -> {
                dlog("ROUTE-FAILED", TpAuraPlusLog.details("failure" to result.failure))
                dchat("route failed: ${result.failure}")
                sfx(sfx.routeFail, SFX_ROUTE_FAIL)
                fail(engagement.target, "route-" + result.failure.name.lowercase())
            }
        }
    }

    /** Sets off once the clicker is about to click when the route arrives. */
    private fun launch() {
        val engagement = liveEngagement ?: return leave("target-lost-while-ready")
        val route = engagement.route ?: return leave("no-route-on-ready")
        val arrival = if (flight == Teleport.FlightMode.PACED) {
            route.batches(steps.packetsPerTick).size
        } else {
            1
        }

        // There used to be a reach check here, before the route was even flown. Against a fast target
        // the destination is meant to be out of reach of the target's *current* position — it is aimed
        // where the target will be — so the gate rejected almost every dispatch before it started.
        // The real check happens on arrival; do not pre-judge the landing spot here.

        if (manual.enabled) {
            if (!manualTriggered) {
                // Armed: keep the route fresh on a schedule, and never set off on its own.
                if (ticks < manualRefreshAt) {
                    return
                }
                manualRefreshAt = ticks + manual.refreshInterval
                return manualRefresh(engagement)
            }
            // The trigger waits at home until the weapon is ready again, then goes.
            if (!attackReady()) {
                dlog("MANUAL-COOLDOWN", TpAuraPlusLog.details("progress" to fmt(cooldownProgress())))
                return
            }
        }

        val aligned = if (manual.enabled) true else attackReadyAt(arrival)
        val drift = player.position().distanceTo(route.start)

        // A tick or two may pass for the click to line up with the arrival, but not more: every tick
        // waited here is a tick the target moves out of reach. The attack phase waits at the target
        // for its click opportunity anyway.
        if (!manual.enabled && !aligned && drift < DRIFT_SOFT && engagement.waited++ < ALIGN_GRACE) {
            dlog(
                "READY-WAIT",
                TpAuraPlusLog.details(
                    "waited" to engagement.waited,
                    "arrival" to arrival,
                    "aligned" to aligned,
                    "playerDrift" to fmt(drift),
                    "targetDist" to fmt(targetDist(engagement)),
                ),
            )
            return
        }

        // The route is a couple of ticks old by now; at speed it is stale in the literal sense. That is
        // a timing problem, not a broken route: re-route from where the player is, without counting it
        // against the failures that end the engagement.
        // Compare where the server has the player, not where the client is: dispatch judges the route
        // against the tracked server position, so judging it against the client position always looks fine.
        val serverDrift = (TeleportManager.serverPosition ?: player.position()).distanceTo(route.start)
        val driftBudget = driftBudget(engagement)
        if (serverDrift > driftBudget) {
            dlog(
                "DRIFT-STALE",
                TpAuraPlusLog.details(
                    "serverDrift" to fmt(serverDrift),
                    "playerDrift" to fmt(drift),
                    "budget" to fmt(driftBudget),
                    "speed" to fmt(playerSpeed()),
                    "searchTicks" to engagement.searchTicks,
                ),
            )
            return if (engagement.staleRetries++ < MAX_STALE_RETRIES) {
                replan("drift-stale")
            } else {
                leave("drift-stale")
            }
        }

        val finish = if (afterAttack == AfterAttack.RETURN) Teleport.Finish.HOLD else Teleport.Finish.LAND
        val dispatched = trip.dispatch(route, finish, flight, driftBudget)
        if (dispatched == null) {
            dlog(
                "DISPATCH-FAILED",
                TpAuraPlusLog.details(
                    "playerDrift" to fmt(drift),
                    "routeStart" to fmt(route.start),
                    "playerPos" to fmt(player.position()),
                    "waited" to engagement.waited,
                ),
            )
            dchat("dispatch failed (drift=${fmt(drift)})")
            return if (engagement.dispatchFails++ < MAX_DISPATCH_RETRIES) {
                replan("dispatch-drift")
            } else {
                leave("dispatch-failed")
            }
        }

        dlog(
            "DISPATCH",
            TpAuraPlusLog.details(
                "finish" to finish,
                "arrival" to arrival,
                "aligned" to aligned,
                "playerDrift" to fmt(drift),
                "waited" to engagement.waited,
                "destination" to fmt(route.destination),
            ),
        )
        dchat("dispatched, arrival in ${arrival}t")
        sfx(sfx.teleport, SFX_TELEPORT)
        phase = Phase.APPROACH
    }

    private fun approach() {
        when (val state = trip.run?.state) {
            null -> leave("no-run-on-approach")
            Teleport.State.ACTIVE -> dlog("APPROACH-FLYING")
            Teleport.State.DONE -> {
                val feet = trip.feet
                val expected = trip.run?.route?.destination
                dlog("ARRIVED", TpAuraPlusLog.details("feet" to fmt(feet), "expected" to expected?.let(::fmt)))
                if (expected != null && feet.distanceToSqr(expected) > ARRIVE_ACCEPT_SQ) {
                    // The server moved the player back during the flight and the setback raced past us.
                    dlog("ARRIVED-WRONG", TpAuraPlusLog.details("feet" to fmt(feet), "expected" to fmt(expected)))
                    dchat("server moved us back mid-flight")
                    rejected()
                    return
                }
                startAttack()
                attack()
            }
            Teleport.State.REJECTED -> {
                dlog("APPROACH-REJECTED")
                dchat("server rejected the teleport")
                rejected()
            }
            Teleport.State.INVALID, Teleport.State.CANCELLED -> {
                dlog("APPROACH-BROKEN", TpAuraPlusLog.details("state" to state))
                view.invalid()
                trip.showReturn()
                phase = Phase.RETURN
            }
        }
    }

    private fun startAttack() {
        val engagement = engagement
        engagement?.run {
            if (requestTick > 0) {
                // From the request, not the engagement: the landing spot was predicted from the request,
                // so the ticks it takes from there to the swing are what the target travels unaccounted.
                val measured = (ticks - requestTick).toDouble()
                dlog(
                    "LATENCY",
                    TpAuraPlusLog.details(
                        "measured" to fmt(measured),
                        "before" to fmt(flightAvg),
                        "after" to fmt(flightAvg * 0.6 + measured * 0.4),
                        "engageTick" to engageTick,
                        "requestTick" to requestTick,
                        "ticks" to ticks,
                    ),
                )
                flightAvg = flightAvg * 0.6 + measured * 0.4
            }
            attacked = false
            // Retries are NOT reset here: this runs on every arrival, and zeroing the budget there made
            // MAX_RETRIES unreachable — the engagement replanned in circles instead of giving up.
            idleTicks = 0
            blockedTicks = 0
            goalAnchor = predictedBox(target).center
        }
        val feet = trip.feet
        val target = engagement?.target
        dlog(
            "START-ATTACK",
            TpAuraPlusLog.details(
                "feet" to fmt(feet),
                "target" to target?.let(::desc),
                "dist" to target?.let { fmt(targetDist(engagement)) },
                "side" to target?.let { sideOf(feet, it) },
                "cooldown" to fmt(cooldownProgress()),
            ),
        )
        phase = Phase.ATTACK
    }

    private fun attack() {
        if (trip.run?.state == Teleport.State.REJECTED) {
            dlog("ATTACK-REJECTED")
            return rejected()
        }

        val engagement = liveEngagement ?: return leave("attack-over")
        val target = engagement.target
        // Where the server has us. The spray moves without a trip, so trip.feet would still be the
        // route destination and every swing from a probe would be written off as out of reach.
        val feet = TeleportManager.serverPosition ?: trip.feet
        if (!sprays) {
            val eyes = player.getEyePositionAt(feet)
            dlog(
                "PLAIN-AIM",
                TpAuraPlusLog.details(
                    "feet" to fmt(feet),
                    "target" to fmt(target.position()),
                    "dist" to fmt(target.boundingBox.distanceToSqr(eyes).let { sqrt(it) }),
                    "predDist" to fmt(predictedBox(target).distanceToSqr(eyes).let { sqrt(it) }),
                    "reach" to fmt(reachTo(feet, target)),
                    "speed" to fmt(trackedVelocity(target)?.length() ?: 0.0),
                    "lead" to fmt(leadTicks(target)),
                    "ping" to player.ping,
                    "pin" to (TeleportManager.serverPosition?.let { "yes" } ?: "no"),
                    "ticks" to (ticks - engagement.requestTick),
                ),
            )
        }
        // `Target → HurtTime` decides when a swing may go out: a target still inside its hurt time
        // swallows the attack — the weapon's cooldown is spent and no damage lands. The patience budget
        // still applies, so a target that stays unhittable ends the engagement instead of freezing it.
        if (target.hurtTime > targetSelector.hurtTimeLimit) {
            if (!hurtWaiting) {
                hurtWaiting = true
                dlog(
                    "HURT-WAIT",
                    TpAuraPlusLog.details(
                        "hurtTime" to target.hurtTime,
                        "limit" to targetSelector.hurtTimeLimit,
                        "holdLeft" to engagement.holdLeft,
                    ),
                )
            }
            if (++engagement.idleTicks > ATTACK_PATIENCE) {
                dlog("ATTACK-PATIENCE", TpAuraPlusLog.details("waiting" to "hurt-time"))
                return leave("attack-patience")
            }
            return
        }
        hurtWaiting = false

        if (!isInReach(feet, target)) {
            val sprayable = sprays && attackReady()
            dlog(
                "ATTACK-OUT-OF-REACH",
                TpAuraPlusLog.details(
                    "retries" to engagement.retries,
                    "dist" to fmt(targetDist(engagement)),
                    "spraying" to sprayable,
                    "repins" to "${engagement.repins}/${debug.holdRepins}",
                ),
            )
            if (sprayable || engagement.waitingSwing) {
                // The spray flies to the target on its own, and a move already in flight still owes
                // its swing on the next tick: both fall through to the swing path below.
            } else if (engagement.repins >= debug.holdRepins) {
                // Followed as often as this hold allows: give up instead of planning a route the
                // target will have left by the time it lands.
                return leave("hold-repins-exhausted")
            } else if (attackReady()) {
                // One more teleport towards where the target went; the swing budget is untouched, so
                // the sequence still ends after `Hold` swings rather than restarting.
                engagement.repins++
                val mode = if (attackWithoutSwing) SwingMode.HIDE_SERVER else SwingMode.DO_NOT_HIDE
                deliver(target, mode)
                return
            } else {
                // The weapon is still on cooldown: stay where the last arrival put us and wait for it,
                // the same way the launch gate waits at home. A cooldown that never ends is a reason to
                // go home rather than to stand here, so the patience budget applies.
                if (++engagement.idleTicks > ATTACK_PATIENCE) {
                    dlog(
                        "ATTACK-PATIENCE",
                        TpAuraPlusLog.details(
                            "waiting" to "cooldown",
                            "progress" to fmt(cooldownProgress()),
                        ),
                    )
                    return leave("attack-patience")
                }
                return
            }
        }

        view.engaged = true
        val swingMode = if (attackWithoutSwing) SwingMode.HIDE_SERVER else SwingMode.DO_NOT_HIDE

        if (attackWithoutLooking) {
            // The protocol takes the attack packet without a look or a click: it goes out as soon as
            // the weapon is ready again, the cooldown pacing being the only limit that matters.
            if (attackReady()) {
                if (deliver(target, swingMode)) {
                    engagement.attacked = true
                    engagement.idleTicks = 0
                    dlog(
                        "HIT",
                        TpAuraPlusLog.details(
                            "target" to desc(target),
                            "dist" to fmt(targetDist(engagement)),
                            "holdLeft" to engagement.holdLeft,
                        ),
                    )
                    if (afterAttack == AfterAttack.RETURN && --engagement.holdLeft <= 0) {
                        dlog("HOLD-EXPIRED")
                        leave("hold-expired")
                    }
                }
            } else {
                dlog("ATTACK-COOLDOWN", TpAuraPlusLog.details("progress" to fmt(cooldownProgress())))
                if (++engagement.idleTicks > ATTACK_PATIENCE) {
                    dlog("ATTACK-PATIENCE")
                    leave("attack-patience")
                }
            }
            return
        }

        val clickTick = clicker.isClickTick
        val executed = clickTick && isFacing(player.getEyePositionAt(feet), target) &&
            !player.cannotAttackWithItem(player.mainHandItem, 0)

        if (executed) {
            clicker.click {
                if (!deliver(target, swingMode)) {
                    return@click false
                }
                engagement.attacked = true
                dlog(
                    "HIT",
                    TpAuraPlusLog.details(
                        "target" to desc(target),
                        "dist" to fmt(targetDist(engagement)),
                        "holdLeft" to engagement.holdLeft,
                    ),
                )
                true
            }

            // The hold counts swings actually taken, so a swing the gate turned away does not cost
            // the engagement its one attempt.
            engagement.idleTicks = 0
            engagement.blockedTicks = 0
            if (afterAttack == AfterAttack.RETURN && --engagement.holdLeft <= 0) {
                dlog("HOLD-EXPIRED")
                leave("hold-expired")
            }
        } else if (clickTick) {
            dlog(
                "CLICK-BLOCKED",
                TpAuraPlusLog.details(
                    "facing" to isFacing(player.getEyePositionAt(feet), target),
                    "cooldown" to player.cannotAttackWithItem(player.mainHandItem, 0),
                ),
            )
            engagement.idleTicks = 0
            if (++engagement.blockedTicks > MAX_BLOCKED) {
                dlog("FACING-STUCK")
                leave("facing-stuck")
            }
        } else {
            dlog(
                "ATTACK-IDLE",
                TpAuraPlusLog.details(
                    "dist" to fmt(targetDist(engagement)),
                    "progress" to fmt(cooldownProgress()),
                ),
            )
            if (++engagement.idleTicks > ATTACK_PATIENCE) {
                dlog("ATTACK-PATIENCE")
                leave("attack-patience")
            }
        }
    }

    /**
     * While the route is still being searched, follows the target: once its predicted position has drifted
     * from [Engagement.goalAnchor], the goals are computed again and the running search is pointed at them
     * without losing its progress.
     */
    private fun updateDestination(engagement: Engagement) {
        if (!prediction.enabled || !prediction.liveRetarget) {
            return
        }

        // A retarget restarts the search and resets the latency measurement, so doing it every tick keeps
        // the route from ever settling. One attempt per few ticks is enough to follow a moving target.
        if (ticks - engagement.retargetTick < RETARGET_COOLDOWN) {
            return
        }

        val anchor = engagement.goalAnchor ?: return
        val predicted = predictedBox(engagement.target).center
        val drift = predicted.distanceTo(anchor)

        // Scale the threshold with the target's speed: a fixed distance retargets a fast target on
        // every tick and the search only chases its own tail.
        val speed = trackedVelocity(engagement.target)?.length() ?: 0.0
        val threshold = max(RETARGET_DRIFT, speed * RETARGET_TICKS)
        if (drift <= threshold) {
            return
        }

        engagement.retargetTick = ticks
        val goals = attackPositions(engagement.target, trip.feet)
        val retargeted = goals.positions.isNotEmpty() && trip.retarget(goals.positions, goals.feet)
        if (retargeted) {
            engagement.plan = goals
            engagement.planAnchor = predicted
        }
        dlog(
            "RETARGET",
            TpAuraPlusLog.details(
                "predDrift" to fmt(drift),
                "goals" to goals.positions.size,
                "retargeted" to retargeted,
            ),
        )
        if (retargeted) {
            engagement.goalAnchor = predicted
        }
    }

    /** Routes again from where the server has the player, because the target moved. */
    private fun replan(reason: String) {
        val target = engagement?.target ?: return leave("target-lost-on-replan")
        val origin = trip.feet

        // The goals were picked once, around the target's predicted position; computing them again on
        // every replan walks the destination forward each time and the route never converges.
        val predicted = predictedBox(target).center
        val stale = engagement?.planAnchor?.let { it.distanceTo(predicted) > GOAL_DRIFT } ?: true
        val goals = if (stale) attackPositions(target, origin) else engagement?.plan!!
        engagement?.plan = goals
        engagement?.planAnchor = predicted
        if (goals.positions.isEmpty()) {
            dlog("REPLAN-NO-GOALS", TpAuraPlusLog.details("reason" to reason, "target" to desc(target)))
            return leave("no-goals-on-replan")
        }

        dlog(
            "REPLAN",
            TpAuraPlusLog.details("reason" to reason, "origin" to fmt(origin), "goals" to goals.positions.size),
        )
        dchat("replan: $reason")
        view.engaged = false
        requestRoute(origin, goals, reuseScores = true)
    }

    /** Ends the engagement and brings the server back to the client. */
    private fun leave(reason: String) {
        if (phase == Phase.RETURN) {
            return
        }

        TpAuraPlusHud.reason = reason

        if (phase == Phase.ATTACK && engagement?.attacked != true) {
            sfx(sfx.hitFail, SFX_HIT)
        }

        dlog(
            "LEAVE",
            TpAuraPlusLog.details(
                "reason" to reason,
                "phase" to phase,
                "client" to fmt(player.position()),
                "server" to fmt(trip.feet),
                "clientServerGap" to fmt(player.position().distanceTo(trip.feet)),
                "serverPin" to (TeleportManager.serverPosition?.let { fmt(it) } ?: "none"),
                "pinGap" to (TeleportManager.serverPosition?.let { fmt(player.position().distanceTo(it)) } ?: "-"),
                "originGap" to engagement?.origin?.let { fmt(player.position().distanceTo(it)) },
            ),
        )
        phase = if (trip.comeBack()) Phase.RETURN else Phase.IDLE
        if (phase == Phase.RETURN) {
            returnSince = ticks
        }
        if (phase != Phase.RETURN) {
            // Only when nothing comes back: a return run needs the position it is standing on.
            releaseSprayPin()
        }
        engagement = null
        // The hurt event of a swing still in flight may arrive after the engagement ended; the wait
        // is kept on purpose, so the sound is not lost on the way home.
        manualTriggered = false
        manualRefreshAt = 0
        hurtWaiting = false
    }

    private fun fail(target: LivingEntity, reason: String) {
        dlog(
            "FAIL",
            TpAuraPlusLog.details(
                "reason" to reason,
                "target" to desc(target),
                "targetY" to fmt(target.position().y),
                "targetOnGround" to target.onGround(),
            ),
        )
        TpAuraPlusHud.reason = reason
        unreachable.put(target.id, ticks + UNREACHABLE_TICKS)
        view.invalid()
        view.target = null
        engagement = null
        restUntil = ticks + FAIL_REST_TICKS
        phase = Phase.IDLE
    }

    /** The server set the player back, so it gets some rest before trying again. */
    private fun rejected() {
        TpAuraPlusHud.reason = "setback"
        sfx(sfx.serverSetback, SFX_SETBACK)
        dlog("REJECTED", TpAuraPlusLog.details("client" to fmt(player.position()), "server" to fmt(trip.feet)))
        reset()
        view.invalid()
        restUntil = ticks + SETBACK_REST_TICKS
    }

    private fun reset() {
        releaseSprayPin()
        trip.reset()
        phase = Phase.IDLE
        engagement = null
        awaitingHit = null
        manualTriggered = false
        manualRefreshAt = 0
        pendingSuppress = null
        hurtWaiting = false
        view.clear()
    }

    /** Keeps the armed route fresh without setting off: a new search from where the player stands. */
    private fun manualRefresh(engagement: Engagement) {
        val origin = trip.feet
        val goals = attackPositions(engagement.target, origin)
        if (goals.positions.isEmpty()) {
            dlog("MANUAL-REFRESH-NO-GOALS", TpAuraPlusLog.details("target" to desc(engagement.target)))
            return
        }

        dlog(
            "MANUAL-REFRESH",
            TpAuraPlusLog.details("goals" to goals.positions.size, "origin" to fmt(origin)),
        )
        requestRoute(origin, goals, reuseScores = true)
    }

    /** The player acted: arm the engagement, swallow the action itself when asked to. */
    private fun onManualTrigger() {
        if (!manual.enabled || manualTriggered) {
            return
        }
        if (phase == Phase.ATTACK || phase == Phase.APPROACH || phase == Phase.RETURN || ticks < restUntil) {
            return
        }

        val target = engagement?.target
            ?: targetSelector.targets().firstOrNull { unreachable.getOrDefault(it.id, 0) <= ticks }
            ?: return

        manualTriggered = true
        manualRefreshAt = 0
        dlog(
            "MANUAL-TRIGGER",
            TpAuraPlusLog.details("target" to desc(target), "phase" to phase),
        )
        if (manual.preventExtraAction) {
            pendingSuppress = actionBinding(manual.trigger)
        }
    }

    private fun actionBinding(trigger: Trigger) = when (trigger) {
        Trigger.LEFT_CLICK -> mc.options.keyAttack
        Trigger.RIGHT_CLICK -> mc.options.keyUse
        Trigger.JUMP -> mc.options.keyJump
        Trigger.SNEAK -> mc.options.keyShift
    }

    /** Whether no vanilla or client screen sits in the way of the player's input. */
    private fun inputFree(screen: Screen?): Boolean =
        screen == null && mc.gui.overlay() == null && !ScreenManager.isClientScreen(mc.gui.screen())

    /**
     * Where the target will be [lead] ticks from now — now on the server, not now on the screen. The
     * picture we extrapolate from is a half ping stale and the swing lands a half ping late, so every
     * lead is topped up with the round trip `ping / 50`. The measured flight in [leadTicks] cannot
     * cover that: request and swing are both local ticks and never see the wire.
     */
    private fun predictedBox(target: LivingEntity, lead: Double): AABB {
        if (!prediction.enabled) {
            return target.boundingBox
        }

        sampleTarget(target)
        val velocity = trackedVelocity(target) ?: return target.boundingBox
        if (velocity.lengthSqr() < MIN_PREDICT_VELOCITY_SQ) {
            return target.boundingBox
        }

        // Local flight plus the round trip on the wire; the KDoc above says why the ping lives here.
        val travel = lead + player.ping / 50.0

        // A circling target curves away from the straight lead: rotate the velocity by half the turn it
        // makes while we travel, so the aim lands on the arc instead of its tangent. A sharp corner is
        // not an arc: there the newest direction is all the truth there is, and the turn must not shrink
        // the lead — that shortened it to a tick or two right after every corner of a square path.
        val omega = trackedTurn(target)
        val offset = if (abs(omega) > SHARP_TURN) {
            velocity.scale(travel)
        } else {
            val turn = (omega * travel * TURN_DAMP / 2.0).coerceIn(-MAX_TURN, MAX_TURN)
            rotateY(velocity, turn).scale(travel)
        }
        return target.boundingBox.move(offset)
    }

    private fun predictedBox(target: LivingEntity): AABB = predictedBox(target, leadTicks(target))

    /**
     * Ticks of lead when aiming: the rolling average of the ticks a route actually took from request
     * to swing, capped by the configured maximum. The cap is a safety rail, not a target — on a fast
     * target with a short flight the measured value settles near three ticks. A fixed offset for the
     * client's interpolation was tried and dropped: the visible target can be anywhere from five to
     * twenty blocks behind a fast flying player, so one constant cannot compensate for it.
     */
    private fun leadTicks(@Suppress("UNUSED_PARAMETER") target: LivingEntity): Double {
        // The lead is the flight actually measured from request to swing — the ticks the target travels
        // unaccounted — with the setting as its ceiling, so a bad measurement cannot overshoot. A fixed
        // value was always wrong at one end: the flight moves between four and six ticks on its own.
        // Measured flight only. A fixed interpolation offset was tried and hurt the verified spray
        // tuning: the client can be anywhere from five to twenty blocks behind a fast target, so one
        // constant cannot compensate for it, and shifting every goal broke the spray that already worked.
        return flightAvg.coerceIn(MIN_LEAD, prediction.ticks.toDouble())
    }

    // --- Target tracking ---
    //
    // The delta movement of a remote player is not synced — its positions come from move packets —
    // so the velocity the prediction needs is measured from the positions ourselves.

    /** Keeps sampling the last watched target while it is only filtered out, so a hurt time leaves no gap. */
    private fun keepSampling() {
        val watched = watchedTarget ?: return
        if (!watched.isAlive || watched.isRemoved) {
            watchedTarget = null
            return
        }
        sampleTarget(watched)
    }

    private fun sampleTarget(target: LivingEntity) {
        val id = target.id
        val samples = targetSamples
        if (samplesId != id) {
            samples.clear()
            samplesId = id
        }
        if (samples.isEmpty() || samples.last().first != ticks) {
            samples.addLast(ticks to target.position())
            while (samples.size > MAX_SAMPLES) {
                samples.removeFirst()
            }
        }
    }

    /**
     * The trailing run of samples taken on consecutive ticks. Sampling pauses while the target is filtered
     * out (its hurt time), and a buffer spanning that gap turns the arc it walked into a chord, which
     * understates the speed.
     */
    private fun contiguousRun(): List<Pair<Int, Vec3>>? {
        val samples = targetSamples
        if (samples.size < 2) {
            return null
        }

        var start = samples.size - 1
        while (start > 0 && samples[start].first - samples[start - 1].first == 1) {
            start--
        }
        return samples.subList(start, samples.size)
    }

    /** Blocks per tick over the last [SPEED_WINDOW] ticks, or null when the target is too new. */
    private fun trackedVelocity(target: LivingEntity): Vec3? {
        val samples = contiguousRun()?.takeIf { samplesId == target.id } ?: return null

        // Right after a corner the window still holds the old direction; the newest step is the only one
        // that points where the target is actually going.
        if (samples.size >= 3 && isSharpCorner(samples)) {
            val last = samples[samples.size - 1]
            val previous = samples[samples.size - 2]
            val elapsed = (last.first - previous.first).toDouble()
            return if (elapsed < 1.0) null else last.second.subtract(previous.second).scale(1.0 / elapsed)
        }
        val (endTick, endPos) = samples.last()
        val (startTick, startPos) = samples.firstOrNull { endTick - it.first >= SPEED_WINDOW } ?: samples.first()
        val elapsed = (endTick - startTick).toDouble()
        if (elapsed < 1.0) {
            return null
        }
        return endPos.subtract(startPos).scale(1.0 / elapsed)
    }

    /** Radians per tick the target turns around its up axis, from the same samples as [trackedVelocity]. */
    /** Whether the last two steps of [samples] disagree by more than a sharp turn. */
    private fun isSharpCorner(samples: List<Pair<Int, Vec3>>): Boolean {
        val last = samples[samples.size - 1].second.subtract(samples[samples.size - 2].second)
        val before = samples[samples.size - 2].second.subtract(samples[samples.size - 3].second)
        if (last.lengthSqr() < MIN_PREDICT_VELOCITY_SQ || before.lengthSqr() < MIN_PREDICT_VELOCITY_SQ) {
            return false
        }
        val angle = atan2(
            before.x * last.z - before.z * last.x,
            before.x * last.x + before.z * last.z,
        )
        return abs(angle) > SHARP_TURN
    }

    private fun trackedTurn(target: LivingEntity): Double {
        // Three consecutive samples are enough. Waiting for two windows of SPEED_WINDOW ticks meant the
        // estimate came back as zero whenever the target entity id changed, which it does constantly —
        // and a zero turn means a circling target is extrapolated as a straight line.
        val run = contiguousRun()?.takeIf { it.size >= 3 && samplesId == target.id } ?: return 0.0
        val last = run[run.size - 1]
        val mid = run[run.size - 2]
        val first = run[run.size - 3]

        val newer = last.second.subtract(mid.second)
        val older = mid.second.subtract(first.second)
        if (newer.lengthSqr() < MIN_PREDICT_VELOCITY_SQ || older.lengthSqr() < MIN_PREDICT_VELOCITY_SQ) {
            return 0.0
        }

        val angle = atan2(
            older.x * newer.z - older.z * newer.x,
            older.x * newer.x + older.z * newer.z,
        )
        return angle / (last.first - first.first).coerceAtLeast(1)
    }

    private fun rotateY(v: Vec3, angle: Double): Vec3 {
        if (abs(angle) < 1.0E-4) {
            return v
        }
        val cos = cos(angle)
        val sin = sin(angle)
        return Vec3(v.x * cos - v.z * sin, v.y, v.x * sin + v.z * cos)
    }

    private fun isInReach(feet: Vec3, target: LivingEntity) =
        target.boundingBox.distanceToSqr(player.getEyePositionAt(feet)) <= Mth.square(reachTo(feet, target))

    /**
     * Whether the target can be hit from here without moving: in reach and not hidden. A wall a few
     * blocks away is not a reason to skip the route — it may be climbed over.
     */
    private fun isHittable(feet: Vec3, target: LivingEntity): Boolean =
        isInReach(feet, target) || (throughWall(player.getEyePositionAt(feet), target.boundingBox) &&
            target.boundingBox.distanceToSqr(player.getEyePositionAt(feet)) <= Mth.square(BLIND_REACH))

    /** Whether the rotation the server has is on where the target is heading — loosely, to swing. */
    private fun isFacing(eyes: Vec3, target: LivingEntity): Boolean {
        val box = predictedBox(target)
        if (box.contains(eyes)) {
            return true
        }

        // The exact ray rarely lands on the box of a jinking target and the smoothed rotation trails
        // it on purpose: a swing only needs the look to be roughly onto the target and unhidden.
        val toCenter = box.center.subtract(eyes)
        if (toCenter.lengthSqr() < 1.0E-6) {
            return true
        }
        return toCenter.normalize().dot(RotationManager.serverRotation.directionVector) >= FACING_DOT &&
            (hasClearSight(eyes, box.center) || throughWall(eyes, box))
    }

    /** Whether the target is close enough to be hit through blocks. */
    private fun throughWall(eyes: Vec3, box: AABB): Boolean =
        allowThroughWall && box.distanceToSqr(eyes) <= Mth.square(throughWallRange)

    /** Like [hasLineOfSight], except leaves and glass do not hide the target. */
    private fun hasClearSight(eyes: Vec3, end: Vec3): Boolean {
        val direction = end.subtract(eyes)
        val limit = direction.lengthSqr()
        val step = direction.normalize().scale(0.05)
        var start = eyes

        repeat(MAX_SIGHT_SKIPS) {
            val hit = world.clip(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            if (hit.type == HitResult.Type.MISS) {
                return true
            }
            if (world.getBlockState(hit.blockPos).canOcclude()) {
                return false
            }

            start = hit.location.add(step)
            if (start.distanceToSqr(eyes) >= limit) {
                return true
            }
        }
        return false
    }

    /**
     * Orders the spots into a nearest-first tour. There is no distance cut-off: the servers this module
     * is built for accept long hops, and dropping the far spots here used to leave an engagement with
     * nothing to teleport to while it spun in ATTACK for ever.
     */
    private fun legalHops(spots: List<Pair<BlockPos, Vec3>>): List<Pair<BlockPos, Vec3>> {
        var cursor = TeleportManager.serverPosition ?: player.position()
        val remaining = spots.toMutableList()
        val ordered = ArrayList<Pair<BlockPos, Vec3>>(spots.size)

        while (remaining.isNotEmpty()) {
            val next = remaining.minByOrNull { (_, feet) -> feet.distanceToSqr(cursor) } ?: break
            ordered += next
            remaining.remove(next)
            cursor = next.second
        }
        return ordered
    }

    /**
     * Hands the position back to the client. The spray pins it to hold a probe spot, and a pin that
     * outlives the swing leaves the server standing where the player is not.
     */
    private fun releaseSprayPin() {
        val pinned = TeleportManager.holds(this) || TeleportManager.serverPosition != null
        if (!pinned && trip.run == null) {
            return
        }

        // A run that never finished owns the position and would rewrite the client's movement for ever:
        // drop it first, then hand the position back so the server follows the player again.
        trip.reset()
        TeleportManager.forceRelease(this)

        sendPacketSilently(ServerboundMovePlayerPacket.Pos(player.position(), player.onGround(), false))
        TeleportManager.releasePosition()
    }

    /** The single spot closest to where the target is, or null when the spray found none. */
    private fun spotFor(target: LivingEntity): Pair<BlockPos, Vec3>? {
        // The aggressive spray was tuned against the drawn position, and that behaviour is kept for it:
        // the chain below covers the forward error on its own. Plain strikes have no such chain, so they
        // must pick the spot nearest where the target will actually be instead.
        return if (sprays) {
            legalHops(probeSpots(target)).minByOrNull { (_, feet) ->
                target.boundingBox.distanceToSqr(player.getEyePositionAt(feet))
            }
        } else {
            val aim = predictedBox(target, leadTicks(target)).center
            legalHops(probeSpots(target)).minByOrNull { (_, feet) -> feet.distanceToSqr(aim) }
        }
    }

    private fun terrainOnGround(pos: BlockPos): Boolean = Terrain.live().feet(pos) != null

    /** Landing spots around the predicted box of [target], hovering ones preferred when it flies. */
    private fun attackPositions(target: LivingEntity, from: Vec3): AttackGoals {
        val box = predictedBox(target)
        val terrain = Terrain.live()
        val facing = Vec3.directionFromRotation(0f, target.yBodyRot)
        val center = box.center

        // Against a target in the air, hovering next to it beats standing far below; against one on
        // the ground, a foothold stays first and hovering is only the fallback.
        val hoverFirst = airTargets && !target.onGround()
        // Land close, not at the edge of the budget: the target keeps moving for the ticks the route
        // takes, and a spot at five blocks is out of reach by the time the player stands on it. The
        // wider sighted budget is for judging the swing, not for choosing where to land.
        val candidates = terrain.reachFeetPositions(
            box,
            min(reach, BLIND_REACH) - TARGET_MOVEMENT_MARGIN,
            from,
            allowAir = airTargets,
            wallsRange = if (allowThroughWall) throughWallRange.toDouble() else 0.0,
        ) { standable, feet ->
            val hoverTier = if (hoverFirst && !standable) 0 else 1
            val sideTier = sideTier(feet, center, facing)
            // Close to the target, not to the player: the far edge of the reach sphere leaves no
            // room for the target to move before the swing, and every step of error ends the attack.
            hoverTier * TIER_HOVER + sideTier * TIER_SIDE + feet.distanceToSqr(center)
        }

        val positions = candidates.map { (position, _) -> position }
        return AttackGoals(positions, candidates.associate { (position, feet) -> position to feet })
    }

    /**
     * Delivers this tick's action. The two-tick move sends only a position packet and returns; the next
     * tick sends the attack. With the aggressive spray the same tick then sends a chain of positions
     * and attacks, covering several possible future positions of the target. Returns whether a swing
     * actually went out, which is what the caller charges to the cooldown and hit confirmation.
     */
    private fun deliver(target: LivingEntity, swingMode: SwingMode): Boolean {
        // The two-tick attack: a position on one tick, a swing on the next. The server has to stand on
        // the spot before judging the attack; a move and an attack in the same tick leave it judging
        // from the old position, which cost most of the hits.
        run {
            val engagement = engagement
            if (!sprays && engagement?.waitingSwing != true && isInReach(trip.feet, target)) {
                // Already standing within reach: swing right here. No position packet, no delay, and no
                // reason to move — this is the walking, in-reach case that always hit.
                engagement?.waitingSwing = false
            } else if (engagement != null && !engagement.waitingSwing) {
                // Move now, swing next tick: the position has to be on the server before the attack.
                val spot = spotFor(target) ?: run {
                    dlog("NO-SPOT", TpAuraPlusLog.details("dist" to fmt(targetDist(engagement))))
                    if (engagement.retries++ >= MAX_RETRIES) {
                        leave("no-spot")
                    }
                    return false
                }
                val onGround = terrainOnGround(spot.first)
                // Pin as well, so the position the gate reads next tick is the one we sent.
                TeleportManager.notePosition(spot.second, onGround, TELEPORT_PIN_TICKS)
                sendPacketSilently(ServerboundMovePlayerPacket.Pos(spot.second, onGround, false))
                engagement.waitingSwing = true
                engagement.movedTick = ticks
                dlog("TWO-TICK", TpAuraPlusLog.details("feet" to fmt(spot.second)))
                return false
            }
            if (engagement != null && ticks - engagement.movedTick < SWING_DELAY) {
                return false
            }
            engagement?.waitingSwing = false
        }

        awaitHit(target)

        hpCheckTarget = target
        hpCheckValue = target.health.toDouble()
        hpCheckLowest = hpCheckValue
        // The swing lands about a lead later and the damage still has to travel the round trip back:
        // without the ping term the far-aimed swings of the frp runs were reported as misses before
        // their damage arrived (drop=0 on hits the server had already confirmed).
        hpCheckTick = ticks + (leadTicks(target) + player.ping / 50.0).toInt() + 2

        // Reset per swing: the strip keeps the last spray visible, a plain swing must not inherit it.
        TpAuraPlusHud.sprayMax = debug.maxProbes
        TpAuraPlusHud.spraySpots = 0

        if (!sprays) {
            attackEntity(target, swingMode, keepSprint = true)
            clicker.itemCooldown?.newCooldown()
            return true
        }

        val spots = probeSpots(target)
        if (spots.isEmpty()) {
            // No spot means there is nowhere to stand within reach: a swing from here would only be
            // dropped by the server. Count it and let the next tick plan again instead.
            val engagement = engagement
            dlog(
                "NO-SPOT",
                TpAuraPlusLog.details("dist" to (engagement?.let { fmt(targetDist(it)) } ?: "-")),
            )
            if (engagement == null || engagement.retries++ >= MAX_RETRIES) {
                leave("no-spot")
            }
            return false
        }

        var sent = 0
        val terrain = Terrain.live()
        val hops = legalHops(spots)
        val toSend = hops
        // The aim is only as good as where the target turns out to be once the flight is over, so the
        // comparison is deferred: this line records what we aimed at, HP-DELTA reports the landing.
        spots.lastOrNull()?.let { (_, aim) ->
            val actual = target.position()
            aimAhead = predictedBox(target, leadTicks(target)).center
            dlog(
                "PREDICT",
                TpAuraPlusLog.details(
                    "aim" to fmt(aim),
                    "targetNow" to fmt(actual),
                    "offset" to fmt(aim.distanceTo(actual)),
                    "vel" to (trackedVelocity(target)?.let { fmt(it) } ?: "none"),
                    "omega" to fmt(trackedTurn(target)),
                    "speed" to fmt(trackedVelocity(target)?.length() ?: 0.0),
                    "flight" to (engagement?.let { ticks - it.requestTick } ?: -1),
                    "lead" to (engagement?.target?.let { fmt(leadTicks(it)) } ?: "-"),
                ),
            )
        }

        for ((pos, feet) in toSend) {
            // The flag has to match the spot: the server rejects a move that claims to be airborne
            // above ground of its own accord, and the attack after it is judged from the old position.
            val onGround = terrain.feet(pos) != null
            // Pin first: the vanilla client sends its own position every tick, and one of those packets
            // arriving between the teleport and the swing puts the server back where the player really
            // stands — far from the target — so the attack is judged out of reach.
            TeleportManager.notePosition(feet, onGround, TELEPORT_PIN_TICKS)
            sendPacketSilently(ServerboundMovePlayerPacket.Pos(feet, onGround, false))
            if (attackEntity(target, swingMode, keepSprint = true)) {
                sent++
            }
        }
        if (sent == 0) {
            dlog("SPRAY-BLOCKED", TpAuraPlusLog.details("spots" to spots.size))
        }

        clicker.itemCooldown?.newCooldown()
        spots.lastOrNull()?.let { (_, feet) ->
            TeleportManager.notePosition(feet, false)

            // Nothing owns the tracked position in the in-reach engagement: no run was started, so no
            // return will ever clean it up and the server would stand on the last probe spot while the
            // player walks away. Put the server back on the client and stop rewriting its movement.
            TpAuraPlusHud.spraySpots = spots.size
            TpAuraPlusHud.sprayMax = debug.maxProbes
            val owned = TeleportManager.holds(this)
            if (!owned) {
                sendPacketSilently(
                    ServerboundMovePlayerPacket.Pos(player.position(), player.onGround(), false),
                )
                TeleportManager.releasePosition()
            }

            // Only the unowned case is worth a line: it is the one that has to hand the position back.
            if (!owned) {
                dlog(
                    "SPRAY-PIN",
                    TpAuraPlusLog.details(
                        "pin" to fmt(feet),
                        "client" to fmt(player.position()),
                        "gap" to fmt(player.position().distanceTo(feet)),
                        "spots" to spots.size,
                        "max" to debug.maxProbes,
                    ),
                )
            }
        }
        return true
    }

    /**
     * Spots along the path of the target, for the spray: the packets are processed a tick or two
     * after the sample, so the probes cover where the target will be by then — a chain along its
     * motion, spaced so one reach still bridges the gaps, each with an arm's height above it.
     */
    private fun probeSpots(target: LivingEntity): List<Pair<BlockPos, Vec3>> {
        val terrain = Terrain.live()
        val speed = trackedVelocity(target)?.length() ?: 0.0

        // How long until the packets are processed: the base window. The round trip is not added here
        // any more — predictedBox tops every lead up with the ping already, and counting it twice
        // pushed the chain tail past the range the prediction can still track.
        val window = PROBE_TICKS
        val maxProbes = debug.maxProbes
        // Neighbours stay within two blind reaches of each other, so the spot between them still lands
        // even where the sight line is blocked — the conservative budget, not the sighted one.
        val spacing = 2.0 * BLIND_REACH * SPRAY_COVERAGE
        // The chain reaches back as well as forward: the server still has the target where it was a
        // tick or two ago, so a spot only placed ahead of the client's view misses it entirely.
        val span = window + PROBE_BACK
        val steps = if (speed * speed < MIN_PREDICT_VELOCITY_SQ) {
            1
        } else {
            min(max(1, ceil(speed * span / spacing).toInt()), maxProbes / 2)
        }

        // The first probes used to start at lead zero, where the client still draws the target but the
        // server has already moved on. The setting shifts the chain forward; zero keeps the old behaviour.
        val leadStart = debug.probeLeadStart.toDouble()

        val spots = ArrayList<Pair<BlockPos, Vec3>>(maxProbes)
        for (step in 0..steps) {
            val point = predictedBox(target, leadStart + span * step / steps).center
            addProbe(spots, terrain, point)
            addProbe(spots, terrain, point.add(0.0, 1.0, 0.0))
        }

        val result = spots.distinctBy { it.first }.take(maxProbes)

        // How close the spots actually are to the target right now: if these are small and the server
        // still drops the swings, the error is not in the prediction.
        val distances = result.map {
            target.boundingBox.distanceToSqr(player.getEyePositionAt(it.second))
        }
        dlog(
            "SPRAY",
            TpAuraPlusLog.details(
                "probeMin" to fmt(sqrt(distances.minOrNull() ?: 0.0)),
                "probeMax" to fmt(sqrt(distances.maxOrNull() ?: 0.0)),
                // Every probe in order: if none of them is close, the chain missed; if one is and the
                // server still says nothing, the packets are the problem.
                "probes" to distances.joinToString(",") { fmt(sqrt(it)) },
                "sent" to legalHops(result).size,
                "delay" to SWING_DELAY,
                "ping" to player.ping,
                "target" to desc(target),
                "speed" to fmt(speed),
                "samples" to targetSamples.size,
                "window" to fmt(window),
                "steps" to steps,
                "gap" to fmt(if (steps > 0) speed * span / steps else 0.0),
                "back" to fmt(PROBE_BACK),
                "leadStart" to fmt(leadStart),
                "leads" to (0..steps).joinToString(",") { fmt(leadStart + span * it / steps) },
                "spots" to result.size,
                "candidates" to spots.size,
                "max" to maxProbes,
                "spacing" to fmt(spacing),
            ),
        )
        return result
    }

    private fun addProbe(spots: MutableList<Pair<BlockPos, Vec3>>, terrain: Terrain, point: Vec3) {
        val block = BlockPos.containing(point)
        val feet = terrain.feet(block)
            ?: if (terrain.hasRoom(block.x, block.y, block.z)) {
                Vec3(block.x + 0.5, block.y.toDouble(), block.z + 0.5)
            } else {
                return
            }
        spots += block to feet
    }

    private fun sideTier(feet: Vec3, center: Vec3, facing: Vec3): Int {
        if (landingSide == LandingSide.ANY) {
            return 0
        }

        val toFeet = Vec3(feet.x - center.x, 0.0, feet.z - center.z)
        if (toFeet.lengthSqr() < 1.0E-6) {
            return 1
        }

        val dot = toFeet.normalize().dot(facing)
        val behind = dot < -SIDE_DOT
        val front = dot > SIDE_DOT
        return when (landingSide) {
            LandingSide.BEHIND -> when {
                behind -> 0
                front -> 2
                else -> 1
            }
            LandingSide.FLANKS -> when {
                behind -> 1
                front -> 2
                else -> 0
            }
            LandingSide.ANY -> 0
        }
    }

    // --- Readiness and debug helpers ---

    /** Whether a click with the attack cooldown passed is expected [tick] ticks from now. */
    /** Whether a swing could land [tick] ticks from now: the weapon's cooldown passed, and a click if one is needed. */
    private fun attackReadyAt(tick: Int): Boolean =
        (attackWithoutLooking || clicker.getClickAmount(tick) > 0) &&
            clicker.itemCooldown?.isCooldownPassed(tick) != false

    /**
     * The first tick from now an attack could land, or null when the weapon is not ready by the time the
     * route would arrive — the engagement then waits at its start instead of standing next to the target
     * for the rest of a long cooldown.
     */
    private fun readyClickTick(): Int? = (1..LAUNCH_WINDOW).firstOrNull(::attackReadyAt)

    private fun cooldownProgress(): Double = clicker.itemCooldown?.cooldownProgress()?.toDouble() ?: 1.0

    /** Whether a swing could go out right now: no miss cooldown, the weapon's cooldown passed and usable. */
    private fun attackReady(): Boolean =
        !isMissCooldownActive && clicker.itemCooldown?.isCooldownPassed() != false &&
            !player.cannotAttackWithItem(player.mainHandItem, 0)

    /** Arms the confirmation for a swing that is going out now; the hurt event answers it. */
    private fun awaitHit(target: LivingEntity) {
        awaitingHit = target
        hitWaitUntil = ticks + hitConfirmWindow()
    }

    /** The player's own speed, which is what ages a route in the ticks a search takes. */
    private fun playerSpeed(): Double = player.deltaMovement.length()

    /**
     * How far the player may have moved before a route counts as stale: the engine's own allowance,
     * widened by what the player covers while a search runs. Standing still, nothing changes.
     */
    private fun driftBudget(engagement: Engagement): Double =
        maxOf(
            DRIFT_FLOOR,
            TeleportManager.MAX_DRIFT + playerSpeed() * (engagement.searchTicks + 1),
        ).coerceAtMost(DRIFT_CAP)

    /**
     * How long the server's word may take. The damage event travels back over the same RTT the swing
     * took, so a window of one RTT plus a tick of server time is the minimum; a tighter one reports a
     * landed hit as a miss.
     */
    private fun hitConfirmWindow(): Int = (2 + player.ping / 50).coerceIn(3, 10)

    @Suppress("unused")
    private val hudHandler = handler<OverlayRenderEvent> { event ->
        TpAuraPlusHud.render(event.context, hud)
    }

    /** Feeds the HUD strip from the values the debug log already computes. */
    private fun syncHud() {
        val target = engagement?.target

        // Health is sampled every tick, so one drop between ticks is what the last swing took. A target
        // that leaves keeps its numbers, otherwise the damage would vanish the moment the module idles.
        if (target != null && target.id != hudTargetId) {
            hudTargetId = target.id
            hudTargetHealth = -1.0
            hudLastDamage = 0.0
        }
        if (target != null) {
            val health = target.health.toDouble()
            if (hudTargetHealth >= 0.0 && health < hudTargetHealth) {
                hudLastDamage = hudTargetHealth - health
            }
            hudTargetHealth = health
            TpAuraPlusHud.targetHealth = health
            TpAuraPlusHud.targetMaxHealth = target.maxHealth.toDouble()
        }
        TpAuraPlusHud.lastDamage = hudLastDamage

        TpAuraPlusHud.phase = phase.name
        TpAuraPlusHud.target = target?.name?.string
        TpAuraPlusHud.distance = engagement?.let { targetDist(it) } ?: 0.0
        // Without the prediction there is no lead in use: `predictedBox` returns the target's own box,
        // so reporting a number here would claim an aim the module does not take.
        TpAuraPlusHud.lead = if (prediction.enabled) {
            target?.let { leadTicks(it) + player.ping / 50.0 } ?: 0.0
        } else {
            0.0
        }
        TpAuraPlusHud.reqAttack = flightAvg
        TpAuraPlusHud.waypoints = engagement?.route?.points?.size?.minus(1) ?: 0
        TpAuraPlusHud.routeLength = engagement?.route?.let { Route.lengthOf(it.points) } ?: 0.0
        TpAuraPlusHud.searchTicks = engagement?.searchTicks ?: 0
        TpAuraPlusHud.drift = engagement?.route?.let { it.start.distanceTo(player.position()) } ?: 0.0
        // While a trip holds the position the divergence is the point of the trip, not a warning.
        TpAuraPlusHud.pinGap = if (TeleportManager.holds(this)) {
            0.0
        } else {
            TeleportManager.serverPosition?.let { it.distanceTo(player.position()) } ?: 0.0
        }

        TpAuraPlusHud.tick(active = phase != Phase.IDLE)
    }

    /** Whether a damage packet names the player as its cause; unattributed packets count as ours. */
    private fun hitFromUs(packet: Any): Boolean {
        if (packet !is ClientboundDamageEventPacket) {
            return true
        }
        return packet.sourceCauseId() == player.id || packet.sourceDirectId() == player.id ||
            (packet.sourceCauseId() <= 0 && packet.sourceDirectId() <= 0)
    }

    /**
     * Confirms the swing with the server's own word: a hurt event for the target just after the damage
     * was accepted. It may carry no attacker on proxied servers, so the window stays short.
     */
    @Suppress("unused")
    private val hitConfirmHandler = handler<PacketEvent> { event ->
        val waiting = awaitingHit

        val hurtId = when (val packet = event.packet) {
            // What onlookers get: the damage itself, with the entity that caused it. The hurt animation
            // packet only ever goes to the player who was hurt, so it cannot confirm our own swing.
            is ClientboundDamageEventPacket -> {
                // Ours, or — on servers reached through a proxy — a packet the proxy synthesised from
                // the old hurt event, which carries no attacker: both ids are -1.
                val ours = packet.sourceCauseId() == player.id || packet.sourceDirectId() == player.id
                val unattributed = packet.sourceCauseId() <= 0 && packet.sourceDirectId() <= 0
                if (packet.entityId() == waiting?.id && (ours || unattributed)) packet.entityId() else null
            }
            // Older servers, translated by ViaFabricPlus, report a hurt through the entity event.
            is ClientboundEntityEventPacket -> if (packet.eventId == ENTITY_EVENT_HURT) {
                packet.getEntity(world)?.id
            } else {
                null
            }
            is ClientboundHurtAnimationPacket -> packet.id()
            else -> null
        }

        // Only the target matters here: logging every hurt in the world turns the file into megabytes
        // of noise, and the interesting case is a packet that arrives when nothing is awaited.
        val relevant = hurtId != null && (hurtId == waiting?.id || hurtId == watchedTarget?.id)
        if (relevant) {
            dlog(
                "DMG",
                TpAuraPlusLog.details(
                    "packet" to event.packet.javaClass.simpleName,
                    "entity" to hurtId,
                    "awaiting" to waiting?.id,
                    "mine" to hitFromUs(event.packet),
                    "tick" to ticks,
                ),
            )
        }

        if (hurtId == null || waiting == null || hurtId != waiting.id) {
            return@handler
        }

        awaitingHit = null
        dlog("HIT-CONFIRMED", TpAuraPlusLog.details("target" to desc(waiting)))
        TpAuraPlusHud.onResult(true)
        sfx(sfx.hitSuccess, SFX_HIT)
    }

    /** Plays the hint of [event] unless a more important one already played this tick. */
    private fun sfx(event: SoundValueGroup, priority: Int) {
        if (!sfx.enabled || !event.enabled) {
            return
        }
        if (sfxTick != ticks) {
            sfxTick = ticks
            sfxPriority = 0
        }
        if (priority < sfxPriority) {
            return
        }
        sfxPriority = priority
        SoundValueGroup.play(event.soundValue.get(), sfx.volume / 100f)
    }

    private fun dlog(event: String, details: String = "") {
        if (debug.log) {
            TpAuraPlusLog.log(ticks, event, details)
        }
    }

    private fun dchat(text: String) {
        if (debug.log) {
            chat("[TpAuraPlus] $text", this)
        }
    }

    private fun playerDrift(engagement: Engagement): Double =
        engagement.route?.start?.let { player.position().distanceTo(it) }
            ?: engagement.origin?.let { player.position().distanceTo(it) }
            ?: 0.0

    /** Distance from where the server has the player to the target's box, same metric as [isInReach]. */
    private fun targetDist(engagement: Engagement): Double =
        sqrt(engagement.target.boundingBox.distanceToSqr(player.getEyePositionAt(trip.feet)))

    /** front/side/behind relative to where the target's body faces. */
    private fun sideOf(feet: Vec3, target: LivingEntity): String {
        val facing = Vec3.directionFromRotation(0f, target.yBodyRot)
        val toFeet = feet.subtract(target.position())
        val flat = Vec3(toFeet.x, 0.0, toFeet.z)
        if (flat.lengthSqr() < 1.0E-6) {
            return "inside"
        }
        val dot = flat.normalize().dot(facing)
        return when {
            dot < -SIDE_DOT -> "behind"
            dot > SIDE_DOT -> "front"
            else -> "side"
        }
    }

    private fun desc(target: LivingEntity): String =
        "#${target.id}:${target.name.string}"

    private fun fmt(value: Double): String = "%.2f".format(value)

    private fun fmt(value: Vec3): String =
        "(%.2f,%.2f,%.2f)".format(value.x, value.y, value.z)

    private const val REACH_MARGIN = 0.1
    private const val TARGET_MOVEMENT_MARGIN = 0.4

    // Reach as the server measures it, per protocol generation:
    //
    //   1.8 – 1.20.4   hasLineOfSight ? 36.0 : 9.0   (a ray from our eyes to the target's eyes)
    //   1.20.5 and up  only the entityInteractionRange attribute, default 3.0, no sight bonus
    //
    // The sight rule therefore only holds on the older servers, which is where HvH happens. On a modern
    // server the six would let the module plan swings the server drops; without the spray the reach is
    // read straight from the attribute anyway, so the ordinary path is unaffected.

    /** Ticks of searching before a pin that the client disagrees with is treated as fiction. */
    private const val RESYNC_AFTER = 8

    /** How far the predicted anchor may drift before a cached goal set is thrown away. */
    private const val GOAL_DRIFT = 10.0

    /** Ticks between two retargets of a running search. */
    private const val RETARGET_COOLDOWN = 3

    /** How many ticks a route search may take before it counts as stuck rather than slow. */
    private const val SEARCH_PATIENCE = 10

    /** Ticks between the teleport and the swing, long enough for the server to take the move. */
    private const val SWING_DELAY = 1

    /** How long a return may take before the run is written off and the position handed back. */
    private const val RETURN_PATIENCE = 40

    /** How far the client and the server may drift apart, and for how long, before a forced resync. */
    private const val DESYNC_GAP = 12.0
    private const val DESYNC_PATIENCE = 20

    /**
     * How long a teleport pin lasts. It only has to hold until the swing goes out; a pin that outlives
     * the swing keeps rewriting the client's own movement, and the player appears stuck for its duration.
     */
    private const val TELEPORT_PIN_TICKS = 3

    /** Distance allowed when the sight line to the target's eyes is blocked. */
    private const val BLIND_REACH = 3.0

    /** Distance allowed with a clear sight line, the 36.0 of the older servers squared. */
    private const val SIGHTED_REACH = 6.0

    /** Probe spacing as a fraction of the coverage two reaches give, leaving room for the swing itself. */
    private const val SPRAY_COVERAGE = 0.9
    private const val ROUTE_DETOUR = 2.0

    /** Ticks the click may delay the dispatch before the flight leaves without it. */
    private const val ALIGN_GRACE = 2
    private const val MAX_RETRIES = 2
    private const val MAX_DISPATCH_RETRIES = 2

    /** Stale routes that may be re-planned before the engagement is given up. */
    private const val MAX_STALE_RETRIES = 8

    /** How far a route may go stale at walking speed; the engine's own allowance is tighter. */
    private const val DRIFT_FLOOR = 6.0

    /** Ceiling of the adaptive drift allowance, in blocks. */
    private const val DRIFT_CAP = 24.0
    private const val FAIL_REST_TICKS = 10
    private const val SETBACK_REST_TICKS = 20
    private const val UNREACHABLE_TICKS = 40
    private const val MIN_PREDICT_VELOCITY_SQ = 1.0E-4
    private const val SIDE_DOT = 0.5

    /** The destination retargets once the predicted position drifted this far, or [RETARGET_TICKS] of travel. */
    private const val RETARGET_DRIFT = 0.5
    private const val RETARGET_TICKS = 1.0

    /** Ticks the velocity is measured over, and how much history is kept for the turn. */
    private const val SPEED_WINDOW = 3
    private const val MAX_SAMPLES = 8

    /** The aggressive spray: a chain of probe spots along the target's path, its height included. */
    private const val PROBE_TICKS = 2.0

    /** How far back along the target's path the spray reaches, covering the server's own lag. */
    private const val PROBE_BACK = 2.0

    /** `LivingEntity` broadcasts this entity event on every accepted damage: the red flash and hurt time. */
    private const val ENTITY_EVENT_HURT: Byte = 2

    /** Sound priorities within one tick: the highest one keeps it, the lower ones stay silent. */
    private const val SFX_PATH = 1
    private const val SFX_TELEPORT = 2
    private const val SFX_ROUTE_FAIL = 2
    private const val SFX_HIT = 3
    private const val SFX_SETBACK = 4

    /** ATTACK ticks without a click opportunity before giving up on the click plan. */
    private const val ATTACK_PATIENCE = 40

    /** How far the server may end from the destination before the flight counts as set back. */
    private const val ARRIVE_ACCEPT_SQ = 1.0

    /** How far the look may trail the target before a swing stops counting as aimed (cos ~49°). */
    private const val FACING_DOT = 0.65

    /** Click ticks the gate may turn away before the engagement gives up on its aim. */
    private const val MAX_BLOCKED = 8

    /** How much of the measured turn is applied to the lead — most targets do not circle cleanly. */
    private const val TURN_DAMP = 0.9

    /** Beyond this much turning per tick the path is a corner, not an arc, and the arc model is dropped. */
    private const val SHARP_TURN = 0.35

    /** The lead never follows more than this much turning; beyond it the extrapolation lies. */
    private const val MAX_TURN = 0.7

    /** The shortest lead that still makes sense; below it the aim sits on the target's current spot. */
    private const val MIN_LEAD = 1.0

    /** Ticks between the flight ending and the swing going out. */

    /** Foliage blocks a sight check may pass through before a real wall counts. */
    private const val MAX_SIGHT_SKIPS = 3

    /** How far the player may walk off the route before the click alignment gives up and flies. */
    private const val DRIFT_SOFT = 1.0

    /**
     * Ticks from starting an engagement to the swing landing: the search, the flight and the state
     * machine. Starting earlier only means waiting next to the target for the rest of the cooldown.
     */
    private const val LAUNCH_WINDOW = 5

    /** Sort weights: hovering tier over side tier over distance, without the bands overlapping. */
    private const val TIER_HOVER = 1_000_000_000_000.0
    private const val TIER_SIDE = 1_000_000.0

}
