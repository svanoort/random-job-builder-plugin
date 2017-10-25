package jenkins.plugin.randomjobbuilder;

import hudson.model.Job;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.List;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

/**
 * @author Sam Van Oort
 */
public class RegexMatchImmediateLGTest {
    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Test
    public void testTrivialGeneratorSetup() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setDefinition(new CpsFlowDefinition("echo 'I did something' "));

        RegexMatchImmediateLG trivialMatch = new RegexMatchImmediateLG(".*", 1);

        LoadGeneratorRuntimeState state = trivialMatch.initializeState();
        assertFalse("Generator should start inactive", state.isActive());
        assertEquals("Inactive generator shouldn't try to launch jobs", 0, trivialMatch.getRunsToLaunch(state));
        assertEquals(1, trivialMatch.getConcurrentRunCount());
        assertEquals("Generator should start idle and didn't", LoadTestMode.IDLE, state.getLoadTestMode());
        assertEquals(".*", trivialMatch.getJobNameRegex());
        assertNotNull(trivialMatch.getGeneratorId());
    }

    @Test
    public void testTrivialGeneratorFilterJobs() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setDefinition(new CpsFlowDefinition("echo 'I did something' "));

        RegexMatchImmediateLG trivialMatch = new RegexMatchImmediateLG(".*", 1);

        LoadGeneratorRuntimeState state = trivialMatch.initializeState();

        List<Job> candidates = trivialMatch.getCandidateJobs(state);
        assertTrue("Filter should return job", candidates.contains(job));
        assertEquals(1, candidates.size());
        assertEquals(job,LoadGeneration.pickRandomJob(candidates));

        trivialMatch.setJobNameRegex("");
        assertEquals(1, trivialMatch.getCandidateJobs(state).size());
        trivialMatch.setJobNameRegex(null);
        assertEquals(1, trivialMatch.getCandidateJobs(state).size());

        RegexMatchImmediateLG trivialNoMatch = new RegexMatchImmediateLG("cheese", 1);


        candidates = trivialNoMatch.getCandidateJobs(state);
        assertEquals("Empty filter should return no matches", 0, candidates.size());
        assertNull(LoadGeneration.pickRandomJob(candidates));
    }
}
