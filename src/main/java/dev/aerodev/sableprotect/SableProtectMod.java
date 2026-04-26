package dev.aerodev.sableprotect;

import com.mojang.logging.LogUtils;
import dev.aerodev.sableprotect.claim.ClaimData;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.claim.ClaimStorage;
import dev.aerodev.sableprotect.command.SpCommand;
import dev.aerodev.sableprotect.config.SableProtectConfig;
import dev.aerodev.sableprotect.freeze.FreezeManager;
import dev.aerodev.sableprotect.freeze.PendingFetchManager;
import dev.aerodev.sableprotect.lifecycle.ClaimObserver;
import dev.aerodev.sableprotect.lifecycle.SplitInheritanceQueue;
import dev.aerodev.sableprotect.protection.BlockProtectionHandler;
import dev.aerodev.sableprotect.protection.DisassemblyProtectionHandler;
import dev.aerodev.sableprotect.protection.InteractionProtectionHandler;
import dev.aerodev.sableprotect.protection.InventoryProtectionHandler;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(SableProtectMod.MODID)
public class SableProtectMod {

    public static final String MODID = "sableprotect";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final ClaimRegistry claimRegistry = new ClaimRegistry();
    private final FreezeManager freezeManager = new FreezeManager();
    private final PendingFetchManager pendingFetchManager = new PendingFetchManager();

    public SableProtectMod(final IEventBus modEventBus, final ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, SableProtectConfig.SPEC);

        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(new BlockProtectionHandler());
        NeoForge.EVENT_BUS.register(new DisassemblyProtectionHandler());
        NeoForge.EVENT_BUS.register(new InventoryProtectionHandler());
        NeoForge.EVENT_BUS.register(new InteractionProtectionHandler());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[sable-protect] Registering sub-level observer");
        SableEventPlatform.INSTANCE.onSubLevelContainerReady((level, container) -> {
            container.addObserver(new ClaimObserver(claimRegistry, container, freezeManager, pendingFetchManager));
            LOGGER.info("[sable-protect] Observer registered for level {}", level.dimension().location());
        });

        // Capture the parent sub-level's claim data the moment a split begins, so we can copy it
        // onto the new fragment when its onSubLevelAdded fires. By the time onSubLevelAdded runs,
        // Sable has already cleared the parent linkage (see SubLevelTrackingSystem.tick).
        SubLevelHeatMapManager.addSplitListener((level, bounds, blocks) -> {
            BlockPos firstBlock = null;
            for (final BlockPos b : blocks) { firstBlock = b; break; }
            if (firstBlock == null) {
                SplitInheritanceQueue.push(level, SplitInheritanceQueue.Entry.unclaimed());
                return;
            }
            final SubLevel parent = Sable.HELPER.getContaining(level, firstBlock);
            ClaimData parentSnapshot = null;
            if (parent instanceof ServerSubLevel parentServer) {
                final ClaimData parentData = ClaimData.read(parentServer);
                if (parentData != null) parentSnapshot = parentData.copy();
            }
            SplitInheritanceQueue.push(level,
                    parentSnapshot == null
                            ? SplitInheritanceQueue.Entry.unclaimed()
                            : new SplitInheritanceQueue.Entry(parentSnapshot));
        });
    }

    @SubscribeEvent
    public void onRegisterCommands(final RegisterCommandsEvent event) {
        SpCommand.register(event, claimRegistry, freezeManager, pendingFetchManager);
        LOGGER.info("[sable-protect] Commands registered");
    }

    @SubscribeEvent
    public void onServerTick(final ServerTickEvent.Post event) {
        final long tick = event.getServer().getTickCount();
        freezeManager.tick(event.getServer(), tick);
        pendingFetchManager.tick(event.getServer(), tick);
    }

    @SubscribeEvent
    public void onServerStarted(final ServerStartedEvent event) {
        // Attach the persistent claim storage. We anchor it to the overworld's data
        // directory so all claims share a single file regardless of which dimension
        // their sub-levels live in.
        final ServerLevel overworld = event.getServer().overworld();
        final ClaimStorage storage = overworld.getDataStorage()
                .computeIfAbsent(ClaimStorage.factory(), ClaimStorage.FILE_ID);
        claimRegistry.attach(storage);
        LOGGER.info("[sable-protect] Claim storage attached ({} claims loaded)",
                storage.entries().size());
    }

    @SubscribeEvent
    public void onServerStopping(final ServerStoppingEvent event) {
        // Clear constraints before the physics pipeline tears down so they don't persist
        // across restarts as orphaned anchors.
        freezeManager.cancelAll(event.getServer());
        pendingFetchManager.cancelAll(event.getServer());
        claimRegistry.detach();
        SplitInheritanceQueue.clear();
    }
}
