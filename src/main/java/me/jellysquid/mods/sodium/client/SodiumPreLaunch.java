package me.jellysquid.mods.sodium.client;

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
        CoreLib.init();

        // test CoreLib
        long pGraph = CoreLib.graphCreate();
        CoreLib.graphSetSection(
                pGraph,
                5,
                5,
                5,
                0b111111_111111_111111_111111_111111_111111l,
                (byte) 0b111);
        CoreLib.graphRemoveSection(pGraph, 5, 5, 5);
        CoreLib.graphDelete(pGraph);
    }
}
