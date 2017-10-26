package jenkins.plugin.randomjobbuilder;

import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.text.MessageFormat;

/**
 * @author Sam Van Oort
 */
public class SingleJobLinearRampUpLGTest {

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Test
    public void testLoadCalculation() throws Exception {
        long startTimeMillis = 0;

        SingleJobLinearRampUpLG gen = new SingleJobLinearRampUpLG("bob");
        gen.setRampUpMillis(1000).setConcurrentRunCount(10).setUseJitter(false);
        SingleJobLinearRampUpLG.TimedLoadGeneratorRuntimeState timedState = (SingleJobLinearRampUpLG.TimedLoadGeneratorRuntimeState)gen.initializeState();

        // Once ramp-up is done, we need to
        Assert.assertEquals(10, gen.computeDesiredRuns(2000L, startTimeMillis));
        Assert.assertEquals(2, gen.computeDesiredRuns(200L, startTimeMillis));
        Assert.assertEquals(4, gen.computeDesiredRuns(400L, startTimeMillis));

        // No ramp-up, no jitter, just return enough runs to bring up to the expected val
        gen.setConcurrentRunCount(37);
        gen.setRampUpMillis(-1);
        Assert.assertEquals(37, gen.computeDesiredRuns(188, startTimeMillis));
        Assert.assertEquals(37, gen.computeRunsToLaunch(188, startTimeMillis, 0));
        Assert.assertEquals(36, gen.computeRunsToLaunch(188, startTimeMillis, 1));
        Assert.assertEquals(0, gen.computeRunsToLaunch(188, startTimeMillis, gen.getConcurrentRunCount()+5));

        // Test with some randomization
        gen.setUseJitter(true);
        gen.setConcurrentRunCount(14);
        for(int i=0; i<10; i++) {
            int expected = gen.computeRunsToLaunch(-1, 0, 7);
            Assert.assertTrue("Suggesting negative runs, that's bogus!", expected >= 0);
            Assert.assertTrue(MessageFormat.format("Launched too many runs ({0}), should not suggest more than 2x goal ({1})", expected, 14), expected <= 14);
        }
        for(int i=0; i<10; i++) {
            Assert.assertEquals(0, gen.computeRunsToLaunch(-1, 0, gen.getConcurrentRunCount()));
            Assert.assertEquals(0, gen.computeRunsToLaunch(System.currentTimeMillis(), 0, gen.getConcurrentRunCount()+1));
        }

        // No jitter, linear ramp-up time
        gen.setUseJitter(false);
        gen.setConcurrentRunCount(100);
        gen.setRampUpMillis(1000);
        Assert.assertEquals(0, gen.computeDesiredRuns(-50, startTimeMillis));
        Assert.assertEquals(50, gen.computeDesiredRuns(500, startTimeMillis));
        Assert.assertEquals(25, gen.computeDesiredRuns(250, startTimeMillis));
        Assert.assertEquals(25, gen.computeRunsToLaunch(250, 0L, 0));
        Assert.assertEquals(15, gen.computeRunsToLaunch(250, 100L, 0));
        Assert.assertEquals(100, gen.computeDesiredRuns(99999, startTimeMillis));
        Assert.assertEquals(100, gen.computeRunsToLaunch(99999,0, 0));

        gen.setUseJitter(true);
        gen.setConcurrentRunCount(100);
        gen.setRampUpMillis(1000);
        for (int i=0; i<10; i++) {
            int expected = gen.computeRunsToLaunch(500, 0, 0);
            Assert.assertTrue("Suggesting negative runs, that's bogus!", expected >= 0);
            Assert.assertTrue(MessageFormat.format("Launched too many runs ({0}), should not suggest more than 2x goal ({1})", expected, 100), expected <= 100);
        }
    }

    @Test
    public void testRunAutostartThenUnregisterAndStopGenerator() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setDefinition(new CpsFlowDefinition("node('doesnotexist') {\n" +
                "echo 'I did something' \n" +
                "}"));
        SingleJobLinearRampUpLG trivial = new SingleJobLinearRampUpLG(job.getFullName());
        trivial.setConcurrentRunCount(8);
        trivial.setUseJitter(false);
        trivial.setRampUpMillis(4000L);

        Assert.assertEquals(8, trivial.getConcurrentRunCount());
        GeneratorController controller = GeneratorController.getInstance();
        controller.addLoadGenerator(trivial);

        // Check it queued up correctly
        Jenkins j = jenkinsRule.getInstance();
        LoadGeneratorRuntimeState state = controller.getRuntimeState(trivial);
        SingleJobLinearRampUpLG.TimedLoadGeneratorRuntimeState fakeFreshStart = (SingleJobLinearRampUpLG.TimedLoadGeneratorRuntimeState)(trivial.initializeState());
        fakeFreshStart.setStartTimeMillis(System.currentTimeMillis());
        fakeFreshStart.setLoadTestMode(LoadTestMode.LOAD_TEST);

        trivial.start(state);
        Thread.sleep(5000L);
        Assert.assertEquals(8, trivial.getRunsToLaunch(fakeFreshStart));  // Compute how many we'd start
        Thread.sleep(GeneratorController.RECURRENCE_PERIOD_MILLIS+500L);
        Assert.assertEquals(8, state.getRunningTaskCount());
        Assert.assertEquals(8, state.getRunningTaskCount());

        // Stop and verify really stopped
        controller.unregisterAndStopGenerator(trivial);
        Assert.assertEquals(0, state.getTotalTaskCount());
        Assert.assertNull(controller.getRegisteredGeneratorbyId(trivial.generatorId));
        Assert.assertEquals(8, job.getBuilds().size());
    }
}
