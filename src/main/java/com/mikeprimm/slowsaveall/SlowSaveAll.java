package com.mikeprimm.slowsaveall;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent; 
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.crash.CrashReport;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.ReportedException;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.world.WorldEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

@Mod(modid = "SlowSaveAll", name = "SlowSaveAll", version = Version.VER)
public class SlowSaveAll
{    
    public static Logger log = Logger.getLogger("SlowSaveAll");
    
    // The instance of your mod that Forge uses.
    @Instance("SlowSaveAll")
    public static SlowSaveAll instance;

    // Says where the client and server 'proxy' code is loaded.
    @SidedProxy(clientSide = "com.mikeprimm.slowsaveall.ClientProxy", serverSide = "com.mikeprimm.slowsaveall.Proxy")
    public static Proxy proxy;
    
    public boolean good_init = false;
    
    public int savePeriod = 600;
    public int chunksPerTick = 20;
    public boolean skipPlayers = false;
    public boolean quietLog = false;
    
    public boolean tickregistered = false;
    private TickHandler handler = new TickHandler();
    private Method saveLevel = null;
    
    public static void crash(Exception x, String msg) {
        CrashReport crashreport = CrashReport.makeCrashReport(x, msg);
        throw new ReportedException(crashreport);
    }
    public static void crash(String msg) {
        crash(new Exception(), msg);
    }
    
    @EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        // Load configuration file - use suggested (config/WesterosBlocks.cfg)
        Configuration cfg = new Configuration(event.getSuggestedConfigurationFile());
        try
        {
            cfg.load();
            savePeriod = cfg.get("Settings", "savePeriod", 600).getInt();
            chunksPerTick = cfg.get("Settings", "chunksPerTick", 20).getInt();
            skipPlayers = cfg.get("Settings",  "skipPlayers", false).getBoolean(false);
            quietLog = cfg.get("Settings",  "quietLog", false).getBoolean(false);
            if (chunksPerTick < 1) chunksPerTick = 1;
            if (savePeriod < 60) savePeriod = 60;
            
            good_init = true;
        }
        catch (Exception e)
        {
            crash(e, "SlowSaveAll couldn't load its configuration");
        }
        finally
        {
            cfg.save();
        }
    }

    @EventHandler
    public void load(FMLInitializationEvent event)
    {
        log.info("Loading SlowSaveAll v" + Version.VER);

        if (!good_init) {
            crash("preInit failed - aborting load()");
            return;
        }
        try {
            saveLevel = WorldServer.class.getDeclaredMethod("func_73042_a", new Class[0]);
            saveLevel.setAccessible(true);
        } catch (SecurityException e) {
        } catch (NoSuchMethodException e) {
        }
        if (saveLevel == null) {
            log.severe("saveLevel method not found - SlowSaveAll disabled");
        }
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event)
    {
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
    }
    
    @EventHandler
    public void serverStarted(FMLServerStartedEvent event)
    {
        if (saveLevel == null) return;
        log.info("Starting SlowSaveAll v" + Version.VER);

        /* Register tick handler */
        if(!tickregistered) {
            FMLCommonHandler.instance().bus().register(handler);
            tickregistered = true;
        }
        handler.reset(false);
    }

    @EventHandler
    public void serverStopping(FMLServerStoppingEvent event)
    {
        /* Unregister tick handler */
        if(tickregistered) {
            handler.reset(true);
            tickregistered = false;
        }
    }    
    
    public class TickHandler {
        private boolean stopped;
        private int ticksUntilSave;
        private List<WorldServer> worldsToDo;
        private List<EntityPlayerMP> playersToDo;
        private WorldServer activeWorld;
        private int[] chunkX;
        private int[] chunkZ;
        private int chunkIdx;
        private int chunkCnt;
        
        public void reset(boolean stop) {
            stopped = stop;
            ticksUntilSave = (savePeriod * 20) / 5; // Do first one after 20% of period 
            worldsToDo = null;
            chunkX = chunkZ = null;
            chunkIdx = 0;
            chunkCnt = 0;
        }

        @SubscribeEvent
        public void tickEvent(TickEvent.ServerTickEvent event)  {
            if (stopped) return;
            if (ticksUntilSave > 0) {   // Still ticking?
                ticksUntilSave--;
                if (ticksUntilSave > 0) {
                    return;
                }
            }
            // Process save
            MinecraftServer server = MinecraftServer.getServer();
            ServerConfigurationManager scm = server.getConfigurationManager();
            // If no world list, start one
            if (worldsToDo == null) {
                worldsToDo = new ArrayList<WorldServer>();
                for (int i = 0; i < server.worldServers.length; i++) {
                    worldsToDo.add(server.worldServers[i]);
                }
            }
            // If no active world, find next one
            while (activeWorld == null) {
                if (worldsToDo.isEmpty()) { // No more?  we're done
                    if (!skipPlayers) {
                        if (playersToDo == null) {
                            playersToDo = new ArrayList<EntityPlayerMP>();
                            for (Object o : scm.playerEntityList) {
                                playersToDo.add((EntityPlayerMP) o);
                            }
                            if (!quietLog) {
                                log.info("Saving " + playersToDo.size() + " players");
                            }
                        }
                        if (!playersToDo.isEmpty()) {   // More to do?
                            EntityPlayerMP p = playersToDo.remove(0);
                            if (scm.playerEntityList.contains(p)) {    // Still in list?
                                scm.writePlayerData(p);
                            }
                            return;
                        }
                    }
                    if (!quietLog) {
                        log.info("Save done");
                    }
                    playersToDo = null;
                    worldsToDo = null;
                    ticksUntilSave = savePeriod * 20;   // Reset timer
                    return;
                }
                activeWorld = worldsToDo.remove(0); // Get first one
                // Now, get the list of loaded chunks
                if (activeWorld != null) {
                    List<Chunk> chunks = ObfuscationReflectionHelper.getPrivateValue(ChunkProviderServer.class, activeWorld.theChunkProviderServer, "field_73245_g");
                    if ((chunks != null) && (!chunks.isEmpty())) {
                        int cnt = chunks.size();
                        chunkX = new int[cnt];
                        chunkZ = new int[cnt];
                        for (int i = 0; i < cnt; i++) {
                            Chunk c = chunks.get(i);
                            chunkX[i] = c.xPosition;
                            chunkZ[i] = c.zPosition;
                        }
                        chunkIdx = 0;
                        chunkCnt = 0;
                        // And save world data
                        try {
                            if (!quietLog) {
                                log.info("Saving level data for world '" + activeWorld.getWorldInfo().getWorldName() + "'");
                            }
                            saveLevel.invoke(activeWorld, new Object[0]);
                        } catch (IllegalArgumentException e) {
                        } catch (IllegalAccessException e) {
                        } catch (InvocationTargetException e) {
                        }
                        if (!quietLog) {
                            log.info("Saving chunks for world '" + activeWorld.getWorldInfo().getWorldName() + "'");
                        }
                        return;
                    }
                    else {
                        activeWorld = null;
                    }
                }
            }
            // Process active world
            boolean flag = activeWorld.levelSaving;
            activeWorld.levelSaving = false;    // False=enabled (bad name for field in 1.7.x)
            for (int i = 0; (i < chunksPerTick) && (chunkIdx < chunkX.length); chunkIdx++) {
                if (activeWorld.theChunkProviderServer.chunkExists(chunkX[chunkIdx], chunkZ[chunkIdx])) {   // If still loaded
                    Chunk c = activeWorld.getChunkFromChunkCoords(chunkX[chunkIdx], chunkZ[chunkIdx]);
                    if ((c != null) && (c.needsSaving(true))) {
                        activeWorld.theChunkProviderServer.safeSaveExtraChunkData(c);
                        activeWorld.theChunkProviderServer.safeSaveChunk(c);
                        c.isModified = false;
                        chunkCnt++;
                    }
                    i++;
                }
            }
            activeWorld.levelSaving = flag;  // Restore save state
            
            if (chunkIdx >= chunkX.length) { // Done?
                MinecraftForge.EVENT_BUS.post(new WorldEvent.Save(activeWorld));
                if (!quietLog) {
                    log.info("Save of world '" + activeWorld.getWorldInfo().getWorldName() + "' completed - " + chunkCnt + " saved");
                }
                activeWorld = null;
                chunkX = chunkZ = null;
                chunkIdx = 0;
            }
        }
    }
}
