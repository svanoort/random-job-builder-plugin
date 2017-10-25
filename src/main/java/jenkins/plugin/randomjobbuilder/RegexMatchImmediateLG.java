package jenkins.plugin.randomjobbuilder;

import com.google.common.base.Predicate;
import hudson.Extension;
import hudson.model.Job;
import hudson.util.FormValidation;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.regex.Pattern;

/** LoadGenerator that selects jobs matching a regex and starts load immediately */
public class RegexMatchImmediateLG extends LoadGenerator {

    private String jobNameRegex = null;

    private int concurrentRunCount = 1;

    @Override
    public List<Job> getCandidateJobs(@Nonnull LoadGeneratorRuntimeState runtimeState) {
        return LoadGeneration.filterJobsByCondition(new JobNameFilter(jobNameRegex));
    }

    @Override
    protected LoadTestMode startInternal(@Nonnull LoadGeneratorRuntimeState runtimeState) {
        return LoadTestMode.LOAD_TEST;
    }

    @Override
    public LoadTestMode stopInternal(@Nonnull LoadGeneratorRuntimeState runtimeState) {
        return LoadTestMode.IDLE;
    }


    @Exported
    public String getJobNameRegex() {
        return jobNameRegex;
    }

    @DataBoundSetter
    public RegexMatchImmediateLG setJobNameRegex(String jobNameRegex) {
        this.jobNameRegex = jobNameRegex;
        return this;
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
    public RegexMatchImmediateLG setConcurrentRunCount(int concurrentRunCount) {
        this.concurrentRunCount = concurrentRunCount;
        return this;
    }

    @DataBoundSetter
    public RegexMatchImmediateLG setGeneratorId(@Nonnull String generatorId) {
        super.setGeneratorId(generatorId);
        return this;
    }

    @Override
    public int getRunsToLaunch(@Nonnull LoadGeneratorRuntimeState runtimeState) {
        if (runtimeState.isActive() && getConcurrentRunCount() > 0) {
            return getConcurrentRunCount()-runtimeState.getTotalTaskCount();
        }
        return 0;
    }

    @DataBoundConstructor
    public RegexMatchImmediateLG() {

    }

    public RegexMatchImmediateLG(@CheckForNull String jobNameRegex, int concurrentRunCount) {
        setJobNameRegex(jobNameRegex);
        this.concurrentRunCount = concurrentRunCount;
    }

    @Extension
    public static class DescriptorImpl extends DescriptorBase {

        @Override
        public String getDisplayName() {
            return "Jobs by regex match, immediate load";
        }

        public FormValidation doCheckJobNameRegex(@QueryParameter String jobNameRegex) {
            if (StringUtils.isEmpty(jobNameRegex)) {
                return FormValidation.ok();
            }

            try {
                List<Job> filtered = LoadGeneration.filterJobsByCondition(new JobNameFilter(jobNameRegex));
                if (filtered.size() == 0) {
                    return FormValidation.warning("No jobs match condition");
                } else {
                    return FormValidation.ok(filtered.size()+" jobs match pattern");
                }
            } catch (Exception ex) {
                return FormValidation.error(ex, "Exception testing pattern");
            }
        }
    }

    /** Filters candidate jobs by pattern matching on fullName */
    static class JobNameFilter implements Predicate<Job> {
        String nameMatchRegex = null;
        Pattern namePattern = null;

        public JobNameFilter(String nameMatchRegex) {
            if (!StringUtils.isEmpty(nameMatchRegex)) {
                this.nameMatchRegex = nameMatchRegex;
                this.namePattern = Pattern.compile(nameMatchRegex);
            }
        }

        @Override
        public boolean apply(@Nullable Job input) {
            if (input == null) {
                return false;
            } else if (StringUtils.isEmpty(nameMatchRegex)) {
                return true;
            } else {
                return namePattern.matcher(input.getFullName()).matches();
            }
        }
    }
}
