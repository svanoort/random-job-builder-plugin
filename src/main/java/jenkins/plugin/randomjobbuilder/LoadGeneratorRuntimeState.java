package jenkins.plugin.randomjobbuilder;

import hudson.model.Run;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Encapsulates all the stateful information about a load generator.
 *  Used by controllers, and may be extended by load generators.
 */
public class LoadGeneratorRuntimeState {
    private boolean running = false;
    private LoadTestMode loadTestMode = LoadTestMode.IDLE;
    private int queuedTaskCount = 0;
    private List<Run> runs = new ArrayList<Run>();

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

    public void addQueuedTask() {
        this.queuedTaskCount++;
    }

    public void removeQueuedTask() {
        if (this.queuedTaskCount > 0) {
            this.queuedTaskCount--;
        }
    }

    public void setQueuedTaskCount(int queuedTaskCount) {
        this.queuedTaskCount = queuedTaskCount;
    }

    public int getRunningTaskCount() {
        return runs.size();
    }

    public boolean isActive() {
        return getLoadTestMode() == LoadTestMode.RAMP_UP || getLoadTestMode() == LoadTestMode.LOAD_TEST;
    }

    /** Return running + queued tasks */
    public int getTotalTaskCount() {
        return getRunningTaskCount()+getRunningTaskCount();
    }

    public List<Run> getRuns() {
        return Collections.unmodifiableList(runs);
    }

    public void addRun(Run r) {
        this.runs.add(r);
    }

    public  boolean removeRun(Run r) {
        return this.runs.remove(r);
    }

    public boolean containsRun(Run r) {
        return this.runs.contains(r);
    }
}
