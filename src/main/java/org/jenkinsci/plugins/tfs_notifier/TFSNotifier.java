package org.jenkinsci.plugins.tfs_notifier;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.regex.Pattern;

import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.tfs_notifier.service.TFSService;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;

public class TFSNotifier extends Notifier {

    private final String nativeDirectory;
    private final String serverUrl;
    private final String projectCollection;
    private final String project;
    private final String userName;
    private final String userPassword;
    private final String projectPath;
    private final String excludedRegions;
    private final String includedRegions;

    @DataBoundConstructor
    public TFSNotifier(String nativeDirectory, String version, String serverUrl, String projectCollection, String project,
                        String userName, String userPassword, String projectPath, String excludedRegions, String includedRegions) {
        this.nativeDirectory   = nativeDirectory;
        this.serverUrl         = serverUrl;
        this.projectCollection = projectCollection;
        this.project           = project;
        this.userName          = userName;
        this.userPassword      = userPassword;
        this.projectPath       = projectPath;
        this.excludedRegions   = excludedRegions;
        this.includedRegions   = includedRegions;
    }


    public String getNativeDirectory() {
        return nativeDirectory;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getProjectCollection() {
        return projectCollection;
    }

    public String getProject() {
        return project;
    }

    public String getUserName() {
        return userName;
    }

    public String getUserPassword() {
        return userPassword;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public String getExcludedRegions() {
        return excludedRegions;
    }

    public String[] getExcludedRegionsNormalized() {
        return StringUtils.isBlank(excludedRegions) ? null : excludedRegions.split("[\\r\\n]+");
    }

    private Pattern[] getExcludedRegionsPatterns() {
        String[] excluded = getExcludedRegionsNormalized();
        if (excluded != null) {
            Pattern[] patterns = new Pattern[excluded.length];

            int i = 0;
            for (String excludedRegion : excluded) {
                patterns[i++] = Pattern.compile(excludedRegion);
            }

            return patterns;
        }

        return new Pattern[0];
    }

    public String getIncludedRegions() {
        return includedRegions;
    }

    public String[] getIncludedRegionsNormalized() {
        return StringUtils.isBlank(includedRegions) ? null : includedRegions.split("[\\r\\n]+");
    }

    private Pattern[] getIncludedRegionsPatterns() {
        String[] included = getIncludedRegionsNormalized();
        if (included != null) {
            Pattern[] patterns = new Pattern[included.length];

            int i = 0;
            for (String includedRegion : included) {
                patterns[i++] = Pattern.compile(includedRegion);
            }

            return patterns;
        }

        return new Pattern[0];
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        if (StringUtils.isBlank(projectPath)) {
            listener.getLogger().println("No project path.");
            return false;
        }

        try {
            int changeSetID = parseChangeSetFile(build);
            Pattern[] excludedPatterns = getExcludedRegionsPatterns();
            Pattern[] includedPatterns = getIncludedRegionsPatterns();
            TFSService service= new TFSService();
            service.setNativeDirectory(nativeDirectory);
            service.setServerUrl(serverUrl);
            service.setUserName(userName);
            service.setUserPassword(userPassword);
            service.init();

            int currentChangeSetID = service.getChangeSetID(projectPath, excludedPatterns, includedPatterns);

            if (changeSetID < currentChangeSetID) {
                if (changeSetID < 0)
                    listener.getLogger().println("ChangeSet: " + currentChangeSetID);
                else
                    listener.getLogger().println("ChangeSet: " + changeSetID + " -> " + currentChangeSetID);

                List<Integer> workItemIDs = service.getWorkItemIDs(currentChangeSetID);
                if (workItemIDs != null && workItemIDs.size() > 0) {
                    for (int workItemID : workItemIDs) {
                        listener.getLogger().println("WorkItem: " + workItemID);

                        String color = "black";
                        if (build.getResult() == Result.SUCCESS)
                            color = "blue";
                        else if (build.getResult() == Result.UNSTABLE)
                            color = "orange";
                        else if (build.getResult() == Result.FAILURE)
                            color = "red";

                        String url = hudson.tasks.Mailer.descriptor().getUrl() + build.getUrl();
                        String history = String.format("Jenkins-CI %s <a href=\"%s\">#%d</a> <font style=\"color:%s; font-weight: bold;\">%s</font>", build.getProject().getDisplayName(), url, build.getNumber(), color, build.getResult());
                        String comment = String.format("Jenkins-CI %s #%d %s", build.getProject().getDisplayName(), build.getNumber(), build.getResult());

                        service.addHyperlink(workItemID, url, comment, history);
                    }
                }

                saveChangeSetFile(build, projectPath, currentChangeSetID);
            } else
                listener.getLogger().println("ChangeSet: " + currentChangeSetID);
        } catch (Exception e) {
            listener.getLogger().println(e.getMessage());
        }
        return true;
    }

    public File getChangeSetFile(AbstractBuild<?, ?> build) {
        return new File(build.getProject().getRootDir(), "changeSet_Notify.txt");
    }

    private int parseChangeSetFile(AbstractBuild<?, ?> build) throws IOException {
        int changeSetID = -1;
        File file = getChangeSetFile(build);
        if (!file.exists())
            return changeSetID;

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(file));

            String line;
            while ((line = br.readLine()) != null) {
                int index = line.lastIndexOf('/');

                if (index < 0)
                    continue;

                try {
                    String path = line.substring(0, index);
                    if (projectPath.equals(path)) {
                        changeSetID = Integer.parseInt(line.substring(index + 1));
                        break;
                    }
                } catch (NumberFormatException ex) {
                }
            }
        } finally {
            if (br != null) br.close();
        }

        return changeSetID;
    }

    private void saveChangeSetFile(AbstractBuild<?, ?> build, String path, int chageSetID) throws IOException, InterruptedException  {
        PrintWriter w = null;
        try {
            w = new PrintWriter(new FileOutputStream(getChangeSetFile(build)));
            w.println(path + "/" + chageSetID);
        } finally {
            if (w != null) w.close();
        }
    }

    @Override
    public BuildStepDescriptor<Publisher> getDescriptor() {
         return DESCRIPTOR;
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public DescriptorImpl() {
            super(TFSNotifier.class);
        }

        @Override
        public String getDisplayName() {
            return Messages.TFSNotifier_Descriptor_DisplayName();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }
}
