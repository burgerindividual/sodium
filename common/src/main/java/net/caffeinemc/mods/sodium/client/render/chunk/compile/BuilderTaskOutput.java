package net.caffeinemc.mods.sodium.client.render.chunk.compile;

public abstract class BuilderTaskOutput {
    public final UniqueSectionRef section;
    public final int submitTime;

    public BuilderTaskOutput(UniqueSectionRef section, int submitTime) {
        this.section = section;
        this.submitTime = submitTime;
    }

    public void destroy() {
    }
}
