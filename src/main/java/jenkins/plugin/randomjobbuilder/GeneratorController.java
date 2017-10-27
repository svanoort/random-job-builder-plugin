package jenkins.plugin.randomjobbuilder;

import com.google.common.collect.Sets;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.PeriodicWork;
import hudson.model.Run;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.security.ACL;
import hudson.util.DescribableList;
import hudson.util.XStream2;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controls load generation for a set of registered set of LoadGenerators
 *
 * <ul>
 *   <li>Uses listeners to manage tasks going from queue to becoming running jobs</li>
 *   <li>Manage load by starting new jobs as needed for loak</li>
 *   <li>Provides way to kill all tasks for a load generator</li>
 *   <li>Provides synchronization and update of the generator list with on-the-fly modifications</li>
 * </ul>
 *
 */
@Extension
public final class GeneratorController extends RunListener<Run> implements Saveable {

    public static final long RECURRENCE_PERIOD_MILLIS = 2000L;

    /** Map {@link LoadGenerator#generatorId} to the system-configured {@link LoadGenerator} instances.
     *
     */
    DescribableList<LoadGenerator, LoadGenerator.DescriptorBase> registeredGenerators = new DescribableList<LoadGenerator, LoadGenerator.DescriptorBase>(this);

    /** Stores the runtime information about currently active load generators, using the {@link LoadGenerator#generatorId} as an identifier */
    transient ConcurrentHashMap<String, LoadGeneratorRuntimeState> runtimeState = new ConcurrentHashMap<String, LoadGeneratorRuntimeState>();

    /** Returns a snapshot of current registered load generator */
    public DescribableList<LoadGenerator, LoadGenerator.DescriptorBase> getRegisteredGenerators() {
        return registeredGenerators;
    }

    public void setRegisteredGenerators(DescribableList<LoadGenerator, LoadGenerator.DescriptorBase> gens) {
        syncGenerators(gens);
    }

    /** Add new load generator, or update existing one if one matches the ID */
    public void addOrUpdateLoadGenerator(@Nonnull LoadGenerator gen) {
        synchronized (gen) {
            int matchId = -1;
            for (int i=0; i<registeredGenerators.size(); i++) {
                if (gen.getGeneratorId().equals(registeredGenerators.get(i))) {
                    matchId = i;
                    break;
                }
            }
            if (matchId == -1) {  // Don't add duplicate generators
                GeneratorControllerListener.fireGeneratorAdded(gen);
                registeredGenerators.add(gen);
            } else {
                registeredGenerators.set(matchId, gen);
            }
            runtimeState.putIfAbsent(gen.getGeneratorId(), gen.initializeState());
        }
    }

    /**
     * Unregister the generator and stop all jobs and tasks from it
     * @param generator Generator to unregister/remove
     */
    public void unregisterAndStopGenerator(@Nonnull LoadGenerator generator) {
        LoadGeneratorRuntimeState state = getRuntimeState(generator);
        if (state == null) {
            registeredGenerators.remove(generator);
            return;
        }

        synchronized (state) {
            generator.stop(state);
            this.stopAbruptly(generator);
            registeredGenerators.remove(generator);
            runtimeState.remove(generator.getGeneratorId());
        }
        GeneratorControllerListener.fireGeneratorRemoved(generator);
    }

    /** Find generator by its unique ID or return null if not registered
     * @param generatorId ID of registered generator
     */
    @CheckForNull
    public LoadGenerator getRegisteredGeneratorbyId(@Nonnull String generatorId) {
        for (LoadGenerator lg : registeredGenerators) {
            if (lg.getGeneratorId().equals(generatorId)) {
                return lg;
            }
        }
        return null;
    }

    /** Find generator by its unique ID or return null if not registered.
     * @param shortName ID of registered generator
     */
    @CheckForNull
    public LoadGenerator getRegisteredGeneratorbyShortName(@Nonnull String shortName) {
        for (LoadGenerator lg : registeredGenerators) {
            if (lg.getShortName().equals(shortName)) {
                return lg;
            }
        }
        return null;
    }

    /** Get runtime state for generator or null if not registered. */
    @CheckForNull
    public LoadGeneratorRuntimeState getRuntimeState(@Nonnull String generatorId) {
        return runtimeState.get(generatorId);
    }

    /** Convenience method, get runtime state directly using the generator or null if not stored. */
    @CheckForNull
    public LoadGeneratorRuntimeState getRuntimeState(@Nonnull LoadGenerator gen) {
        return getRuntimeState(gen.getGeneratorId());
    }

    /** Ensure that the registered generators match input set, registering any new ones and unregistering ones not in input,
     *  which will kill any jobs or tasks linked to them
     *  @param generators List of generators to add/remove/update
     */
     synchronized void syncGenerators(@Nonnull List<LoadGenerator> generators) {
        Set<LoadGenerator> registeredSet = new HashSet<LoadGenerator>(registeredGenerators);
        Set<LoadGenerator> inputSet = new HashSet<LoadGenerator>(generators);

        // Generators that are registered but not in input have been removed
        for (LoadGenerator gen : Sets.difference(registeredSet, inputSet)) {
            unregisterAndStopGenerator(gen);
        }

        // Add entries for new generators
        for (LoadGenerator gen : Sets.difference(inputSet, registeredSet)) {
            addOrUpdateLoadGenerator(gen);
        }

        for (LoadGenerator gen : Sets.intersection(inputSet, registeredSet)) {

        }
    }

    /**
     * Track adding a queued task for the given generator
     * @param generator Generator that created the task
     */
    @SuppressFBWarnings(value = "AT_OPERATION_SEQUENCE_ON_CONCURRENT_ABSTRACTION", justification = "Should be covered by synchronization in invokers.")
    void addQueueItem(@Nonnull LoadGenerator generator) {
        LoadGeneratorRuntimeState state = runtimeState.get(generator.getGeneratorId());

        if (state == null) {
            state = new LoadGeneratorRuntimeState();
            state.setQueuedTaskCount(1);
            runtimeState.put(generator.getGeneratorId(), state);
        } else {
            synchronized (state) {
                state.setQueuedTaskCount(state.getQueuedTaskCount() + 1);
            }
        }
    }

    /**
     * Decrement queued item count for generator
     * @param generator Generator that generated the task
     */
     void removeQueueItem(@Nonnull LoadGenerator generator) {
        LoadGeneratorRuntimeState state = runtimeState.get(generator.getGeneratorId());
        if (state != null) {
            synchronized (state) {
                if (state.getQueuedTaskCount() > 0) {
                    state.setQueuedTaskCount(state.getQueuedTaskCount() - 1);
                }
            }
        }
    }

    /** Triggers runs as requested by the LoadGenerator for a REGISTERED generator only
     *  @param gen
     *  @return Number of runs triggered as a result of load check
     */
    public int checkLoadAndTriggerRuns(@Nonnull LoadGenerator gen) {
        LoadGenerator registeredGen = getRegisteredGeneratorbyId(gen.generatorId);
        if (registeredGen == null) {
            // Not a registered generator, bomb out
            return 0;
        }

        LoadGeneratorRuntimeState state = getRuntimeState(gen);
        if (state == null) {
            return 0;
        }

        synchronized (state) {
            int toLaunch = registeredGen.getRunsToLaunch(state);
            if (toLaunch <= 0) {
                return 0;
            }
            List<Job> candidates = registeredGen.getCandidateJobs(state);
            if (candidates == null || candidates.size() == 0) {
                return 0;  // Can't trigger
            }
            for (int i=0; i<toLaunch; i++) {
                Job j = LoadGeneration.pickRandomJob(candidates);
                if (j != null) {
                    LoadGeneration.launchJob(registeredGen, j, 0);
                    addQueueItem(registeredGen);
                }
            }
            return toLaunch;
        }
    }

    public void stopAllAbruptly() {
        for (LoadGenerator lg : getRegisteredGenerators()) {
            stopAbruptly(lg);
        }
    }

    public void start(@Nonnull LoadGenerator gen) {
        LoadGeneratorRuntimeState state = runtimeState.get(gen.getGeneratorId());
        if (state == null) {
            addOrUpdateLoadGenerator(gen);
            state = runtimeState.get(gen.getGeneratorId());
        }
        gen.start(state);
    }

    /** Shut down the generator, kill all its queued items, and cancel all its current runs
     * @param inputGen
     */
    public void stopAbruptly(@Nonnull final LoadGenerator inputGen) {
        // TODO find the FlyWeightTask too and kill that, if we aren't already
        // Find the appropriate registered generator, don't just blindly use supplied instance
        final LoadGenerator gen = getRegisteredGeneratorbyId(inputGen.generatorId);
        final LoadGeneratorRuntimeState state = (gen != null) ? getRuntimeState(gen) : null;
        if (gen == null || state == null) {
            return;
        }
        gen.stop(state);
        SecurityContext context;
        synchronized (state) {
            ACL.impersonate(ACL.SYSTEM, new Runnable() {
                @Override
                public void run() {
                    synchronized (state) {
                        LoadGeneration.cancelItems(LoadGeneration.getQueueItemsFromLoadGenerator(gen));
                        for (Run r : state.getRuns()) {
                            Executor ex = r.getExecutor();
                            if (ex == null) {
                                ex = r.getOneOffExecutor();
                            }
                            if (ex != null) {
                                ex.doStop();
                            } // May need to do //WorkflowRun.doKill();
                            state.removeRun(r);
                        }
                    }
                }
            });
        }
    }

    /** Triggers load as needed for all the registered generators */
    public void maintainLoad() {
        for (LoadGenerator lg : registeredGenerators) {
            LoadGeneratorRuntimeState state = getRuntimeState(lg);
            if (state != null && state.isActive()) {
                this.checkLoadAndTriggerRuns(lg);
            }
        }
    }


    public static GeneratorController getInstance() {
        return Jenkins.getActiveInstance().getExtensionList(GeneratorController.class).get(0);
    }

    @Override
    public void onStarted(@Nonnull Run run, TaskListener listener) {
        String genId = LoadGeneration.getGeneratorCauseId(run);
        final LoadGeneratorRuntimeState state = (genId != null) ? getRuntimeState(genId) : null;
        if (genId != null && state != null) {
            synchronized (state) {
                state.removeQueuedTask();
                state.addRun(run);
            }

        }
    }

    @Override
    public void onFinalized(@Nonnull Run run) {
        String generatorId = LoadGeneration.getGeneratorCauseId(run);
        LoadGeneratorRuntimeState state = null;
        if (generatorId != null && (state = getRuntimeState(generatorId)) != null) {
            synchronized (state) {
                state.removeRun(run);
                checkLoadAndTriggerRuns(getRegisteredGeneratorbyId(generatorId));
            }
        }
    }

    static final XStream2 XSTREAM = new XStream2();

    // Needed to be able to store this
    @Override
    public void save() throws IOException {
        XmlFile file = new XmlFile(new File(Jenkins.getInstance().getRootDir(), "loadGenerators.xml"));
        file.write(this);
    }

    public static GeneratorController load() throws IOException {
        XmlFile file = new XmlFile(new File(Jenkins.getInstance().getRootDir(), "loadGenerators.xml"));
        if (file.exists()) {
            return (GeneratorController)(file.read());
        } else {
            return new GeneratorController();
        }
    }

    /** Periodically starts up load again if toggled */
    @Extension
    public static class PeriodicWorkImpl extends PeriodicWork {
        GeneratorController controller = getInstance();

        @Override
        public long getRecurrencePeriod() {
            return RECURRENCE_PERIOD_MILLIS;
        }

        @Override
        protected void doRun() throws Exception {
            controller.maintainLoad();
        }
    }
}
