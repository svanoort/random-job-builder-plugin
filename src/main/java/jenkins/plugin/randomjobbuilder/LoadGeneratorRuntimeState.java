package jenkins.plugin.randomjobbuilder;

/** Encapsulates all the stateful information about a load generator.
 *  Used by controllers, and may be extended by load generators.
 */
public class LoadGeneratorRuntimeState {
    private boolean running = false;
    private LoadTestMode loadTestMode = LoadTestMode.IDLE;
    private int queuedTaskCount = 0;
    private int runningTaskCount = 0;

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public LoadTestMode getLoadTestMode() {
        return loadTestMode;
    }

    public void setLoadTestMode(LoadTestMode loadTestMode) {
        this.loadTestMode = loadTestMode;
    }

    public int getQueuedTaskCount() {
        return queuedTaskCount;
    }

    public void setQueuedTaskCount(int queuedTaskCount) {
        this.queuedTaskCount = queuedTaskCount;
    }

    public int getRunningTaskCount() {
        return runningTaskCount;
    }

    public void setRunningTaskCount(int runningTaskCount) {
        this.runningTaskCount = runningTaskCount;
    }

    public boolean isActive() {
        return getLoadTestMode() == LoadTestMode.RAMP_UP || getLoadTestMode() == LoadTestMode.LOAD_TEST;
    }

    /** Return running + queued tasks */
    public int getActiveCount() {
        return getRunningTaskCount()+getRunningTaskCount();
    }
}
