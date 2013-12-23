package com.mikeprimm.slowsaveall;

import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent; 
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.network.NetworkMod;
import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.crash.CrashReport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ReportedException;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.Configuration;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.logging.Logger;

@Mod(modid = "SlowSaveAll", name = "SlowSaveAll", version = Version.VER)
@NetworkMod(clientSideRequired = false, serverSideRequired = false)
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
    
    public boolean tickregistered = false;
    private TickHandler handler = new TickHandler();
    private Method saveChunk = null;
    private Method extraSaveChunkData = null;
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
        if (!good_init) {
            crash("preInit failed - aborting load()");
            return;
        }
        Class<?> cls = ChunkProviderServer.class;
        try {
            saveLevel = WorldServer.class.getMethod("func_73042_a", new Class[0]);
            saveLevel.setAccessible(true);
            extraSaveChunkData = cls.getMethod("func_73243_a", new Class[] { Chunk.class });
            extraSaveChunkData.setAccessible(true);
            saveChunk = cls.getMethod("func_73242_b", new Class[] { Chunk.class });
            saveChunk.setAccessible(true);
        } catch (SecurityException e) {
        } catch (NoSuchMethodException e) {
        }
        if (saveChunk == null) {
            log.severe("saveChunk method not found - SlowSaveAll disabled");
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
        if (saveChunk == null) return;
        /* Register tick handler */
        if(!tickregistered) {
            TickRegistry.registerTickHandler(handler, Side.SERVER);
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
    
    private class TickHandler implements ITickHandler {
        private boolean stopped;
        private int ticksUntilSave;
        private List<WorldServer> worldsToDo;
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
        @Override
        public String getLabel() {
            return "SlowSaveAll";
        }

        @Override
        public void tickEnd(EnumSet<TickType> type, Object... arg1) {
            if (stopped) return;
            // Ignore non-server ticks
            if (!type.contains(TickType.SERVER)) {
                return;
            }
            if (ticksUntilSave > 0) {   // Still ticking?
                ticksUntilSave--;
                if (ticksUntilSave > 0) {
                    return;
                }
            }
            // Process save
            MinecraftServer server = MinecraftServer.getServer();
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
                        log.info("Starting save for world '" + activeWorld.getWorldInfo().getWorldName() + "'");
                        // And save world data
                        try {
                            saveLevel.invoke(activeWorld, new Object[0]);
                        } catch (IllegalArgumentException e) {
                        } catch (IllegalAccessException e) {
                        } catch (InvocationTargetException e) {
                        }
                        return;
                    }
                    else {
                        activeWorld = null;
                    }
                }
            }
            // Process active world
            boolean flag = activeWorld.canNotSave;
            activeWorld.canNotSave = false;
            for (int i = 0; (i < chunksPerTick) && (chunkIdx < chunkX.length); chunkIdx++) {
                if (activeWorld.theChunkProviderServer.chunkExists(chunkX[chunkIdx], chunkZ[chunkIdx])) {   // If still loaded
                    Chunk c = activeWorld.getChunkFromChunkCoords(chunkX[chunkIdx], chunkZ[chunkIdx]);
                    if ((c != null) && (saveChunk != null) && (c.needsSaving(true))) {
                        try {
                            extraSaveChunkData.invoke(activeWorld.theChunkProviderServer, c);
                            saveChunk.invoke(activeWorld.theChunkProviderServer, c);
                            chunkCnt++;
                        } catch (IllegalArgumentException e) {
                        } catch (IllegalAccessException e) {
                        } catch (InvocationTargetException e) {
                        }
                    }
                    i++;
                }
            }
            activeWorld.canNotSave = flag;  // Restore save state
            
            if (chunkIdx >= chunkX.length) { // Done?
                MinecraftForge.EVENT_BUS.post(new WorldEvent.Save(activeWorld));
                log.info("Save of world '" + activeWorld.getWorldInfo().getWorldName() + "' completed - " + chunkCnt + " saved");
                activeWorld = null;
                chunkX = chunkZ = null;
                chunkIdx = 0;
            }
        }

        @Override
        public void tickStart(EnumSet<TickType> arg0, Object... arg1) {
        }

        private final EnumSet<TickType> ticktype = EnumSet.of(TickType.SERVER);
        
        @Override
        public EnumSet<TickType> ticks() {
            return ticktype;
        }
    }
}
