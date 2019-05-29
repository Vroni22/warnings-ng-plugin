package io.jenkins.plugins.analysis.core.scm;

import java.io.IOException;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;

import hudson.FilePath;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.TopLevelItem;
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
    @Rule
    public GitSampleRepoRule repository = new GitSampleRepoRule();

    /**
     * Initializes the git repository.
     *
     * @throws Exception
     *         When git interaction fails.
     */
    @Before
    public void init() throws Exception {
        repository.init();
        repository.git("checkout", "master");
    }

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
        addFileToGit("Alice", "alice@example.com", "Line 1\nLine 2\n", file, "init opentasks");
        addFileToGit("Bob", "bob@example.com", "Line 1\nLine 2 but better\n", file, "update opentasks");
        FreeStyleProject job = createFreeStyleProject();
        job.setScm(new GitSCM(repository.fileUrl()));
        writeStringToWorkspaceFile(job, "build/issues.txt",
                "[WARNING] opentasks.txt:[1,0] [deprecation] something has been deprecated\n"
                        + "[WARNING] opentasks.txt:[2,0] [deprecation] something else has been deprecated too\n");
        enableGenericWarnings(job, new Java());

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
        String file = "opentasks.txt";
        addFileToGit("Alice", "alice@example.com", "Line 1\nLine 2\n", file, "init opentasks");
        addFileToGit("Bob", "bob@example.com", "Line 1\nLine 2 but better\n", file, "update opentasks");

        GitSCMBuilder builder = new GitSCMBuilder(new SCMHead("master"), null, repository.fileUrl(), null);
        RelativeTargetDirectory sourceDirectory = new RelativeTargetDirectory("src");
        builder.withExtension(sourceDirectory);
        GitSCM gitSCM = builder.build();

        FreeStyleProject job = createFreeStyleProject();
        job.setScm(gitSCM);
        enableGenericWarnings(job, new Java());
        writeStringToWorkspaceFile(job, "build/issues.txt",
                "[WARNING] src/opentasks.txt:[1,0] [deprecation] something has been deprecated\n"
                        + "[WARNING] src/opentasks.txt:[2,0] [deprecation] something else has been deprecated too\n");

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
     * Writes given content into a file within the workspace.
     *
     * @param job
     *         Job to create file in.
     * @param file
     *         Name of file to create.
     * @param text
     *         Text to write into file.
     *
     * @throws IOException
     *         When writing content gets interrupted.
     * @throws InterruptedException
     *         When writing content fails.
     */
    private void writeStringToWorkspaceFile(final TopLevelItem job, final String file, final String text)
            throws IOException, InterruptedException {
        FilePath ws = getJenkins().jenkins.getWorkspaceFor(job);

        ws.child(file).write(text, "UTF-8");
    }

    /**
     * Check whether the given SourceControlRow has the given author, email and detailsContent.
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
