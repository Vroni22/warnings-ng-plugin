package hudson.plugins.analysis.core;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.StaplerProxy;

import edu.umd.cs.findbugs.annotations.SuppressWarnings;
import hudson.FilePath;
import hudson.maven.MavenBuild;
import hudson.maven.MavenModule;

import hudson.model.HealthReport;
import hudson.model.HealthReportingAction;
import hudson.model.Result;
import hudson.model.AbstractBuild;

import hudson.plugins.analysis.Messages;
import hudson.plugins.analysis.util.PluginLogger;
import hudson.plugins.analysis.util.ToolTipProvider;
import hudson.plugins.analysis.util.model.AbstractAnnotation;

/**
 * Controls the live cycle of the results in a job. This action persists the results
 * of a build and displays them on the build page. The actual visualization of
 * the results is defined in the matching <code>summary.jelly</code> file.
 * <p>
 * Moreover, this class renders the results trend.
 * </p>
 *
 * @param <T>
 *            type of the result of this action
 * @author Ulli Hafner
 */
//CHECKSTYLE:COUPLING-OFF
public abstract class AbstractResultAction<T extends BuildResult> implements StaplerProxy, HealthReportingAction, ToolTipProvider, ResultAction<T> {
    /** The associated build of this action. */
    private final AbstractBuild<?, ?> owner;
    /** Parameters for the health report. */
    private final AbstractHealthDescriptor healthDescriptor;
    /** The actual result of this action. */
    private T result;

    /**
     * Creates a new instance of <code>AbstractResultAction</code>.
     *
     * @param owner
     *            the associated build of this action
     * @param healthDescriptor
     *            health descriptor
     * @param result
     *            the result of the action
     */
    public AbstractResultAction(final AbstractBuild<?, ?> owner, final AbstractHealthDescriptor healthDescriptor, final T result) {
        this.owner = owner;
        this.result = result;
        this.healthDescriptor = healthDescriptor;
    }

    /**
     * Creates a new instance of <code>AbstractResultAction</code>.
     *
     * @param owner
     *            the associated build of this action
     * @param healthDescriptor
     *            health descriptor
     */
    public AbstractResultAction(final AbstractBuild<?, ?> owner, final AbstractHealthDescriptor healthDescriptor) {
        this.owner = owner;
        this.healthDescriptor = healthDescriptor;
    }

    /**
     * Returns the healthDescriptor.
     *
     * @return the healthDescriptor
     */
    public AbstractHealthDescriptor getHealthDescriptor() {
        if (healthDescriptor == null) {
            return NullHealthDescriptor.NULL_HEALTH_DESCRIPTOR; // for old serialized actions
        }
        else {
            return healthDescriptor;
        }
    }

    /**
     * Returns the descriptor of the associated plug-in.
     *
     * @return the descriptor of the associated plug-in
     */
    protected abstract PluginDescriptor getDescriptor();

    /** {@inheritDoc} */
    public String getUrlName() {
        return getDescriptor().getPluginResultUrlName();
    }

    /** {@inheritDoc} */
    public final HealthReport getBuildHealth() {
        return new HealthReportBuilder(getHealthDescriptor()).computeHealth(getResult());
    }

    /** {@inheritDoc} */
    public ToolTipProvider getToolTipProvider() {
        return this;
    }

    /**
     * Returns the associated build of this action.
     *
     * @return the associated build of this action
     */
    public final AbstractBuild<?, ?> getOwner() {
        return owner;
    }

    /** {@inheritDoc} */
    public final AbstractBuild<?, ?> getBuild() {
        return owner;
    }

    /** {@inheritDoc} */
    public final Object getTarget() {
        return getResult();
    }

    /** {@inheritDoc} */
    public final T getResult() {
        return result;
    }

    /** {@inheritDoc} */
    public final void setResult(final T result) {
        this.result = result;
    }

    /** {@inheritDoc} */
    public String getIconFileName() {
        if (getResult().getNumberOfAnnotations() > 0) {
            return getSmallImage();
        }
        return null;
    }

    /**
     * Returns whether a large image is defined.
     *
     * @return <code>true</code> if a large image is defined, <code>false</code>
     *         otherwise. If no large image is defined, then the attribute
     *         {@code icon} must to be provided in jelly tag {@code summary}.
     * @since 1.41
     */
    public boolean hasLargeImage() {
        return StringUtils.isNotBlank(getLargeImageName());
    }

    /**
     * Returns the URL of the 48x48 image used in the build summary.
     *
     * @return the URL of the image
     * @since 1.41
     */
    public String getLargeImageName() {
        return getDescriptor().getSummaryIconUrl();
    }

    /**
     * Returns the URL of the 24x24 image used in the build link.
     *
     * @return the URL of the image
     * @since 1.41
     */
    public String getSmallImageName() {
        return getSmallImage();
    }

    /**
     * Returns the URL of the 24x24 image used in the build link.
     *
     * @return the URL of the image
     */
    protected String getSmallImage() {
        return getDescriptor().getIconUrl();
    }

    /**
     * Factory method to create the result of this action.
     *
     * @return the result of this action
     */
    protected ParserResult createResult() {
        return new ParserResult();
    }

    /** {@inheritDoc} */
    public String getTooltip(final int numberOfItems) {
        if (numberOfItems == 1) {
            return getSingleItemTooltip();
        }
        else {
            return getMultipleItemsTooltip(numberOfItems);
        }
    }

    /**
     * Returns the tooltip for several items.
     *
     * @param numberOfItems
     *            the number of items to display the tooltip for
     * @return the tooltip for several items
     */
    protected String getMultipleItemsTooltip(final int numberOfItems) {
        return Messages.ResultAction_MultipleWarnings(numberOfItems);
    }

    /**
     * Returns the tooltip for exactly one item.
     *
     * @return the tooltip for exactly one item
     */
    protected String getSingleItemTooltip() {
        return Messages.ResultAction_OneWarning();
    }

    /** {@inheritDoc} */
    public boolean isSuccessful() {
        return getResult().isSuccessful();
    }

    /**
     * Aggregates the results of the specified maven module builds.
     *
     * @param moduleBuilds
     *            the module builds to aggregate
     * @return the aggregated result
     * @deprecated replaced by {@link MavenResultAction}
     */
    @Deprecated
    @java.lang.SuppressWarnings("deprecation")
    protected ParserResult createAggregatedResult(final Map<MavenModule, List<MavenBuild>> moduleBuilds) {
        ParserResult project = createResult();
        for (List<MavenBuild> builds : moduleBuilds.values()) {
            if (!builds.isEmpty()) {
                addModule(project, builds);
            }
        }
        return project;
    }

    /**
     * Updates the build status if the number of annotations exceeds one of the
     * thresholds.
     *
     * @param build
     *            the build to change the status from
     * @param buildResult
     *            the build result
     * @deprecated replaced by {@link MavenResultAction}
     */
    @Deprecated
    protected void updateBuildHealth(final MavenBuild build, final BuildResult buildResult) {
        PluginLogger logger = new PluginLogger(System.out, "[" + getDisplayName() + "] "); // NOCHECKSTYLE
        @java.lang.SuppressWarnings("deprecation")
        Result hudsonResult = new BuildResultEvaluator().evaluateBuildResult(
                logger, getHealthDescriptor().getThresholds(),
                buildResult.getAnnotations(), buildResult.getNewWarnings());
        if (hudsonResult != Result.SUCCESS) {
            build.getParentBuild().setResult(hudsonResult);
        }
    }

    /**
     * Adds a new module to the specified project. The new module is obtained
     * from the specified list of builds.
     *
     * @param aggregatedResult
     *            the result to add the module to
     * @param builds
     *            the builds for a module
     * @deprecated replaced by {@link MavenResultAction}
     */
    @Deprecated
    @java.lang.SuppressWarnings("unchecked")
    protected void addModule(final ParserResult aggregatedResult, final List<MavenBuild> builds) {
        MavenBuild mavenBuild = builds.get(0);
        AbstractResultAction<T> action = mavenBuild.getAction(getClass());
        if (action != null) {
            aggregatedResult.addAnnotations(action.getResult().getAnnotations());
            aggregatedResult.addModules(action.getResult().getModules());
            aggregatedResult.addErrors(action.getResult().getErrors());
            FilePath filePath = new FilePath(new File(mavenBuild.getRootDir(), AbstractAnnotation.WORKSPACE_FILES));
            try {
                filePath.copyRecursiveTo("*.tmp", new FilePath(new File(getOwner().getRootDir(), AbstractAnnotation.WORKSPACE_FILES)));
            }
            catch (IOException exception) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, "Can't copy workspace files: ", exception);
            }
            catch (InterruptedException exception) {
                // ignore, user canceled the operation
            }
        }
    }

    /** Backward compatibility. @deprecated */
    @Deprecated
    @java.lang.SuppressWarnings("unused")
    @SuppressWarnings("UuF")
    private transient HealthReportBuilder healthReportBuilder; // NOPMD
}
