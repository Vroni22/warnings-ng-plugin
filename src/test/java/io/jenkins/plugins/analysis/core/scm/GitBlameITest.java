package io.jenkins.plugins.analysis.core.scm;

import java.util.List;

import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;

import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.extensions.impl.RelativeTargetDirectory;
import jenkins.plugins.git.GitSCMBuilder;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.scm.api.SCMHead;

import io.jenkins.plugins.analysis.core.model.AnalysisResult;
import io.jenkins.plugins.analysis.core.testutil.IntegrationTestWithJenkinsPerSuite;
import io.jenkins.plugins.analysis.warnings.Java;
import io.jenkins.plugins.analysis.warnings.recorder.pageobj.DetailsTab;
import io.jenkins.plugins.analysis.warnings.recorder.pageobj.SourceControlRow;
import io.jenkins.plugins.analysis.warnings.recorder.pageobj.SourceControlTable;

import static io.jenkins.plugins.analysis.core.testutil.IntegrationTest.JavaScriptSupport.*;
import static io.jenkins.plugins.analysis.warnings.recorder.pageobj.DetailsTab.TabType.*;
import static io.jenkins.plugins.analysis.warnings.recorder.pageobj.SourceControlRow.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests the git blame functionality within the git-plugin.
 *
 * @author Veronika Zwickenpflug
 * @author Florian Hageneder
 */
public class GitBlameITest extends IntegrationTestWithJenkinsPerSuite {

    /**
     * Local git integration for testing purposes.
     */
    @ClassRule
    public static GitSampleRepoRule repository = new GitSampleRepoRule();

    /**
     * Creates a repository with a single file that is changed by two committer. Afterwards the plugin has to record
     * correct blame information for arbitrary issues.
     *
     * @throws Exception
     *         When initializing git fails.
     */
    @Test
    public void shouldReadCorrectBlameInformation() throws Exception {
        String file = "opentasks.txt";
        String issuesFile = "issues.txt";
        String branch = "blame-info";
        initGitAndFlawedTestFile(branch, file, issuesFile);

        FreeStyleProject job = createFreeStyleProject();
        job.setScm(new GitSCM(repository.fileUrl()));
        enableGenericWarnings(job, new Java());

        scheduleBuildAndVerifyBlames(job);
    }

    /**
     * Creates an repository with an out of tree build and checks if git blame works correctly. Source files are
     * contained in "src"-folder, while build files are located in the "build"-folder.
     *
     * @throws Exception
     *         When initializing git fails.
     * @see <a href="https://issues.jenkins-ci.org/browse/JENKINS-57260">Issue 57260</a>
     */
    @Test
    @Issue("JENKINS-57260")
    public void checkGitBlameInOutOfTreeBuild() throws Exception {
        String file = "src/opentasks.txt";
        String issuesFile = "build/issues.txt";
        String branch = "out-of-tree-build";
        initGitAndFlawedTestFile(branch, file, issuesFile);

        GitSCMBuilder builder = new GitSCMBuilder(new SCMHead(branch), null, repository.fileUrl(), null);
        RelativeTargetDirectory sourceDirectory = new RelativeTargetDirectory("src");
        builder.withExtension(sourceDirectory);
        GitSCM gitSCM = builder.build();

        FreeStyleProject job = createFreeStyleProject();
        job.setScm(gitSCM);
        enableGenericWarnings(job, new Java());

        scheduleBuildAndVerifyBlames(job);
    }

    /**
     * Schedules build and verifies all blame rows in SourceControlTable
     */
    private void scheduleBuildAndVerifyBlames(final FreeStyleProject job) {
        AnalysisResult result = scheduleBuildAndAssertStatus(job, Result.SUCCESS);
        SourceControlTable blames = new DetailsTab(getWebPage(JS_ENABLED, result)).select(BLAMES);

        assertThat(result.getErrorMessages()).isEmpty();
        assertThat(result.getInfoMessages()).contains("-> found 2 issues (skipped 0 duplicates)",
                "-> blamed authors of issues in 1 files");

        List<SourceControlRow> rows = blames.getRows();
        verifySourceControlRow(rows.get(0), "Alice", "alice@example.com", "something has been deprecated");
        verifySourceControlRow(rows.get(1), "Bob", "bob@example.com", "something else has been deprecated too");
    }

    /**
     * Check whether the given SourceControlRow has the given author, email and detailsContent
     *
     * @param row
     *         SourceControlRow to be checked
     * @param author
     *         Author that is asserted
     * @param email
     *         Email that is asserted
     * @param detailsContent
     *         Details content that is asserted
     */
    private void verifySourceControlRow(final SourceControlRow row, final String author, final String email,
            final String detailsContent) {
        assertThat(row.getValue(AUTHOR)).isEqualTo(author);
        assertThat(row.getValue(EMAIL)).isEqualTo(email);
        assertThat(row.getValue(DETAILS_CONTENT)).isEqualTo(detailsContent);
    }

    /**
     * Initialise the git repository with one src file containing flawed code edited by two users and one issue file
     * containing corresponding msg
     *
     * @param srcFile
     *         Local path where the source file should be created
     * @param issuesFile
     *         Local path where the issue file should be created
     *
     * @throws Exception
     *         When initializing git fails.
     */
    private void initGitAndFlawedTestFile(final String branch, final String srcFile, final String issuesFile)
            throws Exception {
        repository.init();
        repository.git("checkout", branch);

        // make first change as ALICE
        addFileToGit("Alice", "alice@example.com", "Line 1\nLine 2\n", srcFile, "init opentasks");

        // second change as BOB
        addFileToGit("Bob", "bob@example.com", "Line 1\nLine 2 but better\n", srcFile, "update opentasks");

        // add issues.txt to avoid copying it by hand
        repository.write(issuesFile, "[WARNING] opentasks.txt:[1,0] [deprecation] something has been deprecated\n"
                + "[WARNING] opentasks.txt:[2,0] [deprecation] something else has been deprecated too\n");
        repository.git("add", issuesFile);
        repository.git("commit", "-m", "add issue file", issuesFile);
    }

    /**
     * Adds file with specified text to git using the given user information and commit message.
     *
     * @param name
     *         Name of the user for the commit
     * @param email
     *         Email of the user for the commit
     * @param text
     *         Text that should be written to file
     * @param file
     *         Local file path
     * @param msg
     *         Commit message
     */
    private void addFileToGit(final String name, final String email, final String text, final String file,
            final String msg) throws Exception {
        repository.git("config", "user.name", name);
        repository.git("config", "user.email", email);
        repository.write(file, text);
        repository.git("add", file);
        repository.git("commit", "-m", msg);
    }
}
