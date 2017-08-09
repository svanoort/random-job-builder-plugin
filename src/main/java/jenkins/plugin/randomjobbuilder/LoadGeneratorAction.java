package jenkins.plugin.randomjobbuilder;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.ModelObject;
import hudson.model.RootAction;
import hudson.model.TopLevelItem;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.security.Permission;
import hudson.util.HttpResponses;
import jenkins.model.Jenkins;
import jenkins.model.ModelObjectWithContextMenu;
import jenkins.model.TransientActionFactory;
import org.acegisecurity.AccessDeniedException;
import org.acegisecurity.Authentication;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.WebMethod;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;

/**
 * @author Sam Van Oort
 */
public class LoadGeneratorAction implements Action, AccessControlled, ModelObjectWithContextMenu {

    /**
     * The context in which this {@link LoadGeneratorAction} was created.
     */
    private final ModelObject context;

    public LoadGeneration.GeneratorController getController() {
        return LoadGeneration.getGeneratorController();
    }

    @RequirePOST
    public HttpResponse doAutostart(StaplerRequest req, @QueryParameter boolean autostartState) {
        getController().setAutostart(autostartState);
        return HttpResponses.ok();
    }

    @RequirePOST
    public HttpResponse doToggleGenerator(StaplerRequest req, @QueryParameter String generatorId) {
        if (StringUtils.isEmpty(generatorId)) {
            return HttpResponses.errorWithoutStack(500, "You must supply a generator ID");
        } else {
            LoadGeneration.LoadGenerator gen = getController().getRegisteredGeneratorbyId(generatorId);
            if (gen == null) {
                return HttpResponses.errorWithoutStack(500, "Invalid Generator ID "+generatorId);
            }
            if (gen.isActive()) {
                gen.stop();
            } else {
                gen.start();
            } return HttpResponses.ok();
        }
    }

    public ModelObject getContext() {
        return context;
    }

    public LoadGeneratorAction(ModelObject context) {
        this.context = context;
    }

    @Override
    public String getIconFileName() {
        return "gear2.png";
    }

    @Override
    public String getDisplayName() {
        return "LoadGenerator";
    }

    @Override
    public String getUrlName() {
        return "loadgenerator";
    }

    @Nonnull
    @Override
    public ACL getACL() {
        return new ACL() {
            final AccessControlled accessControlled =
                    context instanceof AccessControlled ? (AccessControlled) context : Jenkins.getActiveInstance();

            @Override
            public boolean hasPermission(@Nonnull Authentication a, @Nonnull Permission permission) {
                if (accessControlled.getACL().hasPermission(a, permission)) {
                    return Jenkins.getActiveInstance().hasPermission(permission);
                }
                return false;
            }
        };
    }

    @Override
    public void checkPermission(@Nonnull Permission permission) throws AccessDeniedException {
        getACL().checkPermission(permission);
    }

    @Override
    public boolean hasPermission(@Nonnull Permission permission) {
        return getACL().hasPermission(permission);
    }

    @Override
    public ContextMenu doContextMenu(StaplerRequest request, StaplerResponse response) throws Exception {
        ContextMenu menu = new ContextMenu();
        return menu;
    }

    /**
     * Add the {@link LoadGeneratorAction} to all {@link TopLevelItem} instances.
     */
    @Extension(ordinal = -1000)
    public static class TransientTopLevelItemActionFactoryImpl extends TransientActionFactory<TopLevelItem> {

        /**
         * {@inheritDoc}
         */
        @Override
        public Class<TopLevelItem> type() {
            return TopLevelItem.class;
        }

        /**
         * {@inheritDoc}
         */
        @Nonnull
        @Override
        public Collection<? extends Action> createFor(@Nonnull TopLevelItem target) {
            return Collections.singleton(new LoadGeneratorAction(target));
        }
    }

    /**
     * Add the Action to the {@link Jenkins} root.
     */
    @Extension(ordinal = -1000)
    public static class RootActionImpl extends LoadGeneratorAction implements RootAction {

        /**
         * Our constructor.
         */
        public RootActionImpl() {
            super(Jenkins.getActiveInstance());
        }
    }
}
