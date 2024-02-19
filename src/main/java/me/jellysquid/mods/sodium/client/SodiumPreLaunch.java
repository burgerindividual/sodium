package me.jellysquid.mods.sodium.client;

import org.slf4j.LoggerFactory;

import me.jellysquid.mods.sodium.client.compatibility.checks.EarlyDriverScanner;
import me.jellysquid.mods.sodium.client.compatibility.workarounds.Workarounds;
import me.jellysquid.mods.sodium.ffi.core.CoreLib;
import me.jellysquid.mods.sodium.client.compatibility.environment.probe.GraphicsAdapterProbe;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public class SodiumPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        GraphicsAdapterProbe.findAdapters();
        EarlyDriverScanner.scanDrivers();
        Workarounds.init();

        try {
            LoggerFactory.getLogger("plug").error("plug.");
            Thread.sleep(3000);
        } catch (InterruptedException e) {
        }

        CoreLib.init();
    }
}
