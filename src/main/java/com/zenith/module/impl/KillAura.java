package com.zenith.module.impl;

import com.github.rfresh2.EventConsumer;
import com.zenith.cache.data.entity.EntityLiving;
import com.zenith.cache.data.entity.EntityPlayer;
import com.zenith.cache.data.entity.EntityStandard;
import com.zenith.event.module.ClientBotTick;
import com.zenith.feature.world.*;
import com.zenith.feature.world.raycast.RaycastHelper;
import com.zenith.mc.item.ItemRegistry;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Shared.*;

public class KillAura extends AbstractInventoryModule {

    private static final Set<EntityType> hostileEntities = ReferenceOpenHashSet.of(
        EntityType.BLAZE, EntityType.CAVE_SPIDER, EntityType.CREEPER, EntityType.DROWNED, EntityType.ELDER_GUARDIAN,
        EntityType.ENDER_DRAGON, EntityType.ENDERMITE, EntityType.EVOKER, EntityType.GHAST, EntityType.GUARDIAN,
        EntityType.HOGLIN, EntityType.HUSK, EntityType.ILLUSIONER, EntityType.FIREBALL, EntityType.MAGMA_CUBE,
        EntityType.PHANTOM, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE, EntityType.PILLAGER, EntityType.RAVAGER,
        EntityType.SHULKER, EntityType.SHULKER_BULLET, EntityType.SILVERFISH, EntityType.SKELETON, EntityType.SLIME,
        EntityType.SMALL_FIREBALL, EntityType.SPIDER, EntityType.STRAY, EntityType.VEX, EntityType.VINDICATOR,
        EntityType.WARDEN, EntityType.WITCH, EntityType.WITHER, EntityType.WITHER_SKELETON, EntityType.ZOGLIN,
        EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER
    );
    private static final Set<EntityType> neutralEntities = ReferenceOpenHashSet.of(
        EntityType.BEE, EntityType.DOLPHIN, EntityType.ENDERMAN, EntityType.FOX, EntityType.GOAT, EntityType.IRON_GOLEM,
        EntityType.LLAMA, EntityType.PANDA, EntityType.POLAR_BEAR, EntityType.TRADER_LLAMA, EntityType.WOLF,
        EntityType.ZOMBIFIED_PIGLIN
    );
    private int delay = 0;
    private final WeakReference<EntityLiving> nullRef = new WeakReference<>(null);
    private WeakReference<EntityLiving> attackTarget = nullRef;
    public static final int MOVEMENT_PRIORITY = 500;
    private final IntSet swords = IntSet.of(
        ItemRegistry.DIAMOND_SWORD.id(),
        ItemRegistry.NETHERITE_SWORD.id(),
        ItemRegistry.IRON_SWORD.id()
    );
    private final IntSet axes = IntSet.of(
        ItemRegistry.NETHERITE_AXE.id(),
        ItemRegistry.DIAMOND_AXE.id(),
        ItemRegistry.IRON_AXE.id()
    );

    public KillAura() {
        super(HandRestriction.MAIN_HAND, 1, MOVEMENT_PRIORITY);
    }

    public boolean isActive() {
        return CONFIG.client.extra.killAura.enabled && attackTarget.get() != null;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::handleClientTick),
            of(ClientBotTick.Stopped.class, this::handleBotTickStopped)
        );
    }

    @Override
    public boolean enabledSetting() {
        return CONFIG.client.extra.killAura.enabled;
    }

    @Override
    public void onDisable() {
        delay = 0;
        attackTarget = nullRef;
    }

    private void handleClientTick(final ClientBotTick event) {
        if (delay > 0) {
            delay--;
            EntityLiving target = attackTarget.get();
            if (target != null && canPossiblyReach(target)) {
                if (!hasRotation(target)) {
                    rotateTo(target);
                }
                INVENTORY.invActionReq(this, MOVEMENT_PRIORITY - 1);
            }
            return;
        }
        if (CACHE.getPlayerCache().getThePlayer().isAlive()) {
            final EntityLiving target = findTarget();
            if (target != null) {
                if (!attackTarget.refersTo(target))
                    attackTarget = new WeakReference<>(target);
                if (switchToWeapon()) {
                    INVENTORY.invActionReq(this, MOVEMENT_PRIORITY - 1);
                    attack(target).addInputExecutedListener(this::onAttackInputExecuted);
                } else {
                    // stop while doing inventory actions
                    INPUTS.submit(InputRequest.builder()
                                      .priority(MOVEMENT_PRIORITY - 1)
                                      .build());
                }
                return;
            }
        }
        attackTarget = nullRef;
    }


    private void onAttackInputExecuted(InputRequestFuture future) {
        if (future.getClickResult() instanceof ClickResult.LeftClickResult leftClickResult
            && leftClickResult.getEntity() != null && leftClickResult.getEntity() == attackTarget.get()) {
            delay = CONFIG.client.extra.killAura.attackDelayTicks;
        }
    }

    @Nullable
    private EntityLiving findTarget() {
        // todo: check if its worth it to copy the entity cache to a list to avoid streams
        var entityStream = CACHE.getEntityCache().getEntities().values().stream()
            .filter(e -> e instanceof EntityLiving)
            .map(e -> (EntityLiving) e)
            .filter(e -> e != CACHE.getPlayerCache().getThePlayer())
            .filter(EntityLiving::isAlive);
        entityStream = switch (CONFIG.client.extra.killAura.priority) {
            case NONE -> entityStream;
            case NEAREST -> entityStream
                .sorted(Comparator.comparingDouble(e -> CACHE.getPlayerCache().distanceSqToSelf(e)));
        };

        return entityStream
            .filter(this::validTarget)
            .filter(this::canPossiblyReach)
            .findFirst()
            .orElse(null);
    }

    private boolean validTarget(EntityLiving entity) {
        if (CONFIG.client.extra.killAura.targetPlayers && entity instanceof EntityPlayer player) {
            if (player.isSelfPlayer()) return false;
            return !PLAYER_LISTS.getFriendsList().contains(player.getUuid())
                && !PLAYER_LISTS.getWhitelist().contains(player.getUuid())
//                && !PLAYER_LISTS.getSpectatorWhitelist().contains(player.getUuid())
                    ;

        } else if (entity instanceof EntityStandard e) {
            if (CONFIG.client.extra.killAura.targetHostileMobs) {
                if (hostileEntities.contains(e.getEntityType()))
                    return !CONFIG.client.extra.killAura.onlyHostileAggressive || isAggressive(entity);
            }
            if (CONFIG.client.extra.killAura.targetArmorStands) {
                if (e.getEntityType() == EntityType.ARMOR_STAND) return true;
            }
            if (CONFIG.client.extra.killAura.targetNeutralMobs) {
                if (neutralEntities.contains(e.getEntityType()))
                    return !CONFIG.client.extra.killAura.onlyNeutralAggressive || isAggressive(entity);
            }
            if (CONFIG.client.extra.killAura.targetCustom) {
                return CONFIG.client.extra.killAura.customTargets.contains(e.getEntityType());
            }
        }
        return false;
    }

    private static boolean isAggressive(final EntityLiving entity) {
        // https://wiki.vg/Entity_metadata#Mob
        var byteMetadata = entity.getMetadata().get(15);
        if (byteMetadata == null) return false;
        if (byteMetadata instanceof ByteEntityMetadata byteData) {
            var data = byteData.getPrimitiveValue() & 0x04;
            return data != 0;
        }
        return false;
    }

    private void handleBotTickStopped(final ClientBotTick.Stopped event) {
        delay = 0;
        attackTarget = nullRef;
    }

    private InputRequestFuture attack(final EntityLiving entity) {
        var rotation = RotationHelper.shortestRotationTo(entity);
        return INPUTS.submit(InputRequest.builder()
                                 .input(Input.builder()
                                            .leftClick(true)
                                            .clickTarget(new ClickTarget.EntityInstance(entity))
                                            .build())
                                 .yaw(rotation.getX())
                                 .pitch(rotation.getY())
                                 .priority(MOVEMENT_PRIORITY)
                                 .build());
    }

    private void rotateTo(EntityLiving entity) {
        var rotation = RotationHelper.shortestRotationTo(entity);
        INPUTS.submit(InputRequest.builder()
                          .yaw(rotation.getX())
                          .pitch(rotation.getY())
                          .priority(MOVEMENT_PRIORITY)
                          .build());
    }

    private boolean hasRotation(final EntityLiving entity) {
        var entityRaycastResult = RaycastHelper.playerEyeRaycastThroughToTarget(entity);
        return entityRaycastResult.hit();
    }

    private boolean canPossiblyReach(final EntityLiving entity) {
        var rangeSq = Math.pow(MODULE.get(PlayerSimulation.class).getEntityInteractDistance(), 2) + 5;
        if (CACHE.getPlayerCache().distanceSqToSelf(entity) > rangeSq) return false;
        var rotation = RotationHelper.shortestRotationTo(entity);
        var entityRaycastResult = RaycastHelper.playerEyeRaycastThroughToTarget(entity, rotation.getX(), rotation.getY());
        return entityRaycastResult.hit();
    }

    public boolean switchToWeapon() {
        if (!CONFIG.client.extra.killAura.switchWeapon) return true;
        delay = doInventoryActions();
        return delay == 0;
    }

    private boolean isWeapon(int id) {
        return swords.contains(id) || axes.contains(id);
    }

    @Override
    public boolean itemPredicate(final ItemStack itemStack) {
        return isWeapon(itemStack.getId());
    }
}
