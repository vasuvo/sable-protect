package dev.aerodev.sableprotect;

import com.mojang.logging.LogUtils;
import dev.aerodev.sableprotect.claim.ClaimRegistry;
import dev.aerodev.sableprotect.command.SpCommand;
import dev.aerodev.sableprotect.lifecycle.ClaimObserver;
import dev.aerodev.sableprotect.protection.BlockProtectionHandler;
import dev.aerodev.sableprotect.protection.DisassemblyProtectionHandler;
import dev.aerodev.sableprotect.protection.InteractionProtectionHandler;
import dev.aerodev.sableprotect.protection.InventoryProtectionHandler;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

@Mod(SableProtectMod.MODID)
public class SableProtectMod {

    public static final String MODID = "sableprotect";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final ClaimRegistry claimRegistry = new ClaimRegistry();

    public SableProtectMod(final IEventBus modEventBus) {
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
            container.addObserver(new ClaimObserver(claimRegistry, container));
            LOGGER.info("[sable-protect] Observer registered for level {}", level.dimension().location());
        });
    }

    @SubscribeEvent
    public void onRegisterCommands(final RegisterCommandsEvent event) {
        SpCommand.register(event, claimRegistry);
        LOGGER.info("[sable-protect] Commands registered");
    }
}
