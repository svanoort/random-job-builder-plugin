package jenkins.plugin.randomjobbuilder;

import hudson.model.labels.LabelAtom;
import hudson.tasks.LogRotator;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Arrays;
import java.util.List;

import static junit.framework.Assert.*;

/**
 * @author Sam Van Oort
 */
public class GeneratorControllerTest {
    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Test
    public void testBasicGeneration() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setBuildDiscarder(new LogRotator(-1, 20, -1, 40));
        job.setDefinition(new CpsFlowDefinition("echo 'I did something' \n" +
                "sleep 1"));
        RegexMatchImmediateLG trivial = new RegexMatchImmediateLG(".*", 1);
        LoadGeneration.DescriptorImpl desc = LoadGeneration.getDescriptorInstance();

        GeneratorController.getInstance().addLoadGenerator(trivial);
        GeneratorController.getInstance().start(trivial);

        Jenkins j = jenkinsRule.getInstance();
        Thread.sleep(4000L);
        System.out.println("Currently running jobs: "+j);
        assertTrue("No jobs completed", job.getBuilds().size() > 0);

        trivial.stop(GeneratorController.getInstance().getRuntimeState(trivial));
        Thread.sleep(6000L);

        Assert.assertFalse(job.getLastBuild().isBuilding());
    }

    @Test
    public void testControllerBasics() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setDefinition(new CpsFlowDefinition("node('doesnotexist') {\n" +
                "echo 'I did something' \n" +
                "}"));

        // Generator is unregistered as part of LoadGeneration.DescriptorImpl.generators
        // So it doesn't automatically start creating load
        RegexMatchImmediateLG trivial = new RegexMatchImmediateLG(".*", 8);
        GeneratorController controller = GeneratorController.getInstance();

        controller.addLoadGenerator(trivial);
        Assert.assertEquals(trivial, controller.getRegisteredGeneratorbyId(trivial.generatorId));
        Assert.assertEquals(trivial, controller.getRegisteredGeneratorbyShortName(trivial.getShortName()));
        Assert.assertNotNull("Generator not registered with runtime state after adding", GeneratorController.getInstance().getRuntimeState(trivial));

        controller.start(trivial);

        // Incrementing & Decrementing Queue Item counts & seeing impact on controller & desired run count
        LoadGeneratorRuntimeState state = controller.getRuntimeState(trivial);
        Assert.assertEquals(0, state.getQueuedTaskCount());
        Assert.assertEquals(0, state.getTotalTaskCount());
        Assert.assertEquals(8, trivial.getRunsToLaunch(state));
        controller.addQueueItem(trivial);
        Assert.assertEquals(1, state.getQueuedTaskCount());
        Assert.assertEquals(1, state.getTotalTaskCount());
        Assert.assertEquals(7, trivial.getRunsToLaunch(state));
        controller.removeQueueItem(trivial);
        Assert.assertEquals(0, state.getQueuedTaskCount());
        trivial.stop(state);

        // Simulate having queued an item, then run a job, then see if the controller picks it up as if the job started running
        controller.addQueueItem(trivial);
        LoadGeneration.launchJob(trivial, job, 0);

        long started = System.currentTimeMillis();
        long maxWait = 1000L;
        while (job.getLastBuild() == null) {
            if ((System.currentTimeMillis()-started) >= maxWait) {
                Assert.fail("Job didn't start in a timely fashion!");
            }
            Thread.sleep(100);
        }

        WorkflowRun run = (WorkflowRun)(job.getLastBuild());
        Assert.assertEquals(0, state.getQueuedTaskCount()); // Once we start running, item no longer just queued
        Assert.assertEquals(1, state.getRunningTaskCount()); // ...and is now running
        Assert.assertEquals(1, state.getTotalTaskCount());
        Assert.assertEquals(0, trivial.getRunsToLaunch(state));

        // Kill the job and verify counts were decremented appropriately
        controller.stopAbruptly(trivial);
        Thread.sleep(100);
        Assert.assertEquals(0, jenkinsRule.jenkins.getQueue().getItems().length);
        Assert.assertEquals(0, state.getRunningTaskCount());
        Assert.assertEquals(0, state.getTotalTaskCount());
    }

    @Test
    public void testControllerQueueing() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setDefinition(new CpsFlowDefinition("node('doesnotexist') {\n" +
                "echo 'I did something' \n" +
                "}"));
        RegexMatchImmediateLG trivial = new RegexMatchImmediateLG(".*", 8);
        Assert.assertEquals(8, trivial.getConcurrentRunCount());
        LoadGeneration.DescriptorImpl desc = LoadGeneration.getDescriptorInstance();
        GeneratorController controller = GeneratorController.getInstance();
        controller.addLoadGenerator(trivial);
        LoadGeneratorRuntimeState state = controller.getRuntimeState(trivial);

        // Check it queued up correctly
        Jenkins j = jenkinsRule.getInstance();
        trivial.start(state);
        Assert.assertEquals(8, trivial.getRunsToLaunch(state));
        controller.maintainLoad(); // triggers one cycle of load generation
        trivial.stop(state);
        Assert.assertEquals(8, state.getTotalTaskCount());
        Thread.sleep(4000);

        // Disable generator and verify it doesn't run any more
        jenkinsRule.createOnlineSlave(new LabelAtom("doesnotexist"));
        jenkinsRule.waitUntilNoActivityUpTo(2000);

        Assert.assertEquals(8,  job.getBuilds().size());
    }

    @Test
    public void testControllerSync() throws Exception {
        GeneratorController controller = GeneratorController.getInstance();

        RegexMatchImmediateLG trivial = new RegexMatchImmediateLG(".*", 8);
        controller.addLoadGenerator(trivial);
        LoadGeneratorRuntimeState state = controller.getRuntimeState(trivial);
        trivial.start(state);
        RegexMatchImmediateLG trivial2 = new RegexMatchImmediateLG(".*foo", 4);
        controller.addLoadGenerator(trivial2);
        LoadGeneratorRuntimeState state2 = controller.getRuntimeState(trivial);
        trivial2.setGeneratorId(trivial.generatorId);
        trivial2.stop(state);

        List<LoadGenerator> originalGenerators = Arrays.asList((LoadGenerator)trivial);
        List<LoadGenerator> modifiedGenerators = Arrays.asList((LoadGenerator)trivial2);

        controller.syncGenerators(originalGenerators);
        Assert.assertEquals(1, controller.registeredGenerators.values().size());
        Assert.assertTrue(trivial == controller.getRegisteredGeneratorbyId(trivial.getGeneratorId()));
        Assert.assertEquals(state.getLoadTestMode(), controller.getRuntimeState(trivial).getLoadTestMode());

        controller.syncGenerators(modifiedGenerators);
        Assert.assertTrue(trivial2 == controller.getRegisteredGeneratorbyId(trivial.getGeneratorId()));
    }

    @Test
    public void testTriggerThenUnregisterAndStopGenerator() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setDefinition(new CpsFlowDefinition("node('doesnotexist') {\n" +
                "echo 'I did something' \n" +
                "}"));
        RegexMatchImmediateLG trivial = new RegexMatchImmediateLG(".*", 8);
        Assert.assertEquals(8, trivial.getConcurrentRunCount());
        LoadGeneration.DescriptorImpl desc = LoadGeneration.getDescriptorInstance();
        GeneratorController controller = GeneratorController.getInstance();
        controller.addLoadGenerator(trivial);

        // Check it queued up correctly
        Jenkins j = jenkinsRule.getInstance();
        LoadGeneratorRuntimeState state = controller.getRuntimeState(trivial);

        // Used to mimic being running without actually starting
        LoadGeneratorRuntimeState runningState = trivial.initializeState();
        runningState.setLoadTestMode(LoadTestMode.LOAD_TEST);


        Assert.assertEquals(8, trivial.getRunsToLaunch(runningState));
        trivial.start(state);
        controller.checkLoadAndTriggerRuns(trivial);
        trivial.stop(state);
        Thread.sleep(1000);  // Slight delay for queue maintenance to happen
        Assert.assertEquals(8, state.getTotalTaskCount());
        Assert.assertEquals(8, state.getRunningTaskCount());

        // Stop and verify really stopped
        controller.unregisterAndStopGenerator(trivial);
        Assert.assertEquals(0, state.getTotalTaskCount());
        Assert.assertNull(controller.getRegisteredGeneratorbyId(trivial.generatorId));
        Assert.assertEquals(8, job.getBuilds().size());
    }
}
