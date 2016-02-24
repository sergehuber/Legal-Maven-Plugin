package org.jahia.tools.maven.plugins;

import javax.xml.bind.annotation.XmlTransient;
import java.util.*;

/**
 * Created by loom on 16.02.16.
 */
public class JarMetadata implements Comparable<JarMetadata> {
    String fullPath;
    String name;
    String version;
    String project;
    String classifier = null;
    byte[] jarContents = null;
    String inceptionYear = null;
    String projectUrl = null;
    String organizationName = null;
    String organizationUrl = null;

    SortedSet<JarMetadata> embeddedJars = new TreeSet<JarMetadata>();
    SortedSet<String> packages = new TreeSet<String>();
    Map<String,LicenseFile> licenseFiles = new TreeMap<>();
    Map<String,Notice> noticeFiles = new TreeMap<>();

    public JarMetadata(String fullPath, String name, String version, String classifier) {
        this.fullPath = fullPath;
        this.name = name;
        this.version = version;
        this.classifier = classifier;
        this.project = getProject(name);
    }

    public JarMetadata(String fullPath, String mavenArtifactFileName) {
        this.fullPath = fullPath;
        // look for beginning of version string if any
        int versionStartsAt = -1;
        int dash = mavenArtifactFileName.indexOf('-');
        while (dash > 0 && dash < mavenArtifactFileName.length()) {
            if (Character.isDigit(mavenArtifactFileName.charAt(dash + 1))) {
                versionStartsAt = dash + 1;
                break;
            }
            dash = mavenArtifactFileName.indexOf('-', dash + 1);
        }
        if (versionStartsAt == -1) {
            name = mavenArtifactFileName;
            return;
        }
        name = mavenArtifactFileName.substring(0, versionStartsAt - 1);
        version = mavenArtifactFileName.substring(versionStartsAt);
        if (version.endsWith("-sources")) {
            classifier = "sources";
            version = version.substring(0, version.length()-"-sources".length());
        } else if (version.endsWith("-javadoc")) {
            classifier = "javadoc";
            version = version.substring(0, version.length()-"-javadoc".length());
        } else if (version.contains("-")) {
            int dashPos = version.lastIndexOf("-");
            if (dashPos > 0) {
                classifier = version.substring(dashPos + 1);
                version = version.substring(0, dashPos);
            }
        } else {
            classifier = null;
        }
        this.project = getProject(name);;

    }

    private String getProject(String name) {
        // compute project name heuristically
        String project = name;
        final int dot = name.indexOf('.');
        if (dot > 0) {
            // look for tld.hostname.project pattern
            boolean standard = false;
            int host = name.indexOf('.', dot + 1);
            if (host > dot) {
                int proj = name.indexOf('.', host + 1);
                if (proj >= 0) {
                    project = name.substring(0, proj);
                    standard = true;
                }
            }

            if (!standard) {
                project = getDashSeparatedProjectName(name);
            }
        } else {
            // assume project is project-subproject-blah...
            project = getDashSeparatedProjectName(name);
        }
        return project;
    }

    static String getDashSeparatedProjectName(String name) {
        final int dash = name.indexOf('-');
        if (dash > 0) {
            return name.substring(0, dash);
        } else {
            return name;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        JarMetadata that = (JarMetadata) o;

        if (name != null ? !name.equals(that.name) : that.name != null) return false;
        return version != null ? version.equals(that.version) : that.version == null;

    }

    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + (version != null ? version.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append(name);
        if (version != null) {
            result.append("-");
            result.append(version);
        }
        if (classifier != null) {
            result.append("-").append(classifier);
        }
        return result.toString();
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getProject() {
        return project;
    }

    public String getClassifier() {
        return classifier;
    }

    @XmlTransient
    public byte[] getJarContents() {
        return jarContents;
    }

    public void setJarContents(byte[] jarContents) {
        this.jarContents = jarContents;
    }

    public Map<String, LicenseFile> getLicenseFiles() {
        return licenseFiles;
    }

    public void setLicenseFiles(Map<String, LicenseFile> licenseFiles) {
        this.licenseFiles = licenseFiles;
    }

    public Map<String, Notice> getNoticeFiles() {
        return noticeFiles;
    }

    public void setNoticeFiles(Map<String, Notice> noticeFiles) {
        this.noticeFiles = noticeFiles;
    }

    public SortedSet<JarMetadata> getEmbeddedJars() {
        return embeddedJars;
    }

    public void setEmbeddedJars(SortedSet<JarMetadata> embeddedJars) {
        this.embeddedJars = embeddedJars;
    }

    public String getFullPath() {
        return fullPath;
    }

    public void setFullPath(String fullPath) {
        this.fullPath = fullPath;
    }

    public SortedSet<String> getPackages() {
        return packages;
    }

    public void setPackages(SortedSet<String> packages) {
        this.packages = packages;
    }

    public String getInceptionYear() {
        return inceptionYear;
    }

    public void setInceptionYear(String inceptionYear) {
        this.inceptionYear = inceptionYear;
    }

    public String getProjectUrl() {
        return projectUrl;
    }

    public void setProjectUrl(String projectUrl) {
        this.projectUrl = projectUrl;
    }

    public String getOrganizationName() {
        return organizationName;
    }

    public void setOrganizationName(String organizationName) {
        this.organizationName = organizationName;
    }

    public String getOrganizationUrl() {
        return organizationUrl;
    }

    public void setOrganizationUrl(String organizationUrl) {
        this.organizationUrl = organizationUrl;
    }

    @Override
    public int compareTo(JarMetadata o) {
        return toString().compareTo(o.toString());
    }
}
