package jenkins.plugin.randomjobbuilder;

import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Failure;
import hudson.model.Job;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

/** Base for all load generators that run jobs.
 *  <strong>Implementation note: implementation MUST</strong> implement a {@link Descriptor} extending {@link DescriptorBase}
 *  and MAY implement {@link #initializeState()} for custom load generation.
 */
@ExportedBean
public abstract class LoadGenerator extends AbstractDescribableImpl<LoadGenerator> implements ExtensionPoint {
    /** Identifies the generator for causes */
    @Nonnull
    String generatorId;

    /** Human-visible name to use */
    @Nonnull
    String shortName;

    /** User-readable description */
    @CheckForNull
    String description;

    public LoadGenerator() {
        this.generatorId = DescriptorBase.getNewGeneratorId();
        this.shortName = this.generatorId;
    }

    public LoadGenerator(@Nonnull String generatorId) {
        this.generatorId = generatorId;
        this.shortName = generatorId;
    }

    @Nonnull
    @Exported
    public String getGeneratorId() {
        return generatorId;
    }

    @DataBoundSetter
    @Restricted(NoExternalUse.class)
    public void setGeneratorId(final String generatorId) {
        if (StringUtils.isEmpty(generatorId)) {
            this.generatorId = DescriptorBase.getNewGeneratorId();
        } else {
            Jenkins.checkGoodName(generatorId);
            this.generatorId = generatorId;
        }
    }

    @Nonnull
    @Exported
    public String getShortName() {
        return shortName;
    }

    @DataBoundSetter
    @Restricted(NoExternalUse.class)
    public void setShortName(final String shortName) {
        if (StringUtils.isEmpty(shortName)) {
            throw new IllegalArgumentException("Short name is empty and may not be");
        }
        Jenkins.checkGoodName(shortName);
        this.shortName = shortName;
    }

    @Exported
    @CheckForNull
    public String getDescription() {
        return this.description;
    }

    /**
     * Set the description - may be null
     * @param desc Description for generator
     */
    @Restricted(NoExternalUse.class)
    @DataBoundSetter
    public void setDescription(final String desc) {
        this.description = desc;
    }

    /** Given current number of runs, launch more if needed.  Return number to fire now, or &lt;= 0 for none
     *  This allows for ramp-up behavior.
     */
    public abstract int getRunsToLaunch(@Nonnull LoadGeneratorRuntimeState runtimeState);

    /** FIXME use the runtime state somehow */
    public abstract List<Job> getCandidateJobs(@Nonnull LoadGeneratorRuntimeState runtimeState);

    /** Begin running load test and then switch to full load after any ramp-up time, firing {@link GeneratorControllerListener#onGeneratorStarted(LoadGenerator)}
     *  Implementations should provide logic for this in {@link #startInternal(LoadGeneratorRuntimeState)}
     */
    public final void start(@Nonnull LoadGeneratorRuntimeState runtimeState) {
        GeneratorControllerListener.fireGeneratorStarted(this, runtimeState);
        LoadTestMode lt = startInternal(runtimeState);
        runtimeState.setLoadTestMode(lt);
    }

    /** Provide the actual implementation of state change in the start method and return new state
     *  @return {@link LoadTestMode} phase as we transition to starting load test, i.e. LOAD_TEST or RAMP_UP
     */
    protected abstract LoadTestMode startInternal(@Nonnull LoadGeneratorRuntimeState runtimeState);

    /**
     * Start shutting down the load test and then stop it after ramp-down, firing {@link GeneratorControllerListener#onGeneratorStopped(LoadGenerator)} (LoadGenerator)}
     *  Implementations should provide logic for this in {@link #stopInternal(LoadGeneratorRuntimeState)}
     */
    public final void stop(@Nonnull LoadGeneratorRuntimeState runtimeState) {
        GeneratorControllerListener.fireGeneratorStopped(this, runtimeState);
        LoadTestMode lt = stopInternal(runtimeState);
        runtimeState.setLoadTestMode(lt);
    }

    /** Provide the actual implementation of state change in the stop method and return new state
     * @return {@link LoadTestMode} phase as we transition to starting load test, i.e. LOAD_TEST or RAMP_UP
     */
    protected abstract LoadTestMode stopInternal(@Nonnull LoadGeneratorRuntimeState runtimeState);

    /** Create the initial state, and override if you need fancier variants. */
    public LoadGeneratorRuntimeState initializeState() {
        return new LoadGeneratorRuntimeState();
    }

    /** Descriptors MUST extend this and implement {@link #initializeState()} if they need more complex state. */
    @Extension
    public static class DescriptorBase extends Descriptor<LoadGenerator> {

        /** Creates a new, generally unique generator ID */
        @Nonnull
        public static String getNewGeneratorId() {
            return UUID.randomUUID().toString();
        }

        @Override
        public String getDisplayName() {
            return "";
        }

        public FormValidation doCheckGeneratorId(@QueryParameter String generatorId) {
            if (StringUtils.isEmpty(generatorId)) {
                return FormValidation.error("Generator ID \"{0}\" is empty!");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckShortName(@QueryParameter String shortName) {
            if (StringUtils.isEmpty(shortName)) {
                return FormValidation.error("Short name is empty and may not be");
            }
            try {
                Jenkins.checkGoodName(shortName);
            } catch (Failure fail) {
                return FormValidation.error(fail.getMessage());
            }
            return FormValidation.ok();
        }
    }

    @Override
    public int hashCode() {
        return getGeneratorId().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (o == null || !(o instanceof LoadGenerator)) {
            return false;
        }
        return this.getGeneratorId().equals(((LoadGenerator)o).getGeneratorId());
    }
}
