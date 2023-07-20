package de.cadentem.cave_dweller;

import com.mojang.logging.LogUtils;
import de.cadentem.cave_dweller.client.CaveDwellerRenderer;
import de.cadentem.cave_dweller.config.ServerConfig;
import de.cadentem.cave_dweller.entities.CaveDwellerEntity;
import de.cadentem.cave_dweller.network.CaveSound;
import de.cadentem.cave_dweller.network.NetworkHandler;
import de.cadentem.cave_dweller.registry.ModEntityTypes;
import de.cadentem.cave_dweller.registry.ModItems;
import de.cadentem.cave_dweller.registry.ModSounds;
import de.cadentem.cave_dweller.util.Utils;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import software.bernie.geckolib3.GeckoLib;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod(CaveDweller.MODID)
public class CaveDweller {
    public static final String MODID = "cave_dweller";
    private static final Logger LOGGER = LogUtils.getLogger();

    private boolean initialized; // TODO :: Currently needed since config values are not present at server start
    private int calmTimer;
    private int noiseTimer;
    private boolean anySpelunkers = false;
    private final List<Player> spelunkers = new ArrayList<>();

    public CaveDweller() {
        GeckoLib.initialize();

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::commonSetup);

        ModItems.register(modEventBus);
        ModSounds.register(modEventBus);
        ModEntityTypes.register(modEventBus);

        MinecraftForge.EVENT_BUS.register(this);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
//        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        EntityRenderers.register(ModEntityTypes.CAVE_DWELLER.get(), CaveDwellerRenderer::new);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        NetworkHandler.register();
    }

    @SubscribeEvent
    public void serverTick(final TickEvent.ServerTickEvent event) {
        if (!initialized) {
            resetNoiseTimer();
            resetCalmTimer();
            initialized = true;
        }

        ServerLevel overworld = event.getServer().getLevel(Level.OVERWORLD);

        if (overworld == null) {
            return;
        }

        Iterable<Entity> entities = overworld.getAllEntities();
        AtomicBoolean dwellerExists = new AtomicBoolean(false);

        entities.forEach(entity -> {
            if (entity instanceof CaveDwellerEntity) {
                dwellerExists.set(true);
                this.resetCalmTimer();
            }
        });

        --this.noiseTimer;
        if (this.noiseTimer <= 0 && (dwellerExists.get() || this.calmTimer <= Utils.secondsToTicks(ServerConfig.RESET_CALM_MAX.get()) / 2)) {
            overworld.getPlayers(this::playCaveSoundToSpelunkers);
        }

        boolean canSpawn = this.calmTimer <= 0;

        --this.calmTimer;
        if (canSpawn && !dwellerExists.get()) {
            Random random = new Random();

            if (random.nextDouble() <= ServerConfig.SPAWN_CHANCE_PER_TICK.get()) {
                this.spelunkers.clear();
                this.anySpelunkers = false;

                overworld.getPlayers(this::listSpelunkers);

                if (this.anySpelunkers) {
                    Player victim = this.spelunkers.get(random.nextInt(this.spelunkers.size()));
                    overworld.getPlayers(this::playCaveSoundToSpelunkers);

                    CaveDwellerEntity caveDweller = new CaveDwellerEntity(ModEntityTypes.CAVE_DWELLER.get(), overworld);
                    caveDweller.setInvisible(true);
                    caveDweller.setPos(caveDweller.generatePos(victim));
                    overworld.addFreshEntity(caveDweller);
                    this.resetCalmTimer();
                }
            }
        }
    }

    private boolean listSpelunkers(final ServerPlayer player) {
        if (this.isPlayerSpelunker(player)) {
            this.anySpelunkers = true;
            this.spelunkers.add(player);
        }

        return true;
    }

    public boolean playCaveSoundToSpelunkers(final ServerPlayer player) {
        if (!isPlayerSpelunker(player)) {
            return false;
        }

        Random rand = new Random();
        // TODO :: Play the same sound to all players?
        ResourceLocation soundLocation = switch (rand.nextInt(4)) {
            case 1 -> ModSounds.CAVENOISE_2.get().getLocation();
            case 2 -> ModSounds.CAVENOISE_3.get().getLocation();
            case 3 -> ModSounds.CAVENOISE_4.get().getLocation();
            default -> ModSounds.CAVENOISE_1.get().getLocation();
        };

        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new CaveSound(soundLocation, player.blockPosition(), 2.0F, 1.0F));
        this.resetNoiseTimer();

        return true;
    }

    public boolean isPlayerSpelunker(final Player player) {
        if (player == null) {
            return false;
        } else {
            Level level = player.getLevel();
            BlockPos playerBlockPos = new BlockPos(player.position().x, player.position().y, player.position().z);
            return player.position().y < ServerConfig.SPAWN_HEIGHT.get() && (ServerConfig.ALLOW_SURFACE_SPAWN.get() || !level.canSeeSky(playerBlockPos));
        }
    }

    private void resetCalmTimer() {
        Random random = new Random();
        this.calmTimer = random.nextInt(Utils.secondsToTicks(ServerConfig.RESET_CALM_MIN.get()), Utils.secondsToTicks(ServerConfig.RESET_CALM_MAX.get()));

        if (random.nextDouble() <= ServerConfig.RESET_CALM_COOLDOWN_CHANCE.get()) {
            this.calmTimer = Utils.secondsToTicks(ServerConfig.RESET_CALM_COOLDOWN.get());
        }
    }

    private void resetNoiseTimer() {
        Random random = new Random();
        this.noiseTimer = random.nextInt(Utils.secondsToTicks(ServerConfig.RESET_NOISE_MIN.get()), Utils.secondsToTicks(ServerConfig.RESET_NOISE_MAX.get()));
    }
}
