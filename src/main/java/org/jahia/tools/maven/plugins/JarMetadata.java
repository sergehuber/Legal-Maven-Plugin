package org.jahia.tools.maven.plugins;

/**
 * Created by loom on 16.02.16.
 */
public class JarMetadata {
    String name;
    String version;
    String project;
    String classifier = null;

    public JarMetadata(String name, String version, String classifier) {
        this.name = name;
        this.version = version;
        this.classifier = classifier;
        this.project = getProject(name);;
    }

    public JarMetadata(String mavenArtifactFileName) {
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
                project = name.substring(0, proj);
                standard = true;
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
        result.append(name).append("-").append(version);
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


}
