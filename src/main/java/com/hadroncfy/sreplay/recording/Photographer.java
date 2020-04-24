package com.hadroncfy.sreplay.recording;

import java.io.File;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

import com.hadroncfy.sreplay.Main;
import com.hadroncfy.sreplay.config.TextRenderer;
import com.hadroncfy.sreplay.mixin.PlayerManagerAccessor;
import com.mojang.authlib.GameProfile;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTask;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.dimension.DimensionType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class Photographer extends ServerPlayerEntity implements ISizeLimitExceededListener {
    private static final GameMode MODE = GameMode.SPECTATOR;
    private static final Logger LOGGER = LogManager.getLogger();
    private long sizeLimit;
    private boolean autoReconnect;
    private int reconnectCount = 0;
    private Timer tablistUpdater;
    private HackyClientConnection connection;
    private Recorder recorder;

    public Photographer(MinecraftServer server, ServerWorld world, GameProfile profile, ServerPlayerInteractionManager im, long sizeLimit, boolean autoReconnect){
        super(server, world, profile, im);
        this.sizeLimit = sizeLimit;
        this.autoReconnect = autoReconnect;
    }

    public static Photographer create(String name, MinecraftServer server, DimensionType dim, Vec3d pos, long sizeLimit, boolean autoReconnect){
        GameProfile profile = new GameProfile(PlayerEntity.getOfflinePlayerUuid(name), name);
        ServerWorld world = server.getWorld(dim);
        ServerPlayerInteractionManager im = new ServerPlayerInteractionManager(world);
        Photographer ret = new Photographer(server, world, profile, im, sizeLimit, autoReconnect);
        ret.updatePosition(pos.x, pos.y, pos.z);
        ((PlayerManagerAccessor)server.getPlayerManager()).getSaveHandler().savePlayerData(ret);
        return ret;
    }

    public void connect(File outputPath) throws IOException {
        recorder = new Recorder(getGameProfile(), server, outputPath, sizeLimit);
        connection = new HackyClientConnection(NetworkSide.CLIENTBOUND, recorder);
        
        recorder.setOnSizeLimitExceededListener(this);
        recorder.start();

        tablistUpdater = new Timer();
        tablistUpdater.scheduleAtFixedRate(new TimerTask(){
            @Override
            public void run() {
                if (!recorder.isPaused()){
                    server.getPlayerManager().sendToAll(new PlayerListS2CPacket(Action.UPDATE_DISPLAY_NAME, Photographer.this));
                }
            }
        }, 1000, 1000);

        setHealth(20.0F);
        removed = false;
        server.getPlayerManager().onPlayerConnect(connection, this);
        interactionManager.setGameMode(MODE);
        getServerWorld().getChunkManager().updateCameraPosition(this);
    }

    @Override
    public void tick() {
        if (getServer().getTicks() % 10 == 0){
            networkHandler.syncWithPlayerPosition();
            getServerWorld().getChunkManager().updateCameraPosition(this);
        }
        super.tick();
        method_14226();// playerTick
    }

    @Override
    public Text method_14206() {
        if (recorder == null){
            return null;
        }
        long duration = recorder.getRecordedTime() / 1000;
        long sec = duration % 60;
        duration /= 60;
        long min = duration % 60;
        duration /= 60;
        long hour = duration;
        String time;
        if (hour == 0){
            time = String.format("%d:%02d", min, sec);
        }
        else {
            time = String.format("%d:%02d:%02d", hour, min, sec);
        }
        String size = String.format("%.2f", recorder.getRecordedBytes() / 1024F / 1024F) + "M";
        if (sizeLimit != -1){
            size += "/" + String.format("%.2f", sizeLimit / 1024F / 1024F) + "M";
        }
        Text ret = new LiteralText(getGameProfile().getName()).setStyle(new Style().setItalic(true).setColor(Formatting.AQUA));
        if (autoReconnect){
            ret.append(new LiteralText(" [Auto]").setStyle(new Style().setItalic(false).setColor(Formatting.DARK_PURPLE)));
        }
        ret.append(new LiteralText(" " + time).setStyle(new Style().setItalic(false).setColor(Formatting.GREEN)))
            .append(new LiteralText(" " + size).setStyle(new Style().setItalic(false).setColor(Formatting.GREEN)));
        return ret;
    }

    public void tp(DimensionType dim, double x, double y, double z) {
        if (dimension != dim){
            ServerWorld oldMonde = server.getWorld(dimension), nouveau = server.getWorld(dim);
            oldMonde.removePlayer(this);
            removed = false;
            setWorld(nouveau);
            server.getPlayerManager().sendWorldInfo(this, nouveau);
            interactionManager.setWorld(nouveau);
            networkHandler.sendPacket(new PlayerRespawnS2CPacket(dim, nouveau.getGeneratorType(), MODE));
            nouveau.method_18211(this);
        }
        requestTeleport(x, y, z);
    }

    public Recorder getRecorder(){
        return recorder;
    }

    public void kill(){
        kill(null, true);
    }

    public void kill(Runnable r, boolean async) {
        if (tablistUpdater != null){
            tablistUpdater.cancel();
            tablistUpdater = null;
        }
        if (recorder != null){
            recorder.stop();
            recorder.saveRecording();
            recorder = null;
        }
        Runnable task =  () -> {
            if (networkHandler != null){
                networkHandler.onDisconnected(new LiteralText("Killed"));
            }
            if (r != null){
                r.run();
            }
        };
        if (async){
            server.send(new ServerTask(server.getTicks(), task));
        }
        else {
            task.run();
        }
    }

    @Override
    public boolean method_14239() {
        return false;
    }

    public void onPause() {
        if (recorder != null){
            recorder.setPaused();
        }        
    }

    @Override
    public void onSizeLimitExceeded(long size) {
        final File out = recorder.getOutputPath();
        kill(() -> {
            if (autoReconnect){
                String s = out.getAbsolutePath();
                File out2 = new File(s.substring(0, s.length() - 5) + String.format("_%d.mcpr", ++reconnectCount));
                try {
                    connect(out2);
                } catch (IOException e) {
                    server.getPlayerManager().broadcastChatMessage(TextRenderer.render(Main.getFormats().failedToStartRecording, getGameProfile().getName()), true);
                    e.printStackTrace();
                }
            }
        }, true);
    }

    public void setSizeLimit(long l){
        sizeLimit = l;
        if (recorder != null){
            recorder.setSizeLimit(l);
        }
    }
    public void setAutoReconnect(boolean b){
        autoReconnect = b;
    }
}