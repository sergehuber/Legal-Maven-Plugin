package org.jahia.tools.maven.plugins;

import java.util.Set;
import java.util.TreeSet;

/**
 * Created by loom on 23.02.16.
 */
public class PackageMetadata implements Comparable<PackageMetadata> {

    String packageName;
    String jarName;
    String projectUrl = null;
    Set<String> licenseKeys = new TreeSet<>();
    String copyright = null;

    public PackageMetadata() {
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getJarName() {
        return jarName;
    }

    public void setJarName(String jarName) {
        this.jarName = jarName;
    }

    public String getProjectUrl() {
        return projectUrl;
    }

    public void setProjectUrl(String projectUrl) {
        this.projectUrl = projectUrl;
    }

    public Set<String> getLicenseKeys() {
        return licenseKeys;
    }

    public void setLicenseKeys(Set<String> licenseKeys) {
        this.licenseKeys = licenseKeys;
    }

    public String getCopyright() {
        return copyright;
    }

    public void setCopyright(String copyright) {
        this.copyright = copyright;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PackageMetadata that = (PackageMetadata) o;

        return packageName.equals(that.packageName);

    }

    @Override
    public int hashCode() {
        return packageName.hashCode();
    }

    @Override
    public int compareTo(PackageMetadata o) {
        int packageNameCompare = packageName.compareTo(o.packageName);
        if (packageNameCompare != 0) {
            return packageNameCompare;
        }
        return jarName.compareTo(o.jarName);
    }
}
