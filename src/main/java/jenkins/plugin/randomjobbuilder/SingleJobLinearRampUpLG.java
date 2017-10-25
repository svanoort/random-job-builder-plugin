package jenkins.plugin.randomjobbuilder;

import hudson.Extension;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Job;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

/** LoadGenerator that lets you pick a single job and linear ramps-up load */
public class SingleJobLinearRampUpLG extends LoadGenerator {

    private String jobName = null;

    private int concurrentRunCount = 1;

    private long rampUpMillis = 0;

    private boolean useJitter = true;

    @Exported
    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    /** Compute desired runs to launch */
    static int computeDesiredRuns(long currentTime, long startTimeMillis, long rampUpMillis, int finalConcurrentLoad) {
        if (currentTime >= (startTimeMillis +rampUpMillis)) {
            return finalConcurrentLoad;
        } else if (rampUpMillis <=0) {
            return finalConcurrentLoad;
        } else if (currentTime >= startTimeMillis) {
            double fractionDone = ((double)(currentTime- startTimeMillis)/(double)(rampUpMillis));
            return (int)(Math.round((double)(finalConcurrentLoad)*fractionDone));
        } else {
            return 0;
        }
    }

    /** Based on linear ramp-up, compute how many runs to launch */
    public int computeRunsToLaunch(long currentTime, long startTimeMillis, long rampUpMillis, int finalConcurrentLoad, boolean useJitter, int currentRuns) {
        int target = computeDesiredRuns(currentTime, startTimeMillis, rampUpMillis, finalConcurrentLoad);
        int delta = target-currentRuns;
        if (delta <= 0) {
            return 0;
        }

        if (useJitter) {
            // On average will launch the targetted number of runs, but will randomly launch up to 2x as many
            // And as little as none
            return (int)(Math.round(Math.random()*2.0*(double)delta));
        } else {
            return delta;
        }
    }

    /** Computes expected load */
    static class TimedLoadGeneratorRuntimeState extends LoadGeneratorRuntimeState {
        long startTimeMillis = -1;

        public long getStartTimeMillis() {
            return startTimeMillis;
        }

        public void setStartTimeMillis(long startTimeMillis) {
            this.startTimeMillis = startTimeMillis;
        }
    }

    @Override
    public List<Job> getCandidateJobs(@Nonnull LoadGeneratorRuntimeState runtimeState) {
        Job j = Jenkins.getActiveInstance().getItemByFullName(getJobName(), Job.class);
        if (j == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(j);
    }

    @Override
    protected LoadTestMode startInternal(@Nonnull LoadGeneratorRuntimeState runtimeState) {
        if (!(runtimeState instanceof TimedLoadGeneratorRuntimeState)) {
            throw new IllegalStateException("Mismatched runtime state for load generator "+getShortName()+" ID: "+getGeneratorId());
        }
        TimedLoadGeneratorRuntimeState timedState = (TimedLoadGeneratorRuntimeState)runtimeState;

        if (runtimeState.getLoadTestMode() == LoadTestMode.IDLE || runtimeState.getLoadTestMode() == LoadTestMode.RAMP_DOWN) {
            timedState.setStartTimeMillis(System.currentTimeMillis());

            // If we have no ramp-up time, then ramp up immediately
            return (this.rampUpMillis > 0) ? LoadTestMode.RAMP_UP : LoadTestMode.LOAD_TEST;
        } else { // NO-OP
            return runtimeState.getLoadTestMode();
        }
    }

    @Override
    public LoadTestMode stopInternal(@Nonnull LoadGeneratorRuntimeState runtimeState) {
        return LoadTestMode.IDLE;
    }

    /**
     * Get the intended number of concurrent runs at once
     * @return &lt; 0 or (or negative) will result in no runs triggered, or positive integer for intended count
     */
    @Exported
    public int getConcurrentRunCount() {
        return concurrentRunCount;
    }

    @DataBoundSetter
    public void setConcurrentRunCount(int concurrentRunCount) {
        this.concurrentRunCount = concurrentRunCount;
    }

    @DataBoundSetter
    public void setGeneratorId(@Nonnull String generatorId) {
        super.setGeneratorId(generatorId);
    }

    @Override
    public int getRunsToLaunch(@Nonnull LoadGeneratorRuntimeState runtimeState) {
        if (runtimeState.isActive()) {
            return 0;
        }

        if (!(runtimeState instanceof TimedLoadGeneratorRuntimeState)) {
            throw new IllegalStateException("Mismatched runtime state for load generator "+getShortName()+" ID: "+getGeneratorId());
        }
        TimedLoadGeneratorRuntimeState timedState = (TimedLoadGeneratorRuntimeState)runtimeState;

        long currentTime = System.currentTimeMillis();
        if (currentTime > (timedState.startTimeMillis+rampUpMillis)) {
            if (runtimeState.getLoadTestMode() != LoadTestMode.LOAD_TEST) {
                // Engines already at full speed cap'n I canna go any faster
                runtimeState.setLoadTestMode(LoadTestMode.LOAD_TEST);
            }
        }
        return computeRunsToLaunch(currentTime, timedState.getStartTimeMillis(), getRampUpMillis(), getConcurrentRunCount(), isUseJitter(), runtimeState.getTotalTaskCount());
    }

    @DataBoundConstructor
    public SingleJobLinearRampUpLG(String jobName) {
        this.jobName = jobName;
    }

    @Exported
    public long getRampUpMillis() {
        return rampUpMillis;
    }

    @DataBoundSetter
    public void setRampUpMillis(long rampUpMillis) {
        this.rampUpMillis = rampUpMillis;
    }

    /** If true, we use randomization in the number of runs we launch, anywhere from 0 to 2x number needed to hit goal amt */
    @Exported
    public boolean isUseJitter() {
        return useJitter;
    }

    @DataBoundSetter
    public void setUseJitter(boolean useJitter) {
        this.useJitter = useJitter;
    }

    @Extension
    public static class DescriptorImpl extends DescriptorBase {
        /**
         * Provides autocompletion for the jobName when looking up jobs
         * @param value
         *      The text that the user entered.
         */
        public AutoCompletionCandidates doAutoCompleteJobName(@QueryParameter String value) {
            AutoCompletionCandidates c = new AutoCompletionCandidates();
            if (StringUtils.isEmpty(value) || value.length() < 2) {
                return c;
            }
            for (Job job : Jenkins.getActiveInstance().getItems(Job.class)) {
                if (job.getFullName().contains(value)) {
                    c.add(job.getFullName());
                }
            }
            return c;
        }

        @Override
        public LoadGeneratorRuntimeState initializeState() {
            return new TimedLoadGeneratorRuntimeState();
        }

        @Override
        public String getDisplayName() {
            return "Single job load generator, with load ramp-up";
        }

        public FormValidation doCheckJobName(@QueryParameter String jobName) {
            if (StringUtils.isEmpty(jobName)) {
                return FormValidation.ok();
            }

            if (Jenkins.getActiveInstance().getItemByFullName(jobName, Job.class) == null) {
                return FormValidation.error("No job with that name exists");
            }

            return FormValidation.ok();
        }
    }
}
