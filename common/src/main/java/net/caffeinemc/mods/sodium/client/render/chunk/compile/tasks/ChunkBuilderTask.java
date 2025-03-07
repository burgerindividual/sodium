package net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.UniqueSectionRef;
import net.caffeinemc.mods.sodium.client.util.SectionPosUtil;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.CombinedCameraPos;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;

/**
 * Build tasks are immutable jobs (with optional prioritization) which contain all the necessary state to perform
 * chunk mesh updates or quad sorting off the main thread.
 *
 * When a task is constructed on the main thread, it should copy all the state it requires in order to complete the task
 * without further synchronization. The task will then be scheduled for async execution on a thread pool.
 *
 * After the task completes, it returns a "build result" which contains any computed data that needs to be handled
 * on the main thread.
 */
public abstract class ChunkBuilderTask<OUTPUT extends BuilderTaskOutput> implements CombinedCameraPos {
    protected final UniqueSectionRef section;
    protected final int submitTime;
    protected final Vector3dc absoluteCameraPos;
    protected final Vector3fc cameraPos;

    /**
     * Constructs a new build task for the given chunk and converts the absolute camera position to a relative position. While the absolute position is stored as a double vector, the relative position is stored as a float vector.
     * 
     * @param section           The chunk section to build
     * @param time              The frame in which this task was created
     * @param absoluteCameraPos The absolute position of the camera
     */
    public ChunkBuilderTask(UniqueSectionRef section, int time, Vector3dc absoluteCameraPos) {
        this.section = section;
        this.submitTime = time;
        this.absoluteCameraPos = absoluteCameraPos;

        var sectionPos = section.pos();
        var x = SectionPosUtil.unpackX(sectionPos);
        var y = SectionPosUtil.unpackY(sectionPos);
        var z = SectionPosUtil.unpackZ(sectionPos);
        this.cameraPos = new Vector3f(
                (float) (absoluteCameraPos.x() - (double) SectionPosUtil.originCoord(x)),
                (float) (absoluteCameraPos.y() - (double) SectionPosUtil.originCoord(y)),
                (float) (absoluteCameraPos.z() - (double) SectionPosUtil.originCoord(z)));
    }

    /**
     * Executes the given build task asynchronously from the calling thread. The implementation should be careful not
     * to access or modify global mutable state.
     *
     * @param context            The context to use for building this chunk
     * @param cancellationToken The cancellation source which can be used to query if the task is cancelled
     * @return The build result of this task, containing any data which needs to be uploaded on the main-thread, or null
     *         if the task was cancelled.
     */
    public abstract OUTPUT execute(ChunkBuildContext context, CancellationToken cancellationToken);

    public abstract int getEffort();

    @Override
    public Vector3fc getRelativeCameraPos() {
        return this.cameraPos;
    }

    @Override
    public Vector3dc getAbsoluteCameraPos() {
        return this.absoluteCameraPos;
    }
}
